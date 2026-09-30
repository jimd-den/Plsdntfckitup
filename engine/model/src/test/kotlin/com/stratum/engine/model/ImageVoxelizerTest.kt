package com.stratum.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageVoxelizerTest {

    /** A red disc on a white ground: what an image model draws when asked for one object. */
    private fun disc(size: Int = 80): ArgbImage = ArgbImage(size, size, IntArray(size * size) { i ->
        val x = i % size - size / 2f; val y = i / size - size / 2f
        if (x * x + y * y < (size * 0.35f) * (size * 0.35f)) 0xFFC03020.toInt() else 0xFFFFFFFF.toInt()
    })

    @Test
    fun `the background is keyed out and the thing is puffed from its outline`() {
        val model = ImageVoxelizer.voxelize("d", "Disc", disc(), ImageVoxelizer.Options(height = 32))
        assertTrue(model.filledCount > 0)
        assertTrue(model.palette.none { it == "#FFFFFF" }, "the white ground came along: ${model.palette}")
        // Thicker in the middle than at the rim.
        fun thickness(x: Int, z: Int) = (0 until model.sizeY).count { y -> model.cells[model.index(x, y, z)] != 0 }
        val mid = model.sizeX / 2
        assertTrue(thickness(mid, model.sizeZ / 2) > thickness(1, model.sizeZ / 2) + 2, "no inflation")
    }

    @Test
    fun `every mode makes a model`() {
        for (mode in ImageVoxelizer.Mode.entries) {
            val m = ImageVoxelizer.voxelize("d", "Disc", disc(), ImageVoxelizer.Options(mode = mode, height = 24), side = disc(), depthMap = disc())
            assertTrue(m.filledCount > 0, "$mode made nothing")
        }
    }

    @Test
    fun `editing ops are exact`() {
        var m = com.stratum.core.domain.micro.MicroModel.empty("m", "M", 8, 8, 8)
        m = MicroModelOps.box(m, Triple(1, 1, 0), Triple(6, 6, 3), "#8A8A86", hollow = true)
        val shell = m.filledCount
        m = MicroModelOps.set(m, 0, 0, 7, "#FF0000", setOf(MicroModelOps.Axis.X))
        assertEquals(shell + 2, m.filledCount)
        val turned = MicroModelOps.turn(MicroModelOps.turn(MicroModelOps.turn(MicroModelOps.turn(m))))
        assertEquals(m, turned)
        val trimmed = MicroModelOps.trim(MicroModelOps.box(com.stratum.core.domain.micro.MicroModel.empty("t", "T", 10, 10, 10), Triple(2, 3, 4), Triple(4, 5, 6), "#000000"))
        assertEquals(Triple(3, 3, 3), Triple(trimmed.sizeX, trimmed.sizeY, trimmed.sizeZ))
        val pixels = MicroModelRenderer.render(m, 64)
        assertTrue(pixels.any { it != 0 }, "the preview is blank")
    }
}
