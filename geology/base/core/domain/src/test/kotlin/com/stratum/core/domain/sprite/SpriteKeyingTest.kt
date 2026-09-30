package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asking a model for a transparent background is not enough. They routinely
 * answer with the grey checkerboard that *represents* transparency in an image
 * editor — a picture of transparency rather than transparency — or with a flat
 * colour. Either way the sprite arrives as an opaque square.
 */
class SpriteKeyingTest {

    private val width = 32
    private val height = 32

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private val opaqueRed = argb(255, 220, 40, 40)
    private val lightCheck = argb(255, 204, 204, 204)
    private val darkCheck = argb(255, 153, 153, 153)

    /** A sheet with a subject in the middle and [background] everywhere else. */
    private fun sheet(background: (Int, Int) -> Int, subject: Int = opaqueRed): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in 12..19 && y in 12..19) subject else background(x, y)
        }

    private fun alphaAt(pixels: IntArray, x: Int, y: Int) = (pixels[y * width + x] ushr 24) and 0xFF

    @Test
    fun `a drawn checkerboard is cleared, including between the legs`() {
        // Two gaps inside the subject, which a flood fill from the edge would
        // never reach — exactly where a character's legs leave a hole.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val inSubject = x in 12..19 && y in 12..19
            val inGap = y in 16..19 && (x == 14 || x == 17)
            if (inSubject && !inGap) opaqueRed
            else if (((x / 4) + (y / 4)) % 2 == 0) lightCheck else darkCheck
        }

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.CHECKERBOARD, result.strategy)
        assertEquals(0, alphaAt(result.pixels, 0, 0), "the corner kept the checkerboard")
        assertEquals(0, alphaAt(result.pixels, 14, 17), "an enclosed gap kept the checkerboard")
        assertEquals(255, alphaAt(result.pixels, 13, 13), "the subject was erased")
    }

    @Test
    fun `chroma is cleared by range, including where a flood cannot reach`() {
        // The colours are the ones a real generated pose actually arrives
        // with: the backdrop asked for as flat #00FF00 comes back as a spread
        // of greens, and the figure is bronze, gold and dark red.
        val backdrop = intArrayOf(
            argb(255, 1, 251, 1),
            argb(255, 0, 248, 0),
            argb(255, 16, 232, 16),
            argb(255, 24, 224, 24),
        )
        val bronze = argb(255, 176, 134, 86)
        val gold = argb(255, 214, 176, 62)
        val kilt = argb(255, 122, 26, 38)

        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val inSubject = x in 12..19 && y in 12..19
            // The pocket between an arm and the ribs: enclosed by the figure,
            // so nothing entering from the outside ever arrives.
            val inPocket = x in 14..15 && y in 14..17
            when {
                inSubject && inPocket -> backdrop[(x + y) % backdrop.size]
                inSubject && y < 15 -> bronze
                inSubject && y < 17 -> gold
                inSubject -> kilt
                else -> backdrop[(x + y) % backdrop.size]
            }
        }

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.CHROMA, result.strategy)
        assertEquals(0, alphaAt(result.pixels, 0, 0), "the backdrop survived at the edge")
        assertEquals(
            0,
            alphaAt(result.pixels, 14, 15),
            "an enclosed pocket kept the chroma, which reads as a hole in the character",
        )
        // None of what a character is made of is green-dominant, so the range
        // that takes the backdrop cannot reach the figure.
        assertEquals(255, alphaAt(result.pixels, 13, 13), "bronze was keyed out")
        assertEquals(255, alphaAt(result.pixels, 18, 16), "gold trim was keyed out")
        assertEquals(255, alphaAt(result.pixels, 18, 18), "the red kilt was keyed out")
    }

    @Test
    fun `a half-chroma edge pixel goes with the backdrop`() {
        // Antialiasing and lossy compression leave a rim of pixels that are
        // part backdrop and part costume. Left opaque they fringe the whole
        // silhouette green.
        val pixels = sheet(background = { _, _ -> argb(255, 0, 250, 0) })
        val rim = argb(255, 88, 192, 43) // halfway between the backdrop and bronze
        pixels[12 * width + 12] = rim

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.CHROMA, result.strategy)
        assertEquals(0, alphaAt(result.pixels, 12, 12), "a half-backdrop rim pixel stayed opaque")
    }

    @Test
    fun `a backdrop that is merely greenish is not treated as chroma`() {
        // The olive a model reaches for when it ignores the instruction is a
        // neighbour of the character's own bronze, so it has to go through the
        // careful path rather than a colour range.
        val olive = argb(255, 138, 172, 96)
        val pixels = sheet(background = { _, _ -> olive })

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.SOLID, result.strategy)
        assertEquals(0, alphaAt(result.pixels, 0, 0))
        assertEquals(255, alphaAt(result.pixels, 15, 15), "the subject was erased")
    }

    @Test
    fun `a solid backdrop is cleared from the edges in`() {
        val teal = argb(255, 20, 140, 140)
        val pixels = sheet(background = { _, _ -> teal })

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.SOLID, result.strategy)
        assertEquals(0, alphaAt(result.pixels, 0, 0))
        assertEquals(0, alphaAt(result.pixels, 31, 31))
        assertEquals(255, alphaAt(result.pixels, 16, 16), "the subject was erased")
    }

    @Test
    fun `a solid backdrop colour inside the subject survives`() {
        // The same teal appears on the character. Only what touches the edge is
        // background; a flood fill is the whole reason this is not a blanket
        // colour replace.
        val teal = argb(255, 20, 140, 140)
        val pixels = sheet(background = { _, _ -> teal }).also {
            it[16 * width + 16] = teal
        }

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.SOLID, result.strategy)
        assertEquals(255, alphaAt(result.pixels, 16, 16), "an enclosed teal pixel was keyed out")
    }

    @Test
    fun `a sheet that already has alpha is left completely alone`() {
        val pixels = sheet(background = { _, _ -> argb(0, 0, 0, 0) })

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.ALREADY_TRANSPARENT, result.strategy)
        assertEquals(0, result.clearedPixels)
        assertTrue(result.pixels.contentEquals(pixels), "a correct sheet was modified")
    }

    @Test
    fun `a busy photographic border is not treated as a background`() {
        // No colour dominates the border, so there is nothing to key out and
        // guessing would eat the artwork.
        var seed = 1
        val pixels = IntArray(width * height) {
            seed = seed * 1103515245 + 12345
            argb(255, (seed ushr 16) and 0xFF, (seed ushr 8) and 0xFF, seed and 0xFF)
        }

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(KeyStrategy.NONE, result.strategy)
        assertEquals(0, result.clearedPixels)
    }

    @Test
    fun `keying clears alpha without changing the colour, so edges do not fringe`() {
        val teal = argb(255, 20, 140, 140)
        val result = SpriteKeying.key(sheet(background = { _, _ -> teal }), width, height)

        val corner = result.pixels[0]
        assertEquals(0, (corner ushr 24) and 0xFF)
        assertEquals(teal and 0x00FFFFFF, corner and 0x00FFFFFF, "the colour was discarded too")
    }

    @Test
    fun `a subject touching the edge is not eaten`() {
        val teal = argb(255, 20, 140, 140)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            // A red bar running off the left edge.
            if (y in 14..17 && x <= 10) opaqueRed else teal
        }

        val result = SpriteKeying.key(pixels, width, height)

        assertEquals(255, alphaAt(result.pixels, 0, 15), "a subject bleeding off the edge was keyed out")
        assertEquals(0, alphaAt(result.pixels, 0, 0))
    }

    @Test
    fun `a degenerate image is handled rather than crashing`() {
        assertEquals(KeyStrategy.NONE, SpriteKeying.key(IntArray(0), 0, 0).strategy)
        assertEquals(KeyStrategy.NONE, SpriteKeying.key(IntArray(4), 8, 8).strategy)
    }

    @Test
    fun `a colour that frames every cell is the canvas, and goes from inside the figure too`() {
        // The pocket between a pair of legs is background, and a flood from the
        // outside can never reach it. Knowing the grid settles what a flood
        // cannot: a colour that borders all four frames is the canvas, not the
        // costume, so it can go wherever it appears.
        val size = 40
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF101010.toInt()
        val pixels = IntArray(size * size) { white }
        // A ring of ink in each cell of a 2x2 grid, enclosing white.
        for (cellY in 0 until 2) {
            for (cellX in 0 until 2) {
                val x0 = cellX * 20 + 5
                val y0 = cellY * 20 + 5
                for (x in x0 until x0 + 10) {
                    pixels[y0 * size + x] = ink
                    pixels[(y0 + 9) * size + x] = ink
                }
                for (y in y0 until y0 + 10) {
                    pixels[y * size + x0] = ink
                    pixels[y * size + x0 + 9] = ink
                }
            }
        }

        val blind = SpriteKeying.key(pixels, size, size)
        val knowing = SpriteKeying.key(pixels, size, size, cells = SheetGrid(2, 2))

        assertEquals(KeyStrategy.SOLID, knowing.strategy)
        assertTrue(
            knowing.clearedPixels > blind.clearedPixels,
            "knowing the grid cleared ${knowing.clearedPixels}, no more than the " +
                "${blind.clearedPixels} a flood from the outside reached",
        )
        // The enclosed pockets: 8x8 of white inside each of the four rings.
        assertEquals(blind.clearedPixels + 4 * 8 * 8, knowing.clearedPixels)
    }

    @Test
    fun `clearing the canvas leaves the figure alone, pockets and all`() {
        // The canvas rule clears a colour everywhere, so the thing it must not
        // do is take the character with it. A pale figure on a dark backdrop is
        // the case that would show it.
        val size = 40
        val navy = 0xFF101828.toInt()
        val bone = 0xFFF2E8D5.toInt()
        val pixels = IntArray(size * size) { navy }
        for (cellY in 0 until 2) {
            for (cellX in 0 until 2) {
                val x0 = cellX * 20 + 6
                val y0 = cellY * 20 + 6
                for (y in y0 until y0 + 8) {
                    for (x in x0 until x0 + 8) pixels[y * size + x] = bone
                }
            }
        }

        val keyed = SpriteKeying.key(pixels, size, size, cells = SheetGrid(2, 2))

        assertEquals(KeyStrategy.SOLID, keyed.strategy)
        val survivors = keyed.pixels.count { (it ushr 24) and 0xFF > 0 }
        assertEquals(4 * 8 * 8, survivors, "the canvas clear ate into the figures")
    }

    @Test
    fun `a grid finer than the sheet does not clear the character itself`() {
        // Flooding or clearing inside boundaries that are not there must never
        // eat the subject.
        val size = 40
        val white = 0xFFFFFFFF.toInt()
        val ink = 0xFF101010.toInt()
        val pixels = IntArray(size * size) { white }
        for (y in 12 until 28) {
            for (x in 12 until 28) pixels[y * size + x] = ink
        }

        val keyed = SpriteKeying.key(pixels, size, size, cells = SheetGrid(4, 4))

        val survivors = keyed.pixels.count { (it ushr 24) and 0xFF > 0 }
        assertEquals(16 * 16, survivors, "the flood ate into the character")
    }

    @Test
    fun `a character colour taken from the border does not get cleared everywhere`() {
        // The border of a dense sheet is not one colour: figures touch the edge
        // of their cells, so the second colour read off it is often the
        // character's own. Judging the pair together would let that colour in
        // on the backdrop's evidence and erase the character from every frame.
        val size = 60
        val white = 0xFFFFFFFF.toInt()
        val rust = 0xFFB4502A.toInt()
        val pixels = IntArray(size * size) { white }

        // A 3x3 grid. Each figure fills its cell edge to edge vertically, so
        // rust appears all along the top and bottom of the image.
        for (cellY in 0 until 3) {
            for (cellX in 0 until 3) {
                for (y in cellY * 20 until (cellY + 1) * 20) {
                    for (x in cellX * 20 + 6 until cellX * 20 + 14) {
                        pixels[y * size + x] = rust
                    }
                }
            }
        }

        val keyed = SpriteKeying.key(pixels, size, size, cells = SheetGrid(3, 3))

        val survivors = keyed.pixels.count { (it ushr 24) and 0xFF > 0 }
        assertEquals(9 * 8 * 20, survivors, "the figures were cleared along with the canvas")
    }

    @Test
    fun `keying that would leave nothing at all is refused`() {
        // The backstop. Whatever the reasoning, a fully transparent sheet draws
        // as nothing in the world and tells the player nothing about why — far
        // worse than the background it was trying to remove.
        val size = 40
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(size * size) { white }

        val keyed = SpriteKeying.key(pixels, size, size, cells = SheetGrid(2, 2))

        assertEquals(KeyStrategy.NONE, keyed.strategy)
        assertEquals(0, keyed.clearedPixels)
        assertTrue(
            keyed.pixels.all { (it ushr 24) and 0xFF > 0 },
            "an empty sheet was handed on as if it had been keyed",
        )
    }
}

