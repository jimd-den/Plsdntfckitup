package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.scene.GlowChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaskSculptorTest {

    @Test
    fun `a tradition and a seed are always the same mask`() {
        val t = MaskCulture.tradition("agbogho_mmuo")
        assertEquals(MaskCulture.generate(t, 7L), MaskCulture.generate(t, 7L))
        assertNotEquals(MaskCulture.generate(t, 7L), MaskCulture.generate(t, 8L))
    }

    @Test
    fun `every tradition carves a clean mask with glowing eyes`() {
        for ((i, t) in MaskCulture.traditions.withIndex()) {
            val spec = MaskCulture.generate(t, 100L + i)
            val mesh = MaskSculptor.carve(spec, MaskSculptor.Detail.FAR)
            assertTrue(mesh.triangleCount > 500, "${t.name} has a surface: ${mesh.triangleCount}")
            assertTrue(mesh.positions.all { it.isFinite() } && mesh.normals.all { it.isFinite() }, "${t.name} is finite")
            assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount }, "${t.name} indexes its own vertices")
            assertTrue(mesh.channels.any { it == GlowChannel.EYES }, "${t.name} has eyes that glow")
        }
    }

    @Test
    fun `two traditions blend into a mask that carves`() {
        val a = MaskCulture.traditions.first(); val b = MaskCulture.traditions.last()
        val spec = MaskCulture.blend(a, b, 3L)
        assertEquals(spec, MaskCulture.blend(a, b, 3L))
        assertTrue(MaskSculptor.carve(spec, MaskSculptor.Detail.FAR).triangleCount > 500)
    }

    @Test
    fun `finer detail carves more of the surface`() {
        val spec = MaskCulture.generate(MaskCulture.traditions.first(), 1L)
        assertTrue(MaskSculptor.carve(spec, MaskSculptor.Detail.GAME).triangleCount > MaskSculptor.carve(spec, MaskSculptor.Detail.FAR).triangleCount)
    }
}
