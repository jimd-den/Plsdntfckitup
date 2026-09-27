package com.stratum.core.domain.sprite

import kotlin.math.min
import kotlin.math.roundToInt

/** Where the figure sits in one generated pose, measured after keying. */
data class FrameMeasure(
    val bounds: SourceRect,
    /** Where it meets the ground; the point every frame is hung from. */
    val anchorX: Int = bounds.left + bounds.width / 2,
) {
    companion object {
        /** The measure of an analysed frame, or null when it holds nothing. */
        fun of(analysis: FrameAnalysis): FrameMeasure? =
            analysis.bounds?.let { FrameMeasure(it, analysis.anchorX ?: (it.left + it.width / 2)) }
    }
}

/** One pose copied from its own canvas into its cell. */
data class FramePlacement(
    val key: String,
    /** The figure's box on the pose's own canvas. */
    val source: SourceRect,
    /** Where that box lands on the sheet, scaled. */
    val target: FrameRect,
)

/**
 * Every decision about where forty separately drawn poses go on one sheet.
 *
 * This used to be the body of the Android composer, interleaved with decoding
 * and drawing, which meant the one question that decides whether a generated
 * character looks animated or broken — do the frames line up — could only be
 * answered by looking at a phone. It is arithmetic over rectangles, so it is
 * here, and the composer only moves pixels to where this says.
 *
 * The rules, in the order they are applied:
 * - each row's frames are rescaled toward the heights its authored poses say
 *   they should have, so a character does not pulse ([SpriteDrift]);
 * - cells are shaped like the figure, from the furthest reach either side of
 *   the ground anchor, so a lunge is never clipped and a standing figure is
 *   not lost in a square;
 * - one scale for the whole set, set by the frame that needs the most room, so
 *   a crouch really is shorter than a stand;
 * - every frame hangs from its ground anchor at the centre of its cell and
 *   stands on the cell's floor, so the feet stay put while the limbs move.
 */
data class PoseSheetLayout(
    val plan: PoseSheetPlan,
    val placements: List<FramePlacement>,
    /** Cells the plan expected that nothing was measured for. */
    val missing: List<String>,
) {
    companion object {

        /**
         * Lays out [measures] on [plan]; null when nothing at all was measured.
         *
         * @param expectedHeights the authored height of each frame of a row, so
         *   drift can be told apart from posture. Defaults to the built-in
         *   skeleton; a set drawn against imported poses passes its own.
         */
        fun of(
            plan: PoseSheetPlan,
            measures: Map<String, FrameMeasure>,
            expectedHeights: (AnimationState, Int) -> List<Float> = SpriteDrift::authoredHeightsFor,
        ): PoseSheetLayout? {
            if (plan.width <= 0 || plan.height <= 0) return null
            val measured = plan.cells.filter { it.key in measures }
            if (measured.isEmpty()) return null

            val drift = driftCorrections(plan, measures, expectedHeights)

            // Reach and height measured after correction, so a frame pulled
            // larger still fits the cell it is about to be drawn into.
            var leftReach = 0f
            var rightReach = 0f
            var tallest = 0f
            for (cell in measured) {
                val measure = measures.getValue(cell.key)
                val correction = drift[cell.key] ?: 1f
                leftReach = maxOf(leftReach, (measure.anchorX - measure.bounds.left) * correction)
                rightReach = maxOf(rightReach, (measure.bounds.right - measure.anchorX) * correction)
                tallest = maxOf(tallest, measure.bounds.height * correction)
            }

            // Symmetric about the anchor, so centring the anchor centres the cell.
            val widest = (2 * maxOf(leftReach, rightReach)).toInt().coerceAtLeast(1)
            val tallestCell = tallest.toInt().coerceAtLeast(1)
            val fitted = plan.fittedTo(widest, tallestCell)
            val scale = min(
                fitted.cellWidth.toFloat() / widest,
                fitted.cellHeight.toFloat() / tallestCell,
            )

            val placements = mutableListOf<FramePlacement>()
            val missing = mutableListOf<String>()
            for (cell in fitted.cells) {
                val measure = measures[cell.key]
                if (measure == null) {
                    missing += cell.key
                    continue
                }
                val frameScale = scale * (drift[cell.key] ?: 1f)
                val source = measure.bounds
                val width = (source.width * frameScale).roundToInt().coerceAtLeast(1)
                val height = (source.height * frameScale).roundToInt().coerceAtLeast(1)
                val cellRect = fitted.rectFor(cell)
                val anchorOffset = ((measure.anchorX - source.left) * frameScale).roundToInt()
                placements += FramePlacement(
                    key = cell.key,
                    source = source,
                    target = FrameRect(
                        left = cellRect.left + fitted.cellWidth / 2 - anchorOffset,
                        top = cellRect.top + (fitted.cellHeight - height),
                        width = width,
                        height = height,
                    ),
                )
            }
            return PoseSheetLayout(fitted, placements, missing)
        }

        /**
         * A scale for every cell, worked out one animation and one angle at a
         * time.
         *
         * Per row rather than across the sheet, because what a frame should
         * measure depends on its own animation: a roll curls to half height and
         * a death lies flat. Per angle as well, because the away block is a
         * second drawing of the same row and averaging it with the front would
         * let one angle's drift correct the other.
         */
        private fun driftCorrections(
            plan: PoseSheetPlan,
            measures: Map<String, FrameMeasure>,
            expectedHeights: (AnimationState, Int) -> List<Float>,
        ): Map<String, Float> = buildMap {
            plan.cells.groupBy { it.row }.values.forEach { cells ->
                val row = cells.sortedBy { it.index }.filter { it.key in measures }
                if (row.isEmpty()) return@forEach
                val state = row.first().state
                val heights = row.map { measures.getValue(it.key).bounds.height.toFloat() }
                // Expected heights are for the whole row's length, then picked
                // out at the indices that were actually measured, so a row with
                // a hole in it still compares each frame with its own pose.
                val length = cells.maxOf { it.index } + 1
                val authored = expectedHeights(state, length)
                val expected = row.map { authored.getOrElse(it.index) { 1f } }
                SpriteDrift.correctionsFor(heights, expected).forEachIndexed { at, correction ->
                    put(row[at].key, correction)
                }
            }
        }
    }
}
