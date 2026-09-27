package com.stratum.core.domain.actor

/**
 * A skill: how it is delivered, how big it is, what it does to whatever it
 * reaches, what it is tagged as, and what it costs.
 *
 * Every part is independent, which is what makes skills composable: a
 * projectile that burns, a nova that heals allies, a dash that stuns what it
 * passes through are the same five fields filled differently. Supports and
 * passives change skills through [tags] and the projectile/area stats, so a
 * pack can add "+2 projectiles" without the engine knowing which skills fire
 * any.
 *
 * Packs define them; the engine knows only the [SkillDelivery] verbs and the
 * [SkillEffect] verbs.
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /**
     * The skill's own damage type: the colour of its button and its numbers,
     * and the type of its hit when [effects] names no damage of its own.
     */
    val damageTypeId: String,
    /** Multiplies attack power, so a skill scales with gear rather than replacing it. */
    val powerMultiplier: Float = 1.5f,
    val resourceCost: Int = 10,
    val cooldownSeconds: Float = 4f,
    val delivery: SkillDelivery = SkillDelivery.MELEE,
    /** Blocks: the reach of a strike, the radius of a nova, the length of a lance or a dash, how far a projectile flies. */
    val range: Int = 3,
    val color: Long = 0xFFFFC107,
    val area: SkillArea = SkillArea(),
    /** What happens to each thing it reaches. Empty means one hit of [damageTypeId] at full power. */
    val effects: List<SkillEffect> = emptyList(),
    /** Labels supports and conditions read; see [SkillTags]. The delivery adds its own. */
    val tags: Set<String> = emptySet(),
    /** Seconds of wind-up before it lands. On a monster, the time the player has to see it coming. */
    val castTime: Float = 0f,
    /** Uses stored up; each recharges on the cooldown. */
    val charges: Int = 1,
    /** Life spent per use, on top of [resourceCost]. */
    val lifeCost: Int = 0,
    val projectile: ProjectileSpec = ProjectileSpec(),
    val zone: ZoneSpec = ZoneSpec(),
    /** For [SkillDelivery.SUMMON]: what answers the call. */
    val summon: SummonSpec? = null,
) {
    init {
        require(charges >= 1) { "skill '$id' needs at least one charge" }
        require(castTime >= 0f && cooldownSeconds >= 0f) { "skill '$id' has a negative time" }
    }

    /** Radius or length the delivery uses: the area's when it names one, the reach otherwise. */
    val reach: Float get() = if (area.radius > 0f) area.radius else range.toFloat()

    /**
     * Every tag the skill carries: its own, its delivery's, and one per damage
     * type it deals, so "with fire skills" needs nobody to remember to tag.
     */
    val allTags: Set<String>
        get() = buildSet {
            addAll(tags)
            addAll(delivery.impliedTags)
            if (SkillTags.ATTACK !in tags && SkillTags.SPELL !in tags) add(if (delivery.isAttack) SkillTags.ATTACK else SkillTags.SPELL)
            addAll(damageTypes)
        }

    /** The effects as they apply, with the implicit hit filled in. */
    val resolvedEffects: List<SkillEffect>
        get() = effects.ifEmpty { listOf(SkillEffect.Damage(damageTypeId)) }

    val damageTypes: Set<String>
        get() = resolvedEffects.filterIsInstance<SkillEffect.Damage>().mapTo(LinkedHashSet()) { it.damageTypeId }

    /** Helps rather than harms: no damage, and nothing aimed at an enemy. */
    val isBeneficial: Boolean
        get() = resolvedEffects.none { it is SkillEffect.Damage } && resolvedEffects.none { it.target == EffectTarget.TARGET }

    fun hasTag(tag: String): Boolean = tag in allTags
}

/**
 * How a skill reaches what it affects. The engine's verbs: each is a
 * targeting rule the whole game shares, which is what lets a player read a
 * pack's new skill at a glance.
 */
enum class SkillDelivery(val isAttack: Boolean, val impliedTags: Set<String>) {
    /** The nearest thing within reach. A strike. */
    MELEE(true, setOf(SkillTags.MELEE)),

    /** Everything in a wedge in front of the caster. A cleave, a breath. */
    CONE(true, setOf(SkillTags.MELEE, SkillTags.AREA)),

    /** Everything within the radius around the caster. */
    NOVA(false, setOf(SkillTags.AREA)),

