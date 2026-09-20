package com.stratum.engine.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoiseTest {

    @Test
    fun `the same seed and coordinate always gives the same value`() {
        val a = ValueNoise(1234L)
        val b = ValueNoise(1234L)
        repeat(200) { i ->
            val x = i * 0.37f
            val y = i * -0.91f
            assertEquals(a.at(x, y), b.at(x, y), absoluteTolerance = 0f)
            assertEquals(a.at(x, y, i * 0.13f), b.at(x, y, i * 0.13f), absoluteTolerance = 0f)
        }
    }

    @Test
    fun `different seeds diverge`() {
        val a = ValueNoise(1L)
        val b = ValueNoise(2L)
        val differences = (0 until 100).count { i ->
            a.at(i * 0.5f, i * 0.25f) != b.at(i * 0.5f, i * 0.25f)
        }
        assertTrue(differences > 90, "seeds barely differed: only $differences of 100 samples")
    }

    @Test
    fun `values stay inside the unit range`() {
        val noise = ValueNoise(99L)
        for (i in 0 until 500) {
            val x = (i - 250) * 0.31f
            val y = (i - 250) * 0.17f
            val value = noise.at(x, y)
            assertTrue(value in 0f..1f, "at($x,$y) produced $value")
            val fractal = noise.fractal(x, y)
            assertTrue(fractal in 0f..1f, "fractal($x,$y) produced $fractal")
        }
    }

    @Test
    fun `noise is continuous so terrain does not cliff between adjacent columns`() {
        val noise = ValueNoise(7L)
        var maxJump = 0f
        for (i in 0 until 200) {
            val x = i * 0.05f
            val jump = kotlin.math.abs(noise.at(x, 0f) - noise.at(x + 0.05f, 0f))
            if (jump > maxJump) maxJump = jump
        }
        assertTrue(maxJump < 0.35f, "adjacent samples jumped by $maxJump")
    }

    @Test
    fun `positional random does not depend on call order`() {
        val first = PositionalRandom.floatAt(42L, 10, -3, salt = 5)
        PositionalRandom.floatAt(42L, 999, 999, salt = 1)
        val second = PositionalRandom.floatAt(42L, 10, -3, salt = 5)
        assertEquals(first, second, absoluteTolerance = 0f)
    }

    @Test
    fun `positional random respects its bound`() {
        for (i in 0 until 300) {
            val value = PositionalRandom.intAt(3L, i, -i, salt = 2, bound = 7)
            assertTrue(value in 0..6, "produced $value")
        }
    }
}
