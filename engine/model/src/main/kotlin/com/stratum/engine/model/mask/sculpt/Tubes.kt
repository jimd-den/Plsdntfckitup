package com.stratum.engine.model.mask.sculpt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Swept tubes: horns, raffia strands, braids, anything long and curved.
 * A centreline through control points (a Catmull-Rom curve), a radius and
 * a colour along it, and rings of vertices swept down it with smooth
 * normals. Cleaner than any distance field at the same cost for thin,
 * sweeping forms.
 */
internal class TubeMesh {
    val pos = SurfaceNets.FloatBuffer(); val nrm = SurfaceNets.FloatBuffer(); val col = SurfaceNets.IntBuffer()
    val glow = SurfaceNets.IntBuffer(); val idx = SurfaceNets.IntBuffer()

    /**
     * Per vertex, which primitive (sweep, ball or box) it belongs to, and the
     * [group] that was current when it was made: a broken mask keeps each
     * primitive whole, and sends horns and raffia off as pieces of their own.
     */
    val prims = SurfaceNets.IntBuffer(); val groups = SurfaceNets.IntBuffer()

    /** The group the next primitives are made in. */
    var group = 0
    private var prim = -1

    val vertexCount: Int get() = pos.size / 3

    private fun begin() { prim++ }

    private fun tag(count: Int) { repeat(count) { prims += prim; groups += group } }

