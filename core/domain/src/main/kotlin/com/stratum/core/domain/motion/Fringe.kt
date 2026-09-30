package com.stratum.core.domain.motion

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mask spirit's raffia fringe: a row of ribbons hanging from its lower
 * edge, each a short chain of points that springs after the one above it.
 *
 * This is what makes a floating mask read as a masquerade (mmanwu) rather
 * than a head: the costume below it. It is also the cheapest possible
 * secondary motion -- follow-the-leader springs, no collision, no solver --
 * and it does exactly what cloth has to do here: stream behind a body that
 * moves, whip when it strikes, swing when it turns and hang still when it
 * rests.
 *
 * Each point is chased towards "a segment below its parent" by a critically
 * damped spring ([Springs.step]), looser the further down the chain, then
 * held to a length range so a teleport or a stall cannot stretch a ribbon
 * across the screen. Positions are world blocks, so the scene draws them
 * as they are.
 */
class Fringe(val ribbons: Int = RIBBONS, val points: Int = POINTS) {
    /** x, y, z of every point, ribbon by ribbon, anchor first. */
    val positions = FloatArray(ribbons * points * 3)
    private val velocities = FloatArray(ribbons * points * 3)
    private val omegas = FloatArray(points)
    private val decays = FloatArray(points)
    private var placed = false
    /** How long each ribbon is, in world blocks, as last updated. */
    var length = 0f; private set

    fun reset() { placed = false }

    /**
     * Hangs the anchors from the body's lower edge at [pose] (which is
     * [size] blocks tall) and lets the rest follow for [dt] seconds.
     * [stiffness] and [share] (the fringe's length as a share of the body)
     * come from the profile.
     */
    fun update(pose: MotionPose, size: Float, share: Float, stiffness: Float, dt: Float) {
        val h = size * pose.scale
        length = h * share
        val seg = length / (points - 1)
        for (i in 1 until points) { omegas[i] = stiffness * (1f - 0.1f * i); decays[i] = exp(-omegas[i] * dt) }
        // The body's orientation, as the scene will draw it: yaw, then the top tipped forward, then the roll.
        val cy = cos(pose.yaw); val sy = sin(pose.yaw)
        val cp = cos(-pose.pitch); val sp = sin(-pose.pitch)
        val cr = cos(pose.roll); val sr = sin(pose.roll)
        val m00 = cy * cr - sy * sp * sr; val m01 = -sy * cp; val m02 = cy * sr + sy * sp * cr
        val m10 = sy * cr + cy * sp * sr; val m11 = cy * cp; val m12 = sy * sr - cy * sp * cr
        val m20 = -cp * sr; val m21 = sp; val m22 = cp * cr
        for (r in 0 until ribbons) {
            // Along the lower edge of the mask, a little behind its face, fanned slightly.
            val across = (r / (ribbons - 1f) - 0.5f)
            val lx = across * 0.62f * h; val ly = -0.06f * h; val lz = (-0.36f - 0.06f * (1f - 4f * across * across)) * h
            val o = r * points * 3
            positions[o] = pose.x + m00 * lx + m01 * ly + m02 * lz
            positions[o + 1] = pose.y + m10 * lx + m11 * ly + m12 * lz
            positions[o + 2] = pose.z + m20 * lx + m21 * ly + m22 * lz
            // Spread: the ribbons hang in a slight fan, outer ones splaying out.
            val splayX = (m00 * across) * seg * 0.35f; val splayY = (m10 * across) * seg * 0.35f
            for (i in 1 until points) {
                val p = o + i * 3; val q = p - 3
                val tx = positions[q] + splayX; val ty = positions[q + 1] + splayY; val tz = positions[q + 2] - seg
                if (!placed || !positions[p].isFinite() || !positions[p + 1].isFinite() || !positions[p + 2].isFinite()) {
                    positions[p] = tx; positions[p + 1] = ty; positions[p + 2] = tz
                    velocities[p] = 0f; velocities[p + 1] = 0f; velocities[p + 2] = 0f
                    continue
                }
                // The critically damped spring of [Springs.step], inlined with its decay shared by the level.
                val w = omegas[i]; val e = decays[i]
                for (d in 0 until 3) {
                    val target = if (d == 0) tx else if (d == 1) ty else tz
                    val x = positions[p + d] - target; val v = velocities[p + d]
                    val k = v + w * x
                    velocities[p + d] = (v - w * k * dt) * e
                    positions[p + d] = target + (x + k * dt) * e
                }
                // Hold the segment's length: stretchy raffia looks like chewing gum.
                val dx = positions[p] - positions[q]; val dy = positions[p + 1] - positions[q + 1]; val dz = positions[p + 2] - positions[q + 2]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val lo = seg * 0.7f; val hi = seg * 1.12f
                if (d > hi || d < lo) {
                    val k = (if (d > hi) hi else lo) / d.coerceAtLeast(1e-5f)
                    positions[p] = positions[q] + dx * k; positions[p + 1] = positions[q + 1] + dy * k; positions[p + 2] = positions[q + 2] + dz * k
                }
            }
        }
        placed = true
    }

    companion object {
        const val RIBBONS = 9
        const val POINTS = 5
    }
}
