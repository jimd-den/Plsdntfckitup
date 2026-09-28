package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The green rim chroma keying leaves on a silhouette, and what is done about it. */
class ChromaDespillTest {

    private val width = 32
    private val height = 32
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private val backdrop = argb(255, 0, 250, 0)
    private val bronze = argb(255, 176, 134, 86)
    private val green = argb(255, 70, 130, 80)

    private fun sheet(subject: (Int, Int) -> Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        if (x in 10..21 && y in 10..21) subject(x, y) else backdrop
    }

    private fun at(pixels: IntArray, x: Int, y: Int) = pixels[y * width + x]
    private fun alpha(p: Int) = (p ushr 24) and 0xFF
    private fun green(p: Int) = (p ushr 8) and 0xFF
    private fun red(p: Int) = (p ushr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test
    fun `a rim pixel with a green cast loses the cast and keeps its colour`() {
        // Bronze blended a little with the backdrop: green-dominant, but not
        // by enough to be cleared outright.
        val rim = argb(255, 150, 175, 80)
        val keyed = SpriteKeying.key(sheet { x, _ -> if (x == 10) rim else bronze }, width, height)

        assertEquals(KeyStrategy.CHROMA, keyed.strategy)
        val fixed = at(keyed.pixels, 10, 15)
        assertTrue(alpha(fixed) > 0, "the rim was erased")
        assertTrue(green(fixed) <= maxOf(red(fixed), blue(fixed)), "the rim is still green")
        assertEquals(150, red(fixed))
    }

    @Test
    fun `a rim pixel that was mostly backdrop fades rather than steps`() {
        val mostly = argb(255, 120, 170, 70)
        val keyed = SpriteKeying.key(sheet { x, _ -> if (x == 10) mostly else bronze }, width, height)
        val fixed = at(keyed.pixels, 10, 15)
        assertTrue(alpha(fixed) in 1..254, "expected a partly clear edge, got alpha ${alpha(fixed)}")
    }

    @Test
    fun `green the character wears, away from the edge, is untouched`() {
        val keyed = SpriteKeying.key(sheet { x, y -> if (x in 14..17 && y in 14..17) green else bronze }, width, height)
        assertEquals(green, at(keyed.pixels, 15, 15))
        assertEquals(bronze, at(keyed.pixels, 11, 11))
    }
}
