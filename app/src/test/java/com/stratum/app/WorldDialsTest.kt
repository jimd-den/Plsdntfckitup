package com.stratum.app

import com.stratum.core.domain.world.RulesPresets
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldDialsTest {

    @Test
    fun `every preset's values light up a step on every dial`() {
        RulesPresets.all.forEach { preset ->
            WorldDials.all.forEach { dial -> dial.selected(preset.rules) }
        }
        assertEquals("Harsh", WorldDials.survival.selected(RulesPresets.survivor.rules).label)
        assertEquals("Few", WorldDials.towns.selected(RulesPresets.survivor.rules).label)
    }

    @Test
    fun `an in-between value selects the nearest step`() {
        assertEquals("Swarming", WorldDials.monsters.selected(WorldRules(monsterDensity = 1.4f)).label)
    }

    @Test
    fun `turning a dial writes the rule, and leaves the preset behind`() {
        val turned = WorldDials.survival.write(RulesPresets.adventure.rules, SurvivalMode.HARSH)
        assertEquals(SurvivalMode.HARSH, turned.survival)
        assertNull(presetOf(turned))
        assertEquals(RulesPresets.adventure, presetOf(WorldRules()))
    }

    @Test
    fun `the world config carries the chosen rules`() {
        val rules = RulesPresets.conqueror.rules
        assertEquals(rules, GameSetup.worldConfig(seed = 1L, rules = rules).rules)
    }
}
