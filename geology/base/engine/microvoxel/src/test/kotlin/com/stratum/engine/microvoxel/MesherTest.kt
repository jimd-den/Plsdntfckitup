package com.stratum.engine.microvoxel

import com.stratum.engine.microvoxel.mesh.BinaryGreedyMesher
import com.stratum.engine.microvoxel.mesh.CoarseGrid
import com.stratum.engine.microvoxel.mesh.Lod
import com.stratum.engine.microvoxel.mesh.NeighborOpacity
import com.stratum.engine.microvoxel.mesh.Quad
import com.stratum.engine.microvoxel.mesh.VoxelGrid
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MesherTest {
    private val palette = MaterialPalette.standard()
    private val stone = palette.id(M.STONE)
    private val grass = palette.id(M.GRASS)
    private val mesher = BinaryGreedyMesher(palette)

    @Test
    fun `one voxel is six unit quads`() {
        val c = MicroChunk(MicroChunkPos(0, 0, 0))
        c.set(10, 20, 30, stone)
        val mesh = mesher.mesh(c)
        assertEquals(6, mesh.count)
        assertEquals(6L, mesh.area())
        for (i in 0 until mesh.count) assertEquals(stone, Quad.material(mesh.quads[i]))
    }

    @Test
    fun `a full chunk is six quads`() {
        val c = MicroChunk(MicroChunkPos(0, 0, 0), stone)
        val mesh = mesher.mesh(c)
        assertEquals(6, mesh.count)
        assertEquals(6L * 64 * 64, mesh.area())
    }

    @Test
    fun `solid neighbours hide boundary faces`() {
        val c = MicroChunk(MicroChunkPos(0, 0, 0), stone)
        assertEquals(0, mesher.mesh(c, NeighborOpacity { _, _, _ -> true }).count)
    }

    @Test
    fun `merging never joins different materials`() {
        val c = MicroChunk(MicroChunkPos(0, 0, 0))
        c.fill(0, 0, 0, 63, 63, 0, stone)
        c.fill(0, 0, 0, 31, 63, 0, grass)
        val tops = mesher.mesh(c).let { m -> (0 until m.count).map { m.quads[it] }.filter { Quad.face(it) == 4 } }
        assertEquals(2, tops.size)
        assertEquals(setOf(stone, grass), tops.map(Quad::material).toSet())
    }

    @Test
    fun `greedy area equals the naive face count on random grids`() {
        val rnd = Random(7)
        repeat(5) {
            val n = 16
            val data = ShortArray(n * n * n) { if (rnd.nextFloat() < 0.45f) (if (rnd.nextBoolean()) stone else grass) else 0 }
            val grid = CoarseGrid(n, data)
            val mesh = mesher.mesh(grid)
            assertEquals(naiveFaces(grid), mesh.area())
            // And every quad lies on a real, exposed face.
            for (i in 0 until mesh.count) {
                val q = mesh.quads[i]
                assertTrue(palette.isOpaque(grid[Quad.x(q), Quad.y(q), Quad.z(q)]))
            }
        }
    }

    @Test
    fun `quads pack and unpack losslessly`() {
        val q = Quad.pack(63, 1, 40, 64, 3, 5, 1234)
        assertEquals(listOf(63, 1, 40, 64, 3, 5), listOf(Quad.x(q), Quad.y(q), Quad.z(q), Quad.w(q), Quad.h(q), Quad.face(q)))
        assertEquals(1234.toShort(), Quad.material(q))
    }

    @Test
    fun `lod keeps the top material and thin walls`() {
        val c = MicroChunk(MicroChunkPos(0, 0, 0))
        c.fill(0, 0, 0, 63, 63, 10, stone)
        c.fill(0, 0, 11, 63, 63, 11, grass) // one voxel of grass on top
        c.fill(20, 20, 12, 21, 40, 30, stone) // a wall two voxels thick
        val lod = Lod.downsample(c, 4, palette)
        assertEquals(16, lod.size)
        assertEquals(grass, lod[0, 0, 2], "the surface cell shows grass, not the rock under it")
        assertEquals(stone, lod[5, 7, 4], "a half-filled cell of wall survives")
    }

    private fun naiveFaces(g: VoxelGrid): Long {
        val n = g.size
        fun op(x: Int, y: Int, z: Int) = x in 0 until n && y in 0 until n && z in 0 until n && palette.isOpaque(g[x, y, z])
        var faces = 0L
        for (z in 0 until n) for (y in 0 until n) for (x in 0 until n) if (op(x, y, z)) {
            if (!op(x + 1, y, z)) faces++; if (!op(x - 1, y, z)) faces++
            if (!op(x, y + 1, z)) faces++; if (!op(x, y - 1, z)) faces++
            if (!op(x, y, z + 1)) faces++; if (!op(x, y, z - 1)) faces++
        }
        return faces
    }
}
