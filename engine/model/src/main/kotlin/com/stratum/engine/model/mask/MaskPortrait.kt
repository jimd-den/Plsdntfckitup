package com.stratum.engine.model.mask

import com.stratum.engine.scene.SpiritFeatures
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mask drawn for a flat screen with no 3D scene: the head and its floating
 * pieces turned, lit and sorted back to front into plain coloured triangles,
 * for a canvas to fill in order (Android's drawVertices, or any 2D painter).
 *
 * It is what the mask maker's live preview draws every frame: a few thousand
 * triangles, turned and sorted in a millisecond or two on a phone. Back faces
 * of the head are dropped, painted layers go over the head, and pieces
 * behind it (a halo) go under it. The head is lit with a soft key light and a
 * glossy highlight; the pieces keep most of their drawn colour, as in play.
 *
 * Not thread-safe: one per view. [draw]'s frame is reused by the next call.
 */
class MaskPortrait {
    /** Triangles to fill in order: x, y per corner, and a colour per corner. */
    class Frame internal constructor() {
        var xy = FloatArray(0); internal set
        var colors = IntArray(0); internal set
        var triangles = 0; internal set
    }

    private val frame = Frame()
    private var px = FloatArray(0); private var py = FloatArray(0); private var pd = FloatArray(0); private var pc = IntArray(0)
    private var keys = LongArray(0)

    /**
     * [head] (with its painted layers after its first [solidTriangles]) and
     * [features], turned by [yaw] radians -- the pieces by [featureFollow] of
     * it, as they lag in play -- fitted to a [width] x [height] canvas.
     */
    fun draw(
        head: SpiritMesh, features: SpiritFeatures?, yaw: Float, width: Float, height: Float,
        solidTriangles: Int = AfricanMaskArt.HEAD_TRIANGLES, featureFollow: Float = 0.7f, zoom: Float = 1f,
    ): Frame {
        val scale = min(width / 1.5f, height / 1.9f) * zoom
        val cx = width / 2f; val cy = height * 0.47f
        val headVerts = head.vertexCount
        val featVerts = features?.vertexCount ?: 0
        ensureVerts(headVerts + featVerts)
        light(head.positions, head.normals, head.colors, null, 0, headVerts, yaw, scale, cx, cy, glossy = true)
        if (features != null) light(features.positions, features.normals, features.colors, features.glow, headVerts, featVerts, yaw * featureFollow, scale, cx, cy, glossy = false)

        val headTris = head.indices.size / 3
        val featTris = (features?.indices?.size ?: 0) / 3
        ensureTris(headTris + featTris)
        frame.triangles = 0
        // Behind the head: pieces hung behind it, like a halo.
        if (features != null) pass(features.indices, headVerts, 0, featTris, cull = false) { fy -> features.positions[fy * 3 + 1] < -0.02f }
        pass(head.indices, 0, 0, min(solidTriangles, headTris), cull = true, normals = head.normals, yaw = yaw)
        pass(head.indices, 0, min(solidTriangles, headTris), headTris, cull = true, normals = head.normals, yaw = yaw)
        if (features != null) pass(features.indices, headVerts, 0, featTris, cull = false) { fy -> features.positions[fy * 3 + 1] >= -0.02f }
        return frame
    }

    private fun light(
        pos: FloatArray, nrm: FloatArray, col: IntArray, glow: FloatArray?, first: Int, count: Int,
        yaw: Float, scale: Float, cx: Float, cy: Float, glossy: Boolean,
    ) {
        val c = cos(yaw); val s = sin(yaw)
        for (i in 0 until count) {
            val o = i * 3
            val x = pos[o]; val y = pos[o + 1]; val z = pos[o + 2]
            val rx = x * c - y * s; val ry = x * s + y * c
            val k = first + i
            px[k] = cx - rx * scale; py[k] = cy - z * scale; pd[k] = ry
            val nx = nrm[o] * c - nrm[o + 1] * s; val ny = nrm[o] * s + nrm[o + 1] * c; val nz = nrm[o + 2]
            val lambert = max(0f, nx * LX + ny * LY + nz * LZ)
            val argb = col[i]
            var r = ((argb shr 16) and 255) / 255f; var g = ((argb shr 8) and 255) / 255f; var b = (argb and 255) / 255f
            if (glossy) {
                val shade = AMBIENT + DIFFUSE * lambert
                val spec = SPECULAR * max(0f, nx * HX + ny * HY + nz * HZ).pow(SHINE)
                val rim = RIM * (1f - max(0f, ny)).pow(3f)
                r = r * shade + spec + rim * 0.7f; g = g * shade + spec + rim * 0.75f; b = b * shade + spec + rim
            } else {
                val shade = 0.8f + 0.25f * lambert
                val lit = (glow?.get(i) ?: 0f).coerceIn(0f, 1.5f) * 0.25f
                r = r * shade + lit; g = g * shade + lit * 0.9f; b = b * shade + lit * 0.6f
            }
            pc[k] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }

    private inline fun pass(
        idx: IntArray, base: Int, from: Int, until: Int, cull: Boolean,
        normals: FloatArray? = null, yaw: Float = 0f, crossinline keep: (Int) -> Boolean = { true },
    ) {
        val c = cos(yaw); val s = sin(yaw)
        var n = 0
        for (t in from until until) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val d = idx[t * 3 + 2]
            if (!keep(a)) continue
            if (cull && normals != null) {
                // Facing away from the eye: its corners' normals, turned, point back.
                val ny = (normals[a * 3] + normals[b * 3] + normals[d * 3]) * s + (normals[a * 3 + 1] + normals[b * 3 + 1] + normals[d * 3 + 1]) * c
                if (ny < -0.06f) continue
            }
            val depth = (pd[base + a] + pd[base + b] + pd[base + d]) / 3f
            var bits = java.lang.Float.floatToRawIntBits(depth)
            if (bits < 0) bits = bits xor 0x7FFFFFFF
            keys[n++] = (bits.toLong() shl 32) or t.toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        val xy = frame.xy; val colors = frame.colors
        var out = frame.triangles
        for (i in 0 until n) {
            val t = (keys[i] and 0xFFFFFFFFL).toInt()
            for (corner in 0 until 3) {
                val v = base + idx[t * 3 + corner]
                val o = out * 3 + corner
                xy[o * 2] = px[v]; xy[o * 2 + 1] = py[v]; colors[o] = pc[v]
            }
            out++
        }
        frame.triangles = out
    }

    private fun ensureVerts(n: Int) {
        if (px.size >= n) return
        px = FloatArray(n); py = FloatArray(n); pd = FloatArray(n); pc = IntArray(n)
    }

    private fun ensureTris(n: Int) {
        if (keys.size < n) keys = LongArray(n)
        if (frame.colors.size < n * 3) { frame.xy = FloatArray(n * 6); frame.colors = IntArray(n * 3) }
    }

    private fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private companion object {
        // The key light: from the upper left, in front. Screen right is -x, the eye looks down -y.
        val L = normal(0.45f, 0.62f, 0.64f)
        val LX = L[0]; val LY = L[1]; val LZ = L[2]
        val H = normal(L[0], L[1] + 1f, L[2])
        val HX = H[0]; val HY = H[1]; val HZ = H[2]
        const val AMBIENT = 0.52f
        const val DIFFUSE = 0.6f
        const val SPECULAR = 0.42f
        const val SHINE = 38f
        const val RIM = 0.16f

        fun normal(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
    }
}
