package com.stratum.core.domain.sprite

/**
 * How to cut a source image into candidate frames.
 *
 * A plain columns-by-rows grid covers the case a model was *asked* for and
 * sometimes delivers. The other four numbers cover what actually arrives: a
 * margin of dead pixels around the sheet, a gap the model left between cells,
 * or a grid that starts a few pixels in. Without those, a sheet that is right
 * except for an eight pixel border has to be re-cut by hand, cell by cell —
 * which is the exact drudgery this whole editor exists to avoid.
 */
data class SliceSpec(
    val columns: Int,
    val rows: Int,
    /** Dead space before the first cell. */
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val cellWidth: Int,
    val cellHeight: Int,
    /** Gap between cells, not counted at the outer edges. */
    val gutterX: Int = 0,
    val gutterY: Int = 0,
) {
    init {
        require(columns > 0 && rows > 0) { "A slice has no cells: ${columns}x$rows" }
        require(cellWidth > 0 && cellHeight > 0) { "A cell has no area: ${cellWidth}x$cellHeight" }
    }

    val cells: Int get() = columns * rows

    /** Whether the cells are square, which is what almost every sheet wants. */
    val isSquare: Boolean get() = cellWidth == cellHeight

    val grid: SheetGrid get() = SheetGrid(columns, rows)

    /** How much image this slice covers, for checking it against the picture. */
    val coveredWidth: Int get() = offsetX + columns * cellWidth + (columns - 1) * gutterX
    val coveredHeight: Int get() = offsetY + rows * cellHeight + (rows - 1) * gutterY

    fun rectAt(column: Int, row: Int) = SourceRect(
        left = offsetX + column * (cellWidth + gutterX),
        top = offsetY + row * (cellHeight + gutterY),
        width = cellWidth,
        height = cellHeight,
    )

    companion object {
        /**
         * The slice for a sheet described by its cell size rather than its
         * column count.
         *
         * This is how downloaded art is actually documented -- an LPC sheet is
         * "64 by 64 frames", never "thirteen columns" -- and how a person
         * thinks about a sheet they can measure but not count. Working the grid
         * out from the cell size is exact where counting cells by eye on a
         * twenty-one row sheet is not.
         */
        fun ofCellSize(
            cellWidth: Int,
            cellHeight: Int,
            imageWidth: Int,
            imageHeight: Int,
            offsetX: Int = 0,
            offsetY: Int = 0,
            gutterX: Int = 0,
            gutterY: Int = 0,
        ): SliceSpec? {
            if (cellWidth <= 0 || cellHeight <= 0) return null
            val columns = (imageWidth - offsetX + gutterX) / (cellWidth + gutterX)
            val rows = (imageHeight - offsetY + gutterY) / (cellHeight + gutterY)
            if (columns <= 0 || rows <= 0) return null
            return SliceSpec(
                columns = columns,
                rows = rows,
                offsetX = offsetX,
                offsetY = offsetY,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                gutterX = gutterX,
                gutterY = gutterY,
            )
        }

        /**
         * The same grid, cut into square cells.
         *
         * Dividing an image by its column and row counts only gives square
         * cells when the image's proportions happen to match the grid's, and
         * a sheet is very often a few pixels off that -- a border the model
         * drew, a row that was padded, a width rounded to a multiple of eight.
         * The cells then come out a little oblong, every frame is stretched by
         * the ratio, and the error is small enough to look like bad art rather
         * than a bad grid.
         *
         * The cell takes the *smaller* of the two divisions. The larger would
         * run the last column or row off the edge of the image, which trades a
         * subtle error for a frame that is half missing.
         */
        fun squareFitting(
            grid: SheetGrid,
            imageWidth: Int,
            imageHeight: Int,
            offsetX: Int = 0,
            offsetY: Int = 0,
            gutterX: Int = 0,
            gutterY: Int = 0,
        ): SliceSpec? {
            if (grid.columns <= 0 || grid.rows <= 0) return null
            val usableWidth = imageWidth - offsetX - (grid.columns - 1) * gutterX
            val usableHeight = imageHeight - offsetY - (grid.rows - 1) * gutterY
            val cell = minOf(usableWidth / grid.columns, usableHeight / grid.rows)
            if (cell <= 0) return null
            return SliceSpec(
                columns = grid.columns,
                rows = grid.rows,
                offsetX = offsetX,
                offsetY = offsetY,
                cellWidth = cell,
                cellHeight = cell,
                gutterX = gutterX,
                gutterY = gutterY,
            )
        }

        /**
         * The slice that divides an image evenly, which is the honest starting
         * guess for a grid nobody has measured yet.
         *
         * Integer division deliberately leaves any remainder at the right and
         * bottom edges rather than distributing it. A cell a pixel wider than
         * its neighbours is invisible; a grid that creeps by a pixel per column
         * shears the last cell in every row, and that is very visible.
         */
        fun fitting(
            grid: SheetGrid,
            imageWidth: Int,
            imageHeight: Int,
            offsetX: Int = 0,
            offsetY: Int = 0,
            gutterX: Int = 0,
            gutterY: Int = 0,
        ): SliceSpec? {
            if (grid.columns <= 0 || grid.rows <= 0) return null
            val usableWidth = imageWidth - offsetX - (grid.columns - 1) * gutterX
            val usableHeight = imageHeight - offsetY - (grid.rows - 1) * gutterY
            val cellWidth = usableWidth / grid.columns
            val cellHeight = usableHeight / grid.rows
            if (cellWidth <= 0 || cellHeight <= 0) return null
            return SliceSpec(
                columns = grid.columns,
                rows = grid.rows,
                offsetX = offsetX,
                offsetY = offsetY,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                gutterX = gutterX,
                gutterY = gutterY,
            )
        }
    }
}

