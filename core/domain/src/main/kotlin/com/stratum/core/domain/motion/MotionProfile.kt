package com.stratum.core.domain.motion

/**
 * How a strike is thrown when the body itself is the weapon: a mask spirit
 * has no sword, so the mask is what hits.
 */
enum class StrikeStyle {
    /** Straight at the target and back: the plain, fast blow. */
    LUNGE,

    /** A whirl on the spot, fringe flying out: a sweep round the body. */
    SPIN,

    /** Rears back, then drives the brow down into the target: heavy and slow. */
    HEADBUTT,
}

/**
 * A body's personality in motion, as plain numbers: how it hovers, turns,
 * glows, strikes and falls. The procedural motion layers read nothing else,
 * so the same layers make a serene maiden spirit glide and an Mgbedike jerk
 * and snap, and a pack can give its own monsters a character without code.
 *
 * Every field has a default that looks right on an ordinary body, so a
 * profile is only as long as what makes it different. Distances are in
 * blocks, angles in degrees, rates in cycles a second, times in seconds.
 *
 * ## The numbers, and why they are these
 *
 * - [follow] and [turn] are the natural frequencies (radians a second) of
 *   critically damped springs: the visual body chases the gameplay position
 *   and heading without overshoot. Higher is snappier. The gameplay position
 *   is never changed -- only where the art is drawn lags it, and never by
 *   more than [MAX_LAG] blocks, so what you see is what you can hit.
 * - [twitch] is a temperament, 0 serene to 1 feral: it adds a fast, small
 *   tremor, shortens wind-ups and sharpens turns.
 */
data class MotionProfile(
    val id: String = "default",
    /** Spring frequency the drawn body follows its true position with. */
    val follow: Float = 12f,
    /** Spring frequency it turns to its heading with. */
    val turn: Float = 10f,
    /** Seconds of look-ahead into a turn: it faces where it is about to be going. */
    val anticipate: Float = 0.12f,
    /** Height the body floats at above its feet, for a spirit; 0 for a walker. */
    val hover: Float = 1.15f,
    /** Height of the idle bob, and its rate. */
    val bob: Float = 0.07f,
    val bobRate: Float = 0.6f,
    /** Idle sway of heading and tilt, in degrees, and its rate. */
    val sway: Float = 6f,
    val swayRate: Float = 0.23f,
    /** Resting glow 0..1, how much it breathes, and how fast. */
    val glow: Float = 0.45f,
    val glowPulse: Float = 0.18f,
    val glowRate: Float = 0.3f,
    /** 0 serene .. 1 feral. */
    val twitch: Float = 0f,
    /** Degrees of lean into movement per block a second, and the most it will lean. */
    val lean: Float = 7f,
    val maxLean: Float = 24f,
    /** How the body strikes, how far it throws itself, and the three beats of the blow. */
    val strike: StrikeStyle = StrikeStyle.LUNGE,
    val lunge: Float = 0.8f,
    val windup: Float = 0.1f,
    val blow: Float = 0.09f,
    val recoil: Float = 0.3f,
    /** How far a hit throws it back, as a share of an ordinary flinch. */
    val flinch: Float = 1f,
    /** Squash and stretch, as a share of size: on landing a blow, on being hit, in a walk. */
    val squash: Float = 0.1f,
    /** A walker's bounce per step (sprites); spirits bob instead. */
    val walkBounce: Float = 0.05f,
    /** Seconds to rise into the world, and to fall out of it. */
    val spawn: Float = 0.55f,
    val death: Float = 1.1f,
    /** Raffia fringe below the mask, as a share of its height; 0 for none. */
    val fringe: Float = 0.55f,
    /** How stiff the fringe is: low streams and whips, high hangs neat. */
    val fringeStiffness: Float = 16f,
    /** Floating hands, 0..2, that punch and spread to cast. */
    val hands: Int = 0,
    /** Charms orbiting the mask, 0..8. */
    val charms: Int = 0,
    /** Size, as a share of an ordinary body. */
    val scale: Float = 1f,
) {
    /** The same profile with every number forced into a range the layers are stable in. */
    fun sanitised(): MotionProfile {
        fun f(v: Float, lo: Float, hi: Float, fallback: Float) = if (v.isFinite()) v.coerceIn(lo, hi) else fallback
        val d = DEFAULT
        return copy(
            follow = f(follow, 0.5f, 60f, d.follow), turn = f(turn, 0.5f, 60f, d.turn), anticipate = f(anticipate, 0f, 0.6f, d.anticipate),
            hover = f(hover, 0f, 4f, d.hover), bob = f(bob, 0f, 0.6f, d.bob), bobRate = f(bobRate, 0f, 4f, d.bobRate),
            sway = f(sway, 0f, 45f, d.sway), swayRate = f(swayRate, 0f, 3f, d.swayRate),
            glow = f(glow, 0f, 1f, d.glow), glowPulse = f(glowPulse, 0f, 1f, d.glowPulse), glowRate = f(glowRate, 0f, 4f, d.glowRate),
            twitch = f(twitch, 0f, 1f, d.twitch), lean = f(lean, 0f, 45f, d.lean), maxLean = f(maxLean, 0f, 80f, d.maxLean),
            lunge = f(lunge, 0f, 4f, d.lunge), windup = f(windup, 0.01f, 1.5f, d.windup), blow = f(blow, 0.01f, 1f, d.blow),
            recoil = f(recoil, 0.01f, 2f, d.recoil), flinch = f(flinch, 0f, 4f, d.flinch), squash = f(squash, 0f, 0.5f, d.squash),
            walkBounce = f(walkBounce, 0f, 0.4f, d.walkBounce), spawn = f(spawn, 0.01f, 5f, d.spawn), death = f(death, 0.05f, 6f, d.death),
            fringe = f(fringe, 0f, 2f, d.fringe), fringeStiffness = f(fringeStiffness, 2f, 80f, d.fringeStiffness),
            hands = hands.coerceIn(0, 2), charms = charms.coerceIn(0, 8), scale = f(scale, 0.2f, 5f, d.scale),
        )
    }

    /** Seconds from the start of a strike to its landing: when the blow connects. */
    val strikeLands: Float get() = windup + blow

    /** Seconds a whole strike takes, wind-up to settled. */
    val strikeLength: Float get() = windup + blow + recoil

    companion object {
        val DEFAULT = MotionProfile()

        /** However loose a profile's springs, the drawn body never trails its true position by more than this. */
        const val MAX_LAG = 0.6f
    }
}

