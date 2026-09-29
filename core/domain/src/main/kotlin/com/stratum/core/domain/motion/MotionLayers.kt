package com.stratum.core.domain.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the motion layers decide for one body this frame: where it is drawn
 * and how it is turned, lit and faded.
 *
 * Mutable and reused; every field is reset by [reset] and then each layer
 * adds its part, so layers compose by addition and the order they run in
 * only matters where one deliberately overrides another (a death fade).
 *
 * Positions are world blocks; angles are radians; [yaw] 0 faces +y.
 * [pitch] tips the top of the body forwards (positive), [roll] tilts it to
 * its right. The hands are offsets in the body's own frame, in body heights.
 */
class MotionPose {
    var x = 0f; var y = 0f; var z = 0f
    var yaw = 0f; var pitch = 0f; var roll = 0f
    /** Overall size, and a squash-and-stretch along the vertical (width keeps the volume). */
    var scale = 1f
    var stretch = 1f
    var glow = 0f; var eyes = 0f; var lines = 0f; var crest = 0f
    var opacity = 1f
    var flash = 0f
    /** 0..1 a shield of light (a blow turned aside). */
    var shield = 0f
    /** 0..1 how deep into a strike: trails and rings. */
    var flare = 0f
    /** 0..1 a crack of light at a hit. */
    var crack = 0f
    /** 0..1 how far through dying. */
    var shatter = 0f
    /** Hands, left then right: x (right), y (forward), z (up), in body heights. */
    val hands = FloatArray(6)
    var handGlow = 0f
    /** The charms' orbit angle, radians. */
    var charmSpin = 0f

    fun reset() {
        x = 0f; y = 0f; z = 0f; yaw = 0f; pitch = 0f; roll = 0f; scale = 1f; stretch = 1f
        glow = 0f; eyes = 0f; lines = 0f; crest = 0f; opacity = 1f; flash = 0f; shield = 0f; flare = 0f; crack = 0f; shatter = 0f
        for (i in hands.indices) hands[i] = 0f
        handGlow = 0f; charmSpin = 0f
    }
}

/**
 * One composable piece of procedural motion: reads a [MotionBody] and its
 * profile, and adds its part to a [MotionPose].
 *
 * A layer holds no state of its own -- everything it needs is the body's
 * clocks and springs -- so one instance serves every body, a layer can be
 * added, removed or replaced in a rig without touching the others, and the
 * same body evaluated twice gives the same pose.
 */
fun interface MotionLayer {
    fun apply(body: MotionBody, pose: MotionPose)
}

/** An ordered stack of layers. [SPIRIT] floats a mask character; [WALKER] embellishes a sprite. */
class MotionRig(private val layers: Array<MotionLayer>) {
    fun evaluate(body: MotionBody, pose: MotionPose) {
        pose.reset()
        for (layer in layers) layer.apply(body, pose)
        // Whatever a layer did, the pose leaves here drawable.
        if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite()) { pose.x = body.trueX; pose.y = body.trueY; pose.z = body.trueZ }
        if (!pose.yaw.isFinite()) pose.yaw = 0f
        if (!pose.pitch.isFinite()) pose.pitch = 0f
        if (!pose.roll.isFinite()) pose.roll = 0f
        if (!(pose.scale > 0f) || !pose.scale.isFinite()) pose.scale = 1f
        if (!(pose.stretch > 0f) || !pose.stretch.isFinite()) pose.stretch = 1f
        pose.opacity = if (pose.opacity.isFinite()) pose.opacity.coerceIn(0f, 1f) else 1f
    }

    companion object {
        /** A floating mask character: hovers, glows, leans, turns, strikes with itself, flinches and shatters. */
        val SPIRIT = MotionRig(
            arrayOf(
                MotionLayers.Tether, MotionLayers.Hover, MotionLayers.Breath, MotionLayers.Heading, MotionLayers.Lean,
                MotionLayers.Twitch, MotionLayers.Strike, MotionLayers.Cast, MotionLayers.Flinch, MotionLayers.Crit,
                MotionLayers.Block, MotionLayers.Hands, MotionLayers.Spawn, MotionLayers.Death,
            ),
        )

        /** A sprite actor: walk bounce, lean, squash, wind-up, topple and rise over its drawn animation. */
        val WALKER = MotionRig(
            arrayOf(
                MotionLayers.Tether, MotionLayers.WalkBounce, MotionLayers.Heading, MotionLayers.Lean, MotionLayers.Strike,
                MotionLayers.Cast, MotionLayers.Flinch, MotionLayers.Spawn, MotionLayers.Topple,
            ),
        )
    }
}

/** The standard layers. Each one's KDoc says what it adds and why. */
object MotionLayers {
    private const val DEG = (PI / 180.0).toFloat()

