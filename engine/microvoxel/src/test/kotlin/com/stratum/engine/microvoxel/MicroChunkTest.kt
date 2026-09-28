package com.stratum.engine.microvoxel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicroChunkTest {
    private val pos = MicroChunkPos(0, 0, 0)

    @Test
    fun `a voxel reads back what was written`() {
        val c = MicroChunk(pos)
        assertTrue(c.set(5, 63, 17, 3))
        assertEquals(3.toShort(), c[5, 63, 17])
        assertEquals(MaterialPalette.AIR, c[6, 63, 17])
        assertFalse(c.set(5, 63, 17, 3), "writing the same value is not a change")
        assertFalse(c.set(64, 0, 0, 3), "out of range is ignored")
    }

    @Test
    fun `an empty chunk is tiny and a flat array would not be`() {
        val c = MicroChunk(pos)
        assertTrue(c.isEmpty())
        assertTrue(c.approximateBytes() < 8 * 1024, "empty chunk costs ${c.approximateBytes()} bytes")
    }

    @Test
    fun `filling whole bricks stores them as one value each`() {
        val c = MicroChunk(pos)
        c.fill(0, 0, 0, 63, 63, 31, 4)
        assertEquals(0, c.detailBrickCount)
        assertEquals(4.toShort(), c[10, 10, 31])
        assertEquals(MaterialPalette.AIR, c[10, 10, 32])
    }

    @Test
    fun `a partial fill details only the bricks it cuts`() {
        val c = MicroChunk(pos)
        c.fill(0, 0, 0, 63, 63, 12, 4) // z 8..12 cuts one layer of bricks
        assertEquals(64, c.detailBrickCount)
        assertEquals(4.toShort(), c[0, 0, 12])
        assertEquals(MaterialPalette.AIR, c[0, 0, 13])
    }

    @Test
    fun `compact collapses bricks that became uniform again`() {
        val c = MicroChunk(pos)
        c.set(1, 1, 1, 7)
        assertEquals(1, c.detailBrickCount)
        c.set(1, 1, 1, MaterialPalette.AIR)
        c.compact()
        assertEquals(0, c.detailBrickCount)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `a brick survives more than 256 materials`() {
        val c = MicroChunk(pos)
        var m = 1
        for (z in 0 until 8) for (y in 0 until 8) for (x in 0 until 8) c.set(x, y, z, (m++).toShort())
        m = 1
        for (z in 0 until 8) for (y in 0 until 8) for (x in 0 until 8) assertEquals((m++).toShort(), c[x, y, z])
    }

    @Test
    fun `opaque rows put voxel x at bit x`() {
        val p = MaterialPalette.standard()
        val c = MicroChunk(pos)
        c.set(0, 2, 3, p.id(M.STONE))
        c.set(63, 2, 3, p.id(M.STONE))
        c.set(10, 2, 3, p.id(M.WATER)) // not opaque
        val rows = c.opaqueRows(p)
        assertEquals(1L or (1L shl 63), rows[3 * 64 + 2])
    }

    @Test
    fun `chunk positions floor negative coordinates`() {
        assertEquals(MicroChunkPos(-1, 0, -1), MicroChunkPos.containing(-1, 0, -64))
        assertEquals(MicroChunkPos(-2, 1, 0), MicroChunkPos.containing(-65, 64, 63))
    }
}
