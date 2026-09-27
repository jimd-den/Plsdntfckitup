package com.stratum.engine.scene

import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ChunkMeshCacheTest {

    private val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL)

    /** A flat world of real chunks, ground at z = 3, spanning [span] chunks each way from the origin. */
    private class ChunkWorld(private val span: Int, grass: BlockType) : World {
        override val registry = BlockRegistry.build(listOf(grass))
        private val grassIndex = registry.indexOf(grass.id)
        private val chunks = HashMap<ChunkPos, Chunk>()

        init {
            for (cy in -span..span) for (cx in -span..span) {
                chunks[ChunkPos(cx, cy)] = Chunk(ChunkPos(cx, cy)).apply {
                    for (y in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) for (z in 0..3) setBlock(x, y, z, grassIndex)
                }
            }
        }

        fun set(pos: BlockPos, index: Int) {
            chunks.getValue(pos.chunkPos).setBlock(Math.floorMod(pos.x, Chunk.SIZE), Math.floorMod(pos.y, Chunk.SIZE), pos.z, index)
        }

        override val loadedChunks: Collection<Chunk> get() = chunks.values
        override fun chunkAt(pos: ChunkPos): Chunk? = chunks[pos]
        override fun blockIndexAt(pos: BlockPos): Int {
            val chunk = chunks[pos.chunkPos] ?: return BlockRegistry.AIR_INDEX
            return chunk.blockAt(Math.floorMod(pos.x, Chunk.SIZE), Math.floorMod(pos.y, Chunk.SIZE), pos.z)
        }
        override fun blockAt(pos: BlockPos): BlockType = registry.typeOf(blockIndexAt(pos))
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int =
            chunks[ChunkPos.containing(x, y)]?.surfaceAt(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE)) ?: -1
        override fun isLoaded(pos: ChunkPos): Boolean = pos in chunks
    }

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("house").direction)
    private fun cache() = ChunkMeshCache(TerrainMesher(director, TextureLibrary()))

    @Test
    fun `every chunk in view is meshed once, then nothing is until something changes`() {
        val world = ChunkWorld(span = 3, grass)
        val cache = cache()

        val first = cache.around(world, 0, 0, radius = 40, worldRevision = 0)
        assertEquals(36, cache.meshedLastCall, "chunks -3..2 each way: six by six")

        val again = cache.around(world, 0, 0, radius = 40, worldRevision = 0)
        assertEquals(0, cache.meshedLastCall)
        first.zip(again).forEach { (a, b) -> assertSame(a.mesh, b.mesh) }
    }

    @Test
    fun `digging inside a chunk remeshes only that chunk`() {
        val world = ChunkWorld(span = 3, grass)
        val cache = cache()
        val before = cache.around(world, 0, 0, radius = 40, worldRevision = 0)

        world.set(BlockPos(20, 20, 3), BlockRegistry.AIR_INDEX)
        val after = cache.around(world, 0, 0, radius = 40, worldRevision = 1)

        assertEquals(1, cache.meshedLastCall, "a neighbour reads one cell past its edge, and this edit is four cells in")
        val far = ChunkMeshCache.chunksCovering(0, 0, 40).indexOf(ChunkPos(-3, -3))
        assertSame(before[far].mesh, after[far].mesh, "a chunk far from the edit keeps its mesh")
        val edited = ChunkMeshCache.chunksCovering(0, 0, 40).indexOf(ChunkPos(1, 1))
        assertNotSame(before[edited].mesh, after[edited].mesh)
    }

    @Test
    fun `chunks that leave the view are forgotten and those that enter are meshed`() {
        val world = ChunkWorld(span = 6, grass)
        val cache = cache()
        cache.around(world, 0, 0, radius = 20, worldRevision = 0)

        cache.around(world, 48, 0, radius = 20, worldRevision = 0)

        assertEquals(12, cache.meshedLastCall, "three new columns of four chunks; the shared column is kept")
    }

    @Test
    fun `digging on a border remeshes the neighbours that share it`() {
        val world = ChunkWorld(span = 3, grass)
        val cache = cache()
        cache.around(world, 0, 0, radius = 40, worldRevision = 0)

        // The corner of chunk (1, 1): its edge and corner neighbours all read this cell.
        world.set(BlockPos(16, 16, 3), BlockRegistry.AIR_INDEX)
        cache.around(world, 0, 0, radius = 40, worldRevision = 1)

        assertEquals(9, cache.meshedLastCall)
    }

    @Test
    fun `chunks off screen are meshed a few at a time, nearest first, and on screen ones at once`() {
        val world = ChunkWorld(span = 6, grass)
        val cache = cache()
        val onScreen = ChunkPos(0, 0)
        // A cold start meshes the whole square at once; then the player walks east.
        cache.around(world, -48, 0, radius = 40, worldRevision = 0)
        assertEquals(0, cache.deferredLastCall)
        val first = cache.around(world, 0, 0, radius = 40, worldRevision = 0, urgent = { it == onScreen }, offscreenBudget = 2)
        // Chunks -6..-1 were meshed from the first position; 0..2 are new, six rows of them.
        assertEquals(3, cache.meshedLastCall, "the chunk on screen, and two more")
        assertEquals(15, cache.deferredLastCall)
        assertEquals(36 - 18 + 3, first.size, "chunks never meshed are left out rather than drawn empty")

        var calls = 1
        while (cache.deferredLastCall > 0) {
            cache.around(world, 0, 0, radius = 40, worldRevision = 0, urgent = { it == onScreen }, offscreenBudget = 2)
            calls++
        }
        assertEquals(9, calls, "eighteen new chunks at two a call, less the one meshed on sight")
        assertEquals(36, cache.around(world, 0, 0, radius = 40, worldRevision = 0).size)
    }
}
