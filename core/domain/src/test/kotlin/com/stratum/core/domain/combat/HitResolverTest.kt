package com.stratum.core.domain.combat

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.status.StackingRule
import com.stratum.core.domain.status.StatusApplication
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusBook
import com.stratum.core.domain.status.StatusDefinition
import com.stratum.core.domain.status.StatusSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitResolverTest {

    private val fire = DamageTypeDefinition("t:fire", "Fire", mitigatedByArmour = false, ailmentStatusId = "t:burn", ailmentChance = 0.5f)
    private val physical = DamageTypeDefinition("t:phys", "Physical")
    private val types = mapOf(fire.id to fire, physical.id to physical)::get
    private val attacker = HitAttacker(CombatStats(attackPower = 100, critChance = 0f, critMultiplier = 2f))
    private val target = HitDefender(CombatStats(maxHealth = 1000))

    private fun hit(
        damage: Map<String, Float>,
        from: HitAttacker = attacker,
        to: HitDefender = target,
        rolls: HitRolls = HitRolls(),
        rules: CombatRules = CombatRules(),
        book: StatusBook = StatusBook.EMPTY,
    ) = HitResolver.resolve(from, to, damage, rolls, rules, types, book)

    @Test
    fun `evasion is weighed against accuracy and decided by the roll`() {
        val nimble = HitDefender(CombatStats(evasion = 100))
        // 100 evasion against 100 accuracy is an even chance.
        assertEquals(0.5f, HitResolver.evadeChance(attacker, nimble, CombatRules()), 1e-4f)
        assertTrue(hit(mapOf(physical.id to 50f), to = nimble, rolls = HitRolls(evade = 0.49f)).wasEvaded)
        assertFalse(hit(mapOf(physical.id to 50f), to = nimble, rolls = HitRolls(evade = 0.51f)).wasEvaded)
    }

    @Test
    fun `spells cannot be evaded`() {
        val nimble = HitDefender(CombatStats(evasion = 10_000))
        val spell = attacker.copy(evadable = false)
        assertEquals(50, hit(mapOf(physical.id to 50f), from = spell, to = nimble, rolls = HitRolls(evade = 0f)).amount)
    }

    @Test
    fun `evasion and block are capped by the world, and a sandbox can lift the caps`() {
        val ghost = HitDefender(CombatStats(evasion = 1_000_000, blockChance = 5f))
        assertEquals(0.75f, HitResolver.evadeChance(attacker, ghost, CombatRules()), 1e-4f)
        assertEquals(0.75f, HitResolver.blockChance(ghost, CombatRules()), 1e-4f)
        assertEquals(1f, HitResolver.blockChance(ghost, CombatRules.UNBOUND), 1e-4f)
    }

    @Test
    fun `a blocked hit deals nothing and inflicts nothing`() {
        val shield = HitDefender(CombatStats(blockChance = 0.5f))
        val result = hit(mapOf(fire.id to 50f), to = shield, rolls = HitRolls(block = 0.2f, ailments = listOf(0f)))
        assertEquals(0, result.amount)
        assertTrue(result.wasBlocked)
        assertTrue(result.inflicted.isEmpty())
    }

    @Test
    fun `conversion moves damage to the new type, where the new type's resistance applies`() {
        val converter = attacker.copy(conversions = listOf(DamageConversion(physical.id, fire.id, 0.5f)))
        val fireproof = HitDefender(CombatStats(resistances = mapOf(fire.id to 0.5f)))
        val result = hit(mapOf(physical.id to 100f), from = converter, to = fireproof)
        assertEquals(mapOf(physical.id to 50, fire.id to 25), result.packets)
        assertEquals(75, result.amount)
    }

    @Test
    fun `conversion beyond all of it converts all of it, in proportion`() {
        val converted = DamageConversions.apply(
            mapOf("a" to 100f),
            listOf(DamageConversion("a", "b", 1f), DamageConversion("a", "c", 1f)),
        )
        assertEquals(mapOf("b" to 50f, "c" to 50f), converted)
    }

    @Test
    fun `converted damage is not converted again, so conversion loops end`() {
        val converted = DamageConversions.apply(mapOf("a" to 100f), listOf(DamageConversion("a", "b", 1f), DamageConversion("b", "a", 1f)))
        assertEquals(mapOf("b" to 100f), converted)
    }

    @Test
    fun `gain as extra adds on top of what was there`() {
        val extra = attacker.copy(extraDamage = listOf(ExtraDamage(physical.id, fire.id, 0.3f)))
        val result = hit(mapOf(physical.id to 100f), from = extra)
        assertEquals(mapOf(physical.id to 100, fire.id to 30), result.packets)
    }

    @Test
    fun `the cannot crit keystone overrides any crit chance`() {
        val lucky = attacker.copy(stats = attacker.stats.copy(critChance = 1f))
        assertTrue(hit(mapOf(physical.id to 10f), from = lucky, rolls = HitRolls(crit = 0f)).wasCritical)
        val oathbound = lucky.copy(keystones = setOf(Keystone.CANNOT_CRIT))
        assertFalse(hit(mapOf(physical.id to 10f), from = oathbound, rolls = HitRolls(crit = 0f)).wasCritical)
    }

    @Test
    fun `typed damage modifiers only touch their own type`() {
        val pyromancer = attacker.copy(sheet = StatSheet(listOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 1f, fire.id))))
        val result = hit(mapOf(physical.id to 100f, fire.id to 100f), from = pyromancer)
        assertEquals(mapOf(physical.id to 100, fire.id to 200), result.packets)
    }

    @Test
    fun `penetration lowers resistance to zero but not below`() {
        val warded = HitDefender(CombatStats(resistances = mapOf(fire.id to 0.5f)))
        val piercing = attacker.copy(sheet = StatSheet(listOf(StatModifier(Stat.PENETRATION, ModifierKind.FLAT, 0.3f, fire.id))))
        assertEquals(80, hit(mapOf(fire.id to 100f), from = piercing, to = warded).amount)
        val overkill = attacker.copy(sheet = StatSheet(listOf(StatModifier(Stat.PENETRATION, ModifierKind.FLAT, 2f))))
        assertEquals(100, hit(mapOf(fire.id to 100f), from = overkill, to = warded).amount)
    }

    @Test
    fun `maximum resistance raises the cap only up to the world's hard cap, and unbound rules allow immunity`() {
        val immune = CombatStats(resistances = mapOf(fire.id to 1.5f))
        val raised = HitDefender(immune, StatSheet(listOf(StatModifier(Stat.MAX_RESISTANCE, ModifierKind.FLAT, 0.5f, fire.id))))
        // The default cap is 85%; +50% maximum resistance is clipped at the 95% hard cap.
        assertEquals(15, hit(mapOf(fire.id to 100f), to = HitDefender(immune)).amount)
        assertEquals(5, hit(mapOf(fire.id to 100f), to = raised).amount)
        assertEquals(0, hit(mapOf(fire.id to 100f), to = HitDefender(immune), rules = CombatRules.UNBOUND).amount)
    }

    @Test
    fun `a vulnerability status makes every hit of its type land harder`() {
        val shock = StatusDefinition("t:shock", "Shock", behaviours = listOf(StatusBehaviour.DamageTaken(0.1f)), stacking = StackingRule.INTENSITY, maxStacks = 5)
        val book = StatusBook(listOf(shock))
        val shocked = StatusSet().applying(shock, StatusApplication(shock.id, stacks = 3))
        assertEquals(130, hit(mapOf(fire.id to 100f), to = target.copy(statuses = shocked), book = book).amount)
    }

    @Test
    fun `armour only mitigates the types that say it does`() {
        val plated = HitDefender(CombatStats(armour = 100))
        val result = hit(mapOf(physical.id to 20f, fire.id to 20f), to = plated)
        assertEquals(20, result.packets[fire.id])
        assertTrue(result.packets.getValue(physical.id) < 20)
    }

    @Test
    fun `ailments roll per damage type from the type's own chance`() {
        assertEquals(listOf("t:burn"), hit(mapOf(fire.id to 40f), rolls = HitRolls(ailments = listOf(0.4f))).inflicted.map { it.statusId })
        assertTrue(hit(mapOf(fire.id to 40f), rolls = HitRolls(ailments = listOf(0.6f))).inflicted.isEmpty())
        val inflicted = hit(mapOf(fire.id to 40f), rolls = HitRolls(ailments = listOf(0f))).inflicted.single()
        assertEquals(40f, inflicted.potency, "an ailment's potency is the damage that caused it")
    }

    @Test
    fun `the crits inflict ailments keystone guarantees them`() {
        val brutal = attacker.copy(stats = attacker.stats.copy(critChance = 1f), keystones = setOf(Keystone.CRITS_INFLICT_AILMENTS))
        assertEquals(1, hit(mapOf(fire.id to 40f), from = brutal, rolls = HitRolls(crit = 0f, ailments = listOf(0.99f))).inflicted.size)
    }

    @Test
    fun `damage over time is resisted but never armoured`() {
        val defender = HitDefender(CombatStats(armour = 1_000, resistances = mapOf(fire.id to 0.5f)))
        assertEquals(5f, HitResolver.dot(10f, fire.id, defender), 1e-4f)
        assertEquals(10f, HitResolver.dot(10f, physical.id, defender), 1e-4f)
    }

    @Test
    fun `the same seed resolves the same hit`() {
        val a = hit(mapOf(fire.id to 40f), rolls = HitRolls.from(Random(9)))
        val b = hit(mapOf(fire.id to 40f), rolls = HitRolls.from(Random(9)))
        assertEquals(a, b)
    }
}
