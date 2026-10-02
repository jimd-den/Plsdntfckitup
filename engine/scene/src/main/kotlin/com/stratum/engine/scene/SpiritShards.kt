package com.stratum.engine.scene

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a piece of a broken mask is, which decides how it moves: brows lift
 * when the spirit casts, the jaw drops when it shouts, the crest floats
 * above, embers swirl, relics circle.
 */
enum class ShardRole {
    /** The face that stays whole when only the crown or the jaw breaks away. */
    FACE,
    CREST, BROW_LEFT, BROW_RIGHT, CHEEK_LEFT, CHEEK_RIGHT,
    /** The nose and the plane between the eyes. */
    BRIDGE,
    JAW,
    /** The two halves of a face split down its middle. */
    HALF_LEFT, HALF_RIGHT,
    /** A piece of a broken crown, comb or superstructure. */
    CROWN,
    /** A small fragment of a jaw broken to embers. */
    EMBER,
    /** A horn broken from the head, hovering where it grew. */
    RELIC,
    /** Plaques, bells and discs circling the head. */
    ORBIT,
    /** Raffia trailing beneath the jaw. */
    SHROUD,
}

/**
 * One floating piece of a broken mask: its mesh, built about its own pivot
 * so it turns about itself, where that pivot sits in the whole mask, and how
 * it floats.
 */
class SpiritShard(
    val mesh: SpiritMesh,
    val role: ShardRole,
    /** Where the piece sat in the unbroken mask, model units. Its mesh is built about this point. */
    val pivotX: Float, val pivotY: Float, val pivotZ: Float,
    /** How far it floats off the mask at rest, model units. */
    val restX: Float, val restY: Float, val restZ: Float,
    /** Its idle drift: where in the cycle it starts, and how far it moves on each axis, model units. */
    val phase: Float,
    val bobX: Float, val bobY: Float, val bobZ: Float,
    /** 1 for a piece of the face; above it heavier (bronze, a superstructure) and slower to follow. */
    val mass: Float = 1f,
)

/**
 * How a spirit's pieces move: from its tradition's sense of what the spirit
 * is. A judge's mask holds its pieces still and close; a storm spirit's
 * crackle and snap; a monumental one sways with weight.
 *
 * The springs are per unit mass: a piece pulled off its place accelerates
 * back at [stiffness] times the distance, slowed by [damping] times its
 * speed. Below critical damping (`damping < 2 * sqrt(stiffness)`) it
 * overshoots and settles; above it, it eases home.
 */
class SpiritTemperament(
    val name: String,
    val stiffness: Float,
    val damping: Float,
    /** How many seconds of the spirit's velocity its pieces trail by while it moves. */
    val drag: Float,
    /** Scales every idle drift. */
    val drift: Float,
    /** How fast the idle drift cycles. */
    val rate: Float,
    /** How far each piece rocks about itself, radians. */
    val sway: Float,
    /** Erratic electric jitter, model units; 0 for none. */
    val crackle: Float,
    /** How far pieces fly out from the core on a strike or a cast, model units. */
    val burst: Float,
    /** How fast orbiting relics circle, radians a second. */
    val orbit: Float,
    /** How deeply the core's light breathes. */
    val pulse: Float,
    /** Breaths a second. */
    val breathRate: Float = 0.22f,
    /** How far a breath swells the mask open, 1 as a person breathes at rest. */
    val breathDepth: Float = 1f,
    /** Seconds between the eyes' glances, about. */
    val glance: Float = 1.6f,
) {
    companion object {
        /** Judges and mediators (Ngil, Deangle): pieces held still and aligned, barely drifting. */
        val AUSTERE = SpiritTemperament("Austere", stiffness = 220f, damping = 30f, drag = 0.02f, drift = 0.5f, rate = 0.45f, sway = 0.008f, crackle = 0f, burst = 0.018f, orbit = 0.25f, pulse = 0.12f, breathRate = 0.14f, breathDepth = 0.6f, glance = 3.2f)
        /** Storm and martial spirits (Kifwebe, Mgbedike): crackling, flaring, snapping back. */
        val STORM = SpiritTemperament("Storm", stiffness = 260f, damping = 9f, drag = 0.035f, drift = 1f, rate = 1.4f, sway = 0.07f, crackle = 0.005f, burst = 0.075f, orbit = 1.4f, pulse = 0.5f, breathRate = 0.38f, breathDepth = 1.2f, glance = 0.7f)
        /** The great superstructure masks (Ijele, Gelede): heavy, slow, swaying with their own mass. */
        val MONUMENTAL = SpiritTemperament("Monumental", stiffness = 60f, damping = 5f, drag = 0.06f, drift = 0.8f, rate = 0.5f, sway = 0.05f, crackle = 0f, burst = 0.03f, orbit = 0.5f, pulse = 0.25f, breathRate = 0.11f, breathDepth = 1.4f, glance = 2.6f)
        /** Maidens and mothers (Agbogho Mmuo, Pwo): a solemn breathing, pieces swelling and settling. */
        val BREATHING = SpiritTemperament("Breathing", stiffness = 140f, damping = 12f, drag = 0.035f, drift = 1f, rate = 0.8f, sway = 0.03f, crackle = 0f, burst = 0.04f, orbit = 0.7f, pulse = 0.4f, breathRate = 0.2f, breathDepth = 1.3f, glance = 2f)
        /** Water, bush and satirical spirits (Okoroshi, Okumkpa): never quite still. */
        val RESTLESS = SpiritTemperament("Restless", stiffness = 120f, damping = 8f, drag = 0.045f, drift = 1.25f, rate = 1.1f, sway = 0.06f, crackle = 0.0012f, burst = 0.05f, orbit = 1f, pulse = 0.35f, breathRate = 0.3f, breathDepth = 1f, glance = 1f)

        val all = listOf(AUSTERE, STORM, MONUMENTAL, BREATHING, RESTLESS)
    }
}

