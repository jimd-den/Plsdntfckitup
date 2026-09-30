package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A carved mask drawn as a picture, in software: for the maker's preview and
 * for cards, wherever there is no scene to fly it in.
 *
 * It is lit like a carving on a shelf: a warm key light from the upper left
 * that casts shadows (the brow over the eyes, the nose across the cheek), a
 * cool fill, and a rim from behind. Its eyes burn with their own light. It is
 * depth-buffered and drawn at [supersample] times the size, then filtered
 * down.
 */
object MaskPortrait {

    /**
     * [mesh] turned by [yaw] (radians, positive shows its left cheek) and tipped
     * by [pitch] (positive looks down on it). Returns ARGB pixels, [width] by
     * [height], over a gradient from [top] to [bottom].
     */
    fun render(
        mesh: SpiritMesh, width: Int, height: Int, yaw: Float = 0.35f, pitch: Float = 0.18f, glow: Float = 0.8f,
        top: Int = 0xFF2A211B.toInt(), bottom: Int = 0xFF120E0C.toInt(), supersample: Int = 2, margin: Float = 0.08f,
    ): IntArray {
        val ss = supersample.coerceIn(1, 3)
        val w = width * ss; val h = height * ss
        val n = mesh.vertexCount
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        // Into view space: x right, y toward the viewer, z up.
        val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)
        val nx = FloatArray(n); val ny = FloatArray(n); val nz = FloatArray(n)
        fun turn(x: Float, y: Float, z: Float, out: Int, px: FloatArray, py: FloatArray, pz: FloatArray) {
            val x1 = x * cy + y * sy; val y1 = -x * sy + y * cy
            px[out] = x1; py[out] = y1 * cp + z * sp; pz[out] = -y1 * sp + z * cp
        }
        for (i in 0 until n) {
            turn(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2], i, vx, vy, vz)
            turn(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2], i, nx, ny, nz)
        }
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until n) { x0 = min(x0, vx[i]); x1 = max(x1, vx[i]); z0 = min(z0, vz[i]); z1 = max(z1, vz[i]) }
        val span = max((x1 - x0) / w, (z1 - z0) / h) / (1f - 2f * margin)
        val scale = 1f / max(span, 1e-6f)
        val ox = w / 2f - (x0 + x1) / 2f * scale; val oz = h / 2f + (z0 + z1) / 2f * scale

        // Lit colour at each vertex.
        val key = norm(-0.55f, 0.75f, 0.62f); val fill = norm(0.7f, 0.5f, -0.1f)
        val lit = shadowing(mesh, vx, vy, vz, key)
        val lr = FloatArray(n); val lg = FloatArray(n); val lb = FloatArray(n)
        for (i in 0 until n) {
            val c = mesh.colors[i]
            val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
            val kd = max(0f, nx[i] * key[0] + ny[i] * key[1] + nz[i] * key[2]) * lit[i]
            val fd = max(0f, nx[i] * fill[0] + ny[i] * fill[1] + nz[i] * fill[2])
            val sky = 0.5f + 0.5f * nz[i]
            val rim = (1f - max(0f, ny[i])).pow(3f) * 0.35f
            // A soft sheen on polished wood, from the key light.
            val hx = key[0]; val hy = key[1] + 1f; val hz = key[2]; val hl = sqrt(hx * hx + hy * hy + hz * hz)
            val spec = max(0f, (nx[i] * hx + ny[i] * hy + nz[i] * hz) / hl).pow(22f) * 0.22f * lit[i]
            var er = r * (0.2f + 0.16f * sky + 1.05f * kd * 1.0f + 0.25f * fd * 0.8f) + spec + rim * 0.9f
            var eg = g * (0.2f + 0.16f * sky + 1.05f * kd * 0.93f + 0.25f * fd * 0.9f) + spec * 0.95f + rim * 0.75f
            var eb = b * (0.22f + 0.2f * sky + 1.05f * kd * 0.82f + 0.25f * fd * 1.1f) + spec * 0.85f + rim * 0.6f
            val ch = mesh.channels[i]
            if (ch != GlowChannel.NONE) {
                val gc = mesh.glowColors[i]
                val k = glow * when (ch) { GlowChannel.EYES -> 1.4f; GlowChannel.LINES -> 0.25f; else -> 0.35f }
                er += ((gc shr 16) and 255) / 255f * k; eg += ((gc shr 8) and 255) / 255f * k; eb += (gc and 255) / 255f * k
            }
            lr[i] = er; lg[i] = eg; lb[i] = eb
        }

        val depth = FloatArray(w * h) { -Float.MAX_VALUE }
        val cr = FloatArray(w * h); val cg = FloatArray(w * h); val cb = FloatArray(w * h)
        val sx = FloatArray(n) { ox + vx[it] * scale }; val sz = FloatArray(n) { oz - vz[it] * scale }
        val idx = mesh.indices
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val ax = sx[a]; val ay = sz[a]; val bx = sx[b]; val by = sz[b]; val cx = sx[c]; val cyy = sz[c]
            val area = (bx - ax) * (cyy - ay) - (by - ay) * (cx - ax)
            if (area == 0f) continue
            val minX = max(0, min(ax, min(bx, cx)).toInt()); val maxX = min(w - 1, max(ax, max(bx, cx)).toInt() + 1)
            val minY = max(0, min(ay, min(by, cyy)).toInt()); val maxY = min(h - 1, max(ay, max(by, cyy)).toInt() + 1)
            if (minX > maxX || minY > maxY) continue
            val inv = 1f / area
            for (py in minY..maxY) {
                val fy = py + 0.5f
                for (px in minX..maxX) {
                    val fx = px + 0.5f
                    val w0 = ((bx - fx) * (cyy - fy) - (by - fy) * (cx - fx)) * inv
                    val w1 = ((cx - fx) * (ay - fy) - (cyy - fy) * (ax - fx)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val d = w0 * vy[a] + w1 * vy[b] + w2 * vy[c]
                    val o = py * w + px
                    if (d <= depth[o]) continue
                    depth[o] = d
                    cr[o] = w0 * lr[a] + w1 * lr[b] + w2 * lr[c]
                    cg[o] = w0 * lg[a] + w1 * lg[b] + w2 * lg[c]
                    cb[o] = w0 * lb[a] + w1 * lb[b] + w2 * lb[c]
                }
            }
        }

        // Down to size over the backdrop, with a gentle tone curve.
        val out = IntArray(width * height)
        val tr = ((top shr 16) and 255) / 255f; val tg = ((top shr 8) and 255) / 255f; val tb = (top and 255) / 255f
        val br = ((bottom shr 16) and 255) / 255f; val bg = ((bottom shr 8) and 255) / 255f; val bb = (bottom and 255) / 255f
        val inv = 1f / (ss * ss)
        for (y in 0 until height) {
            val v = y / (height - 1f).coerceAtLeast(1f)
            for (x in 0 until width) {
                val u = x / (width - 1f).coerceAtLeast(1f) - 0.5f
                val halo = 1f + 0.25f * (1f - min(1f, sqrt(u * u * 2.2f + (v - 0.45f).let { it * it } * 1.6f) * 1.6f))
                val bgR = (tr + (br - tr) * v) * halo; val bgG = (tg + (bg - tg) * v) * halo; val bgB = (tb + (bb - tb) * v) * halo
                var r = 0f; var g = 0f; var b = 0f
                for (j in 0 until ss) for (i in 0 until ss) {
                    val o = (y * ss + j) * w + x * ss + i
                    if (depth[o] == -Float.MAX_VALUE) { r += bgR; g += bgG; b += bgB }
                    else { r += tone(cr[o]); g += tone(cg[o]); b += tone(cb[o]) }
                }
                out[y * width + x] = (0xFF shl 24) or (ch(r * inv) shl 16) or (ch(g * inv) shl 8) or ch(b * inv)
            }
        }
        return out
    }

    /**
     * How much of the key light reaches each vertex, 0 to 1: a depth map drawn
     * from the light, read back softly over its neighbours.
     */
    private fun shadowing(mesh: SpiritMesh, vx: FloatArray, vy: FloatArray, vz: FloatArray, light: FloatArray): FloatArray {
        val n = vx.size
        // The light's own frame: looking along -light, with a and b across it.
        var ax = -light[2]; var ay = 0f; var az = light[0]
        run { val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f); ax /= l; ay /= l; az /= l }
        val bx = light[1] * az - light[2] * ay; val by = light[2] * ax - light[0] * az; val bz = light[0] * ay - light[1] * ax
        val la = FloatArray(n); val lb = FloatArray(n); val ld = FloatArray(n)
        var a0 = Float.MAX_VALUE; var a1 = -Float.MAX_VALUE; var b0 = Float.MAX_VALUE; var b1 = -Float.MAX_VALUE; var d0 = Float.MAX_VALUE; var d1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            la[i] = vx[i] * ax + vy[i] * ay + vz[i] * az; lb[i] = vx[i] * bx + vy[i] * by + vz[i] * bz
            ld[i] = vx[i] * light[0] + vy[i] * light[1] + vz[i] * light[2]
            a0 = min(a0, la[i]); a1 = max(a1, la[i]); b0 = min(b0, lb[i]); b1 = max(b1, lb[i]); d0 = min(d0, ld[i]); d1 = max(d1, ld[i])
        }
        val size = SHADOW_MAP
        val span = max(a1 - a0, b1 - b0).coerceAtLeast(1e-6f)
        val k = (size - 3) / span
        val sa = FloatArray(n) { (la[it] - a0) * k + 1f }; val sb = FloatArray(n) { (lb[it] - b0) * k + 1f }
        val near = FloatArray(size * size) { -Float.MAX_VALUE }
        val idx = mesh.indices
        for (t in 0 until idx.size / 3) {
            val p = idx[t * 3]; val q = idx[t * 3 + 1]; val r = idx[t * 3 + 2]
            val area = (sa[q] - sa[p]) * (sb[r] - sb[p]) - (sb[q] - sb[p]) * (sa[r] - sa[p])
            if (area == 0f) continue
            val x0 = max(0, min(sa[p], min(sa[q], sa[r])).toInt()); val x1 = min(size - 1, max(sa[p], max(sa[q], sa[r])).toInt() + 1)
            val y0 = max(0, min(sb[p], min(sb[q], sb[r])).toInt()); val y1 = min(size - 1, max(sb[p], max(sb[q], sb[r])).toInt() + 1)
            val inv = 1f / area
            for (y in y0..y1) for (x in x0..x1) {
                val fx = x + 0.5f; val fy = y + 0.5f
                val w0 = ((sa[q] - fx) * (sb[r] - fy) - (sb[q] - fy) * (sa[r] - fx)) * inv
                val w1 = ((sa[r] - fx) * (sb[p] - fy) - (sb[r] - fy) * (sa[p] - fx)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val d = w0 * ld[p] + w1 * ld[q] + w2 * ld[r]
                val o = y * size + x
                if (d > near[o]) near[o] = d
            }
        }
        // Lit where nothing stands nearer the light, a little slack for the surface itself; softened over a 5x5 patch.
        val bias = (d1 - d0) * 0.012f + 2.2f / k
        return FloatArray(n) { i ->
            val cx = sa[i].toInt(); val cy = sb[i].toInt()
            var sum = 0f; var cnt = 0
            for (dy in -2..2) for (dx in -2..2) {
                val x = (cx + dx).coerceIn(0, size - 1); val y = (cy + dy).coerceIn(0, size - 1)
                val m = near[y * size + x]
                sum += if (m == -Float.MAX_VALUE || ld[i] >= m - bias) 1f else 0f; cnt++
            }
            0.25f + 0.75f * (sum / cnt)
        }
    }

    private const val SHADOW_MAP = 512

    private fun tone(v: Float): Float = v / (1f + 0.35f * v) * 1.2f

    private fun ch(v: Float): Int = (v.coerceIn(0f, 1f).pow(1f / 1.1f) * 255f + 0.5f).toInt()

    private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
}
