package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeedbackLogTest {

    @Test
    fun `marks age and expire`() {
        val log = FeedbackLog()
        log.add(FeedbackKind.DAMAGE_DEALT, "12", WorldPoint.ZERO, lifetime = 1f)
        assertEquals(1, log.active.size)

        log.advance(0.5f)
        assertEquals(0.5f, log.active.single().progress, absoluteTolerance = 1e-4f)

        log.advance(0.6f)
        assertTrue(log.active.isEmpty(), "an expired mark stayed on screen")
    }

    @Test
    fun `a flood of marks drops the oldest rather than refusing the newest`() {
        val log = FeedbackLog(capacity = 3)
        repeat(5) { log.add(FeedbackKind.DAMAGE_DEALT, "hit $it", WorldPoint.ZERO) }

        val texts = log.active.map { it.text }
        assertEquals(3, texts.size)
        assertTrue(texts.contains("hit 4"), "the newest hit was discarded")
        assertFalse(texts.contains("hit 0"), "the oldest hit survived the cap")
    }

    @Test
    fun `every mark gets its own id`() {
        val log = FeedbackLog()
        val ids = (1..10).map { log.add(FeedbackKind.DAMAGE_DEALT, "$it", WorldPoint.ZERO).id }
        assertEquals(ids.size, ids.distinct().size)
    }
}

class HitFlashesTest {

    @Test
    fun `a struck actor lights up and fades`() {
        val flashes = HitFlashes()
        assertEquals(0f, flashes.intensity("a"))

        flashes.strike("a")
        assertEquals(1f, flashes.intensity("a"), absoluteTolerance = 1e-4f)

        flashes.advance(0.09f)
        val mid = flashes.intensity("a")
        assertTrue(mid in 0.1f..0.9f, "flash did not fade smoothly: $mid")

        flashes.advance(0.2f)
        assertEquals(0f, flashes.intensity("a"))
    }

    @Test
    fun `actors flash independently`() {
        val flashes = HitFlashes()
        flashes.strike("a")
        flashes.advance(0.1f)
        flashes.strike("b")

        assertTrue(flashes.intensity("b") > flashes.intensity("a"))
    }

    @Test
    fun `forgetting an actor clears its flash so a dead monster does not linger`() {
        val flashes = HitFlashes()
        flashes.strike("a")
        flashes.forget("a")
        assertEquals(0f, flashes.intensity("a"))
    }
}

class SessionFeedbackTest {

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 31L, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    private fun WorldSession.flatten(radius: Int = 6) {
        val feet = player.blockPos
        val world = world as MutableWorld
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                world.setBlock(
                    BlockPos(feet.x + dx, feet.y + dy, feet.z - 1),
                    TestContent.registry.indexOf(TestContent.stone.id),
                )
                for (dz in 0..3) {
                    world.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), BlockRegistry.AIR_INDEX)
                }
            }
        }
    }

    @Test
    fun `hitting an enemy produces a damage number at the enemy`() {
        val session = session()
        session.flatten()
        val enemy = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))

        session.attack()
        val mark = session.feedback.firstOrNull {
            it.kind == FeedbackKind.DAMAGE_DEALT || it.kind == FeedbackKind.CRITICAL
        }
        assertNotNull(mark, "a hit produced no feedback")
        assertEquals(enemy.position.x, mark.origin.x, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a struck enemy flashes`() {
        val session = session()
        session.flatten()
        val enemy = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        // Tough enough to survive the blow: a dead monster's flash is dropped
        // with the corpse, which is correct but untestable.
        session.enemies = session.enemies.map {
            it.copy(health = 5000, stats = it.stats.copy(maxHealth = 5000))
        }

        assertEquals(0f, session.flashFor(enemy.instanceId))
        session.attack()
        assertTrue(session.flashFor(enemy.instanceId) > 0f, "the enemy did not flash")
    }

    @Test
    fun `a killed enemy stops flashing so nothing lingers after the corpse`() {
        val session = session()
        session.flatten()
        val enemy = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))

        session.attack()
        assertTrue(session.enemies.none { it.instanceId == enemy.instanceId }, "the rat survived")
        assertEquals(0f, session.flashFor(enemy.instanceId))
    }

    @Test
    fun `taking damage marks the player and flashes them`() {
        val session = session()
        session.flatten()
        session.spawn(TestContent.rat, session.player.position.translated(0.5f, 0f, 0f))

        session.tick(0.05f)
        assertTrue(session.feedback.any { it.kind == FeedbackKind.DAMAGE_TAKEN })
        assertTrue(session.snapshot().playerFlash > 0f)
    }

    @Test
    fun `a dodge is announced and no damage mark is produced`() {
        val session = session()
        session.flatten()
        session.spawn(TestContent.rat, session.player.position.translated(0.5f, 0f, 0f))

        session.dodge()
        session.tick(0.05f)

        assertTrue(session.feedback.any { it.kind == FeedbackKind.DODGED })
        assertFalse(session.feedback.any { it.kind == FeedbackKind.DAMAGE_TAKEN })
    }

    @Test
    fun `levelling is announced`() {
        val session = session()
        session.flatten()
        // One rat is not a level. Put a fresh one in arm's reach every round:
        // the director keeps its own population topped up at a distance, so
        // waiting for the list to empty would mean swinging at nothing.
        var guard = 0
        while (session.player.level == 1 && guard < 200) {
            session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
            session.attack()
            session.tick(0.6f)
            guard++
        }

        assertTrue(session.player.level > 1, "never levelled after $guard rounds")
        assertTrue(
            session.feedback.any { it.kind == FeedbackKind.LEVEL_UP },
            "levelling produced no announcement",
        )
    }

    @Test
    fun `feedback fades away on its own`() {
        val session = session()
        session.flatten()
        session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        session.attack()
        assertTrue(session.feedback.isNotEmpty())

        repeat(60) { session.tick(0.1f) }
        assertTrue(
            session.feedback.none { it.kind == FeedbackKind.DAMAGE_DEALT },
            "damage numbers never expired",
        )
    }

    @Test
    fun `the snapshot carries feedback for the renderer`() {
        val session = session()
        session.flatten()
        session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        session.attack()

        val snapshot = session.snapshot()
        assertTrue(snapshot.feedback.isNotEmpty())
    }
}
