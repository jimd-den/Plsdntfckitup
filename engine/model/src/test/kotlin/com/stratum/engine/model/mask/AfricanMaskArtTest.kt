package com.stratum.engine.model.mask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AfricanMaskArtTest {
    private val art = AfricanMaskArt

    @Test
    fun `a seed is always the same mask`() {
        assertEquals(art.generate(42L, "A"), art.generate(42L, "A"))
        assertNotEquals(art.generate(42L, "A"), art.generate(43L, "A"))
    }

    @Test
    fun `every silhouette builds a clean head and its pieces, at any moment`() {
        for ((i, shape) in AfricanMaskArt.Silhouette.entries.withIndex()) {
            val d = art.generate(i * 31L + 5, "S$i").copy(silhouette = shape)
            val head = art.head(d)
            assertTrue(head.positions.all { it.isFinite() } && head.normals.all { it.isFinite() }, "$shape head is finite")
            assertTrue(head.triangleCount in 1..40_000, "$shape head fits a phone: ${head.triangleCount}")
            for (t in listOf(0f, 1.3f, 7.9f)) {
                val f = art.features(d, head.face!!, t)
                assertTrue(f.positions.all { it.isFinite() }, "$shape pieces are finite at $t")
                assertTrue(f.indices.all { it in 0 until f.vertexCount }, "$shape pieces index their own vertices")
            }
        }
    }

    @Test
    fun `pieces move over time, and a nudge moves only its own part`() {
        val d = art.generate(7L, "Mover").copy(crown = AfricanMaskArt.Crown.HORNS, hanging = AfricanMaskArt.Hanging.RAFFIA)
        val face = art.head(d).face!!
        val a = art.features(d, face, 0.2f); val b = art.features(d, face, 1.1f)
        assertTrue(a.positions.indices.any { a.positions[it] != b.positions[it] }, "the pieces animate")
        val moved = art.features(d.copy(nudges = mapOf(AfricanMaskArt.Part.CROWN to AfricanMaskArt.Nudge(dy = 0.3f))), face, 0.2f)
        assertEquals(a.vertexCount, moved.vertexCount)
        val changed = a.positions.indices.count { a.positions[it] != moved.positions[it] }
        assertTrue(changed in 1 until a.positions.size / 2, "only the crown's vertices moved: $changed of ${a.positions.size}")
    }
}
