package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MovementTest {

    private fun session(seed: Long = 5L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    private fun WorldSession.put(pos: BlockPos, blockId: String) {
        (world as MutableWorld).setBlock(pos, TestContent.registry.indexOf(blockId))
    }

    private fun WorldSession.clear(pos: BlockPos) {
        (world as MutableWorld).setBlock(pos, BlockRegistry.AIR_INDEX)
    }

    /** Flattens a patch around the player so a test measures movement, not terrain. */
    private fun WorldSession.flatten(radius: Int = 6) {
        val feet = player.blockPos
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                put(BlockPos(feet.x + dx, feet.y + dy, feet.z - 1), TestContent.stone.id)
                for (dz in 0..3) clear(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz))
            }
        }
    }

    // ---- continuous movement ---------------------------------------------

    @Test
    fun `holding a direction moves at a speed measured per second, not per call`() {
        val session = session()
        session.flatten()
        val start = session.player.position

        session.setMoveInput(1f, 0f)
        // One second of travel, delivered as twenty frames.
        repeat(20) { session.tick(0.05f) }

        val travelled = session.player.position.x - start.x
        assertEquals(PlayerMotion.WALK_SPEED, travelled, absoluteTolerance = 0.3f)
    }

    @Test
    fun `frame rate does not change how far the player gets`() {
        fun distanceOver(frames: Int, step: Float): Float {
            val session = session()
            session.flatten()
            val start = session.player.position.x
            session.setMoveInput(1f, 0f)
            repeat(frames) { session.tick(step) }
            return session.player.position.x - start
        }
        val coarse = distanceOver(10, 0.1f)
        val fine = distanceOver(50, 0.02f)
        assertEquals(coarse, fine, absoluteTolerance = 0.25f)
    }

    @Test
    fun `a diagonal is not faster than a cardinal`() {
        fun distance(dx: Float, dy: Float): Float {
            val session = session()
            session.flatten()
            val start = session.player.position
            session.setMoveInput(dx, dy)
            repeat(10) { session.tick(0.05f) }
            val ex = session.player.position.x - start.x
            val ey = session.player.position.y - start.y
            return kotlin.math.sqrt(ex * ex + ey * ey)
        }
        // Raw square input would make this 1.41x faster.
        assertEquals(distance(1f, 0f), distance(1f, 1f), absoluteTolerance = 0.3f)
    }

    @Test
    fun `a half deflected stick walks at half speed`() {
        fun distance(scale: Float): Float {
            val session = session()
            session.flatten()
            val start = session.player.position.x
            session.setMoveInput(scale, 0f)
            repeat(10) { session.tick(0.05f) }
            return session.player.position.x - start
        }
        assertEquals(distance(1f) / 2f, distance(0.5f), absoluteTolerance = 0.25f)
    }

    @Test
    fun `releasing the stick stops the player`() {
        val session = session()
        session.flatten()
        session.setMoveInput(1f, 0f)
        repeat(5) { session.tick(0.05f) }

        session.setMoveInput(0f, 0f)
        val stopped = session.player.position
        repeat(5) { session.tick(0.05f) }
        assertEquals(stopped.x, session.player.position.x, absoluteTolerance = 0.001f)
    }

    @Test
    fun `a stick inside the deadzone is treated as centred`() {
        val session = session()
        session.flatten()
        val start = session.player.position.x
        session.setMoveInput(0.05f, 0.05f)
        repeat(10) { session.tick(0.05f) }
        assertEquals(start, session.player.position.x, absoluteTolerance = 0.001f)
    }

    @Test
    fun `walking into a wall at an angle slides along it instead of sticking`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos

        // A wall to the east, running north-south.
        for (dy in -4..4) {
            for (dz in 0..2) {
                session.put(BlockPos(feet.x + 2, feet.y + dy, feet.z + dz), TestContent.stone.id)
            }
        }

        val start = session.player.position
        // Push north-east: east is blocked, north is open.
        session.setMoveInput(1f, -1f)
        repeat(20) { session.tick(0.05f) }

        val movedNorth = start.y - session.player.position.y
        assertTrue(movedNorth > 1f, "the player stuck on the wall instead of sliding (moved $movedNorth)")
        assertTrue(session.player.position.x < feet.x + 2, "the player walked through the wall")
    }

    @Test
    fun `facing follows the stick`() {
        val session = session()
        session.flatten()
        session.setMoveInput(1f, 0f)
        assertEquals(Direction.EAST, session.player.facing)
        session.setMoveInput(-1f, 0f)
        assertEquals(Direction.WEST, session.player.facing)
        session.setMoveInput(0f, 1f)
        assertEquals(Direction.SOUTH, session.player.facing)
        session.setMoveInput(0f, -1f)
        assertEquals(Direction.NORTH, session.player.facing)
    }

    // ---- dodge roll -------------------------------------------------------

    @Test
    fun `a roll carries the player further than walking does`() {
        fun distance(roll: Boolean): Float {
            val session = session()
            session.flatten(radius = 12)
            val start = session.player.position.x
            session.setMoveInput(1f, 0f)
            if (roll) session.dodge()
            repeat(6) { session.tick(0.05f) }
            return session.player.position.x - start
        }
        assertTrue(distance(roll = true) > distance(roll = false) * 1.5f)
    }

    @Test
    fun `a roll with no stick input goes the way the player faces`() {
        val session = session()
        session.flatten(radius = 12)
        session.setMoveInput(0f, -1f)
        session.setMoveInput(0f, 0f)
        assertEquals(Direction.NORTH, session.player.facing)

        val start = session.player.position
        assertIs<DodgeResult.Rolling>(session.dodge())
        repeat(6) { session.tick(0.05f) }

        assertTrue(session.player.position.y < start.y - 1f, "the roll did not go north")
    }

    @Test
    fun `rolling grants invulnerability, and it ends before the roll does`() {
        val session = session()
        session.flatten(radius = 12)
        session.dodge()
        assertTrue(session.isInvulnerable)
        assertTrue(session.isRolling)

        // Past the invulnerability window but still inside the roll.
        repeat(5) { session.tick(0.05f) }
        assertFalse(session.isInvulnerable, "invulnerability outlasted its window")
        assertTrue(session.isRolling, "the roll ended at the same time as the i-frames")
    }

    @Test
    fun `an invulnerable player takes no damage and the dodge is reported`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        session.spawn(TestContent.rat, session.player.position.translated(0.5f, 0f, 0f))
        val fullHealth = session.player.health

        session.dodge()
        val events = session.tick(0.05f)

        assertEquals(fullHealth, session.player.health, "a rolling player took damage")
        assertTrue(events.any { it is CombatEvent.PlayerDodged }, "the dodge was not reported")
        assertTrue(events.none { it is CombatEvent.PlayerHurt })
        assertEquals(feet.z, session.player.blockPos.z)
    }

    @Test
    fun `a roll cannot be spammed`() {
        val session = session()
        session.flatten(radius = 12)
        assertIs<DodgeResult.Rolling>(session.dodge())
        assertIs<DodgeResult.AlreadyRolling>(session.dodge())

        repeat(8) { session.tick(0.05f) }
        assertFalse(session.isRolling)
        assertIs<DodgeResult.OnCooldown>(session.dodge())

        repeat(30) { session.tick(0.05f) }
        assertIs<DodgeResult.Rolling>(session.dodge())
    }

    @Test
    fun `the roll cooldown is reported for the interface`() {
        val session = session()
        session.flatten(radius = 12)
        assertEquals(0f, session.rollCooldownFraction)
        session.dodge()
        assertEquals(1f, session.rollCooldownFraction, absoluteTolerance = 0.05f)
        repeat(11) { session.tick(0.05f) }
        assertTrue(session.rollCooldownFraction < 0.6f)
    }

    @Test
    fun `a dead player cannot roll`() {
        val session = session()
        session.player = session.player.damaged(session.player.health)
        assertIs<DodgeResult.Rejected>(session.dodge())
    }

    @Test
    fun `a roll cannot pass through a wall`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        for (dy in -4..4) {
            for (dz in 0..2) {
                session.put(BlockPos(feet.x + 2, feet.y + dy, feet.z + dz), TestContent.stone.id)
            }
        }

        session.setMoveInput(1f, 0f)
        session.dodge()
        repeat(10) { session.tick(0.05f) }

        assertTrue(
            session.player.position.x < feet.x + 2,
            "the roll teleported through a wall to ${session.player.position.x}",
        )
    }

    @Test
    fun `the snapshot carries roll state for the interface`() {
        val session = session()
        session.flatten(radius = 12)
        session.dodge()
        val snapshot = session.snapshot()
        assertTrue(snapshot.isRolling)
        assertTrue(snapshot.isInvulnerable)
        assertTrue(snapshot.rollCooldownFraction > 0f)
    }
}