    /**
     * A tube along the curve through [points] (x, y, z triples), [rings]
     * rings of [sides] vertices; [radius], [colour] and [channel] are
     * functions of t from 0 (start) to 1 (tip).
     */
    fun sweep(points: FloatArray, rings: Int, sides: Int, radius: (Float) -> Float, colour: (Float) -> Int, channel: Int = 0, capEnd: Boolean = true) {
        begin()
        val before = vertexCount
        val n = points.size / 3
        val cx = FloatArray(rings); val cy = FloatArray(rings); val cz = FloatArray(rings)
        for (i in 0 until rings) {
            val t = i / (rings - 1f) * (n - 1)
            val seg = t.toInt().coerceAtMost(n - 2); val u = t - seg
            fun p(k: Int, a: Int) = points[k.coerceIn(0, n - 1) * 3 + a]
            for (a in 0..2) {
                val p0 = p(seg - 1, a); val p1 = p(seg, a); val p2 = p(seg + 1, a); val p3 = p(seg + 2, a)
                val v = 0.5f * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u)
                when (a) { 0 -> cx[i] = v; 1 -> cy[i] = v; else -> cz[i] = v }
            }
        }
        // A frame carried down the curve without twisting.
        var nx = 0f; var ny = 0f; var nz = 1f
        val first = vertexCount
        for (i in 0 until rings) {
            val j = (i + 1).coerceAtMost(rings - 1); val k = (i - 1).coerceAtLeast(0)
            var tx = cx[j] - cx[k]; var ty = cy[j] - cy[k]; var tz = cz[j] - cz[k]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= tl; ty /= tl; tz /= tl
            // Keep the reference normal perpendicular to the tangent.
            val dot = nx * tx + ny * ty + nz * tz
            nx -= dot * tx; ny -= dot * ty; nz -= dot * tz
            var nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-4f) { nx = if (kotlin.math.abs(tx) < 0.9f) 1f else 0f; ny = if (nx == 0f) 1f else 0f; nz = 0f; val d2 = nx * tx + ny * ty + nz * tz; nx -= d2 * tx; ny -= d2 * ty; nz -= d2 * tz; nl = sqrt(nx * nx + ny * ny + nz * nz) }
            nx /= nl; ny /= nl; nz /= nl
            val bx = ty * nz - tz * ny; val by = tz * nx - tx * nz; val bz = tx * ny - ty * nx
            val t = i / (rings - 1f)
            val r = radius(t); val c = colour(t)
            for (s in 0 until sides) {
                val a = 2f * PI.toFloat() * s / sides
                val ca = cos(a); val sa = sin(a)
                val ox = nx * ca + bx * sa; val oy = ny * ca + by * sa; val oz = nz * ca + bz * sa
                pos += cx[i] + ox * r; pos += cy[i] + oy * r; pos += cz[i] + oz * r
                nrm += ox; nrm += oy; nrm += oz
                col += c; glow += channel
            }
        }
        for (i in 0 until rings - 1) for (s in 0 until sides) {
            val a = first + i * sides + s; val b = first + i * sides + (s + 1) % sides
            val c = a + sides; val d = b + sides
            idx += a; idx += c; idx += b; idx += b; idx += c; idx += d
        }
        if (capEnd) {
            val tipIndex = vertexCount
            val last = rings - 1
            pos += cx[last]; pos += cy[last]; pos += cz[last]
            var tx = cx[last] - cx[last - 1]; var ty = cy[last] - cy[last - 1]; var tz = cz[last] - cz[last - 1]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= tl; ty /= tl; tz /= tl
            nrm += tx; nrm += ty; nrm += tz
            col += colour(1f); glow += channel
            for (s in 0 until sides) { idx += first + last * sides + s; idx += tipIndex; idx += first + last * sides + (s + 1) % sides }
        }
        tag(vertexCount - before)
    }

    /** A little sphere: a bead, a stud, a knob. */
    fun ball(x: Float, y: Float, z: Float, r: Float, colour: Int, channel: Int = 0, detail: Int = 8) = ellipsoid(x, y, z, r, r, r, colour, channel, detail)

    /** An ellipsoid of semi-axes [rx], [ry], [rz]: a cowrie, a flattened bead. */
    fun ellipsoid(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, colour: Int, channel: Int = 0, detail: Int = 8) {
        begin()
        val first = vertexCount
        val rings = detail; val sides = detail * 2
        for (i in 0..rings) {
            val th = PI.toFloat() * i / rings
            for (s in 0 until sides) {
                val ph = 2f * PI.toFloat() * s / sides
                val ox = sin(th) * cos(ph); val oy = sin(th) * sin(ph); val oz = cos(th)
                pos += x + ox * rx; pos += y + oy * ry; pos += z + oz * rz
                // The normal of an ellipsoid: the sphere's, stretched the other way.
                val gx = ox / rx; val gy = oy / ry; val gz = oz / rz
                val gl = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6f)
                nrm += gx / gl; nrm += gy / gl; nrm += gz / gl
                col += colour; glow += channel
            }
        }
        for (i in 0 until rings) for (s in 0 until sides) {
            val a = first + i * sides + s; val b = first + i * sides + (s + 1) % sides
            val c = a + sides; val d = b + sides
            idx += a; idx += b; idx += c; idx += b; idx += d; idx += c
        }
        tag(vertexCount - first)
    }

    /**
     * A box of half-sizes [hx], [hy], [hz] about (x, y, z), turned by [yaw]
     * about z: a plaque, a bar of a kanaga. Each face has its own vertices,
     * so its edges stay sharp.
     */
    fun box(x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float, colour: Int, channel: Int = 0, yaw: Float = 0f) {
        begin()
        val first = vertexCount
        val c = cos(yaw); val s = sin(yaw)
        for (f in 0 until 6) {
            val axis = f / 2; val sign = if (f % 2 == 0) 1f else -1f
            // The face's normal and the two directions across it, in the box's frame.
            val n = FloatArray(3).also { it[axis] = sign }
            val u = FloatArray(3).also { it[(axis + 1) % 3] = 1f }
            val v = FloatArray(3).also { it[(axis + 2) % 3] = 1f }
            val h = floatArrayOf(hx, hy, hz)
            for (k in 0 until 4) {
                val su = if (k == 1 || k == 2) 1f else -1f; val sv = if (k >= 2) 1f else -1f
                val lx = n[0] * h[0] + u[0] * su * h[0] + v[0] * sv * h[0]
                val ly = n[1] * h[1] + u[1] * su * h[1] + v[1] * sv * h[1]
                val lz = n[2] * h[2] + u[2] * su * h[2] + v[2] * sv * h[2]
                pos += x + lx * c - ly * s; pos += y + lx * s + ly * c; pos += z + lz
                nrm += n[0] * c - n[1] * s; nrm += n[0] * s + n[1] * c; nrm += n[2]
                col += colour; glow += channel
            }
            val o = first + f * 4
            // Wound so the face looks out along its normal.
            if (sign > 0f) { idx += o; idx += o + 1; idx += o + 2; idx += o; idx += o + 2; idx += o + 3 }
            else { idx += o; idx += o + 2; idx += o + 1; idx += o; idx += o + 3; idx += o + 2 }
        }
        tag(24)
    }

    companion object {
        /** A curve as a flat list of points. */
        fun curve(vararg p: Float): FloatArray = p

        fun lerp(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(c: Int, s: Int) = (c shr s) and 255
            fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }

        fun ridge(t: Float, frequency: Float, depth: Float): Float = 1f + depth * max(0f, sin(t * frequency * 2f * PI.toFloat())).let { it * it }
    }
}