/**
 * A mask broken open by the spirit inside it: the spirit's glowing [core]
 * at the heart, the mask's carved pieces floating round it, held by springs.
 *
 * Immutable and shared, as a [SpiritMesh] is: every copy of the spirit on
 * screen draws the same pieces, each moved by its own [ShardRig].
 */
class ShatteredSpirit(
    /** The spirit's core, built about its own centre; also the mesh its instance carries. */
    val core: SpiritMesh,
    /** Where the core sits in the mask, model units. */
    val coreX: Float, val coreY: Float, val coreZ: Float,
    val shards: List<SpiritShard>,
    val temperament: SpiritTemperament,
    /** Which shard holds each eye, left then right (-1 if none), and where on it, relative to its pivot. */
    val eyeShards: IntArray,
    val eyes: FloatArray,
    /** Arcs of light jump between the pieces while it strikes: the storm cores. */
    val arcs: Boolean = false,
    val name: String = "",
) {
    init {
        require(eyeShards.size == 2 && eyes.size == 6) { "two eyes" }
    }

    val triangleCount: Int get() = core.triangleCount + shards.sumOf { it.mesh.triangleCount }
}

/**
 * Where each piece of one spirit's broken mask is this frame.
 *
 * Every piece floats on an idle Lissajous curve about its rest place, and
 * hangs on a damped spring in the world: when the spirit dashes the pieces
 * drag behind it, and when it stops they snap back into the mask's shape. A
 * strike or a cast bursts them out from the core (brows lift, the jaw
 * drops); a blow kicks them; death throws them apart.
 *
 * Mutable, one per spirit on screen, and allocation-free per frame.
 */
class ShardRig(val spirit: ShatteredSpirit) {
    private val n = spirit.shards.size

    /** Per piece, its part transform for [SpiritEmitter.emit]: rotation row by row, then translation, model units. */
    val locals: Array<FloatArray> = Array(n) { FloatArray(12) }

    /** Per piece, how far its spring has left it behind, world units, x y z. */
    val lag = FloatArray(n * 3)
    private val vel = FloatArray(n * 3)

    /** The core's part transform. */
    val coreLocal = FloatArray(12)

    /** Seconds this rig has run. */
    var time = 0f; private set

    /** 0..1: how hard the spirit is surging (striking or casting), eased in fast and out slowly. */
    var surge = 0f; private set

    /** 0..1: how far apart death has thrown the pieces. */
    var scatter = 0f; private set

    /** 0 breathed out .. 1 breathed in: the slow swell the whole mask moves with. */
    var breath = 0f; private set

    /** How brightly the eyes burn now: with the breath, the surge, and dark for a blink. */
    var eyeLight = 0.6f; private set

    /** Where the eyes' glints look, model units off the eyes' centres: across, and up. */
    var gazeX = 0f; private set
    var gazeZ = 0f; private set
    private var gazeToX = 0f; private var gazeToZ = 0f; private var nextGlance = 0.8f
    private var nextBlink = 2.2f; private var blinkAt = -10f

