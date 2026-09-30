package com.stratum.core.domain.sprite

/**
 * What one keyed frame actually contains, measured once.
 *
 * The composer used to decode, key and measure every pose three times — once
 * for its box, once for where its feet were, once to draw it — and every one of
 * those passes lived in an Android adapter where nothing could test it. The
 * measuring is plain arithmetic over an ARGB buffer, so it lives here, runs
 * once per frame, and answers every question the rest of the pipeline asks:
 * where the figure is, where it stands, whether anything is there at all, and
 * whether the model drew one figure or scattered pieces of several.
 */
data class FrameAnalysis(
    val width: Int,
    val height: Int,
    /** The tight box around what is drawn, or null for an empty frame. */
    val bounds: SourceRect?,
    /** Where the figure meets the ground, in source pixels; null when empty. */
    val anchorX: Int?,
    /** Pixels solid enough to count as the figure. */
    val opaquePixels: Int,
    /** Which canvas edges the figure is cut off by. */
    val touchedEdges: Set<Edge>,
    /**
     * Sizes of the separate pieces of drawing, largest first, measured on a
     * coarse grid so a stray antialiased pixel does not count as a figure.
     */
    val blobs: List<Int>,
    /** A small fixed-size mask of the figure's shape, for comparing two frames. */
    val silhouette: Silhouette?,
) {
    val isEmpty: Boolean get() = bounds == null

    /** The share of the canvas the figure covers, 0 to 1. */
    val coverage: Float
        get() = if (width <= 0 || height <= 0) 0f else opaquePixels.toFloat() / (width * height)

    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }
}

/**
 * A figure's shape at a fixed coarse resolution, stretched to its own box.
 *
 * Stretched rather than placed, so two drawings of the same pose at different
 * sizes and positions compare as equal. That is exactly what is wanted when the
 * question is "did the model return the reference unchanged" or "are these two
 * cells the same drawing twice": the answer should not depend on where on the
 * canvas either happened to land.
 */
data class Silhouette(val columns: Int, val rows: Int, val cells: BooleanArray) {

    /**
     * Intersection over union, 0 for nothing in common and 1 for identical.
     *
     * Two empty silhouettes are identical, which never arises in practice
     * because an empty frame has no silhouette at all.
     */
    fun similarity(other: Silhouette): Float {
        if (columns != other.columns || rows != other.rows) return 0f
        var both = 0
        var either = 0
        for (i in cells.indices) {
            val a = cells[i]
            val b = other.cells[i]
            if (a && b) both++
            if (a || b) either++
        }
        return if (either == 0) 1f else both.toFloat() / either
    }

    // Array identity would make equal shapes unequal.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Silhouette && columns == other.columns && rows == other.rows && cells.contentEquals(other.cells))

    override fun hashCode(): Int = (columns * 31 + rows) * 31 + cells.contentHashCode()
}

object FrameAnalyser {

    /**
     * Measures [pixels], which must already have had their background keyed out.
     *
     * Pure and single-pass apart from the blob count, which works on a coarse
     * grid: counting connected pixels at full resolution on a 1024 canvas is a
     * million-entry flood for an answer that needs a few dozen cells.
     */
    fun analyse(
        pixels: IntArray,
        width: Int,
        height: Int,
        alphaFloor: Int = SpriteSlicing.NEARLY_CLEAR,
    ): FrameAnalysis {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return FrameAnalysis(width, height, null, null, 0, emptySet(), emptyList(), null)
        }
        val whole = SourceRect(0, 0, width, height)
        val bounds = SpriteSlicing.contentBounds(pixels, width, height, whole, alphaFloor)
            ?: return FrameAnalysis(width, height, null, null, 0, emptySet(), emptyList(), null)

        var opaque = 0
        for (pixel in pixels) if (alphaOf(pixel) >= alphaFloor) opaque++

