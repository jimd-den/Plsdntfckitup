package com.stratum.feature.play

import com.stratum.core.domain.actor.PendingCast
import com.stratum.core.domain.actor.SkillCooldowns
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.content.PackPalette
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.world.WorldPoint
import com.stratum.engine.world.Held
import com.stratum.engine.world.IsometricProjection
import com.stratum.engine.world.Telegraph
import com.stratum.engine.world.WorldSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBarTest {

    private val bolt = SkillDefinition("t:bolt", "Bolt", damageTypeId = "t:fire", resourceCost = 30, cooldownSeconds = 2f, tags = setOf("spell"))
    private val dash = SkillDefinition("t:dash", "Dash", damageTypeId = "t:phys", resourceCost = 0, cooldownSeconds = 3f, charges = 3)

    private fun state(resource: Int = 0, health: Int = 100, lifePays: Boolean = false, cooldowns: SkillCooldowns = SkillCooldowns(), telegraphs: List<Telegraph> = emptyList()) = PlayUiState(
        player = PlayerState("t:hero", WorldPoint(0f, 0f, 0f), resource = resource, health = health, cooldowns = cooldowns),
        camera = WorldPoint(0f, 0f, 0f),
        projection = IsometricProjection(),
        palette = PackPalette(),
        biomeName = "",
        lifePaysCosts = lifePays,
        telegraphs = telegraphs,
    )

    @Test
    fun `a life-paid skill lights up with an empty resource pool, and says what it costs`() {
        assertFalse(state(resource = 0).canAfford(bolt))
        val bloody = state(resource = 0, lifePays = true)
        assertTrue(bloody.canAfford(bolt))
        assertEquals("♥30", skillBadge(bloody, bolt))
        assertFalse(state(resource = 0, health = 30, lifePays = true).canAfford(bolt), "a cast never kills its caster")
    }

    @Test
    fun `a skill with charges counts them down on its badge`() {
        val used = state().copy(player = state().player.copy(cooldowns = SkillCooldowns().started(dash)))
        assertEquals("2", skillBadge(used, dash))
        assertNull(skillBadge(state(resource = 50), bolt))
    }

    @Test
    fun `the button is lit while its skill winds up`() {
        val winding = Telegraph.of(WorldSession.PLAYER_ACTOR_ID, bolt, PendingCast(bolt.id, 0.5f, 1f, WorldPoint(0f, 0f, 0f), 0f, 1f), WorldPoint(0f, 0f, 0f), hostile = false)
        assertTrue(state(telegraphs = listOf(winding)).isWindingUp(bolt))
        assertFalse(state().isWindingUp(bolt))
    }

    @Test
    fun `supports that fit come first, and the rest say what they need`() {
        val volley = SupportDefinition("t:volley", "Volley", requiresTags = setOf("projectile"), modifiers = listOf(StatModifier(Stat.PROJECTILES, ModifierKind.FLAT, 2f)))
        val echo = SupportDefinition(
            "t:echo", "Echo", requiresTags = setOf("spell"),
            trigger = TriggerDefinition(TriggerEvent.ON_CRIT, cooldownSeconds = 0.5f),
            modifiers = listOf(StatModifier(Stat.SKILL_DAMAGE, ModifierKind.MORE, -0.2f)),
        )
        val options = SkillFacts.options(bolt, listOf(Held(volley, 1), Held(echo, 2)))
        assertEquals(listOf("t:echo", "t:volley"), options.map { it.support.id })
        assertTrue(options.first().fits)
        assertEquals("projectile", options.last().needs)
        assertTrue(options.first().changes.any { it.startsWith("Casts itself on crit") }, options.first().changes.toString())
        assertTrue(options.first().changes.any { it.contains("less skill damage") })
    }

    @Test
    fun `the facts of a skill are its cost, timing and charges`() {
        val facts = SkillFacts.facts(dash, com.stratum.core.domain.actor.SkillCost.of(dash, false), "Focus")
        assertTrue("Free" in facts)
        assertTrue("3 charges" in facts)
        assertTrue("Instant" in facts)
        val paid = SkillFacts.facts(bolt, com.stratum.core.domain.actor.SkillCost.of(bolt, true), "Focus")
        assertEquals("Costs 30 life", paid.first())
    }
}
