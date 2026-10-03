package com.stratum.core.domain.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkTest {

    @Test
    fun `reads and writes round trip`() {
        val chunk = Chunk(ChunkPos(0, 0))
        assertTrue(chunk.setBlock(3, 4, 5, 7))
        assertEquals(7, chunk.blockAt(3, 4, 5))
    }

    @Test
    fun `writing the same value twice reports no change so renderers can skip it`() {
        val chunk = Chunk(ChunkPos(0, 0))
        assertTrue(chunk.setBlock(1, 1, 1, 4))
        assertFalse(chunk.setBlock(1, 1, 1, 4))
    }

    @Test
    fun `out of bounds access is clamped rather than throwing`() {
        val chunk = Chunk(ChunkPos(0, 0))
        assertEquals(BlockRegistry.AIR_INDEX, chunk.blockAt(-1, 0, 0))
        assertEquals(BlockRegistry.AIR_INDEX, chunk.blockAt(0, 0, Chunk.HEIGHT))
        assertFalse(chunk.setBlock(Chunk.SIZE, 0, 0, 3))
    }

    @Test
    fun `revision advances only on real changes`() {
        val chunk = Chunk(ChunkPos(0, 0))
        val start = chunk.revision
        chunk.setBlock(0, 0, 0, 2)
        chunk.setBlock(0, 0, 0, 2)
        assertEquals(start + 1, chunk.revision)
    }

    @Test
    fun `surface height tracks the highest non air block and updates after edits`() {
        val chunk = Chunk(ChunkPos(0, 0))
        assertEquals(-1, chunk.surfaceAt(2, 2))

        chunk.setBlock(2, 2, 0, 1)
        chunk.setBlock(2, 2, 9, 1)
        assertEquals(9, chunk.surfaceAt(2, 2))

        chunk.setBlock(2, 2, 9, BlockRegistry.AIR_INDEX)
        assertEquals(0, chunk.surfaceAt(2, 2))
    }

    @Test
    fun `index mapping is unique across the whole volume`() {
        val seen = HashSet<Int>(Chunk.VOLUME)
        for (z in 0 until Chunk.HEIGHT) {
            for (y in 0 until Chunk.SIZE) {
                for (x in 0 until Chunk.SIZE) {
                    assertTrue(seen.add(Chunk.indexOf(x, y, z)), "collision at $x,$y,$z")
                }
            }
        }
        assertEquals(Chunk.VOLUME, seen.size)
    }

    @Test
    fun `restore rebuilds an identical chunk from exported arrays`() {
        val original = Chunk(ChunkPos(1, 2))
        original.setBlock(5, 6, 7, 9)
        original.setLight(5, 6, 7, 11)

        val copy = Chunk.restore(original.pos, original.exportBlocks(), original.exportLight())
        assertEquals(9, copy.blockAt(5, 6, 7))
        assertEquals(11, copy.lightAt(5, 6, 7))
    }

    @Test
    fun `the surface stays true through thousands of edits without rescanning the chunk`() {
        val chunk = Chunk(ChunkPos(0, 0))
        val r = kotlin.random.Random(3)
        for (x in 0 until Chunk.SIZE) for (y in 0 until Chunk.SIZE) for (z in 0..r.nextInt(20)) chunk.setBlock(x, y, z, 1)
        chunk.surfaceAt(0, 0) // the map is built once here, then kept current edit by edit
        repeat(5_000) {
            val x = r.nextInt(Chunk.SIZE); val y = r.nextInt(Chunk.SIZE); val z = r.nextInt(Chunk.HEIGHT)
            chunk.setBlock(x, y, z, if (r.nextInt(3) == 0) 1 else BlockRegistry.AIR_INDEX)
            val truth = (Chunk.HEIGHT - 1 downTo 0).firstOrNull { chunk.blockAt(x, y, it) != BlockRegistry.AIR_INDEX } ?: -1
            kotlin.test.assertEquals(truth, chunk.surfaceAt(x, y), "column ($x, $y) after setting z=$z")
        }
        val fresh = Chunk.restore(chunk.pos, chunk.exportBlocks(), chunk.exportLight())
        for (x in 0 until Chunk.SIZE) for (y in 0 until Chunk.SIZE) kotlin.test.assertEquals(fresh.surfaceAt(x, y), chunk.surfaceAt(x, y))
    }
}