    private var lastX = Float.NaN; private var lastY = 0f; private var lastZ = 0f
    private var vx = 0f; private var vy = 0f; private var vz = 0f
    private var lastCrack = 0f

    /** World velocity of the spirit, as the springs feel it. */
    val speed: Float get() = sqrt(vx * vx + vy * vy + vz * vz)

    init { place(null) }

    /** Advances the springs by [dt] seconds from where [pose] has the spirit now, and places every piece. */
    fun update(pose: SpiritPose, dt: Float) {
        val step = if (dt.isFinite()) dt.coerceIn(0f, MAX_STEP) else 0f
        time += step
        val s = if (pose.scale.isFinite() && pose.scale > 0f) pose.scale else 1f
        // The spirit's velocity, from where it was; a jump of more than a few heights is a teleport, not a dash.
        if (!lastX.isFinite() || step <= 0f) {
            if (!lastX.isFinite()) { vx = 0f; vy = 0f; vz = 0f }
        } else {
            val ix = (pose.x - lastX) / step; val iy = (pose.y - lastY) / step; val iz = (pose.z - lastZ) / step
            if (sqrt(ix * ix + iy * iy + iz * iz) * step > TELEPORT * s) { vx = 0f; vy = 0f; vz = 0f; lag.fill(0f); vel.fill(0f) }
            else {
                val k = 1f - exp(-step * VELOCITY_RATE)
                vx += (ix - vx) * k; vy += (iy - vy) * k; vz += (iz - vz) * k
            }
        }
        lastX = pose.x; lastY = pose.y; lastZ = pose.z

        // A strike or a cast surges the spirit: in fast, out slowly.
        val want = maxOf(pose.flare.finiteOr0(), (pose.lines.finiteOr0() - 0.25f) * 1.3f, pose.core.finiteOr0() * 0.7f).coerceIn(0f, 1f)
        surge += (want - surge) * (1f - exp(-step * if (want > surge) 16f else 3.5f))
        scatter = pose.shatter.finiteOr0().coerceIn(0f, 1f)

        val t = spirit.temperament
        // A blow kicks every piece out from the core.
        val crack = pose.crack.finiteOr0()
        if (crack > lastCrack + 0.25f) for (i in 0 until n) {
            val sh = spirit.shards[i]
            var dx = sh.restX + sh.pivotX - spirit.coreX; var dz = sh.restZ + sh.pivotZ - spirit.coreZ
            val l = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f); dx /= l; dz /= l
            val kick = KICK * s * (0.6f + 0.4f * hash(i, 3)) / sh.mass
            vel[i * 3] += dx * kick; vel[i * 3 + 2] += dz * kick
        }
        lastCrack = crack
        eyes(step)

