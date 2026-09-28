package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.StatModifier

/**
 * A named item with its own fixed modifiers: the thing a build is planned
 * around rather than the thing it is made of.
 *
 * A unique can carry [flags] that change a rule outright -- that is what
 * makes it build-enabling rather than merely big -- and its modifiers are
 * written freely, penalties included, because a unique that is only an upgrade
 * is a rare with a name.
 */
data class UniqueDefinition(
    val id: String,
    val name: String,
    /** The base it is made on: its slot, its hit and its defences. */
    val baseId: String,
    /** Modifiers to the wearer. Ranges roll once when it drops; a fixed number is a range of one. */
    val modifiers: List<ModifierRange> = emptyList(),
    /** Modifiers to the item's own base numbers, like a local affix. */
    val localModifiers: List<ModifierRange> = emptyList(),
    val flags: Set<BuildFlag> = emptySet(),
    /** The line in italics under the stats. */
    val flavour: String = "",
    /** Makes it a piece of that set rather than a unique on its own. */
    val setId: String? = null,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    /** Sockets it drops with; null for what its rarity would give. */
    val sockets: Int? = null,
    val glyph: String? = null,
)

/**
 * Pieces that are stronger together. Every unique naming a set is one of its
 * pieces, and wearing more distinct pieces unlocks more [bonuses].
 */
data class ItemSetDefinition(
    val id: String,
    val name: String,
    val bonuses: List<SetBonus>,
    val description: String = "",
)

/** What a set grants once [pieces] of it are worn. Bonuses stack: four pieces also get the two-piece bonus. */
data class SetBonus(
    val pieces: Int,
    val modifiers: List<StatModifier> = emptyList(),
    val flags: Set<BuildFlag> = emptySet(),
) {
    init {
        require(pieces >= 1) { "A set bonus needs at least one piece" }
    }

    val description: String
        get() = (modifiers.map { it.describe() } + flags.map { it.label }).joinToString(", ")
}
