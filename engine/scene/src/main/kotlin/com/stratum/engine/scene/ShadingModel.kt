package com.stratum.engine.scene

import com.stratum.core.domain.art.SceneLighting
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The lighting equation, written once in Kotlin.
 *
 * The GLES fragment shader in `:feature:play` is a line-for-line port of this
 * object, and the software rasteriser calls it directly. Keeping the reference
 * here — pure, testable, next to the data it reads — is what stops the preview
 * images and the game drifting into two different looks.
 */
object ShadingModel {

    /** Unpacked lighting, so the per-pixel path does no bit shifting. */
    class Terms(lighting: SceneLighting) {
        val sun = floatArrayOf(lighting.sunX, lighting.sunY, lighting.sunZ)
        val fill = floatArrayOf(lighting.fillX, lighting.fillY, lighting.fillZ)
        val fillStrength = lighting.fillStrength
        val sunColor = rgb(lighting.sunColor, lighting.sunIntensity)
        val sky = rgb(lighting.skyAmbient, lighting.ambientIntensity)
        val ground = rgb(lighting.groundAmbient, lighting.ambientIntensity)
        val fog = rgb(lighting.fogColor, 1f)
        val rim = rgb(lighting.rimColor, lighting.rimStrength)
        val fogStart = lighting.fogStart
        val fogEnd = lighting.fogEnd
        val fogFloor = lighting.fogFloor
        val shadowStrength = lighting.shadowStrength
        val exposure = lighting.exposure
        val saturation = lighting.saturation
        val vignette = lighting.vignette
        val floorDetail = lighting.floorDetail
        val floorSaturation = lighting.floorSaturation
    }

    /**
     * Quietens a painted surface in place so it reads as background: contrast
     * pulled towards the painting's [mean] colour and saturation lowered.
     * Floors (facing up) get the full treatment; walls keep a little more,
     * because a cliff face is also what tells the player where they can go.
     * The GLSL twin is `calm` in the shaders.
     */
    fun calm(t: Terms, rgb: FloatArray, mean: FloatArray, nz: Float) {
        val floor = nz > FLOOR_FACING
        val detail = if (floor) t.floorDetail else minOf(1f, t.floorDetail + WALL_DETAIL_BONUS)
        val saturation = if (floor) t.floorSaturation else minOf(1f, t.floorSaturation + WALL_DETAIL_BONUS)
        for (i in 0 until 3) rgb[i] = mean[i] + (rgb[i] - mean[i]) * detail
        val luma = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        for (i in 0 until 3) rgb[i] = luma + (rgb[i] - luma) * saturation
    }

