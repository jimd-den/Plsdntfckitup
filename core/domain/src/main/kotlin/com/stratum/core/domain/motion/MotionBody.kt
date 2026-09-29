package com.stratum.core.domain.motion

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * One animated body's motion state: where it is drawn, which way it faces,
 * and the clocks of everything that has happened to it (a strike, a cast, a
 * hit, a crit, its birth and its death).
 *
 * ## What it owns and what it does not
 *
 * The gameplay position is an *input*: the simulation says where the body
 * truly is and this never changes it. The body keeps its own drawn position
 * and heading, which chase the true ones through critically damped springs
 * ([Springs.step]) so movement has weight and turns have follow-through --
 * and never lag by more than [MotionProfile.MAX_LAG], so what the player
 * sees is always what they can hit.
 *
 * Events only start clocks. What a clock *looks like* -- a lunge, a whirl, a
 * flinch -- is decided by the [MotionLayer]s reading it, which is what makes
 * motion composable: swap a layer and every body that uses it moves
 * differently, and the state underneath does not change.
 *
 * ## Robustness
 *
 * [advance] is safe with any time step: a non-finite or negative one is
 * ignored, a huge one is clamped to [MAX_STEP] (a stall is a pause, not a
 * teleport), and the rest is cut into substeps of at most [SUBSTEP]. Inputs
 * are checked as they arrive, and if anything ever does go non-finite the
 * body snaps to its true position instead of vanishing. Given the same id,
 * inputs and time steps it produces the same motion, bit for bit.
 *
 * Reused by [MotionBank]: [reset] makes it a fresh body without allocating.
 */
class MotionBody {
    var id: String = ""
        private set
    /** Mixed from the id: every phase and tremor is derived from it, so two bodies never move in lockstep. */
    var seed: Int = 0
        private set
    var profile: MotionProfile = MotionProfile.DEFAULT

    /** Whatever the caller hangs on this body (the scene hangs its mesh here); cleared by [reset]. */
    var tag: Any? = null

    // ---- Inputs: the truth, set every frame -------------------------------
    var trueX = 0f; private set
    var trueY = 0f; private set
    var trueZ = 0f; private set
    var facingYaw = 0f; private set
    var aimX = 0f; private set
    var aimY = 0f; private set
    var aimZ = 0f; private set
    var hasAim = false; private set
    /** How hard the body is being shoved right now, 0..1 (see ImpactField). */
    var impact = 0f

    // ---- The drawn body ----------------------------------------------------
    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set
    private var vx = 0f; private var vy = 0f; private var vz = 0f
    var yaw = 0f; private set
    var yawSpeed = 0f; private set
    /** The true position's velocity, smoothed: what lean and bounce read. */
    var speedX = 0f; private set
    var speedY = 0f; private set
    val speed: Float get() = sqrt(speedX * speedX + speedY * speedY)

    /** Seconds this body has existed: the clock every idle rhythm runs on. */
    var time = 0f; private set

    // ---- Event clocks (seconds since, or -1 for never) --------------------
    var strikeAge = -1f; private set
    var strikeStyle = StrikeStyle.LUNGE; private set
    var strikeDirX = 0f; private set
    var strikeDirY = 1f; private set
    /** 0..1+: how hard, so a heavy blow throws further and burns brighter. */
    var strikePower = 1f; private set
    var castAge = -1f; private set
    var hitAge = -1f; private set
    var hitDirX = 0f; private set
    var hitDirY = 0f; private set
    var hitPower = 0f; private set
    var critAge = -1f; private set
    var blockAge = -1f; private set
    var spawnAge = -1f; private set
    var deathAge = -1f; private set
    val dying: Boolean get() = deathAge >= 0f

    /** The flinch: a displacement along the hit, sprung back to rest. */
    var flinch = 0f; private set
    private var flinchSpeed = 0f

    private var lastDesiredYaw = 0f
    private var placed = false
    private val spring = FloatArray(1)

