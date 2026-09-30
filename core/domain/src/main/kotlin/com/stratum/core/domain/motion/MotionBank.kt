package com.stratum.core.domain.motion

/**
 * Every animated body on screen, in a fixed pool: a [MotionBody], its
 * [MotionPose] and (for spirits) its [Fringe], by id.
 *
 * ## The frame
 *
 * ```
 * bank.begin()
 * bank.track("m1", profile, x, y, z, fx, fy)   // every body the simulation has, every frame
 * bank.body("m1")?.strike()                     // events, as they happen
 * bank.advance(dt)                              // springs, layers, fringe
 * bank.poseOf("m1")                             // what to draw
 * ```
 *
 * A body the simulation stops reporting is not dropped on the spot: it is
 * played out through its death (a monster killed and removed the same tick
 * still shatters), then its slot returns to the pool.
 *
 * ## Cost
 *
 * Slots are allocated once, up front; the steady-state frame allocates
 * nothing (a new id costs one map entry the frame it appears). Each body
 * costs the same fixed work whatever it is doing, so a crowd's cost is
 * linear in its size and nothing else -- see the benchmark in the tests.
 */
class MotionBank(
    val capacity: Int = DEFAULT_CAPACITY,
    /** Layers for bodies tracked with a hovering profile; walkers use [walkerRig]. */
    private val spiritRig: MotionRig = MotionRig.SPIRIT,
    private val walkerRig: MotionRig = MotionRig.WALKER,
) {
    private val bodies = Array(capacity) { MotionBody() }
    private val poses = Array(capacity) { MotionPose() }
    private val fringes = Array(capacity) { Fringe() }
    private val sizes = FloatArray(capacity) { 1f }
    private val used = BooleanArray(capacity)
    private val seen = IntArray(capacity)
    private val slots = HashMap<String, Int>(capacity * 2)
    private val free = IntArray(capacity) { capacity - 1 - it }
    private var freeCount = capacity
    private var frame = 0

    /** Bodies in play, including the dying. */
    val size: Int get() = capacity - freeCount

    fun begin() { frame++ }

    /**
     * Reports a body the simulation has this frame: where it truly is and
     * which way it faces. A new id rises into the world ([spawning]) unless
     * told otherwise. Returns null only when the pool is full.
     *
     * [size] is the body's height in world blocks, for the fringe.
     */
    fun track(id: String, profile: MotionProfile, x: Float, y: Float, z: Float, facingX: Float, facingY: Float, size: Float = 1f, spawning: Boolean = true): MotionBody? {
        var slot = slots[id] ?: -1
        if (slot < 0) {
            if (freeCount == 0) return null
            slot = free[--freeCount]
            slots[id] = slot
            used[slot] = true
            bodies[slot].reset(id, profile, x, y, z, facingX, facingY, spawning)
            fringes[slot].reset()
        }
        val b = bodies[slot]
        if (b.profile !== profile) b.profile = profile
        b.track(x, y, z, facingX, facingY)
        sizes[slot] = if (size > 0f && size.isFinite()) size else 1f
        seen[slot] = frame
        return b
    }

    fun body(id: String): MotionBody? = slots[id]?.let { bodies[it] }

    fun poseOf(id: String): MotionPose? = slots[id]?.let { poses[it] }

    fun fringeOf(id: String): Fringe? = slots[id]?.let { s -> fringes[s].takeIf { bodies[s].profile.fringe > 0f } }

    /**
     * Advances every body by [dt] and evaluates its pose and fringe. Bodies
     * not tracked this frame begin to die; the dead are released.
     */
    fun advance(dt: Float) {
        for (s in 0 until capacity) {
            if (!used[s]) continue
            val b = bodies[s]
            if (seen[s] != frame && !b.dying) b.die()
            b.advance(dt)
            if (b.deathDone && seen[s] != frame) { release(s); continue }
            val pose = poses[s]
            val rig = if (b.profile.hover > 0f) spiritRig else walkerRig
            rig.evaluate(b, pose)
            if (b.profile.fringe > 0f) {
                val step = if (dt.isFinite() && dt > 0f) minOf(dt, MotionBody.MAX_STEP) else 0f
                fringes[s].update(pose, sizes[s], b.profile.fringe, b.profile.fringeStiffness, step)
            }
        }
    }

    /** Calls [visit] for every body in play, with its slot (for [fringeAt]) and pose; the order is stable (by slot). */
    inline fun forEach(visit: (slot: Int, body: MotionBody, pose: MotionPose) -> Unit) {
        for (s in 0 until capacity) if (isUsed(s)) visit(s, bodyAt(s), poseAt(s))
    }

    @PublishedApi internal fun isUsed(s: Int) = used[s]
    @PublishedApi internal fun bodyAt(s: Int) = bodies[s]
    @PublishedApi internal fun poseAt(s: Int) = poses[s]
    fun fringeAt(s: Int): Fringe = fringes[s]

    /** The height in blocks a slot's body was last tracked with. */
    fun sizeAt(s: Int): Float = sizes[s]

    fun clear() {
        for (s in 0 until capacity) if (used[s]) release(s)
    }

    private fun release(s: Int) {
        slots.remove(bodies[s].id)
        used[s] = false
        free[freeCount++] = s
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
