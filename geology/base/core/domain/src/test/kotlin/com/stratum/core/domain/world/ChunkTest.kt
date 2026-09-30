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
}
