package com.stratum.tools.artpreview

import com.stratum.engine.scene.MaterialKind
import com.stratum.engine.scene.Mat4
import com.stratum.engine.scene.MeshBatch
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vertex
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws a [SceneFrame] into an image, on the CPU.
 *
 * This is the build-machine twin of the GLES renderer: the same frame, the same
 * vertex layout, the same [ShadingModel], the same four material kinds in the
 * same order. It exists so the 3D look can be reviewed and regression-tested
 * without a device — and so a change to the lighting is judged by looking at
 * it, not by imagining it.
 *
 * Supersampled and then box-filtered down, because a single sample per pixel
 * on a field of cube edges crawls with stair-steps that the GPU's MSAA would
 * never show, and a preview that looks worse than the game is not a preview.
 */
class SceneRasterizer(
    private val width: Int,
    private val height: Int,
    private val textures: TextureLibrary,
    private val supersample: Int = 2,
    private val shadowSize: Int = 2048,
) {
    private val w = width * supersample
    private val h = height * supersample
    private val color = FloatArray(w * h * 3)
    private val depth = FloatArray(w * h)
    private val shadow = FloatArray(shadowSize * shadowSize)

    fun render(frame: SceneFrame): BufferedImage {
        val terms = ShadingModel.Terms(frame.lighting)
        shadowPass(frame)
        clear(frame)

        val viewProj = frame.camera.viewProjection
        val eye = frame.camera.eye
        val lightColors = Array(frame.lights.size) { i ->
            ShadingModel.rgb(frame.lights[i].color, frame.lights[i].strength)
        }
        val shade = FloatArray(3)

        val surface = Surface(terms, frame, eye.x, eye.y, eye.z, lightColors, shade)
        frame.opaque.forEach { rasterize(it, viewProj, surface) }
        rasterize(frame.cutout, viewProj, surface)
        rasterize(frame.decals, viewProj, surface)
        rasterize(frame.glows, viewProj, surface)

        return resolve(terms)
    }

    private fun clear(frame: SceneFrame) {
        val top = ShadingModel.rgb(frame.lighting.skyTop, 1f)
        val bottom = ShadingModel.rgb(frame.lighting.skyBottom, 1f)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            for (x in 0 until w) {
                val o = (y * w + x) * 3
                // Stored pre-tone-map, so undo the curve the finish will apply.
                for (c in 0 until 3) color[o + c] = untone(top[c] + (bottom[c] - top[c]) * t, frame.lighting.exposure)
            }
        }
        depth.fill(Float.MAX_VALUE)
    }

    private fun untone(v: Float, exposure: Float): Float =
        (-kotlin.math.ln((1f - v.coerceIn(0f, 0.999f)).toDouble()) / (exposure * ShadingModel.TONE_GAIN)).toFloat()

    /** Depth from the sun, for everything that casts: opaque and cut-out geometry. */
    private fun shadowPass(frame: SceneFrame) {
        shadow.fill(Float.MAX_VALUE)
        val m = frame.shadowViewProjection
        val clip = FloatArray(4)
        val sx = FloatArray(3); val sy = FloatArray(3); val sz = FloatArray(3)
        val casters = frame.opaque + frame.cutout
        casters.forEach { batch ->
            val v = batch.vertices
            val idx = batch.indices
            var i = 0
            while (i < idx.size) {
                for (k in 0 until 3) {
                    val o = idx[i + k] * Vertex.STRIDE
                    Mat4.transform(m, v[o], v[o + 1], v[o + 2], clip)
                    sx[k] = (clip[0] / clip[3] * 0.5f + 0.5f) * shadowSize
                    sy[k] = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * shadowSize
                    sz[k] = clip[2] / clip[3] * 0.5f + 0.5f
                }
                fillDepth(sx, sy, sz)
                i += 3
            }
        }
    }

    private fun fillDepth(x: FloatArray, y: FloatArray, z: FloatArray) {
        val minX = max(0, floor(min(x[0], min(x[1], x[2]))).toInt())
        val maxX = min(shadowSize - 1, ceil(max(x[0], max(x[1], x[2]))).toInt())
        val minY = max(0, floor(min(y[0], min(y[1], y[2]))).toInt())
        val maxY = min(shadowSize - 1, ceil(max(y[0], max(y[1], y[2]))).toInt())
        val area = edge(x[0], y[0], x[1], y[1], x[2], y[2])
        if (abs(area) < 1e-6f) return
        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val cx = px + 0.5f; val cy = py + 0.5f
                val w0 = edge(x[1], y[1], x[2], y[2], cx, cy) / area
                val w1 = edge(x[2], y[2], x[0], y[0], cx, cy) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val d = w0 * z[0] + w1 * z[1] + w2 * z[2]
                val o = py * shadowSize + px
                if (d < shadow[o]) shadow[o] = d
            }
        }
    }

    /** 0 in shadow, 1 in sun, soft at the edge (3x3 percentage-closer filter). */
    private fun sunlit(frame: SceneFrame, x: Float, y: Float, z: Float, ndl: Float, clip: FloatArray): Float {
        Mat4.transform(frame.shadowViewProjection, x, y, z, clip)
        val u = (clip[0] / clip[3] * 0.5f + 0.5f) * shadowSize
        val v = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * shadowSize
        val d = clip[2] / clip[3] * 0.5f + 0.5f
        // Slope-scaled bias: grazing faces need more, or they shadow themselves in stripes.
        val bias = SHADOW_BIAS + SHADOW_SLOPE_BIAS * (1f - ndl)
        var lit = 0f
        for (oy in -1..1) for (ox in -1..1) {
            val sx = (u + ox).toInt(); val sy = (v + oy).toInt()
            if (sx < 0 || sy < 0 || sx >= shadowSize || sy >= shadowSize) { lit += 1f; continue }
            if (d - bias <= shadow[sy * shadowSize + sx]) lit += 1f
        }
        return lit / 9f
    }

    private class Surface(
        val terms: ShadingModel.Terms,
        val frame: SceneFrame,
        val eyeX: Float, val eyeY: Float, val eyeZ: Float,
        val lightColors: Array<FloatArray>,
        val out: FloatArray,
    )

    /** Screen-space triangles with perspective-correct attributes. */
    private fun rasterize(batch: MeshBatch, viewProj: FloatArray, s: Surface) {
        val v = batch.vertices
        val idx = batch.indices
        val clip = FloatArray(4)
        val sx = FloatArray(3); val sy = FloatArray(3); val sz = FloatArray(3); val iw = FloatArray(3)
        val attr = Array(3) { FloatArray(Vertex.STRIDE) }
        val scratch = FloatArray(4)
        var i = 0
        while (i < idx.size) {
            var behind = false
            for (k in 0 until 3) {
                val o = idx[i + k] * Vertex.STRIDE
                System.arraycopy(v, o, attr[k], 0, Vertex.STRIDE)
                Mat4.transform(viewProj, v[o], v[o + 1], v[o + 2], clip)
                if (clip[3] < NEAR_W) behind = true
                iw[k] = 1f / clip[3]
                sx[k] = (clip[0] * iw[k] * 0.5f + 0.5f) * w
                sy[k] = (1f - (clip[1] * iw[k] * 0.5f + 0.5f)) * h
                sz[k] = clip[2] * iw[k]
            }
            i += 3
            if (behind) continue
            triangle(batch.kind, sx, sy, sz, iw, attr, s, scratch)
        }
    }

    private fun triangle(
        kind: MaterialKind,
        x: FloatArray, y: FloatArray, z: FloatArray, iw: FloatArray,
        a: Array<FloatArray>, s: Surface, clip: FloatArray,
    ) {
        val area = edge(x[0], y[0], x[1], y[1], x[2], y[2])
        if (abs(area) < 1e-6f) return
        val minX = max(0, floor(min(x[0], min(x[1], x[2]))).toInt())
        val maxX = min(w - 1, ceil(max(x[0], max(x[1], x[2]))).toInt())
        val minY = max(0, floor(min(y[0], min(y[1], y[2]))).toInt())
        val maxY = min(h - 1, ceil(max(y[0], max(y[1], y[2]))).toInt())
        if (minX > maxX || minY > maxY) return
        val layer = a[0][Vertex.LAYER]
        val texture = if (layer >= 0f) textures.textureAt(layer.toInt()) else null
        val writesDepth = kind == MaterialKind.OPAQUE || kind == MaterialKind.CUTOUT
        val p = FloatArray(Vertex.STRIDE)
        val detile = FloatArray(4)

        for (py in minY..maxY) {
            for (px in minX..maxX) {
                val cx = px + 0.5f; val cy = py + 0.5f
                var w0 = edge(x[1], y[1], x[2], y[2], cx, cy) / area
                var w1 = edge(x[2], y[2], x[0], y[0], cx, cy) / area
                var w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val d = w0 * z[0] + w1 * z[1] + w2 * z[2]
                val o = py * w + px
                if (d >= depth[o]) continue
                // Perspective-correct weights.
                val pw0 = w0 * iw[0]; val pw1 = w1 * iw[1]; val pw2 = w2 * iw[2]
                val sum = pw0 + pw1 + pw2
                w0 = pw0 / sum; w1 = pw1 / sum; w2 = pw2 / sum
                for (k in 0 until Vertex.STRIDE) p[k] = a[0][k] * w0 + a[1][k] * w1 + a[2][k] * w2

                when (kind) {
                    MaterialKind.DECAL -> { blendDecal(o, p); continue }
                    MaterialKind.GLOW -> { addGlow(o, p); continue }
                    else -> Unit
                }

                if (kind == MaterialKind.CUTOUT && p[Vertex.AO] < 0.999f) {
                    // Screen-door fade, in output pixels so the pattern does
                    // not vanish into the supersample average.
                    val threshold = Vertex.DITHER[((py / supersample) and 3) * 4 + ((px / supersample) and 3)]
                    if (p[Vertex.AO] <= threshold) continue
                }
                var ar = p[Vertex.R]; var ag = p[Vertex.R + 1]; var ab = p[Vertex.R + 2]
                if (texture != null) {
                    val texel = sample(texture, p[Vertex.U], p[Vertex.V], clampEdges = kind == MaterialKind.CUTOUT)
                    val alpha = ((texel ushr 24) and 0xFF) / 255f
                    if (kind == MaterialKind.CUTOUT && alpha < 0.5f) continue
                    var tr = ((texel shr 16) and 0xFF) / 255f
                    var tg = ((texel shr 8) and 0xFF) / 255f
                    var tb = (texel and 0xFF) / 255f
                    if (kind == MaterialKind.OPAQUE) {
                        // Tiles repeat; break the period. See ShadingModel.detile.
                        ShadingModel.detile(p[Vertex.U], p[Vertex.V], p[Vertex.PX], p[Vertex.PX + 1], p[Vertex.PX + 2], detile)
                        val other = sample(texture, detile[0], detile[1], clampEdges = false)
                        val w = detile[2]
                        tr = (tr + ((((other shr 16) and 0xFF) / 255f) - tr) * w) * detile[3]
                        tg = (tg + ((((other shr 8) and 0xFF) / 255f) - tg) * w) * detile[3]
                        tb = (tb + (((other and 0xFF) / 255f) - tb) * w) * detile[3]
                    }
                    ar *= tr; ag *= tg; ab *= tb
                }

                var nx = p[Vertex.NX]; var ny = p[Vertex.NX + 1]; var nz = p[Vertex.NX + 2]
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
                nx /= nl; ny /= nl; nz /= nl
                val wx = p[Vertex.PX]; val wy = p[Vertex.PX + 1]; val wz = p[Vertex.PX + 2]
                var ex = s.eyeX - wx; var ey = s.eyeY - wy; var ez = s.eyeZ - wz
                val dist = sqrt(ex * ex + ey * ey + ez * ez)
                ex /= dist; ey /= dist; ez /= dist
                val ndl = max(0f, nx * s.terms.sun[0] + ny * s.terms.sun[1] + nz * s.terms.sun[2])
                val lit = if (ndl > 0f) sunlit(s.frame, wx + nx * NORMAL_OFFSET, wy + ny * NORMAL_OFFSET, wz + nz * NORMAL_OFFSET, ndl, clip) else 0f

                ShadingModel.shade(
                    s.terms, ar, ag, ab, nx, ny, nz,
                    if (kind == MaterialKind.CUTOUT) 1f else p[Vertex.AO], lit, wx, wy, wz, ex, ey, ez,
                    p[Vertex.EMISSIVE], layer <= Vertex.ACTOR + 0.5f,
                    s.frame.lights, s.lightColors, s.out,
                )
                val f = ShadingModel.fog(s.terms, dist, wz)
                val c = o * 3
                color[c] = s.out[0] + (untoneFog(s.terms, 0) - s.out[0]) * f
                color[c + 1] = s.out[1] + (untoneFog(s.terms, 1) - s.out[1]) * f
                color[c + 2] = s.out[2] + (untoneFog(s.terms, 2) - s.out[2]) * f
                if (writesDepth) depth[o] = d
            }
        }
    }

    private fun untoneFog(t: ShadingModel.Terms, channel: Int): Float = untone(t.fog[channel], t.exposure)

    /** Soft disc or ring, alpha-blended over what is already there. */
    private fun blendDecal(o: Int, p: FloatArray) {
        val r = sqrt(p[Vertex.U] * p[Vertex.U] + p[Vertex.V] * p[Vertex.V])
        val shape = if (p[Vertex.LAYER] >= Vertex.RING - 0.5f) {
            val k = (r - 0.82f) / 0.1f
            exp(-k * k)
        } else {
            1f - ShadingModel.smoothstep(0.35f, 1f, r)
        }
        val alpha = (shape * p[Vertex.AO]).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val c = o * 3
        for (k in 0 until 3) color[c + k] += (p[Vertex.R + k] - color[c + k]) * alpha
    }

    private fun addGlow(o: Int, p: FloatArray) {
        val r = sqrt(p[Vertex.U] * p[Vertex.U] + p[Vertex.V] * p[Vertex.V])
        if (r >= 1f) return
        val fall = (1f - r) * (1f - r) * p[Vertex.AO]
        val c = o * 3
        for (k in 0 until 3) color[c + k] += p[Vertex.R + k] * fall * GLOW_GAIN
    }

    private fun sample(t: Texture, u: Float, v: Float, clampEdges: Boolean): Int {
        val fu = if (clampEdges) u.coerceIn(0f, 1f) * (t.width - 1) else (u - floor(u)) * t.width
        val fv = if (clampEdges) v.coerceIn(0f, 1f) * (t.height - 1) else (v - floor(v)) * t.height
        val x0 = floor(fu).toInt(); val y0 = floor(fv).toInt()
        val tx = fu - x0; val ty = fv - y0
        fun at(x: Int, y: Int): Int {
            val xx = if (clampEdges) x.coerceIn(0, t.width - 1) else Math.floorMod(x, t.width)
            val yy = if (clampEdges) y.coerceIn(0, t.height - 1) else Math.floorMod(y, t.height)
            return t.argb[yy * t.width + xx]
        }
        val c00 = at(x0, y0); val c10 = at(x0 + 1, y0); val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (c00 ushr shift) and 0xFF; val b = (c10 ushr shift) and 0xFF
            val c = (c01 ushr shift) and 0xFF; val d = (c11 ushr shift) and 0xFF
            val top = a + (b - a) * tx; val bottom = c + (d - c) * tx
            out = out or (((top + (bottom - top) * ty).toInt() and 0xFF) shl shift)
        }
        return out
    }

    /** Tone map, grade, and box-filter the supersampled buffer down. */
    private fun resolve(terms: ShadingModel.Terms): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val rgb = FloatArray(3)
        val n = supersample * supersample
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f; var g = 0f; var b = 0f
                for (sy in 0 until supersample) for (sx in 0 until supersample) {
                    val o = ((y * supersample + sy) * w + (x * supersample + sx)) * 3
                    rgb[0] = color[o]; rgb[1] = color[o + 1]; rgb[2] = color[o + 2]
                    ShadingModel.finish(terms, rgb, (x + 0.5f) / width, (y + 0.5f) / height)
                    r += rgb[0]; g += rgb[1]; b += rgb[2]
                }
                val ri = (r / n * 255f).toInt().coerceIn(0, 255)
                val gi = (g / n * 255f).toInt().coerceIn(0, 255)
                val bi = (b / n * 255f).toInt().coerceIn(0, 255)
                image.setRGB(x, y, (ri shl 16) or (gi shl 8) or bi)
            }
        }
        return image
    }

    private fun edge(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun abs(v: Float) = if (v < 0f) -v else v

    private companion object {
        const val NEAR_W = 0.5f
        const val SHADOW_BIAS = 0.0015f
        const val SHADOW_SLOPE_BIAS = 0.004f
        const val NORMAL_OFFSET = 0.04f
        const val GLOW_GAIN = 1.4f
    }
}
