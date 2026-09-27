package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerBudgetTest {

    private val helm = ItemBase("t:helm", "Helm", ItemSlot.HELM, defences = listOf(StatModifier(Stat.ARMOUR, ModifierKind.FLAT, 14f)))
    private val sword = ItemBase("t:sword", "Sword", ItemSlot.WEAPON, weapon = WeaponProfile("t:cut", 10, 14, 1.4f))

    private fun increased(stat: Stat, value: Float) = ModifierRange(stat, ModifierKind.INCREASED, value)
    private fun more(stat: Stat, value: Float) = ModifierRange(stat, ModifierKind.MORE, value)
    private fun flat(stat: Stat, min: Float, max: Float = min) = ModifierRange(stat, ModifierKind.FLAT, min, max)

    @Test
    fun `one ordinary affix is worth one point at its depth, and flat amounts earn more room deeper`() {
        assertEquals(1f, PowerBudget.range(flat(Stat.MAX_HEALTH, 25f), 1), 1e-4f)
        assertTrue(PowerBudget.range(flat(Stat.MAX_HEALTH, 25f), 30) < 0.5f, "25 health at level 30 is small")
        assertEquals(1f, PowerBudget.range(increased(Stat.DAMAGE, 0.3f), 1), 1e-4f)
        // "More" multiplies alone, so the same number is worth more of it.
        assertTrue(PowerBudget.range(more(Stat.DAMAGE, 0.3f), 1) > PowerBudget.range(increased(Stat.DAMAGE, 0.3f), 1))
    }

    @Test
    fun `a range counts at its best roll, and a penalty counts for less than its size`() {
        assertEquals(PowerBudget.range(flat(Stat.MAX_HEALTH, 50f), 1), PowerBudget.range(flat(Stat.MAX_HEALTH, 10f, 50f), 1))
        val penalty = PowerBudget.range(increased(Stat.DAMAGE, -0.3f), 1)
        assertTrue(penalty < 0f && penalty > -1f, "a drawback does not buy its full price back: $penalty")
        // Less cost is a benefit, so a negative cost modifier scores positive.
        assertTrue(PowerBudget.range(increased(Stat.RESOURCE_COST, -0.2f), 1) > 0f)
    }

    @Test
    fun `affixes, uniques, bases and sets read as balanced, strong or broken`() {
        val modest = AffixDefinition.single("t:a", "Stout", AffixKind.PREFIX, flat(Stat.MAX_HEALTH, 10f, 25f))
        val absurd = AffixDefinition.single("t:b", "Godly", AffixKind.PREFIX, increased(Stat.DAMAGE, 5f))
        assertEquals(PowerTier.BALANCED, PowerBudget.affix(modest).tier)
        assertEquals(PowerTier.BROKEN, PowerBudget.affix(absurd).tier)

        val crown = UniqueDefinition("t:crown", "Crown", helm.id, listOf(increased(Stat.MAX_HEALTH, 0.2f), more(Stat.SKILL_DAMAGE, 0.2f)), flags = setOf(BuildFlag.SKILLS_COST_HEALTH))
        assertEquals(PowerTier.BALANCED, PowerBudget.unique(crown).tier)
        val godhead = crown.copy(modifiers = listOf(more(Stat.DAMAGE, 3f)), flags = setOf(BuildFlag.HITS_IGNORE_RESISTANCE, BuildFlag.LIFE_STEAL_UNCAPPED))
        assertEquals(PowerTier.BROKEN, PowerBudget.unique(godhead).tier)

        assertEquals(PowerTier.BALANCED, PowerBudget.base(sword).tier)
        assertEquals(PowerTier.BROKEN, PowerBudget.base(sword.copy(weapon = WeaponProfile("t:cut", 90, 140, 2f))).tier)
        assertEquals(PowerTier.BALANCED, PowerBudget.base(helm).tier)
        assertEquals(PowerTier.BROKEN, PowerBudget.base(helm.copy(implicits = listOf(flat(Stat.MAX_HEALTH, 300f)))).tier)

        val set = ItemSetDefinition("t:set", "Pair", listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.3f)))))
        assertEquals(PowerTier.BALANCED, PowerBudget.set(set, 1).tier)
    }

    @Test
    fun `flags carry weight of their own, and a drawback flag counts against`() {
        val plain = UniqueDefinition("t:u", "U", helm.id, listOf(flat(Stat.MAX_HEALTH, 25f)))
        val bent = plain.copy(flags = setOf(BuildFlag.HITS_IGNORE_RESISTANCE))
        val hobbled = plain.copy(flags = setOf(BuildFlag.CANNOT_CRIT))
        assertTrue(PowerBudget.unique(bent).score > PowerBudget.unique(plain).score)
        assertTrue(PowerBudget.unique(hobbled).score < PowerBudget.unique(plain).score)
    }

    @Test
    fun `fitting to balanced scales the helpful numbers and leaves penalties alone`() {
        val greedy = UniqueDefinition(
            "t:u", "Greedy", helm.id,
            modifiers = listOf(more(Stat.DAMAGE, 2f), flat(Stat.MAX_HEALTH, 100f, 200f), increased(Stat.MOVE_SPEED, -0.2f)),
            minItemLevel = 10,
        )
        val fitted = PowerBudget.fit(greedy, PowerTier.BALANCED)
        assertEquals(PowerTier.BALANCED, PowerBudget.unique(fitted).tier, "${PowerBudget.unique(fitted)}")
        assertEquals(greedy.modifiers[2], fitted.modifiers[2], "the penalty stays what it was")
        assertTrue(fitted.modifiers[0].max < 2f && fitted.modifiers[0].max > 0f)
        assertEquals(fitted.modifiers[1].max, fitted.modifiers[1].max.toInt().toFloat(), "whole stats stay whole")

        // Broken is allowed through untouched; strong only when over strong.
        assertEquals(greedy, PowerBudget.fit(greedy, PowerTier.BROKEN))
        assertTrue(PowerBudget.unique(PowerBudget.fit(greedy, PowerTier.STRONG)).ratio <= PowerBudget.STRONG_LIMIT)
    }

    @Test
    fun `when flags alone break the budget, the strongest go first`() {
        val rules = UniqueDefinition(
            "t:u", "Rules", helm.id, listOf(flat(Stat.MAX_HEALTH, 20f)),
            flags = setOf(BuildFlag.HITS_IGNORE_RESISTANCE, BuildFlag.LIFE_STEAL_UNCAPPED, BuildFlag.RESOURCE_SHIELDS_HEALTH, BuildFlag.SKILLS_COST_HEALTH),
        )
        val fitted = PowerBudget.fit(rules, PowerTier.BALANCED)
        assertTrue(BuildFlag.HITS_IGNORE_RESISTANCE !in fitted.flags)
        assertTrue(BuildFlag.SKILLS_COST_HEALTH in fitted.flags, "the gentlest rule survives")
        assertTrue(PowerBudget.unique(fitted).ratio <= PowerBudget.BALANCED_LIMIT)
    }

    @Test
    fun `bases, affix tiers and set bonuses fit too`() {
        val cannon = sword.copy(weapon = WeaponProfile("t:cut", 90, 140, 2f))
        assertEquals(PowerTier.BALANCED, PowerBudget.base(PowerBudget.fit(cannon, PowerTier.BALANCED)).tier)

        val affix = AffixDefinition("t:a", "A", AffixKind.SUFFIX, listOf(AffixTier(listOf(increased(Stat.DAMAGE, 0.1f))), AffixTier(listOf(increased(Stat.DAMAGE, 4f)), 20)))
        val fitted = PowerBudget.fit(affix, PowerTier.BALANCED)
        assertEquals(affix.tiers[0], fitted.tiers[0], "a tier inside the budget is left alone")
        assertEquals(PowerTier.BALANCED, PowerBudget.affix(fitted).tier)

        val set = ItemSetDefinition("t:s", "S", listOf(SetBonus(2, listOf(StatModifier(Stat.DAMAGE, ModifierKind.MORE, 3f)), setOf(BuildFlag.HITS_IGNORE_RESISTANCE))))
        assertEquals(PowerTier.BALANCED, PowerBudget.set(PowerBudget.fit(set, PowerTier.BALANCED, 10), 10).tier)
    }
}