    /** Everything within the radius around a point: the nearest enemy, or [SkillDefinition.range] ahead. */
    AREA(false, setOf(SkillTags.AREA)),

    /** Everything in a lane ahead of the caster. A lance, a beam. */
    BEAM(false, setOf(SkillTags.AREA)),

    /** Flies, collides with blocks, and hits what it touches; may pierce, chain and fork. */
    PROJECTILE(false, setOf(SkillTags.PROJECTILE)),

    /** Strikes the nearest thing, then leaps to the next nearest, [ProjectileSpec.chain] times. */
    CHAIN(false, setOf(SkillTags.CHAIN)),

    /** Carries the caster along its facing, hitting what it passes through. */
    DASH(true, setOf(SkillTags.MOVEMENT, SkillTags.MELEE)),

    /** Calls monsters to fight for the caster. */
    SUMMON(false, setOf(SkillTags.MINION, SkillTags.DURATION)),

    /** Only the caster, and allies in the radius for an effect aimed at allies. A war cry, an aura. */
    SELF(false, setOf(SkillTags.AURA)),

    /** A patch of ground that pulses its effects for a while. A trap, a totem, burning ground. */
    ZONE(false, setOf(SkillTags.AREA, SkillTags.DURATION, SkillTags.ZONE)),
    ;

    companion object {
        /**
         * The shapes packs wrote before skills were composable. Kept so every
         * old plugin still loads and plays exactly as it did.
         */
        fun fromLegacyShape(shape: String): SkillDelivery? = when (shape.trim().lowercase()) {
            "strike" -> MELEE
            "nova" -> NOVA
            "lance" -> BEAM
            else -> null
        }
    }
}

/** Tags the engine itself hands out or reads. Packs may use any other string too. */
object SkillTags {
    const val ATTACK = "attack"
    const val SPELL = "spell"
    const val MELEE = "melee"
    const val PROJECTILE = "projectile"
    const val AREA = "area"
    const val CHAIN = "chain"
    const val MOVEMENT = "movement"
    const val MINION = "minion"
    const val DURATION = "duration"
    const val AURA = "aura"
    const val ZONE = "zone"

    /** Carried by any cast a trigger caused, so a trigger can refuse to answer another trigger. */
    const val TRIGGERED = "triggered"
}

/** How big a skill is, beyond its reach. */
data class SkillArea(
    /** Radius of a nova, an area or a zone, and a chain's leap; 0 uses the skill's range. */
    val radius: Float = 0f,
    /** A cone's full width. */
    val angleDegrees: Float = 90f,
    /** Half the width of a beam's or a dash's lane. */
    val halfWidth: Float = 1f,
) {
    init {
        require(radius >= 0f && halfWidth >= 0f) { "an area cannot be negative" }
        require(angleDegrees in 0f..360f) { "a cone of $angleDegrees degrees is not a cone" }
    }
}

/**
 * A projectile's flight. Counts here are the skill's own; the caster's
 * projectile, pierce, chain and fork stats add to them.
 */
data class ProjectileSpec(
    val count: Int = 1,
    /** Blocks per second. */
    val speed: Float = 12f,
    /** Targets it passes through before it stops. */
    val pierce: Int = 0,
    /** Times it redirects to a new target after hitting one. Also a chain skill's leaps. */
    val chain: Int = 0,
    /** Times it splits in two on a hit. */
    val fork: Int = 0,
    /** Fan across all projectiles, in degrees. */
    val spreadDegrees: Float = 20f,
    /** How close it must pass to touch something, in blocks. */
    val radius: Float = 0.45f,
    /** Whether terrain stops it. Spectral things pass through walls. */
    val collidesWithBlocks: Boolean = true,
) {
    init {
        require(count >= 1) { "a projectile skill fires at least one" }
        require(speed > 0f) { "a projectile must move" }
        require(pierce >= 0 && chain >= 0 && fork >= 0) { "projectile counts cannot be negative" }
    }
}

/** A [SkillDelivery.ZONE]'s life. */
data class ZoneSpec(
    val durationSeconds: Float = 4f,
    /** Seconds between pulses. */
    val pulseSeconds: Float = 1f,
    /** Waits for something to step in, pulses once, and is gone. A trap. */
    val isTrap: Boolean = false,
) {
    init {
        require(durationSeconds > 0f && pulseSeconds > 0f) { "a zone needs a duration and a pulse" }
    }
}

