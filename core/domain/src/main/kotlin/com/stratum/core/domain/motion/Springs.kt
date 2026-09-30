package com.stratum.core.domain.motion

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The arithmetic procedural motion is built from: a critically damped spring,
 * easing curves and deterministic noise.
 *
 * ## Why a critically damped spring, solved exactly
 *
 * Most "smooth follow" code is `x += (target - x) * k * dt`, which is a
 * spring with no velocity: it cannot carry momentum into a turn, and it
 * explodes when `k * dt` passes 2 -- a hitch of one long frame and the body
 * flies off. A critically damped spring is the fastest approach to a target
 * that never overshoots, and its closed-form solution is exact for any time
 * step: a frame of a microsecond and a frame of a second land the body in
 * the same place the same way. That, plus clamping and substepping in
 * [MotionBody], is what makes the layers stable at any frame rate.
 */
object Springs {
    const val TAU = (2 * PI).toFloat()

    /**
     * Advances a critically damped spring from [x] (moving at [v]) towards
     * [target] with natural frequency [omega] over [dt]. Returns the new
     * position; the new velocity is left in [out]`[0]`.
     */
    fun step(x: Float, v: Float, target: Float, omega: Float, dt: Float, out: FloatArray): Float {
        val d = x - target
        val e = exp(-omega * dt)
        val k = v + omega * d
        out[0] = (v - omega * k * dt) * e
        return target + (d + k * dt) * e
    }

    /** The shortest signed turn from angle [from] to [to], in radians, in -pi..pi. */
    fun angleDelta(from: Float, to: Float): Float {
        var d = (to - from) % TAU
        if (d > PI) d -= TAU
        if (d < -PI) d += TAU
        return d
    }

    /** Wraps an angle into -pi..pi, so a body that spun a hundred times still reads its heading exactly. */
    fun wrap(a: Float): Float = angleDelta(0f, a)

    // ---- Easing: t in 0..1 -----------------------------------------------

    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t
    fun easeOutQuad(t: Float): Float { val u = clamp01(t); return 1f - (1f - u) * (1f - u) }
    fun easeInQuad(t: Float): Float { val u = clamp01(t); return u * u }
    fun easeInOutCubic(t: Float): Float { val u = clamp01(t); return if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).let { it * it * it } / 2f }

    /** Overshoots a little and settles: the snap of a blow landing. */
    fun easeOutBack(t: Float): Float {
        val u = clamp01(t); val c1 = 1.70158f; val c3 = c1 + 1f
        val w = u - 1f
        return 1f + c3 * w * w * w + c1 * w * w
    }

    /** 1 at 0, falling to 0 by [length] seconds: an envelope for flashes. */
    fun decay(age: Float, rate: Float): Float = if (age < 0f) 0f else exp(-age * rate)

    /** A smooth hump: 0 at 0 and 1, 1 in the middle. */
    fun hump(t: Float): Float = if (t <= 0f || t >= 1f) 0f else sin(clamp01(t) * PI.toFloat())

    // ---- Noise -------------------------------------------------------------

    /** A well-mixed integer hash, so nearby seeds give unrelated phases. */
    fun hash(x: Int): Int {
        var h = x * -0x61c88647
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        return h xor (h ushr 16)
    }

    /** 0..1 from a seed and a salt. */
    fun unit(seed: Int, salt: Int): Float = (hash(seed * 31 + salt * 7919) and 0xFFFFFF) / 16777215f

    /**
     * Smooth tremor in -1..1: three incommensurate sines with phases from the
     * seed. Deterministic in (seed, time) and cheap; not Perlin, and it does
     * not need to be -- a body's quiver only has to never repeat visibly.
     */
    fun tremor(seed: Int, salt: Int, t: Float, rate: Float): Float {
        val p1 = unit(seed, salt) * TAU; val p2 = unit(seed, salt + 1) * TAU; val p3 = unit(seed, salt + 2) * TAU
        return (sin(t * rate * TAU + p1) * 0.5f + sin(t * rate * 2.13f * TAU + p2) * 0.3f + sin(t * rate * 3.71f * TAU + p3) * 0.2f)
    }
}