        val anchor = SpriteSlicing.groundAnchorX(pixels, width, height, bounds)
        return FrameAnalysis(
            width = width,
            height = height,
            bounds = bounds,
            anchorX = anchor,
            opaquePixels = opaque,
            touchedEdges = edgesTouched(pixels, width, height, bounds, alphaFloor),
            blobs = blobs(pixels, width, height, alphaFloor),
            silhouette = silhouette(pixels, width, bounds, alphaFloor),
        )
    }

    /**
     * Edges the figure is cut off by, counted only where a real run of it
     * touches: a single antialiased pixel on the border is not a cropped arm.
     */
    private fun edgesTouched(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: SourceRect,
        alphaFloor: Int,
    ): Set<FrameAnalysis.Edge> = buildSet {
        val minRun = maxOf(MIN_EDGE_RUN, minOf(width, height) / EDGE_RUN_DIVISOR)
        fun countRow(y: Int) = (0 until width).count { alphaOf(pixels[y * width + it]) >= alphaFloor }
        fun countColumn(x: Int) = (0 until height).count { alphaOf(pixels[it * width + x]) >= alphaFloor }
        if (bounds.left == 0 && countColumn(0) >= minRun) add(FrameAnalysis.Edge.LEFT)
        if (bounds.top == 0 && countRow(0) >= minRun) add(FrameAnalysis.Edge.TOP)
        if (bounds.right == width && countColumn(width - 1) >= minRun) add(FrameAnalysis.Edge.RIGHT)
        if (bounds.bottom == height && countRow(height - 1) >= minRun) add(FrameAnalysis.Edge.BOTTOM)
    }

    /**
     * Connected pieces of drawing on a coarse grid, largest first.
     *
     * A cell counts when a meaningful share of it is drawn on, and pieces are
     * joined through all eight neighbours, so a sword hand a pixel away from
     * the arm is still one figure. What survives as a second large piece is a
     * second figure, or the same one drawn twice.
     */
    private fun blobs(pixels: IntArray, width: Int, height: Int, alphaFloor: Int): List<Int> {
        val cell = maxOf(1, maxOf(width, height) / BLOB_GRID)
        val columns = (width + cell - 1) / cell
        val rows = (height + cell - 1) / cell
        val counts = IntArray(columns * rows)
        for (y in 0 until height) {
            val row = (y / cell) * columns
            val base = y * width
            for (x in 0 until width) {
                if (alphaOf(pixels[base + x]) >= alphaFloor) counts[row + x / cell]++
            }
        }
        val need = maxOf(1, (cell * cell * BLOB_CELL_FILL).toInt())
        val filled = BooleanArray(counts.size) { counts[it] >= need }
        val seen = BooleanArray(counts.size)
        val sizes = mutableListOf<Int>()
        val queue = ArrayDeque<Int>()
        for (start in filled.indices) {
            if (!filled[start] || seen[start]) continue
            var size = 0
            seen[start] = true
            queue += start
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                size++
                val cx = at % columns
                val cy = at / columns
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= columns || ny >= rows) continue
                    val next = ny * columns + nx
                    if (filled[next] && !seen[next]) {
                        seen[next] = true
                        queue += next
                    }
                }
            }
            sizes += size
        }
        return sizes.sortedDescending()
    }

    private fun silhouette(pixels: IntArray, width: Int, bounds: SourceRect, alphaFloor: Int): Silhouette {
        val cells = BooleanArray(SIL_COLUMNS * SIL_ROWS)
        for (row in 0 until SIL_ROWS) {
            val top = bounds.top + row * bounds.height / SIL_ROWS
            val bottom = maxOf(top + 1, bounds.top + (row + 1) * bounds.height / SIL_ROWS)
            for (column in 0 until SIL_COLUMNS) {
                val left = bounds.left + column * bounds.width / SIL_COLUMNS
                val right = maxOf(left + 1, bounds.left + (column + 1) * bounds.width / SIL_COLUMNS)
                var drawn = 0
                var total = 0
                for (y in top until bottom) for (x in left until right) {
                    total++
                    if (alphaOf(pixels[y * width + x]) >= alphaFloor) drawn++
                }
                cells[row * SIL_COLUMNS + column] = total > 0 && drawn * 2 >= total
            }
        }
        return Silhouette(SIL_COLUMNS, SIL_ROWS, cells)
    }

    private fun alphaOf(color: Int) = (color ushr 24) and 0xFF

    /** The coarse grid the blob count runs on, cells along the longer side. */
    private const val BLOB_GRID = 48

    /** A coarse cell is drawing when this share of it is. */
    private const val BLOB_CELL_FILL = 0.2f

    private const val MIN_EDGE_RUN = 3
    private const val EDGE_RUN_DIVISOR = 64

    const val SIL_COLUMNS = 12
    const val SIL_ROWS = 16
}