    /** The drawn body where its springs have carried it. Everything else is laid on top. */
    val Tether = MotionLayer { b, p -> p.x = b.x; p.y = b.y; p.z = b.z; p.scale = b.profile.scale }

    /**
     * The idle float: a bob on two rhythms (so it never loops visibly), a
     * slow sway of heading and tilt. Phases come from the body's seed, so a
     * crowd of the same mask never bobs in unison.
     */
    val Hover = MotionLayer { b, p ->
        val pr = b.profile
        if (pr.hover <= 0f) return@MotionLayer
        val t = b.time
        val ph = Springs.unit(b.seed, 1) * Springs.TAU
        p.z += pr.bob * (sin(t * pr.bobRate * Springs.TAU + ph) * 0.75f + sin(t * pr.bobRate * 1.618f * Springs.TAU + ph * 2f) * 0.25f)
        val sway = pr.sway * DEG
        val ps = Springs.unit(b.seed, 2) * Springs.TAU
        p.yaw += sway * 0.6f * sin(t * pr.swayRate * Springs.TAU + ps)
        p.roll += sway * 0.5f * sin(t * pr.swayRate * 0.83f * Springs.TAU + ps * 1.7f)
        p.pitch += sway * 0.3f * sin(t * pr.swayRate * 1.31f * Springs.TAU + ps * 0.6f)
        p.charmSpin = t * (0.35f + pr.twitch * 0.5f) * Springs.TAU + ps
    }

    /** The glow breathing: the whole mask, and its lines more than its eyes, slow and steady. */
    val Breath = MotionLayer { b, p ->
        val pr = b.profile
        val breath = 0.5f + 0.5f * sin(b.time * pr.glowRate * Springs.TAU + Springs.unit(b.seed, 3) * Springs.TAU)
        p.glow += pr.glow + pr.glowPulse * (breath - 0.5f)
        p.lines += pr.glowPulse * breath * 0.6f
        p.eyes += 0.1f * breath
    }

    /** Facing: the heading spring, which leads into turns by the profile's anticipation. */
    val Heading = MotionLayer { b, p -> p.yaw += b.yaw }

    /**
     * Leaning into movement, and banking into turns: tips the top towards
     * where the body is going and rolls it into the curve, as a skater does.
     */
    val Lean = MotionLayer { b, p ->
        val pr = b.profile
        val sx = b.speedX; val sy = b.speedY
        // Speed along and across the way the body faces.
        val fx = -sin(b.yaw); val fy = cos(b.yaw)
        val along = sx * fx + sy * fy
        val max = pr.maxLean * DEG
        p.pitch += (along * pr.lean * DEG).coerceIn(-max, max)
        p.roll += (-b.yawSpeed * pr.lean * 0.08f * DEG * 10f).coerceIn(-max, max)
    }

    /** A feral body's quiver: a small fast tremor of position and heading, scaled by twitch. */
    val Twitch = MotionLayer { b, p ->
        val tw = b.profile.twitch
        if (tw <= 0f) return@MotionLayer
        val t = b.time
        p.x += Springs.tremor(b.seed, 11, t, 7f) * 0.018f * tw
        p.y += Springs.tremor(b.seed, 13, t, 7.7f) * 0.018f * tw
        p.z += Springs.tremor(b.seed, 17, t, 9f) * 0.012f * tw
        p.yaw += Springs.tremor(b.seed, 19, t, 5f) * 5f * DEG * tw
    }

