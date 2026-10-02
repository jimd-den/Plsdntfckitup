package com.stratum.engine.model.mask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiMaskTest {
    @Test
    fun `every carver's mask makes every feeling`() {
        for (look in EmojiMask.presets) for ((name, face) in EmojiMask.expressions) {
            val px = EmojiMask.image(look, face, 64, turn = 0.3f, time = 1.2f)
            assertTrue(px.count { (it ushr 24) > 0 } > 64 * 64 / 8, "${look.name} $name draws a face")
        }
    }

    @Test
    fun `a seed is always the same mask, and rolls differ`() {
        assertEquals(EmojiMask.generate(9L), EmojiMask.generate(9L))
        assertTrue((1..20).map { EmojiMask.generate(it.toLong()) }.toSet().size > 15)
    }

    @Test
    fun `a blend runs from one feeling to the other`() {
        val a = EmojiMask.expressions.getValue("Serene"); val b = EmojiMask.expressions.getValue("Laugh")
        assertEquals(a, EmojiMask.blend(a, b, 0f))
        assertEquals(b, EmojiMask.blend(a, b, 1f))
    }
}