    /** Makes this a fresh body for [id] standing at its true position; nothing allocated. */
    fun reset(id: String, profile: MotionProfile, x: Float, y: Float, z: Float, facingX: Float = 0f, facingY: Float = 1f, spawning: Boolean = true) {
        this.id = id
        seed = Springs.hash(id.hashCode())
        this.profile = profile
        tag = null
        trueX = x; trueY = y; trueZ = z
        this.x = x; this.y = y; this.z = z; vx = 0f; vy = 0f; vz = 0f
        facingYaw = yawOf(facingX, facingY); yaw = facingYaw; yawSpeed = 0f; lastDesiredYaw = facingYaw
        speedX = 0f; speedY = 0f; time = 0f; impact = 0f
        strikeAge = -1f; castAge = -1f; hitAge = -1f; critAge = -1f; blockAge = -1f; deathAge = -1f
        spawnAge = if (spawning) 0f else -1f
        flinch = 0f; flinchSpeed = 0f; hasAim = false
        placed = true
    }

    /** Where the simulation says the body is and which way it faces. Non-finite values are ignored. */
    fun track(x: Float, y: Float, z: Float, facingX: Float, facingY: Float) {
        if (x.isFinite() && y.isFinite() && z.isFinite()) { trueX = x; trueY = y; trueZ = z }
        if (facingX.isFinite() && facingY.isFinite() && (facingX * facingX + facingY * facingY) > 1e-6f) facingYaw = yawOf(facingX, facingY)
    }

    /** What it is looking at, or [clearAim] for nothing. */
    fun aim(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        aimX = x; aimY = y; aimZ = z; hasAim = true
    }

    fun clearAim() { hasAim = false }

    // ---- Events ------------------------------------------------------------

    /** Begins a strike at the aim (or straight ahead), [power] 1 for an ordinary blow. */
    fun strike(power: Float = 1f, style: StrikeStyle = profile.strike) {
        if (dying) return
        var dx = if (hasAim) aimX - x else -kotlin.math.sin(facingYaw)
        var dy = if (hasAim) aimY - y else kotlin.math.cos(facingYaw)
        val l = sqrt(dx * dx + dy * dy)
        if (l < 1e-4f) { dx = -kotlin.math.sin(yaw); dy = kotlin.math.cos(yaw) } else { dx /= l; dy /= l }
        strikeDirX = dx; strikeDirY = dy
        strikePower = if (power.isFinite()) power.coerceIn(0.2f, 2f) else 1f
        strikeStyle = style
        strikeAge = 0f
    }

    /** Begins a cast: the eyes flare and the body rises into it. */
    fun cast() { if (!dying) castAge = 0f }

    /** Takes a hit from the direction of ([fromX], [fromY]); [power] 0..1+. */
    fun hit(fromX: Float, fromY: Float, power: Float = 1f) {
        if (dying) return
        var dx = x - fromX; var dy = y - fromY
        val l = sqrt(dx * dx + dy * dy)
        if (!l.isFinite() || l < 1e-4f) { dx = kotlin.math.sin(yaw); dy = -kotlin.math.cos(yaw) } else { dx /= l; dy /= l }
        hitDirX = dx; hitDirY = dy
        hitPower = if (power.isFinite()) power.coerceIn(0f, 2f) else 1f
        hitAge = 0f
        // Kicked, not placed: the flinch spring takes a velocity and springs back.
        flinchSpeed += FLINCH_KICK * hitPower * profile.flinch
    }

    /** A critical landed (by this body): the eyes flash. */
    fun crit() { if (!dying) critAge = 0f }

    /** A blow was turned aside: a shield of light. */
    fun block() { if (!dying) blockAge = 0f }

    /** Begins dying; the body keeps drawing until [deathDone]. */
    fun die() { if (!dying) deathAge = 0f }

    val deathDone: Boolean get() = dying && deathAge >= profile.death

    // ---- Time ----------------------------------------------------------------

