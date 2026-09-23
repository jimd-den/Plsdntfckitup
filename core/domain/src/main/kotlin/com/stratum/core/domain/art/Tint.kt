package com.stratum.core.domain.art

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Colour arithmetic on packed `0xAARRGGBB` longs.
 *
 * Packed longs rather than a colour class because that is what blocks, packs
 * and palettes already store, and because the art layer has to run in pure
 * Kotlin: `androidx.compose.ui.graphics.Color` cannot be imported here, and a
 * parallel colour type would only have to be converted at every boundary.
 *
 * Everything is a pure function of its inputs, so a style can be tested by
 * asserting on numbers instead of by looking at a screenshot.
 */
object Tint {

    const val OPAQUE = 0xFF000000L

    fun alpha(color: Long): Int = ((color ushr 24) and 0xFF).toInt()

    fun red(color: Long): Int = ((color ushr 16) and 0xFF).toInt()

    fun green(color: Long): Int = ((color ushr 8) and 0xFF).toInt()

    fun blue(color: Long): Int = (color and 0xFF).toInt()

    fun argb(a: Int, r: Int, g: Int, b: Int): Long =
        (clamp(a).toLong() shl 24) or
            (clamp(r).toLong() shl 16) or
            (clamp(g).toLong() shl 8) or
            clamp(b).toLong()

    fun withAlpha(color: Long, alpha: Float): Long =
        argb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), red(color), green(color), blue(color))

    /** Perceptual brightness, Rec. 601 weights. Used wherever "how dark is this" matters. */
    fun luma(color: Long): Float =
        (0.299f * red(color) + 0.587f * green(color) + 0.114f * blue(color)) / 255f

    /** Multiplies the colour channels, leaving alpha alone. The shading primitive. */
    fun scale(color: Long, factor: Float): Long {
        val f = factor.coerceAtLeast(0f)
        return argb(
            alpha(color),
            (red(color) * f).roundToInt(),
            (green(color) * f).roundToInt(),
            (blue(color) * f).roundToInt(),
        )
    }

    /** Linear blend; [amount] of 0 keeps [from], 1 returns [to]. */
    fun mix(from: Long, to: Long, amount: Float): Long {
        val t = amount.coerceIn(0f, 1f)
        return argb(
            alpha(from) + ((alpha(to) - alpha(from)) * t).roundToInt(),
            red(from) + ((red(to) - red(from)) * t).roundToInt(),
            green(from) + ((green(to) - green(from)) * t).roundToInt(),
            blue(from) + ((blue(to) - blue(from)) * t).roundToInt(),
        )
    }

    /**
     * Pushes a colour towards or away from its own grey.
     *
     * This is the single most important operation in the whole art layer. The
     * screenshot problem is not that the terrain is the wrong hue, it is that
     * terrain, props, loot and monsters are all equally colourful, so nothing
     * wins the eye. Desaturating the ground is what buys the contrast that the
     * player, the ore and the threat then spend.
     */
    fun saturate(color: Long, amount: Float): Long {
        val grey = (luma(color) * 255f).roundToInt()
        return argb(
            alpha(color),
            grey + ((red(color) - grey) * amount).roundToInt(),
            grey + ((green(color) - grey) * amount).roundToInt(),
            grey + ((blue(color) - grey) * amount).roundToInt(),
        )
    }

    /**
     * Warms or cools a colour without changing how bright it reads.
     *
     * Positive pushes red up and blue down, negative the reverse, and green is
     * left where it is. Warm light with cool shadow is most of what separates
     * an illustrated scene from a flat one, and doing it as a channel tilt
     * rather than a hue rotation keeps the pack's own colours recognisable:
     * a bronze shrine under cold moonlight should still be bronze.
     */
    fun temperature(color: Long, amount: Float): Long {
        val shift = (amount.coerceIn(-1f, 1f) * TEMPERATURE_RANGE)
        return argb(
            alpha(color),
            red(color) + shift.roundToInt(),
            green(color) + (shift * 0.25f).roundToInt(),
            blue(color) - shift.roundToInt(),
        )
    }

    /**
     * Squeezes a colour's brightness into a band.
     *
     * Terrain is clamped into a mid band so that no patch of ground is ever as
     * dark as an outline or as bright as a rim light. That is what makes the
     * contrast contract enforceable rather than aspirational: however garish a
     * generated pack's palette is, its ground still cannot outshout an actor.
     */
    fun clampLuma(color: Long, floor: Float, ceiling: Float): Long {
        val luma = luma(color)
        if (luma in floor..ceiling) return color
        val target = luma.coerceIn(floor, ceiling)
        if (luma <= 0.001f) {
            val flat = (target * 255f).roundToInt()
            return argb(alpha(color), flat, flat, flat)
        }
        return scale(color, target / luma)
    }

    /**
     * Snaps a colour to the nearest entry of a ramp.
     *
     * Quantisation is what makes an image read as painted rather than rendered:
     * a small palette forces flat planes and hard transitions, which is the
     * shape language the whole style rests on. With an empty ramp this is the
     * identity, so a style that wants smooth shading simply ships no ramp.
     */
    fun quantize(color: Long, ramp: List<Long>): Long {
        if (ramp.isEmpty()) return color
        var best = ramp.first()
        var bestDistance = Float.MAX_VALUE
        ramp.forEach { candidate ->
            val distance = abs(red(color) - red(candidate)) * 0.4f +
                abs(green(color) - green(candidate)) * 0.45f +
                abs(blue(color) - blue(candidate)) * 0.15f
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return argb(alpha(color), red(best), green(best), blue(best))
    }

    /** Blends towards a colour by a share, the fog and haze primitive. */
    fun veil(color: Long, veil: Long, strength: Float): Long = mix(color, veil, strength)

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)

    /** How far a full-strength temperature shift moves a channel, in 0..255 steps. */
    private const val TEMPERATURE_RANGE = 34f
}
