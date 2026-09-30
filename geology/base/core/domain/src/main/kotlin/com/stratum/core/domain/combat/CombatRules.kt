package com.stratum.core.domain.combat

/**
 * The ceilings and floors every fight in a world obeys.
 *
 * They are rules of the world rather than constants of the engine because
 * the same caps that keep an adventure fair are what a sandbox wants to lift:
 * a player who has spent an evening building a character that is immune to
 * fire should be able to switch the ceiling off and watch it work. The
 * defaults are the sane ones; [UNBOUND] is the "break it" preset.
 *
 * The one guard that is never optional is [triggerDepth]: a world can make it
 * deeper, but not infinite, because a chain of triggers that never ends is a
 * frozen phone, not a broken build.
 */
data class CombatRules(
    /** The most resistance counts for, before "maximum resistance" modifiers. */
    val resistanceCap: Float = CombatStats.MAX_RESISTANCE,
    /** What "maximum resistance" modifiers can raise the cap to. 1 allows true immunity. */
    val resistanceHardCap: Float = 0.95f,
    /** The deepest a vulnerability can go; -1 is double damage. */
    val minResistance: Float = CombatStats.MIN_RESISTANCE,
    val maxEvadeChance: Float = 0.75f,
    val maxBlockChance: Float = 0.75f,
    /** Armour can never take more than this share off one hit. */
    val maxArmourReduction: Float = 0.9f,
    /** No cooldown recovery brings a skill with a cooldown below this, in seconds. */
    val cooldownFloor: Float = 0.15f,
    /** Life leech per second, as a share of maximum life. Instant leech ignores it. */
    val maxLeechRate: Float = 0.2f,
    /** Critical strike chance can never exceed this. */
    val critChanceCap: Float = 1f,
    /** How many triggers deep a cast may be before it stops triggering anything. */
    val triggerDepth: Int = 3,
    /** No trigger fires more often than this, in seconds, whatever its own cooldown says. */
    val triggerCooldownFloor: Float = 0.1f,
    /** The most triggered casts one action or tick may cause, however it branches. */
    val triggerBudget: Int = 24,
    /** The most stacks any one status can build on one body. */
    val maxStatusStacks: Int = 20,
) {
    init {
        require(triggerDepth in 0..MAX_TRIGGER_DEPTH) { "triggerDepth $triggerDepth is outside 0..$MAX_TRIGGER_DEPTH" }
        require(triggerBudget in 0..MAX_TRIGGER_BUDGET) { "triggerBudget $triggerBudget is outside 0..$MAX_TRIGGER_BUDGET" }
        require(triggerCooldownFloor >= 0f && cooldownFloor >= 0f) { "cooldown floors cannot be negative" }
        require(minResistance <= resistanceCap && resistanceCap <= resistanceHardCap && resistanceHardCap <= 1f) {
            "resistances must run minResistance <= resistanceCap <= resistanceHardCap <= 1"
        }
        require(maxStatusStacks >= 1) { "maxStatusStacks must allow one stack" }
    }

    companion object {
        /**
         * Hard limits the world rules cannot exceed. Deep enough for any build
         * a person would design on purpose, shallow enough that a loop a pack
         * made by accident costs a few hundred hits, not the frame.
         */
        const val MAX_TRIGGER_DEPTH = 8
        const val MAX_TRIGGER_BUDGET = 256

        /** Everything a sandbox might want lifted, lifted. */
        val UNBOUND = CombatRules(
            resistanceCap = 1f,
            resistanceHardCap = 1f,
            minResistance = -3f,
            maxEvadeChance = 1f,
            maxBlockChance = 1f,
            maxArmourReduction = 1f,
            cooldownFloor = 0f,
            maxLeechRate = 10f,
            triggerDepth = MAX_TRIGGER_DEPTH,
            triggerCooldownFloor = 0f,
            triggerBudget = MAX_TRIGGER_BUDGET,
            maxStatusStacks = 999,
        )
    }
}
