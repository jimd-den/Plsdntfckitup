package com.stratum.engine.model.mask.sculpt

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a [Sculpture] into triangles by surface nets: one vertex in every
 * grid cell the surface passes through, at the mean of where it crosses the
 * cell's edges, and a quad across every grid edge it crosses. Watertight,
 * without marching cubes' ambiguous cases, and smoother at the same size.
 *
 * Only a narrow band is sampled finely: a coarse pass finds where the
 * surface is, and the fine grid is filled only in coarse cells near it, so
 * a detailed carving costs its surface, not its volume.
 *
 * Normals come from the field's gradient, so shading is smooth across the
 * facets; each vertex also knows which part's surface it is on.
 */
class SurfaceNets(private val sculpture: Sculpture, private val cell: Float) {

    class Result(val positions: FloatArray, val normals: FloatArray, val materials: IntArray, val indices: IntArray) {
        val vertexCount: Int get() = positions.size / 3
        val triangleCount: Int get() = indices.size / 3
    }

    fun mesh(): Result {
        val b = sculpture.bounds()
        val pad = cell * 3f
        val ox = b[0] - pad; val oy = b[1] - pad; val oz = b[2] - pad
        val nx = ceil((b[3] - b[0] + 2 * pad) / cell).toInt() + 1
        val ny = ceil((b[4] - b[1] + 2 * pad) / cell).toInt() + 1
        val nz = ceil((b[5] - b[2] + 2 * pad) / cell).toInt() + 1
        val reach = COARSE * cell * 1.8f
        val field = FloatArray(nx * ny * nz) { reach }
        val done = BooleanArray(nx * ny * nz)
        fun at(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

        // Coarse pass: every COARSE-th sample. Blocks the surface may pass through are sampled finely;
        // the rest keep a plain inside or outside value.
        val c = COARSE
        val cnx = (nx - 1) / c + 2; val cny = (ny - 1) / c + 2; val cnz = (nz - 1) / c + 2
        val coarse = FloatArray(cnx * cny * cnz)
        for (k in 0 until cnz) for (j in 0 until cny) for (i in 0 until cnx) {
            coarse[(k * cny + j) * cnx + i] = sculpture.d(ox + i * c * cell, oy + j * c * cell, oz + k * c * cell)
        }
        for (k in 0 until cnz - 1) for (j in 0 until cny - 1) for (i in 0 until cnx - 1) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (dk in 0..1) for (dj in 0..1) for (di in 0..1) {
                val v = coarse[((k + dk) * cny + j + dj) * cnx + i + di]; lo = min(lo, v); hi = max(hi, v)
            }
            val near = lo < reach && hi > -reach
            for (kk in k * c..min(nz - 1, (k + 1) * c)) for (jj in j * c..min(ny - 1, (j + 1) * c)) for (ii in i * c..min(nx - 1, (i + 1) * c)) {
                val o = at(ii, jj, kk)
                if (done[o]) continue
                if (near) { field[o] = sculpture.d(ox + ii * cell, oy + jj * cell, oz + kk * cell); done[o] = true }
                else field[o] = if (lo >= reach) reach else -reach
            }
        }

        // One vertex per crossed cell.
        val vertexOf = IntArray((nx - 1) * (ny - 1) * (nz - 1)) { -1 }
        val pos = FloatBuffer(); val nrm = FloatBuffer(); val mats = IntBuffer()
        val corner = FloatArray(8)
        val mat = IntArray(1)
        for (k in 0 until nz - 1) for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            var mask = 0
            for (q in 0 until 8) {
                val v = field[at(i + (q and 1), j + ((q shr 1) and 1), k + ((q shr 2) and 1))]
                corner[q] = v
                if (v < 0f) mask = mask or (1 shl q)
            }
            if (mask == 0 || mask == 255) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var n = 0
            for (e in EDGES.indices step 2) {
                val a = EDGES[e]; val bq = EDGES[e + 1]
                val da = corner[a]; val db = corner[bq]
                if ((da < 0f) == (db < 0f)) continue
                val t = da / (da - db)
                sx += ((a and 1) + ((bq and 1) - (a and 1)) * t)
                sy += (((a shr 1) and 1) + (((bq shr 1) and 1) - ((a shr 1) and 1)) * t)
                sz += (((a shr 2) and 1) + (((bq shr 2) and 1) - ((a shr 2) and 1)) * t)
                n++
            }
            val px = ox + (i + sx / n) * cell; val py = oy + (j + sy / n) * cell; val pz = oz + (k + sz / n) * cell
            // One step onto the true surface along the gradient, so fine carving is not averaged away.
            val h = cell * 0.25f
            var gx = sculpture.d(px + h, py, pz) - sculpture.d(px - h, py, pz)
            var gy = sculpture.d(px, py + h, pz) - sculpture.d(px, py - h, pz)
            var gz = sculpture.d(px, py, pz + h) - sculpture.d(px, py, pz - h)
            var gl = sqrt(gx * gx + gy * gy + gz * gz)
            if (gl < 1e-12f) { gx = 0f; gy = 1f; gz = 0f; gl = 1f }
            gx /= gl; gy /= gl; gz /= gl
            val dv = sculpture.eval(px, py, pz, mat)
            val step = dv.coerceIn(-cell * 0.5f, cell * 0.5f)
            vertexOf[(k * (ny - 1) + j) * (nx - 1) + i] = pos.size / 3
            pos += px - gx * step; pos += py - gy * step; pos += pz - gz * step
            nrm += gx; nrm += gy; nrm += gz
            mats += mat[0]
        }