    /**
     * Lights one surface point. [out] receives linear-ish RGB before tone mapping.
     *
     * [lit] is 1 in full sun and 0 in full shadow, from the shadow map.
     * [toEye] must be normalised. [rimLit] turns the rim term on, for actors.
     */
    fun shade(
        t: Terms,
        albedoR: Float, albedoG: Float, albedoB: Float,
        nx: Float, ny: Float, nz: Float,
        ao: Float,
        lit: Float,
        px: Float, py: Float, pz: Float,
        toEyeX: Float, toEyeY: Float, toEyeZ: Float,
        emissive: Float,
        rimLit: Boolean,
        lights: List<PointLight>,
        lightGainColors: Array<FloatArray>,
        out: FloatArray,
        /** [DioramaLook.occlusionDepth]: 0 shades exactly as meshed. */
        occlusionDepth: Float = 0f,
    ) {
        // Deeper occlusion: the ambient term takes the corner value to a
        // higher power, and direct light gives up part of it too, so a crevice
        // stays soft and dark even in full sun.
        val skyAo = if (occlusionDepth > 0f) Math.pow(ao.toDouble(), 1.0 + occlusionDepth).toFloat() else ao
        val sunAo = 1f - occlusionDepth * OCCLUSION_SUN_SHARE * (1f - ao)
        // Hemisphere ambient: faces up see the sky, faces down see the ground.
        val hemi = nz * 0.5f + 0.5f
        var r = (t.ground[0] + (t.sky[0] - t.ground[0]) * hemi) * skyAo
        var g = (t.ground[1] + (t.sky[1] - t.ground[1]) * hemi) * skyAo
        var b = (t.ground[2] + (t.sky[2] - t.ground[2]) * hemi) * skyAo

        val ndl = max(0f, nx * t.sun[0] + ny * t.sun[1] + nz * t.sun[2])
        val shadow = (1f - t.shadowStrength * (1f - lit)) * sunAo
        r += t.sunColor[0] * ndl * shadow
        g += t.sunColor[1] * ndl * shadow
        b += t.sunColor[2] * ndl * shadow

        // Fill light from the camera's side; see SceneLighting.fillX.
        val fdl = max(0f, nx * t.fill[0] + ny * t.fill[1] + nz * t.fill[2])
        r += t.sunColor[0] * fdl * t.fillStrength
        g += t.sunColor[1] * fdl * t.fillStrength
        b += t.sunColor[2] * fdl * t.fillStrength

        for (i in lights.indices) {
            val light = lights[i]
            val dx = light.x - px; val dy = light.y - py; val dz = light.z - pz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d >= light.radius) continue
            val inv = 1f / max(d, 1e-4f)
            val facing = max(0f, (nx * dx + ny * dy + nz * dz) * inv) * 0.7f + 0.3f
            val fall = 1f - d / light.radius
            val k = fall * fall * facing
            val c = lightGainColors[i]
            r += c[0] * k; g += c[1] * k; b += c[2] * k
        }

