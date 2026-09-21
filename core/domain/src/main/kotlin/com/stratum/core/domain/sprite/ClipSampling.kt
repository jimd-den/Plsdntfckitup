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
     * The economical shape, and not for the reason it first looks. Clips cannot
     * be bought shorter than four seconds — that is the floor across the whole
     * catalogue — which is some eighty frames whatever you asked for. Taking
     * twelve out of one clip uses what was paid for; buying a clip per beat to
     * take one frame out of each pays for the same four seconds six times and
     * discards all but a frame of each.
     *
     * What it gives up is the poses in the middle. Pinned at both ends by
     * stills drawn under a guide, the clip starts and finishes on authored
     * poses and invents its own way between them, so the beats in between are
     * plausible rather than the ones that were written. For a cycle both ends
     * are the *same* drawing, which is what closes the loop.
     */
    fun cutsFor(
        state: AnimationState,
        count: Int,
        usableFraction: Float = USABLE_FRACTION,
    ): List<ClipCut> =
        cutsFor(count, cycle = state in MocapPoses.cycles, usableFraction = usableFraction)

    fun cutsFor(
        count: Int,
        cycle: Boolean,
        usableFraction: Float = USABLE_FRACTION,
    ): List<ClipCut> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(ClipCut(0, 0f))
        val window = usableFraction.coerceIn(0f, 1f)
        val intervals = if (cycle) count else count - 1
        return (0 until count).map { index ->
            ClipCut(index, (index.toFloat() / intervals * window).coerceIn(0f, 1f))
        }
    }

    /**
     * How much of a generated clip is worth cutting from.
     *
     * Measured, and the measurement was a surprise. A walk generated from a
     * pinned first and last frame came back with the scale rock steady, the
     * gait natural and the character identical throughout -- every complaint
     * the separately generated stills had -- and then, about eight tenths of a
     * second in, the figure began to rotate. By halfway it was showing its
     * back. A second attempt, whose prompt said in as many words that the
     * character never turns and never shows its back, and which pinned both
     * ends to the same drawing, turned sooner.
     *
     * The cause is the four second floor. A walk cycle is about a second, and
     * nothing sells less than four, so the model is handed five times the
     * footage the animation needs and fills the rest by inventing -- and what
     * it invents is a turntable. That is fatal for a sprite row, which is
     * defined by holding one facing.
     *
     * So only the opening is cut from, and the rest is paid for and discarded.
     * That is not waste to be optimised away: it is the shape of the purchase.
     * Cutting the same twelve frames across the whole four seconds gives two
     * usable frames and ten of a character turning round.
     */
    const val USABLE_FRACTION = 0.2f

    /**
     * Cuts for a clip at a chosen frame rate.
     *
     * The frame count stops being a number anybody picks. A clip is a length of
     * footage, a frame rate is how finely it is sliced, and how many frames
     * come out falls out of the two — which is how animation has always been
     * counted, and it is the thing a person can actually reason about. Twelve
     * frames of a walk means nothing on its own; twelve frames a second means
     * the walk plays at twelve frames a second.
     *
     * Bounded at both ends. Below [MIN_FPS] the result is a slideshow rather
     * than a walk, and above [MAX_FPS] the frames are closer together than the
     * model drew distinct ones, so the sheet grows without the animation
     * improving. [MAX_FRAMES] is the harder stop: a row has to fit a sheet that
     * fits a texture, and a long clip at a high rate would otherwise ask for
     * hundreds of cells.
     */
    fun cutsAtRate(
        durationMillis: Long,
        fps: Int,
        cycle: Boolean,
        usableFraction: Float = USABLE_FRACTION,
    ): List<ClipCut> {
        if (durationMillis <= 0L) return emptyList()
        val rate = fps.coerceIn(MIN_FPS, MAX_FPS)
        val window = usableFraction.coerceIn(0f, 1f)
        val usableMillis = durationMillis * window
        val count = (usableMillis * rate / MILLIS_PER_SECOND)
            .toInt()
            .coerceIn(1, MAX_FRAMES)
        return cutsFor(count, cycle, window)
    }

    fun cutsAtRate(
        state: AnimationState,
        durationMillis: Long,
        fps: Int,
        usableFraction: Float = USABLE_FRACTION,
    ): List<ClipCut> =
        cutsAtRate(durationMillis, fps, state in MocapPoses.cycles, usableFraction)

    /** How many frames a rate will produce, for a screen that has to say so before spending. */
    fun frameCountAtRate(
        durationMillis: Long,
        fps: Int,
        usableFraction: Float = USABLE_FRACTION,
    ): Int = cutsAtRate(durationMillis, fps, cycle = true, usableFraction = usableFraction).size

    /**
     * Twelve frames a second.
     *
     * The rate hand-drawn animation has used for a century: fast enough to
     * read as movement, slow enough that each frame is a pose somebody chose.
     * It is also close to what a four second clip yields inside the window
     * that holds its facing, so the default costs one clip and fills a row.
     */
    const val DEFAULT_FPS = 12

    /** Below this a walk is a slideshow. */
    const val MIN_FPS = 6

    /** Above this the frames are closer together than the model drew distinct ones. */
    const val MAX_FPS = 30

    /**
     * The most cells a row can hold.
     *
     * A sheet has to fit in one texture, and a row is as wide as its longest
     * animation. Left unbounded, eight seconds at thirty frames a second would
     * ask for two hundred and forty columns and the planner would shrink every
     * cell to nothing to make it fit.
     */
    const val MAX_FRAMES = 32

    private const val MILLIS_PER_SECOND = 1000f

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