    /**
     * The strike, in three beats -- anticipation, blow, recoil -- thrown with
     * the body itself.
     *
     * - LUNGE draws back a little, then throws itself along the aim and
     *   springs back past its rest.
     * - SPIN whirls a full turn on the spot as it drives a short way in.
     * - HEADBUTT rears up and back, then drives the brow down into the target.
     *
     * The crest and lines flare through the blow, and [MotionPose.flare]
     * carries the trail and ring the scene draws in the mask's colours.
     */
    val Strike = MotionLayer { b, p ->
        val a = b.strikeAge
        if (a < 0f) return@MotionLayer
        val pr = b.profile
        val quick = 1f - 0.35f * pr.twitch
        val wind = pr.windup * quick; val blow = pr.blow * quick; val back = pr.recoil
        if (a > wind + blow + back) return@MotionLayer
        val reach = pr.lunge * (0.6f + 0.4f * b.strikePower)
        // How far along the aim the body is: back in the wind-up, out in the blow, home in the recoil.
        val out: Float; val envelope: Float
        when {
            a < wind -> { val t = a / wind; out = -0.22f * Springs.easeOutQuad(t); envelope = 0.4f * t }
            a < wind + blow -> { val t = (a - wind) / blow; out = -0.22f + 1.22f * Springs.easeOutBack(t); envelope = 0.4f + 0.6f * t }
            else -> { val t = (a - wind - blow) / back; out = 1f - Springs.easeInOutCubic(t); envelope = 1f - t }
        }
        val dx = b.strikeDirX; val dy = b.strikeDirY
        when (b.strikeStyle) {
            StrikeStyle.LUNGE -> {
                p.x += dx * out * reach; p.y += dy * out * reach
                p.pitch += out * 18f * DEG
            }
            StrikeStyle.SPIN -> {
                val spin = if (a < wind) -0.25f * Springs.easeOutQuad(a / wind) else -0.25f + 1.25f * Springs.easeInOutCubic((a - wind) / (blow + back * 0.6f))
                p.yaw += spin * Springs.TAU
                p.x += dx * out * reach * 0.5f; p.y += dy * out * reach * 0.5f
                p.roll += Springs.hump((a - wind) / (blow + back)) * 12f * DEG
            }
            StrikeStyle.HEADBUTT -> {
                val rear = if (a < wind) Springs.easeOutQuad(a / wind) else if (a < wind + blow) 1f - Springs.easeInQuad((a - wind) / blow) else 0f
                p.z += rear * 0.28f * pr.scale - (if (a >= wind && a < wind + blow + back * 0.4f) 0.08f else 0f)
                p.pitch += (-rear * 28f + (if (out > 0f) out * 38f else 0f)) * DEG
                p.x += dx * out * reach; p.y += dy * out * reach
            }
        }
        // The blow landing squashes the body along its travel: a hit you can feel.
        val landing = if (a >= wind + blow) Springs.decay(a - wind - blow, 14f) else 0f
        p.stretch *= 1f - pr.squash * landing + pr.squash * 0.6f * (if (a in wind..(wind + blow)) 1f else 0f)
        p.flare = maxOf(p.flare, envelope)
        p.crest += envelope * 1.1f
        p.lines += envelope * 0.7f
        p.glow += envelope * 0.35f
    }

    /** The cast: the eyes flare, the body rises and swells a little, the hands spread. */
    val Cast = MotionLayer { b, p ->
        val a = b.castAge
        if (a < 0f || a > MotionBody.CAST_LENGTH) return@MotionLayer
        val t = a / MotionBody.CAST_LENGTH
        val swell = Springs.hump(t)
        p.eyes += 1.3f * swell + 0.4f * Springs.decay(a, 5f)
        p.lines += 0.8f * swell
        p.glow += 0.4f * swell
        p.z += 0.22f * swell * b.profile.scale
        p.pitch -= 12f * DEG * swell
        p.scale *= 1f + 0.06f * swell
        p.handGlow += swell
        p.flare = maxOf(p.flare, 0.5f * swell)
    }

    /**
     * The flinch: knocked back along the blow by a spring that was kicked,
     * not moved, so it snaps away and eases home; the tilt jolts, the whole
     * mask flashes white, and a crack of light marks the hit. Also shoved by
     * the knockback the combat already applies ([MotionBody.impact]).
     */
    val Flinch = MotionLayer { b, p ->
        val pr = b.profile
        val shove = b.flinch * 0.12f + b.impact * 0.1f * pr.flinch
        p.x += b.hitDirX * shove; p.y += b.hitDirY * shove
        // Tilt away from the blow: back if hit in the face, sideways if from the side.
        val fx = -sin(b.yaw); val fy = cos(b.yaw)
        val fromFront = -(b.hitDirX * fx + b.hitDirY * fy)
        val side = b.hitDirX * fy - b.hitDirY * fx
        p.pitch -= fromFront * b.flinch * 14f * DEG
        p.roll += side * b.flinch * 14f * DEG
        if (b.hitAge >= 0f) {
            val flash = Springs.decay(b.hitAge, 14f) * b.hitPower.coerceAtMost(1f)
            p.flash = maxOf(p.flash, flash)
            p.crack = maxOf(p.crack, if (b.hitAge < 0.3f) 1f - b.hitAge / 0.3f else 0f)
            p.stretch *= 1f - pr.squash * 1.2f * Springs.decay(b.hitAge, 10f)
        }
    }

    /** A critical: the eyes flash white-hot and fade. */
    val Crit = MotionLayer { b, p -> if (b.critAge >= 0f) p.eyes += 2.2f * Springs.decay(b.critAge, 5f) }

    /** A blow turned aside: a shield of light that flickers as it fades. */
    val Block = MotionLayer { b, p ->
        if (b.blockAge < 0f) return@MotionLayer
        val e = Springs.decay(b.blockAge, 4f)
        val flicker = 0.75f + 0.25f * sin(b.blockAge * 70f)
        p.shield = maxOf(p.shield, e * flicker)
    }

