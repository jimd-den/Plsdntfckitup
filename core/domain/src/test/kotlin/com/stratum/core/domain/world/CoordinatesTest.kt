package com.stratum.core.domain.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinatesTest {

    @Test
    fun `chunk position uses floor division so negative coordinates do not fold onto zero`() {
        assertEquals(ChunkPos(0, 0), BlockPos(0, 0, 0).chunkPos)
        assertEquals(ChunkPos(0, 0), BlockPos(15, 15, 0).chunkPos)
        assertEquals(ChunkPos(1, 1), BlockPos(16, 16, 0).chunkPos)
        // The bug this guards: integer division rounds toward zero, which would
        // map both -1 and 0 into chunk 0 and tear the world along the axes.
        assertEquals(ChunkPos(-1, -1), BlockPos(-1, -1, 0).chunkPos)
        assertEquals(ChunkPos(-1, -1), BlockPos(-16, -16, 0).chunkPos)
        assertEquals(ChunkPos(-2, -2), BlockPos(-17, -17, 0).chunkPos)
    }

    @Test
    fun `local index stays inside the chunk for negative world coordinates`() {
        val pos = BlockPos(-3, -5, 7)
        val index = pos.localIndex
        assertTrue(index in 0 until Chunk.VOLUME, "index $index escaped the chunk volume")
        assertEquals(Chunk.indexOf(13, 11, 7), index)
    }

    @Test
    fun `offsetting by a direction and its opposite returns to the start`() {
        val start = BlockPos(4, -9, 12)
        Direction.entries.forEach { direction ->
            assertEquals(start, start.offset(direction).offset(direction.opposite))
        }
    }

    @Test
    fun `horizontal distance ignores height`() {
        val a = BlockPos(0, 0, 0)
        val b = BlockPos(3, 2, 40)
        assertEquals(3, a.horizontalDistanceTo(b))
    }

    @Test
    fun `chunk origin matches the block coordinates it contains`() {
        val chunk = ChunkPos(-2, 3)
        assertEquals(-32, chunk.originX)
        assertEquals(48, chunk.originY)
        assertEquals(chunk, BlockPos(chunk.originX, chunk.originY, 0).chunkPos)
        assertEquals(chunk, BlockPos(chunk.originX + Chunk.SIZE - 1, chunk.originY, 0).chunkPos)
    }

    @Test
    fun `world point snaps to the block that contains it`() {
        assertEquals(BlockPos(0, 0, 0), WorldPoint(0.9f, 0.1f, 0.5f).toBlockPos())
        assertEquals(BlockPos(-1, -1, 0), WorldPoint(-0.1f, -0.9f, 0f).toBlockPos())
        assertEquals(BlockPos(3, 4, 5), WorldPoint.centerOf(BlockPos(3, 4, 5)).toBlockPos())
    }
}