/**
 * A backdrop that compression has split into several shades.
 *
 * Measured, not imagined: a provider returning JPEG instead of PNG scattered
 * one flat chroma green across seven neighbouring quantise buckets, the largest
 * holding 37% of the border where the same image as PNG held 100%. The backdrop
 * fell under the coverage bar, nothing was keyed, and the sprite arrived wearing
 * its background.
 */
class NoisyBackdropKeyingTest {

    private val width = 64
    private val height = 64

    /** A flat colour, jittered per pixel the way lossy compression jitters one. */
    private fun noisyBackdrop(base: Int, jitter: Int): IntArray {
        val pixels = IntArray(width * height)
        var seed = 12345
        for (i in pixels.indices) {
            seed = seed * 1103515245 + 12345
            fun wobble(shift: Int): Int {
                val channel = (base ushr shift) and 0xFF
                val delta = ((seed ushr (shift + 4)) % (2 * jitter + 1)) - jitter
                return (channel + delta).coerceIn(0, 255)
            }
            pixels[i] = (0xFF shl 24) or (wobble(16) shl 16) or (wobble(8) shl 8) or wobble(0)
        }
        // A subject in the middle, well away from the backdrop's colour.
        for (y in 20 until 44) for (x in 20 until 44) pixels[y * width + x] = SUBJECT
        return pixels
    }