/**
 * Turns a source image into frames, with or without a grid.
 *
 * Pure: it works on rectangles and, where it needs to know what is drawn, on an
 * ARGB array. Both so the awkward parts — a grid that runs off the edge, a cell
 * with nothing in it, a figure sitting high in its box — can be tested without
 * a graphics stack, the same way [SheetInspection] is.
 */
object SpriteSlicing {

    /**
     * Every cell of [spec] that is really on the image, as frames.
     *
     * Cells are labelled by their grid position rather than numbered in
     * sequence, because a person looking at a contact sheet and a person
     * looking at the source image are both thinking "third one along, second
     * row down" and neither is counting to fifteen.
     */
    fun slice(
        spec: SliceSpec,
        imageWidth: Int,
        imageHeight: Int,
        pivot: FramePivot = FramePivot.BOTTOM_CENTER,
    ): List<FrameRef> = buildList {
        for (row in 0 until spec.rows) {
            for (column in 0 until spec.columns) {
                val rect = spec.rectAt(column, row).clampedTo(imageWidth, imageHeight) ?: continue
                add(
                    FrameRef(
                        id = frameId(column, row),
                        source = rect,
                        pivot = pivot,
                        label = "r${row + 1}c${column + 1}",
                    ),
                )
            }
        }
    }

    /** The id a sliced cell gets, so a re-slice on the same grid keeps its mapping. */
    fun frameId(column: Int, row: Int): String = "r${row}c$column"

    /**
     * Re-slices while keeping as much of the mapping as survives.
     *
     * Nudging a grid by two pixels is a thing people do constantly, and losing
     * a finished clip mapping every time would make the nudge unaffordable.
     * Ids are positional, so a cell that still exists keeps its place in every
     * clip; frames the new grid does not reach fall out of the clips with it.
     */
    fun reslice(
        atlas: SpriteAtlas,
        spec: SliceSpec,
        pivot: FramePivot = FramePivot.BOTTOM_CENTER,
    ): SpriteAtlas {
        val previous = atlas.frames.associateBy { it.id }
        // Copies made with duplicateFrame, grouped under the cell they came
        // from. They have no position of their own, so a re-slice would lose
        // them -- and with them the held poses someone set up deliberately,
        // which is a worse thing to lose than a grid nudge is to make.
        val copies = atlas.frames
            .filter { COPY_MARK in it.id && !it.id.startsWith(FrameGeometry.FREE_PREFIX) }
            .groupBy { it.id.substringBefore(COPY_MARK) }

        val next = buildList {
            for (fresh in slice(spec, atlas.sourceWidth, atlas.sourceHeight, pivot)) {
                val old = previous[fresh.id]
                // Everything the person decided about this cell is theirs; only
                // the rectangle came from the grid, so only the rectangle
                // changes.
                add(
                    if (old == null) {
                        fresh
                    } else {
                        fresh.copy(pivot = old.pivot, flippedX = old.flippedX, enabled = old.enabled)
                    },
                )
                // A copy follows the cell it was made from onto its new
                // rectangle, keeping its own flip and anchor.
                copies[fresh.id]?.forEach { copy -> add(copy.copy(source = fresh.source)) }
            }
            // Frames placed by hand have no grid position, so nothing about a
            // new grid says anything about them. They are carried across
            // untouched, and stay after the cells, which is where they were
            // added and where stepping through the sheet expects to find them.
            addAll(atlas.frames.filter { it.id.startsWith(FrameGeometry.FREE_PREFIX) })
        }

        val keptIds = next.map { it.id }.toSet()
        return atlas.copy(
            frames = next,
            clips = atlas.clips.map { clip ->
                clip.copy(frameIds = clip.frameIds.filter { it in keptIds })
            },
        )
    }

