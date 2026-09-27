package com.stratum.core.domain.stats

/**
 * A rule a build can break. Modifiers move numbers; a flag changes what the
 * numbers mean -- skills paid in blood, hits that never crit -- which is where
 * the builds people remember come from.
 *
 * The engine's vocabulary, like [ModifierKind]: a pack grants flags from
 * uniques, set bonuses and anything else that carries them, but cannot invent
 * one, because a flag nothing reads would be a promise the game breaks.
 * Combat reads them from the wearer's gear; each one says what it does.
 */
enum class BuildFlag(val label: String) {
    /** Skills spend health instead of their resource; the resource pool goes unused. */
    SKILLS_COST_HEALTH("Skills cost health instead of their resource"),

    /** Hits never land critically. Items carrying it pay for that in raw damage. */
    CANNOT_CRIT("Your hits can never be critical"),

    /** Damage taken is paid from the resource pool first, and from health only once it is empty. */
    RESOURCE_SHIELDS_HEALTH("Damage is taken from your resource before your health"),

    /** Skills deal the equipped weapon's damage type rather than their own. */
    SKILLS_USE_WEAPON_TYPE("Skills deal your weapon's damage type"),

    /** Hits ignore the target's resistances entirely. */
    HITS_IGNORE_RESISTANCE("Your hits ignore resistances"),

    /** Life steal is no longer capped, so a hit can return more than it dealt. */
    LIFE_STEAL_UNCAPPED("Life steal is uncapped"),
}
