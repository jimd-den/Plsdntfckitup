package com.stratum.core.domain.combat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamageCalculatorTest {

    private val attacker = CombatStats(attackPower = 100, critChance = 0.25f, critMultiplier = 2f)
    private val plain = CombatStats(maxHealth = 500)

    @Test
    fun `a non critical hit deals base damage`() {
        val result = DamageCalculator.resolve(attacker, plain, "fire", critRoll = 0.99f)
        assertEquals(100, result.amount)
        assertFalse(result.wasCritical)
    }

    @Test
    fun `a critical hit applies the multiplier`() {
        val result = DamageCalculator.resolve(attacker, plain, "fire", critRoll = 0.0f)
        assertEquals(200, result.amount)
        assertTrue(result.wasCritical)
    }

    @Test
    fun `the crit roll boundary is exclusive so a 25 percent chance is not 26`() {
        assertTrue(DamageCalculator.resolve(attacker, plain, "fire", critRoll = 0.249f).wasCritical)
        assertFalse(DamageCalculator.resolve(attacker, plain, "fire", critRoll = 0.25f).wasCritical)
    }

    @Test
    fun `resistance reduces damage of its own type only`() {
        val resistant = plain.copy(resistances = mapOf("fire" to 0.5f))
        assertEquals(50, DamageCalculator.resolve(attacker, resistant, "fire", 0.99f).amount)
        assertEquals(100, DamageCalculator.resolve(attacker, resistant, "cold", 0.99f).amount)
    }

    @Test
    fun `negative resistance is a vulnerability`() {
        val vulnerable = plain.copy(resistances = mapOf("fire" to -0.5f))
        assertEquals(150, DamageCalculator.resolve(attacker, vulnerable, "fire", 0.99f).amount)
    }

    @Test
    fun `resistance is capped so a fight can always be won`() {
        val immune = plain.copy(resistances = mapOf("fire" to 5f))
        val result = DamageCalculator.resolve(attacker, immune, "fire", 0.99f)
        assertTrue(result.amount > 0, "capped resistance still produced immunity")
        assertEquals(15, result.amount)
    }

    @Test
    fun `vulnerability is capped too`() {
        val paper = plain.copy(resistances = mapOf("fire" to -99f))
        assertEquals(200, DamageCalculator.resolve(attacker, paper, "fire", 0.99f).amount)
    }

    @Test
    fun `armour is flat and applied after resistance`() {
        val armoured = plain.copy(armour = 30, resistances = mapOf("fire" to 0.5f))
        // 100 -> 50 after resistance -> 20 after armour.
        assertEquals(20, DamageCalculator.resolve(attacker, armoured, "fire", 0.99f).amount)
    }

    @Test
    fun `armour never heals the defender`() {
        val wall = plain.copy(armour = 10_000)
        val result = DamageCalculator.resolve(attacker, wall, "fire", 0.99f)
        assertEquals(0, result.amount)
        assertFalse(result.landed)
    }

    @Test
    fun `a hit fully absorbed by armour reads as blocked rather than missed`() {
        val wall = plain.copy(armour = 10_000)
        assertTrue(DamageCalculator.resolve(attacker, wall, "fire", 0.99f).wasBlocked)
    }

    @Test
    fun `a power multiplier scales with attack power rather than replacing it`() {
        val weak = CombatStats(attackPower = 10)
        val strong = CombatStats(attackPower = 100)
        val weakSkill = DamageCalculator.resolve(weak, plain, "fire", 0.99f, powerMultiplier = 3f).amount
        val strongSkill = DamageCalculator.resolve(strong, plain, "fire", 0.99f, powerMultiplier = 3f).amount
        assertEquals(30, weakSkill)
        assertEquals(300, strongSkill)
    }

    @Test
    fun `life steal returns a fraction of damage actually dealt`() {
        val vampiric = attacker.copy(lifeSteal = 0.1f)
        assertEquals(10, DamageCalculator.resolve(vampiric, plain, "fire", 0.99f).healedAttacker)
        // Blocked damage heals nothing, so armour is not a healing engine.
        val wall = plain.copy(armour = 10_000)
        assertEquals(0, DamageCalculator.resolve(vampiric, wall, "fire", 0.99f).healedAttacker)
    }

    @Test
    fun `attack speed converts to a delay between swings`() {
        assertEquals(0.5f, CombatStats(attackSpeed = 2f).secondsBetweenAttacks)
        assertEquals(Float.MAX_VALUE, CombatStats(attackSpeed = 0f).secondsBetweenAttacks)
    }

    @Test
    fun `adding stats takes the larger reach rather than summing it`() {
        val a = CombatStats(attackRange = 1, attackPower = 5)
        val b = CombatStats(attackRange = 3, attackPower = 7)
        val combined = a + b
        assertEquals(3, combined.attackRange)
        assertEquals(12, combined.attackPower)
    }

    @Test
    fun `adding stats merges resistances from both sides`() {
        val a = CombatStats(resistances = mapOf("fire" to 0.2f))
        val b = CombatStats(resistances = mapOf("fire" to 0.1f, "cold" to 0.4f))
        val combined = a + b
        assertEquals(0.3f, combined.resistanceTo("fire"), absoluteTolerance = 1e-5f)
        assertEquals(0.4f, combined.resistanceTo("cold"), absoluteTolerance = 1e-5f)
    }
}
