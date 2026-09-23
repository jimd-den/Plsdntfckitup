package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockShapes
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WallTest {

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

    private fun WorldSession.flatten(radius: Int = 6) {
        val feet = player.blockPos
        for (dy in -radius..radius) for (dx in -radius..radius) {
            put(BlockPos(feet.x + dx, feet.y + dy, feet.z - 1), TestContent.stone.id)
            for (dz in 0..3) clear(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz))
        }
    }

    @Test
    fun `a lone wall runs east to west rather than shrinking to a post`() {
        val boxes = BlockShapes.boxes(BlockShape.WALL, connections = 0)
        val minX = boxes.minOf { it.minX }
        val maxX = boxes.maxOf { it.maxX }
        assertEquals(0f, minX)
        assertEquals(1f, maxX)
    }

    @Test
    fun `a wall is a third of a block thick`() {
        val core = BlockShapes.boxes(BlockShape.WALL, BlockShapes.EAST or BlockShapes.WEST)
        val thickness = core.maxOf { it.maxY } - core.minOf { it.minY }
        assertEquals(1f / 3f, thickness, 0.0001f)
    }

    @Test
    fun `walls join their neighbours without being rotated`() {
        val session = session()
        session.flatten()
        val base = session.player.blockPos.offsetBy(2, 0)
        session.put(base, TestContent.wall.id)
        session.put(base.offsetBy(1, 0), TestContent.wall.id)
        session.put(base.offsetBy(0, 1), TestContent.wall.id)

        val mask = BlockShapes.connections(session.world, base)
        assertEquals(BlockShapes.EAST or BlockShapes.SOUTH, mask, "an L joins both ways it turns")
    }

    @Test
    fun `you can walk beside a wall inside its own cell`() {
        // The whole reason for the shape: a wall a full block thick makes the
        // cell it stands in unusable, a thin one leaves room either side.
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        // A north-south run of wall one cell east of the player.
        for (dy in -3..3) session.put(BlockPos(feet.x + 1, feet.y + dy, feet.z), TestContent.wall.id)

        // Stand in the western third of the wall's cell: open ground.
        val beside = WorldPoint(feet.x + 1.15f, feet.y + 0.5f, feet.z.toFloat())
        assertTrue(!BlockShapes.occupies(session.world, BlockPos(feet.x + 1, feet.y, feet.z), 0.15f, 0.5f, PlayerMotion.BODY_MARGIN))
        // ...and the middle third is wall.
        assertTrue(BlockShapes.occupies(session.world, BlockPos(feet.x + 1, feet.y, feet.z), 0.5f, 0.5f))
        assertEquals(feet.x + 1, beside.toBlockPos().x)
    }

    @Test
    fun `a wall stops the player walking through it`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        for (dy in -4..4) {
            session.put(BlockPos(feet.x + 2, feet.y + dy, feet.z), TestContent.wall.id)
            session.put(BlockPos(feet.x + 2, feet.y + dy, feet.z + 1), TestContent.wall.id)
        }
        session.setMoveInput(1f, 0f)
        repeat(60) { session.tick(0.05f) }

        assertTrue(
            session.player.position.x < feet.x + 2 + 0.5f,
            "walked through the wall to ${session.player.position.x}",
        )
        assertTrue(session.player.position.x > feet.x + 1.5f, "stopped a whole cell short of a thin wall")
    }

    @Test
    fun `erasing a drag hands the blocks back`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        val start = feet.offsetBy(2, -1)
        for (dy in 0..2) session.put(start.offsetBy(0, dy), TestContent.wall.id)
        val before = session.player.countOf(TestContent.wall.id)

        session.selectBuildTool(BuildTool.ERASE)
        // Drags are anchored on the ground the player touched, one below the wall.
        val preview = session.previewBuild(start.below(), start.offsetBy(0, 2).below())
        assertEquals(3, preview.positions.size)

        val result = assertIs<BuildResult.Erased>(session.commitBuild())
        assertEquals(3, result.removed)
        assertEquals(before + 3, session.player.countOf(TestContent.wall.id))
        assertTrue(session.world.blockAt(start).isAir)
    }

    @Test
    fun `erasing never takes the ground out from under the player`() {
        val session = session()
        session.flatten()
        val feet = session.player.blockPos
        session.selectBuildTool(BuildTool.ERASE)
        val preview = session.previewBuild(feet.below().below(), feet.below().below())
        assertTrue(feet.below() !in preview.positions)
    }

    private fun BlockPos.offsetBy(dx: Int, dy: Int) = BlockPos(x + dx, y + dy, z)
}
