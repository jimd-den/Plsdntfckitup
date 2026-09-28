package com.stratum.core.domain.status

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusSetTest {

    private val burn = StatusDefinition("t:burn", "Burn", behaviours = listOf(StatusBehaviour.DamageOverTime("t:fire", hitShare = 0.5f)), durationSeconds = 2f, tags = setOf("burning"))
    private val poison = StatusDefinition(
        "t:poison", "Poison", behaviours = listOf(StatusBehaviour.DamageOverTime("t:venom", perSecond = 2f)),
        durationSeconds = 3f, stacking = StackingRule.STACK, maxStacks = 3,
    )
    private val shock = StatusDefinition("t:shock", "Shock", behaviours = listOf(StatusBehaviour.DamageTaken(0.1f)), stacking = StackingRule.INTENSITY, maxStacks = 4)
    private val chill = StatusDefinition("t:chill", "Chill", behaviours = listOf(StatusBehaviour.Slow(0.3f)))
    private val deepChill = StatusDefinition("t:deep", "Deep chill", behaviours = listOf(StatusBehaviour.Slow(0.6f)))
    private val freeze = StatusDefinition("t:freeze", "Freeze", behaviours = listOf(StatusBehaviour.Stun), durationSeconds = 1f)
    private val frenzy = StatusDefinition(
        "t:frenzy", "Frenzy", behaviours = listOf(StatusBehaviour.Modifiers(listOf(StatModifier(Stat.ATTACK_SPEED, ModifierKind.INCREASED, 0.1f)))),
        stacking = StackingRule.INTENSITY, maxStacks = 3, isDebuff = false,
    )
    private val book = StatusBook(listOf(burn, poison, shock, chill, deepChill, freeze, frenzy))

    @Test
    fun `refreshing keeps one instance and the stronger potency`() {
        val set = StatusSet().applying(burn, StatusApplication(burn.id, potency = 10f)).advanced(1.5f)
            .applying(burn, StatusApplication(burn.id, potency = 4f))
        val only = set.instances.single()
        assertEquals(10f, only.potency)
        assertEquals(2f, only.remaining, 1e-4f)
    }

    @Test
    fun `stacking statuses keep their own clocks and drop the oldest when full`() {
        var set = StatusSet()
        repeat(3) { set = set.applying(poison, StatusApplication(poison.id)).advanced(0.5f) }
        set = set.applying(poison, StatusApplication(poison.id))
        assertEquals(3, set.stacksOf(poison.id))
        // The survivors are the three newest: 3.0, 2.5 and 2.0 seconds left.
        assertEquals(listOf(3f, 2.5f, 2f), set.instances.map { it.remaining })
    }

    @Test
    fun `the world's stack cap wins over the status's own`() {
        var set = StatusSet()
        repeat(5) { set = set.applying(shock, StatusApplication(shock.id), stackCap = 2) }
        assertEquals(2, set.stacksOf(shock.id))
    }

    @Test
    fun `intensity stacks add up in effect`() {
        var set = StatusSet()
        repeat(6) { set = set.applying(shock, StatusApplication(shock.id)) }
        assertEquals(4, set.stacksOf(shock.id))
        assertEquals(0.4f, set.damageTakenShare("any", book), 1e-4f)
    }

    @Test
    fun `damage over time scales with the hit that caused it and stops when it expires`() {
        val set = StatusSet().applying(burn, StatusApplication(burn.id, potency = 20f))
        assertEquals(10f, set.dotTicks(1f, book).single().amount, 1e-4f)
        // Only the second that remains is dealt, not the whole slice asked for.
        assertEquals(10f, set.advanced(1f).dotTicks(5f, book).single().amount, 1e-4f)
        assertTrue(set.advanced(2f).isEmpty)
    }

    @Test
    fun `stacked poisons each deal their own damage`() {
        var set = StatusSet()
        repeat(3) { set = set.applying(poison, StatusApplication(poison.id)) }
        assertEquals(6f, set.dotTicks(1f, book).sumOf { it.amount.toDouble() }.toFloat(), 1e-4f)
    }

    @Test
    fun `the strongest slow applies, not the sum`() {
        val set = StatusSet().applying(chill, StatusApplication(chill.id)).applying(deepChill, StatusApplication(deepChill.id))
        assertEquals(0.6f, set.slow(book), 1e-4f)
    }

    @Test
    fun `a stun is a stun until it ends`() {
        val set = StatusSet().applying(freeze, StatusApplication(freeze.id))
        assertTrue(set.isStunned(book))
        assertFalse(set.advanced(1.1f).isStunned(book))
    }

    @Test
    fun `stat buffs count once per stack`() {
        var set = StatusSet()
        repeat(2) { set = set.applying(frenzy, StatusApplication(frenzy.id)) }
        assertEquals(0.2f, set.modifiers(book).single().value, 1e-4f)
    }

    @Test
    fun `a duration scale lengthens or shortens a status`() {
        val set = StatusSet().applying(burn, StatusApplication(burn.id, durationScale = 1.5f))
        assertEquals(3f, set.instances.single().remaining, 1e-4f)
    }

    @Test
    fun `tags let a condition ask for a kind of status`() {
        val set = StatusSet().applying(burn, StatusApplication(burn.id))
        assertTrue(set.hasTag("burning", book))
        assertTrue(set.hasTag(burn.id, book))
        assertFalse(set.hasTag("poisoned", book))
    }
}
