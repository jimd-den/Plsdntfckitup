package com.stratum.core.domain.sprite

/**
 * An undo stack with a depth limit.
 *
 * Immutable, like the atlas it holds, so the mapper's state is one value that
 * can be tested and restored, rather than a value plus a mutable deque living
 * beside it in the view model.
 */
data class EditHistory<T>(
    val past: List<T> = emptyList(),
    val depth: Int = DEFAULT_DEPTH,
) {
    val canUndo: Boolean get() = past.isNotEmpty()

    /** Remembers [previous], forgetting the oldest entry past [depth]. */
    fun record(previous: T): EditHistory<T> = copy(past = (past + previous).takeLast(depth))

    /** The value to go back to and the history without it; null when there is nothing to undo. */
    fun undo(): Pair<T, EditHistory<T>>? =
        past.lastOrNull()?.let { it to copy(past = past.dropLast(1)) }

    fun cleared(): EditHistory<T> = copy(past = emptyList())

    companion object {
        const val DEFAULT_DEPTH = 40
    }
}

/** The mapper's grid rules that are not about any one screen. */
object MapperGrid {

    /** More divisions than this on a phone screen is a grid nobody can tap. */
    const val MAX_DIVISIONS = 16

    /**
     * Below this, a box drawn on the sheet was a tap that slid.
     *
     * Eight source pixels. Small enough that a genuinely tiny cell can still be
     * drawn, large enough that a finger resting on the image does not cut the
     * sheet into thousands of cells and lose the mapping.
     */
    const val MIN_DRAWN_CELL = 8

    /**
     * The grid implied by a box drawn around one cell, or null for a box too
     * small to be one.
     *
     * The box is the margin and the cell size together: where it starts is
     * where the grid starts, how big it is is how big a cell is, and the
     * column and row counts fall out of the image size. The gap is dropped: a
     * box around one cell says nothing about what sits between cells, and a
     * gutter kept from the grid this replaces would shift every cell after the
     * first.
     */
    fun fromBox(rect: SourceRect, imageWidth: Int, imageHeight: Int, square: Boolean): SliceSpec? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = rect.left.coerceIn(0, imageWidth - 1)
        val top = rect.top.coerceIn(0, imageHeight - 1)
        var width = rect.width.coerceAtMost(imageWidth - left)
        var height = rect.height.coerceAtMost(imageHeight - top)
        if (width < MIN_DRAWN_CELL || height < MIN_DRAWN_CELL) return null
        if (square) {
            val side = minOf(width, height)
            width = side
            height = side
        }
        return SliceSpec.ofCellSize(width, height, imageWidth, imageHeight, offsetX = left, offsetY = top)
    }

    /** A grid of [columns] by [rows], clamped to what can be tapped, square or not. */
    fun of(
        columns: Int,
        rows: Int,
        imageWidth: Int,
        imageHeight: Int,
        square: Boolean,
        offsetX: Int = 0,
        offsetY: Int = 0,
        gutterX: Int = 0,
        gutterY: Int = 0,
    ): SliceSpec? {
        val grid = SheetGrid(columns.coerceIn(1, MAX_DIVISIONS), rows.coerceIn(1, MAX_DIVISIONS))
        val cut = if (square) SliceSpec::squareFitting else SliceSpec::fitting
        return cut(
            grid, imageWidth, imageHeight,
            offsetX.coerceAtLeast(0), offsetY.coerceAtLeast(0),
            gutterX.coerceAtLeast(0), gutterY.coerceAtLeast(0),
        )
    }

    /** The first grid that fits a sheet: square cells if possible, even ones otherwise. */
    fun initial(grid: SheetGrid, imageWidth: Int, imageHeight: Int): SliceSpec? =
        SliceSpec.squareFitting(grid, imageWidth, imageHeight)
            ?: SliceSpec.fitting(grid, imageWidth, imageHeight)
}

/** Moving through a sheet's frames one at a time. */
object FrameCursor {

    /**
     * The frame after or before [current], wrapping.
     *
     * Wrapping rather than stopping, because the gesture is working straight
     * through a sheet: press next, fix, press next. An end that refuses to move
     * reads as the button having broken.
     */
    fun step(frames: List<FrameRef>, current: String?, forward: Boolean): String? {
        if (frames.isEmpty()) return null
        val at = frames.indexOfFirst { it.id == current }
        val count = frames.size
        val next = when {
            at < 0 -> 0
            forward -> (at + 1) % count
            else -> (at - 1 + count) % count
        }
        return frames[next].id
    }

    /**
     * Where focus goes when the focused frame is deleted: to the frame that
     * slid into its place, so a sweep through junk cells carries on.
     */
    fun afterRemoval(remaining: List<FrameRef>, removedIndex: Int): String? =
        if (remaining.isEmpty() || removedIndex < 0) null
        else remaining[removedIndex.coerceAtMost(remaining.size - 1)].id
}