        // The springs, in substeps short enough to stay stable at any frame time.
        val sub = ceil(step / SUBSTEP).toInt().coerceIn(1, 32)
        val h = if (sub > 0) step / sub else 0f
        val reach = MAX_LAG * s
        for (i in 0 until n) {
            val sh = spirit.shards[i]
            val m = sh.mass.coerceAtLeast(0.2f)
            val kk = t.stiffness / m; val cc = t.damping / m
            // Moving, it trails by a fraction of a second of its velocity; heavier pieces and the raffia trail further.
            val trail = t.drag * (if (sh.role == ShardRole.SHROUD) 2.2f else 1f) * (0.75f + 0.25f * m)
            for (a in 0..2) {
                val v = when (a) { 0 -> vx; 1 -> vy; else -> vz }
                val target = (-v * trail).coerceIn(-reach, reach)
                var x = lag[i * 3 + a]; var u = vel[i * 3 + a]
                repeat(sub) {
                    u += (-kk * (x - target) - cc * u) * h
                    x += u * h
                }
                lag[i * 3 + a] = x.coerceIn(-reach * 1.5f, reach * 1.5f); vel[i * 3 + a] = u
            }
        }
        place(pose)
    }

    /**
     * The eyes: the glints dart to a new place every glance or so (a
     * saccade, fast, then still), and lock on ahead while the spirit strikes
     * or casts; the eyes close for a blink every few seconds.
     */
    private fun eyes(step: Float) {
        val t = spirit.temperament
        if (time >= nextGlance) {
            val k = (time * 7.31f).toInt()
            gazeToX = (hash(k, 21) - 0.5f) * 2f * GAZE_X; gazeToZ = (hash(k, 23) - 0.5f) * 2f * GAZE_Z
            nextGlance = time + t.glance * (0.5f + hash(k, 25))
        }
        val lock = surge.coerceIn(0f, 1f)
        val tx = gazeToX * (1f - lock); val tz = gazeToZ * (1f - lock) + 0.3f * GAZE_Z * lock
        val f = 1f - exp(-step * SACCADE)
        gazeX += (tx - gazeX) * f; gazeZ += (tz - gazeZ) * f
        if (time >= nextBlink) { blinkAt = time; nextBlink = time + 2.5f + 4f * hash((time * 3.7f).toInt(), 27) }
        val sinceBlink = time - blinkAt
        val blink = if (sinceBlink in 0f..BLINK) 1f - sin(sinceBlink / BLINK * PI_F) else 1f
        eyeLight = ((0.5f + 0.35f * breath + 1.1f * surge) * blink * (1f - scatter)).coerceIn(0f, 1.6f)
    }

    /**
     * Places every piece from the rig's clock, surge and scatter (no
     * springs): what a still frame of the spirit shows. [pose] may be null
     * for a spirit at rest.
     */
    fun place(pose: SpiritPose?) {
        val t = spirit.temperament
        val tt = time * t.rate
        val b = t.burst * surge
        val sc = scatter
        // The breath: in slowly, a pause, out a little faster.
        val bp = (time * t.breathRate) % 1f
        breath = if (bp < 0.45f) 0.5f - 0.5f * cos(bp / 0.45f * PI_F) else if (bp < 0.55f) 1f else 0.5f + 0.5f * cos((bp - 0.55f) / 0.45f * PI_F)
        val bd = t.breathDepth * breath
        for (i in 0 until n) {
            val sh = spirit.shards[i]
            val ph = sh.phase
            // The idle drift: a slow Lissajous loop, a different one for every piece.
            var ox = sin(tt * 1.8f + ph) * sh.bobX * t.drift
            var oy = cos(tt * 2.2f + ph) * sh.bobY * t.drift
            var oz = sin(tt * 1.4f + ph) * sh.bobZ * t.drift
            if (t.crackle > 0f) {
                // Storm spirits crackle: jitter that never repeats, stronger as they surge.
                val c = t.crackle * (0.35f + 1.4f * surge)
                ox += c * jitter(time * 23f + ph * 7f); oy += c * 0.5f * jitter(time * 29f + ph * 3f); oz += c * jitter(time * 19f + ph * 5f)
            }
            var rx = sh.restX; var ry = sh.restY; var rz = sh.restZ
            // Every breath swells the pieces out from the core a little, and lets them back.
            run {
                val dx = sh.pivotX - spirit.coreX; val dz = sh.pivotZ - spirit.coreZ
                val l = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-3f)
                rx += dx / l * BREATH * bd; rz += dz / l * BREATH * bd
            }
            var yaw = t.sway * sin(tt * 0.9f + ph * 1.3f); var pitch = t.sway * 0.7f * sin(tt * 0.7f + ph * 2.1f); var roll = t.sway * sin(tt * 1.1f + ph)
            val side = if (sh.pivotX < spirit.coreX) -1f else 1f
            when (sh.role) {
                ShardRole.BROW_LEFT, ShardRole.BROW_RIGHT -> { rx += side * 0.45f * b; rz += 1.1f * b + 0.6f * BREATH * bd; roll -= side * (4f * b + 0.04f * bd) }
                // The jaw parts on the breath and drops open on a shout.
                ShardRole.JAW -> { rz -= 1.6f * b + 1.6f * BREATH * bd; ry += 0.4f * b; pitch -= 6f * b + 0.08f * bd }
                ShardRole.CREST, ShardRole.CROWN -> { rz += 1.3f * b + 0.8f * BREATH * bd; rx += side * 0.3f * b }
                ShardRole.CHEEK_LEFT, ShardRole.CHEEK_RIGHT -> { rx += side * (0.9f * b + 0.5f * BREATH * bd); rz -= 0.2f * b }
                ShardRole.HALF_LEFT, ShardRole.HALF_RIGHT -> { rx += side * 1.8f * b; yaw += side * 3f * b }
                ShardRole.EMBER -> {
                    // Embers swirl about where they broke off.
                    val a = time * (0.8f + t.orbit) + ph
                    ox += cos(a) * 0.012f * t.drift; oz += sin(a) * 0.009f * t.drift
                    rx *= 1f + 1.2f * b; rz *= 1f + 1.2f * b
                    yaw += time * 0.6f + ph; roll += time * 0.4f
                }
                ShardRole.ORBIT -> {
                    // Relics circle the head about the vertical through the core.
                    val r = sqrt(rx * rx + ry * ry); val a = kotlin.math.atan2(ry, rx) + time * t.orbit
                    rx = cos(a) * r * (1f + 0.4f * b); ry = sin(a) * r * (1f + 0.4f * b)
                    yaw += time * t.orbit
                }
                ShardRole.SHROUD -> { roll += 0.6f * t.sway * sin(tt * 1.7f + ph) }
                ShardRole.RELIC -> { rz += 0.6f * b; rx += side * 0.5f * b }
                ShardRole.FACE, ShardRole.BRIDGE -> { ry += 0.3f * b }
            }
            if (sc > 0f) {
                // Death throws the pieces out from the core, up, and down again, tumbling.
                var dx = sh.pivotX + sh.restX - spirit.coreX; var dy = sh.pivotY + sh.restY - spirit.coreY + 0.05f; var dz = sh.pivotZ + sh.restZ - spirit.coreZ
                val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f); dx /= l; dy /= l; dz /= l
                val out = DEATH_REACH * sc * (0.7f + 0.6f * hash(i, 11))
                rx += dx * out; ry += dy * out; rz += dz * out + 0.35f * sc - 0.9f * sc * sc
                yaw += sc * 4f * (hash(i, 5) - 0.5f); pitch += sc * 5f * (hash(i, 7) - 0.5f); roll += sc * 6f * (hash(i, 9) - 0.5f)
            }
            rotation(locals[i], yaw, pitch, roll)
            locals[i][9] = sh.pivotX + rx + ox; locals[i][10] = sh.pivotY + ry + oy; locals[i][11] = sh.pivotZ + rz + oz
        }
        // The core turns slowly in place, bobbing with the breath.
        rotation(coreLocal, time * (0.3f + 0.4f * t.orbit), 0f, 0f)
        coreLocal[9] = spirit.coreX; coreLocal[10] = spirit.coreY; coreLocal[11] = spirit.coreZ + 0.006f * sin(tt * 1.6f)
    }

    /** How brightly the core burns now: its breath and its surge. */
    fun coreGlow(): Float {
        val t = spirit.temperament
        return (0.45f + t.pulse * (0.6f * breath + 0.4f * sin(time * t.rate * 2.4f)) + 0.9f * surge).coerceIn(0f, 1.5f) * (1f - scatter)
    }

    private companion object {
        const val MAX_STEP = 0.1f
        const val SUBSTEP = 1f / 240f
        /** A jump further than this many heights in one frame is a teleport. */
        const val TELEPORT = 3f
        const val VELOCITY_RATE = 14f
        /** The furthest a piece trails, in mask heights. */
        const val MAX_LAG = 0.22f
        /** A blow's kick, in mask heights a second. */
        const val KICK = 1.4f
        /** How far death throws the pieces, model units. */
        const val DEATH_REACH = 0.9f
        /** How far a full breath swells the mask, model units. */
        const val BREATH = 0.0045f
        /** How far the glints look, model units, and how fast they get there. */
        const val GAZE_X = 0.011f
        const val GAZE_Z = 0.005f
        const val SACCADE = 26f
        /** A blink's length, seconds. */
        const val BLINK = 0.16f
        const val PI_F = 3.1415927f

        fun Float.finiteOr0(): Float = if (isFinite()) this else 0f

        /** Jitter in -1..1 that never quite repeats: two incommensurate waves beating. */
        fun jitter(a: Float): Float = sin(a) * sin(a * 1.618f + 1.3f) + 0.35f * sin(a * 3.7f + 0.4f)

        fun hash(i: Int, salt: Int): Float {
            var h = i * 374761393 + salt * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
        }

        /** R = Rz(yaw) * Rx(pitch) * Ry(roll), row by row, as the emitter orients a whole spirit. */
        fun rotation(out: FloatArray, yaw: Float, pitch: Float, roll: Float) {
            val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch); val cr = cos(roll); val sr = sin(roll)
            out[0] = cy * cr - sy * sp * sr; out[1] = -sy * cp; out[2] = cy * sr + sy * sp * cr
            out[3] = sy * cr + cy * sp * sr; out[4] = cy * cp; out[5] = sy * sr - cy * sp * cr
            out[6] = -cp * sr; out[7] = sp; out[8] = cp * cr
        }
    }

}
