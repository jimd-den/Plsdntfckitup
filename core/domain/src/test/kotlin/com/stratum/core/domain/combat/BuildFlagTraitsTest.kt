package com.stratum.core.domain.combat

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BuildFlagTraitsTest {

    @Test
    fun `no flags means no traits`() {
        assertSame(CombatTraits.NONE, CombatTraits.of(emptySet<BuildFlag>()))
    }

    @Test
    fun `every flag gear can carry means something to combat`() {
        BuildFlag.entries.forEach { flag ->
            val traits = CombatTraits.of(setOf(flag))
            assertTrue(traits.keystones.isNotEmpty() || traits.modifiers.isNotEmpty(), "$flag changes nothing in a fight")
        }
    }

    @Test
    fun `gear flags become the keystones passives grant`() {
        val traits = CombatTraits.of(setOf(BuildFlag.SKILLS_COST_HEALTH, BuildFlag.CANNOT_CRIT, BuildFlag.LIFE_STEAL_UNCAPPED))
        assertEquals(setOf(Keystone.LIFE_PAYS_COSTS, Keystone.CANNOT_CRIT, Keystone.INSTANT_LEECH), traits.keystones)
    }

    @Test
    fun `ignoring resistance is full penetration of every type`() {
        val traits = CombatTraits.of(setOf(BuildFlag.HITS_IGNORE_RESISTANCE))
        assertEquals(listOf(StatModifier(Stat.PENETRATION, ModifierKind.FLAT, 1f)), traits.modifiers)
    }
}
