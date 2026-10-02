package com.stratum.engine.model.mask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeMaskTest {
    @Test
    fun `every base shape fills, with every feeling`() {
        for (base in ShapeMask.Base.entries) for ((name, face) in EmojiMask.expressions) {
            val px = ShapeMask.image(ShapeMask.presets[0].copy(base = base), face, 64, turn = 0.3f, time = 1.1f)
            assertTrue(px.count { (it ushr 24) > 200 } > 64 * 64 / 10, "$base $name is a filled mask")
        }
    }

    @Test
    fun `rolls are repeatable and varied, and layers build up`() {
        assertEquals(ShapeMask.generate(5L), ShapeMask.generate(5L))
        assertTrue((1..20).map { ShapeMask.generate(it.toLong()) }.toSet().size > 15)
        val smile = EmojiMask.expressions.getValue("Smile")
        val layers = ShapeMask.Layer.entries
        val counts = (1..layers.size).map { n -> ShapeMask.image(ShapeMask.presets[3], smile, 64, layers = layers.take(n).toSet()).count { (it ushr 24) > 0 } }
        assertTrue(counts.zipWithNext().all { (a, b) -> b >= a }, "each layer adds to the mask: $counts")
    }
}