    /**
     * Floating hands: they hang at the body's sides, bob out of step with it,
     * punch along the aim in a strike and spread up and out to cast.
     */
    val Hands = MotionLayer { b, p ->
        if (b.profile.hands <= 0) return@MotionLayer
        val t = b.time
        val h = p.hands
        for (side in 0 until 2) {
            val s = if (side == 0) -1f else 1f
            val bob = sin(t * b.profile.bobRate * Springs.TAU + side * 1.9f + Springs.unit(b.seed, 5) * 6f) * 0.05f
            var hx = 0.62f * s; var hy = 0.12f; var hz = -0.42f + bob
            // Punch: the leading hand drives forward on the blow.
            val a = b.strikeAge
            val pr = b.profile
            if (a >= 0f && a <= pr.strikeLength) {
                // Which hand leads is fixed for the strike (from its aim), so it never swaps mid-blow.
                val lead = if ((Springs.hash(b.seed + (b.strikeDirX * 1000f).toInt()) and 1) == side) 1f else 0.4f
                val punch = if (a < pr.windup) -0.2f * (a / pr.windup) else Springs.hump((a - pr.windup) / (pr.blow + pr.recoil)) * 1.1f
                hy += punch * lead
                hx *= 1f - 0.5f * lead * Springs.clamp01(punch)
                hz += 0.25f * lead * Springs.clamp01(punch)
            }
            val c = b.castAge
            if (c >= 0f && c <= MotionBody.CAST_LENGTH) {
                val spread = Springs.hump(c / MotionBody.CAST_LENGTH)
                hx += 0.3f * s * spread; hz += 0.55f * spread; hy += 0.15f * spread
            }
            h[side * 3] = hx; h[side * 3 + 1] = hy; h[side * 3 + 2] = hz
        }
        p.handGlow += 0.25f + p.flare * 0.8f
    }

    /** Rising into the world: up from below, growing and fading in, with a last small overshoot. */
    val Spawn = MotionLayer { b, p ->
        val a = b.spawnAge
        if (a < 0f) return@MotionLayer
        val t = Springs.clamp01(a / b.profile.spawn)
        val e = Springs.easeOutBack(t)
        p.z -= (1f - Springs.easeOutQuad(t)) * (0.6f + b.profile.hover * 0.6f)
        p.scale *= 0.55f + 0.45f * e
        p.opacity *= Springs.easeOutQuad(t)
        p.glow += (1f - t) * 0.6f
    }

    /**
     * Death: a last flare, then the mask spins slowly, sinks and dissolves
     * into shards of light. [MotionPose.shatter] drives the shards; opacity
     * drives the dissolve.
     */
    val Death = MotionLayer { b, p ->
        val a = b.deathAge
        if (a < 0f) return@MotionLayer
        val t = Springs.clamp01(a / b.profile.death)
        p.shatter = t
        p.opacity = if (t < 0.25f) 1f else 1f - Springs.easeInQuad((t - 0.25f) / 0.75f)
        p.eyes += 2f * Springs.decay(a, 6f)
        p.glow += 0.8f * Springs.hump(t * 1.5f)
        p.yaw += Springs.easeInQuad(t) * 1.5f
        p.pitch += 25f * DEG * Springs.easeInQuad(t)
        p.z -= 0.35f * Springs.easeInQuad(t)
        p.scale *= 1f + 0.12f * Springs.hump(t)
    }

    /** A walker's bounce: one hop per step, faster the faster it goes. */
    val WalkBounce = MotionLayer { b, p ->
        val s = b.speed
        if (s < MotionBody.MOVING) return@MotionLayer
        val rate = 1.6f + s * 0.35f
        val step = abs(sin(b.time * rate * PI.toFloat() + Springs.unit(b.seed, 7) * 3f))
        val k = Springs.clamp01((s - MotionBody.MOVING) / 2f)
        p.z += b.profile.walkBounce * step * k
        p.stretch *= 1f - b.profile.squash * 0.4f * (1f - step) * k
    }

    /** A walker's death: toppling over sideways and fading, rather than a mask's shatter. */
    val Topple = MotionLayer { b, p ->
        val a = b.deathAge
        if (a < 0f) return@MotionLayer
        val t = Springs.clamp01(a / b.profile.death)
        p.roll += 80f * DEG * Springs.easeInQuad(Springs.clamp01(t * 1.6f))
        p.opacity = 1f - Springs.clamp01((t - 0.5f) * 2f)
        p.stretch *= 1f - 0.1f * t
        p.shatter = t
    }
}
