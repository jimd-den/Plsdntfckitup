package com.stratum.engine.scene

import com.stratum.core.domain.art.SceneLighting
import kotlin.math.exp
import kotlin.math.max
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
    ) {
        // Hemisphere ambient: faces up see the sky, faces down see the ground.
        val hemi = nz * 0.5f + 0.5f
        var r = (t.ground[0] + (t.sky[0] - t.ground[0]) * hemi) * ao
        var g = (t.ground[1] + (t.sky[1] - t.ground[1]) * hemi) * ao
        var b = (t.ground[2] + (t.sky[2] - t.ground[2]) * hemi) * ao

        val ndl = max(0f, nx * t.sun[0] + ny * t.sun[1] + nz * t.sun[2])
        val shadow = 1f - t.shadowStrength * (1f - lit)
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

    const val DETILE_COS = 0.8253356f   // cos(0.6)
    const val DETILE_SIN = 0.5646425f   // sin(0.6)
    const val DETILE_SCALE = 0.71f
    const val DETILE_SHIFT_U = 0.37f
    const val DETILE_SHIFT_V = 0.19f
    const val DETILE_CELL = 6f
    const val TINT_CELL = 13f
    const val VARIANT_CELL = 9f
    const val VARIANT_EDGE0 = 0.5f
    const val VARIANT_EDGE1 = 0.64f
    const val TONE_GAIN = 1.25f
    const val HEIGHT_FOG_DEPTH = 6f
    const val HEIGHT_FOG_MAX = 0.55f
}
