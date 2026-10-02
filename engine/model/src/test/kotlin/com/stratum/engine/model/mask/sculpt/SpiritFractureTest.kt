package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.ShardRole
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpiritFractureTest {

    private val maiden = MaskCulture.generate(MaskCulture.tradition("agbogho_mmuo"), 3L)

    @Test
    fun `every tradition has a spirit, and a seed is always the same spirit`() {
        for (t in MaskCulture.traditions) {
            assertEquals(MaskCulture.spirit(t, 9L), MaskCulture.spirit(t, 9L))
            assertEquals(t.spirit.temper, MaskCulture.spirit(t, 9L).temper, t.name)
        }
    }

    @Test
    fun `every fracture breaks a mask into clean pieces round a core`() {
        for (f in Spirit.Fracture.entries) {
            val sp = MaskSculptor.shatter(maiden, MaskCulture.spiritOf(maiden).copy(fracture = f, pieces = 0.8f), MaskSculptor.Detail.FAR)
            assertTrue(sp.shards.count { it.role != ShardRole.ORBIT } >= 2, "$f breaks: ${sp.shards.size}")
            assertTrue(sp.core.channels.any { it == GlowChannel.CORE }, "$f has a burning core")
            for (sh in sp.shards) {
                val m = sh.mesh
                assertTrue(m.positions.all { it.isFinite() } && m.normals.all { it.isFinite() }, "$f ${sh.role} is finite")
                assertTrue(m.indices.all { it in 0 until m.vertexCount }, "$f ${sh.role} indexes its own vertices")
                assertTrue(sh.restX.isFinite() && sh.restY.isFinite() && sh.restZ.isFinite())
            }
            // The split faces are lit by the core.
            assertTrue(sp.shards.any { s -> s.mesh.channels.any { it == GlowChannel.CORE } }, "$f shows its cracks")
            assertTrue(sp.eyeShards.all { it in sp.shards.indices }, "$f keeps both eyes")
        }
    }

    @Test
    fun `pieces are built about their own centres`() {
        val sp = MaskSculptor.shatter(maiden, MaskCulture.spiritOf(maiden), MaskSculptor.Detail.FAR)
        for (sh in sp.shards.filter { it.role != ShardRole.ORBIT }) {
            val m = sh.mesh
            var cx = 0f; var cz = 0f
            for (i in 0 until m.vertexCount) { cx += m.positions[i * 3]; cz += m.positions[i * 3 + 2] }
            assertTrue(abs(cx / m.vertexCount) < 1e-3f && abs(cz / m.vertexCount) < 1e-3f, "${sh.role} centred")
        }
    }

    @Test
    fun `split visage cleaves the face down its middle`() {
        val sp = MaskSculptor.shatter(maiden, MaskCulture.spiritOf(maiden).copy(fracture = Spirit.Fracture.SPLIT_VISAGE, pieces = 0f), MaskSculptor.Detail.FAR)
        val left = sp.shards.single { it.role == ShardRole.HALF_LEFT }; val right = sp.shards.single { it.role == ShardRole.HALF_RIGHT }
        assertTrue(left.pivotX < 0f && right.pivotX > 0f)
        assertTrue(left.restX < 0f && right.restX > 0f, "the halves drift apart")
    }

    @Test
    fun `horns break away as relics of their own`() {
        val ram = MaskCulture.generate(MaskCulture.tradition("ikenga"), 2L)
        val sp = MaskSculptor.shatter(ram, MaskCulture.spiritOf(ram), MaskSculptor.Detail.FAR)
        assertEquals(2, sp.shards.count { it.role == ShardRole.RELIC && it.mesh.vertexCount > 100 }, "two horns")
    }

    @Test
    fun `relics circle the head`() {
        val sp = MaskSculptor.shatter(maiden, MaskCulture.spiritOf(maiden).copy(relics = setOf(Spirit.Relic.BRASS_BELL, Spirit.Relic.BRONZE_PLAQUE)), MaskSculptor.Detail.FAR)
        assertEquals(4, sp.shards.count { it.role == ShardRole.ORBIT })
    }

    @Test
    fun `no drift keeps the mask whole`() {
        val sp = MaskSculptor.shatter(maiden, MaskCulture.spiritOf(maiden).copy(drift = 0f), MaskSculptor.Detail.FAR)
        for (sh in sp.shards.filter { it.role !in setOf(ShardRole.CREST, ShardRole.CROWN, ShardRole.ORBIT, ShardRole.RELIC) }) {
            assertTrue(abs(sh.restX) < 0.01f && abs(sh.restZ) < 0.02f, "${sh.role} stays in place: ${sh.restX}, ${sh.restZ}")
        }
    }
}
