package com.stratum.tools.artpreview

import com.stratum.engine.scene.MaterialKind
import com.stratum.engine.scene.Mat4
import com.stratum.engine.scene.MeshBatch
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.ShadingModel
import com.stratum.engine.scene.SplatBatch
import com.stratum.engine.scene.SplatFaces
import com.stratum.engine.scene.SplatMode
import com.stratum.engine.scene.VoxelSplat
import com.stratum.engine.scene.Surfel
import com.stratum.engine.scene.SurfelDraw
import com.stratum.engine.scene.SurfelLod
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vertex
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
        // The land and its models open up around the player; actors and the rest never do.
        frame.opaque.forEach { rasterize(it, viewProj, surface, if (it === frame.actors) null else frame.reveal) }
        // Voxel splats with the opaque land, depth-written like it.
        if (frame.splats.isNotEmpty()) {
            val inverse = Mat4.invert(viewProj) ?: FloatArray(16)
            frame.splats.forEach { splats(it, frame.splatMode, viewProj, inverse, surface) }
        }
        // Surfels after the opaque pass, as the GPU draws them: depth-tested against the land they lie on.
        frame.surfels.forEach { surfels(it, viewProj, surface) }
        rasterize(frame.cutout, viewProj, surface)
        rasterize(frame.decals, viewProj, surface)
        rasterize(frame.glows, viewProj, surface)

        if (frame.look.readsDepth) depthFinish(frame)
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
        // Sprites cast their silhouettes as decals, not into the map. See
        // Vertex.SPRITE_SHADOW and the GL renderer's shadow pass.
        val casters = frame.opaque
        casters.forEach { batch ->
            val v = batch.vertices
            val idx = batch.indices
            var i = 0
            while (i < batch.indexCount) {
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
        // Splats cast as squares of their centre's depth, as the GPU's shadow sprites do.
        val row = max(
            sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8]),
            sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9]),
        ) * shadowSize / 2f
        for (b in frame.splats) {
            val d = b.data
            val per = 1f / b.microPerBlock
            for (i in 0 until b.count) {
                val a = d[i * VoxelSplat.INTS]
                val side = VoxelSplat.size(a) * per
                val half = side / 2f
                Mat4.transform(m, b.originX + VoxelSplat.x(a) * per + half, b.originY + VoxelSplat.y(a) * per + half, VoxelSplat.z(a) * per + half, clip)
                val u = (clip[0] / clip[3] * 0.5f + 0.5f) * shadowSize
                val v = (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * shadowSize
                val z = clip[2] / clip[3] * 0.5f + 0.5f
                val r = max(0.5f, side * VoxelSplat.SHADOW_SPREAD * row / 2f)
                val x0 = max(0, (u - r).roundToInt()); val x1 = min(shadowSize - 1, (u + r).roundToInt() - 1)
                val y0 = max(0, (v - r).roundToInt()); val y1 = min(shadowSize - 1, (v + r).roundToInt() - 1)
                for (py in y0..y1) for (px in x0..x1) {
                    val o = py * shadowSize + px
                    if (z < shadow[o]) shadow[o] = z
                }
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

    private inner class Surface(
        val terms: ShadingModel.Terms,
        val frame: SceneFrame,
        val eyeX: Float, val eyeY: Float, val eyeZ: Float,
        val lightColors: Array<FloatArray>,
        val out: FloatArray,
    ) {
        val look = frame.look
        val focus = frame.camera.target
        /** World size of one output pixel at distance 1: what the bevel fade measures voxels against. */
        val pixelAngle = (2.0 * kotlin.math.tan(Math.toRadians(frame.camera.fovY / 2.0)) / height).toFloat()
        val grain = FloatArray(3)
        val normal = FloatArray(3)
        val haze = FloatArray(3)
    }

    /** Aerial haze, then the fog, over the lit colour in [Surface.out], in place; the GLSL twin ends LIT_FRAGMENT. */
    private fun atmosphere(s: Surface, dist: Float, wx: Float, wy: Float, wz: Float) {
        if (s.look.aerialHaze) {
            val amount = ShadingModel.haze(s.terms, dist, s.frame.camera.distance, wz, s.focus.z, wx - s.eyeX, wy - s.eyeY, s.haze)
            for (k in 0 until 3) s.out[k] += (untone(s.haze[k], s.terms.exposure) - s.out[k]) * amount
        }
        val f = ShadingModel.fog(s.terms, dist, wz)
        for (k in 0 until 3) s.out[k] += (untoneFog(s.terms, k) - s.out[k]) * f
    }

    /**
     * One chunk's surfels, as the GPU draws its point sprites: lit once at
     * the centre, then splatted as a disc squashed by how the face turns
     * from the eye, domed towards the rim, depth-tested at a centre pulled
     * a little towards the eye, without writing depth. Twin of
     * SURFEL_VERTEX and SURFEL_FRAGMENT.
     */
    private fun surfels(draw: SurfelDraw, viewProj: FloatArray, s: Surface) {
        val b = draw.batch
        val clip = FloatArray(4)
        val tip = FloatArray(4)
        val n = s.normal
        val pixelsPerUnit = h * s.frame.camera.projection[5] / 2f
        val minPixels = ShadingModel.SURFEL_MIN_PIXELS * supersample
        val radiusLimit = s.look.surfelRadius.toFloat()
        val reveal = s.frame.reveal
        for (i in 0 until draw.count) {
            val wx = Surfel.x(b, i); val wy = Surfel.y(b, i); val wz = Surfel.z(b, i)
            val fx = wx - s.focus.x; val fy = wy - s.focus.y
            val grow = SurfelLod.size(Surfel.rank(b, i), SurfelLod.share(sqrt(fx * fx + fy * fy), radiusLimit) * draw.keep)
            if (grow <= 0f) continue
            if (reveal != null && ShadingModel.revealCut(reveal, s.eyeX, s.eyeY, s.eyeZ, wx, wy, wz) > 0.5f) continue
            val radius = Surfel.radius(b, i) * grow
            Surfel.normal(b, i, n)
            var ex = s.eyeX - wx; var ey = s.eyeY - wy; var ez = s.eyeZ - wz
            val dist = sqrt(ex * ex + ey * ey + ez * ez)
            ex /= dist; ey /= dist; ez /= dist
            val pull = radius * ShadingModel.SURFEL_PULL
            Mat4.transform(viewProj, wx + ex * pull, wy + ey * pull, wz + ez * pull, clip)
            if (clip[3] < NEAR_W) continue
            val iw = 1f / clip[3]
            val cx = (clip[0] * iw * 0.5f + 0.5f) * w
            val cy = (1f - (clip[1] * iw * 0.5f + 0.5f)) * h
            val cz = clip[2] * iw
            val rp = radius * pixelsPerUnit * iw
            if (rp < minPixels) continue
            if (cx + rp < 0f || cy + rp < 0f || cx - rp >= w || cy - rp >= h) continue
            // Which way the disc is foreshortened on screen: along its normal's projection.
            Mat4.transform(viewProj, wx, wy, wz, tip)
            val bx = tip[0] / tip[3]; val by = tip[1] / tip[3]
            Mat4.transform(viewProj, wx + n[0] * radius, wy + n[1] * radius, wz + n[2] * radius, tip)
            var dx = (tip[0] / tip[3] - bx) * w; var dy = -(tip[1] / tip[3] - by) * h
            val dl = sqrt(dx * dx + dy * dy)
            if (dl < 1e-6f) { dx = 1f; dy = 0f } else { dx /= dl; dy /= dl }
            val squash = abs(n[0] * ex + n[1] * ey + n[2] * ez).coerceIn(ShadingModel.SURFEL_MIN_SQUASH, 1f)

            val ndl = max(0f, n[0] * s.terms.sun[0] + n[1] * s.terms.sun[1] + n[2] * s.terms.sun[2])
            val so = ShadingModel.SURFEL_SHADOW_OFFSET
            val lit = if (ndl > 0f) sunlit(s.frame, wx + n[0] * so, wy + n[1] * so, wz + n[2] * so, ndl, clip) else 0f
            ShadingModel.shade(
                s.terms, Surfel.red(b, i), Surfel.green(b, i), Surfel.blue(b, i), n[0], n[1], n[2],
                Surfel.occlusion(b, i), lit, wx, wy, wz, ex, ey, ez, 0f, false,
                s.frame.lights, s.lightColors, s.out, occlusionDepth = s.look.occlusionDepth,
            )
            atmosphere(s, dist, wx, wy, wz)
            val r0 = s.out[0]; val g0 = s.out[1]; val b0 = s.out[2]

            val minX = max(0, floor(cx - rp).toInt()); val maxX = min(w - 1, ceil(cx + rp).toInt())
            val minY = max(0, floor(cy - rp).toInt()); val maxY = min(h - 1, ceil(cy + rp).toInt())
            for (py in minY..maxY) for (px in minX..maxX) {
                val ux = (px + 0.5f - cx) / rp; val uy = (py + 0.5f - cy) / rp
                val along = (ux * dx + uy * dy) / squash
                val across = -ux * dy + uy * dx
                val q = along * along + across * across
                if (q > 1f) continue
                val o = py * w + px
                if (cz >= depth[o]) continue
                val dome = 1f + ShadingModel.SURFEL_DOME * (0.5f - q)
                val c = o * 3
                color[c] = r0 * dome; color[c + 1] = g0 * dome; color[c + 2] = b0 * dome
                // No depth write: the finish reads depth for creases, and a
                // field of tiny bumps in it would ink every surfel.
            }
        }
    }

    /**
     * One batch of voxel splats, as SPLAT_VERTEX and SPLAT_FRAGMENT draw
     * them: each voxel's top and its two eye-facing sides lit once at its
     * centre, then a square sprite covering the cube, split into faces by
     * [SplatFaces] and cut to its outline ([SplatMode.FAST], depth of the centre) or ray-cast
     * against the box per pixel ([SplatMode.EXACT], depth of the hit).
     */
    private fun splats(b: SplatBatch, mode: SplatMode, viewProj: FloatArray, inverse: FloatArray, s: Surface) {
        val clip = FloatArray(4)
        val scratch = FloatArray(4)
        val lines = FloatArray(SplatFaces.FLOATS)
        val cols = Array(3) { FloatArray(3) }
        val grain = s.grain
        val near = FloatArray(4); val far = FloatArray(4)
        val pixelsPerUnit = h * s.frame.camera.projection[5] / 2f
        val reveal = s.frame.reveal
        val per = 1f / b.microPerBlock
        val t = s.terms
        val glow = 1f + s.look.nightGlow * s.frame.night
        val exact = mode == SplatMode.EXACT
        // Lamps are baked into the splats; only the lights that move are shaded here.
        val moving = s.frame.lights.take(s.frame.dynamicLights)
        val lampGain = s.frame.lighting.pointLightGain
        // One bounce of the hour's daylight: sky and sun as they fall on open ground.
        val bounceLight = FloatArray(3) { k -> (t.sky[k] + t.sunColor[k] * max(0f, t.sun[2])) * VoxelSplat.BOUNCE_GAIN }
        for (i in 0 until b.count) {
            val a = b.data[i * VoxelSplat.INTS]; val c = b.data[i * VoxelSplat.INTS + 1]
            val lightBits = b.data[i * VoxelSplat.INTS + 2]; val lampBits = b.data[i * VoxelSplat.INTS + 3]
            val side = VoxelSplat.size(a) * per
            val half = side / 2f
            val mx = b.originX + VoxelSplat.x(a) * per; val my = b.originY + VoxelSplat.y(a) * per; val mz = VoxelSplat.z(a) * per
            val wx = mx + half; val wy = my + half; val wz = mz + half
            if (reveal != null && ShadingModel.revealCut(reveal, s.eyeX, s.eyeY, s.eyeZ, wx, wy, wz) > 0.5f) continue
            Mat4.transform(viewProj, wx, wy, wz, clip)
            if (clip[3] < NEAR_W) continue
            val iw = 1f / clip[3]
            val cx = (clip[0] * iw * 0.5f + 0.5f) * w
            val cy = (1f - (clip[1] * iw * 0.5f + 0.5f)) * h
            val cz = clip[2] * iw
            val r = side * VoxelSplat.SPRITE_SPREAD * pixelsPerUnit * iw / 2f
            if (cx + r < 0f || cy + r < 0f || cx - r >= w || cy - r >= h) continue

            var ex = s.eyeX - wx; var ey = s.eyeY - wy; var ez = s.eyeZ - wz
            val dist = sqrt(ex * ex + ey * ey + ez * ez)
            ex /= dist; ey /= dist; ez /= dist
            val sx = if (ex >= 0f) 1f else -1f; val sy = if (ey >= 0f) 1f else -1f
            val faces = VoxelSplat.faces(a)
            val rgb = VoxelSplat.rgb(c)
            var ar = ((rgb shr 16) and 255) / 255f; var ag = ((rgb shr 8) and 255) / 255f; var ab = (rgb and 255) / 255f
            if (s.look.grain > 0f) {
                ShadingModel.voxelGrain(s.look.grain, wx, wy, wz, 0f, 0f, 0f, grain)
                ar *= grain[0]; ag *= grain[1]; ab *= grain[2]
            }
            val emissive = VoxelSplat.emissive(VoxelSplat.emission(c)) * glow
            // One shadow lookup for the voxel, just above its top towards the sun.
            val lift = side * VoxelSplat.SHADOW_LIFT
            val lit = sunlit(s.frame, wx + t.sun[0] * lift, wy + t.sun[1] * lift, wz + t.sun[2] * lift, 1f, scratch)
            val skyShare = VoxelSplat.skyShare(VoxelSplat.sky(lightBits))
            val bounce = VoxelSplat.bounce(lightBits); val lamp = VoxelSplat.lamp(lampBits)
            // Light the neighbourhood gives the voxel, the same on each of its faces.
            val ir = VoxelSplat.channel(bounce, 16, 1f) * bounceLight[0] + VoxelSplat.channel(lamp, 16, VoxelSplat.LAMP_RANGE) * lampGain
            val ig = VoxelSplat.channel(bounce, 8, 1f) * bounceLight[1] + VoxelSplat.channel(lamp, 8, VoxelSplat.LAMP_RANGE) * lampGain
            val ib = VoxelSplat.channel(bounce, 0, 1f) * bounceLight[2] + VoxelSplat.channel(lamp, 0, VoxelSplat.LAMP_RANGE) * lampGain
            val xOpen = faces and (if (sx > 0f) VoxelSplat.FACE_PX else VoxelSplat.FACE_NX) != 0
            val yOpen = faces and (if (sy > 0f) VoxelSplat.FACE_PY else VoxelSplat.FACE_NY) != 0
            for (f in 0 until 3) {
                // A closed side is hidden by its neighbour; shading it as top keeps a sprite's spill onto that neighbour its colour.
                val face = when {
                    f == SplatFaces.X_SIDE && xOpen -> 1
                    f == SplatFaces.Y_SIDE && yOpen -> 2
                    else -> 0
                }
                if (face == 0 && f != 0) { cols[0].copyInto(cols[f]); continue }
                val nx = if (face == 1) sx else 0f; val ny = if (face == 2) sy else 0f; val nz = if (face == 0) 1f else 0f
                val ao = (if (face == 0) VoxelSplat.occlusion(VoxelSplat.ao(a)) else 1f) * skyShare
                ShadingModel.shade(
                    t, ar, ag, ab, nx, ny, nz, ao, lit, wx, wy, wz, ex, ey, ez, emissive, false,
                    moving, s.lightColors, s.out, occlusionDepth = s.look.occlusionDepth,
                )
                s.out[0] += ar * ir; s.out[1] += ag * ig; s.out[2] += ab * ib
                atmosphere(s, dist, wx, wy, wz)
                s.out.copyInto(cols[f])
            }
            if (!exact) SplatFaces.lines(viewProj, clip, w / 2f, h / 2f, half, sx, sy, lines)

            val minX = max(0, floor(cx - r).toInt()); val maxX = min(w - 1, ceil(cx + r).toInt() - 1)
            val minY = max(0, floor(cy - r).toInt()); val maxY = min(h - 1, ceil(cy + r).toInt() - 1)
            for (py in minY..maxY) for (px in minX..maxX) {
                val o = py * w + px
                val f: Int
                val z: Float
                if (exact) {
                    // The pixel's ray, from the near plane to the far, against the voxel's box.
                    val nxd = (px + 0.5f) / w * 2f - 1f; val nyd = 1f - (py + 0.5f) / h * 2f
                    unproject(inverse, nxd, nyd, -1f, near); unproject(inverse, nxd, nyd, 1f, far)
                    val dx = far[0] - near[0]; val dy = far[1] - near[1]; val dz = far[2] - near[2]
                    var t0 = 0f; var t1 = Float.MAX_VALUE; var axis = -1
                    var hit = true
                    for (k in 0 until 3) {
                        val o3 = near[k]
                        val d3 = when (k) { 0 -> dx; 1 -> dy; else -> dz }
                        val lo = when (k) { 0 -> mx; 1 -> my; else -> mz }
                        if (abs(d3) < 1e-9f) { if (o3 < lo || o3 > lo + side) { hit = false; break }; continue }
                        var ta = (lo - o3) / d3; var tb = (lo + side - o3) / d3
                        if (ta > tb) { val sw = ta; ta = tb; tb = sw }
                        if (ta > t0) { t0 = ta; axis = k }
                        if (tb < t1) t1 = tb
                    }
                    if (!hit || t0 > t1 || axis < 0) continue
                    Mat4.transform(viewProj, near[0] + dx * t0, near[1] + dy * t0, near[2] + dz * t0, far)
                    z = far[2] / far[3]
                    f = when (axis) { 0 -> SplatFaces.X_SIDE; 1 -> SplatFaces.Y_SIDE; else -> SplatFaces.TOP }
                } else {
                    f = SplatFaces.face(lines, px + 0.5f - cx, cy - (py + 0.5f))
                    if (f == SplatFaces.OUTSIDE) continue
                    z = cz
                }
                if (z >= depth[o]) continue
                depth[o] = z
                val col = cols[f]
                val k = o * 3
                color[k] = col[0]; color[k + 1] = col[1]; color[k + 2] = col[2]
            }
        }
    }

    private fun unproject(inverse: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        val cw = inverse[3] * x + inverse[7] * y + inverse[11] * z + inverse[15]
        out[0] = (inverse[0] * x + inverse[4] * y + inverse[8] * z + inverse[12]) / cw
        out[1] = (inverse[1] * x + inverse[5] * y + inverse[9] * z + inverse[13]) / cw
        out[2] = (inverse[2] * x + inverse[6] * y + inverse[10] * z + inverse[14]) / cw
    }

    private var finished: FloatArray? = null

    /**
     * The depth-reading half of the finish, on the supersampled buffer
     * before tone mapping: ink in the creases, occlusion between separate
     * pieces, and the tilt-shift blur away from the focus. Offsets are in
     * output pixels, as the GPU's are in its scene target's. Twin of the
     * depth block in FINISH_FRAGMENT.
     */
    private fun depthFinish(frame: SceneFrame) {
        val look = frame.look
        val cam = frame.camera
        val out = finished?.takeIf { it.size == color.size } ?: FloatArray(color.size).also { finished = it }
        val near = cam.near; val far = cam.far
        fun inverse(x: Int, y: Int): Float {
            val d = depth[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            if (d == Float.MAX_VALUE) return 0f
            return (far + near - d * (far - near)) / (2f * near * far)
        }
        val step = supersample
        val pixelsPerUnit = h * cam.projection[5] / 2f
        val focusDepth = cam.distance
        val ring = ShadingModel.RING
        val disc = ShadingModel.DISC
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val wc = inverse(x, y)
            var amount = 1f
            var k = 1f
            if (wc > 0f) {
                val lin = 1f / wc
                amount = if (look.tiltShift > 0f) ShadingModel.smoothstep(ShadingModel.TILT_NEAR, ShadingModel.TILT_FAR, abs(lin - focusDepth) / focusDepth) else 0f
                val lap = (inverse(x - step, y) + inverse(x + step, y) + inverse(x, y - step) + inverse(x, y + step)) * 0.25f - wc
                val rel = lap / wc * ShadingModel.EDGE_GAIN
                val crease = rel.coerceIn(0f, 1f)
                val rim = (-rel).coerceIn(0f, 1f) * ShadingModel.EDGE_LIGHT
                var occ = 0f
                if (look.screenOcclusion > 0f) {
                    val r = ShadingModel.SCREEN_AO_RADIUS * pixelsPerUnit * wc
                    val lo = wc * (1f - ShadingModel.SCREEN_AO_CLAMP); val hi = wc * (1f + ShadingModel.SCREEN_AO_CLAMP)
                    var sum = 0f
                    for (t in 0 until 8) sum += inverse((x + ring[t * 2] * r).toInt(), (y + ring[t * 2 + 1] * r).toInt()).coerceIn(lo, hi)
                    occ = ((sum / 8f - wc) / wc * ShadingModel.SCREEN_AO_GAIN).coerceIn(0f, 1f)
                }
                val shade = (1f - look.edges * crease) * (1f - look.screenOcclusion * occ) + look.edges * rim
                k = 1f + (shade - 1f) * (1f - amount)
            } else if (look.tiltShift <= 0f) amount = 0f
            if (amount > 0f && look.tiltShift > 0f) {
                val r = look.tiltShift * h * amount
                var cr = 0f; var cg = 0f; var cb = 0f
                for (t in 0 until 12) {
                    val sx = (x + disc[t * 2] * r).toInt().coerceIn(0, w - 1)
                    val sy = (y + disc[t * 2 + 1] * r).toInt().coerceIn(0, h - 1)
                    val so = (sy * w + sx) * 3
                    cr += color[so]; cg += color[so + 1]; cb += color[so + 2]
                }
                out[o] = cr / 12f * k; out[o + 1] = cg / 12f * k; out[o + 2] = cb / 12f * k
            } else {
                out[o] = color[o] * k; out[o + 1] = color[o + 1] * k; out[o + 2] = color[o + 2] * k
            }
        }
        out.copyInto(color)
    }

    /** Screen-space triangles with perspective-correct attributes. */
    private fun rasterize(batch: MeshBatch, viewProj: FloatArray, s: Surface, reveal: com.stratum.engine.scene.Reveal? = null) {
        val v = batch.vertices
        val idx = batch.indices
        val clip = FloatArray(4)
        val sx = FloatArray(3); val sy = FloatArray(3); val sz = FloatArray(3); val iw = FloatArray(3)
        val attr = Array(3) { FloatArray(Vertex.STRIDE) }
        val scratch = FloatArray(4)
        var i = 0
        while (i < batch.indexCount) {
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
            triangle(batch.kind, sx, sy, sz, iw, attr, s, scratch, reveal)
        }
    }

    private fun triangle(
        kind: MaterialKind,
        x: FloatArray, y: FloatArray, z: FloatArray, iw: FloatArray,
        a: Array<FloatArray>, s: Surface, clip: FloatArray, reveal: com.stratum.engine.scene.Reveal? = null,
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
        val weights = FloatArray(2)
        val calmed = FloatArray(3)
        val variantA = a[0][Vertex.VARIANT_A].takeIf { it >= 0f }?.let { textures.textureAt(it.toInt()) }
        val variantB = a[0][Vertex.VARIANT_B].takeIf { it >= 0f }?.let { textures.textureAt(it.toInt()) }

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

                if (reveal != null) {
                    val cut = ShadingModel.revealCut(reveal, s.eyeX, s.eyeY, s.eyeZ, p[Vertex.PX], p[Vertex.PX + 1], p[Vertex.PX + 2])
                    if (cut > 0f && cut >= Vertex.DITHER[((py / supersample) and 3) * 4 + ((px / supersample) and 3)]) continue
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
                        tr += ((((other shr 16) and 0xFF) / 255f) - tr) * w
                        tg += ((((other shr 8) and 0xFF) / 255f) - tg) * w
                        tb += (((other and 0xFF) / 255f) - tb) * w
                        // Sister paintings, in slow patches. See ShadingModel.variants.
                        if (variantA != null || variantB != null) {
                            ShadingModel.variants(p[Vertex.PX], p[Vertex.PX + 1], weights)
                            if (variantA != null && weights[0] > 0f) {
                                val t = sample(variantA, p[Vertex.U], p[Vertex.V], clampEdges = false)
                                tr += ((((t shr 16) and 0xFF) / 255f) - tr) * weights[0]
                                tg += ((((t shr 8) and 0xFF) / 255f) - tg) * weights[0]
                                tb += (((t and 0xFF) / 255f) - tb) * weights[0]
                            }
                            if (variantB != null && weights[1] > 0f) {
                                val t = sample(variantB, detile[0], detile[1], clampEdges = false)
                                tr += ((((t shr 16) and 0xFF) / 255f) - tr) * weights[1]
                                tg += ((((t shr 8) and 0xFF) / 255f) - tg) * weights[1]
                                tb += (((t and 0xFF) / 255f) - tb) * weights[1]
                            }
                        }
                        tr *= detile[3]; tg *= detile[3]; tb *= detile[3]
                        // Background stays background. See ShadingModel.calm.
                        calmed[0] = tr; calmed[1] = tg; calmed[2] = tb
                        ShadingModel.calm(s.terms, calmed, meanOf(texture), p[Vertex.NX + 2])
                        tr = calmed[0]; tg = calmed[1]; tb = calmed[2]
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
                // Flat-coloured opaque surfaces are the microvoxels: each tiny cube its own tone, its edges rounded.
                if (kind == MaterialKind.OPAQUE && layer > -1.5f && layer < -0.5f) {
                    if (s.look.grain > 0f) {
                        ShadingModel.voxelGrain(s.look.grain, wx, wy, wz, nx, ny, nz, s.grain)
                        ar *= s.grain[0]; ag *= s.grain[1]; ab *= s.grain[2]
                    }
                    val bevel = ShadingModel.bevelFade(s.look.bevel, dist, s.pixelAngle)
                    if (bevel > 0f) {
                        s.normal[0] = nx; s.normal[1] = ny; s.normal[2] = nz
                        ShadingModel.bevel(bevel, wx, wy, wz, s.normal)
                        nx = s.normal[0]; ny = s.normal[1]; nz = s.normal[2]
                    }
                }
                val ndl = max(0f, nx * s.terms.sun[0] + ny * s.terms.sun[1] + nz * s.terms.sun[2])
                val lit = if (ndl > 0f) sunlit(s.frame, wx + nx * NORMAL_OFFSET, wy + ny * NORMAL_OFFSET, wz + nz * NORMAL_OFFSET, ndl, clip) else 0f

                ShadingModel.shade(
                    s.terms, ar, ag, ab, nx, ny, nz,
                    if (kind == MaterialKind.CUTOUT) 1f else p[Vertex.AO], lit, wx, wy, wz, ex, ey, ez,
                    ShadingModel.nightEmissive(p[Vertex.EMISSIVE], s.look.nightGlow, s.frame.night), layer <= Vertex.ACTOR + 0.5f,
                    s.frame.lights, s.lightColors, s.out,
                    occlusionDepth = if (kind == MaterialKind.OPAQUE) s.look.occlusionDepth else 0f,
                )
                if (layer < Vertex.CLAY + 0.5f) ShadingModel.clay(s.terms, nx, ny, nz, ex, ey, ez, lit, s.out)
                atmosphere(s, dist, wx, wy, wz)
                val c = o * 3
                color[c] = s.out[0]; color[c + 1] = s.out[1]; color[c + 2] = s.out[2]
                if (writesDepth) depth[o] = d
            }
        }
    }

    private val means = java.util.IdentityHashMap<Texture, FloatArray>()

    /** A texture's average colour: the one-pixel mip level the GPU samples for the same thing. */
    private fun meanOf(t: Texture): FloatArray = means.getOrPut(t) {
        var r = 0.0; var g = 0.0; var b = 0.0
        t.argb.forEach { c -> r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        val n = t.argb.size * 255.0
        floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    private fun untoneFog(t: ShadingModel.Terms, channel: Int): Float = untone(t.fog[channel], t.exposure)

    /** Soft disc or ring, alpha-blended over what is already there. */
    private fun blendDecal(o: Int, p: FloatArray) {
        val r = sqrt(p[Vertex.U] * p[Vertex.U] + p[Vertex.V] * p[Vertex.V])
        val shape = if (p[Vertex.LAYER] >= Vertex.SPRITE_SHADOW - 0.5f) {
            val t = textures.textureAt(p[Vertex.VARIANT_A].toInt()) ?: return
            softAlpha(t, p[Vertex.U], p[Vertex.V])
        } else if (p[Vertex.LAYER] >= Vertex.RING - 0.5f) {
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

    /** Alpha averaged over a small cross, standing in for the GPU's blurrier mip. */
    private fun softAlpha(t: Texture, u: Float, v: Float): Float {
        val step = 1.5f / t.width
        var sum = 0f
        for ((du, dv) in SOFT_TAPS) {
            val texel = sample(t, u + du * step, v + dv * step, clampEdges = true)
            sum += ((texel ushr 24) and 0xFF) / 255f
        }
        return sum / SOFT_TAPS.size
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
        private val SOFT_TAPS = listOf(0f to 0f, 1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
    }
}
