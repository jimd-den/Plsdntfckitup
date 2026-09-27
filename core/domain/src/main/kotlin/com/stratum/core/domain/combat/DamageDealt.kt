package com.stratum.core.domain.combat

/** Where a piece of the player's damage came from, as a damage meter groups it. */
enum class DamageSourceKind(val label: String) {
    /** The basic swing. */
    ATTACK("Attack"),

    /** A skill the player cast by hand. */
    SKILL("Skill"),

    /** A skill cast by a trigger, a support or another skill's effect. */
    TRIGGER("Triggered"),

    /** Damage over time from a status the player's side inflicted. */
    AILMENT("Ailment"),

    /** A summon or follower fighting on the player's side. */
    MINION("Minion"),
}

/** One named source: the skill, the status or the minion, and what kind of source it is. */
data class DamageSource(val kind: DamageSourceKind, val id: String, val name: String)

/**
 * A piece of damage the player's side dealt: one hit, or one tick of damage
 * over time. The combat system reports these as it resolves them, so anything
 * that wants to count the fight -- a meter, a log, a test -- reads exactly the
 * numbers that came off the target's health, not a second estimate of them.
 */
data class DamageDealt(
    val source: DamageSource,
    val targetId: String,
    /** What came off the target, after every mitigation. Fractional for damage over time. */
    val amount: Float,
    /** The same damage by type. Sums to [amount]. */
    val packets: Map<String, Float> = emptyMap(),
    val critical: Boolean = false,
    val evaded: Boolean = false,
    val blocked: Boolean = false,
    /** A tick of damage over time rather than a hit. */
    val overTime: Boolean = false,
)