    @Test
    fun `a compressed flat backdrop is still one colour`() {
        val keyed = SpriteKeying.key(noisyBackdrop(BACKDROP, jitter = 12), width, height)

        assertEquals(KeyStrategy.SOLID, keyed.strategy)
        assertTrue(keyed.clearedPixels > 0, "the backdrop survived, so the sprite wears it")
        // The subject is untouched: merging shades of the backdrop must not
        // reach a colour nothing like it.
        assertEquals(SUBJECT, keyed.pixels[32 * width + 32])
    }

    @Test
    fun `a clean flat backdrop is unaffected by the merge`() {
        val keyed = SpriteKeying.key(noisyBackdrop(BACKDROP, jitter = 0), width, height)

        assertEquals(KeyStrategy.SOLID, keyed.strategy)
        assertTrue(keyed.clearedPixels > width * height / 2)
    }

    @Test
    fun `a compressed chroma backdrop goes by range, not by merging shades`() {
        // Chroma is the case the merge was built for, and the case it no
        // longer has to handle: every shade compression scatters a green
        // backdrop into is still green-dominant, so the range takes all of
        // them without having to work out that they are one colour.
        val keyed = SpriteKeying.key(noisyBackdrop(CHROMA_GREEN, jitter = 12), width, height)

        assertEquals(KeyStrategy.CHROMA, keyed.strategy)
        assertTrue(keyed.clearedPixels > width * height / 2)
        assertEquals(SUBJECT, keyed.pixels[32 * width + 32], "the subject went with the backdrop")
    }

    private companion object {
        /**
         * Not green: green is chroma and short-circuits the merge this class
         * exists to test. The scatter a lossy codec makes of a flat colour is
         * not a property of the colour, so any flat backdrop proves it.
         */
        const val BACKDROP = 0xFF2080E0.toInt()
        const val CHROMA_GREEN = 0xFF20E010.toInt()
        const val SUBJECT = 0xFFB07840.toInt()
    }
}
