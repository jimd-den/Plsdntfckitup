package com.stratum.core.domain.sprite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How a sheet's background was dealt with. */
enum class KeyStrategy {
    /** The model returned real alpha. Left alone. */
    ALREADY_TRANSPARENT,

    /**
     * The model drew the checkerboard people associate with transparency,
     * instead of being transparent. Cleared everywhere it appears.
     */
    CHECKERBOARD,

    /** A solid backdrop, cleared inward from the edges. */
    SOLID,

    /**
     * A saturated chroma backdrop, cleared by colour range wherever it lands.
     */
    CHROMA,

    /** Nothing confidently identifiable. Left alone rather than guessed at. */
    NONE,
}

data class KeyResult(
    val pixels: IntArray,
    val strategy: KeyStrategy,
    val clearedPixels: Int,
) {
    val cleared: Boolean get() = clearedPixels > 0

    // Array identity would make two equal results unequal, which is surprising
    // for a value type; compare the contents.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is KeyResult &&
                strategy == other.strategy &&
                clearedPixels == other.clearedPixels &&
                pixels.contentEquals(other.pixels))

    override fun hashCode(): Int =
        pixels.contentHashCode() * 31 * 31 + strategy.hashCode() * 31 + clearedPixels
}

/**
 * Removes the background an image model drew instead of leaving transparent.
 *
 * Asking for a transparent background is not enough. Models routinely answer
 * with the grey checkerboard that *represents* transparency in image editors —
 * a picture of transparency rather than transparency — or with a flat colour.
 * Either way the sprite arrives as an opaque square and gets drawn on the world
 * as one.
 *
 * Works on raw ARGB so it can be tested without a graphics stack, and so the
 * rule lives beside the sheet model rather than in an Android adapter.
 *
 * It never crops or resizes: frames are cut on a fixed grid, and trimming the
 * sheet would shear every frame off its cell.
 */
object SpriteKeying {

    /**
     * @param cells the grid the sheet is laid out on, when it is known. It
     *   settles the question a flood fill cannot: whether a colour is the
     *   canvas or the costume. A colour that borders *every cell* is the canvas
     *   — no character is drawn identically around all forty-two frames — and
     *   can be cleared wherever it appears, including the pockets a flood from
     *   the outside never reaches, like the gap between a pair of legs. Not
     *   known on the first pass, because the grid is found by looking at what
     *   keying leaves behind.
     */
    fun key(
        pixels: IntArray,
        width: Int,
        height: Int,
        tolerance: Int = DEFAULT_TOLERANCE,
        cells: SheetGrid? = null,
    ): KeyResult {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return KeyResult(pixels, KeyStrategy.NONE, 0)
        }

        // Real alpha already? Leave it: a sheet that came back correct must not
        // be second-guessed by a heuristic.
        val transparent = pixels.count { alphaOf(it) < NEARLY_CLEAR }
        if (transparent > pixels.size * ALREADY_CLEAR_FRACTION) {
            return KeyResult(pixels, KeyStrategy.ALREADY_TRANSPARENT, 0)
        }

        val border = borderColors(pixels, width, height)
        val dominant = dominantColors(border, tolerance)
        if (dominant.isEmpty()) return KeyResult(pixels, KeyStrategy.NONE, 0)

        val out = pixels.copyOf()

        // A checkerboard is two flat greys and is never part of a character, so
        // it can be cleared wherever it appears — including the gaps a flood
        // fill from the edge would never reach, like between a pair of legs.
        if (looksLikeCheckerboard(dominant)) {
            val cleared = clearEverywhere(out, dominant, tolerance)
            return survivorsOf(pixels, out, KeyStrategy.CHECKERBOARD, cleared)
        }

        // A chroma backdrop is not a colour to match, it is a range to drop.
        //
        // Everything below matches the backdrop's *colour*, within a tolerance
        // of a few steps, and then argues about which of the matching pixels
        // may be cleared. Neither half survives contact with real chroma. The
        // backdrop is asked for as flat #00FF00 and comes back compressed, so
        // it arrives as a spread of greens rather than one; and the pixels
        // that matter most are the ones a flood can never reach, in the pocket
        // between an arm and the ribs or inside a bent elbow, which is where
        // bright green was found sitting *inside* the character on four of
        // nine real poses.
        //
        // Asking instead whether a pixel is green-dominant answers both at
        // once, and answers the edge as well: a pixel half backdrop and half
        // bronze is still green-dominant and still has no business being
        // opaque. No part of a character is this green -- the test is not
        // "greenish", it is a green channel clear of both others by more than
        // a fifth of the range -- so nothing of the figure is at stake.
        if (dominant.any { isChroma(it) }) {
            val cleared = clearChroma(out)
            return survivorsOf(pixels, out, KeyStrategy.CHROMA, cleared)
        }

