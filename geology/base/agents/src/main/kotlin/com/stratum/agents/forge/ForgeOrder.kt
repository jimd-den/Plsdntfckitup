package com.stratum.agents.forge

import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier

/**
 * What a person asks the forge for, beyond the words: the setting around
 * the request, the depth it is for, and how strong it may be.
 */
data class ForgeContext(
    /** The world's tone in a phrase, when the prompt does not carry it: "grimdark river delta". */
    val theme: String = "",
    /** Item levels the result is for. Its numbers are judged against the bottom of this. */
    val levels: IntRange = 1..20,
    /**
     * Balanced results are scaled into the budget; strong ones only when
     * they go past strong; broken ones are allowed through, labelled.
     */
    val budget: PowerTier = PowerTier.BALANCED,
    val modelId: String? = null,
) {
    init {
        require(levels.first >= 1 && levels.last >= levels.first) { "Item levels $levels are not a range" }
    }
}

/**
 * One thing the forge can make. Each is a request type on the one agent
 * pipeline -- a small crew with its own sections, guidance and repair --
 * not a generator of its own.
 */
sealed interface ForgeOrder {
    /** In the person's words: "a spear that remembers every river it crossed". */
    val prompt: String

    /** Codex entries tied to things that exist. */
    data class Lore(
        override val prompt: String,
        /** Null lets the model choose among history, deities, artifacts, beasts, places and rites. */
        val category: LoreCategory? = null,
        /** Ids of existing content the entries should be about; empty lets the model pick. */
        val subjects: List<String> = emptyList(),
        val count: Int = 3,
    ) : ForgeOrder

    /**
     * A weapon or a piece of armour. With [ladder], a family of the same
     * kind that climbs through the level range, one rung per depth.
     */
    data class Base(
        override val prompt: String,
        val slot: ItemSlot = ItemSlot.WEAPON,
        val ladder: Boolean = false,
    ) : ForgeOrder

    /** Tiered affixes, each kept to the slots it suits. */
    data class Affixes(
        override val prompt: String,
        /** Empty lets each affix choose where it rolls. */
        val slots: Set<ItemSlot> = emptySet(),
        val count: Int = 3,
    ) : ForgeOrder

    /**
     * A named item a build is planned around, on an existing base, with the
     * lore that explains it. Rule-bending is the point.
     */
    data class Unique(
        override val prompt: String,
        /** Null lets the model choose the kind of item. */
        val slot: ItemSlot? = null,
    ) : ForgeOrder

    /** Pieces that are stronger together, each a unique, with the set's bonuses and its story. */
    data class ItemSet(
        override val prompt: String,
        val pieces: Int = 3,
    ) : ForgeOrder
}

/** The kinds a person picks from, in the order the forge offers them. */
enum class ForgeKind(val label: String, val glyph: String) {
    LORE("Lore", "📜"),
    WEAPON("Weapon", "⚔"),
    ARMOUR("Armour", "🛡"),
    UNIQUE("Unique", "✦"),
    SET("Set", "❖"),
    AFFIXES("Affixes", "✧"),
    ;

    /** The order this kind makes from [prompt], with the options a screen offers. */
    fun order(prompt: String, slot: ItemSlot? = null, ladder: Boolean = false, count: Int? = null): ForgeOrder = when (this) {
        LORE -> ForgeOrder.Lore(prompt, count = count ?: 3)
        WEAPON -> ForgeOrder.Base(prompt, ItemSlot.WEAPON, ladder)
        ARMOUR -> ForgeOrder.Base(prompt, slot?.takeIf { it != ItemSlot.WEAPON } ?: ItemSlot.CHEST, ladder)
        UNIQUE -> ForgeOrder.Unique(prompt, slot)
        SET -> ForgeOrder.ItemSet(prompt, count ?: 3)
        AFFIXES -> ForgeOrder.Affixes(prompt, setOfNotNull(slot), count ?: 3)
    }
}
