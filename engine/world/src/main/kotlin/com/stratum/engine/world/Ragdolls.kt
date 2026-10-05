package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A fallen body's pose: [points] holds x, y, z for each of the
 * [Ragdolls.POINTS] joints, in [Ragdolls.Joint] order.
 */
class RagdollPose(val id: String, val points: FloatArray, val color: Long, val scale: Float, val alpha: Float)

/**
 * Bodies that fall loose: a dying monster crumples and tumbles away from the
 * blow that felled it, rolls over the ground's steps and lies still, then
 * fades.
 *
 * Each body is eleven points held at their bone lengths by constraints
 * (Verlet integration): no rigid-body solver, nothing that can explode,
 * and a handful of multiplications per point per step. At most [max] lie
 * about at once; the oldest goes first.
 */
class Ragdolls(private val world: World, private val max: Int = 12) {

    /** The joints, in the order a pose stores them. */
    enum class Joint { HEAD, CHEST, PELVIS, L_ELBOW, L_HAND, R_ELBOW, R_HAND, L_KNEE, L_FOOT, R_KNEE, R_FOOT }

    private class Body(val id: String, val pos: FloatArray, val prev: FloatArray, val rest: FloatArray, val color: Long, val scale: Float, var age: Float = 0f)

    private val bodies = ArrayList<Body>()

    val count: Int get() = bodies.size

    /**
     * Lays down a body standing at [at] facing ([facingX], [facingY]), struck
     * with a push of ([pushX], [pushY]) blocks a second, as tall as [scale]
     * times a person.
     */
    fun spawn(id: String, at: WorldPoint, facingX: Float, facingY: Float, pushX: Float, pushY: Float, color: Long, scale: Float = 1f) {
        if (bodies.any { it.id == id }) return
        if (bodies.size >= max) bodies.removeAt(0)
        val fl = sqrt(facingX * facingX + facingY * facingY).takeIf { it > 1e-4f } ?: 1f
        val fx = facingX / fl; val fy = facingY / fl
        // Right is the facing turned a quarter.
        val rx = fy; val ry = -fx
        val pos = FloatArray(POINTS * 3)
        STANDING.forEachIndexed { i, (side, up) ->
            pos[i * 3] = at.x + rx * side * scale
            pos[i * 3 + 1] = at.y + ry * side * scale
            pos[i * 3 + 2] = at.z + up * scale
        }
        // The first step's motion is the blow: stronger at the chest and head, so the body folds and topples.
        val prev = pos.copyOf()
        for (j in 0 until POINTS) {
            val weight = if (j <= Joint.CHEST.ordinal) 1f else if (j == Joint.PELVIS.ordinal) 0.7f else 0.45f
            prev[j * 3] -= pushX * weight * KICK_DT
            prev[j * 3 + 1] -= pushY * weight * KICK_DT
            prev[j * 3 + 2] -= LIFT * weight * KICK_DT
        }
        val rest = FloatArray(BONES.size) { b -> distance(pos, BONES[b].first, BONES[b].second) }
        bodies += Body(id, pos, prev, rest, color, scale)
    }

    fun advance(deltaSeconds: Float) {
        if (bodies.isEmpty() || deltaSeconds <= 0f) return
        val dt = deltaSeconds.coerceAtMost(MAX_DT)
        bodies.removeAll { b -> b.age += deltaSeconds; b.age >= LIFETIME }
        for (b in bodies) {
            integrate(b, dt)
            repeat(ITERATIONS) { relax(b); collide(b) }
        }
    }

    fun clear() = bodies.clear()

    fun poses(): List<RagdollPose> = bodies.map { b ->
        val fade = ((LIFETIME - b.age) / FADE).coerceIn(0f, 1f)
        RagdollPose(b.id, b.pos.copyOf(), b.color, b.scale, fade)
    }

