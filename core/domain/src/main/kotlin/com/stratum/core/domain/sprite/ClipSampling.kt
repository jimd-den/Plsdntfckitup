package com.stratum.core.domain.sprite

/**
 * One frame to cut out of a generated clip.
 *
 * Carried as a fraction of the way through rather than as a timestamp, because
 * the domain does not know how long the clip came back as. A model asked for
 * two seconds returns what it returns, and the layer holding the bytes is the
 * one that can read the duration off them.
 */
data class ClipCut(
    /** Which frame of the finished row this becomes. */
    val index: Int,
    /** How far through the clip to cut, from 0 at the first frame to 1 at the last. */
    val fraction: Float,
)

/**
 * One frame to cut out of a clip that spans a single authored beat.
 *
 * The other way of using a video model: rather than generating a whole
 * animation and hoping it contains the poses, generate the poses as stills the
 * way the pipeline already does — under a stick figure guide, which is the only
 * thing that makes them *these* poses rather than a plausible walk — and use
 * the video model only to fill the gaps between them, conditioned on the
 * drawing at each end.
 *
 * That is the shape worth having. The guide survives, the beats survive, the
 * loop closes because the last segment is authored as key-back-to-first, and
 * the in-betweens are coherent by construction: nothing can grow a cape halfway
 * through a segment the way separately generated stills do.
 */
data class SegmentCut(
    /** Which frame of the finished row this becomes. */
    val index: Int,
    /** Which authored pose the segment starts from. */
    val fromKey: Int,
    /** Which authored pose it ends at. Wraps to 0 on a clip authored as a cycle. */
    val toKey: Int,
    /** How far along that one segment to cut. Zero means the keyframe itself. */
    val fraction: Float,
) {
    /**
     * True when this frame is an authored pose rather than an in-between.
     *
     * Worth asking, because such a frame already exists: it is the still that
     * conditioned the segment, and generating it a second time would be paying
     * twice for the same picture and risking getting a different one.
     */
    val isKeyframe: Boolean get() = fraction == 0f
}

/**
 * Where to cut a generated clip so the frames land where the poses would.
 *
 * The arithmetic is small and the reason for it is not. Everything upstream —
 * the drawn pose in [MocapPoses], the written pose in the script — is spaced by
 * stepping through a source in `count` steps, and the two of them drifting
 * apart at twelve frames is a bug this pipeline has already had: the diagram
 * sent to the model ended up most of a beat away from the sentence sent beside
 * it, and a walk lost the step from its last frame back to its first. Frames
 * cut out of a clip are the same frames, so they are spaced by the same rule
 * and read the same [MocapPoses.cycles] to know which clips loop.
 *
 * The one deliberate difference is the last interval, and it is a difference in
 * the *source*, not in the rule. A clip is continuous and has a real end, so a
 * one-shot spreads its frames over `count - 1` intervals and the last frame
 * lands on the final instant — a death has to reach the body being still. A
 * cycle has no end to land on: its last frame hands back to its first, so that
 * hand-back is an interval like any other and the frames spread over `count` of
 * them. Spreading a cycle over `count - 1` is exactly what made the walk hitch.
 */
object ClipSampling {

    /**
     * Cuts for a clip generated as a whole animation.
     *
     * Simple, and it gives up the thing that makes these poses *these* poses: a
     * model handed a sentence and no drawing returns a plausible walk, not the
     * authored one, and nothing holds the camera still across the clip. Kept
     * because it is one call per row instead of a dozen, which for a rough pass
     * is a real trade.
     */
    fun cutsFor(state: AnimationState, count: Int): List<ClipCut> =
        cutsFor(count, cycle = state in MocapPoses.cycles)

    fun cutsFor(count: Int, cycle: Boolean): List<ClipCut> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(ClipCut(0, 0f))
        val intervals = if (cycle) count else count - 1
        return (0 until count).map { index ->
            ClipCut(index, (index.toFloat() / intervals).coerceIn(0f, 1f))
        }
    }

    /**
     * Cuts for clips generated one authored beat at a time.
     *
     * Each frame is placed exactly where [MocapPoses.poseFor] would place it —
     * `index * keys / count` through the authored list — and then split into
     * which segment that falls in and how far along it. That is what keeps a
     * row generated this way interchangeable with a row generated as stills:
     * the same frame index means the same moment either way, so a set can be
     * started one way and finished the other, and a frame that came back wrong
     * can be redrawn as a still and dropped back into place.
     *
     * A cycle gets a segment from its last key back to its first. That segment
     * is the loop closing, and it is the one a whole-clip video cannot give you
     * at all.
     */
    fun segmentCutsFor(state: AnimationState, count: Int): List<SegmentCut> =
        segmentCutsFor(count, MocapPoses.framesFor(state).size, state in MocapPoses.cycles)

    fun segmentCutsFor(count: Int, keys: Int, cycle: Boolean): List<SegmentCut> {
        if (count <= 0 || keys <= 0) return emptyList()
        if (keys == 1) return (0 until count).map { SegmentCut(it, 0, 0, 0f) }
        return (0 until count).map { index ->
            val exact = index.toFloat() * keys / count
            val from = exact.toInt().coerceIn(0, keys - 1)
            val to = if (cycle) (from + 1) % keys else (from + 1).coerceAtMost(keys - 1)
            SegmentCut(index = index, fromKey = from, toKey = to, fraction = exact - from)
        }
    }

    /**
     * The moment to seek to, in milliseconds.
     *
     * Here rather than in the decoder because it is a decision, not a
     * multiplication. A cut at the very end is pulled a hair inside it: seeking
     * to the final instant lands past the last frame on some decoders and
     * returns nothing, and the frame that would lose is the last one of a
     * one-shot -- the body finally still, which is the one frame a death
     * animation cannot do without.
     *
     * A cycle never asks for the end, so this only ever bites the clips that
     * do.
     */
    fun millisFor(cut: ClipCut, durationMillis: Long): Long {
        if (durationMillis <= 0L) return 0L
        val at = (cut.fraction.coerceIn(0f, 1f) * durationMillis).toLong()
        return at.coerceIn(0L, durationMillis - 1)
    }

    /**
     * The segments that actually have to be generated, and how many frames each
     * owes.
     *
     * A segment nothing falls inside is a video nobody needs: at six frames
     * from six keys every frame is a keyframe, so the whole video path costs
     * nothing and draws nothing, and the row is the still pipeline unchanged.
     * Asking for this before generating is what stops a run paying for twelve
     * clips to use four of them.
     */
    fun segmentsNeeded(cuts: List<SegmentCut>): Map<Pair<Int, Int>, Int> =
        cuts.filterNot { it.isKeyframe }
            .groupingBy { it.fromKey to it.toKey }
            .eachCount()
}
