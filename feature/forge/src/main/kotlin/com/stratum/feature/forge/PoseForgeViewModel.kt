package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.BasePoseRequest
import com.stratum.core.domain.ai.CharacterSetId
import com.stratum.core.domain.ai.ClipMotion
import com.stratum.core.domain.ai.ClipRow
import com.stratum.core.domain.ai.ClipRowRequest
import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.PoseFrameRequest
import com.stratum.core.domain.ai.PoseFrameRun
import com.stratum.core.domain.ai.PosePacking
import com.stratum.core.domain.ai.PoseRunEvent
import com.stratum.core.domain.ai.PoseRunRecord
import com.stratum.core.domain.ai.PoseScript
import com.stratum.core.domain.ai.PoseStep
import com.stratum.core.domain.ai.PoseView
import com.stratum.core.domain.ai.RunOutcome
import com.stratum.core.domain.ai.SavedCharacter
import com.stratum.core.domain.bvh.BandaiNamcoMotionDataset
import com.stratum.core.domain.character.CharacterRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.ClipSampling
import com.stratum.core.domain.sprite.FrameAnalysis
import com.stratum.core.domain.sprite.FrameQuality
import com.stratum.core.domain.sprite.PackedSheet
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.PoseCell
import com.stratum.core.domain.sprite.PoseGuideMode
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.PoseGuides
import com.stratum.core.domain.sprite.PoseSheetPlan
import com.stratum.core.domain.sprite.PoseSheetPlanner
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * nothing is held in memory that could be on disk — every pose is written the
 * moment it arrives, and what the run was *for* is written as a
 * [PoseRunRecord] the moment it starts — and a run can be stopped, killed with
 * the process, and resumed at any frame without losing what came before.
 *
 * Deliberately thin. The loop and its retry, redraw and abandon rules are
 * [PoseFrameRun]; what a bad frame looks like is [FrameQuality]; which sheet a
 * set packs into is [PosePacking]; where a character is filed is
 * [CharacterSetId]. All of those are tested without a device. What is left
 * here is the state the screen shows and the order things happen in.
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
    /**
     * Decodes, keys and measures a frame, or null when it cannot be read.
     *
     * Defaulted to "cannot tell" so a caller without a decoder gets the old
     * behaviour of keeping whatever comes back.
     */
    private val inspectPose: (ByteArray) -> FrameAnalysis? = { null },
    /** Keeps what a run is for beside its poses, so a killed run can be resumed. */
    private val saveRunRecord: (PoseRunRecord) -> Unit = {},
    private val loadRunRecord: (String) -> PoseRunRecord? = { null },
    /** Every character's record, newest first, to find a run the process died in. */
    private val runRecords: () -> List<PoseRunRecord> = { emptyList() },
    /** Where the long runs live; the application's scope in production. */
    private val launchRun: (suspend CoroutineScope.() -> Unit) -> Job = PoseRun::start,
    private val reportProgress: (String, Int, Int) -> Unit = PoseRun::report,
    private val stopRun: () -> Unit = PoseRun::stop,
    private val isRunning: () -> Boolean = { PoseRun.running.value },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PoseForgeUiState(
            providerConfigured = isProviderConfigured(),
            characters = savedCharacters(),
            // A run marked active on disk while none is running in this
            // process is one the system killed. Offered, never started on its
            // own: resuming spends money, and that is the person's decision.
            interruptedRun = if (isRunning()) null else PoseRunRecord.interrupted(runRecords()),
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
        val setId = CharacterSetId.of(subject, _state.value.role)
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
            drawn = poses,
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
                // file: libraries draw their previews in their own colours,
                // and pasting the keypoints is exact where reading pixels is a
                // guess.
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
        val referenceFrames = BandaiNamcoMotionDataset.buildReferenceScript(_state.value.script)
        updateGuides { current ->
            current.copy(mode = PoseGuideMode.BANDAI_NAMCO, imported = current.imported + referenceFrames)
        }
        _state.value = _state.value.copy(
            message = "Applied Bandai Namco mocap reference frames for all animations.",
            error = null,
        )
    }

    /** Imports a BVH motion capture clip from raw text for the targeted pose step. */
    fun importGuideBvh(step: PoseStep, text: String) {
        val result = BandaiNamcoMotionDataset.buildFromBvh(text, step.frameCount)
        val poses = result.getOrNull()
        if (poses.isNullOrEmpty()) {
            _state.value = _state.value.copy(
                error = "Could not parse BVH motion capture data: " +
                    (result.exceptionOrNull()?.message ?: "Unknown error"),
            )
            return
        }
        acceptImported(step, poses.getOrNull(step.index) ?: poses.first())
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
        val setId = CharacterSetId.of(current.subject, role)
        val moved = setId?.let(posesDrawn).orEmpty()
        // Said, because the drawn count is about to change under them, and a
        // screen that silently went from forty poses to none reads as having
        // deleted them.
        val note = if (setId != null && current.drawn.isNotEmpty() && moved.isEmpty()) {
            "That is a different character: ${role.label.lowercase()} art is filed " +
                "separately. The ${current.drawn.size} pose(s) you drew are still there " +
                "under the other one."
        } else {
            null
        }
        _state.value = current.copy(
            message = note,
            role = role,
            setId = setId,
            hasReference = setId?.let(hasReference) ?: false,
            drawn = moved,
            guides = setId?.let(loadGuides) ?: PoseGuides(),
            savedSheet = null,
        )
    }

    /**
     * Sets how many frames one animation gets.
     *
     * Only ever changes that one animation. Frames already drawn are left
     * alone: the sampling takes instructions from the start of the cycle, so
     * raising the count adds work rather than invalidating it.
     */
    fun selectFrames(state: AnimationState, count: Int) {
        val wanted = count.coerceIn(PoseScript.MIN_FRAMES, PoseScript.MAX_FRAMES)
        _state.value = _state.value.copy(frames = _state.value.frames + (state to wanted))
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
     * looking at before spending forty more on it.
     */
    fun drawReferencePose() {
        val current = _state.value
        if (current.busy) return
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
                    withContext(io) { saveReference(setId, image.bytes) }
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
        // One button, two paths, chosen by the toggle beside it.
        if (_state.value.drawsFromClip) {
            drawFromClips()
            return
        }
        val current = _state.value
        if (current.busy) return
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

        // Written before the first request, so a process killed on frame two
        // still knows it was drawing twelve walk frames with an away view.
        val record = recordOf(current, previous = loadRunRecord(setId)).copy(active = true)
        saveRunRecord(record)

        _state.value = current.copy(
            busy = true,
            error = null,
            message = null,
            failures = emptyMap(),
            flagged = emptyMap(),
            savedSheet = null,
            interruptedRun = null,
        )
        val label = current.subject.trim().ifBlank { "Character" }

        // The one run that outlives the screen: the quarter of an hour, and
        // the only work worth surviving a person leaving the forge.
        job = launchRun {
            // Measured once, so every frame can be checked against it: a frame
            // the model handed back untouched is the reference, and a frame a
            // third of its size is a distant figure, not a pose.
            val referenceAnalysis = withContext(compute) { inspectPose(referenceBytes) }
            val run = PoseFrameRun(
                draw = { step, take ->
                    withContext(io) {
                        drawPose(
                            PoseFrameRequest(
                                reference = reference,
                                step = step,
                                guide = guideFor(step, guides),
                                styleDirection = style,
                                take = take,
                            ),
                            GenerationObserver.None,
                        )
                    }
                },
                inspect = { step, bytes ->
                    inspectPose(bytes)?.let { FrameQuality.inspect(it, referenceAnalysis, step.state) }.orEmpty()
                },
                save = { step, bytes ->
                    withContext(io) { savePose(setId, step.key, bytes) }
                    val done = withContext(io) { posesDrawn(setId) }
                    _state.value = _state.value.copy(drawn = done, attempt = 1)
                    // Reported for the notification, which is the only thing a
                    // person can see once they have left the app.
                    reportProgress(label, script.steps.count { it.key in done }, script.steps.size)
                },
                sleep = sleep,
                onEvent = ::onRunEvent,
            )
            val outcome = try {
                run.run(todo, record.takes)
            } catch (cancelled: CancellationException) {
                // Stopped on purpose: nothing to resume.
                saveRunRecord(record.copy(active = false))
                throw cancelled
            }
            saveRunRecord(record.finished(outcome))
            finishRun(setId, label, outcome)
        }
    }

    private fun onRunEvent(event: PoseRunEvent) {
        _state.value = when (event) {
            is PoseRunEvent.Drawing -> _state.value.copy(
                currentStep = event.step,
                attempt = event.attempt,
                waitingMs = 0L,
            )
            // The expected failure at this volume, not an edge case: forty
            // requests in a row will meet a rate limit, and it clears by waiting.
            is PoseRunEvent.Waiting -> _state.value.copy(
                waitingMs = event.millis,
                message = "${event.reason} Retrying ${event.step.key} in ${event.millis / 1000}s.",
            )
            is PoseRunEvent.Rejected -> _state.value.copy(
                message = "Redrawing ${FrameQuality.describe(event.step.key, event.defects)}.",
            )
            is PoseRunEvent.Saved -> if (event.flagged.isEmpty()) {
                _state.value
            } else {
                _state.value.copy(flagged = _state.value.flagged + (event.step.key to event.flagged.joinToString(" and ") { it.label }))
            }
            // One bad frame does not end the run: thirty-nine good poses and a
            // list of which to retry is far better than nothing.
            is PoseRunEvent.Failed -> _state.value.copy(failures = _state.value.failures + (event.step.key to event.reason))
        }
    }

    private suspend fun finishRun(setId: String, label: String, outcome: RunOutcome) {
        val drawnNow = withContext(io) { posesDrawn(setId) }
        val hasSheet = savedCharacters().firstOrNull { it.setId == setId }?.sheetId != null
        var packed: PackedSheet? = null
        if (PosePacking.shouldAutoPack(outcome, drawnNow, hasSheet)) {
            val plan = PosePacking.planFor(setId, label, drawnNow, _state.value.cellSize, _state.value.frameRate)
            if (plan != null) packed = withContext(compute) { composeSheet(setId, plan) }
        }
        val summary = PoseFrameRun.summary(outcome, _state.value.completed, _state.value.total)
        _state.value = _state.value.copy(
            busy = false,
            currentStep = null,
            attempt = 1,
            waitingMs = 0L,
            drawn = drawnNow,
            error = outcome.abandonedBecause,
            savedSheet = packed?.sheet ?: _state.value.savedSheet,
            characters = savedCharacters(),
            message = summary + when {
                packed != null ->
                    " Packed into a ${packed.sheet.columns}x${packed.sheet.rows} sheet — ready to wear on the home screen!"
                outcome.abandonedBecause != null -> ""
                else -> " Poses drawn so far can be packed into a sheet now."
            },
        )
    }

    /** Throws one pose away so the next run draws it again, and asks for a fresh take of it. */
    fun redrawPose(key: String) {
        val current = _state.value
        val setId = current.setId ?: return
        dropPose(setId, key)
        // A new take, or the cache would hand back the very picture that was
        // just thrown away.
        saveRunRecord(recordOf(current, previous = loadRunRecord(setId)).redrawn(key))
        _state.value = current.copy(
            drawn = posesDrawn(setId),
            failures = current.failures - key,
            flagged = current.flagged - key,
            savedSheet = null,
        )
    }

    fun stop() {
        job?.cancel()
        job = null
        // Also the run itself, which no longer belongs to this scope: without
        // this, Stop would clear the screen and leave the generations going.
        stopRun()
        _state.value.setId?.let { setId -> loadRunRecord(setId)?.let { saveRunRecord(it.copy(active = false)) } }
        _state.value = _state.value.copy(
            busy = false,
            currentStep = null,
            message = "Stopped. The poses already drawn are kept.",
        )
    }

    /**
     * Picks a killed run back up exactly as it was set up.
     *
     * Everything the run was for comes from the record — frame counts, angles,
     * cell size, style — so the resumed run draws the frames the first one
     * would have, not whatever the screen's defaults happen to be.
     */
    fun resumeInterrupted() {
        val record = _state.value.interruptedRun ?: return
        adopt(record)
        _state.value = _state.value.copy(interruptedRun = null)
        buildAnimations()
    }

    /** Forgets a killed run without resuming it. The poses it drew are kept. */
    fun dismissInterrupted() {
        _state.value.interruptedRun?.let { saveRunRecord(it.copy(active = false)) }
        _state.value = _state.value.copy(interruptedRun = null)
    }

    /**
     * Packs what has been drawn into a sheet.
     *
     * Allowed before the set is complete on purpose. A character with an idle
     * and a walk is playable, and seeing it in the world after eight
     * generations rather than forty is the difference between a pipeline
     * someone uses and one they read about.
     */
    fun buildSheet() {
        val current = _state.value
        if (current.busy) return
        val setId = current.setId ?: return
        val drawn = posesDrawn(setId)
        if (drawn.isEmpty()) {
            _state.value = current.copy(error = "No poses have been drawn yet.")
            return
        }
        // Planned from what is on disk, not from the toggles: a set drawn with
        // an away view and reopened with the toggle off still packs both.
        val plan = PosePacking.planFor(setId, current.subject, drawn, current.cellSize, current.frameRate)
        if (plan == null) {
            _state.value = current.copy(error = "There is nothing to pack yet.")
            return
        }

        _state.value = current.copy(busy = true, error = null)
        job = viewModelScope.launch {
            // Decoding, keying and downscaling forty 1024-pixel images is
            // seconds of work. On the main thread that is a frozen screen.
            val packed = withContext(compute) { composeSheet(setId, plan) }
            _state.value = if (packed == null) {
                _state.value.copy(busy = false, error = "The sheet could not be written.")
            } else {
                _state.value.copy(
                    busy = false,
                    savedSheet = packed.sheet,
                    characters = savedCharacters(),
                    message = packMessage(packed),
                )
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    override fun onCleared() {
        // The reference and pack jobs belong to this screen; the frame run
        // does not, and keeps going.
        if (!isRunning()) job?.cancel()
        super.onCleared()
    }

    /**
     * Opens a character that is already on disk, set up as it was last run.
     *
     * Typing the subject again used to be the only way back to a set, and even
     * then the frame counts and angles came back as the defaults.
     */
    fun openCharacter(character: SavedCharacter) {
        val poses = posesDrawn(character.setId)
        _state.value = _state.value.copy(
            subject = character.name,
            setId = character.setId,
            // Read off the poses rather than left as whatever the toggle was.
            drawsAwayView = poses.anyAway(),
            // Read back off the id: opening an enemy and then generating would
            // otherwise write the next frames into the hero's set.
            role = CharacterRole.fromSetId(character.setId),
            hasReference = hasReference(character.setId),
            drawn = poses,
            guides = loadGuides(character.setId),
            savedSheet = null,
            failures = emptyMap(),
            flagged = emptyMap(),
            message = null,
            error = null,
        )
        loadRunRecord(character.setId)?.let { adopt(it, keepSubject = true) }
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
            interruptedRun = current.interruptedRun?.takeIf { it.setId != setId },
            message = "Deleted.",
        )
    }

    /** Hands the packed sheet to the device. Only offered once a sheet has been packed. */
    fun exportSheet() {
        val sheet = _state.value.savedSheet
        if (sheet == null) {
            _state.value = _state.value.copy(error = "Pack the sheet first, then export it.")
            return
        }
        val name = _state.value.subject.trim().ifBlank { sheet.name }
        report(exportSheet(sheet.id, name), "Sheet exported.", "The sheet could not be exported.")
    }

    /**
     * How finely to cut a clip.
     *
     * Clamped to what reads as animation: below the floor it is a slideshow,
     * and above the ceiling the frames are closer together than the model drew
     * distinct ones.
     */
    fun setFrameRate(fps: Int) {
        _state.value = _state.value.copy(frameRate = fps.coerceIn(ClipSampling.MIN_FPS, ClipSampling.MAX_FPS))
    }

    /** Chooses between drawing every frame and cutting them out of one clip. */
    fun setDrawsFromClip(on: Boolean) {
        _state.value = _state.value.copy(drawsFromClip = on)
    }

    /**
     * Draws each animation as a clip and cuts its row out.
     *
     * One request an animation rather than one a frame: the frames of a row
     * come out of a single generation, so they cannot disagree about the
     * costume or the scale the way separately drawn ones do. The clip is kept,
     * because changing the frame rate afterwards should not cost anything.
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

        val record = recordOf(current, previous = loadRunRecord(setId)).copy(active = true)
        saveRunRecord(record)
        val states = current.script.states
        _state.value = current.copy(busy = true, error = null, message = null, failures = emptyMap(), interruptedRun = null)

        job = launchRun {
            var failures = emptyMap<String, String>()
            var drawn = 0

            states.forEachIndexed { index, state ->
                reportProgress(current.subject, index, states.size)

                // The clip is pinned to its opening pose at both ends, so that
                // has to be the animation's own first pose, not the T-pose: the
                // first version passed the reference and every clip came back
                // a T-pose held for four seconds.
                val opening = current.script.stepsFor(state).firstOrNull()
                if (opening == null) {
                    failures = failures + (state.name.lowercase() to "no first pose to open on")
                    return@forEachIndexed
                }
                // Reused when already on disk, so switching between the two
                // paths does not pay for it twice.
                val openingBytes = withContext(io) {
                    loadPose(setId, opening.key) ?: runCatching {
                        drawPose(
                            PoseFrameRequest(
                                reference = ImageReference(reference),
                                step = opening,
                                guide = guideFor(opening, current.guides),
                                styleDirection = current.style,
                                take = record.takes[opening.key] ?: 0,
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
                        motion = ClipMotion.of(state),
                        openingPose = ImageReference(openingBytes),
                        fps = current.frameRate,
                        styleDirection = current.style,
                    ),
                    GenerationObserver.None,
                )
                result.onSuccess { row ->
                    withContext(io) {
                        row.frames.forEach { (key, bytes) -> savePose(setId, key, bytes) }
                        saveClip(setId, state.name.lowercase(), row.clip.bytes)
                    }
                    drawn += row.frameCount
                }.onFailure { failure ->
                    failures = failures + (state.name.lowercase() to (failure.message ?: "failed"))
                }
            }

            saveRunRecord(record.copy(active = false, failures = failures))
            val onDisk = withContext(io) { posesDrawn(setId) }
            _state.value = _state.value.copy(
                busy = false,
                drawn = onDisk,
                clips = withContext(io) { clipsDrawn(setId) },
                rowLengths = PoseCell.rowLengths(onDisk, _state.value.views.map { it.keySuffix }),
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

    /** Writes out the T-pose the character is built from, which is not in the sheet. */
    fun exportReference() {
        val setId = _state.value.setId
        if (setId == null || !_state.value.hasReference) {
            _state.value = _state.value.copy(error = "There is no reference to export yet.")
            return
        }
        report(exportReference(setId, exportName()), "Reference exported.", "The reference could not be exported.")
    }

    /** Writes out the clip an animation was cut from, which the sheet only samples. */
    fun exportClip(key: String) {
        val setId = _state.value.setId
        if (setId == null || key !in _state.value.clips) {
            _state.value = _state.value.copy(error = "There is no clip for that animation.")
            return
        }
        report(exportClip(setId, exportName(), key), "Clip exported.", "The clip could not be exported.")
    }

    fun exportPoses() {
        val setId = _state.value.setId
        if (setId == null || _state.value.drawn.isEmpty()) {
            _state.value = _state.value.copy(error = "There are no poses to export yet.")
            return
        }
        report(exportPoses(setId, exportName()), "Poses exported.", "The poses could not be exported.")
    }

    private fun exportName(): String = _state.value.subject.trim().ifBlank { "character" }

    private fun report(ok: Boolean, success: String, failure: String) {
        _state.value = if (ok) {
            _state.value.copy(message = success, error = null)
        } else {
            _state.value.copy(error = failure)
        }
    }

    /** The record for the character on screen, keeping what an earlier record knew about takes. */
    private fun recordOf(state: PoseForgeUiState, previous: PoseRunRecord?): PoseRunRecord = PoseRunRecord(
        setId = state.setId.orEmpty(),
        subject = state.subject.trim(),
        style = state.style,
        scope = state.scope,
        frames = state.frames,
        drawsAwayView = state.drawsAwayView,
        drawsFromClip = state.drawsFromClip,
        cellSize = state.cellSize,
        frameRate = state.frameRate,
        promptOverride = state.promptOverride,
        takes = previous?.takes.orEmpty(),
        failures = previous?.failures.orEmpty(),
        flagged = previous?.flagged.orEmpty(),
    )

    /** Sets the screen up as [record] was. */
    private fun adopt(record: PoseRunRecord, keepSubject: Boolean = false) {
        val poses = posesDrawn(record.setId)
        _state.value = _state.value.copy(
            subject = if (keepSubject) _state.value.subject else record.subject,
            setId = record.setId,
            role = CharacterRole.fromSetId(record.setId),
            style = record.style,
            scope = record.scope,
            frames = record.frames,
            drawsAwayView = record.drawsAwayView || poses.anyAway(),
            drawsFromClip = record.drawsFromClip,
            cellSize = record.cellSize,
            frameRate = record.frameRate,
            promptOverride = record.promptOverride,
            hasReference = hasReference(record.setId),
            drawn = poses,
            guides = loadGuides(record.setId),
            failures = record.failures,
            flagged = record.flagged,
        )
    }

    private fun packMessage(packed: PackedSheet): String = buildString {
        val sheet = packed.sheet
        append("Saved as a ${sheet.columns}x${sheet.rows} sheet at ")
        // The frame size the sheet actually came out at, which is cut to the
        // figure's proportions after measuring it.
        append("${sheet.frameWidth}x${sheet.frameHeight} a frame. ")
        // A hole in an animation looks exactly like a frame the character is
        // invisible for, so it is named rather than left to be noticed.
        if (packed.missing.isNotEmpty()) {
            append("${packed.missing.size} pose(s) could not be read and left ")
            append("empty cells: ${packed.missing.joinToString()}. ")
        }
        append("Open it in the frame mapper to adjust it.")
    }

    /** Whether any of these pose keys is an away frame. */
    private fun Set<String>.anyAway(): Boolean = any { it.endsWith(PoseView.AWAY.keySuffix) }

    companion object {
        /**
         * Frame heights offered, not frame sizes: the width is taken from the
         * art once it has been measured, so it is not a choice to make here.
         */
        val CELL_SIZES = listOf(96, 128, 192, 256, 384)

        /**
         * The rates offered. Twelve is the default and the one hand-drawn
         * animation has used for a century.
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
            inspectPose: (ByteArray) -> FrameAnalysis? = { null },
            saveRunRecord: (PoseRunRecord) -> Unit = {},
            loadRunRecord: (String) -> PoseRunRecord? = { null },
            runRecords: () -> List<PoseRunRecord> = { emptyList() },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PoseForgeViewModel(
                drawReference = drawReference,
                drawPose = drawPose,
                guideFor = guideFor,
                readGuideImage = readGuideImage,
                readGuideJson = readGuideJson,
                loadGuides = loadGuides,
                saveGuides = saveGuides,
                saveReference = saveReference,
                loadReference = loadReference,
                hasReference = hasReference,
                savePose = savePose,
                dropPose = dropPose,
                posesDrawn = posesDrawn,
                loadPose = loadPose,
                composeSheet = composeSheet,
                savedCharacters = savedCharacters,
                deleteCharacter = deleteCharacter,
                exportSheet = exportSheet,
                exportPoses = exportPoses,
                exportReference = exportReference,
                exportClip = exportClip,
                clipsDrawn = clipsDrawn,
                drawClipRow = drawClipRow,
                saveClip = saveClip,
                isProviderConfigured = isProviderConfigured,
                inspectPose = inspectPose,
                saveRunRecord = saveRunRecord,
                loadRunRecord = loadRunRecord,
                runRecords = runRecords,
            ) as T
        }
    }
}

/**
 * What a character is being drawn for. The choice is written into the set id,
 * so the art is filed where the game looks for that kind of actor.
 */
typealias CharacterRole = com.stratum.core.domain.character.CharacterRole

/** How many animations to draw; lives in the domain beside the script it builds. */
typealias PoseScope = com.stratum.core.domain.ai.PoseScope

data class PoseForgeUiState(
    val subject: String = "",
    val style: String = "",
    val scope: PoseScope = PoseScope.ENEMY,
    /**
     * Whether this character is the player's or something it meets. A hero by
     * default: the commonest thing to make is the character you play.
     */
    val role: CharacterRole = CharacterRole.HERO,
    /** How many frames each animation gets, per state. */
    val frames: Map<AnimationState, Int> = emptyMap(),
    /** Whether the away-facing angle is drawn too. Off by default: it doubles the cost. */
    val drawsAwayView: Boolean = false,
    val cellSize: Int = PoseSheetPlanner.DEFAULT_CELL,
    /** Frames a second a clip is cut into, and a packed row plays at. */
    val frameRate: Int = ClipSampling.DEFAULT_FPS,
    /**
     * An edited reference prompt, or null for the built-in one — null rather
     * than a copy, so the default can be improved later.
     */
    val promptOverride: String? = null,
    /** Animations with a clip saved, which can be re-cut for nothing. */
    val clips: Set<String> = emptySet(),
    /** How long each animation actually is on disk, read off the keys. */
    val rowLengths: Map<AnimationState, Int> = emptyMap(),
    /** Whether each animation is drawn as one clip rather than frame by frame. */
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
    /**
     * Frames kept even though a redraw did not fix what was wrong with them,
     * and what that was. Worth a person's look before the sheet is packed.
     */
    val flagged: Map<String, String> = emptyMap(),
    val savedSheet: SpriteSheet? = null,
    val providerConfigured: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Which poses this character is drawn against, and how they are drawn. */
    val guides: PoseGuides = PoseGuides(),
    /** Every character on disk, so one can be picked up without retyping it. */
    val characters: List<SavedCharacter> = emptyList(),
    /** A run the process died in, offered for resuming. */
    val interruptedRun: PoseRunRecord? = null,
) {
    val views: List<PoseView>
        get() = if (drawsAwayView) listOf(PoseView.FRONT, PoseView.AWAY) else listOf(PoseView.FRONT)

    /** The prompt that would be sent, default or edited, which the editor shows. */
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
    fun framesFor(state: AnimationState): Int = frames[state] ?: PoseScript.DEFAULT_FRAMES

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