        // A colour that surrounds every frame is the canvas. Clearing it
        // wherever it appears is then safe in the way the checkerboard is, and
        // reaches the pockets a flood cannot: between the legs, inside the ring
        // of an arm, through the eye of an axe.
        //
        // Judged one colour at a time, never as a set. The border of a dense
        // sheet is not always one colour — figures touch the edges of their
        // cells — so the second colour taken from it is quite often the
        // character's own. Asking whether the *pair* frames every cell lets
        // that colour in on the backdrop's evidence, and clearing it globally
        // then erases the character from every frame at once.
        //
        // The trade the rule does make: a character drawn in the canvas's own
        // colour loses any pocket of it enclosed by its own outline. That is
        // the same trade the checkerboard rule makes, and it is the right way
        // round — a hole in one frame is a blemish, a backdrop on every frame
        // is a sprite that cannot be used at all.
        val canvas = if (cells != null && cells.cells > 1) {
            dominant.filter { framesEveryCell(pixels, width, height, cells, it, tolerance) }
        } else {
            emptyList()
        }

        var cleared = clearEverywhere(out, canvas, tolerance)
        // Whatever is left of the backdrop but was not proved to be canvas
        // still goes if it is connected to the outside. Running second lets the
        // flood travel through what the canvas pass already cleared.
        cleared += floodFromEdges(out, width, height, dominant, tolerance)