    /**
     * Where the figure meets the ground, as an x in image coordinates.
     *
     * The horizontal point a frame should be hung from, and it is not the
     * middle of the content box. An animation is a body doing something with
     * its limbs while its feet stay where they are, so the content box is
     * exactly the wrong reference: raising an arm widens the box on one side
     * and moves its centre, and hanging the frame from that centre slides the
     * whole body the other way.
     *
     * Measured on a real walk cycle, the feet landed sixty-two pixels apart
     * across a hundred and ninety-one pixel cell -- a third of the cell, every
     * frame. Played back that does not read as a walk with a wobble; it reads
     * as unrelated poses, because the thing the eye tracks between frames is
     * the part that should not be moving.
     *
     * Taken as the centroid of the bottom slice rather than its midpoint, so a
     * stance with one foot forward and one back is weighted by how much of
     * each is down. And taken from the bottom of the *figure*, which is what
     * keeps it meaningful for a body that is not standing: a rolled-up body's
     * contact patch is its back, and a fallen one's is most of it, which in
     * both cases is the part that should stay put.
     */
    fun groundAnchorX(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        rect: SourceRect,
        bottomFraction: Float = GROUND_SLICE,
    ): Int? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val bounds = contentBounds(pixels, imageWidth, imageHeight, rect) ?: return null
        val sliceHeight = (bounds.height * bottomFraction).toInt().coerceAtLeast(1)
        val from = (bounds.bottom - sliceHeight).coerceAtLeast(bounds.top)

        var total = 0L
        var count = 0
        for (y in from until bounds.bottom) {
            if (y < 0 || y >= imageHeight) continue
            val row = y * imageWidth
            for (x in bounds.left until bounds.right) {
                if (x < 0 || x >= imageWidth) continue
                if ((pixels[row + x] ushr 24) and 0xFF >= NEARLY_CLEAR) {
                    total += x
                    count++
                }
            }
        }
        // Nothing in the slice cannot really happen -- it is inside the
        // content box -- but a midpoint is a better answer than none.
        return if (count == 0) bounds.left + bounds.width / 2 else (total / count).toInt()
    }

    /**
     * The tight box around what is drawn inside [rect], or null when nothing is.
     *
     * Two jobs, and the second is the one that matters. It finds the empty
     * cells a generated sheet is full of, so they can be switched off without a
     * person squinting at each one. And it is what makes aligning by the feet
     * mean anything: a pivot applied to the cell a figure was cut from just
     * repeats the miscentring the cell already had, while a pivot applied to
     * the figure's own box puts every frame's feet on one line.
     */
    fun contentBounds(
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        rect: SourceRect,
        alphaFloor: Int = NEARLY_CLEAR,
    ): SourceRect? {
        val bounded = rect.clampedTo(imageWidth, imageHeight) ?: return null
        if (pixels.size < imageWidth * imageHeight) return null

        var left = bounded.right
        var top = bounded.bottom
        var right = bounded.left
        var bottom = bounded.top

        for (y in bounded.top until bounded.bottom) {
            val base = y * imageWidth
            for (x in bounded.left until bounded.right) {
                if ((pixels[base + x] ushr 24) and 0xFF < alphaFloor) continue
                if (x < left) left = x
                if (x >= right) right = x + 1
                if (y < top) top = y
                if (y >= bottom) bottom = y + 1
            }
        }

        if (right <= left || bottom <= top) return null
        return SourceRect(left, top, right - left, bottom - top)
    }

    /**
     * Every frame shrunk to what it actually contains, and the empty ones
     * switched off.
     *
     * Non-destructive, like everything else here: the source image is untouched
     * and a frame's rectangle can be widened again by re-slicing.
     */
    fun trimToContent(
        atlas: SpriteAtlas,
        pixels: IntArray,
        alphaFloor: Int = NEARLY_CLEAR,
    ): SpriteAtlas = atlas.copy(
        frames = atlas.frames.map { frame ->
            val tight = contentBounds(
                pixels = pixels,
                imageWidth = atlas.sourceWidth,
                imageHeight = atlas.sourceHeight,
                rect = frame.source,
                alphaFloor = alphaFloor,
            )
            // An empty cell is turned off rather than dropped. A sheet where
            // half the cells came back blank is a fact about the generation
            // worth leaving visible, and the person may want one of them back
            // as a deliberate gap in a clip.
            if (tight == null) frame.copy(enabled = false) else frame.copy(source = tight)
        },
    )

    /** Below this an edge pixel is anti-aliasing or nothing at all, not content. */
    const val NEARLY_CLEAR = 16

    /**
     * How much of a figure counts as the part touching the ground.
     *
     * An eighth. Enough that both feet are in it when one is ahead of the
     * other, and little enough that a knee or a trailing hand is not.
     */
    const val GROUND_SLICE = 0.12f

    /**
     * What separates a copy's id from the cell it was copied from.
     *
     * Sliced ids never contain it -- they are "r1c2" -- so splitting on it
     * recovers the base cell unambiguously, however many times a frame has
     * been copied.
     */
    const val COPY_MARK = "-"
}
