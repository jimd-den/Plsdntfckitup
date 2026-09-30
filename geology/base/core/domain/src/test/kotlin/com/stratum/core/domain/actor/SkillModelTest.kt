package com.stratum.core.domain.actor

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.stats.tune
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillModelTest {

    private val bolt = SkillDefinition(
        id = "t:bolt", name = "Bolt", damageTypeId = "t:fire", delivery = SkillDelivery.PROJECTILE, cooldownSeconds = 2f,
        projectile = ProjectileSpec(count = 1, pierce = 0),
    )

    @Test
    fun `the old shapes map onto deliveries`() {
        assertEquals(SkillDelivery.MELEE, SkillDelivery.fromLegacyShape("strike"))
        assertEquals(SkillDelivery.NOVA, SkillDelivery.fromLegacyShape(" Nova "))
        assertEquals(SkillDelivery.BEAM, SkillDelivery.fromLegacyShape("lance"))
        assertNull(SkillDelivery.fromLegacyShape("spiral"))
    }

    @Test
    fun `tags come from the delivery and the damage types without anyone writing them`() {
        assertTrue(bolt.hasTag(SkillTags.PROJECTILE))
        assertTrue(bolt.hasTag(SkillTags.SPELL))
        assertTrue(bolt.hasTag("t:fire"))
        val swing = bolt.copy(delivery = SkillDelivery.MELEE)
        assertTrue(swing.hasTag(SkillTags.ATTACK))
        assertFalse(swing.hasTag(SkillTags.SPELL))
        // Written tags win over the delivery's guess.
        assertFalse(bolt.copy(tags = setOf(SkillTags.ATTACK)).hasTag(SkillTags.SPELL))
    }

    @Test
    fun `a skill with no effects hits once with its own type, and a buff is beneficial`() {
        assertEquals(listOf<SkillEffect>(SkillEffect.Damage("t:fire")), bolt.resolvedEffects)
        val cry = bolt.copy(delivery = SkillDelivery.SELF, effects = listOf(SkillEffect.ApplyStatus("t:rage", target = EffectTarget.ALLIES)))
        assertTrue(cry.isBeneficial)
        assertFalse(bolt.isBeneficial)
    }

    @Test
    fun `charges are spent one at a time and come back one at a time`() {
        val dash = bolt.copy(charges = 2, cooldownSeconds = 3f)
        var cooldowns = SkillCooldowns().started(dash)
        assertTrue(cooldowns.isReady(dash))
        assertEquals(1, cooldowns.chargesLeft(dash))
        cooldowns = cooldowns.started(dash)
        assertFalse(cooldowns.isReady(dash))
        cooldowns = cooldowns.advanced(3f)
        assertEquals(1, cooldowns.chargesLeft(dash))
        cooldowns = cooldowns.advanced(3f)
        assertEquals(2, cooldowns.chargesLeft(dash))
        assertTrue(cooldowns.isReady(dash.id))
    }

    @Test
    fun `projectile, pierce and chain stats add to the skill's own counts`() {
        val sheet = StatSheet(
            listOf(
                StatModifier(Stat.PROJECTILES, ModifierKind.FLAT, 2f),
                StatModifier(Stat.PIERCE, ModifierKind.FLAT, 1f),
                StatModifier(Stat.CHAIN, ModifierKind.FLAT, 3f),
                StatModifier(Stat.PROJECTILE_SPEED, ModifierKind.INCREASED, 0.5f),
            ),
        )
        val tuned = sheet.tune(bolt)
        assertEquals(3, tuned.projectile.count)
        assertEquals(1, tuned.projectile.pierce)
        assertEquals(3, tuned.projectile.chain)
        assertEquals(bolt.projectile.speed * 1.5f, tuned.projectile.speed, 1e-4f)
    }

    @Test
    fun `cooldown recovery stops at the world's floor, which a sandbox can lower`() {
        val absurd = StatSheet(listOf(StatModifier(Stat.COOLDOWN_RECOVERY, ModifierKind.MORE, 1000f)))
        assertEquals(0.15f, absurd.tune(bolt).cooldownSeconds, 1e-4f)
        assertEquals(0f, absurd.tune(bolt, CombatRules.UNBOUND).cooldownSeconds, 0.01f)
        // A skill authored faster than the floor is not slowed down by it.
        val quick = bolt.copy(cooldownSeconds = 0.05f)
        assertEquals(0.05f, StatSheet(listOf(StatModifier(Stat.AREA, ModifierKind.INCREASED, 0.1f))).tune(quick).cooldownSeconds, 1e-5f)
    }

    @Test
    fun `cast speed shortens the wind-up`() {
        val slow = bolt.copy(castTime = 1f)
        assertEquals(0.5f, StatSheet(listOf(StatModifier(Stat.CAST_SPEED, ModifierKind.INCREASED, 1f))).tune(slow).castTime, 1e-4f)
    }
}
