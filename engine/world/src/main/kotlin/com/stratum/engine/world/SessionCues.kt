package com.stratum.engine.world

import com.stratum.core.domain.world.WorldPoint

/**
 * The words and numbers a session floats over the world, named for what
 * happened rather than for how they look.
 *
 * Every size, colour and duration decision lives here, so the rules that
 * produce a hit say "a critical landed" and never how big the number is.
 */
internal class SessionCues(private val log: FeedbackLog = FeedbackLog()) {

    val active: List<FeedbackMark> get() = log.active

    fun advance(deltaSeconds: Float) = log.advance(deltaSeconds)

    fun clear() = log.clear()

    /** An attack that would have landed, and did not because of a roll. */
    fun dodged(at: WorldPoint) = log.add(FeedbackKind.DODGED, "DODGED", at, DODGE, emphasis = 1.1f)

    fun hurt(amount: Int, at: WorldPoint) = log.add(FeedbackKind.DAMAGE_TAKEN, "-$amount", at, HURT, emphasis = 1.2f)

    fun fallen(at: WorldPoint) = log.add(FeedbackKind.KILL, "FALLEN", at, HURT, emphasis = 1.8f, lifetime = 2.5f)

    fun blocked(at: WorldPoint) = log.add(FeedbackKind.BLOCKED, "BLOCKED", at, BLOCKED, emphasis = 0.85f)

    /**
     * Crits read as bigger and last longer. A critical the player cannot tell
     * from a normal hit is a stat they have no reason to build for.
     */
    fun critical(amount: Int, at: WorldPoint, color: Long) =
        log.add(FeedbackKind.CRITICAL, "$amount!", at, color, emphasis = 1.7f, lifetime = 1.2f)

    fun dealt(amount: Int, at: WorldPoint, color: Long) = log.add(FeedbackKind.DAMAGE_DEALT, amount.toString(), at, color)

    fun healed(amount: Int, at: WorldPoint) = log.add(FeedbackKind.HEAL, "+$amount", at, HEAL)

    fun levelUp(level: Int, at: WorldPoint) =
        log.add(FeedbackKind.LEVEL_UP, "LEVEL $level", at, LEVEL, emphasis = 1.9f, lifetime = 1.8f)

    /** An upgrade that equipped itself is announced louder than one put in the bag. */
    fun itemTaken(name: String, at: WorldPoint, color: Long, equipped: Boolean) =
        log.add(FeedbackKind.LOOT, name, at, color, emphasis = if (equipped) 1.3f else 1f, lifetime = 1.4f)

    fun insertTaken(name: String, at: WorldPoint, color: Long) =
        log.add(FeedbackKind.LOOT, name, at, color, emphasis = 1.1f, lifetime = 1.3f)

    fun insertSlotted(name: String, at: WorldPoint, color: Long?) =
        log.add(FeedbackKind.LOOT, name, at, color ?: BUILT, emphasis = 1.2f, lifetime = 1.2f)

    fun built(count: Int, at: WorldPoint) = log.add(FeedbackKind.LOOT, "Built $count", at, BUILT)

    fun cleared(count: Int, at: WorldPoint) = log.add(FeedbackKind.LOOT, "Cleared $count", at, BUILT)

    private companion object {
        const val HURT = 0xFFD2544BL
        const val DODGE = 0xFF7FD4E0L
        const val BLOCKED = 0xFF9A96A8L
        const val HEAL = 0xFF7BC67EL
        const val LEVEL = 0xFFFFC107L
        const val BUILT = 0xFF8FB8DEL
    }
}
