package com.stratum.core.domain.sandbox

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildBreakdownTest {

    private fun source(label: String, stat: Stat, kind: ModifierKind, value: Float, type: String? = null) =
        Contribution(SourceKind.GEAR, label, StatModifier(stat, kind, value, type))

    private val sources = listOf(
        source("Ring", Stat.DAMAGE, ModifierKind.FLAT, 7f),
        source("Helm", Stat.DAMAGE, ModifierKind.INCREASED, 0.25f),
        source("Amulet", Stat.DAMAGE, ModifierKind.INCREASED, 0.15f),
        source("Oath", Stat.DAMAGE, ModifierKind.MORE, 0.4f),
        source("Brand", Stat.DAMAGE, ModifierKind.MORE, -0.1f),
        source("Fire ring", Stat.DAMAGE, ModifierKind.INCREASED, 0.5f, "t:fire"),
        source("Boots", Stat.RESISTANCE, ModifierKind.FLAT, -0.3f, "t:fire"),
        source("Belt", Stat.RESISTANCE, ModifierKind.FLAT, 0.1f),
        source("Charm", Stat.CRIT_CHANCE, ModifierKind.FLAT, 2f),
    )
    private val sheet = StatSheet(sources.map { it.modifier })
    private val base = CombatStats(attackPower = 23, critChance = 0.05f)

    @Test
    fun `the explained damage is the number the sheet gives`() {
        val layer = BuildBreakdown.layer("Build", Stat.DAMAGE, listOf(BaseContribution(SourceKind.CLASS, "Class", 23f)), sources, bounds = StatBounds.WHOLE)
        assertEquals(sheet.applyTo(base).attackPower.toFloat(), layer.result)
        assertEquals(listOf("Ring"), layer.flat.map { it.label })
        assertEquals(listOf("Helm", "Amulet"), layer.increased.map { it.label }, "typed damage is not attack power")
        assertEquals(listOf("Oath", "Brand"), layer.more.map { it.label })
        assertEquals("(23 + 7) × (1 + 0.4) × 1.26 = 52.92", layer.formula)
    }

    @Test
    fun `a typed query counts its own type and the unscoped, and is not floored`() {
        val layer = BuildBreakdown.layer("Build", Stat.RESISTANCE, emptyList(), sources, damageTypeId = "t:fire", bounds = StatBounds.SIGNED)
        assertEquals(sheet.applyTo(base, listOf("t:fire")).resistances.getValue("t:fire"), layer.result)
        assertEquals(-0.2f, layer.result, 0.0001f)
    }

    @Test
    fun `bounds clamp after the formula, the way the sheet does`() {
        val layer = BuildBreakdown.layer("Build", Stat.CRIT_CHANCE, listOf(BaseContribution(SourceKind.CLASS, "Class", 0.05f)), sources, bounds = StatBounds(atMost = 1f))
        assertEquals(1f, layer.result)
        assertEquals(sheet.applyTo(base).critChance, layer.result)
    }

    @Test
    fun `a carried layer starts from the one before`() {
        val first = BuildBreakdown.layer("Build", Stat.DAMAGE, listOf(BaseContribution(SourceKind.CLASS, "Class", 10f)), emptyList(), bounds = StatBounds.WHOLE)
        val second = BuildBreakdown.carried(first, "Survival", Stat.DAMAGE, listOf(source("Hunger", Stat.DAMAGE, ModifierKind.MORE, -0.5f)))
        assertEquals(5f, second.result)
        assertEquals(SourceKind.CARRIED, second.base.single().kind)
    }
}