    /** Advances everything by [dt] seconds; see the class notes for why any [dt] is safe. */
    fun advance(dt: Float) {
        if (!placed) return
        if (!dt.isFinite() || dt <= 0f) return
        val step = if (dt > MAX_STEP) MAX_STEP else dt
        val n = ceil(step / SUBSTEP).toInt().coerceIn(1, MAX_SUBSTEPS)
        val h = step / n

        // The true velocity, from how far the truth moved this frame; smoothed so a jittery tick does not rattle the lean.
        val p = profile
        val desiredX = trueX; val desiredY = trueY; val desiredZ = trueZ + p.hover
        // Anticipation: face where the movement is taking it, a beat ahead of the facing the simulation reports.
        val moving = speed > MOVING
        val desiredYaw = when {
            (strikeAge >= 0f && strikeAge < p.strikeLength) -> yawOf(strikeDirX, strikeDirY)
            hasAim && (castAge >= 0f && castAge < CAST_LENGTH) -> yawOf(aimX - x, aimY - y)
            moving -> yawOf(speedX, speedY)
            else -> facingYaw
        }
        val turning = Springs.angleDelta(lastDesiredYaw, desiredYaw) / step
        lastDesiredYaw = desiredYaw
        val lead = (turning.coerceIn(-TURN_LEAD_CAP, TURN_LEAD_CAP)) * p.anticipate

        val lastX = x; val lastY = y
        repeat(n) {
            x = Springs.step(x, vx, desiredX, p.follow, h, spring); vx = spring[0]
            y = Springs.step(y, vy, desiredY, p.follow, h, spring); vy = spring[0]
            z = Springs.step(z, vz, desiredZ, p.follow, h, spring); vz = spring[0]
            // The yaw spring works on the short way round, so it never spins the long way to a heading.
            val target = yaw + Springs.angleDelta(yaw, desiredYaw + lead)
            yaw = Springs.step(yaw, yawSpeed, target, p.turn, h, spring); yawSpeed = spring[0]
            flinch = Springs.step(flinch, flinchSpeed, 0f, FLINCH_SPRING, h, spring); flinchSpeed = spring[0]
        }
        yaw = Springs.wrap(yaw)

        // Never let the drawing trail the truth by more than a hand's width.
        val lx = x - trueX; val ly = y - trueY; val lag = sqrt(lx * lx + ly * ly)
        if (lag > MotionProfile.MAX_LAG) {
            val k = MotionProfile.MAX_LAG / lag
            x = trueX + lx * k; y = trueY + ly * k
        }
        val mvx = (x - lastX) / step; val mvy = (y - lastY) / step
        val blend = 1f - kotlin.math.exp(-step * SPEED_SMOOTHING)
        speedX += (mvx - speedX) * blend; speedY += (mvy - speedY) * blend

        time += step
        if (strikeAge >= 0f) strikeAge += step
        if (castAge >= 0f) castAge += step
        if (hitAge >= 0f) hitAge += step
        if (critAge >= 0f) critAge += step
        if (blockAge >= 0f) blockAge += step
        if (spawnAge >= 0f) { spawnAge += step; if (spawnAge > p.spawn + 1f) spawnAge = -1f }
        if (deathAge >= 0f) deathAge += step
        // Old clocks stop, so a body idle for an hour is not carrying huge ages.
        if (strikeAge > p.strikeLength + 1f) strikeAge = -1f
        if (castAge > CAST_LENGTH + 1f) castAge = -1f
        if (hitAge > 2f) hitAge = -1f
        if (critAge > 2f) critAge = -1f
        if (blockAge > 2f) blockAge = -1f
        if (time > TIME_WRAP) time -= TIME_WRAP

        if (!sane()) {
            // Something upstream fed nonsense: stand the body at its truth rather than lose it.
            x = trueX; y = trueY; z = trueZ + p.hover; vx = 0f; vy = 0f; vz = 0f
            yaw = if (facingYaw.isFinite()) facingYaw else 0f; yawSpeed = 0f; flinch = 0f; flinchSpeed = 0f
            speedX = 0f; speedY = 0f
        }
    }

    private fun sane(): Boolean = x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && yawSpeed.isFinite() &&
        vx.isFinite() && vy.isFinite() && vz.isFinite() && flinch.isFinite() && flinchSpeed.isFinite() && speedX.isFinite() && speedY.isFinite()

    companion object {
        /** A frame longer than this is a stall: motion pauses rather than jumping. */
        const val MAX_STEP = 0.25f
        /** Substeps are at most this long, so fast targets are chased smoothly. */
        const val SUBSTEP = 1f / 60f
        const val MAX_SUBSTEPS = 16
        /** Seconds a cast pose lasts. */
        const val CAST_LENGTH = 0.55f
        const val MOVING = 0.35f
        const val SPEED_SMOOTHING = 10f
        const val FLINCH_KICK = 5f
        const val FLINCH_SPRING = 16f
        const val TURN_LEAD_CAP = 8f
        /** The idle clock wraps after this long, so its sines keep their precision. */
        const val TIME_WRAP = 3600f

        /** Yaw of a facing: 0 faces +y, turning counter-clockwise (the spirit mesh's convention). */
        fun yawOf(dx: Float, dy: Float): Float = if (dx * dx + dy * dy < 1e-10f) 0f else atan2(-dx, dy)
    }
}
