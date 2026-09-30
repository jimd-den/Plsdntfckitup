package com.stratum.core.domain.stats

import com.stratum.core.domain.combat.CombatStats
import kotlin.test.Test
import kotlin.test.assertEquals

class StatSheetTest {

    private fun mod(stat: Stat, kind: ModifierKind, value: Float, type: String? = null) = StatModifier(stat, kind, value, type)

    @Test
    fun `increased adds up, more multiplies`() {
        val increased = StatSheet(listOf(mod(Stat.DAMAGE, ModifierKind.INCREASED, 0.2f), mod(Stat.DAMAGE, ModifierKind.INCREASED, 0.2f)))
        val more = StatSheet(listOf(mod(Stat.DAMAGE, ModifierKind.MORE, 0.2f), mod(Stat.DAMAGE, ModifierKind.MORE, 0.2f)))

        assertEquals(140f, increased.apply(Stat.DAMAGE, 100f), 1e-3f)
        assertEquals(144f, more.apply(Stat.DAMAGE, 100f), 1e-3f)
    }

    @Test
    fun `flat applies before the multipliers`() {
        val sheet = StatSheet(listOf(mod(Stat.MAX_HEALTH, ModifierKind.FLAT, 20f), mod(Stat.MAX_HEALTH, ModifierKind.INCREASED, 0.5f)))
        assertEquals(180f, sheet.apply(Stat.MAX_HEALTH, 100f), 1e-3f)
    }

    @Test
    fun `less and reduced are negative more and increased, and nothing goes below zero`() {
        val sheet = StatSheet(listOf(mod(Stat.MAX_HEALTH, ModifierKind.MORE, -0.3f), mod(Stat.ARMOUR, ModifierKind.FLAT, -50f)))
        assertEquals(70f, sheet.apply(Stat.MAX_HEALTH, 100f), 1e-3f)
        assertEquals(0f, sheet.apply(Stat.ARMOUR, 10f))
    }

    @Test
    fun `combat stats take every modifier, and an unscoped resistance covers every type`() {
        val sheet = StatSheet(
            listOf(
                mod(Stat.DAMAGE, ModifierKind.MORE, 0.5f),
                mod(Stat.CRIT_CHANCE, ModifierKind.INCREASED, 1f),
                mod(Stat.RESISTANCE, ModifierKind.FLAT, 0.1f),
                mod(Stat.RESISTANCE, ModifierKind.FLAT, 0.2f, "t:fire"),
            ),
        )
        val stats = sheet.applyTo(CombatStats(attackPower = 20, critChance = 0.05f), damageTypeIds = listOf("t:fire", "t:cold"))

        assertEquals(30, stats.attackPower)
        assertEquals(0.1f, stats.critChance, 1e-4f)
        assertEquals(0.3f, stats.resistanceTo("t:fire"), 1e-4f)
        assertEquals(0.1f, stats.resistanceTo("t:cold"), 1e-4f)
    }

    @Test
    fun `multiplier stats start at one`() {
        assertEquals(1f, StatSheet.EMPTY.multiplier(Stat.ITEM_RARITY))
        assertEquals(1.3f, StatSheet(listOf(mod(Stat.ITEM_RARITY, ModifierKind.INCREASED, 0.3f))).multiplier(Stat.ITEM_RARITY), 1e-4f)
    }

    @Test
    fun `modifiers read the way players read them`() {
        assertEquals("+12 maximum health", mod(Stat.MAX_HEALTH, ModifierKind.FLAT, 12f).describe())
        assertEquals("15% increased damage", mod(Stat.DAMAGE, ModifierKind.INCREASED, 0.15f).describe())
        assertEquals("30% less maximum health", mod(Stat.MAX_HEALTH, ModifierKind.MORE, -0.3f).describe())
        assertEquals("+5% critical strike chance", mod(Stat.CRIT_CHANCE, ModifierKind.FLAT, 0.05f).describe())
        assertEquals("+20% resistance to fire", mod(Stat.RESISTANCE, ModifierKind.FLAT, 0.2f, "t:fire").describe())
    }
}
