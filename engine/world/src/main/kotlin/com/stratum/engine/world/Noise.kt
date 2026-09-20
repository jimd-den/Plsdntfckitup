package com.stratum.engine.world

import kotlin.math.floor

/**
 * Seeded value noise with fractal octaves.
 *
 * Deterministic by construction: the value at a coordinate is derived from the
 * coordinate and the seed alone, with no iteration order or state involved. That
 * is what lets a chunk be generated, discarded and regenerated identically when
 * the player walks back.
 */
class ValueNoise(private val seed: Long) {

    fun at(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi

        val topLeft = hash(xi, yi)
        val topRight = hash(xi + 1, yi)
        val bottomLeft = hash(xi, yi + 1)
        val bottomRight = hash(xi + 1, yi + 1)

        val u = smoothstep(xf)
        val v = smoothstep(yf)

        val top = lerp(topLeft, topRight, u)
        val bottom = lerp(bottomLeft, bottomRight, u)
        return lerp(top, bottom, v)
    }

    /** 3D variant, used to carve caves rather than shape a surface. */
    fun at(x: Float, y: Float, z: Float): Float {
        val zi = floor(z).toInt()
        val zf = smoothstep(z - zi)
        val near = layered(x, y, zi)
        val far = layered(x, y, zi + 1)
        return lerp(near, far, zf)
    }

    private fun layered(x: Float, y: Float, zLayer: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val u = smoothstep(x - xi)
        val v = smoothstep(y - yi)
        val top = lerp(hash(xi, yi, zLayer), hash(xi + 1, yi, zLayer), u)
        val bottom = lerp(hash(xi, yi + 1, zLayer), hash(xi + 1, yi + 1, zLayer), u)
        return lerp(top, bottom, v)
    }

    /**
     * Sums octaves at halving amplitude, giving broad landforms with fine detail
     * instead of uniform bumpiness. Result stays in 0..1.
     */
    fun fractal(x: Float, y: Float, octaves: Int = 4, frequency: Float = 1f, persistence: Float = 0.5f): Float {
        var amplitude = 1f
        var currentFrequency = frequency
        var total = 0f
        var normaliser = 0f
        repeat(octaves) {
            total += at(x * currentFrequency, y * currentFrequency) * amplitude
            normaliser += amplitude
            amplitude *= persistence
            currentFrequency *= 2f
        }
        return if (normaliser == 0f) 0f else total / normaliser
    }

    private fun hash(x: Int, y: Int, z: Int = 0): Float {
        var h = seed xor (x * 0x1F1F1F1FL) xor (y * 0x5BD1E995L) xor (z * 0x27D4EB2FL)
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        h *= -0x3b314601e57a13adL
        h = h xor (h ushr 32)
        // Take the top 24 bits: the low bits of a multiply-shift hash are the
        // weakest, and 24 bits is ample resolution for terrain.
        return ((h ushr 40).toInt() and 0xFFFFFF) / 0xFFFFFF.toFloat()
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * Small deterministic RNG for per-position decisions such as "does a tree stand
 * here". Seeded from the coordinate so it does not depend on call order.
 */
object PositionalRandom {
    fun floatAt(seed: Long, x: Int, y: Int, salt: Int): Float {
        var h = seed xor (x * 0x9E3779B97F4A7C15uL.toLong()) xor
            (y * 0xC2B2AE3D27D4EB4FuL.toLong()) xor (salt * 0x165667B19E3779F9uL.toLong())
        h = h xor (h ushr 30)
        h *= -0x40a7b892e31b1a47L
        h = h xor (h ushr 27)
        h *= -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return ((h ushr 40).toInt() and 0xFFFFFF) / 0xFFFFFF.toFloat()
    }

    fun intAt(seed: Long, x: Int, y: Int, salt: Int, bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return (floatAt(seed, x, y, salt) * bound).toInt().coerceIn(0, bound - 1)
    }
}
