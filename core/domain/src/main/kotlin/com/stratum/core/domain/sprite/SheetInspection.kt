package com.stratum.core.domain.sprite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A candidate arrangement of frames on a sheet. */
data class SheetGrid(val columns: Int, val rows: Int) {
    val cells: Int get() = columns * rows
    override fun toString(): String = "${columns}x$rows"
}

/** What happened when the returned image was checked against the grid we asked for. */
enum class GridOutcome {
    /** The model drew the grid it was asked for. */
    AS_ASKED,

    /** It drew a grid, but a different one. Cut on what is actually there. */
    RECUT,

    /**
     * No grid at all — one picture. Kept whole as a single still, because
     * cutting a picture into cells is what makes a sprite pan instead of
     * animate.
     */
    SINGLE_FRAME,

    /** Nothing legible enough to judge. Left on the requested grid. */
    UNVERIFIED,
}

data class GridVerdict(
    val grid: SheetGrid,
    val outcome: GridOutcome,
    val confidence: Float,
) {
    /** Plain enough to put in front of a player who just pressed a button. */
    val summary: String
        get() = when (outcome) {
            GridOutcome.AS_ASKED -> "Cut as $grid, as asked."
            GridOutcome.RECUT -> "The model drew a $grid grid, so it was cut as one."
            GridOutcome.SINGLE_FRAME ->
                "The model drew one picture rather than a grid of frames, so it is kept " +
                    "whole as a single still. Try a different model, or a simpler subject."
            GridOutcome.UNVERIFIED -> "The grid could not be checked; cut as $grid."
        }
}

/**
 * What was done to a generated sheet before it was stored: the background dealt
 * with, and the grid checked against what the model actually drew.
 *
 * A domain value so the screen that reports it does not have to reach into the
 * adapter that produced it.
 */
data class SheetPreparation(
    val sheet: SpriteSheet,
    val keyStrategy: KeyStrategy,
    val grid: GridVerdict,
    /**
     * Almost nothing is drawn on the sheet.
     *
     * Worth saying out loud, because the way it presents in the world is a
     * character that simply is not there — the shape the renderer falls back to
     * and no explanation. A player seeing that has no way to tell an empty
     * generation from a broken game.
     */
    val looksEmpty: Boolean = false,
)

/**
 * Checks whether a returned image is really laid out on the grid we asked for.
 *
 * This exists because of one specific, very visible failure. Image models are
 * told to draw a grid of small frames; a weaker one draws a single large
 * character instead. The sheet is then cut on a grid that was never drawn, so
 * every "frame" is a crop of the same picture and the sprite appears to roll or
 * pan rather than animate. Nothing in the reply says this happened — the image
 * is the right size and decodes fine — so the only way to know is to look at
 * the pixels.
 *
 * The signal is the gutters. A real sheet has near-empty seams between cells
 * and content in the middle of them; one big picture has content straight
 * across every seam. That holds whatever the art style is, which a comparison
 * of frame contents would not.
 *
 * Pure ARGB so it can be tested without a graphics stack.
 */
object SheetInspection {

    fun inspect(
        pixels: IntArray,
        width: Int,
        height: Int,
        requested: SheetGrid,
        candidates: List<SheetGrid> = COMMON_GRIDS,
    ): GridVerdict {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return GridVerdict(requested, GridOutcome.UNVERIFIED, 0f)
        }

        val mask = contentMask(pixels, width, height)
            ?: return GridVerdict(requested, GridOutcome.UNVERIFIED, 0f)

        val requestedScore = score(mask, width, height, requested)
        if (requestedScore >= MIN_CONFIDENCE) {
            return GridVerdict(requested, GridOutcome.AS_ASKED, requestedScore)
        }

        // Only grids that could plausibly be cut from this image: a 6x7 grid on
        // a 64px thumbnail is arithmetic, not a sheet.
        val best = (candidates - requested)
            .filter { width / it.columns >= MIN_CELL_PIXELS && height / it.rows >= MIN_CELL_PIXELS }
            .map { it to score(mask, width, height, it) }
            .maxByOrNull { it.second }

