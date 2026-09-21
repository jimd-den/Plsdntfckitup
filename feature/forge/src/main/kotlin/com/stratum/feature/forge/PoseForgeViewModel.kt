package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.ClipRow
import com.stratum.core.domain.ai.ClipRowRequest
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
import com.stratum.core.domain.character.CharacterRole
import com.stratum.core.domain.sprite.ClipSampling
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
import com.stratum.core.domain.bvh.BandaiNamcoMotionDataset
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
    /** Reads a frame back, so an opening pose already drawn is not paid for twice. */
    private val loadPose: (String, String) -> ByteArray?,
    /** Composites the set into a sheet and puts it in the sprite library. */
    private val composeSheet: (String, PoseSheetPlan) -> PackedSheet?,
    /** Characters already on disk, newest first, so one can be picked up again. */
    private val savedCharacters: () -> List<SavedCharacter>,
    private val deleteCharacter: (String) -> Unit,
    /** Writes the packed sheet out where the rest of the device can reach it. */
    private val exportSheet: (String, String) -> Boolean,
    /** Writes every full-size pose out as one archive. */
    private val exportPoses: (String, String) -> Boolean,
    /** Writes the T-pose the whole character is an edit of. */
    private val exportReference: (String, String) -> Boolean,
    /** Writes the clip an animation was cut from, which the sheet only samples. */
    private val exportClip: (String, String, String) -> Boolean,
    /** Which animations have a clip on disk and can be re-cut without paying again. */
    private val clipsDrawn: (String) -> Set<String>,
    /** Draws one animation as a clip and cuts a row of frames out of it. */
    private val drawClipRow: suspend (ClipRowRequest, GenerationObserver) -> Result<ClipRow>,
    /** Keeps the clip, so the rate can be changed later without paying again. */
    private val saveClip: (String, String, ByteArray) -> Unit,
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

    /**
     * Applies genuine optical motion capture reference frames from the Bandai Namco
     * Research Motiondataset across all animation states of the current script.
     */
    fun applyBandaiNamcoMocap() {
        val script = _state.value.script
        val referenceFrames = BandaiNamcoMotionDataset.buildReferenceScript(script)
        updateGuides { current ->
            current.copy(
                mode = PoseGuideMode.BANDAI_NAMCO,
                imported = current.imported + referenceFrames,
            )
        }
        _state.value = _state.value.copy(
            message = "Applied Bandai Namco mocap reference frames for all animations.",
            error = null,
        )
    }

    /**
     * Imports a BVH motion capture clip from raw text (such as clips from the Bandai
     * Namco Research Motiondataset) for the targeted pose step.
     */
    fun importGuideBvh(step: PoseStep, text: String) {
        val frameCount = _state.value.script.frameCounts()[step.state] ?: 6
        val result = BandaiNamcoMotionDataset.buildFromBvh(text, frameCount)
        val poses = result.getOrNull()
        if (poses == null || poses.isEmpty()) {
            val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
            _state.value = _state.value.copy(
                error = "Could not parse BVH motion capture data: $errorMsg",
            )
            return
        }
        val targetPose = poses.getOrNull(step.index) ?: poses.first()
        acceptImported(step, targetPose)
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
                BasePoseRequest(
                    subject = current.subject.trim(),
                    styleDirection = current.style,
                    promptOverride = current.promptOverride,
                ),
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
        // One button, two paths, chosen by the toggle beside it. Keeping the
        // choice here rather than in a second button means a person picks how
        // the character is drawn once, where the trade-off is written down,
        // rather than by which control they happened to press.
        if (_state.value.drawsFromClip) {
            drawFromClips()
            return
        }
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

            val currentSetId = setId
            val drawnNow = withContext(Dispatchers.IO) { posesDrawn(currentSetId) }
            var autoPackedSheet: SpriteSheet? = null
            var autoPackedMessage: String? = null

            if (abandoned == null && drawnNow.isNotEmpty()) {
                val hasExistingSheet = savedCharacters().firstOrNull { it.setId == currentSetId }?.sheetId != null
                if (!hasExistingSheet || failures.isEmpty()) {
                    val onDisk = current.scope.scriptFor(current.frames, PoseView.entries)
                    val counts = onDisk.drawnCounts(drawnNow)
                    val plan = PoseSheetPlanner.plan(
                        id = currentSetId,
                        name = current.subject.trim().ifBlank { "Character" },
                        frameCounts = counts,
                        cellSize = current.cellSize,
                        views = onDisk.drawnViews(drawnNow)
                            .ifEmpty { listOf(PoseView.FRONT) }
                            .map { it.keySuffix to it.serves },
                    )
                    if (plan != null) {
                        val packed = withContext(Dispatchers.Default) { composeSheet(currentSetId, plan) }
                        if (packed != null) {
                            autoPackedSheet = packed.sheet
                            autoPackedMessage = " Packed into a ${packed.sheet.columns}x${packed.sheet.rows} sheet — ready to wear on the home screen!"
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
                savedSheet = autoPackedSheet ?: _state.value.savedSheet,
                characters = savedCharacters(),
                message = when {
                    // Said plainly, because the failure is the same for every
                    // remaining frame and the person needs to fix one thing
                    // rather than read forty identical errors.
                    abandoned != null -> "Stopped after ${_state.value.completed} of " +
                        "${_state.value.total}. Nothing else would have worked either."
                    failures.isEmpty() -> "Every pose drawn.${autoPackedMessage ?: " Build the sheet."}"
                    else -> "${failures.size} pose${if (failures.size == 1) "" else "s"} " +
                        "failed. Run it again to retry just those.${autoPackedMessage ?: " Poses drawn so far can be packed into a sheet now."}"
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
                    characters = savedCharacters(),
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

    /**
     * How finely to cut a clip.
     *
     * Clamped to what reads as animation: below the floor it is a slideshow,
     * and above the ceiling the frames are closer together than the model drew
     * distinct ones, so the sheet grows without the walk improving.
     */
    fun setFrameRate(fps: Int) {
        _state.value = _state.value.copy(
            frameRate = fps.coerceIn(ClipSampling.MIN_FPS, ClipSampling.MAX_FPS),
        )
    }

    /** Chooses between drawing every frame and cutting them out of one clip. */
    fun setDrawsFromClip(on: Boolean) {
        _state.value = _state.value.copy(drawsFromClip = on)
    }

    /**
     * Draws each animation as a clip and cuts its row out.
     *
     * One request an animation rather than one a frame, which is the whole
     * difference: the frames of a row come out of a single generation, so they
     * cannot disagree about the costume or the scale the way separately drawn
     * ones do. The clip is kept, because changing the frame rate afterwards
     * should not cost anything.
     *
     * Runs on the same scope as the frame-by-frame path, for the same reason --
     * it is long enough that a person will leave the screen.
     */
    fun drawFromClips() {
        val current = _state.value
        val setId = current.setId
        if (current.busy) return
        if (setId == null || !current.hasReference) {
            _state.value = current.copy(error = "Draw the reference first.")
            return
        }
        val reference = loadReference(setId)
        if (reference == null) {
            _state.value = current.copy(error = "The reference could not be read.")
            return
        }

        val states = current.script.states
        _state.value = current.copy(busy = true, error = null, message = null, failures = emptyMap())

        job = PoseRun.start {
            var failures = emptyMap<String, String>()
            var drawn = 0

            states.forEachIndexed { index, state ->
                PoseRun.report(current.subject, index, states.size)

                // The clip is pinned to this, and for a cycle it is pinned to
                // it at both ends -- so it has to be the animation's own first
                // pose, not the reference. Handing over the T-pose asks the
                // model to start in a T-pose, finish in a T-pose and not move
                // the body between, and it obliges: the first version of this
                // passed the reference and every clip came back a T-pose held
                // for four seconds.
                val opening = current.script.stepsFor(state).firstOrNull()
                if (opening == null) {
                    failures = failures + (state.name.lowercase() to "no first pose to open on")
                    return@forEachIndexed
                }
                // Drawn under its stick figure exactly as the frame-by-frame
                // path draws it, and reused when it is already on disk, so
                // switching between the two paths does not pay for it twice.
                val openingBytes = withContext(Dispatchers.IO) {
                    loadPose(setId, opening.key) ?: runCatching {
                        drawPose(
                            PoseFrameRequest(
                                reference = ImageReference(reference),
                                step = opening,
                                guide = guideFor(opening, current.guides),
                                styleDirection = current.style,
                            ),
                            GenerationObserver.None,
                        ).getOrNull()?.bytes?.also { savePose(setId, opening.key, it) }
                    }.getOrNull()
                }
                if (openingBytes == null) {
                    failures = failures + (state.name.lowercase() to "the opening pose could not be drawn")
                    return@forEachIndexed
                }

                val result = drawClipRow(
                    ClipRowRequest(
                        state = state,
                        motion = state.clipMotion,
                        openingPose = ImageReference(openingBytes),
                        fps = current.frameRate,
                        styleDirection = current.style,
                    ),
                    GenerationObserver.None,
                )
                result.onSuccess { row ->
                    withContext(Dispatchers.IO) {
                        row.frames.forEach { (key, bytes) -> savePose(setId, key, bytes) }
                        saveClip(setId, state.name.lowercase(), row.clip.bytes)
                    }
                    drawn += row.frameCount
                }.onFailure { failure ->
                    failures = failures + (state.name.lowercase() to (failure.message ?: "failed"))
                }
            }

            _state.value = _state.value.copy(
                busy = false,
                drawn = posesDrawn(setId),
                clips = clipsDrawn(setId),
                failures = failures,
                message = if (drawn > 0) "Cut $drawn frames from ${states.size} clips." else null,
                error = if (drawn == 0) "No clip could be drawn." else null,
            )
        }
    }

    /** Edits the reference prompt. Blank clears back to the built-in one. */
    fun editBasePrompt(text: String) {
        _state.value = _state.value.copy(promptOverride = text.takeIf { it.isNotBlank() })
    }

    /** Puts the built-in prompt back, which is what clearing the field means. */
    fun resetBasePrompt() {
        _state.value = _state.value.copy(promptOverride = null, message = "Prompt reset.")
    }

    /**
     * Writes out the T-pose the character is built from.
     *
     * Separate from the sheet because it is not in the sheet: every animation
     * frame is an edit of this one drawing and none of them is it, so until
     * now the one image the character actually depends on was the one image
     * that could not leave.
     */
    fun exportReference() {
        val setId = _state.value.setId
        if (setId == null || !_state.value.hasReference) {
            _state.value = _state.value.copy(error = "There is no reference to export yet.")
            return
        }
        val name = _state.value.subject.trim().ifBlank { "character" }
        _state.value = if (exportReference(setId, name)) {
            _state.value.copy(message = "Reference exported.", error = null)
        } else {
            _state.value.copy(error = "The reference could not be exported.")
        }
    }

    /**
     * Writes out the clip an animation was cut from.
     *
     * The sheet is a sampling of this and a coarse one -- four seconds holds
     * nearly a hundred pictures and a twelve frame row takes ten. Anyone who
     * wants the motion rather than the grid, or who wants to re-cut it
     * somewhere better than a fixed cell, needs the clip.
     */
    fun exportClip(key: String) {
        val setId = _state.value.setId
        if (setId == null || key !in _state.value.clips) {
            _state.value = _state.value.copy(error = "There is no clip for that animation.")
            return
        }
        val name = _state.value.subject.trim().ifBlank { "character" }
        _state.value = if (exportClip(setId, name, key)) {
            _state.value.copy(message = "Clip exported.", error = null)
        } else {
            _state.value.copy(error = "The clip could not be exported.")
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

        /**
         * The rates offered.
         *
         * Twelve is the default and the one hand-drawn animation has used for a
         * century. Six is for a sheet that has to stay small, and twenty-four is
         * for motion smooth enough to bear slowing down -- past which the frames
         * are closer together than the model drew distinct ones.
         */
        val FRAME_RATES = listOf(6, 8, 12, 16, 24)

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
            loadPose: (String, String) -> ByteArray? = { _, _ -> null },
            composeSheet: (String, PoseSheetPlan) -> PackedSheet?,
            savedCharacters: () -> List<SavedCharacter> = { emptyList() },
            deleteCharacter: (String) -> Unit = {},
            exportSheet: (String, String) -> Boolean = { _, _ -> false },
            exportPoses: (String, String) -> Boolean = { _, _ -> false },
            drawClipRow: suspend (ClipRowRequest, GenerationObserver) -> Result<ClipRow> =
                { _, _ -> Result.failure(IllegalStateException("No video model is wired")) },
            saveClip: (String, String, ByteArray) -> Unit = { _, _, _ -> },
            exportReference: (String, String) -> Boolean = { _, _ -> false },
            exportClip: (String, String, String) -> Boolean = { _, _, _ -> false },
            clipsDrawn: (String) -> Set<String> = { emptySet() },
            isProviderConfigured: () -> Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PoseForgeViewModel(
                drawReference, drawPose, guideFor, readGuideImage, readGuideJson, loadGuides,
                saveGuides, saveReference, loadReference, hasReference, savePose, dropPose,
                posesDrawn, loadPose, composeSheet, savedCharacters, deleteCharacter, exportSheet,
                exportPoses, exportReference, exportClip, clipsDrawn, drawClipRow, saveClip,
                isProviderConfigured,
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
typealias CharacterRole = com.stratum.core.domain.character.CharacterRole

/**
 * The movement, in words, for a clip that has no stick figure to follow.
 *
 * Short and plain. A clip is given no per-frame guide, so this is the whole of
 * what it knows about the motion -- and a long description is worse than a
 * short one here, because every extra clause is something the model can decide
 * to illustrate with a camera move.
 */
private val AnimationState.clipMotion: String
    get() = when (this) {
        AnimationState.IDLE -> "A character standing still, breathing, weight settling"
        AnimationState.WALK -> "A steady walk cycle"
        AnimationState.ATTACK -> "One weapon swing, wind-up through follow-through"
        AnimationState.SPECIAL -> "Gathering, then throwing both arms wide"
        AnimationState.HURT -> "Taking a hit and staggering back"
        AnimationState.ROLL -> "A forward roll and back onto the feet"
        AnimationState.DIE -> "Collapsing to the ground and going still"
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
    /**
     * Frames a second a clip is cut into.
     *
     * The rate rather than the count, because a count is a number nobody can
     * reason about: twelve frames of a walk means nothing on its own, and
     * twelve frames a second means the walk plays at twelve frames a second.
     * How many cells the row ends up with falls out of the rate and the length
     * of the clip, which is also what lets the same clip be re-cut into a
     * different sheet without the model being asked anything again.
     */
    val frameRate: Int = ClipSampling.DEFAULT_FPS,
    /**
     * An edited reference prompt, or null for the built-in one.
     *
     * Null rather than a copy of the default, so the default can be improved
     * later without every character that never touched the field being stuck
     * on the old wording.
     */
    val promptOverride: String? = null,
    /** Animations with a clip saved, which can be re-cut for nothing. */
    val clips: Set<String> = emptySet(),
    /**
     * Whether each animation is drawn as one clip rather than frame by frame.
     *
     * Off by default, because the still path is the one that draws the authored
     * poses. A clip takes no per-frame stick figure, so its middle is whatever
     * the model thought walking looks like -- what it buys instead is that the
     * frames cannot disagree with each other, which separately drawn ones
     * measurably do.
     */
    val drawsFromClip: Boolean = false,
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

    /**
     * The prompt that would be sent, default or edited.
     *
     * What the editor shows when it opens. The default used to be unreachable
     * from here, so the one prompt every frame of every animation is an edit of
     * was the one prompt nobody could read.
     */
    val basePrompt: String
        get() = BasePoseRequest(
            subject = subject.trim(),
            styleDirection = style,
            promptOverride = promptOverride,
        ).prompt

    /** True once the prompt has been changed from the built-in one. */
    val basePromptEdited: Boolean get() = !promptOverride.isNullOrBlank()

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

    val canBuildSheet: Boolean get() = (completed > 0 || drawn.isNotEmpty()) && !busy

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
