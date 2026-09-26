package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug: step off a terrace and there is no way back up.
 *
 * Terraces were three blocks tall and a step was one, so every terrace edge
 * was a one-way drop, and any pit a player dug was a trap.
 */
class ClimbTest {

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 5L, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    private fun WorldSession.put(pos: BlockPos, blockId: String) {
        (world as MutableWorld).setBlock(pos, TestContent.registry.indexOf(blockId))
    }

    private fun WorldSession.clear(pos: BlockPos) {
        (world as MutableWorld).setBlock(pos, BlockRegistry.AIR_INDEX)
    }

    /** Flat ground at the player's feet, with a ledge [height] tall starting two cells east. */
    private fun WorldSession.ledge(height: Int) {
        val feet = player.blockPos
        for (dy in -6..6) for (dx in -6..6) {
            put(BlockPos(feet.x + dx, feet.y + dy, feet.z - 1), TestContent.stone.id)
            for (dz in 0..8) clear(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz))
            if (dx >= 2) for (dz in 0 until height) put(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), TestContent.stone.id)
        }
    }

    /** Holds a direction and reports the highest the player got. */
    private fun WorldSession.highestWhileHolding(dx: Float, ticks: Int): Float {
        setMoveInput(dx, 0f)
        var highest = player.position.z
        repeat(ticks) {
            tick(0.05f)
            highest = maxOf(highest, player.position.z)
        }
        return highest
    }

    @Test
    fun `holding against a terrace climbs it`() {
        val session = session()
        session.ledge(height = 3)
        val startZ = session.player.position.z
        assertEquals(startZ + 3f, session.highestWhileHolding(1f, 30), "never got up the three-block terrace")
    }

    @Test
    fun `brushing past a ledge does not climb it`() {
        // A climb has to be meant: tapping into a wall for a frame is not a request to scale it.
        val session = session()
        session.ledge(height = 2)
        val startZ = session.player.position.z
        session.setMoveInput(1f, 0f)
        repeat(6) { session.tick(0.05f) }
        // Close enough to touch it, not long enough to climb it.
        assertEquals(startZ, session.player.position.z)
    }

    @Test
    fun `a one block step is still free`() {
        val session = session()
        session.ledge(height = 1)
        val startZ = session.player.position.z
        assertEquals(startZ + 1f, session.highestWhileHolding(1f, 12))
    }

    @Test
    fun `a sheer cliff taller than a climb still stops you`() {
        val session = session()
        session.ledge(height = 6)
        val startZ = session.player.position.z
        assertEquals(startZ, session.highestWhileHolding(1f, 20))
    }

    @Test
    fun `a thin wall cannot be climbed`() {
        val session = session()
        session.ledge(height = 0)
        val feet = session.player.blockPos
        for (dy in -6..6) for (dz in 0..1) {
            session.put(BlockPos(feet.x + 2, feet.y + dy, feet.z + dz), TestContent.wall.id)
        }
        assertEquals(session.player.position.z, session.highestWhileHolding(1f, 30))
    }

    @Test
    fun `a pit you dug yourself is not a trap`() {
        val session = session()
        session.ledge(height = 0)
        val feet = session.player.blockPos
        // Dig a three-deep shaft under the player and drop into it.
        for (dz in 1..3) session.clear(BlockPos(feet.x, feet.y, feet.z - dz))
        session.put(BlockPos(feet.x, feet.y, feet.z - 4), TestContent.stone.id)
        session.tick(0.05f)
        assertEquals(feet.z - 3f, session.player.position.z, "did not fall into the shaft")

        val highest = session.highestWhileHolding(1f, 30)
        assertTrue(highest >= feet.z.toFloat(), "still stuck at $highest")
    }
}
