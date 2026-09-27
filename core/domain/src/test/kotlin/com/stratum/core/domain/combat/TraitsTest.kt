package com.stratum.core.domain.combat

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.status.StatusApplication
import com.stratum.core.domain.status.StatusBook
import com.stratum.core.domain.status.StatusDefinition
import com.stratum.core.domain.status.StatusSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitsTest {

    private val more = StatModifier(Stat.DAMAGE, ModifierKind.INCREASED, 0.1f)
    private val burn = StatusDefinition("t:burn", "Burn", tags = setOf("burning"))
    private val book = StatusBook(listOf(burn))

    @Test
    fun `full life and low life switch with health`() {
        val glass = ConditionalModifier(Condition.FullLife, more)
        val desperate = ConditionalModifier(Condition.LowLife(0.3f), more)
        assertEquals(more, glass.resolve(ConditionContext(lifeFraction = 1f)))
        assertNull(glass.resolve(ConditionContext(lifeFraction = 0.9f)))
        assertEquals(more, desperate.resolve(ConditionContext(lifeFraction = 0.3f)))
        assertNull(desperate.resolve(ConditionContext(lifeFraction = 0.31f)))
    }

    @Test
    fun `against burning enemies reads the target's status tags`() {
        val against = ConditionalModifier(Condition.TargetHas("burning"), more)
        val burning = StatusSet().applying(burn, StatusApplication(burn.id))
        assertEquals(more, against.resolve(ConditionContext(targetStatuses = burning, book = book)))
        assertNull(against.resolve(ConditionContext(targetStatuses = StatusSet(), book = book)))
        assertNull(against.resolve(ConditionContext(targetStatuses = null, book = book)))
    }

    @Test
    fun `per ten strength counts whole steps only`() {
        val per = ConditionalModifier(Condition.Per(Attribute.STRENGTH, 10), more)
        assertEquals(0.3f, per.resolve(ConditionContext(attributes = mapOf(Attribute.STRENGTH to 39)))!!.value, 1e-5f)
        assertNull(per.resolve(ConditionContext(attributes = mapOf(Attribute.STRENGTH to 9))))
    }

    @Test
    fun `skill tag conditions read the skill in use`() {
        val projectiles = ConditionalModifier(Condition.SkillTagged("projectile"), more)
        assertEquals(more, projectiles.resolve(ConditionContext(skillTags = setOf("projectile", "spell"))))
        assertNull(projectiles.resolve(ConditionContext(skillTags = setOf("melee"))))
    }

    @Test
    fun `traits merge, and each trigger keeps a key of its own`() {
        val a = TraitDefinition("t:a", "A", triggers = listOf(TriggerDefinition(TriggerEvent.ON_HIT), TriggerDefinition(TriggerEvent.ON_KILL)), keystones = setOf(Keystone.CANNOT_CRIT))
        val b = TraitDefinition("t:b", "B", triggers = listOf(TriggerDefinition(TriggerEvent.ON_HIT)), keystones = setOf(Keystone.INSTANT_LEECH))
        val merged = CombatTraits.of(listOf(a, b))
        assertEquals(listOf("t:a#0", "t:a#1", "t:b#0"), merged.triggers.map { it.key })
        assertTrue(merged.has(Keystone.CANNOT_CRIT) && merged.has(Keystone.INSTANT_LEECH))
    }
}