        // Beating the requested grid is not enough; it has to be convincing on
        // its own, or a near-miss on the real grid would be thrown away for a
        // coarser one that happens to score a little higher.
        if (best != null && best.second >= MIN_CONFIDENCE && best.second > requestedScore + MARGIN) {
            return GridVerdict(best.first, GridOutcome.RECUT, best.second)
        }

        return GridVerdict(SheetGrid(1, 1), GridOutcome.SINGLE_FRAME, requestedScore)
    }

    /**
     * Which pixels are the subject rather than the backdrop.
     *
     * Alpha is the honest answer when it is there. When it is not — a sheet
     * whose background survived keying — the backdrop is whatever colour fills
     * most of the border, and everything unlike it is content. Without the
     * second rule an opaque sheet reads as solid content everywhere and no grid
     * could ever be found in it.
     */
    private fun contentMask(pixels: IntArray, width: Int, height: Int): BooleanArray? {
        val size = width * height
        val clear = (0 until size).count { alphaOf(pixels[it]) < NEARLY_CLEAR }
        if (clear > size * ENOUGH_ALPHA) {
            return BooleanArray(size) { alphaOf(pixels[it]) >= NEARLY_CLEAR }
        }

        val backdrop = modalBorderColor(pixels, width, height) ?: return null
        val mask = BooleanArray(size) {
            alphaOf(pixels[it]) >= NEARLY_CLEAR && !near(pixels[it], backdrop)
        }
        // A backdrop that is also most of the picture leaves nothing to measure.
        val content = mask.count { it }
        return if (content < size * MIN_CONTENT || content > size * MAX_CONTENT) null else mask
    }

    private fun modalBorderColor(pixels: IntArray, width: Int, height: Int): Int? {
        val counts = HashMap<Int, Int>()
        fun tally(index: Int) {
            val key = quantise(pixels[index])
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (x in 0 until width) {
            tally(x)
            tally((height - 1) * width + x)
        }
        for (y in 0 until height) {
            tally(y * width)
            tally(y * width + width - 1)
        }
        val border = 2 * width + 2 * height
        val top = counts.maxByOrNull { it.value } ?: return null
        return if (top.value >= border * BORDER_SHARE) top.key else null
    }

    /**
     * How much this image looks like it is laid out on [grid].
     *
     * Two things have to hold: the seams between cells are emptier than the
     * middles of cells, on both axes, and most cells actually have something in
     * them. The second rule is what rejects a single figure drawn in a corner,
     * which can otherwise produce beautifully empty seams everywhere else.
     */
    fun score(mask: BooleanArray, width: Int, height: Int, grid: SheetGrid): Float {
        if (grid.columns <= 0 || grid.rows <= 0) return 0f
        if (grid.columns == 1 && grid.rows == 1) return 0f
        val cellWidth = width / grid.columns
        val cellHeight = height / grid.rows
        if (cellWidth < MIN_CELL_PIXELS || cellHeight < MIN_CELL_PIXELS) return 0f

        val columnDensity = FloatArray(width)
        val rowDensity = FloatArray(height)
        for (y in 0 until height) {
            var rowCount = 0
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x]) {
                    rowCount++
                    columnDensity[x] += 1f
                }
            }
            rowDensity[y] = rowCount.toFloat() / width
        }
        for (x in 0 until width) columnDensity[x] /= height

        val across = axisScore(columnDensity, width, grid.columns)
        val down = axisScore(rowDensity, height, grid.rows)
        // Both axes, not the average: a grid is only a grid if it holds in both
        // directions, and averaging lets one strong axis carry a false match.
        val gutters = minOf(across, down)

        return gutters * cellCoverage(mask, width, height, grid)
    }

    private fun axisScore(density: FloatArray, length: Int, divisions: Int): Float {
        if (divisions < 2) {
            // A single division has no seams to read, so it cannot be confirmed
            // this way; it is neither evidence for nor against.
            return NO_SEAM_SCORE
        }
        val cell = length.toFloat() / divisions
        val band = max(1, (cell / SEAM_BAND).roundToInt())

        var seam = 0f
        var seamCount = 0
        for (division in 1 until divisions) {
            val centre = (division * cell).roundToInt()
            for (offset in -band..band) {
                val index = centre + offset
                if (index in 0 until length) {
                    seam += density[index]
                    seamCount++
                }
            }
        }

        var core = 0f
        var coreCount = 0
        for (division in 0 until divisions) {
            val start = (division * cell + cell * CORE_INSET).roundToInt()
            val end = ((division + 1) * cell - cell * CORE_INSET).roundToInt()
            for (index in start until end) {
                if (index in 0 until length) {
                    core += density[index]
                    coreCount++
                }
            }
        }

        if (seamCount == 0 || coreCount == 0) return 0f
        val seamMean = seam / seamCount
        val coreMean = core / coreCount
        if (coreMean <= 0f) return 0f
        return (1f - seamMean / coreMean).coerceIn(0f, 1f)
    }

    /** The share of cells with anything drawn in them. */
    private fun cellCoverage(
        mask: BooleanArray,
        width: Int,
        height: Int,
        grid: SheetGrid,
    ): Float {
        val cellWidth = width / grid.columns
        val cellHeight = height / grid.rows
        var occupied = 0
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                var count = 0
                val x0 = column * cellWidth
                val y0 = row * cellHeight
                for (y in y0 until y0 + cellHeight) {
                    val base = y * width
                    for (x in x0 until x0 + cellWidth) {
                        if (mask[base + x]) count++
                    }
                }
                if (count > cellWidth * cellHeight * OCCUPIED_CELL) occupied++
            }
        }
        return occupied.toFloat() / grid.cells
    }

    private fun quantise(color: Int): Int {
        val r = (redOf(color) / QUANTISE) * QUANTISE
        val g = (greenOf(color) / QUANTISE) * QUANTISE
        val b = (blueOf(color) / QUANTISE) * QUANTISE
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(redOf(a) - redOf(b)) <= TOLERANCE &&
            abs(greenOf(a) - greenOf(b)) <= TOLERANCE &&
            abs(blueOf(a) - blueOf(b)) <= TOLERANCE

    private fun alphaOf(color: Int) = (color ushr 24) and 0xFF
    private fun redOf(color: Int) = (color ushr 16) and 0xFF
    private fun greenOf(color: Int) = (color ushr 8) and 0xFF
    private fun blueOf(color: Int) = color and 0xFF

    /** Grids worth trying when the one we asked for is not what came back. */
    val COMMON_GRIDS = listOf(
        SheetGrid(6, 7),
        SheetGrid(4, 4),
        SheetGrid(3, 4),
        SheetGrid(4, 2),
        SheetGrid(2, 2),
    )

    /** Below this a grid is not believed. */
    const val MIN_CONFIDENCE = 0.35f

    /** How much better another grid must be before the asked-for one is abandoned. */
    private const val MARGIN = 0.1f
    private const val NEARLY_CLEAR = 16
    /** Enough cleared pixels that alpha alone can be trusted to mark content. */
    private const val ENOUGH_ALPHA = 0.12f
    private const val QUANTISE = 16
    private const val TOLERANCE = 24
    private const val BORDER_SHARE = 0.6f
    private const val MIN_CONTENT = 0.02f
    private const val MAX_CONTENT = 0.92f
    /** A cell smaller than this is arithmetic, not a drawing. */
    private const val MIN_CELL_PIXELS = 16
    /** Seam band as a fraction of a cell: a tenth either side of the line. */
    private const val SEAM_BAND = 10f
    /** How far inside a cell the part that should hold the drawing starts. */
    private const val CORE_INSET = 0.2f
    /** Content covering this share of a cell counts as the cell being used. */
    private const val OCCUPIED_CELL = 0.01f
    /** A one-division axis is unprovable either way, so it neither helps nor hurts. */
    private const val NO_SEAM_SCORE = 0.5f
}
