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
    const val TONE_GAIN = 1.25f
    const val HEIGHT_FOG_DEPTH = 6f
    const val HEIGHT_FOG_MAX = 0.55f
}
