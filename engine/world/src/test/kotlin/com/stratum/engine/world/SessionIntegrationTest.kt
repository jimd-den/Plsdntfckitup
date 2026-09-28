package com.stratum.engine.world

import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.engine.world.CombatCoreFixtures.enemy
import com.stratum.engine.world.CombatCoreFixtures.place
import com.stratum.engine.world.CombatCoreFixtures.run
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seams between the session's systems: one set of ceilings, and followers in the same fight as everyone else. */
class SessionIntegrationTest {

    private val fixtures = CombatCoreFixtures
    private val hale = TraitDefinition(
        "test:hale", "Hale",
        modifiers = listOf(StatModifier(Stat.MAX_HEALTH, ModifierKind.FLAT, 80f), StatModifier(Stat.MAX_RESOURCE, ModifierKind.FLAT, 30f)),
    )

    private fun hardy() = fixtures.session(fixtures.pack(traits = listOf(hale), classTraits = listOf(hale.id)))

    @Test
    fun `a trait's extra life is in the bar, the refill and the fight alike`() {
        val session = hardy()

        assertEquals(session.player.maxHealthWithGear + 80, session.maxHealth)
        assertEquals(session.playerStats.maxHealth, session.maxHealth)
        assertEquals(session.maxHealth, session.player.health, "a new character starts full")
        assertEquals(session.player.resourceCeiling + 30, session.maxResource)
        assertEquals(session.maxResource, session.player.resource)
        assertEquals(session.maxHealth, session.snapshot().maxHealth)
        assertEquals(session.maxResource, session.snapshot().maxResource)
    }

    @Test
    fun `a revival refills to the same ceiling`() {
        val session = hardy()
        session.player = session.player.copy(health = 0)

        assertIs<ReviveResult.Revived>(session.revive())
        assertEquals(session.maxHealth, session.player.health)
    }

    @Test
    fun `changing gear does not cut away life a trait grants`() {
        val session = hardy()
        assertIs<EquipResult.Unequipped>(session.unequip(EquipmentSlot.WEAPON))

        assertEquals(session.maxHealth, session.player.health)
        assertTrue(session.player.health > session.player.maxHealthWithGear)
    }

    @Test
    fun `a flask heals past what gear alone allows, up to the full ceiling`() {
        val session = hardy()
        session.player = session.player.copy(health = session.player.maxHealthWithGear)

        assertIs<FlaskResult.Drunk>(session.useFlask(0))
        session.run(5f)

        assertTrue(session.player.health > session.player.maxHealthWithGear)
        assertTrue(session.player.health <= session.maxHealth)
    }

    @Test
    fun `a follower fights with its own skills`() {
        val session = fixtures.session()
        session.enemies = listOf(session.place(fixtures.archer, dx = -2f, id = "ally").copy(factionId = Factions.PLAYER))
        session.place(fixtures.dummy, dx = 5f, id = "foe")

        session.run(3f)

        val foe = assertNotNull(session.enemy("foe"))
        assertTrue(foe.health < fixtures.dummy.baseStats.maxHealth, "the follower's volley should reach the foe")
        assertEquals(session.player.maxHealthWithGear, session.player.health, "the player never swung and was never reached")
    }

    @Test
    fun `a monster strikes the player's follower when the player is out of reach`() {
        val session = fixtures.session()
        session.enemies = listOf(session.place(fixtures.dummy, dx = 3f, id = "ally").copy(factionId = Factions.PLAYER))
        session.place(fixtures.clubber, dx = 4f, id = "foe")

        session.run(2f)

        val ally = assertNotNull(session.enemy("ally"))
        assertTrue(ally.health < fixtures.dummy.baseStats.maxHealth)
    }

    @Test
    fun `a monster's skills reach the player's side too`() {
        val session = fixtures.session()
        session.enemies = listOf(session.place(fixtures.dummy, dx = 3f, id = "ally").copy(factionId = Factions.PLAYER))
        session.place(fixtures.archer, dx = 9f, id = "foe")

        session.run(3f)

        val ally = assertNotNull(session.enemy("ally"))
        assertTrue(ally.health < fixtures.dummy.baseStats.maxHealth, "the archer's volley should find the follower")
        assertEquals(session.player.maxHealthWithGear, session.player.health, "the nearer follower drew the fire")
    }

    @Test
    fun `a fallen follower drops nothing and pays nothing`() {
        val session = fixtures.session()
        val weak = session.place(fixtures.dummy, dx = 3f, id = "ally").copy(factionId = Factions.PLAYER, health = 1)
        session.enemies = listOf(weak)
        session.place(fixtures.clubber, dx = 4f, id = "foe")
        val experience = session.player.experience

        session.run(2f)

        assertNull(session.enemy("ally"))
        assertEquals(experience, session.player.experience)
        assertTrue(session.groundLoot.isEmpty())
    }
}