/** What a [SkillDelivery.SUMMON] calls up: monsters from the loaded packs. */
data class SummonSpec(
    val enemyId: String,
    val count: Int = 1,
    /** Seconds before they fade; 0 keeps them until they die. */
    val durationSeconds: Float = 20f,
    /** The most of these one caster can have at once. */
    val limit: Int = 3,
)

/** Who an effect lands on. */
enum class EffectTarget {
    /** Each thing the delivery reached. */
    TARGET,

    /** The caster, once per cast. */
    SELF,

    /** The caster's allies within the skill's reach of where it landed, once per cast. */
    ALLIES,
}

/** What a skill does. The engine's verbs; a pack combines them. */
sealed interface SkillEffect {
    val target: EffectTarget

    /** A share of the skill's power as damage of one type. Several in one skill make one multi-typed hit. */
    data class Damage(val damageTypeId: String, val share: Float = 1f) : SkillEffect {
        override val target: EffectTarget get() = EffectTarget.TARGET
    }

    data class ApplyStatus(
        val statusId: String,
        val chance: Float = 1f,
        override val target: EffectTarget = EffectTarget.TARGET,
        val stacks: Int = 1,
    ) : SkillEffect

    /** Restores life: flat plus a share of the recipient's maximum. */
    data class Heal(val amount: Int = 0, val maxShare: Float = 0f, override val target: EffectTarget = EffectTarget.SELF) : SkillEffect

    data class RestoreResource(val amount: Int) : SkillEffect {
        override val target: EffectTarget get() = EffectTarget.SELF
    }

    /** Throws what it hits away from the caster. */
    data class Knockback(val force: Float) : SkillEffect {
        override val target: EffectTarget get() = EffectTarget.TARGET
    }

    /**
     * Casts another skill where this one landed -- the explosion at the end of
     * a fireball. Counts as a triggered cast, one level deeper, so a skill
     * that casts itself stops at the world's trigger depth.
     */
    data class CastSkill(val skillId: String, override val target: EffectTarget = EffectTarget.TARGET) : SkillEffect
}

/**
 * Live cooldown state, keyed by skill id: time to the next charge, and how
 * many charges are out.
 */
data class SkillCooldowns(
    private val remaining: Map<String, Float> = emptyMap(),
    private val spent: Map<String, Int> = emptyMap(),
    private val durations: Map<String, Float> = emptyMap(),
) {
    /** Seconds until the next charge comes back. */
    fun secondsLeft(skillId: String): Float = remaining[skillId] ?: 0f

    /** True when nothing is recharging. A skill with charges to spare can be used before this. */
    fun isReady(skillId: String): Boolean = secondsLeft(skillId) <= 0f && (spent[skillId] ?: 0) == 0

    /** True when at least one charge is available. */
    fun isReady(skill: SkillDefinition): Boolean = chargesLeft(skill) > 0

    fun chargesLeft(skill: SkillDefinition): Int = (skill.charges - (spent[skill.id] ?: 0)).coerceAtLeast(0)

    /** Spends a charge. The recharge clock starts if it was not already running. */
    fun started(skill: SkillDefinition): SkillCooldowns {
        if (skill.cooldownSeconds <= 0f) return this
        val out = (spent[skill.id] ?: 0) + 1
        val clock = remaining[skill.id]?.takeIf { it > 0f } ?: skill.cooldownSeconds
        return SkillCooldowns(remaining + (skill.id to clock), spent + (skill.id to out), durations + (skill.id to skill.cooldownSeconds))
    }

    fun advanced(deltaSeconds: Float): SkillCooldowns {
        if (remaining.isEmpty()) return this
        val nextRemaining = HashMap<String, Float>()
        val nextSpent = HashMap<String, Int>()
        remaining.forEach { (id, left) ->
            var clock = left - deltaSeconds
            var out = spent[id] ?: 1
            val duration = durations[id] ?: 0f
            while (clock <= 0f && out > 0) {
                out--
                if (out > 0 && duration > 0f) clock += duration else break
            }
            if (out > 0) {
                nextRemaining[id] = clock.coerceAtLeast(0f)
                nextSpent[id] = out
            }
        }
        return SkillCooldowns(nextRemaining, nextSpent, durations.filterKeys { it in nextRemaining })
    }

    fun fractionRemaining(skill: SkillDefinition): Float =
        if (skill.cooldownSeconds <= 0f || chargesLeft(skill) > 0 && skill.charges > 1) 0f
        else (secondsLeft(skill.id) / skill.cooldownSeconds).coerceIn(0f, 1f)
}
