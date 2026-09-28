package com.stratum.engine.microvoxel.mesh

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk

/** Anything cubic a mesher can read: a full-resolution chunk or a downsampled LOD of one. */
interface VoxelGrid {
    /** Voxels per side, at most 64 (one `Long` per row). */
    val size: Int
    operator fun get(x: Int, y: Int, z: Int): Short
}

/** Opacity just outside a grid, so faces against a neighbouring chunk are culled. Coordinates are grid-local. */
fun interface NeighborOpacity {
    fun opaque(x: Int, y: Int, z: Int): Boolean

    companion object {
        /** Treat the outside as open air: every boundary face is drawn. */
        val OPEN = NeighborOpacity { _, _, _ -> false }
    }
}

/**
 * Greedy-merged quads, packed one per `Long` -- 8 bytes a quad, the whole
 * vertex payload for instanced rendering on GLES 3.0 (see [Quad]).
 */
class QuadMesh(val quads: LongArray, val count: Int) {
    /** Total visible face area in voxel faces: what a naive mesher would have emitted as separate quads. */
    fun area(): Long { var a = 0L; for (i in 0 until count) a += Quad.w(quads[i]).toLong() * Quad.h(quads[i]); return a }
}

/**
 * Bit layout of a packed quad.
 *
 * ```
 *  bits  0-5   x      min-corner voxel
 *  bits  6-11  y
 *  bits 12-17  z
 *  bits 18-23  w - 1  extent along the face's u axis
 *  bits 24-29  h - 1  extent along the face's v axis
 *  bits 30-32  face   0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z
 *  bits 33-48  material id
 * ```
 * u/v are (y, z) for X faces, (x, z) for Y faces, (x, y) for Z faces. A
 * vertex shader expands one instance into four corners with
 * `gl_VertexID`, so the GPU never sees a float position array.
 */
object Quad {
    fun pack(x: Int, y: Int, z: Int, w: Int, h: Int, face: Int, material: Short): Long =
        x.toLong() or (y.toLong() shl 6) or (z.toLong() shl 12) or ((w - 1).toLong() shl 18) or
            ((h - 1).toLong() shl 24) or (face.toLong() shl 30) or ((material.toLong() and 0xFFFF) shl 33)

    fun x(q: Long) = (q and 63).toInt()
    fun y(q: Long) = ((q ushr 6) and 63).toInt()
    fun z(q: Long) = ((q ushr 12) and 63).toInt()
    fun w(q: Long) = ((q ushr 18) and 63).toInt() + 1
    fun h(q: Long) = ((q ushr 24) and 63).toInt() + 1
    fun face(q: Long) = ((q ushr 30) and 7).toInt()
    fun material(q: Long) = ((q ushr 33) and 0xFFFF).toShort()
}

/**
 * Binary greedy meshing (after cgerikj's `binary-greedy-meshing` and
 * TanTan's write-up): every row of 64 voxels is one `Long`, so hidden-face
 * culling for a whole row is a shift and an and-not, and merging walks set
 * bits with `numberOfTrailingZeros` instead of visiting every voxel.
 *
 * Merged quads only join faces of the same material, so colour stays exact.
 * It is single-threaded and allocation-light by design; a streaming world
 * runs one per worker thread.
 */
class BinaryGreedyMesher(private val palette: MaterialPalette) {

