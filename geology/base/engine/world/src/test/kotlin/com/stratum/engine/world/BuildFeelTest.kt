package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Building as a player does it: fast, in bursts, across chunk borders, with
 * undo and redo hammered in between. Nothing may throw, the hero may not be
 * nudged, and every block must be accounted for at the end.
 */
class BuildFeelTest {

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 12L, simulationRadius = 1),
    ).also { it.enemies = emptyList() }

    /** Stone underfoot and open air above, [radius] blocks each way, so every placement has somewhere to go. */
    private fun WorldSession.flatten(radius: Int = 8) {
        val feet = player.blockPos
        val w = world as MutableWorld
        for (dy in -radius..radius) for (dx in -radius..radius) {
            w.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z - 1), TestContent.registry.indexOf(TestContent.stone.id))
            for (dz in 0..5) w.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), BlockRegistry.AIR_INDEX)
        }
    }

    /** Stands the hero on the west edge of their chunk, so half of what they build is in the next chunk over. */
    private fun WorldSession.onChunkBorder() {
        val p = player.position
        val west = Math.floorDiv(p.x.toInt(), Chunk.SIZE) * Chunk.SIZE
        player = player.copy(position = p.copy(x = west + 0.5f))
    }

    @Test
    fun `a line dragged across the ground is built on the ground`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val ground = feet.z - 1
        session.selectBuildTool(BuildTool.LINE)
        // What a finger on the ground picks: the ground blocks themselves.
        val preview = session.previewBuild(BlockPos(feet.x + 2, feet.y, ground), BlockPos(feet.x + 5, feet.y, ground))
        assertEquals((2..5).map { BlockPos(feet.x + it, feet.y, feet.z) }, preview.positions)
        assertEquals(4, assertIs<BuildResult.Built>(session.commitBuild()).placed)
    }

    @Test
    fun `a box dragged on the ground rises its full height`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val ground = feet.z - 1
        session.selectBuildTool(BuildTool.BOX)
        session.setBuildHeight(2)
        val preview = session.previewBuild(BlockPos(feet.x + 2, feet.y, ground), BlockPos(feet.x + 3, feet.y + 1, ground))
        assertEquals(8, preview.positions.size, "two courses of a two-by-two box")
        assertTrue(preview.positions.all { it.z == feet.z || it.z == feet.z + 1 })
    }

    @Test
    fun `rapid place, undo and redo across a chunk border never throw, never move the hero, and lose no block`() {
        val session = session()
        session.onChunkBorder()
        session.flatten()
        val start = session.player.position
        val feet = session.player.blockPos
        val block = session.player.selectedBlockId!!
        val held = session.player.countOf(block)
        val random = Random(7)
        session.selectBuildTool(BuildTool.SINGLE)
        repeat(300) {
            when (random.nextInt(10)) {
                in 0..5 -> {
                    // A tap on the ground within reach, on either side of the border.
                    val dx = random.nextInt(-3, 4).let { if (it == 0) 2 else it }
                    val dy = random.nextInt(-3, 4)
                    session.place(BlockPos(feet.x + dx, feet.y + dy, feet.z - 1))
                }
                in 6..7 -> session.undo()
                else -> session.redo()
            }
            assertEquals(start, session.player.position, "building moved the hero")
        }
        // Everything placed is either standing in the world or back in the bag.
        val standing = (-8..8).sumOf { dy -> (-8..8).sumOf { dx -> (0..5).count { dz ->
            session.world.blockAt(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz)).id == block
        } } }
        assertEquals(held, session.player.countOf(block) + standing)
        while (session.canUndo) session.undo()
        assertEquals(held, session.player.countOf(block), "undoing everything did not give every block back")
    }
}