class AnimationStateTest {

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 8L, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    private fun WorldSession.flatten(radius: Int = 6) {
        val feet = player.blockPos
        val w = world as MutableWorld
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                w.setBlock(
                    BlockPos(feet.x + dx, feet.y + dy, feet.z - 1),
                    TestContent.registry.indexOf(TestContent.stone.id),
                )
                for (dz in 0..3) w.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), BlockRegistry.AIR_INDEX)
            }
        }
    }

    @Test
    fun `standing still is idle and walking is walk`() {
        val session = session()
        session.flatten()
        session.tick(0.05f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.IDLE,
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state,
        )

        session.setMoveInput(1f, 0f)
        session.tick(0.05f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.WALK,
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state,
        )
    }

    @Test
    fun `rolling overrides walking`() {
        val session = session()
        session.flatten(radius = 12)
        session.setMoveInput(1f, 0f)
        session.dodge()
        session.tick(0.05f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.ROLL,
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state,
        )
    }

    @Test
    fun `attacking holds the attack state for a beat rather than one frame`() {
        val session = session()
        session.flatten()
        // Deliberately no enemy: one standing in melee range would hit back, and
        // the flinch correctly overrides the attack animation. The swing sets
        // the hold whether or not it connects, which is what this measures.

        session.attack()
        session.tick(0.05f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.ATTACK,
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state,
        )

        // Still swinging a moment later.
        session.tick(0.1f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.ATTACK,
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state,
        )

        // And released eventually.
        repeat(10) { session.tick(0.1f) }
        assertTrue(
            session.animationFor(WorldSession.PLAYER_ACTOR_ID).state !=
                com.stratum.core.domain.sprite.AnimationState.ATTACK,
            "the attack animation never ended",
        )
    }

    @Test
    fun `a looping animation keeps its clock across frames`() {
        val session = session()
        session.flatten()
        session.setMoveInput(1f, 0f)
        session.tick(0.1f)
        val first = session.animationFor(WorldSession.PLAYER_ACTOR_ID).elapsedMs
        session.tick(0.1f)
        val second = session.animationFor(WorldSession.PLAYER_ACTOR_ID).elapsedMs
        assertTrue(second > first, "the walk cycle restarted every frame")
    }

    @Test
    fun `a dead player animates as dead`() {
        val session = session()
        session.flatten()
        session.player = session.player.damaged(session.player.health)
        session.tick(0.05f)
        // A dead session stops ticking, so drive the selector directly.
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.DIE,
            com.stratum.core.domain.sprite.AnimationSelector.select(isDead = true),
        )
    }

    @Test
    fun `animation clocks are forgotten when an actor stops existing`() {
        val session = session()
        session.flatten()
        val enemy = session.spawn(TestContent.rat, session.player.position.translated(1f, 0f, 0f))
        session.tick(0.05f)

        // Ranks are rolled, so a spawn can be an elite with several times the
        // health. Keep swinging until it is actually gone.
        var guard = 0
        while (session.enemies.any { it.instanceId == enemy.instanceId } && guard < 60) {
            session.attack()
            session.tick(0.3f)
            guard++
        }
        assertTrue(
            session.enemies.none { it.instanceId == enemy.instanceId },
            "could not kill the spawn in $guard rounds",
        )

        session.tick(0.05f)
        assertEquals(
            0L,
            session.animationFor(enemy.instanceId).elapsedMs,
            "a dead actor's animation clock lingered for the rest of the run",
        )
    }

    @Test
    fun `the snapshot carries the player's animation`() {
        val session = session()
        session.flatten()
        session.setMoveInput(0f, 1f)
        session.tick(0.05f)
        assertEquals(
            com.stratum.core.domain.sprite.AnimationState.WALK,
            session.snapshot().playerAnimation.state,
        )
    }
}
