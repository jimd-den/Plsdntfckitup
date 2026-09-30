package com.stratum.core.domain.item

import com.stratum.core.domain.stats.StatModifier

/**
 * Something you slot into an item.
 *
 * Packs define these, so a pack can ship bronze studs where another ships
 * runes. An insert is an item in its own right: it drops, it stacks in the bag,
 * and it can be pulled back out of whatever it was put into.
 */
data class InsertDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** What it grants whoever wears the item it sits in. */
    val modifiers: List<StatModifier> = emptyList(),
    /**
     * Changes what a weapon deals, rather than only adding numbers. A weapon
     * carries at most one conversion, the most recently slotted; in armour or
     * jewellery a converter only grants its modifiers.
     */
    val convertsToDamageTypeId: String? = null,
    val tier: Int = 1,
    val glyph: String = "💠",
    val color: Long = 0xFF7FD4E0,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
) {
    /**
     * How the insert's effect reads in a tooltip. The same wording as an
     * affix, so a "+12 damage" rune and a "+12 damage" affix read
     * identically; the player should not have to learn two vocabularies for
     * one number.
     */
    val statLine: String
        get() = modifiers.joinToString(", ") { it.describe() }
}

/**
 * Sockets on a specific item.
 *
 * Held separately from the affixes an item rolled: affixes are fixed at the
 * drop, sockets are the part the player owns. Keeping them apart is what makes
 * "put it back how it was" possible.
 */
data class SocketSet(
    val capacity: Int = 0,
    /** Index-aligned with capacity; null is an empty socket. */
    val filled: List<String?> = List(capacity) { null },
) {
    init {
        require(capacity >= 0) { "An item cannot have negative sockets" }
    }

    val used: Int get() = filled.count { it != null }

    val free: Int get() = capacity - used

    val isEmpty: Boolean get() = used == 0

    val hasSpace: Boolean get() = free > 0

    val insertIds: List<String> get() = filled.filterNotNull()

    /** Slots into the first free socket, or returns null when full. */
    fun slotting(insertId: String): SocketSet? {
        val index = filled.indexOfFirst { it == null }
        if (index < 0) return null
        return copy(filled = filled.toMutableList().also { it[index] = insertId })
    }

    /** Empties one socket, returning the new set and what came out. */
    fun unslotting(socketIndex: Int): Pair<SocketSet, String>? {
        val insertId = filled.getOrNull(socketIndex) ?: return null
        return copy(filled = filled.toMutableList().also { it[socketIndex] = null }) to insertId
    }

    /** Empties everything, for a player taking an item apart before selling it. */
    fun cleared(): Pair<SocketSet, List<String>> =
        copy(filled = List(capacity) { null }) to insertIds

    /** Sockets only ever grow, and what is slotted stays slotted. */
    fun grownTo(capacity: Int): SocketSet =
        if (capacity <= this.capacity) this else SocketSet(capacity, filled + List(capacity - this.capacity) { null })

    companion object {
        val NONE = SocketSet(capacity = 0, filled = emptyList())

        fun of(capacity: Int) = SocketSet(capacity, List(capacity) { null })

        /**
         * How many sockets an item of this rarity rolls.
         *
         * Tied to rarity rather than rolled independently so a rare item is
         * unambiguously better than a common one, instead of a common with
         * three sockets outclassing it and muddying the whole tier system.
         */
        fun rolledFor(rarity: ItemRarity): Int = when (rarity) {
            ItemRarity.COMMON -> 0
            ItemRarity.UNCOMMON -> 1
            ItemRarity.RARE -> 2
            ItemRarity.EPIC -> 3
            ItemRarity.RELIC -> 4
            // A unique's definition can say otherwise; this is what it gets when it does not.
            ItemRarity.UNIQUE, ItemRarity.SET -> 2
        }
    }
}

/**
 * What an item's inserts add on top of its own rolls.
 *
 * Separate from [ItemInstance] so the item stays a record of what it rolled,
 * and a socketed item's contribution can be explained to the player line by line.
 */
object SocketResolver {

    fun modifiersFor(inserts: List<InsertDefinition>): List<StatModifier> = inserts.flatMap { it.modifiers }

    /**
     * The damage type a socketed weapon deals.
     *
     * The most recently slotted converting insert wins, so re-slotting is how a
     * player changes their element rather than having to find a new weapon.
     */
    fun damageTypeFor(base: String, inserts: List<InsertDefinition>): String =
        inserts.lastOrNull { it.convertsToDamageTypeId != null }?.convertsToDamageTypeId ?: base
}