    private fun integrate(b: Body, dt: Float) {
        for (j in 0 until POINTS) {
            val i = j * 3
            val x = b.pos[i]; val y = b.pos[i + 1]; val z = b.pos[i + 2]
            b.pos[i] = x + (x - b.prev[i]) * DAMPING
            b.pos[i + 1] = y + (y - b.prev[i + 1]) * DAMPING
            b.pos[i + 2] = z + (z - b.prev[i + 2]) * DAMPING - GRAVITY * dt * dt
            b.prev[i] = x; b.prev[i + 1] = y; b.prev[i + 2] = z
        }
    }

    /** Pulls each bone back toward its length, half the error to each end. */
    private fun relax(b: Body) {
        for (k in BONES.indices) {
            val (a, c) = BONES[k]
            val ia = a * 3; val ic = c * 3
            val dx = b.pos[ic] - b.pos[ia]; val dy = b.pos[ic + 1] - b.pos[ia + 1]; val dz = b.pos[ic + 2] - b.pos[ia + 2]
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-5f)
            val diff = (d - b.rest[k]) / d * 0.5f * STIFFNESS[k]
            b.pos[ia] += dx * diff; b.pos[ia + 1] += dy * diff; b.pos[ia + 2] += dz * diff
            b.pos[ic] -= dx * diff; b.pos[ic + 1] -= dy * diff; b.pos[ic + 2] -= dz * diff
        }
    }

    /** Keeps every joint out of solid blocks, with friction against the ground it lies on. */
    private fun collide(b: Body) {
        for (j in 0 until POINTS) {
            val i = j * 3
            val x = b.pos[i]; val y = b.pos[i + 1]; val z = b.pos[i + 2]
            if (z >= Chunk.HEIGHT) continue
            val cell = BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z - RADIUS).toInt())
            if (cell.z < 0 || world.isSolid(cell)) {
                b.pos[i + 2] = cell.z + 1f + RADIUS
                // Ground friction: most of the sideways motion is lost on contact.
                b.prev[i] = x - (x - b.prev[i]) * GROUND_FRICTION
                b.prev[i + 1] = y - (y - b.prev[i + 1]) * GROUND_FRICTION
                if (b.prev[i + 2] < b.pos[i + 2]) b.prev[i + 2] = b.pos[i + 2]
            }
        }
    }

    private fun distance(p: FloatArray, a: Int, c: Int): Float {
        val dx = p[c * 3] - p[a * 3]; val dy = p[c * 3 + 1] - p[a * 3 + 1]; val dz = p[c * 3 + 2] - p[a * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        val POINTS = Joint.entries.size

        /** Each joint standing: (sideways, up), in a person's units. */
        private val STANDING = listOf(
            0f to 1.65f, 0f to 1.3f, 0f to 0.9f,
            -0.3f to 1.05f, -0.35f to 0.75f, 0.3f to 1.05f, 0.35f to 0.75f,
            -0.13f to 0.45f, -0.13f to 0.02f, 0.13f to 0.45f, 0.13f to 0.02f,
        )

        /** The bones, and two braces that keep the trunk and the hips from folding flat. */
        val BONES: List<Pair<Int, Int>> = listOf(
            0 to 1, 1 to 2, 1 to 3, 3 to 4, 1 to 5, 5 to 6, 2 to 7, 7 to 8, 2 to 9, 9 to 10,
            0 to 2, 7 to 9, 3 to 5,
        )
        private val STIFFNESS = FloatArray(BONES.size) { if (it >= 10) 0.35f else 1f }

        const val LIFETIME = 6f
        private const val FADE = 1.5f
        private const val GRAVITY = 22f
        private const val DAMPING = 0.985f
        private const val ITERATIONS = 6
        private const val RADIUS = 0.08f
        private const val GROUND_FRICTION = 0.35f
        private const val MAX_DT = 1f / 30f
        private const val KICK_DT = 1f / 30f
        private const val LIFT = 3.5f
    }
}
