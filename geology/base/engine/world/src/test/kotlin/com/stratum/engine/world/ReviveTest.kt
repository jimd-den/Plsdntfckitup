package com.stratum.engine.world

import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviveTest {

    private fun session(seed: Long = 31L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    )

    private fun WorldSession.kill() {
        while (player.isAlive) hurtPlayer(50)
    }

    @Test
    fun `reviving someone who never died is refused`() {
        assertIs<ReviveResult.StillStanding>(session().revive())
    }

    @Test
    fun `rising restores health and puts the player back on their feet`() {
        val session = session()
        session.kill()
        assertTrue(!session.player.isAlive)

        val result = session.revive()

        assertIs<ReviveResult.Revived>(result)
        assertTrue(session.player.isAlive)
        assertEquals(session.player.maxHealthWith(session::insertOrNull), session.player.health)
        assertEquals(session.player.maxResource, session.player.resource)
    }

    @Test
    fun `dying costs progress toward the level, never the level and never the gear`() {
        val session = session()
        val weapon = session.player.equippedWeapon
        assertNotNull(weapon)
        session.player = session.player.copy(level = 4, experience = 100)
        session.kill()

        val result = session.revive()

        assertIs<ReviveResult.Revived>(result)
        assertEquals(25, result.experienceLost)
        assertEquals(75, session.player.experience)
        assertEquals(4, session.player.level, "a death took a whole level")
        assertEquals(
            weapon.instanceId,
            session.player.equippedWeapon?.instanceId,
            "the weapon was lost on death",
        )
    }

    @Test
    fun `a death with no progress banked costs nothing`() {
        val session = session()
        session.player = session.player.copy(experience = 0)
        session.kill()

        val result = session.revive()

        assertIs<ReviveResult.Revived>(result)
        assertEquals(0, result.experienceLost)
    }

    @Test
    fun `whatever cornered the player does not get to greet them at the spawn point`() {
        val session = session()
        session.kill()
        val killer = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        assertTrue(session.enemies.any { it.instanceId == killer.instanceId })

        session.revive()

        val survivors = session.enemies.filter {
            it.position.horizontalDistanceTo(session.player.position) <= WorldSession.REVIVE_CLEAR_RADIUS
        }
        assertTrue(survivors.isEmpty(), "a monster was still standing on the spawn point")
    }

    @Test
    fun `rising clears the numbers left floating over the old corpse`() {
        val session = session()
        val enemy = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        session.attack()
        assertTrue(session.feedback.isNotEmpty(), "the fixture produced no feedback to clear")

        session.kill()
        session.revive()

        assertTrue(session.feedback.isEmpty(), "stale damage numbers survived the revive")
        assertEquals(0f, session.flashFor(enemy.instanceId))
        assertTrue(!session.isRolling)
        assertEquals(0f, session.rollCooldownFraction)
    }

    @Test
    fun `the world keeps running after a revive`() {
        val session = session()
        session.kill()
        session.revive()

        // tick() returns early while the player is dead; if the revive had not
        // taken, this would be a no-op forever.
        session.setMoveInput(1f, 0f)
        val before = session.player.position
        repeat(10) { session.tick(0.05f) }

        assertTrue(
            session.player.position != before,
            "the world was still frozen after the player got up",
        )
    }
}
