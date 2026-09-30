package com.stratum.engine.model.mask

import com.stratum.core.domain.micro.MicroModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IgboMaskGeneratorTest {

    @Test
    fun `the same genome carves the same mask`() {
        val g = MaskGenome.random(42L)
        assertEquals(g, MaskGenome.random(42L))
        assertEquals(IgboMaskGenerator.generate(g), IgboMaskGenerator.generate(g))
        assertNotEquals(MaskGenome.random(42L), MaskGenome.random(43L))
    }

    @Test
    fun `a symmetric mask is its own mirror image`() {
        val genomes = MaskGenome.presets + (1..40).map { MaskGenome.random(it * 31L).copy(symmetric = true) }
        for (g in genomes) {
            val m = IgboMaskGenerator.generate(g)
            for (z in 0 until m.sizeZ) for (y in 0 until m.sizeY) for (x in 0 until m.sizeX) {
                assertEquals(m.cells[m.index(x, y, z)], m.cells[m.index(m.sizeX - 1 - x, y, z)], "${g.name} is not mirrored at $x,$y,$z")
            }
        }
    }

    @Test
    fun `every preset is a real, small-paletted, in-bounds model`() {
        assertTrue(MaskGenome.presets.size >= 8)
        assertEquals(MaskGenome.presets.size, MaskGenome.presets.map { it.name }.distinct().size, "presets need distinct names")
        for (g in MaskGenome.presets) {
            val m = IgboMaskGenerator.generate(g)
            assertTrue(m.filledCount > 500, "${g.name} is nearly empty")
            assertTrue(m.sizeX <= MicroModel.MAX_SIDE && m.sizeY <= MicroModel.MAX_SIDE && m.sizeZ <= MicroModel.MAX_SIDE)
            assertTrue(m.sizeZ in 30..74, "${g.name} is ${m.sizeZ} tall")
            assertTrue(m.palette.size <= 12, "${g.name} uses ${m.palette.size} colours")
        }
    }

    @Test
    fun `random masks never fail and always have eyes in a face`() {
        for (seed in 0L until 200L) {
            val g = MaskGenome.random(seed)
            val m = IgboMaskGenerator.generate(g)
            assertTrue(m.palette.size <= 12)
            assertTrue(m.sizeX <= MicroModel.MAX_SIDE && m.sizeZ <= MicroModel.MAX_SIDE)
            // The eye band: somewhere in the middle of the mask there is face.
            val band = (m.sizeZ * 0.2f).toInt()..(m.sizeZ * 0.8f).toInt()
            val filled = band.any { z -> (0 until m.sizeX).any { x -> (0 until m.sizeY).any { y -> m.cells[m.index(x, y, z)] != 0 } } }
            assertTrue(filled, "seed $seed has no face at eye height")
            // And the centre column carries the face across that band without holes.
            val cx = m.sizeX / 2
            val holes = band.count { z -> (0 until m.sizeY).none { y -> m.cells[m.index(cx, y, z)] != 0 } }
            assertTrue(holes <= band.count() / 3, "seed $seed has a hollow face")
        }
    }

    @Test
    fun `the back is flat so the mask hangs and stands`() {
        for (g in MaskGenome.presets) {
            val m = IgboMaskGenerator.generate(g)
            val back = m.sizeY - 1
            for (z in 0 until m.sizeZ) for (x in 0 until m.sizeX) {
                val any = (0 until m.sizeY).any { y -> m.cells[m.index(x, y, z)] != 0 }
                if (any) assertTrue(m.cells[m.index(x, back, z)] != 0, "${g.name} has a column off the back board at $x,$z")
            }
            // The bottom row is solid: it stands.
            assertTrue((0 until m.sizeX).any { x -> m.cells[m.index(x, back, 0)] != 0 })
        }
    }

    @Test
    fun `tradition steers a random roll`() {
        repeat(20) { i ->
            assertEquals(CrestForm.TIERS, MaskGenome.random(i.toLong(), MaskTradition.IJELE).crest)
            val e = MaskGenome.random(i.toLong(), MaskTradition.ELEPHANT)
            assertEquals(EarForm.ELEPHANT, e.ears)
            assertTrue(e.tusks)
        }
    }

    @Test
    fun `another like this stays close`() {
        val g = MaskGenome.presets.first()
        assertEquals(g, g.mutate(1L, 0f))
        val m = g.mutate(7L, 0.3f)
        assertEquals(g.tradition, m.tradition)
        assertEquals(m, g.mutate(7L, 0.3f))
        val changed = (1L..20L).count { g.mutate(it, 0.3f) != g }
        assertTrue(changed >= 18)
    }

    @Test
    fun `a genome survives a trip through the model's tags`() {
        for (g in MaskGenome.presets + (1..20).map { MaskGenome.random(it.toLong()) }) {
            val m = IgboMaskGenerator.generate(g)
            val back = MaskCodec.fromTags(m.tags, g.name)
            assertEquals(IgboMaskGenerator.generate(g), back?.let { IgboMaskGenerator.generate(it) }, "${g.name} did not round-trip")
        }
    }

    @Test
    fun `generating is quick enough to follow a slider`() {
        val g = MaskGenome.presets.maxBy { it.height }
        repeat(3) { IgboMaskGenerator.generate(g) }
        val t0 = System.nanoTime()
        repeat(5) { IgboMaskGenerator.generate(g) }
        val ms = (System.nanoTime() - t0) / 5_000_000
        assertTrue(ms < 400, "a mask took $ms ms")
    }
}
