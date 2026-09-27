package com.stratum.core.domain.tabletop

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.HeroClassDefinition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabletopTest {

    /** A Random whose nextInt(from, until) returns the given faces in turn. */
    private class Loaded(vararg faces: Int) : Random() {
        private val queue = ArrayDeque(faces.toList())
        override fun nextBits(bitCount: Int): Int = error("unused")
        override fun nextInt(from: Int, until: Int): Int = queue.removeFirst().also { require(it in from until until) }
    }

    @Test
    fun `dice notation parses the way tables write it`() {
        assertEquals(1..20, DiceExpression.parse("d20")!!.range)
        assertEquals(5..15, DiceExpression.parse("2d6+3")!!.range)
        assertEquals(1..11, DiceExpression.parse("1d8 + 1d4 - 1")!!.range)
        assertEquals(3..18, DiceExpression.parse("4d6kh3")!!.range)
        listOf("", "d", "2d", "d1", "4d6kh5", "2d6 3", "1000d6", "hello").forEach { assertNull(DiceExpression.parse(it), it) }
    }

    @Test
    fun `keep-highest keeps the highest and a lone die reports its natural face`() {
        val stats = DiceExpression.parse("4d6kh3")!!.roll(Loaded(1, 6, 4, 5))
        assertEquals(15, stats.total)
        assertEquals(listOf(1, 6, 4, 5), stats.faces)
        assertNull(stats.natural, "four dice kept three: no single face")

        val advantage = DiceExpression.parse("2d20kh1+2")!!.roll(Loaded(3, 20))
        assertEquals(22, advantage.total)
        assertEquals(20, advantage.natural)
        assertTrue(advantage.isMaxNatural)
    }

    @Test
    fun `seeded dice roll the same every time`() {
        val dice = DiceExpression.parse("3d8+2")!!
        assertEquals(List(20) { dice.roll(Random(9)).total }.distinct().size, 1)
    }

    private val warCry = SkillCheck(
        id = "t:war_cry", name = "War Cry", attribute = Attribute.STRENGTH, difficulty = 15,
        boon = Boon("Ancestral Might", durationSeconds = 60f, attackPowerFraction = 0.25f),
        bane = Boon("Shaken", durationSeconds = 20f, armour = -5),
    )
    private val brute = HeroClassDefinition(id = "t:brute", name = "Brute", strength = 16)

    @Test
    fun `a check adds the attribute modifier and proficiency, and meeting the difficulty succeeds`() {
        assertEquals(3, Tabletop.modifierFor(brute, Attribute.STRENGTH))
        assertEquals(-1, Tabletop.modifierFor(brute.copy(strength = 8), Attribute.STRENGTH))
        assertEquals(2, Tabletop.proficiency(1))
        assertEquals(3, Tabletop.proficiency(5))

        val result = Tabletop.attempt(warCry, brute, level = 1, random = Loaded(10))
        assertEquals(15, result.total)
        assertEquals(CheckOutcome.SUCCESS, result.outcome)
        assertEquals(60f, assertNotNull(result.effect).remainingSeconds)
        assertEquals("10 + 5 = 15 vs 15", result.summary)
    }

    @Test
    fun `a natural twenty is a critical success and a natural one brings the bane`() {
        val critical = Tabletop.attempt(warCry, null, level = 1, random = Loaded(20))
        assertEquals(CheckOutcome.CRITICAL_SUCCESS, critical.outcome)
        assertEquals(90f, critical.effect!!.remainingSeconds, "a critical lasts half as long again")

        val fumble = Tabletop.attempt(warCry, brute, level = 20, random = Loaded(1))
        assertEquals(CheckOutcome.CRITICAL_FAILURE, fumble.outcome, "a one fails whatever the modifier")
        assertEquals("Shaken", fumble.effect!!.boon.name)
    }

    @Test
    fun `a boon changes combat stats by shares and flat amounts, within sane bounds`() {
        val stats = CombatStats(maxHealth = 100, attackPower = 20, armour = 3, critChance = 0.95f)
        val boosted = Boon("x", attackPowerFraction = 0.25f, armour = -10, critChance = 0.2f).applyTo(stats)

        assertEquals(25, boosted.attackPower)
        assertEquals(0, boosted.armour, "armour does not go negative")
        assertEquals(1f, boosted.critChance)
    }

    @Test
    fun `boons run out`() {
        val active = ActiveBoon(Boon("x"), remainingSeconds = 1f, fromCheckId = "c")
        assertEquals(0.5f, active.aged(0.5f)!!.remainingSeconds)
        assertNull(active.aged(1f))
    }
}
