package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.ClipCut
import com.stratum.core.domain.sprite.ClipSampling
import com.stratum.core.domain.sprite.MocapPoses
import com.stratum.core.domain.sprite.PoseCell

/** One animation's worth of frames, cut from a single generated clip. */
data class ClipRow(
    val state: AnimationState,
    /** The clip itself, kept because re-cutting it at another rate costs nothing. */
    val clip: GeneratedClip,
    /** Frames by pose key, ready to be saved exactly as a generated still would be. */
    val frames: Map<String, ByteArray>,
    /** Cuts the decoder could not read. Usually empty, and it matters when it is not. */
    val missing: List<Int>,
) {
    val frameCount: Int get() = frames.size
}

/**
 * Draws one animation as a clip and cuts a row of frames out of it.
 *
 * The alternative to drawing every frame separately, and it answers the one
 * complaint separate frames cannot. Stills of the same character under
 * different guides come back as the same character *nearly*: measured on real
 * output, one frame of a walk arrived wearing a cape the other five did not
 * have, and the figure's size wandered between frames. Frames cut out of one
 * clip cannot disagree like that, because they were never generated apart.
 *
 * What it costs is the poses. A clip takes no per-frame stick figure, so the
 * beats in the middle are whatever the model thought walking looks like rather
 * than the ones that were authored. Pinning both ends to drawings the pipeline
 * made under a guide is what keeps it anchored, and for a cycle both ends are
 * the *same* drawing — a movement that returns to where it started, which is a
 * closed loop and is the one thing the still path cannot produce at all.
 *
 * The thing to know before choosing it: only the opening of a clip is usable.
 * Nothing sells a clip shorter than four seconds, a walk cycle is about one,
 * and the model fills the rest by inventing — measured, what it invents is the
 * character turning round to show its back. [ClipSampling.USABLE_FRACTION]
 * carries the measurement and every cut stays inside it, so the rest is paid
 * for and discarded. That is the shape of the purchase rather than waste to be
 * optimised away.
 */
class GenerateClipRowUseCase(
    private val videoModel: VideoModelPort,
    /**
     * Cuts frames out of the clip, injected because the domain cannot decode
     * video any more than it can decode a PNG.
     *
     * Returns frames by cut index, leaving out any it could not read, which is
     * what [ClipRow.missing] is worked out from.
     */
    private val cutFrames: (clip: ByteArray, cuts: List<ClipCut>) -> Map<Int, ByteArray>,
) {

    suspend operator fun invoke(
        request: ClipRowRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<ClipRow> {
        val clip = videoModel.generateClip(
            ClipRequest(
                prompt = buildPrompt(request),
                modelId = request.modelId,
                firstFrame = request.firstFrame,
                // A cycle ends where it began, so both ends are the same
                // drawing. A one-shot ends somewhere else, and is given only a
                // start unless the caller has drawn its finish too.
                lastFrame = if (request.state in MocapPoses.cycles) {
                    request.firstFrame
                } else {
                    request.lastFrame
                },
                seconds = request.seconds,
                width = request.edge,
                height = request.edge,
            ),
            observer,
        ).getOrElse { return Result.failure(it) }

        val duration = clip.durationMillis ?: request.assumedDurationMillis
        val cuts = ClipSampling.cutsAtRate(
            state = request.state,
            durationMillis = duration,
            fps = request.fps,
        )
        if (cuts.isEmpty()) {
            return Result.failure(
                GenerationException("The clip came back with no readable length, so nothing could be cut"),
            )
        }

        val cut = cutFrames(clip.bytes, cuts)
        if (cut.isEmpty()) {
            return Result.failure(
                GenerationException("The clip generated but no frame could be read out of it"),
            )
        }

        // Keyed exactly as a generated still is, so everything downstream --
        // saving, keying, anchoring, packing -- cannot tell which path a frame
        // came from, and a row can be half of each.
        val frames = cut.entries.associate { (index, bytes) ->
            PoseCell.keyOf(request.state, index, request.viewSuffix) to bytes
        }
        return Result.success(
            ClipRow(
                state = request.state,
                clip = clip,
                frames = frames,
                missing = cuts.map { it.index }.filterNot { it in cut.keys },
            ),
        )
    }

    /**
     * What to ask the clip for.
     *
     * Every line here is answering something a real generation got wrong. The
     * character turned round, so it is told not to. It walked across the frame,
     * so it is told to stay put. The camera drifted, so the camera is nailed
     * down twice. None of it stops the turn entirely -- that is what the usable
     * window is for -- but it delays it, and every tenth of a second it buys is
     * more frames before the row goes bad.
     */
    private fun buildPrompt(request: ClipRowRequest): String = buildString {
        appendLine("${request.motion} -- a looping animation for a 2D game sprite sheet, on the spot.")
        appendLine()
        appendLine("THE CHARACTER NEVER TURNS. The body, hips, shoulders and face point in")
        appendLine("exactly the same direction in every frame, from the first to the last. Do")
        appendLine("not rotate the character, do not turn it towards or away from the viewer,")
        appendLine("and never show its back. It does not travel across the frame or change")
        appendLine("direction: the limbs move and the body stays where it is.")
        appendLine()
        appendLine(IsometricCamera.holdClause)
        appendLine()
        appendLine("Keep the character identical in every frame: same face, same build, same")
        appendLine("colours, same equipment, same clothing. Add nothing that is not already")
        appendLine("on it. No motion blur, no speed lines, no dust, no shadow.")
        if (request.styleDirection.isNotBlank()) {
            appendLine()
            appendLine(request.styleDirection)
        }
        appendLine()
        appendLine(CLIP_BACKGROUND)
    }

    private companion object {
        /**
         * The same demand the stills make, worded for footage.
         *
         * Keyed the same way afterwards, so the backdrop has to be the same
         * colour and just as flat -- a gradient that would be survivable in one
         * still is survivable in none of eighty frames.
         */
        const val CLIP_BACKGROUND =
            "Background: a single flat solid chroma green (#00FF00) filling the frame edge " +
                "to edge, unchanging for the whole clip. No scenery, no ground, no gradient, " +
                "no texture, no shadow cast onto it. The green must not appear anywhere on " +
                "the character."
    }
}

/** What to draw, how long, and how finely to slice it. */
data class ClipRowRequest(
    val state: AnimationState,
    /** The movement, in words. "A steady walk cycle", "a sword swing". */
    val motion: String,
    /**
     * The drawing the clip starts on.
     *
     * Not optional in practice. Without it the model invents a character, and
     * the row is of somebody else.
     */
    val firstFrame: ImageReference,
    /**
     * The drawing a one-shot ends on, when there is one.
     *
     * Ignored for a cycle, which ends on its first frame by definition and is
     * given that instead.
     */
    val lastFrame: ImageReference? = null,
    val fps: Int = ClipSampling.DEFAULT_FPS,
    val seconds: Int = ClipRequest.MIN_SECONDS,
    val edge: Int = ClipRequest.DEFAULT_EDGE,
    val styleDirection: String = "",
    /** Which angle this row is, matching the key a generated still would get. */
    val viewSuffix: String = "",
    val modelId: String? = null,
    /**
     * What to assume when the container will not say how long the clip is.
     *
     * A clip of unknown length cannot be cut at the right moments, and the
     * length asked for is a better guess than refusing: providers return what
     * was requested, and a cut a few frames out is a worse row rather than no
     * row.
     */
    val assumedDurationMillis: Long = seconds * 1000L,
)
