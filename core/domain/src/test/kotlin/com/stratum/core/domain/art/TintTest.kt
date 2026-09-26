package com.stratum.core.domain.art

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TintTest {

    @Test
    fun `channels round trip`() {
        val color = Tint.argb(0x80, 0x12, 0x34, 0x56)
        assertEquals(0x80, Tint.alpha(color))
        assertEquals(0x12, Tint.red(color))
        assertEquals(0x34, Tint.green(color))
        assertEquals(0x56, Tint.blue(color))
    }

    @Test
    fun `channels clamp rather than wrapping`() {
        // Wrapping is the failure that turns an over-bright highlight black,
        // which looks like a hole in the world rather than like a bug.
        val over = Tint.scale(0xFFE0E0E0, 4f)
        assertEquals(0xFF, Tint.red(over))
        assertEquals(0xFF, Tint.blue(over))
    }

    @Test
    fun `desaturating moves a colour towards its own grey`() {
        val green = 0xFF3FA03Fu.toLong()
        val flat = Tint.saturate(green, 0f)
        assertEquals(Tint.red(flat), Tint.green(flat))
        assertEquals(Tint.green(flat), Tint.blue(flat))
        // Brightness is preserved, which is the whole reason to desaturate
        // rather than to darken: the ground stays where it was in the value
        // hierarchy and only stops shouting in colour.
        assertTrue(abs(Tint.luma(flat) - Tint.luma(green)) < 0.02f)
    }

    @Test
    fun `clamping luma pulls both ends into the band`() {
        val white = Tint.clampLuma(0xFFFFFFFF, 0.1f, 0.6f)
        val black = Tint.clampLuma(0xFF000000, 0.2f, 0.6f)
        assertTrue(Tint.luma(white) <= 0.61f, "bright colour stayed at ${Tint.luma(white)}")
        assertTrue(Tint.luma(black) >= 0.19f, "dark colour stayed at ${Tint.luma(black)}")
    }

    @Test
    fun `quantising snaps to a ramp entry and keeps alpha`() {
        val ramp = listOf(0xFF102030, 0xFFA0B0C0)
        val snapped = Tint.quantize(Tint.withAlpha(0xFF98A8B8, 0.5f), ramp)
        assertEquals(0xA0, Tint.red(snapped))
        assertEquals(0x80, Tint.alpha(snapped), "quantising is a hue decision, not an opacity one")
    }

    @Test
    fun `an empty ramp is the identity`() {
        assertEquals(0xFF123456, Tint.quantize(0xFF123456, emptyList()))
    }

    @Test
    fun `warming pushes red up and blue down without changing green much`() {
        val warm = Tint.temperature(0xFF808080, 1f)
        assertTrue(Tint.red(warm) > 0x80)
        assertTrue(Tint.blue(warm) < 0x80)
        assertTrue(abs(Tint.green(warm) - 0x80) < 16)
    }
}
