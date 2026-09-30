package com.stratum.engine.model.mask.sculpt

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a [Sculpture] into triangles, keeping its edges sharp: one vertex in
 * every grid cell the surface passes through and a quad across every grid edge
 * it crosses (watertight, without marching cubes' ambiguous cases).
 *
 * Each vertex is placed as in dual contouring. Where the surface crosses the
 * cell's edges, and which way it faces there, says where its planes meet: the
 * vertex goes to the point nearest all of them, so a ridge, a chisel facet or
 * a cut's lip lands on its edge rather than rounded over it.
 *
 * Normals are then split along creases sharper than [crease] degrees, so cut
 * planes shade as planes, as carved wood does.
 *
 * Only a narrow band is sampled finely: a coarse pass finds where the
 * surface is, and the fine grid is filled only in coarse cells near it, so
 * a detailed carving costs its surface, not its volume.
 *
 * Normals come from the field's gradient, so shading is smooth across the
 * facets; each vertex also knows which part's surface it is on.
 */
class SurfaceNets(private val sculpture: Sculpture, private val cell: Float, private val crease: Float = 38f) {

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
            // Hermite data: where the surface crosses the cell's edges, and its normal there.
            var mx = 0f; var my = 0f; var mz = 0f; var n = 0
            var a00 = 0f; var a01 = 0f; var a02 = 0f; var a11 = 0f; var a12 = 0f; var a22 = 0f
            var b0 = 0f; var b1 = 0f; var b2 = 0f
            val bx = ox + i * cell; val by = oy + j * cell; val bz = oz + k * cell
            for (e in EDGES.indices step 2) {
                val a = EDGES[e]; val bq = EDGES[e + 1]
                val da = corner[a]; val db = corner[bq]
                if ((da < 0f) == (db < 0f)) continue
                val t = da / (da - db)
                val px = bx + ((a and 1) + ((bq and 1) - (a and 1)) * t) * cell
                val py = by + (((a shr 1) and 1) + (((bq shr 1) and 1) - ((a shr 1) and 1)) * t) * cell
                val pz = bz + (((a shr 2) and 1) + (((bq shr 2) and 1) - ((a shr 2) and 1)) * t) * cell
                gradient(px, py, pz, grad)
                val gx = grad[0]; val gy = grad[1]; val gz = grad[2]
                val w = gx * px + gy * py + gz * pz
                a00 += gx * gx; a01 += gx * gy; a02 += gx * gz; a11 += gy * gy; a12 += gy * gz; a22 += gz * gz
                b0 += gx * w; b1 += gy * w; b2 += gz * w
                mx += px; my += py; mz += pz; n++
            }
            mx /= n; my /= n; mz /= n
            // The point nearest every crossing plane, drawn gently toward their middle where they don't pin it down.
            val lambda = 0.05f
            val m00 = a00 + lambda; val m11 = a11 + lambda; val m22 = a22 + lambda
            val r0 = b0 + lambda * mx; val r1 = b1 + lambda * my; val r2 = b2 + lambda * mz
            val det = m00 * (m11 * m22 - a12 * a12) - a01 * (a01 * m22 - a12 * a02) + a02 * (a01 * a12 - m11 * a02)
            var vx = mx; var vy = my; var vz = mz
            if (abs(det) > 1e-9f) {
                val sx = (r0 * (m11 * m22 - a12 * a12) - a01 * (r1 * m22 - a12 * r2) + a02 * (r1 * a12 - m11 * r2)) / det
                val sy = (m00 * (r1 * m22 - a12 * r2) - r0 * (a01 * m22 - a12 * a02) + a02 * (a01 * r2 - r1 * a02)) / det
                val sz = (m00 * (m11 * r2 - r1 * a12) - a01 * (a01 * r2 - r1 * a02) + r0 * (a01 * a12 - m11 * a02)) / det
                // Kept within its cell, and a little over: a ridge's point sits on it even when it falls just outside.
                val slack = cell * 0.35f
                if (sx.isFinite() && sy.isFinite() && sz.isFinite()) {
                    vx = sx.coerceIn(bx - slack, bx + cell + slack); vy = sy.coerceIn(by - slack, by + cell + slack); vz = sz.coerceIn(bz - slack, bz + cell + slack)
                }
            }
            sculpture.eval(vx, vy, vz, mat)
            gradient(vx, vy, vz, grad)
            vertexOf[(k * (ny - 1) + j) * (nx - 1) + i] = pos.size / 3
            pos += vx; pos += vy; pos += vz
            nrm += grad[0]; nrm += grad[1]; nrm += grad[2]
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
        return splitCreases(positions, normals, mats.toArray(), tris)
    }

    private val grad = FloatArray(3)

    /** The field's unit gradient at a point, from four samples on a tetrahedron. */
    private fun gradient(x: Float, y: Float, z: Float, out: FloatArray) {
        val h = cell * 0.12f
        val d1 = sculpture.d(x + h, y - h, z - h); val d2 = sculpture.d(x - h, y - h, z + h)
        val d3 = sculpture.d(x - h, y + h, z - h); val d4 = sculpture.d(x + h, y + h, z + h)
        var gx = d1 - d2 - d3 + d4; var gy = -d1 - d2 + d3 + d4; var gz = -d1 + d2 - d3 + d4
        val l = sqrt(gx * gx + gy * gy + gz * gz)
        if (l < 1e-12f) { gx = 0f; gy = 1f; gz = 0f } else { gx /= l; gy /= l; gz /= l }
        out[0] = gx; out[1] = gy; out[2] = gz
    }

    /**
     * Normals from the faces round each vertex, but only those within [crease]
     * of each other: where planes meet at a sharper angle the vertex is split,
     * one copy per side, so the edge shades sharp.
     */
    private fun splitCreases(positions: FloatArray, normals: FloatArray, materials: IntArray, tris: IntArray): Result {
        val nv = positions.size / 3; val nt = tris.size / 3
        val fn = FloatArray(nt * 3)
        for (t in 0 until nt) {
            val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
            val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
            fn[t * 3] = uy * vz - uz * vy; fn[t * 3 + 1] = uz * vx - ux * vz; fn[t * 3 + 2] = ux * vy - uy * vx
        }
        // The faces round each vertex.
        val start = IntArray(nv + 1)
        for (x in tris) start[x + 1]++
        for (v in 0 until nv) start[v + 1] += start[v]
        val fill = start.copyOf(nv); val around = IntArray(tris.size)
        for (t in 0 until nt) for (j in 0..2) { val v = tris[t * 3 + j]; around[fill[v]++] = t }
        val cosCrease = kotlin.math.cos(Math.toRadians(crease.toDouble())).toFloat()
        fun unit(t: Int, out: FloatArray) {
            val x = fn[t * 3]; val y = fn[t * 3 + 1]; val z = fn[t * 3 + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l < 1e-20f) { out[0] = 0f; out[1] = 0f; out[2] = 0f } else { out[0] = x / l; out[1] = y / l; out[2] = z / l }
        }
        val pos = FloatBuffer(); val nrm = FloatBuffer(); val mats = IntBuffer()
        val first = IntArray(nv) { -1 }; val next = IntBuffer()
        val out = IntArray(tris.size)
        val ft = FloatArray(3); val fo = FloatArray(3)
        for (t in 0 until nt) {
            unit(t, ft)
            for (j in 0..2) {
                val v = tris[t * 3 + j]
                var sx = 0f; var sy = 0f; var sz = 0f
                for (q in start[v] until start[v + 1]) {
                    val f = around[q]
                    unit(f, fo)
                    if (fo[0] * ft[0] + fo[1] * ft[1] + fo[2] * ft[2] >= cosCrease) { sx += fn[f * 3]; sy += fn[f * 3 + 1]; sz += fn[f * 3 + 2] }
                }
                var l = sqrt(sx * sx + sy * sy + sz * sz)
                if (l < 1e-20f) { sx = normals[v * 3]; sy = normals[v * 3 + 1]; sz = normals[v * 3 + 2]; l = 1f }
                sx /= l; sy /= l; sz /= l
                // The same vertex with (nearly) this normal, if it has been made already.
                var id = first[v]; var found = -1
                while (id >= 0) {
                    if (nrm.data[id * 3] * sx + nrm.data[id * 3 + 1] * sy + nrm.data[id * 3 + 2] * sz > 0.995f) { found = id; break }
                    id = next.data[id]
                }
                if (found < 0) {
                    found = pos.size / 3
                    pos += positions[v * 3]; pos += positions[v * 3 + 1]; pos += positions[v * 3 + 2]
                    nrm += sx; nrm += sy; nrm += sz
                    mats += materials[v]
                    next += first[v]; first[v] = found
                }
                out[t * 3 + j] = found
            }
        }
        return Result(pos.toArray(), nrm.toArray(), mats.toArray(), out)
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