        // A quad across every crossed edge, between the four cells round it.
        val idx = IntBuffer()
        fun cellVertex(i: Int, j: Int, k: Int): Int =
            if (i < 0 || j < 0 || k < 0 || i >= nx - 1 || j >= ny - 1 || k >= nz - 1) -1 else vertexOf[(k * (ny - 1) + j) * (nx - 1) + i]
        fun quad(a: Int, b: Int, cc: Int, d: Int, flip: Boolean) {
            if (a < 0 || b < 0 || cc < 0 || d < 0) return
            if (flip) { idx += a; idx += d; idx += cc; idx += a; idx += cc; idx += b }
            else { idx += a; idx += b; idx += cc; idx += a; idx += cc; idx += d }
        }
        for (k in 1 until nz - 1) for (j in 1 until ny - 1) for (i in 1 until nx - 1) {
            val v0 = field[at(i, j, k)]
            val inside = v0 < 0f
            if (i < nx - 1 && (field[at(i + 1, j, k)] < 0f) != inside) {
                quad(cellVertex(i, j - 1, k - 1), cellVertex(i, j, k - 1), cellVertex(i, j, k), cellVertex(i, j - 1, k), !inside)
            }
            if (j < ny - 1 && (field[at(i, j + 1, k)] < 0f) != inside) {
                quad(cellVertex(i - 1, j, k - 1), cellVertex(i - 1, j, k), cellVertex(i, j, k), cellVertex(i, j, k - 1), !inside)
            }
            if (k < nz - 1 && (field[at(i, j, k + 1)] < 0f) != inside) {
                quad(cellVertex(i - 1, j - 1, k), cellVertex(i, j - 1, k), cellVertex(i, j, k), cellVertex(i - 1, j, k), !inside)
            }
        }
        val positions = pos.toArray(); val normals = nrm.toArray(); val tris = idx.toArray()
        // Wind every triangle outward: check the faces against the field's normals and turn them all if need be.
        var agree = 0; var disagree = 0
        for (t in 0 until tris.size / 3) {
            val a0 = tris[t * 3] * 3; val b0 = tris[t * 3 + 1] * 3; val c0 = tris[t * 3 + 2] * 3
            val ux = positions[b0] - positions[a0]; val uy = positions[b0 + 1] - positions[a0 + 1]; val uz = positions[b0 + 2] - positions[a0 + 2]
            val vx = positions[c0] - positions[a0]; val vy = positions[c0 + 1] - positions[a0 + 1]; val vz = positions[c0 + 2] - positions[a0 + 2]
            val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
            if (fx * normals[a0] + fy * normals[a0 + 1] + fz * normals[a0 + 2] >= 0f) agree++ else disagree++
        }
        if (disagree > agree) for (t in 0 until tris.size / 3) { val tmp = tris[t * 3 + 1]; tris[t * 3 + 1] = tris[t * 3 + 2]; tris[t * 3 + 2] = tmp }
        return Result(positions, normals, mats.toArray(), tris)
    }

    internal class FloatBuffer {
        var data = FloatArray(4096); var size = 0
        operator fun plusAssign(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }

    internal class IntBuffer {
        var data = IntArray(4096); var size = 0
        operator fun plusAssign(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }

    private companion object {
        const val COARSE = 4
        /** The twelve edges of a cell, as corner pairs (corner bit 0 = +x, bit 1 = +y, bit 2 = +z). */
        val EDGES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
    }
}
