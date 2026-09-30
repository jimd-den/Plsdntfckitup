package com.stratum.plugins.schema

import com.stratum.core.domain.motion.MotionProfile
import com.stratum.core.domain.motion.MotionProfiles
import com.stratum.core.domain.motion.StrikeStyle
import kotlinx.serialization.Serializable

/**
 * A mask spirit's motion profile as a pack writes it: an id, what it is
 * [based][base] on, and only the numbers that differ.
 *
 * Every number is optional. A profile with the id of a built-in one
 * (`spirit:mgbedike`) changes that one; a new id starts from [base] (a
 * built-in or `spirit:default`) and a monster names it with `"motion"`.
 * Whatever is written is sanitised when it is used, so a zero stiffness or a
 * huge lunge cannot break the motion.
 *
 * ```json
 * "motionProfiles": [
 *   { "id": "spirit:mgbedike", "twitch": 1.0, "lunge": 1.3 },
 *   { "id": "my:drifter", "base": "spirit:agbogho-mmuo", "hover": 1.6, "strike": "SPIN", "charms": 4 }
 * ]
 * ```
 */
@Serializable
internal data class MotionProfileSchema(
    val id: String,
    val base: String? = null,
    val follow: Float? = null,
    val turn: Float? = null,
    val anticipate: Float? = null,
    val hover: Float? = null,
    val bob: Float? = null,
    val bobRate: Float? = null,
    val sway: Float? = null,
    val swayRate: Float? = null,
    val glow: Float? = null,
    val glowPulse: Float? = null,
    val glowRate: Float? = null,
    val twitch: Float? = null,
    val lean: Float? = null,
    val maxLean: Float? = null,
    val strike: String? = null,
    val lunge: Float? = null,
    val windup: Float? = null,
    val blow: Float? = null,
    val recoil: Float? = null,
    val flinch: Float? = null,
    val squash: Float? = null,
    val walkBounce: Float? = null,
    val spawn: Float? = null,
    val death: Float? = null,
    val fringe: Float? = null,
    val fringeStiffness: Float? = null,
    val hands: Int? = null,
    val charms: Int? = null,
    val scale: Float? = null,
) {
    fun toDomain(): MotionProfile {
        val from = MotionProfiles.byId(base ?: id) ?: base?.let { MotionProfiles.byId(it) } ?: MotionProfile.DEFAULT
        val d = from.copy(id = id)
        return d.copy(
            follow = follow ?: d.follow, turn = turn ?: d.turn, anticipate = anticipate ?: d.anticipate,
            hover = hover ?: d.hover, bob = bob ?: d.bob, bobRate = bobRate ?: d.bobRate,
            sway = sway ?: d.sway, swayRate = swayRate ?: d.swayRate,
            glow = glow ?: d.glow, glowPulse = glowPulse ?: d.glowPulse, glowRate = glowRate ?: d.glowRate,
            twitch = twitch ?: d.twitch, lean = lean ?: d.lean, maxLean = maxLean ?: d.maxLean,
            strike = strike?.let { SchemaValues.enum<StrikeStyle>(it, "motion profile '$id' strike") } ?: d.strike,
            lunge = lunge ?: d.lunge, windup = windup ?: d.windup, blow = blow ?: d.blow, recoil = recoil ?: d.recoil,
            flinch = flinch ?: d.flinch, squash = squash ?: d.squash, walkBounce = walkBounce ?: d.walkBounce,
            spawn = spawn ?: d.spawn, death = death ?: d.death,
            fringe = fringe ?: d.fringe, fringeStiffness = fringeStiffness ?: d.fringeStiffness,
            hands = hands ?: d.hands, charms = charms ?: d.charms, scale = scale ?: d.scale,
        )
    }

    companion object {
        /** Writes every number, so a pack round-trips exactly. */
        fun of(p: MotionProfile) = MotionProfileSchema(
            p.id, null, p.follow, p.turn, p.anticipate, p.hover, p.bob, p.bobRate, p.sway, p.swayRate, p.glow, p.glowPulse, p.glowRate,
            p.twitch, p.lean, p.maxLean, SchemaValues.name(p.strike), p.lunge, p.windup, p.blow, p.recoil, p.flinch, p.squash, p.walkBounce,
            p.spawn, p.death, p.fringe, p.fringeStiffness, p.hands, p.charms, p.scale,
        )
    }
}
