package com.stratum.core.domain.sprite

/** Something wrong with a generated frame that asking again is likely to fix. */
enum class FrameDefect(val label: String) {
    /** Nothing survived keying: a blank image, or a figure drawn in the backdrop's own colour. */
    EMPTY("came back blank"),

    /** The background was not a colour anything could key out, so the frame is an opaque square. */
    UNKEYED("came back without a removable background"),

    /** Part of the figure runs off the canvas: a cropped head, a missing foot. */
    CLIPPED("is cut off at the edge of the picture"),

    /** More than one figure, or one drawn in pieces far apart. */
    SCATTERED("has more than one figure in it"),

    /** So small against the reference that it would be a smudge in its cell. */
    TINY("is drawn far smaller than the character"),

    /** The model handed back the reference pose instead of the one asked for. */
    UNCHANGED("is the reference pose, not the one asked for"),
}

/**
 * Whether a generated frame is worth keeping.
 *
 * The pipeline used to accept whatever came back, write it to disk, and count
 * it as drawn. A frame the model returned blank, or cropped at the knees, or as
 * the reference T-pose untouched, was then indistinguishable from a good one
 * until someone packed the sheet and watched the character vanish for a frame
 * — at which point the only remedy was to find that one frame by eye and
 * redraw it by hand. Every one of these failures is measurable from the pixels,
 * so the run measures them as each frame arrives and asks again for just that
 * frame, which is the cheapest moment there will ever be to fix it.
 *
 * Deliberately conservative. A false alarm costs a generation; a missed defect
 * costs a frame. Each threshold sits where a real defect is unmistakable, so a
 * frame that is merely unusual — a crouch, a lunge, a body lying flat — is
 * never rejected for being unusual.
 */
object FrameQuality {

    /**
     * The defects in [frame], most serious first; empty when it is fine.
     *
     * @param reference the analysed reference pose, when there is one. Needed
     *   for the two checks that are relative: whether the figure shrank to
     *   nothing, and whether it is the reference itself.
     * @param state lets a body that is meant to be low — rolled up, lying dead
     *   — be exempt from the size check it would otherwise fail by design.
     */
    fun inspect(
        frame: FrameAnalysis,
        reference: FrameAnalysis? = null,
        state: AnimationState? = null,
    ): List<FrameDefect> {
        val bounds = frame.bounds ?: return listOf(FrameDefect.EMPTY)
        if (frame.coverage >= UNKEYED_COVERAGE) return listOf(FrameDefect.UNKEYED)

        return buildList {
            // The bottom edge is allowed: a model asked for "feet near the
            // bottom" sometimes puts them on it, and that loses nothing.
            if (frame.touchedEdges.any { it != FrameAnalysis.Edge.BOTTOM }) add(FrameDefect.CLIPPED)

            val largest = frame.blobs.firstOrNull() ?: 0
            val second = frame.blobs.getOrNull(1) ?: 0
            if (largest > 0 && second >= largest * SCATTER_RATIO && second >= MIN_SCATTER_CELLS) {
                add(FrameDefect.SCATTERED)
            }

            val referenceBounds = reference?.bounds
            if (referenceBounds != null && state !in LOW_STATES) {
                val relative = bounds.height.toFloat() / referenceBounds.height
                if (relative < TINY_RATIO) add(FrameDefect.TINY)
            }

            val a = frame.silhouette
            val b = reference?.silhouette
            if (a != null && b != null && a.similarity(b) >= UNCHANGED_SIMILARITY) {
                add(FrameDefect.UNCHANGED)
            }
        }
    }

    /**
     * Pairs of frames that are the same drawing, by index.
     *
     * A model asked for a sheet of six different poses sometimes draws two or
     * three of them identically, and a walk with a repeated frame plays as a
     * hitch. Only neighbours in the same clip are compared, because an idle
     * that returns to its first pose at the end is supposed to.
     */
    fun repeatedNeighbours(frames: List<FrameAnalysis?>): List<Pair<Int, Int>> =
        frames.indices.zipWithNext().filter { (a, b) ->
            val first = frames[a]?.silhouette
            val second = frames[b]?.silhouette
            first != null && second != null && first.similarity(second) >= REPEAT_SIMILARITY
        }

    /** A sentence for a person, naming what went wrong with a frame. */
    fun describe(key: String, defects: List<FrameDefect>): String =
        "$key ${defects.joinToString(" and ") { it.label }}"

    /** States whose body is legitimately low, so height says nothing about scale. */
    private val LOW_STATES = setOf(AnimationState.ROLL, AnimationState.DIE)

    /** Nine tenths of the canvas drawn on is a background, not a character. */
    private const val UNKEYED_COVERAGE = 0.9f

    /** A second piece a third the size of the first is a second figure. */
    private const val SCATTER_RATIO = 0.33f

    /** Coarse cells a second piece must span before it counts at all. */
    private const val MIN_SCATTER_CELLS = 6

    /**
     * Less than a third of the reference's height, outside a roll or a death.
     *
     * A crouch is about two thirds; nothing the script asks for while standing
     * is a third. Below it the model has drawn a distant figure, not a pose.
     */
    private const val TINY_RATIO = 0.33f

    /**
     * Silhouettes this alike are the same drawing.
     *
     * High, because the reference is a T-pose and nothing the script asks for
     * is: an idle with arms down scores far below it, while a frame returned
     * untouched scores at or near one.
     */
    const val UNCHANGED_SIMILARITY = 0.95f

    /** Neighbouring frames this alike are one drawing repeated. */
    const val REPEAT_SIMILARITY = 0.97f
}