    fun mesh(grid: VoxelGrid, neighbors: NeighborOpacity = NeighborOpacity.OPEN): QuadMesh {
        val n = grid.size
        require(n in 1..64) { "Grids up to 64 per side, got $n" }
        val full = if (n == 64) -1L else (1L shl n) - 1
        val rows = if (grid is MicroChunk) grid.opaqueRows(palette) else opaqueRows(grid, n)

        // Rows of the neighbouring layers, for culling at the grid boundary.
        val negZ = LongArray(n); val posZ = LongArray(n) // indexed by y, bits x
        val negY = LongArray(n); val posY = LongArray(n) // indexed by z, bits x
        val negX = LongArray(n); val posX = LongArray(n) // indexed by z, bits y
        for (a in 0 until n) for (b in 0 until n) {
            if (neighbors.opaque(b, a, -1)) negZ[a] = negZ[a] or (1L shl b)
            if (neighbors.opaque(b, a, n)) posZ[a] = posZ[a] or (1L shl b)
            if (neighbors.opaque(b, -1, a)) negY[a] = negY[a] or (1L shl b)
            if (neighbors.opaque(b, n, a)) posY[a] = posY[a] or (1L shl b)
            if (neighbors.opaque(-1, b, a)) negX[a] = negX[a] or (1L shl b)
            if (neighbors.opaque(n, b, a)) posX[a] = posX[a] or (1L shl b)
        }

        val out = QuadSink()
        val plane = LongArray(n * n)

        // ±Z: slice z, rows y, bits x.
        for (dir in 0..1) {
            for (z in 0 until n) for (y in 0 until n) {
                val r = rows[z * n + y]
                val other = if (dir == 0) (if (z + 1 < n) rows[(z + 1) * n + y] else posZ[y]) else (if (z > 0) rows[(z - 1) * n + y] else negZ[y])
                plane[z * n + y] = r and other.inv()
            }
            merge(plane, n, grid, 4 + dir, out)
        }
        // ±Y: slice y, rows z, bits x.
        for (dir in 0..1) {
            for (z in 0 until n) for (y in 0 until n) {
                val r = rows[z * n + y]
                val other = if (dir == 0) (if (y + 1 < n) rows[z * n + y + 1] else posY[z]) else (if (y > 0) rows[z * n + y - 1] else negY[z])
                plane[y * n + z] = r and other.inv()
            }
            merge(plane, n, grid, 2 + dir, out)
        }
        // ±X: cull along the row's own bits, then scatter into slice x, rows z, bits y.
        for (dir in 0..1) {
            java.util.Arrays.fill(plane, 0L)
            for (z in 0 until n) for (y in 0 until n) {
                val r = rows[z * n + y]
                val edge = if (dir == 0) ((posX[z] ushr y) and 1L) shl (n - 1) else ((negX[z] ushr y) and 1L)
                val shifted = if (dir == 0) (r ushr 1) or edge else ((r shl 1) and full) or edge
                var faces = r and shifted.inv() and full
                while (faces != 0L) {
                    val x = java.lang.Long.numberOfTrailingZeros(faces)
                    faces = faces and (faces - 1)
                    plane[x * n + z] = plane[x * n + z] or (1L shl y)
                }
            }
            merge(plane, n, grid, dir, out)
        }
        return QuadMesh(out.quads, out.count)
    }

    /** Greedy merge of one direction's planes: widen along the bits, then grow down the rows while every voxel matches. */
    private fun merge(plane: LongArray, n: Int, grid: VoxelGrid, face: Int, out: QuadSink) {
        for (slice in 0 until n) {
            val base = slice * n
            for (v in 0 until n) {
                while (plane[base + v] != 0L) {
                    val bits = plane[base + v]
                    val u = java.lang.Long.numberOfTrailingZeros(bits)
                    val m = material(grid, face, slice, u, v)
                    var w = 1
                    while (u + w < n && ((bits ushr (u + w)) and 1L) == 1L && material(grid, face, slice, u + w, v) == m) w++
                    val span = (if (w == 64) -1L else (1L shl w) - 1) shl u
                    var h = 1
                    grow@ while (v + h < n) {
                        val row = plane[base + v + h]
                        if ((row and span) != span) break
                        for (k in u until u + w) if (material(grid, face, slice, k, v + h) != m) break@grow
                        plane[base + v + h] = row and span.inv()
                        h++
                    }
                    plane[base + v] = bits and span.inv()
                    val x: Int; val y: Int; val z: Int
                    when (face) {
                        0, 1 -> { x = slice; y = u; z = v }
                        2, 3 -> { x = u; y = slice; z = v }
                        else -> { x = u; y = v; z = slice }
                    }
                    out.add(Quad.pack(x, y, z, w, h, face, m))
                }
            }
        }
    }

    private fun material(grid: VoxelGrid, face: Int, slice: Int, u: Int, v: Int): Short = when (face) {
        0, 1 -> grid[slice, u, v]
        2, 3 -> grid[u, slice, v]
        else -> grid[u, v, slice]
    }

    private fun opaqueRows(grid: VoxelGrid, n: Int): LongArray {
        val rows = LongArray(n * n)
        for (z in 0 until n) for (y in 0 until n) {
            var r = 0L
            for (x in 0 until n) if (palette.isOpaque(grid[x, y, z])) r = r or (1L shl x)
            rows[z * n + y] = r
        }
        return rows
    }

    private class QuadSink {
        var quads = LongArray(1024); var count = 0
        fun add(q: Long) {
            if (count == quads.size) quads = quads.copyOf(count * 2)
            quads[count++] = q
        }
    }
}