        return survivorsOf(pixels, out, KeyStrategy.SOLID, cleared)
    }

    /**
     * Accepts a keyed result only if a sprite survived it.
     *
     * The failure this catches is total: a colour misjudged as backdrop takes
     * the character with it, and what reaches the world is a fully transparent
     * sheet that draws as nothing at all. That is far worse than the background
     * it was trying to remove, and unlike a background it gives the player
     * nothing to look at and no way to guess why.
     */
    private fun survivorsOf(
        original: IntArray,
        keyed: IntArray,
        strategy: KeyStrategy,
        cleared: Int,
    ): KeyResult {
        if (cleared <= 0) return KeyResult(original, KeyStrategy.NONE, 0)
        val surviving = keyed.count { alphaOf(it) > 0 }
        return if (surviving < keyed.size * MIN_SURVIVING) {
            KeyResult(original, KeyStrategy.NONE, 0)
        } else {
            KeyResult(keyed, strategy, cleared)
        }
    }

    /**
     * Two low-saturation colours a clear step apart in brightness: the
     * transparency checkerboard every image editor draws.
     */
    private fun looksLikeCheckerboard(dominant: List<Int>): Boolean {
        if (dominant.size != 2) return false
        if (dominant.any { saturationOf(it) > CHECKER_MAX_SATURATION }) return false
        val gap = abs(luminanceOf(dominant[0]) - luminanceOf(dominant[1]))
        return gap in CHECKER_MIN_GAP..CHECKER_MAX_GAP
    }

    private fun borderColors(pixels: IntArray, width: Int, height: Int): List<Int> = buildList {
        for (x in 0 until width) {
            add(pixels[x])
            add(pixels[(height - 1) * width + x])
        }
        for (y in 0 until height) {
            add(pixels[y * width])
            add(pixels[y * width + width - 1])
        }
    }

    /**
     * The few colours that make up most of the border, quantised so that noise
     * and compression do not split one backdrop into a hundred shades.
     */
    private fun dominantColors(border: List<Int>, tolerance: Int): List<Int> {
        if (border.isEmpty()) return emptyList()
        val buckets = HashMap<Int, Int>()
        border.forEach { buckets[quantise(it)] = (buckets[quantise(it)] ?: 0) + 1 }

        val ranked = merged(buckets, tolerance)
        val chosen = mutableListOf<Int>()
        var covered = 0
        for (entry in ranked) {
            if (chosen.size >= MAX_BACKGROUND_COLORS) break
            chosen += entry.first
            covered += entry.second
            if (covered >= border.size * BORDER_COVERAGE) break
        }
        // A border that is mostly the subject is not a background to key out.
        return if (covered >= border.size * BORDER_COVERAGE) chosen else emptyList()
    }

    /**
     * Buckets that are the same colour, counted as the same colour.
     *
     * Quantising alone assumes a flat backdrop lands in one bucket, which is
     * true of a PNG and false of a JPEG. Some providers return JPEG whatever
     * was asked for, and its ringing scatters one flat chroma green across half
     * a dozen neighbouring buckets -- measured at 37% in the largest where the
     * same image as PNG gives 100%. The backdrop then falls under the coverage
     * bar and nothing is keyed at all, so the sprite arrives wearing its
     * background and the character cannot be used.
     *
     * Merging is by the same tolerance the flood fill already uses, so this
     * admits nothing the clearing step would not have taken anyway. Shades a
     * step apart join; the two greys of a checkerboard are far further apart
     * than that and stay separate, which is what keeps that rule working.
     */
    private fun merged(buckets: Map<Int, Int>, tolerance: Int): List<Pair<Int, Int>> {
        val ranked = buckets.entries.sortedByDescending { it.value }
        val heads = mutableListOf<Pair<Int, Int>>()
        for (entry in ranked) {
            // Heaviest first, so a merged group is named by its densest shade
            // rather than by whichever fringe bucket happened to be seen first.
            val at = heads.indexOfFirst { near(it.first, entry.key, tolerance) }
            if (at < 0) {
                heads += entry.key to entry.value
            } else {
                heads[at] = heads[at].first to (heads[at].second + entry.value)
            }
        }
        return heads.sortedByDescending { it.second }
    }

    private fun floodFromEdges(
        out: IntArray,
        width: Int,
        height: Int,
        background: List<Int>,
        tolerance: Int,
    ): Int = flood(
        out, BooleanArray(out.size), width, 0, 0, width, height, background, tolerance,
    )

    /**
     * Whether one colour frames the inside of essentially every cell.
     *
     * Sampled a little inside the cell, not on its boundary: a model that drew
     * frame borders puts a line exactly there, and a ring one pixel wide would
     * measure the line rather than what it encloses.
     */
    private fun framesEveryCell(
        pixels: IntArray,
        width: Int,
        height: Int,
        cells: SheetGrid,
        background: Int,
        tolerance: Int,
    ): Boolean {
        val cellWidth = width / cells.columns
        val cellHeight = height / cells.rows
        if (cellWidth < MIN_CELL || cellHeight < MIN_CELL) return false

        val insetX = max(1, (cellWidth * CELL_INSET).toInt())
        val insetY = max(1, (cellHeight * CELL_INSET).toInt())
        var sampled = 0
        var matched = 0

        for (row in 0 until cells.rows) {
            for (column in 0 until cells.columns) {
                val left = column * cellWidth + insetX
                val right = (column + 1) * cellWidth - insetX
                val top = row * cellHeight + insetY
                val bottom = (row + 1) * cellHeight - insetY
                if (right <= left || bottom <= top) continue

                fun sample(index: Int) {
                    sampled++
                    if (near(pixels[index], background, tolerance)) matched++
                }
                for (x in left until right) {
                    sample(top * width + x)
                    sample((bottom - 1) * width + x)
                }
                for (y in top until bottom) {
                    sample(y * width + left)
                    sample(y * width + right - 1)
                }
            }
        }

        return sampled > 0 && matched >= sampled * CANVAS_COVERAGE
    }

    /** Green clear of both other channels: a backdrop nobody would wear. */
    private fun isChroma(color: Int): Boolean {
        val g = greenOf(color)
        return g >= CHROMA_MIN_GREEN && g - max(redOf(color), blueOf(color)) >= CHROMA_MARGIN
    }

    private fun clearChroma(out: IntArray): Int {
        var cleared = 0
        for (i in out.indices) {
            if (alphaOf(out[i]) > 0 && isChroma(out[i])) {
                out[i] = out[i] and RGB_MASK
                cleared++
            }
        }
        return cleared
    }

    private fun clearEverywhere(out: IntArray, background: List<Int>, tolerance: Int): Int {
        if (background.isEmpty()) return 0
        var cleared = 0
        for (i in out.indices) {
            if (alphaOf(out[i]) > 0 && background.any { near(out[i], it, tolerance) }) {
                out[i] = out[i] and RGB_MASK
                cleared++
            }
        }
        return cleared
    }

    /** Clears background connected to the border of one rectangle, staying inside it. */
    private fun flood(
        out: IntArray,
        seen: BooleanArray,
        stride: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        background: List<Int>,
        tolerance: Int,
    ): Int {
        if (right <= left || bottom <= top) return 0
        val queue = ArrayDeque<Int>()

        fun seed(index: Int) {
            if (!seen[index] && background.any { near(out[index], it, tolerance) }) {
                seen[index] = true
                queue += index
            }
        }

        for (x in left until right) {
            seed(top * stride + x)
            seed((bottom - 1) * stride + x)
        }
        for (y in top until bottom) {
            seed(y * stride + left)
            seed(y * stride + right - 1)
        }

        var cleared = 0
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            // Already transparent pixels are walked through but not counted
            // twice, so two passes over one cell do not inflate the tally.
            if (alphaOf(out[index]) > 0) {
                out[index] = out[index] and RGB_MASK
                cleared++
            }

            val x = index % stride
            val y = index / stride
            if (x > left) seed(index - 1)
            if (x < right - 1) seed(index + 1)
            if (y > top) seed(index - stride)
            if (y < bottom - 1) seed(index + stride)
        }
        return cleared
    }

    private fun quantise(color: Int): Int {
        val r = (redOf(color) / QUANTISE) * QUANTISE
        val g = (greenOf(color) / QUANTISE) * QUANTISE
        val b = (blueOf(color) / QUANTISE) * QUANTISE
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun near(a: Int, b: Int, tolerance: Int): Boolean =
        abs(redOf(a) - redOf(b)) <= tolerance &&
            abs(greenOf(a) - greenOf(b)) <= tolerance &&
            abs(blueOf(a) - blueOf(b)) <= tolerance

    private fun alphaOf(color: Int) = (color ushr 24) and 0xFF
    private fun redOf(color: Int) = (color ushr 16) and 0xFF
    private fun greenOf(color: Int) = (color ushr 8) and 0xFF
    private fun blueOf(color: Int) = color and 0xFF

    private fun luminanceOf(color: Int) =
        (redOf(color) * 299 + greenOf(color) * 587 + blueOf(color) * 114) / 1000

    private fun saturationOf(color: Int): Int {
        val r = redOf(color)
        val g = greenOf(color)
        val b = blueOf(color)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    /** Clears alpha while keeping the colour, so edges do not fringe. */
    private const val RGB_MASK = 0x00FFFFFF

    private const val DEFAULT_TOLERANCE = 18
    private const val NEARLY_CLEAR = 16
    /** Above this share of clear pixels the sheet is taken at its word. */
    private const val ALREADY_CLEAR_FRACTION = 0.12f
    /** Border colours are bucketed this coarsely before being counted. */
    private const val QUANTISE = 16
    private const val MAX_BACKGROUND_COLORS = 2
    /** A background has to account for most of the border to count as one. */
    private const val BORDER_COVERAGE = 0.80f
    /**
     * How far the green channel must clear the other two.
     *
     * 60 of 255. Measured against the two things it has to separate: the
     * backdrop arrives between 200 and 248 clear of the others even after
     * compression, and the most saturated thing the characters wear -- gold
     * trim, bronze plate, a red kilt -- is not green-dominant at all. The gap
     * between those is enormous, so this sits low enough to take the
     * half-backdrop pixels along a silhouette's edge and nowhere near
     * anything a figure is made of.
     */
    private const val CHROMA_MARGIN = 60

    /** Dark enough to be a shadow rather than a backdrop. */
    private const val CHROMA_MIN_GREEN = 90

    private const val CHECKER_MAX_SATURATION = 24
    private const val CHECKER_MIN_GAP = 8
    private const val CHECKER_MAX_GAP = 90
    /** How far inside a cell the ring that is sampled sits. */
    private const val CELL_INSET = 0.06f
    /** A cell smaller than this has no inside worth sampling. */
    private const val MIN_CELL = 8
    /** The share of every cell's inner ring a colour must hold to be the canvas. */
    private const val CANVAS_COVERAGE = 0.8f
    /**
     * Below this share of the sheet still opaque, keying ate the sprite.
     *
     * Deliberately generous: even a sheet of thin line-art figures covers far
     * more than this, and the case being caught is a sheet cleared to nothing.
     */
    private const val MIN_SURVIVING = 0.005f
}
