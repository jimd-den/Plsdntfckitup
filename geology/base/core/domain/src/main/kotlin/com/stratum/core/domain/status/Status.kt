package com.stratum.core.domain.status

import com.stratum.core.domain.stats.StatModifier

/**
 * A status a body can carry: a burn, a chill, a war cry, a curse.
 *
 * Packs define statuses; the engine only knows [StatusBehaviour]s. That is
 * the whole trick of pack-defined ailments: "Anyanwu's Scorch" is a pack's
 * name for a status whose behaviour is damage over time, and the engine
 * neither knows nor cares that it is meant to be fire.
 */
data class StatusDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val behaviours: List<StatusBehaviour> = emptyList(),
    val durationSeconds: Float = 4f,
    val stacking: StackingRule = StackingRule.REFRESH,
    /** For [StackingRule.STACK] and [StackingRule.INTENSITY]: the most stacks one body can carry. */
    val maxStacks: Int = 1,
    /** Harmful to whoever carries it. Decides who a skill may put it on, and how it is drawn. */
    val isDebuff: Boolean = true,
    val color: Long = 0xFFB0BEC5,
    val symbol: String = "",
    /** Free-form labels conditions can test for, e.g. "burning" for "against burning enemies". */
    val tags: Set<String> = emptySet(),
) {
    init {
        require(durationSeconds > 0f) { "status '$id' must last some time, not $durationSeconds seconds" }
        require(maxStacks >= 1) { "status '$id' must allow at least one stack" }
    }
}

/** How reapplying a status combines with what is already there. */
enum class StackingRule {
    /** One instance. Reapplying restarts the clock and keeps the stronger potency. Bleeds, chills. */
    REFRESH,

    /** Independent instances, each with its own clock, up to the limit; the oldest falls off. Poisons. */
    STACK,

    /** One instance whose stack count grows, refreshing the clock. Shock that builds, a frenzy. */
    INTENSITY,
}

/**
 * What a status does. These are the engine's verbs; a pack combines them.
 * Every magnitude is per stack.
 */
sealed interface StatusBehaviour {

    /**
     * Damage every second of [damageTypeId], resisted like a hit but never
     * evaded, blocked or reduced by armour. [perSecond] is flat; [hitShare]
     * is the share of the inflicting hit dealt per second, which is how an
     * ailment scales with the build that caused it.
     */
    data class DamageOverTime(val damageTypeId: String, val perSecond: Float = 0f, val hitShare: Float = 0f) : StatusBehaviour

    /** Slows movement and action speed by [amount], 0..1. A slow of 1 is a freeze in all but name. */
    data class Slow(val amount: Float) : StatusBehaviour

    /** Cannot move, attack or cast. */
    data object Stun : StatusBehaviour

    /** Takes [amount] more damage, e.g. 0.15 for 15% increased; of one type, or all when null. */
    data class DamageTaken(val amount: Float, val damageTypeId: String? = null) : StatusBehaviour

    /** Any stat change for the duration: the generic buff or debuff. */
    data class Modifiers(val modifiers: List<StatModifier>) : StatusBehaviour

    /** Restores life every second: flat plus a share of maximum. */
    data class Recover(val perSecond: Float = 0f, val maxShare: Float = 0f) : StatusBehaviour
}

/**
 * A request to put a status on a body: from a hit's ailment roll, a skill
 * effect, a trigger or a flask. Rolled already -- by the time an application
 * exists, it happens.
 */
data class StatusApplication(
    val statusId: String,
    /** The damage of the hit that caused it, for [StatusBehaviour.DamageOverTime.hitShare]. */
    val potency: Float = 0f,
    /** Scales the status's own duration, from the source's duration modifiers. */
    val durationScale: Float = 1f,
    val stacks: Int = 1,
    /** Who applied it, so kills by damage over time are credited. */
    val sourceId: String? = null,
)

/** One status on one body, as it is right now. */
data class StatusInstance(
    val statusId: String,
    val remaining: Float,
    val duration: Float,
    val stacks: Int = 1,
    val potency: Float = 0f,
    val sourceId: String? = null,
) {
    /** 0 when fresh, 1 when about to expire, for a draining icon. */
    val progress: Float get() = if (duration <= 0f) 1f else (1f - remaining / duration).coerceIn(0f, 1f)
}
