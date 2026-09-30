package com.stratum.engine.microvoxel.gen

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministic coordinate hashing. Every random decision in the generators
 * goes through here, keyed by world coordinates and a salt, never by call
 * order -- which is what lets any chunk be generated alone, in any order, on
 * any thread, and come out identical.
 */
object Hash {
    fun mix(seed: Long, a: Int, b: Int = 0, c: Int = 0, salt: Int = 0): Long {
        var h = seed xor (a * -0x61c8864680b583ebL) xor (b * -0x3d4d51c2d82b14b1L) xor
            (c * 0x165667B19E3779F9L) xor (salt * 0x27D4EB2F165667C5L)
        h = h xor (h ushr 30); h *= -0x40a7b892e31b1a47L
        h = h xor (h ushr 27); h *= -0x6b2fb644ecceee15L
        return h xor (h ushr 31)
    }

    /** Uniform in [0, 1). */
    fun unit(seed: Long, a: Int, b: Int = 0, c: Int = 0, salt: Int = 0): Float =
        ((mix(seed, a, b, c, salt) ushr 40).toInt() and 0xFFFFFF) / 16777216f

    fun int(seed: Long, a: Int, b: Int, salt: Int, bound: Int): Int =
        (unit(seed, a, b, 0, salt) * bound).toInt().coerceIn(0, bound - 1)
}

/** A 2D noise sample with its analytic gradient. */
class NoiseSample(var value: Float = 0f, var dx: Float = 0f, var dy: Float = 0f)

/**
 * Gradient-aware value noise and the fractal shapes built from it.
 *
 * Value noise with a quintic fade and analytic derivatives (Quilez, "value
 * noise derivatives"): cheap enough for a phone -- four hashes per sample --
 * and the derivatives are what make [erodedFbm] possible.
 */
class Noise(private val seed: Long) {

    fun value2(x: Float, y: Float, out: NoiseSample = NoiseSample()): NoiseSample {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val ux = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val uy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
        val dux = 30f * fx * fx * (fx * (fx - 2f) + 1f)
        val duy = 30f * fy * fy * (fy * (fy - 2f) + 1f)
        val a = h(xi, yi); val b = h(xi + 1, yi); val c = h(xi, yi + 1); val d = h(xi + 1, yi + 1)
        val k1 = b - a; val k2 = c - a; val k4 = a - b - c + d
        out.value = a + k1 * ux + k2 * uy + k4 * ux * uy
        out.dx = dux * (k1 + k4 * uy)
        out.dy = duy * (k2 + k4 * ux)
        return out
    }

    fun value2(x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
        val a = h(xi, yi); val b = h(xi + 1, yi); val c = h(xi, yi + 1); val d = h(xi + 1, yi + 1)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    fun value3(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val fx = x - xi; val fy = y - yi; val fz = z - zi
        val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy); val uz = fz * fz * (3f - 2f * fz)
        fun layer(zz: Int): Float {
            val a = h(xi, yi, zz); val b = h(xi + 1, yi, zz); val c = h(xi, yi + 1, zz); val d = h(xi + 1, yi + 1, zz)
            return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
        }
        val n = layer(zi)
        return n + (layer(zi + 1) - n) * uz
    }

    /** Plain fractal sum in roughly [-1, 1]. */
    fun fbm(x: Float, y: Float, octaves: Int, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var norm = 0f; var fx = x; var fy = y
        repeat(octaves) { i ->
            sum += amp * value2(fx + i * 17.3f, fy - i * 9.1f)
            norm += amp; amp *= gain; fx *= lacunarity; fy *= lacunarity
        }
        return sum / norm
    }

    /** Sharp ridges -- mountain spines and escarpments -- in [0, 1]. */
    fun ridged(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f; var amp = 0.5f; var fx = x; var fy = y; var weight = 1f
        repeat(octaves) { i ->
            var n = 1f - abs(value2(fx + i * 31.7f, fy + i * 7.9f))
            n *= n * weight
            weight = (n * 2f).coerceIn(0f, 1f)
            sum += n * amp
            amp *= 0.5f; fx *= 2.03f; fy *= 2.03f
        }
        return sum
    }

    /**
     * Fractal noise whose octaves are damped where the landscape is already
     * steep (Quilez's derivative trick). Fine detail collects in valleys and on
     * plateaus while slopes stay clean: the look of erosion at the cost of
     * noise, with no simulation and no neighbours -- so still chunk-local.
     */
    fun erodedFbm(x: Float, y: Float, octaves: Int, erosion: Float = 1f): Float {
        var sum = 0f; var amp = 0.5f; var fx = x; var fy = y
        var gx = 0f; var gy = 0f
        val s = NoiseSample()
        repeat(octaves) { i ->
            value2(fx + i * 13.1f, fy + i * 5.3f, s)
            gx += s.dx; gy += s.dy
            sum += amp * s.value / (1f + erosion * (gx * gx + gy * gy))
            amp *= 0.5f
            val nx = 1.6f * fx - 1.2f * fy; val ny = 1.2f * fx + 1.6f * fy // rotate to kill axis artefacts
            fx = nx; fy = ny
        }
        return sum
    }

    /** Inigo-style domain warp: bends any field into something that looks less like noise. */
    fun warp(x: Float, y: Float, strength: Float, scale: Float): Pair<Float, Float> {
        val wx = fbm(x * scale + 5.2f, y * scale + 1.3f, 3)
        val wy = fbm(x * scale - 3.7f, y * scale + 8.1f, 3)
        return Pair(x + wx * strength, y + wy * strength)
    }

    /** Distance to the nearest jittered feature point, in cell units. Cellular / Worley. */
    fun cellular(x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        var best = 9f
        for (oy in -1..1) for (ox in -1..1) {
            val cx = xi + ox; val cy = yi + oy
            val px = cx + Hash.unit(seed, cx, cy, 0, 71); val py = cy + Hash.unit(seed, cx, cy, 0, 72)
            val dx = px - x; val dy = py - y
            val d = dx * dx + dy * dy
            if (d < best) best = d
        }
        return sqrt(best)
    }

    /** In [-1, 1]. */
    private fun h(x: Int, y: Int, z: Int = 0): Float = Hash.unit(seed, x, y, z, 0x5eed) * 2f - 1f
}