        r *= albedoR; g *= albedoG; b *= albedoB
        if (emissive > 0f) {
            r += albedoR * emissive * EMISSIVE_GAIN
            g += albedoG * emissive * EMISSIVE_GAIN
            b += albedoB * emissive * EMISSIVE_GAIN
        }
        if (rimLit) {
            val facingEye = max(0f, nx * toEyeX + ny * toEyeY + nz * toEyeZ)
            val edge = (1f - facingEye).let { it * it }
            r += t.rim[0] * edge; g += t.rim[1] * edge; b += t.rim[2] * edge
        }
        out[0] = r; out[1] = g; out[2] = b
    }

    /** How much fog a point takes, from its distance and its height. */
    fun fog(t: Terms, distance: Float, z: Float): Float {
        val byDistance = smoothstep(t.fogStart, t.fogEnd, distance)
        val byHeight = if (z < t.fogFloor) ((t.fogFloor - z) / HEIGHT_FOG_DEPTH).coerceIn(0f, 1f) * HEIGHT_FOG_MAX else 0f
        return max(byDistance, byHeight)
    }

    /**
     * The tone of one microvoxel: a multiplier near 1 from a hash of the
     * voxel's own cell, with a whisper of warm or cool, into [rgb] as three
     * multipliers. [DioramaLook.grain] sets the spread; 0 gives exactly 1.
     *
     * The cell is found by stepping a hair inside the surface, so every
     * pixel of a voxel's face agrees on which voxel it is. The integer hash
     * is the one in the shaders (`voxelHash`), bit for bit.
     */
    fun voxelGrain(grain: Float, wx: Float, wy: Float, wz: Float, nx: Float, ny: Float, nz: Float, rgb: FloatArray) {
        val h = voxelHash(
            kotlin.math.floor((wx - nx * INSIDE) * VOXELS_PER_BLOCK).toInt(),
            kotlin.math.floor((wy - ny * INSIDE) * VOXELS_PER_BLOCK).toInt(),
            kotlin.math.floor((wz - nz * INSIDE) * VOXELS_PER_BLOCK).toInt(),
        )
        val tone = 1f + ((h and 0xFFFF) / 65535f - 0.5f) * 2f * grain
        val warm = (((h ushr 16) and 0xFFFF) / 65535f - 0.5f) * grain * GRAIN_WARMTH
        rgb[0] = tone * (1f + warm); rgb[1] = tone; rgb[2] = tone * (1f - warm)
    }

    /** 32-bit integer hash of a voxel cell; twin of `voxelHash` in GLSL, which uses uint arithmetic. */
    fun voxelHash(x: Int, y: Int, z: Int): Int {
        var h = ((x + HASH_OFFSET) * 73856093) xor ((y + HASH_OFFSET) * 19349663) xor ((z + HASH_OFFSET) * 83492791)
        h = h xor (h ushr 13)
        h *= 1274126177
        return h xor (h ushr 16)
    }

    /**
     * How much of [DioramaLook.bevel] shows at a pixel: all of it while a
     * microvoxel spans several pixels, none once it shrinks towards one,
     * where the rounded edges would only shimmer. [pixelAngle] is the
     * world size of one screen pixel at distance 1.
     */
    fun bevelFade(bevel: Float, distance: Float, pixelAngle: Float): Float {
        if (bevel <= 0f) return 0f
        val voxelsPerPixel = distance * pixelAngle * VOXELS_PER_BLOCK
        return bevel * (1f - smoothstep(BEVEL_FADE_START, BEVEL_FADE_END, voxelsPerPixel))
    }

    /**
     * Rounds a microvoxel's edges in the lighting: the normal [n] (unit,
     * axis-aligned) is tipped outwards near each edge of the voxel's face,
     * by [strength], in place. Faces that are not axis-aligned are left alone.
     */
    fun bevel(strength: Float, wx: Float, wy: Float, wz: Float, n: FloatArray) {
        if (strength <= 0f) return
        val ax = abs(n[0]); val ay = abs(n[1]); val az = abs(n[2])
        if (max(ax, max(ay, az)) < AXIS_ALIGNED) return
        if (ax < 0.5f) n[0] += edgeTilt(wx) * strength * BEVEL_TILT
        if (ay < 0.5f) n[1] += edgeTilt(wy) * strength * BEVEL_TILT
        if (az < 0.5f) n[2] += edgeTilt(wz) * strength * BEVEL_TILT
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        n[0] /= l; n[1] /= l; n[2] /= l
    }

    /** -1 at a voxel's low edge on this axis, +1 at its high edge, 0 across the middle. */
    private fun edgeTilt(w: Float): Float {
        val v = w * VOXELS_PER_BLOCK
        val f = v - kotlin.math.floor(v)
        return when {
            f < BEVEL_WIDTH -> -(1f - f / BEVEL_WIDTH)
            f > 1f - BEVEL_WIDTH -> 1f - (1f - f) / BEVEL_WIDTH
            else -> 0f
        }
    }

    /**
     * Aerial haze: how much of the warm-or-cool haze colour a point takes,
     * 0..[HAZE_MAX], with the colour (before tone mapping is undone) in
     * [rgb]. Grows with distance past the focus and is thicker low down
     * (exponential in height below and above the focus); warm looking
     * towards the sun, cool looking away. The GLSL twin is `haze`.
     */
    fun haze(
        t: Terms, distance: Float, focusDistance: Float, wz: Float, focusZ: Float,
        viewX: Float, viewY: Float, rgb: FloatArray,
    ): Float {
        val beyond = max(0f, distance - focusDistance * HAZE_START)
        val low = exp(-(wz - focusZ) * HAZE_FALLOFF).coerceIn(HAZE_LOW_MIN, HAZE_LOW_MAX)
        val amount = min(HAZE_MAX, (1f - exp(-beyond * HAZE_DENSITY)) * low)
        val sl = sqrt(t.sun[0] * t.sun[0] + t.sun[1] * t.sun[1]).coerceAtLeast(1e-4f)
        val vl = sqrt(viewX * viewX + viewY * viewY).coerceAtLeast(1e-4f)
        val towards = smoothstep(-0.6f, 1f, (viewX * t.sun[0] + viewY * t.sun[1]) / (sl * vl))
        for (i in 0 until 3) rgb[i] = t.fog[i] * (HAZE_COOL[i] + (HAZE_WARM[i] - HAZE_COOL[i]) * towards)
        return amount
    }

    /** Emissive after the night glow: [night] 0 at noon, 1 at midnight. */
    fun nightEmissive(emissive: Float, nightGlow: Float, night: Float): Float = emissive * (1f + nightGlow * night)

    /**
     * Exposure, a soft filmic shoulder, saturation and vignette, in place.
     *
     * The shoulder matters more than it looks: without it, every torch and
     * every sunlit bronze face clips to flat white, and the image loses exactly
     * the highlights that make a scene read as lit rather than coloured in.
     */
    fun finish(t: Terms, rgb: FloatArray, screenU: Float, screenV: Float) {
        for (i in 0 until 3) rgb[i] = 1f - exp(-rgb[i] * t.exposure * TONE_GAIN)
        val luma = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        for (i in 0 until 3) rgb[i] = luma + (rgb[i] - luma) * t.saturation
        val dx = screenU - 0.5f; val dy = screenV - 0.5f
        val v = 1f - t.vignette * smoothstep(0.25f, 0.75f, sqrt(dx * dx + dy * dy) * 1.2f)
        for (i in 0 until 3) rgb[i] = (rgb[i] * v).coerceIn(0f, 1f)
    }

    /**
     * Breaking up a repeating texture, the cheap way.
     *
     * A painted tile repeating every two blocks draws a visible grid across a
     * whole field however good the painting is. Every textured pixel is
     * sampled twice — once as laid, once rotated, rescaled and shifted — and
     * the two are blended by a slow noise field in world space, with a second
     * slow field varying the brightness. The eye loses the period; the tile
     * does not need to be any larger, and no extra art is asked for.
     *
     * [out] receives the second sample's u, v, the blend weight towards it,
     * and a brightness multiplier. The GLSL twin is `detile` in the shaders.
     */
    fun detile(u: Float, v: Float, wx: Float, wy: Float, wz: Float, out: FloatArray) {
        val c = DETILE_COS; val sn = DETILE_SIN
        out[0] = (u * c - v * sn) * DETILE_SCALE + DETILE_SHIFT_U
        out[1] = (u * sn + v * c) * DETILE_SCALE + DETILE_SHIFT_V
        // Walls vary along their height as well as their run.
        val px = wx + wz * 0.7f
        val py = wy - wz * 0.7f
        out[2] = smoothstep(0.35f, 0.65f, valueNoise(px / DETILE_CELL, py / DETILE_CELL))
        out[3] = 0.88f + 0.24f * valueNoise(px / TINT_CELL + 17f, py / TINT_CELL + 5f)
    }

    /**
     * How far a ground point leans towards its two variant paintings.
     *
     * Slow patches several blocks across with short edges: a worn clearing
     * here, a lush hollow there, the usual ground between. [out] receives the
     * weights towards variant A and variant B. The GLSL twin is in `main`.
     */
    fun variants(wx: Float, wy: Float, out: FloatArray) {
        val qx = wx / VARIANT_CELL; val qy = wy / VARIANT_CELL
        out[0] = smoothstep(VARIANT_EDGE0, VARIANT_EDGE1, valueNoise(qx + 41f, qy + 7f))
        out[1] = smoothstep(VARIANT_EDGE0, VARIANT_EDGE1, valueNoise(qx * 1.3f - 23f, qy * 1.3f + 61f))
    }

    /** Smooth value noise in 0..1, matching the shader's `vnoise`. */
    fun valueNoise(x: Float, y: Float): Float {
        val ix = kotlin.math.floor(x); val iy = kotlin.math.floor(y)
        val fx = x - ix; val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy); val b = hash(ix + 1f, iy)
        val c = hash(ix, iy + 1f); val d = hash(ix + 1f, iy + 1f)
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
    }

    /** The classic shader hash; matches `hash21` in GLSL closely enough for noise. */
    private fun hash(x: Float, y: Float): Float {
        val h = kotlin.math.sin((x * 127.1f + y * 311.7f).toDouble()) * 43758.5453
        return (h - kotlin.math.floor(h)).toFloat()
    }

    /**
     * How much of a surface point is cut away so the player shows through,
     * 0 (kept) to 1 (gone). Twin of `revealCut` in the GLES shader.
     *
     * The cut is a cylinder around the line from the eye to the middle of
     * the player's body, [Reveal.radius] wide with a soft rim: anything in it,
     * in front of the player and above their feet, is removed. The ground they
     * stand on stays, roofs and hills between them and the camera open up,
     * and everything behind them is left alone.
     */
    fun revealCut(r: Reveal, eyeX: Float, eyeY: Float, eyeZ: Float, wx: Float, wy: Float, wz: Float): Float {
        if (r.radius <= 0f || wz <= r.z + Reveal.FLOOR_CLEARANCE) return 0f
        var dx = r.x - eyeX; var dy = r.y - eyeY; var dz = r.z + Reveal.BODY_CENTRE - eyeZ
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        dx /= len; dy /= len; dz /= len
        val vx = wx - eyeX; val vy = wy - eyeY; val vz = wz - eyeZ
        val t = vx * dx + vy * dy + vz * dz
        if (t >= len - Reveal.BEHIND_MARGIN) return 0f
        val px = vx - dx * t; val py = vy - dy * t; val pz = vz - dz * t
        val off = kotlin.math.sqrt(px * px + py * py + pz * pz)
        return 1f - smoothstep(r.radius - Reveal.FEATHER, r.radius, off)
    }

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun rgb(color: Long, gain: Float) = floatArrayOf(
        ((color ushr 16) and 0xFF) / 255f * gain,
        ((color ushr 8) and 0xFF) / 255f * gain,
        (color and 0xFF) / 255f * gain,
    )

    const val EMISSIVE_GAIN = 1.6f

    /** How tight a clay highlight is, and how bright; the rim sheen's strength. */
    const val CLAY_SHINE = 36f
    const val CLAY_SPEC = 0.55f
    const val CLAY_SHEEN = 0.35f

    /**
     * The glossy clay finish ([Vertex.CLAY]) added to [out] after [shade]:
     * a Blinn highlight from the sun, dimmed in shadow, and a sheen of sky
     * light round the rim. Twin of the clay block in `LIT_FRAGMENT`.
     */
    fun clay(t: Terms, nx: Float, ny: Float, nz: Float, ex: Float, ey: Float, ez: Float, lit: Float, out: FloatArray) {
        var hx = t.sun[0] + ex; var hy = t.sun[1] + ey; var hz = t.sun[2] + ez
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-5f)
        hx /= hl; hy /= hl; hz /= hl
        val spec = Math.pow(max(0f, nx * hx + ny * hy + nz * hz).toDouble(), CLAY_SHINE.toDouble()).toFloat() * CLAY_SPEC * (0.3f + 0.7f * lit)
        val facing = max(0f, nx * ex + ny * ey + nz * ez)
        val sheen = (1f - facing).let { it * it * it } * CLAY_SHEEN
        for (k in 0 until 3) out[k] += t.sunColor[k] * spec + t.sky[k] * sheen
    }

    const val DETILE_COS = 0.8253356f   // cos(0.6)
    const val DETILE_SIN = 0.5646425f   // sin(0.6)
    const val DETILE_SCALE = 0.71f
    const val DETILE_SHIFT_U = 0.37f
    const val DETILE_SHIFT_V = 0.19f
    const val DETILE_CELL = 6f
    const val TINT_CELL = 13f
    const val FLOOR_FACING = 0.7f
    const val WALL_DETAIL_BONUS = 0.25f
    const val VARIANT_CELL = 9f
    const val VARIANT_EDGE0 = 0.5f
    const val VARIANT_EDGE1 = 0.64f
    const val TONE_GAIN = 1.25f
    const val HEIGHT_FOG_DEPTH = 6f
    const val HEIGHT_FOG_MAX = 0.55f

    // The diorama finish; every one of these has a twin in SceneShaders.
    const val VOXELS_PER_BLOCK = 4f
    /** How far inside a surface the grain looks for its voxel, in blocks. */
    const val INSIDE = 0.02f
    const val GRAIN_WARMTH = 0.5f
    /** Keeps hashed cells positive, since GLSL converts to uint. */
    const val HASH_OFFSET = 1 shl 22
    /** Share of a voxel's width, from each edge, that is rounded. */
    const val BEVEL_WIDTH = 0.16f
    const val BEVEL_TILT = 0.55f
    const val BEVEL_FADE_START = 0.3f
    const val BEVEL_FADE_END = 0.6f
    const val AXIS_ALIGNED = 0.95f
    /** How much of the occlusion direct sun obeys at full [DioramaLook.occlusionDepth]. */
    const val OCCLUSION_SUN_SHARE = 0.5f
    /** Haze starts a little in front of the focus, so the hero's own ground stays clear. */
    const val HAZE_START = 0.9f
    const val HAZE_DENSITY = 0.03f
    const val HAZE_FALLOFF = 0.06f
    const val HAZE_LOW_MIN = 0.4f
    const val HAZE_LOW_MAX = 1.5f
    const val HAZE_MAX = 0.5f
    val HAZE_WARM = floatArrayOf(1.1f, 0.98f, 0.82f)
    val HAZE_COOL = floatArrayOf(0.88f, 0.97f, 1.1f)

    // The depth-reading finish: creases, screen occlusion and tilt-shift.
    /** Relative inverse-depth curvature at which a crease is fully dark. */
    const val EDGE_GAIN = 350f
    /** Convex edges, the near side of a silhouette, lighten by this much at most. */
    const val EDGE_LIGHT = 0.18f
    /** Radius of the occlusion ring, in blocks at the pixel's depth. */
    const val SCREEN_AO_RADIUS = 0.45f
    /** Taps are clamped to this relative depth either side, so far silhouettes cast no halo. */
    const val SCREEN_AO_CLAMP = 0.025f
    const val SCREEN_AO_GAIN = 70f
    /** Relative distance from the focus depth where the tilt-shift blur starts, and where it is full. */
    const val TILT_NEAR = 0.1f
    const val TILT_FAR = 0.5f
    /** Eight directions round the occlusion ring, x then y. */
    val RING = floatArrayOf(1f, 0f, 0.7071f, 0.7071f, 0f, 1f, -0.7071f, 0.7071f, -1f, 0f, -0.7071f, -0.7071f, 0f, -1f, 0.7071f, -0.7071f)
    /** Twelve taps of a Vogel disc, radius 1, for the tilt-shift blur. */
    val DISC = FloatArray(24).also { d ->
        for (i in 0 until 12) {
            val r = sqrt((i + 0.5f) / 12f); val a = i * 2.39996323f
            d[i * 2] = r * kotlin.math.cos(a); d[i * 2 + 1] = r * kotlin.math.sin(a)
        }
    }

    // Surfels.
    /** A surfel's depth is pulled this many radii towards the eye, so it is never lost in its own face. */
    const val SURFEL_PULL = 1.5f
    /** Surfels flatter than this to the eye are drawn at this squash, not thinner. */
    const val SURFEL_MIN_SQUASH = 0.3f
    /**
     * Light at a surfel's centre, dark at its rim, even on average: each disc
     * reads as a tiny dome without the whole field of them going darker
     * than the face it grows on. Brightness is `1 + DOME * (0.5 - q)`, q the squared radius.
     */
    const val SURFEL_DOME = 0.3f
    /**
     * How far along its normal a surfel looks up the sun's shadow map. More
     * than a face's offset: a surfel stands a hair proud of its face, and
     * with a face's offset a third of them shadowed themselves into pepper.
     */
    const val SURFEL_SHADOW_OFFSET = 0.12f
    /** Smallest drawn radius, in output pixels. */
    const val SURFEL_MIN_PIXELS = 0.35f
}
