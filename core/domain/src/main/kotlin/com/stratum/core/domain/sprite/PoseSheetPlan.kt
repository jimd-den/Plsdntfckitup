package com.stratum.core.domain.sprite

import kotlin.math.ceil
import kotlin.math.min

/** Where one generated pose lands on the finished sheet. */
data class PoseCell(
    /** Matches the pose step that asked for it, so a retry replaces the right cell. */
    val key: String,
    val state: AnimationState,
    val index: Int,
    val column: Int,
    val row: Int,
) {

    fun cellIndex(columns: Int): Int = row * columns + column

    companion object {
        /**
         * How a pose is named, in one place.
         *
         * The step that asks for a pose and the cell that receives it are built
         * by different layers at different times, and they find each other by
         * this string. Two copies of the format would drift, and the symptom
         * would be a run that generates forty frames and composites none of
         * them.
         */
        fun keyOf(state: AnimationState, index: Int, viewSuffix: String = ""): String =
            "${state.name.lowercase()}_$index$viewSuffix"
    }
}

/**
 * The sheet a set of separately generated poses will be composited into.
 *
 * Planned before a single pose has been drawn, which is what makes the whole
 * pipeline restartable. Each generated frame knows the cell it belongs in from
 * the moment it is asked for, so a run that fails on frame nine can be resumed
 * at frame nine, and a frame that came back wrong can be regenerated on its own
 * and dropped back into place without disturbing the other thirty-nine.
 */
data class PoseSheetPlan(
    val cellWidth: Int,
    val cellHeight: Int,
    val columns: Int,
    val rows: Int,
    val cells: List<PoseCell>,
    val sheet: SpriteSheet,
) {
    val width: Int get() = columns * cellWidth
    val height: Int get() = rows * cellHeight

    fun cellFor(key: String): PoseCell? = cells.firstOrNull { it.key == key }

    /** Where a cell sits on the composited image, for the layer that has pixels. */
    fun rectFor(cell: PoseCell): FrameRect = FrameRect(
        left = cell.column * cellWidth,
        top = cell.row * cellHeight,
        width = cellWidth,
        height = cellHeight,
    )

    /**
     * The same plan with cells shaped like the figure that will stand in them.
     *
     * Square cells are what make a finished sheet look far coarser than the
     * pixel count suggests. A standing human is roughly a third as wide as it
     * is tall, and a set scaled to fit the tallest frame into a square cell
     * then spends two thirds of every cell on nothing: measured on real
     * generated art, the character came out 34 pixels wide inside a 96 pixel
     * cell. The lost detail is all in the direction that carries the
     * silhouette -- an arm, a blade, the gap between the legs.
     *
     * So the height asked for is honoured and the width is taken from the art:
     * the widest and tallest content across the whole set, which is the same
     * box the common scale is derived from. Every cell keeps identical
     * dimensions, because the runtime cuts frames on a fixed grid, and a
     * per-frame cell would shear the sheet.
     *
     * @param contentWidth the widest content box in the set, in source pixels.
     * @param contentHeight the tallest. Both come from measuring; this cannot
     *   be decided when the plan is made, because nothing has been drawn yet.
     */
    fun fittedTo(contentWidth: Int, contentHeight: Int): PoseSheetPlan {
        if (contentWidth <= 0 || contentHeight <= 0) return this

        var height = cellHeight
        var width = ceil(height.toDouble() * contentWidth / contentHeight).toInt()
            .coerceAtLeast(PoseSheetPlanner.MIN_CELL)

        // A sheet no GPU will take as one texture is worse than a coarse one.
        val shrink = min(
            AtlasBaker.MAX_SHEET_EDGE.toDouble() / (columns.toDouble() * width),
            AtlasBaker.MAX_SHEET_EDGE.toDouble() / (rows.toDouble() * height),
        )
        if (shrink < 1.0) {
            width = (width * shrink).toInt().coerceAtLeast(PoseSheetPlanner.MIN_CELL)
            height = (height * shrink).toInt().coerceAtLeast(PoseSheetPlanner.MIN_CELL)
        }

        if (width == cellWidth && height == cellHeight) return this
        return copy(
            cellWidth = width,
            cellHeight = height,
            sheet = sheet.copy(frameWidth = width, frameHeight = height),
        )
    }
}

/**
 * A packed sheet, and the cells nothing could be put in.
 *
 * [missing] is almost always empty and matters entirely when it is not. A pose
 * whose file is unreadable leaves a hole in the middle of an animation, and a
 * hole looks exactly like a frame the character is invisible for -- which is
 * the one failure nobody can diagnose from watching it happen.
 */