/**
 * The built-in profiles: one per mask tradition, so a maiden glides and an
 * Mgbedike snaps, and a handful of walker archetypes for sprite actors.
 *
 * Ids are plain strings a pack can name ("spirit:mgbedike", "actor:brute")
 * and override; [resolve] layers a pack's overrides over these.
 */
object MotionProfiles {

    /** Agbogho Mmuo: the maiden. Serene, gliding, a slow graceful sway; strikes are quick and clean. */
    val MAIDEN = MotionProfile(
        id = "spirit:agbogho-mmuo", follow = 7f, turn = 6f, anticipate = 0.2f, hover = 1.2f, bob = 0.09f, bobRate = 0.4f,
        sway = 9f, swayRate = 0.16f, glow = 0.5f, glowPulse = 0.22f, glowRate = 0.22f, twitch = 0f, lean = 9f,
        strike = StrikeStyle.LUNGE, lunge = 0.9f, windup = 0.14f, blow = 0.1f, recoil = 0.42f, fringe = 0.7f, fringeStiffness = 10f,
        charms = 3,
    )

    /** Mgbedike: the brave. Twitchy and aggressive, snapping turns, a hard head-first blow. */
    val MGBEDIKE = MotionProfile(
        id = "spirit:mgbedike", follow = 18f, turn = 20f, anticipate = 0.06f, hover = 1.0f, bob = 0.05f, bobRate = 1.1f,
        sway = 4f, swayRate = 0.5f, glow = 0.4f, glowPulse = 0.3f, glowRate = 0.8f, twitch = 0.85f, lean = 5f, maxLean = 18f,
        strike = StrikeStyle.HEADBUTT, lunge = 1.05f, windup = 0.07f, blow = 0.06f, recoil = 0.22f, flinch = 0.7f,
        fringe = 0.45f, fringeStiffness = 24f, hands = 2,
    )

    /** Okoroshi: the dry-season spirits, poised and uncanny; a whirling sweep. */
    val OKOROSHI = MotionProfile(
        id = "spirit:okoroshi", follow = 10f, turn = 9f, hover = 1.2f, bob = 0.06f, bobRate = 0.5f, sway = 5f, swayRate = 0.2f,
        glow = 0.5f, twitch = 0.25f, strike = StrikeStyle.SPIN, lunge = 0.6f, windup = 0.12f, blow = 0.16f, recoil = 0.3f, fringe = 0.8f,
    )

    /** Ogbodo Enyi: the elephant. Heavy and slow, a big bob, a ponderous head-first charge. */
    val ELEPHANT = MotionProfile(
        id = "spirit:ogbodo-enyi", follow = 6f, turn = 4.5f, anticipate = 0.25f, hover = 0.95f, bob = 0.1f, bobRate = 0.35f, sway = 4f,
        swayRate = 0.12f, glow = 0.35f, twitch = 0f, lean = 4f, strike = StrikeStyle.HEADBUTT, lunge = 0.7f, windup = 0.22f, blow = 0.12f,
        recoil = 0.5f, flinch = 0.5f, squash = 0.14f, fringe = 0.6f, fringeStiffness = 12f, scale = 1.15f,
    )

