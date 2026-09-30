package com.stratum.core.domain.combat

/** The moments a trigger can listen for. */
enum class TriggerEvent {
    /** Any hit that deals damage. */
    ON_HIT,
    ON_CRIT,
    ON_KILL,

    /** Being hit, after the hit lands. */
    ON_HIT_TAKEN,
    ON_BLOCK,
    ON_EVADE,

    /** Using any skill, including a basic attack. */
    ON_SKILL_USE,

    /** Dropping to low life. Fires once per crossing, not every frame spent there. */
    ON_LOW_LIFE,
}

/**
 * "When X happens, do Y": the build-breaking half of the combat core.
 *
 * Every trigger has an internal cooldown, never below the world's floor, and
 * every cast it causes is one level deeper than the cast that caused it; past
 * the world's depth nothing triggers at all. Those two guards are what let a
 * pack write "on hit, cast a nova that hits" without the phone locking up --
 * see `TriggerEngine` in the engine for where they are enforced.
 */
data class TriggerDefinition(
    val event: TriggerEvent,
    /** 0..1 chance it fires when the event happens. */
    val chance: Float = 1f,
    /** The trigger's own internal cooldown, in seconds. */
    val cooldownSeconds: Float = DEFAULT_COOLDOWN,
    /** Casts this skill, free of cost and cooldown, at the event's target (or the owner, for self events). */
    val castSkillId: String? = null,
    /** Puts this status on someone. */
    val applyStatusId: String? = null,
    /** Whether [applyStatusId] goes on the owner rather than the target. */
    val statusOnSelf: Boolean = false,
    /** Only events from skills carrying any of these tags count; empty means any. */
    val requiresTags: Set<String> = emptySet(),
    /** For [TriggerEvent.ON_LOW_LIFE]: the share of life that counts as low. */
    val lowLifeThreshold: Float = Condition.LOW_LIFE,
) {
    init {
        require(chance in 0f..1f) { "a trigger chance of $chance is not a share" }
        require(cooldownSeconds >= 0f) { "a trigger cooldown cannot be negative" }
    }

    companion object {
        const val DEFAULT_COOLDOWN = 0.25f
    }
}
