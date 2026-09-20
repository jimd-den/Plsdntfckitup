package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingWorldTest {

    private val config = WorldConfig(seed = 4242L, simulationRadius = 1)
    private val registry = TestContent.registry

    private fun world(cfg: WorldConfig = config) = StreamingWorld(
        registry,
        LayeredTerrainGenerator(cfg, TestContent.assembled.biomes),
        cfg,
    )

    @Test
    fun `focusing loads exactly the configured window`() {
        val world = world()
        val delta = world.focusOn(ChunkPos(0, 0))
        assertEquals(9, world.loadedChunks.size)
        assertEquals(9, delta.loaded.size)
        assertTrue(delta.unloaded.isEmpty())
        assertTrue(world.isLoaded(ChunkPos(1, 1)))
        assertFalse(world.isLoaded(ChunkPos(2, 0)))
    }

    @Test
    fun `moving the focus reports only what appeared and vanished`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        val delta = world.focusOn(ChunkPos(1, 0))

        assertEquals(9, world.loadedChunks.size)
        assertEquals(setOf(ChunkPos(2, -1), ChunkPos(2, 0), ChunkPos(2, 1)), delta.loaded.toSet())
        assertEquals(setOf(ChunkPos(-1, -1), ChunkPos(-1, 0), ChunkPos(-1, 1)), delta.unloaded.toSet())
    }

    @Test
    fun `re-focusing on the same chunk changes nothing`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        assertTrue(world.focusOn(ChunkPos(0, 0)).isEmpty)
    }

    @Test
    fun `an untouched chunk regenerates identically after being unloaded`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        val before = world.chunkAt(ChunkPos(-1, -1))!!.exportBlocks()

        world.focusOn(ChunkPos(4, 4))
        assertFalse(world.isLoaded(ChunkPos(-1, -1)))
        world.focusOn(ChunkPos(0, 0))

        val after = world.chunkAt(ChunkPos(-1, -1))!!.exportBlocks()
        assertTrue(before.contentEquals(after), "regenerated terrain drifted")
    }

    @Test
    fun `a tunnel the player dug survives walking away and coming back`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))

        // Carve straight down through whatever terrain generated here.
        val column = BlockPos(2, 2, 0)
        val surface = world.surfaceAt(column.x, column.y)
        assertTrue(surface > 1, "expected terrain to dig through")
        val dug = (1..surface).map { BlockPos(column.x, column.y, it) }
        dug.forEach { world.setBlock(it, BlockRegistry.AIR_INDEX) }

        world.focusOn(ChunkPos(9, 9))
        world.focusOn(ChunkPos(0, 0))

        dug.forEach { pos ->
            assertTrue(world.blockAt(pos).isAir, "the world grew back over the tunnel at $pos")
        }
    }

    @Test
    fun `edited chunks are reported for saving and untouched ones are not`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        assertTrue(world.dirtyChunks().isEmpty())

        world.setBlock(BlockPos(1, 1, 1), BlockRegistry.AIR_INDEX)
        val dirty = world.dirtyChunks()
        assertEquals(1, dirty.size)
        assertEquals(ChunkPos(0, 0), dirty.single().pos)
    }

    @Test
    fun `writes outside the loaded window are ignored rather than silently lost`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        assertFalse(world.setBlock(BlockPos(500, 500, 5), registry.indexOf(TestContent.stone.id)))
    }

    @Test
    fun `writes outside the world column are rejected`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        assertFalse(world.setBlock(BlockPos(0, 0, -1), registry.indexOf(TestContent.stone.id)))
        assertFalse(world.setBlock(BlockPos(0, 0, 9999), registry.indexOf(TestContent.stone.id)))
    }

    @Test
    fun `unloaded regions read as air rather than throwing`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))
        assertTrue(world.blockAt(BlockPos(9999, 9999, 4)).isAir)
        assertEquals(-1, world.surfaceAt(9999, 9999))
        assertEquals(0, world.lightAt(BlockPos(9999, 9999, 4)))
    }

    @Test
    fun `a saved chunk can be installed in place of generated terrain`() {
        val world = world()
        world.focusOn(ChunkPos(0, 0))

        val restored = com.stratum.core.domain.world.Chunk(ChunkPos(0, 0))
        restored.setBlock(0, 0, 1, registry.indexOf(TestContent.stone.id))
        world.installChunk(restored)

        assertEquals(TestContent.stone.id, world.blockAt(BlockPos(0, 0, 1)).id)
        assertTrue(world.blockAt(BlockPos(0, 0, 2)).isAir)
    }

    @Test
    fun `a larger simulation radius loads the square it promises`() {
        val wide = WorldConfig(seed = 1L, simulationRadius = 3)
        assertEquals(49, wide.loadedChunkCount)
        val world = world(wide)
        world.focusOn(ChunkPos(0, 0))
        assertEquals(49, world.loadedChunks.size)
    }

    @Test
    fun `focusing by block position resolves to the containing chunk`() {
        val world = world()
        world.focusOn(BlockPos(-1, -1, 5))
        assertEquals(ChunkPos(-1, -1), world.focus)
    }
}
