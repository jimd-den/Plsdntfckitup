package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaletteMatchTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A figure of two tones with an optional tint added, on a clear background. */
    private fun frame(tint: Int = 0): IntArray = IntArray(400) { i ->
        when {
            i < 100 -> 0
            i % 2 == 0 -> argb(255, 160 + tint, 110 + tint, 70 + tint)
            else -> argb(255, 80 + tint, 60 + tint, 40 + tint)
        }
    }

    private fun red(pixel: Int) = (pixel ushr 16) and 0xFF

    @Test
    fun `stats ignore the clear background`() {
        val stats = assertNotNull(ColourStats.of(frame()))
        assertEquals(300, stats.samples)
        assertEquals(120f, stats.mean[0], 0.01f)
    }

    @Test
    fun `a frame drawn warmer than the rest is pulled back toward the set`() {
        val stats = mapOf(
            "a" to ColourStats.of(frame())!!,
            "b" to ColourStats.of(frame())!!,
            "warm" to ColourStats.of(frame(tint = 12))!!,
        )
        val corrections = PaletteMatch.correctionsFor(stats)

        assertTrue(corrections.getValue("a").isIdentity)
        val fixed = corrections.getValue("warm").applyTo(frame(tint = 12))
        assertEquals(frame()[100].let(::red), red(fixed[100]), "the tint survived")
        assertEquals(0, fixed[0], "a clear pixel was coloured in")
    }

    @Test
    fun `a large honest change is only partly corrected`() {
        val correction = PaletteMatch.correctionFor(ColourStats.of(frame(tint = 60))!!, ColourStats.of(frame())!!)
        assertTrue(correction.offset.all { it >= -PaletteMatch.MAX_OFFSET })
        val fixed = correction.applyTo(frame(tint = 60))
        assertTrue(red(fixed[100]) > red(frame()[100]), "the clamp let a real change be erased")
    }

    @Test
    fun `a set of one is left alone`() {
        val only = mapOf("a" to ColourStats.of(frame(tint = 30))!!)
        assertTrue(PaletteMatch.correctionsFor(only).getValue("a").isIdentity)
    }
}