data class PackedSheet(val sheet: SpriteSheet, val missing: List<String>)

/**
 * Lays generated poses out as a sheet the engine can already draw.
 *
 * One state per row, short rows padded, exactly as [AtlasBaker] does — and for
 * the same reason. It costs a few empty cells and buys a sheet a person can
 * open in an image editor and understand at a glance, which matters more for an
 * asset that took forty generations to make than the kilobytes do.
 *
 * Cells start square and are reshaped once the art has been measured, by
 * [PoseSheetPlan.fittedTo]. Nothing is known about the figure's proportions at
 * planning time -- not one frame has been drawn -- so the size asked for here
 * is the height budget, and the width is settled later by what turned up.
 */
object PoseSheetPlanner {

    fun plan(
        id: String,
        name: String,
        frameCounts: Map<AnimationState, Int>,
        cellSize: Int = DEFAULT_CELL,
        origin: SpriteOrigin = SpriteOrigin.AI_GENERATED,
        /**
         * The angles drawn, as (key suffix, the facings that angle serves).
         *
         * Passed as plain data rather than as the generation layer's enum, so
         * the sheet model keeps knowing nothing about how its art was made.
         */
        views: List<Pair<String, List<SpriteFacing>>> =
            listOf("" to listOf(SpriteFacing.SOUTH_EAST, SpriteFacing.SOUTH_WEST)),
    ): PoseSheetPlan? {
        val rows = AnimationState.generatedRowOrder
            .mapNotNull { state -> frameCounts[state]?.takeIf { it > 0 }?.let { state to it } }
        if (rows.isEmpty()) return null

        val columns = rows.maxOf { it.second }
        val size = cellSize.coerceIn(MIN_CELL, MAX_CELL)

        val angles = views.ifEmpty {
            listOf("" to listOf(SpriteFacing.SOUTH_EAST, SpriteFacing.SOUTH_WEST))
        }

        val cells = mutableListOf<PoseCell>()
        val clips = mutableListOf<AnimationClip>()
        val facingRows = mutableMapOf<SpriteFacing, Int>()

        // One block of rows per angle, stacked. The runtime reads a facing as
        // a row offset added to the frame index, so a clip written once
        // against the first block serves every angle: the offset carries it
        // into the right block, and the mirror rule covers the other side of
        // each pair. That is what keeps a two-angle sheet from needing two of
        // everything else.
        angles.forEachIndexed { angle, (suffix, facings) ->
            val blockRow = angle * rows.size
            facings.forEach { facing -> facingRows[facing] = blockRow }

            rows.forEachIndexed { row, (state, count) ->
                for (index in 0 until count) {
                    cells += PoseCell(
                        key = PoseCell.keyOf(state, index, suffix),
                        state = state,
                        index = index,
                        column = index,
                        row = blockRow + row,
                    )
                }
                // Clips come from the first block only. A second set would be
                // the same animation named twice, and the sheet has one clip
                // per state by construction.
                if (angle == 0) {
                    clips += AnimationClip(
                        state = state,
                        firstFrame = row * columns,
                        frameCount = count,
                        frameDurationMs = state.defaultFrameDurationMs,
                        loops = state !in AnimationState.oneShot,
                    )
                }
            }
        }

        val totalRows = rows.size * angles.size

        return PoseSheetPlan(
            cellWidth = size,
            cellHeight = size,
            columns = columns,
            rows = totalRows,
            cells = cells,
            sheet = SpriteSheet(
                id = id,
                name = name,
                columns = columns,
                rows = totalRows,
                frameWidth = size,
                frameHeight = size,
                clips = clips,
                facingRows = facingRows,
                // Still mirrored: each drawn angle covers its own pair by
                // being flipped, which is why two drawn angles cover four.
                mirrorsFacings = true,
                origin = origin,
            ),
        )
    }

    /**
     * 192 pixels tall a cell.
     *
     * This was 96, reasoning that the sprite is drawn about a tile and a half
     * wide and anything more is detail nobody sees. Both halves of that were
     * wrong. A square 96 cell gave an idle figure 34 pixels across, so the
     * number that mattered was never the cell -- and a sheet is authored once
     * and then looked at in an editor, zoomed, and rescaled for other
     * densities, none of which the on-screen size governs.
     *
     * Doubling the height is what buys the detail: four times the pixels on
     * the character. Cutting the width to the figure is what pays for it, by
     * not storing the empty background that a square cell spends half its
     * area on. Together the sheet grows a little over twice, not four times.
     */
    const val DEFAULT_CELL = 192

    /** Below this a character has no silhouette; above it, no sheet fits in a texture. */
    const val MIN_CELL = 32
    const val MAX_CELL = 512
}
