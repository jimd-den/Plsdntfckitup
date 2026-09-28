package com.stratum.engine.model

import kotlin.math.roundToInt

/** ARGB arithmetic on plain ints, so no stage allocates a colour object per texel. */
internal object Colors {

    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c ushr 16) and 0xFF
    fun g(c: Int) = (c ushr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Channel by channel, as glTF combines a factor with a texture and a vertex colour. */
    fun multiply(x: Int, y: Int): Int =
        argb(a(x) * a(y) / 255, r(x) * r(y) / 255, g(x) * g(y) / 255, b(x) * b(y) / 255)

    fun blend3(c0: Int, c1: Int, c2: Int, w0: Float, w1: Float, w2: Float): Int {
        fun ch(shift: Int) = (((c0 ushr shift) and 0xFF) * w0 + ((c1 ushr shift) and 0xFF) * w1 + ((c2 ushr shift) and 0xFF) * w2).roundToInt()
        return argb(ch(24), ch(16), ch(8), ch(0))
    }

    /** The factor glTF gives as four floats, 0..1. */
    fun fromFloats(r: Float, g: Float, b: Float, a: Float): Int =
        argb((a * 255).roundToInt(), (r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())

    /** Scales the colour channels, keeping alpha; used for lighting a baked sprite. */
    fun shade(c: Int, light: Float): Int =
        argb(a(c), (r(c) * light).roundToInt(), (g(c) * light).roundToInt(), (b(c) * light).roundToInt())

    /**
     * How different two colours look, roughly.
     *
     * The "redmean" weighting rather than plain RGB distance: it costs nothing
     * and stops a mossy green voxel matching a grey stone because the two share
     * a blue channel.
     */
    fun distance(x: Int, y: Int): Float {
        val rMean = (r(x) + r(y)) / 2f
        val dr = (r(x) - r(y)).toFloat(); val dg = (g(x) - g(y)).toFloat(); val db = (b(x) - b(y)).toFloat()
        return (2f + rMean / 256f) * dr * dr + 4f * dg * dg + (2f + (255f - rMean) / 256f) * db * db
    }
}
