package com.stratum.engine.model.mask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaskMakerTest {
    @Test
    fun `a mask and its code are the same mask`() {
        val looks = EmojiMask.presets + (1..40).map { EmojiMask.generate(it * 31L, "Roll $it") }
        for (look in looks.map(MaskMaker::normalised)) {
            val code = MaskMaker.encode(look)
            assertTrue(code.length < 80, "a code is short enough to send: $code")
            assertEquals(look, MaskMaker.decode(code), "round trip of ${look.name}")
        }
        assertNull(MaskMaker.decode("not a mask"))
        assertNull(MaskMaker.decode("im1:zz"))
    }

    @Test
    fun `a roll keeps what is locked and changes the rest`() {
        val look = MaskMaker.normalised(EmojiMask.presets[3])
        val locked = setOf(MaskMaker.Trait.CREST, MaskMaker.Trait.EYES, MaskMaker.Trait.FINISH)
        repeat(12) { i ->
            val r = MaskMaker.reroll(look, i * 7L + 1, locked)
            assertEquals(look.crest, r.crest); assertEquals(look.crestCount, r.crestCount); assertEquals(look.eyes, r.eyes)
            assertEquals(look.finish, r.finish); assertEquals(look.wood, r.wood)
        }
        val rolls = (1..12).map { MaskMaker.reroll(look, it.toLong(), locked) }.toSet()
        assertTrue(rolls.size > 8, "rolls differ in what is not locked")
    }

    @Test
    fun `variations stay close and differ`() {
        val look = MaskMaker.normalised(EmojiMask.presets[0])
        val vs = MaskMaker.variations(look, 5L, 6)
        assertEquals(6, vs.size)
        assertTrue(vs.all { it != look } || vs.toSet().size > 3)
        for (v in vs) {
            val same = listOf(v.shape == look.shape, v.crest == look.crest, v.eyes == look.eyes, v.mouth == look.mouth, v.hairline == look.hairline, v.marks == look.marks).count { it }
            assertTrue(same >= 3, "a variation keeps most of the mask")
        }
    }

    @Test
    fun `there are millions of masks, and the hero can wear one`() {
        assertTrue(MaskMaker.choices() > java.math.BigInteger.valueOf(1_000_000_000L))
        assertTrue(MaskMaker.spoken(MaskMaker.choices()).split(' ').size == 2)
        val card = MaskMaker.card(EmojiMask.presets[0], EmojiMask.expressions.getValue("Serene"), 48)
        assertTrue(card.triangleCount in 200..20_000, "a card the game can carry: ${card.triangleCount}")
        assertTrue(card.positions.all { it.isFinite() })
        val angry = MaskMaker.card(EmojiMask.presets[0], EmojiMask.expressions.getValue("Angry"), 48)
        assertNotEquals(card.colors.toList(), angry.colors.toList())
    }
}