    /** Ijele: the king of masks. Stately, towering, ringed by charms; spins its great crown. */
    val IJELE = MotionProfile(
        id = "spirit:ijele", follow = 6f, turn = 5f, anticipate = 0.2f, hover = 1.3f, bob = 0.06f, bobRate = 0.3f, sway = 3f,
        swayRate = 0.12f, glow = 0.55f, glowPulse = 0.25f, glowRate = 0.25f, strike = StrikeStyle.SPIN, lunge = 0.5f, windup = 0.18f,
        blow = 0.2f, recoil = 0.4f, flinch = 0.6f, fringe = 0.9f, fringeStiffness = 9f, charms = 6, scale = 1.1f,
    )

    /** After Ikenga: proud and horned, fists ready; a driving head-first blow. */
    val IKENGA = MotionProfile(
        id = "spirit:ikenga", follow = 13f, turn = 13f, anticipate = 0.1f, hover = 1.1f, bob = 0.06f, bobRate = 0.7f, sway = 5f,
        glow = 0.45f, twitch = 0.35f, strike = StrikeStyle.HEADBUTT, lunge = 0.95f, windup = 0.1f, blow = 0.08f, recoil = 0.3f,
        fringe = 0.5f, hands = 2,
    )

    /** After Mbari: bright and buoyant, charms circling like a festival; quick darting lunges. */
    val MBARI = MotionProfile(
        id = "spirit:mbari", follow = 14f, turn = 12f, hover = 1.2f, bob = 0.08f, bobRate = 0.9f, sway = 7f, swayRate = 0.35f,
        glow = 0.55f, glowPulse = 0.25f, glowRate = 0.6f, twitch = 0.3f, strike = StrikeStyle.LUNGE, lunge = 1f, windup = 0.08f,
        blow = 0.08f, recoil = 0.28f, fringe = 0.6f, charms = 5,
    )

    /** A walker: the old sprite hero and monsters when masks are turned off. */
    val WALKER = MotionProfile(id = "actor:default", hover = 0f, bob = 0f, sway = 0f, fringe = 0f, lean = 4f, maxLean = 10f, lunge = 0.2f)
    val BRUTE = WALKER.copy(id = "actor:brute", follow = 9f, lean = 3f, squash = 0.14f, walkBounce = 0.07f, windup = 0.18f, recoil = 0.4f, flinch = 0.6f)
    val BEAST = WALKER.copy(id = "actor:beast", follow = 16f, lean = 7f, maxLean = 16f, twitch = 0.4f, walkBounce = 0.08f, windup = 0.06f)
    val CASTER = WALKER.copy(id = "actor:caster", follow = 10f, lean = 2f, walkBounce = 0.03f, windup = 0.2f)

    val builtIn: List<MotionProfile> = listOf(DEFAULT_SPIRIT(), MAIDEN, MGBEDIKE, OKOROSHI, ELEPHANT, IJELE, IKENGA, MBARI, WALKER, BRUTE, BEAST, CASTER)

    @Suppress("FunctionName")
    private fun DEFAULT_SPIRIT() = MotionProfile(id = "spirit:default")

    /** A built-in profile by id, or null. */
    fun byId(id: String): MotionProfile? = builtIn.firstOrNull { it.id == id }

    /**
     * The profile named [id]: a pack's own ([overrides], later packs winning)
     * over the built-in one, over [fallback]. Always sanitised, so a
     * hand-written profile with a zero stiffness or a NaN cannot break the
     * layers.
     */
    fun resolve(id: String, overrides: Map<String, MotionProfile> = emptyMap(), fallback: MotionProfile = MotionProfile.DEFAULT): MotionProfile =
        (overrides[id] ?: byId(id) ?: fallback.copy(id = id)).sanitised()

    /** The id of a mask tradition's profile, from the tradition's enum name ("MGBEDIKE" -> "spirit:mgbedike"). */
    fun forTradition(traditionName: String): String = when (traditionName.uppercase()) {
        "MAIDEN" -> MAIDEN.id
        "MGBEDIKE" -> MGBEDIKE.id
        "OKOROSHI" -> OKOROSHI.id
        "ELEPHANT" -> ELEPHANT.id
        "IJELE" -> IJELE.id
        "IKENGA" -> IKENGA.id
        "MBARI" -> MBARI.id
        else -> "spirit:default"
    }
}
