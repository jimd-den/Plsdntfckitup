package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.BasePoseRequest
import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.PoseFrameRequest
import com.stratum.core.domain.ai.PoseRunPolicy
import com.stratum.core.domain.ai.PoseScript
import com.stratum.core.domain.ai.RunDecision
import com.stratum.core.domain.ai.PoseStep
import com.stratum.core.domain.ai.PoseView
import com.stratum.core.domain.ai.SavedCharacter
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.PackedSheet
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.PoseCell
import com.stratum.core.domain.sprite.PoseGuideMode
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.PoseGuides
import com.stratum.core.domain.sprite.PoseSheetPlan
import com.stratum.core.domain.sprite.PoseSheetPlanner
import com.stratum.core.domain.sprite.SpriteNamespace
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds a character one pose at a time.
 *
 * The shape of this screen follows from one fact: a full character is forty
 * calls to an image model, which is several minutes and several dollars. So
 * nothing is held in memory that could be on disk, every pose is written the
 * moment it arrives, and the run can be stopped and resumed at any frame
 * without losing what came before. A pipeline that had to start over on a
 * dropped connection would never finish once.
 */
class PoseForgeViewModel(
    private val drawReference: suspend (BasePoseRequest, GenerationObserver) -> Result<GeneratedImage>,
    private val drawPose: suspend (PoseFrameRequest, GenerationObserver) -> Result<GeneratedImage>,
    /**
     * A stick figure of the pose, handed to the model alongside the character.
     *
     * Null when a guide cannot be drawn, which is survivable: the prose
     * instruction still describes the pose, and the two agree because both come
     * from the same skeleton.
     */
    private val guideFor: (PoseStep, PoseGuides) -> ImageReference?,
    /** Reads an OpenPose skeleton out of a downloaded PNG, when it can. */
    private val readGuideImage: (ByteArray) -> Pose?,
    /** Reads OpenPose keypoints out of pasted JSON. */
    private val readGuideJson: (String) -> Pose?,
    private val loadGuides: (String) -> PoseGuides,
    private val saveGuides: (String, PoseGuides) -> Unit,
    private val saveReference: (String, ByteArray) -> Unit,
    private val loadReference: (String) -> ByteArray?,
    /** Cheap enough to ask on every keystroke, unlike reading the file. */
    private val hasReference: (String) -> Boolean,
    private val savePose: (String, String, ByteArray) -> Unit,
    private val dropPose: (String, String) -> Unit,
    private val posesDrawn: (String) -> Set<String>,
    /** Composites the set into a sheet and puts it in the sprite library. */
    private val composeSheet: (String, PoseSheetPlan) -> PackedSheet?,
    /** Characters already on disk, newest first, so one can be picked up again. */
    private val savedCharacters: () -> List<SavedCharacter>,
    private val deleteCharacter: (String) -> Unit,
    /** Writes the packed sheet out where the rest of the device can reach it. */
    private val exportSheet: (String, String) -> Boolean,
    /** Writes every full-size pose out as one archive. */
    private val exportPoses: (String, String) -> Boolean,
    private val isProviderConfigured: () -> Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PoseForgeUiState(
            providerConfigured = isProviderConfigured(),
            characters = savedCharacters(),
        ),
    )
    val state: StateFlow<PoseForgeUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun refresh() {
        val current = _state.value
        _state.value = current.copy(
            providerConfigured = isProviderConfigured(),
            hasReference = current.setId?.let(hasReference) ?: false,
            drawn = current.setId?.let(posesDrawn).orEmpty(),
            characters = savedCharacters(),
        )
    }

    fun updateSubject(subject: String) {
        val setId = setIdFor(subject, _state.value.role)
        val poses = setId?.let(posesDrawn).orEmpty()
        _state.value = _state.value.copy(
            subject = subject,
            drawsAwayView = poses.anyAway() || _state.value.drawsAwayView,
            setId = setId,
            // Typing a subject that was worked on before finds its poses again,
            // which is what makes coming back to a character cheap. Asked as an
            // existence check rather than a read: this runs on every keystroke,
            // and the reference is a megabyte.
            hasReference = setId?.let(hasReference) ?: false,
            drawn = setId?.let(posesDrawn).orEmpty(),
            // A character's poses travel with it: what its art was drawn
            // against is what its weapon must be rigged against.
            guides = setId?.let(loadGuides) ?: PoseGuides(),
            savedSheet = null,
        )
    }

    // ---- where the poses come from ---------------------------------------

    fun selectGuideMode(mode: PoseGuideMode) = updateGuides { it.copy(mode = mode) }

    fun selectGuideStyle(style: PoseGuideStyle) = updateGuides { it.copy(style = style) }

    /**
     * Takes a skeleton out of a downloaded pose PNG.
     *
     * What pose libraries actually hand you is the rendered skeleton, not its
     * keypoints — so the picture is read back to find the joints. It can fail,
     * and saying so matters: without joints the image is still a perfectly good
     * guide for the drawing, but the weapon has nothing to hang from.
     */
    fun importGuideImage(step: PoseStep, bytes: ByteArray) {
        val pose = readGuideImage(bytes)
        if (pose == null) {
            _state.value = _state.value.copy(
                // Named precisely, because the common cause is not a wrong
                // file. Reading a rendered skeleton back means matching the
                // canonical OpenPose palette, and libraries draw their previews
                // in whatever colours they like -- one checked ships Material
                // blues and pinks. Its keypoints are right there in the page,
                // and pasting those is exact where reading pixels is a guess.
                error = "No OpenPose skeleton could be read from that image. Many libraries " +
                    "draw their skeletons in their own colours, which cannot be read back. " +
                    "Paste the pose's JSON keypoints instead — that is exact.",
            )
            return
        }
        acceptImported(step, pose)
    }

    fun importGuideJson(step: PoseStep, text: String) {
        val pose = readGuideJson(text)
        if (pose == null) {
            _state.value = _state.value.copy(
                error = "That is not an OpenPose file, or it has no wrist on the weapon side.",
            )
            return
        }
        acceptImported(step, pose)
    }

    fun clearImported(step: PoseStep) = updateGuides { it.withoutImported(step.key) }

    private fun acceptImported(step: PoseStep, pose: Pose) {
        updateGuides { it.withImported(step.key, pose) }
        _state.value = _state.value.copy(
            message = "Pose imported for ${step.key}. Redraw that frame to use it.",
            error = null,
        )
    }

    private fun updateGuides(change: (PoseGuides) -> PoseGuides) {
        val next = change(_state.value.guides)
        _state.value.setId?.let { saveGuides(it, next) }
        _state.value = _state.value.copy(guides = next)
    }

    fun updateStyle(style: String) {
        _state.value = _state.value.copy(style = style)
    }

    /**
     * Changes what the character is for, and moves it.
     *
     * The role is part of the id, so switching it points at a different set.
     * Re-reading what is on disk under the new id is the honest thing to do:
     * the alternative is a screen showing forty drawn poses that the next
     * generation will not find.
     */
    fun selectRole(role: CharacterRole) {
        val current = _state.value
        val setId = setIdFor(current.subject, role)
        val moved = setId?.let(posesDrawn).orEmpty()
        // Said, because the drawn count is about to change under them. The
        // role is part of the id, so switching it points at a different set --
        // and a screen that silently went from forty poses to none reads as
        // having deleted them.
        val note = when {
            setId == null -> null
            current.drawn.isNotEmpty() && moved.isEmpty() ->
                "That is a different character: ${role.label.lowercase()} art is filed " +
                    "separately. The ${current.drawn.size} pose(s) you drew are still there " +
                    "under the other one."
            else -> null
        }
        _state.value = current.copy(
            message = note,
            role = role,
            setId = setId,
            hasReference = setId?.let(hasReference) ?: false,
            drawn = setId?.let(posesDrawn).orEmpty(),
            guides = setId?.let(loadGuides) ?: PoseGuides(),
            savedSheet = null,
        )
    }

    /**
     * Sets how many frames one animation gets.
     *
     * Only ever changes that one animation. Frames already drawn are left
     * alone: the sampling takes instructions from the start of the cycle, so
     * frame zero of a four frame walk and of a twelve frame walk are the same
     * pose, and raising the count adds work rather than invalidating it.
     */
    fun selectFrames(state: AnimationState, count: Int) {
        val wanted = count.coerceIn(PoseScript.MIN_FRAMES, PoseScript.MAX_FRAMES)
        _state.value = _state.value.copy(
            frames = _state.value.frames + (state to wanted),
        )
    }

    fun toggleAwayView(on: Boolean) {
        _state.value = _state.value.copy(drawsAwayView = on)
    }

    fun selectScope(scope: PoseScope) {
        _state.value = _state.value.copy(scope = scope)
    }

    fun selectCellSize(pixels: Int) {
        _state.value = _state.value.copy(
            cellSize = pixels.coerceIn(PoseSheetPlanner.MIN_CELL, PoseSheetPlanner.MAX_CELL),
        )
    }

    /**
     * Draws the reference the whole character is edited out of.
     *
     * Deliberately its own step with its own button. It is the one image worth
     * looking at before spending forty more on it — every later frame inherits
     * this character's face, palette and armour, so a reference that came back
     * wrong is worth another attempt before the expensive part begins.
     */
    fun drawReferencePose() {
        val current = _state.value
        val setId = current.setId
        if (setId == null || current.subject.isBlank()) {
            _state.value = current.copy(error = "Describe the character first.")
            return
        }
        if (!isProviderConfigured()) {
            _state.value = current.copy(error = "Add a provider API key in settings first.")
            return
        }

        _state.value = current.copy(busy = true, error = null, message = null, savedSheet = null)
        job = viewModelScope.launch {
            val result = drawReference(
                BasePoseRequest(subject = current.subject.trim(), styleDirection = current.style),
                GenerationObserver.None,
            )
            _state.value = result.fold(
                onSuccess = { image ->
                    saveReference(setId, image.bytes)
                    _state.value.copy(
                        busy = false,
                        hasReference = true,
                        message = "Reference drawn. Check it before building the animations — " +
                            "every frame inherits this character.",
                    )
                },
                onFailure = { cause ->
                    _state.value.copy(busy = false, error = cause.message ?: "The reference failed.")
                },
            )
        }
    }

    /**
     * Works through the script, one pose at a time.
     *
     * Sequential rather than parallel, and not only to be polite to a rate
     * limit. A person watching forty frames appear wants to stop when the third
     * one comes back wrong, and eight in flight at once means eight more
     * arriving after they have already decided to stop.
     */
    fun buildAnimations() {
        val current = _state.value
        val setId = current.setId
        if (setId == null) {
            _state.value = current.copy(error = "Describe the character first.")
            return
        }
        val referenceBytes = loadReference(setId)
        if (referenceBytes == null) {
            _state.value = current.copy(error = "Draw the reference pose first.")
            return
        }
        if (!isProviderConfigured()) {
            _state.value = current.copy(error = "Add a provider API key in settings first.")
            return
        }

        val reference = ImageReference(referenceBytes)
        val guides = current.guides
        val style = current.style
        val script = current.script
        val todo = script.remaining(posesDrawn(setId))
        if (todo.isEmpty()) {
            _state.value = current.copy(message = "Every pose is already drawn. Build the sheet.")
            return
        }

        _state.value = current.copy(
            busy = true,
            error = null,
            message = null,
            failures = emptyMap(),
            savedSheet = null,
        )
        // The one run that outlives the screen. The reference is a single
        // request and the sheet pack is seconds of work, so those stay on the
        // view model's own scope -- this is the quarter of an hour, and it is
        // the only one worth surviving a person leaving the forge.
        job = PoseRun.start {
            var failures = emptyMap<String, String>()
            var abandoned: String? = null

            steps@ for (step in todo) {
                var attempt = 1
                while (true) {
                    ensureActive()
                    _state.value = _state.value.copy(
                        currentStep = step,
                        attempt = attempt,
                        waitingMs = 0L,
                    )

                    // Everything here touches the network or the disk: a
                    // megabyte written per frame, a guide rendered per frame,
                    // and a directory listed per frame. On the main thread that
                    // is forty stutters at best.
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            drawPose(
                                PoseFrameRequest(
                                    reference = reference,
                                    step = step,
                                    guide = guideFor(step, guides),
                                    styleDirection = style,
                                ),
                                GenerationObserver.None,
                            )
                        }.getOrElse { cause ->
                            if (cause is CancellationException) throw cause
                            Result.failure(cause)
                        }
                    }

                    val image = result.getOrNull()
                    if (image != null) {
                        withContext(Dispatchers.IO) { savePose(setId, step.key, image.bytes) }
                        val done = withContext(Dispatchers.IO) { posesDrawn(setId) }
                        _state.value = _state.value.copy(drawn = done, attempt = 1)
                        // Reported for the notification, which is the only
                        // thing a person can see once they have left the app.
                        PoseRun.report(
                            label = current.subject.trim().ifBlank { "Character" },
                            done = script.steps.count { it.key in done },
                            total = script.steps.size,
                        )
                        continue@steps
                    }

                    val cause = result.exceptionOrNull() ?: GenerationException("failed")
                    when (PoseRunPolicy.decide(attempt, cause)) {
                        RunDecision.RETRY -> {
                            // The expected failure at this volume, not an edge
                            // case: forty requests in a row will meet a rate
                            // limit, and it clears by waiting.
                            val wait = PoseRunPolicy.backoffMillis(attempt)
                            _state.value = _state.value.copy(
                                waitingMs = wait,
                                message = "${cause.message} Retrying ${step.key} in " +
                                    "${wait / 1000}s.",
                            )
                            delay(wait)
                            attempt++
                        }

                        RunDecision.SKIP -> {
                            // One bad frame does not end the run. Thirty-nine
                            // good poses and a list of which to retry is a far
                            // better place to be than nothing.
                            failures = failures + (step.key to (cause.message ?: "failed"))
                            _state.value = _state.value.copy(failures = failures)
                            continue@steps
                        }

                        RunDecision.ABANDON -> {
                            abandoned = cause.message ?: "The provider refused the run."
                            break@steps
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                busy = false,
                currentStep = null,
                attempt = 1,
                waitingMs = 0L,
                error = abandoned,
                message = when {
                    // Said plainly, because the failure is the same for every
                    // remaining frame and the person needs to fix one thing
                    // rather than read forty identical errors.
                    abandoned != null -> "Stopped after ${_state.value.completed} of " +
                        "${_state.value.total}. Nothing else would have worked either."
                    failures.isEmpty() -> "Every pose drawn. Build the sheet."
                    else -> "${failures.size} pose${if (failures.size == 1) "" else "s"} " +
                        "failed. Run it again to retry just those."
                },
            )
        }
    }

    /** Throws one pose away so the next run draws it again. */
    fun redrawPose(key: String) {
        val setId = _state.value.setId ?: return
        dropPose(setId, key)
        _state.value = _state.value.copy(
            drawn = posesDrawn(setId),
            failures = _state.value.failures - key,
            savedSheet = null,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        // Also the run itself, which no longer belongs to this scope: without
        // this, Stop would clear the screen and leave the generations going.
        PoseRun.stop()
        _state.value = _state.value.copy(
            busy = false,
            currentStep = null,
            message = "Stopped. The poses already drawn are kept.",
        )
    }

    /**
     * Packs what has been drawn into a sheet.
     *
     * Allowed before the set is complete on purpose. A character with an idle
     * and a walk is playable, and being able to see it in the world after eight
     * generations rather than forty is the difference between a pipeline
     * someone uses and one they read about.
     */
    fun buildSheet() {
        val current = _state.value
        val setId = current.setId ?: return
        val drawn = posesDrawn(setId)
        if (drawn.isEmpty()) {
            _state.value = current.copy(error = "No poses have been drawn yet.")
            return
        }

        // Counted against every angle this character *could* have, not the
        // ones the toggle happens to be showing.
        //
        // The script is built from the toggle, and the toggle is UI state: it
        // is off by default and nothing restored it when a character was
        // reopened. So a set generated with away frames, closed and opened
        // again, packed as front-only -- the away art was on disk, was paid
        // for, and the sheet quietly left it out. What exists on disk is the
        // only honest answer to what the sheet should contain.
        val onDisk = current.scope.scriptFor(current.frames, PoseView.entries)

        // Only the states that actually have frames, and only as many as
        // arrived: a row planned for four and given two would leave two cells
        // of nothing in the middle of the animation. Counted per view by the
        // script itself; the view model used to do this arithmetic too, and
        // having two copies is how it came to be right in one and wrong in
        // the other.
        val counts = onDisk.drawnCounts(drawn)

        val plan = PoseSheetPlanner.plan(
            id = setId,
            name = current.subject.trim().ifBlank { "Character" },
            frameCounts = counts,
            cellSize = current.cellSize,
            // Only the angles that actually came back. Planning a block of
            // rows for an away view nobody drew would leave the bottom half
            // of the sheet empty and the renderer would walk the character
            // north as a hole in the world.
            views = onDisk.drawnViews(drawn)
                .ifEmpty { listOf(PoseView.FRONT) }
                .map { it.keySuffix to it.serves },
        )
        if (plan == null) {
            _state.value = current.copy(error = "There is nothing to pack yet.")
            return
        }

        _state.value = current.copy(busy = true, error = null)
        job = viewModelScope.launch {
            // Decoding, keying and downscaling forty 1024-pixel images is
            // seconds of work. On the main thread that is not a slow screen, it
            // is a frozen one.
            val packed = withContext(Dispatchers.Default) { composeSheet(setId, plan) }
            _state.value = if (packed == null) {
                _state.value.copy(busy = false, error = "The sheet could not be written.")
            } else {
                val sheet = packed.sheet
                _state.value.copy(
                    busy = false,
                    savedSheet = sheet,
                    message = buildString {
                        append("Saved as a ${sheet.columns}x${sheet.rows} sheet at ")
                        // The frame size the sheet actually came out at, which
                        // is not the size that was asked for: the width is cut
                        // to the figure's proportions after measuring it.
                        append("${sheet.frameWidth}x${sheet.frameHeight} a frame. ")
                        // A hole in an animation looks exactly like a frame the
                        // character is invisible for, so it is named rather
                        // than left to be noticed in the world.
                        if (packed.missing.isNotEmpty()) {
                            append("${packed.missing.size} pose(s) could not be read and left ")
                            append("empty cells: ${packed.missing.joinToString()}. ")
                        }
                        append("Open it in the frame mapper to adjust it.")
                    },
                )
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    /**
     * A character's poses live under an id derived from what it is, so typing
     * the same description again finds the same set rather than paying for it
     * twice.
     */
    /**
     * Opens a character that is already on disk.
     *
     * Typing the subject again used to be the only way back to a set, because
     * the id is derived from it -- so a character was reachable only by
     * remembering, exactly, what it had been called. Forty generations of work
     * behind a spelling test.
     */
    fun openCharacter(character: SavedCharacter) {
        val poses = posesDrawn(character.setId)
        _state.value = _state.value.copy(
            subject = character.name,
            setId = character.setId,
            // Read off the poses rather than left as whatever the toggle was:
            // a character with away frames should say so when it is opened,
            // not look like one that never had any.
            drawsAwayView = poses.anyAway(),
            // Read back off the id rather than left as whatever was last
            // picked: opening an enemy and then generating would otherwise
            // write the next frames into the hero's set.
            role = if (SpriteNamespace.servesMonster(character.setId)) {
                CharacterRole.ENEMY
            } else {
                CharacterRole.HERO
            },
            hasReference = hasReference(character.setId),
            drawn = posesDrawn(character.setId),
            guides = loadGuides(character.setId),
            savedSheet = null,
            failures = emptyMap(),
            message = null,
            error = null,
        )
    }

    fun forgetCharacter(setId: String) {
        deleteCharacter(setId)
        val current = _state.value
        val cleared = current.setId == setId
        _state.value = current.copy(
            characters = savedCharacters(),
            setId = if (cleared) null else current.setId,
            subject = if (cleared) "" else current.subject,
            hasReference = if (cleared) false else current.hasReference,
            drawn = if (cleared) emptySet() else current.drawn,
            savedSheet = if (cleared) null else current.savedSheet,
            message = "Deleted.",
        )
    }

    /**
     * Hands the packed sheet to the device.
     *
     * Only offered once a sheet has been packed: exporting the set before that
     * would write whatever the last pack produced, which may be nothing or may
     * be several runs old, and neither is what the button appears to promise.
     */
    fun exportSheet() {
        val sheet = _state.value.savedSheet
        if (sheet == null) {
            _state.value = _state.value.copy(error = "Pack the sheet first, then export it.")
            return
        }
        val name = _state.value.subject.trim().ifBlank { sheet.name }
        _state.value = if (exportSheet(sheet.id, name)) {
            _state.value.copy(message = "Sheet exported.", error = null)
        } else {
            _state.value.copy(error = "The sheet could not be exported.")
        }
    }

    fun exportPoses() {
        val setId = _state.value.setId
        if (setId == null || _state.value.drawn.isEmpty()) {
            _state.value = _state.value.copy(error = "There are no poses to export yet.")
            return
        }
        val name = _state.value.subject.trim().ifBlank { "character" }
        _state.value = if (exportPoses(setId, name)) {
            _state.value.copy(message = "Poses exported.", error = null)
        } else {
            _state.value.copy(error = "The poses could not be exported.")
        }
    }

    /** Whether any of these pose keys is an away frame. */
    private fun Set<String>.anyAway(): Boolean =
        any { it.endsWith(PoseView.AWAY.keySuffix) }

    private fun setIdFor(subject: String, role: CharacterRole): String? {
        val slug = subject.lowercase().replace(NON_ID, "_").trim('_').take(MAX_SLUG)
        return if (slug.isBlank()) null else "${role.namespace}$slug"
    }

    companion object {
        private val NON_ID = Regex("[^a-z0-9]+")
        private const val MAX_SLUG = 32

        /** Frame sizes worth packing down to, at this camera. */
        /**
         * Frame heights offered, not frame sizes: the width is taken from the
         * art once it has been measured, so it is not a choice to make here.
         */
        val CELL_SIZES = listOf(96, 128, 192, 256, 384)

        fun factory(
            drawReference: suspend (BasePoseRequest, GenerationObserver) -> Result<GeneratedImage>,
            drawPose: suspend (PoseFrameRequest, GenerationObserver) -> Result<GeneratedImage>,
            guideFor: (PoseStep, PoseGuides) -> ImageReference?,
            readGuideImage: (ByteArray) -> Pose?,
            readGuideJson: (String) -> Pose?,
            loadGuides: (String) -> PoseGuides,
            saveGuides: (String, PoseGuides) -> Unit,
            saveReference: (String, ByteArray) -> Unit,
            loadReference: (String) -> ByteArray?,
            hasReference: (String) -> Boolean,
            savePose: (String, String, ByteArray) -> Unit,
            dropPose: (String, String) -> Unit,
            posesDrawn: (String) -> Set<String>,
            composeSheet: (String, PoseSheetPlan) -> PackedSheet?,
            savedCharacters: () -> List<SavedCharacter> = { emptyList() },
            deleteCharacter: (String) -> Unit = {},
            exportSheet: (String, String) -> Boolean = { _, _ -> false },
            exportPoses: (String, String) -> Boolean = { _, _ -> false },
            isProviderConfigured: () -> Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PoseForgeViewModel(
                drawReference, drawPose, guideFor, readGuideImage, readGuideJson, loadGuides,
                saveGuides, saveReference, loadReference, hasReference, savePose, dropPose,
                posesDrawn, composeSheet, savedCharacters, deleteCharacter, exportSheet,
                exportPoses, isProviderConfigured,
            ) as T
        }
    }
}

/** How much of a character to draw. Every state is more calls and more money. */
/**
 * What a character is being drawn for.
 *
 * Separate from [PoseScope], which says how many animations to draw. They
 * correlate -- an enemy usually needs fewer -- but they are not the same
 * question, and answering both with one control is what produced a world in
 * which every monster wore the player's face. The choice is written into the
 * set id, so the art is filed where the game looks for that kind of actor.
 */
enum class CharacterRole(val label: String, val namespace: String) {
    HERO("Hero", SpriteNamespace.HERO),
    ENEMY("Enemy", SpriteNamespace.MONSTER),
}

/**
 * How many animations to draw.
 *
 * Labelled by what it does rather than by who it is for. It used to be
 * "Enemy" and "Full character", sitting one row above a Hero/Enemy chip row
 * that decides something else entirely -- two chips reading "Enemy", side by
 * side, controlling different things. Tapping either looked like the other had
 * changed on its own.
 */
enum class PoseScope(val label: String) {
    /** Idle, walk, attack, death: what an enemy is actually seen doing. */
    ENEMY("4 animations"),

    /** Everything, for the character a player looks at all session. */
    FULL("All 7");

    /** The script for this scope at the frame counts and angles the person chose. */
    fun scriptFor(
        frames: Map<AnimationState, Int>,
        views: List<PoseView>,
    ): PoseScript = when (this) {
        ENEMY -> PoseScript.enemy(frames, views)
        FULL -> PoseScript.full(frames, views)
    }

    val states: List<AnimationState>
        get() = when (this) {
            ENEMY -> listOf(
                AnimationState.IDLE,
                AnimationState.WALK,
                AnimationState.ATTACK,
                AnimationState.DIE,
            )
            FULL -> AnimationState.generatedRowOrder
        }
}

data class PoseForgeUiState(
    val subject: String = "",
    val style: String = "",
    val scope: PoseScope = PoseScope.ENEMY,
    /**
     * Whether this character is the player's or something it meets.
     *
     * A hero by default. It used to default to enemy, which meant a character
     * drawn by somebody who never touched the chip was filed under the monster
     * namespace -- and the main screen, which lists art a player can wear,
     * never saw it. The commonest thing to make is the character you play, and
     * an enemy is the deliberate choice.
     */
    val role: CharacterRole = CharacterRole.HERO,
    /**
     * How many frames each animation gets.
     *
     * Per state rather than one number, because the states do not need the
     * same count: an idle is watched for minutes and a death is seen once.
     */
    val frames: Map<AnimationState, Int> = emptyMap(),
    /**
     * Whether the away-facing angle is drawn too.
     *
     * Off by default: it doubles the cost and the time of a character, and a
     * character with only a front is perfectly playable -- it simply walks
     * north with its face towards you.
     */
    val drawsAwayView: Boolean = false,
    val cellSize: Int = PoseSheetPlanner.DEFAULT_CELL,
    val setId: String? = null,
    val hasReference: Boolean = false,
    /** Pose keys already on disk. */
    val drawn: Set<String> = emptySet(),
    val busy: Boolean = false,
    val currentStep: PoseStep? = null,
    /** Which attempt at the current frame, counting from 1. */
    val attempt: Int = 1,
    /** How long this wait is, while riding out a rate limit. Zero when running. */
    val waitingMs: Long = 0L,
    val failures: Map<String, String> = emptyMap(),
    val savedSheet: SpriteSheet? = null,
    val providerConfigured: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Which poses this character is drawn against, and how they are drawn. */
    val guides: PoseGuides = PoseGuides(),
    /** Every character on disk, so one can be picked up without retyping it. */
    val characters: List<SavedCharacter> = emptyList(),
) {
    val views: List<PoseView>
        get() = if (drawsAwayView) listOf(PoseView.FRONT, PoseView.AWAY) else listOf(PoseView.FRONT)

    val script: PoseScript get() = scope.scriptFor(frames, views)

    /** How many frames [state] is set to, falling back to the default. */
    fun framesFor(state: AnimationState): Int =
        frames[state] ?: PoseScript.DEFAULT_FRAMES

    /** Steps with an imported pose behind them rather than a built-in one. */
    fun isImported(step: PoseStep): Boolean = step.key in guides.imported

    val importedCount: Int get() = script.steps.count { it.key in guides.imported }

    val total: Int get() = script.steps.size

    val completed: Int get() = script.steps.count { it.key in drawn }

    val progress: Float get() = script.progress(drawn)

    val canBuildAnimations: Boolean get() = hasReference && !busy && providerConfigured

    val canBuildSheet: Boolean get() = completed > 0 && !busy

    /** What is being drawn right now, in the words the model was given. */
    val currentLabel: String?
        get() = currentStep?.let {
            val name = "${it.state.name.lowercase()} ${it.index + 1}"
            when {
                waitingMs > 0L -> "$name — waiting ${waitingMs / 1000}s"
                attempt > 1 -> "$name — attempt $attempt"
                else -> name
            }
        }

    fun statesWithFrames(): List<AnimationState> =
        script.states.filter { state -> script.stepsFor(state).any { it.key in drawn } }
}
