package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockInteractionTest {

    /** An empty world, so each test builds exactly the geometry its rule needs. */
    private object EmptyTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private lateinit var world: MutableWorld
    private lateinit var system: BlockInteractionSystem

    private val origin = BlockPos(0, 0, 5)

    @BeforeTest
    fun setUp() {
        world = StreamingWorld(TestContent.registry, EmptyTerrain, WorldConfig(simulationRadius = 1))
            .also { it.focusOn(ChunkPos(0, 0)) }
        system = BlockInteractionSystem(world)
    }

    private fun put(pos: BlockPos, type: BlockType) {
        world.setBlock(pos, TestContent.registry.indexOf(type.id))
    }

    // ---- mining ----------------------------------------------------------

    @Test
    fun `mining accumulates effort instead of breaking on contact`() {
        val target = BlockPos(1, 0, 5)
        put(target, TestContent.stone)

        val partial = system.mine(
            MineRequest(origin, target, toolTier = 1, deltaSeconds = 0.5f),
        )
        val progress = assertIs<MineResult.InProgress>(partial)
        assertEquals(2f, progress.required)
        assertTrue(progress.fraction < 1f)
        assertTrue(world.isSolid(target), "block broke before its hardness was met")
    }

    @Test
    fun `accumulated effort eventually breaks the block and yields its drop`() {
        val target = BlockPos(1, 0, 5)
        put(target, TestContent.ore)

        var progress = 0f
        var result: MineResult
        var guard = 0
        do {
            result = system.mine(
                MineRequest(origin, target, toolTier = 2, deltaSeconds = 0.5f, progressSoFar = progress),
            )
            if (result is MineResult.InProgress) progress = result.progress
            guard++
        } while (result is MineResult.InProgress && guard < 50)

        val broken = assertIs<MineResult.Broken>(result)
        assertEquals("test:ingot", broken.drop, "ore should drop an ingot, not itself")
        assertTrue(world.blockAt(target).isAir)
    }

    @Test
    fun `a better tool digs the same block faster`() {
        val target = BlockPos(1, 0, 5)
        put(target, TestContent.stone)

        val slow = assertIs<MineResult.InProgress>(
            system.mine(MineRequest(origin, target, toolTier = 1, deltaSeconds = 0.5f)),
        )
        val fast = assertIs<MineResult.InProgress>(
            system.mine(MineRequest(origin, target, toolTier = 4, deltaSeconds = 0.5f)),
        )
        assertTrue(fast.progress > slow.progress, "tier gave no advantage: ${fast.progress} vs ${slow.progress}")
    }

    @Test
    fun `a tool below the required tier is refused outright`() {
        val target = BlockPos(1, 0, 5)
        put(target, TestContent.ore)
        val result = system.mine(MineRequest(origin, target, toolTier = 1, deltaSeconds = 99f))
        assertEquals(MineRejection.TOOL_TOO_WEAK, assertIs<MineResult.Rejected>(result).reason)
    }

    @Test
    fun `bedrock cannot be mined at any tier`() {
        val target = BlockPos(1, 0, 5)
        put(target, BlockType.BEDROCK)
        val result = system.mine(MineRequest(origin, target, toolTier = 99, deltaSeconds = 999f))
        assertEquals(MineRejection.UNBREAKABLE, assertIs<MineResult.Rejected>(result).reason)
    }

    @Test
    fun `mining beyond reach is refused`() {
        val target = BlockPos(20, 0, 5)
        put(target, TestContent.soil)
        val result = system.mine(MineRequest(origin, target, toolTier = 1, deltaSeconds = 99f))
        assertEquals(MineRejection.OUT_OF_REACH, assertIs<MineResult.Rejected>(result).reason)
    }

    @Test
    fun `mining air reports that there is nothing there`() {
        val result = system.mine(MineRequest(origin, BlockPos(1, 0, 5), deltaSeconds = 1f))
        assertEquals(MineRejection.NOTHING_THERE, assertIs<MineResult.Rejected>(result).reason)
    }

    // ---- placing ---------------------------------------------------------

    @Test
    fun `a block placed against a solid neighbour is accepted`() {
        put(BlockPos(1, 0, 5), TestContent.stone)
        val result = system.place(PlaceRequest(origin, BlockPos(1, 0, 6), TestContent.soil.id))
        assertIs<PlaceResult.Placed>(result)
        assertEquals(TestContent.soil.id, world.blockAt(BlockPos(1, 0, 6)).id)
    }

    @Test
    fun `a block floating in mid air is refused`() {
        val result = system.place(PlaceRequest(origin, BlockPos(2, 2, 20), TestContent.soil.id))
        assertEquals(
            PlaceRejection.NOTHING_TO_ATTACH_TO,
            assertIs<PlaceResult.Rejected>(result).reason,
        )
    }

    @Test
    fun `placing into an occupied cell is refused`() {
        val target = BlockPos(1, 0, 5)
        put(target, TestContent.stone)
        val result = system.place(PlaceRequest(origin, target, TestContent.soil.id))
        assertEquals(PlaceRejection.OCCUPIED, assertIs<PlaceResult.Rejected>(result).reason)
    }

    @Test
    fun `placing where an actor stands is refused`() {
        put(BlockPos(1, 0, 5), TestContent.stone)
        val occupied = BlockPos(1, 0, 6)
        val result = system.place(
            PlaceRequest(origin, occupied, TestContent.soil.id, occupiedByActors = setOf(occupied)),
        )
        assertEquals(PlaceRejection.ACTOR_IN_THE_WAY, assertIs<PlaceResult.Rejected>(result).reason)
    }

    @Test
    fun `an unknown block id is refused rather than crashing the registry`() {
        put(BlockPos(1, 0, 5), TestContent.stone)
        val result = system.place(PlaceRequest(origin, BlockPos(1, 0, 6), "test:nonexistent"))
        assertEquals(PlaceRejection.UNKNOWN_BLOCK, assertIs<PlaceResult.Rejected>(result).reason)
    }

    @Test
    fun `placing outside the world column is refused`() {
        val result = system.place(PlaceRequest(origin, BlockPos(0, 0, Chunk.HEIGHT), TestContent.soil.id))
        assertEquals(PlaceRejection.OUT_OF_BOUNDS, assertIs<PlaceResult.Rejected>(result).reason)
    }

    // ---- gravity and support --------------------------------------------

    @Test
    fun `sand falls when the block beneath it is mined`() {
        val floor = BlockPos(1, 0, 3)
        val support = BlockPos(1, 0, 4)
        val sand = BlockPos(1, 0, 5)
        put(floor, TestContent.stone)
        put(support, TestContent.soil)
        put(sand, TestContent.sand)

        var progress = 0f
        var result: MineResult
        var guard = 0
        do {
            result = system.mine(MineRequest(origin, support, toolTier = 3, deltaSeconds = 0.5f, progressSoFar = progress))
            if (result is MineResult.InProgress) progress = result.progress
            guard++
        } while (result is MineResult.InProgress && guard < 50)

        val broken = assertIs<MineResult.Broken>(result)
        assertTrue(world.blockAt(sand).isAir, "sand stayed floating")
        assertEquals(TestContent.sand.id, world.blockAt(support).id, "sand did not land on the stone floor")
        val fall = broken.cascade.single { it.kind == BlockChangeKind.FELL }
        assertEquals(sand, fall.from)
        assertEquals(support, fall.to)
    }

    @Test
    fun `a stack of sand collapses in order rather than interpenetrating`() {
        put(BlockPos(1, 0, 2), TestContent.stone)
        put(BlockPos(1, 0, 3), TestContent.soil)
        (4..6).forEach { put(BlockPos(1, 0, it), TestContent.sand) }

        world.setBlock(BlockPos(1, 0, 3), BlockRegistry.AIR_INDEX)
        system.settle(BlockPos(1, 0, 3))

        assertEquals(TestContent.sand.id, world.blockAt(BlockPos(1, 0, 3)).id)
        assertEquals(TestContent.sand.id, world.blockAt(BlockPos(1, 0, 4)).id)
        assertEquals(TestContent.sand.id, world.blockAt(BlockPos(1, 0, 5)).id)
        assertTrue(world.blockAt(BlockPos(1, 0, 6)).isAir, "the stack did not compact")
    }

    @Test
    fun `a torch drops when the wall it hangs on is mined`() {
        val wall = BlockPos(1, 0, 5)
        val torch = BlockPos(2, 0, 5)
        put(wall, TestContent.stone)
        put(torch, TestContent.torch)

        world.setBlock(wall, BlockRegistry.AIR_INDEX)
        val cascade = system.settle(wall)

        assertTrue(world.blockAt(torch).isAir, "torch survived with nothing to hang on")
        assertEquals(BlockChangeKind.LOST_SUPPORT, cascade.single().kind)
    }

    @Test
    fun `a torch with another wall keeps hanging`() {
        val wallA = BlockPos(1, 0, 5)
        val wallB = BlockPos(3, 0, 5)
        val torch = BlockPos(2, 0, 5)
        put(wallA, TestContent.stone)
        put(wallB, TestContent.stone)
        put(torch, TestContent.torch)

        world.setBlock(wallA, BlockRegistry.AIR_INDEX)
        system.settle(wallA)

        assertEquals(TestContent.torch.id, world.blockAt(torch).id)
    }

    @Test
    fun `settling terminates on a tall sand column instead of hanging the frame`() {
        put(BlockPos(1, 0, 1), TestContent.stone)
        (2 until Chunk.HEIGHT).forEach { put(BlockPos(1, 0, it), TestContent.sand) }

        world.setBlock(BlockPos(1, 0, 1), BlockRegistry.AIR_INDEX)
        val cascade = system.settle(BlockPos(1, 0, 1))

        assertTrue(cascade.size <= BlockInteractionSystem.MAX_CASCADE)
        assertEquals(TestContent.sand.id, world.blockAt(BlockPos(1, 0, 0)).id)
    }
}
