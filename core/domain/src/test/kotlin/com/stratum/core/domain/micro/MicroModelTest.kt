package com.stratum.core.domain.micro

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MicroModelTest {

    @Test
    fun `cells survive the codec`() {
        val rnd = Random(3)
        val cells = IntArray(40 * 30 * 20) { if (rnd.nextFloat() < 0.3f) rnd.nextInt(1, 9) else 0 }
        assertContentEquals(cells, MicroModelCodec.decode(MicroModelCodec.encode(cells), cells.size))
        val empty = IntArray(128 * 128 * 128)
        val text = MicroModelCodec.encode(empty)
        assertTrue(text.length < 16, "an empty box took ${text.length} characters")
        assertContentEquals(empty, MicroModelCodec.decode(text, empty.size))
    }

    @Test
    fun `a turned stamp lands every cell once`() {
        val model = MicroModel("m", "M", 3, 2, 1, listOf("#FF0000"), intArrayOf(1, 0, 0, 0, 0, 1))
        for (turns in 0..3) for (mirror in listOf(false, true)) {
            val stamp = MicroStamp("m", 10, 20, 5, turns, mirror)
            val (fx, fy) = stamp.footprint(model)
            val hits = (0 until fx).flatMap { x -> (0 until fy).map { y -> stamp.cellAt(model, 10 + x, 20 + y, 5) } }
            assertEquals(model.volume, hits.toSet().size, "turns=$turns mirror=$mirror")
            assertEquals(-1, stamp.cellAt(model, 9, 20, 5))
        }
    }

    @Test
    fun `brushes are made from their ids`() {
        val ball = assertNotNull(MicroBrushes.model(MicroBrushes.id("sphere", 3, "arch:lime")))
        assertEquals(7, ball.sizeX)
        assertEquals("arch:lime", ball.palette.single())
        assertTrue(ball.filledCount in 100..343)
    }

    @Test
    fun `unused colours are dropped`() {
        val m = MicroModel("m", "M", 2, 1, 1, listOf("#000000", "#FFFFFF"), intArrayOf(2, 0)).compacted()
        assertEquals(listOf("#FFFFFF"), m.palette)
        assertContentEquals(intArrayOf(1, 0), m.cells)
    }
}
