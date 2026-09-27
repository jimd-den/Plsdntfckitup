package com.stratum.core.domain.sprite

/**
 * Takes the wobble out of a row drawn one frame at a time.
 *
 * Measured on a real character: across twelve separately generated walk frames
 * the figure's height varied by fifteen per cent of its median. That is not the
 * body bobbing -- a walk bobs about three -- it is the model drawing the same
 * character a different size each time it is asked, which is what a dozen
 * independent generations buy you. Played back it reads as the character
 * pulsing, and it is the drift that survives everything else the composer does:
 * hanging every frame from its ground contact fixes where the figure stands and
 * says nothing about how big it is, and one scale for the whole set faithfully
 * preserves the difference rather than removing it.
 *
 * The correction comes from the poses themselves. Every frame of a generated
 * row was drawn under a stick figure, and that stick figure has a height --
 * so how tall this frame *should* be relative to its neighbours is already
 * known, before any art exists. Comparing the two says how much the drawing
 * drifted, and nothing else in the pipeline can tell the difference between a
 * character drawn too large and a character genuinely standing taller.
 *
 * Clamped, and the clamp is the whole safety of it. A roll curls to half
 * height and a death lies flat; correcting those toward a median would stretch
 * a curled body back into a standing one. [MAX_CORRECTION] bounds how far any
 * frame can be pulled, so gross drift is taken out and real posture is left
 * alone -- and where the skeleton and the art disagree wildly, the art wins,
 * because the pose it was drawn from may simply not be the pose that came back.
 */
object SpriteDrift {

    /**
     * How far a single frame may be rescaled.
     *
     * A fifth either way. Enough to absorb the fifteen per cent measured on
     * real output, and not enough to make a crouch stand up: the shortest
     * authored pose is less than half the tallest, so no clamp in this region
     * can flatten the difference between them.
     */
    const val MAX_CORRECTION = 1.2f

    /**
     * A scale for each frame, so the row stops changing size.
     *
     * One where the drawing already agrees with the pose. Above one where the
     * frame came back too small, below where it came back too large.
     *
     * @param measured the height of the art in each frame, in any unit, in
     *   frame order.
     * @param expected what the authored pose says the same frame's height
     *   should be. Any unit again -- only the ratios between frames are read,
     *   so the two lists never have to be in the same units at all.
     */
    fun correctionsFor(
        measured: List<Float>,
        expected: List<Float>,
        limit: Float = MAX_CORRECTION,
    ): List<Float> {
        if (measured.isEmpty()) return emptyList()
        if (expected.size != measured.size) return List(measured.size) { 1f }

        val measuredMid = medianOf(measured)
        val expectedMid = medianOf(expected)
        if (measuredMid <= 0f || expectedMid <= 0f) return List(measured.size) { 1f }

        // Medians rather than means, because one frame that came back at twice
        // the size would drag a mean and quietly shrink every other frame to
        // meet it. A median is unmoved by the outlier it is there to catch.
        val bound = if (limit > 1f) limit else 1f
        return measured.indices.map { index ->
            val have = measured[index] / measuredMid
            val want = expected[index] / expectedMid
            if (have <= 0f) 1f else (want / have).coerceIn(1f / bound, bound)
        }
    }

    /** The same, with the expected heights read off the authored poses. */
    fun correctionsFor(
        state: AnimationState,
        measured: List<Float>,
        limit: Float = MAX_CORRECTION,
    ): List<Float> = correctionsFor(measured, authoredHeightsFor(state, measured.size), limit)

    /**
     * How tall the authored pose stands in each frame of a row.
     *
     * Drawn rather than guessed: the skeleton is posed exactly as the guide
     * that was sent with the frame, and measured top to bottom. A crouch comes
     * out short and a reach comes out tall because the pose is short and tall,
     * which is the point -- this is the only thing in the pipeline that knows
     * the difference between a frame that should be shorter and a frame that
     * was merely drawn smaller.
     */
    fun authoredHeightsFor(state: AnimationState, frameCount: Int): List<Float> {
        if (frameCount <= 0) return emptyList()
        val skeleton = Skeleton()
        return (0 until frameCount).map { index ->
            val joints = skeleton.pose(MocapPoses.poseFor(state, index, frameCount)).joints.values
            if (joints.isEmpty()) {
                1f
            } else {
                val top = joints.minOf { it.y }
                val bottom = joints.maxOf { it.y }
                (bottom - top).coerceAtLeast(MIN_HEIGHT)
            }
        }
    }

    private fun medianOf(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    /** A pose with no height at all would divide by zero rather than be corrected. */
    private const val MIN_HEIGHT = 1e-4f
}
