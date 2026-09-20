package com.stratum.core.domain.item

import com.stratum.core.domain.combat.CombatStats
import kotlin.math.roundToInt

/**
 * Something you slot into a weapon.
 *
 * Packs define these, so a pack can ship bronze studs where another ships
 * runes. An insert is an item in its own right: it drops, it stacks in the bag,
 * and it can be pulled back out of whatever it was put into.
 */
data class InsertDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val stat: AffixStat,
    val value: Float,
    /** Set for resistance inserts, and for inserts that add a damage type. */
    val damageTypeId: String? = null,
    /**
     * Whether slotting this changes what the weapon deals, rather than only
     * adding numbers. A weapon carries at most one, the most recently slotted.
     */
    val convertsDamageType: Boolean = false,
    val tier: Int = 1,
    val glyph: String = "💠",
    val color: Long = 0xFF7FD4E0,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
) {
    /**
     * How the insert's effect reads in a tooltip. Borrows [AffixRoll]'s wording
     * so a "+12 attack" rune and a "+12 attack" affix read identically; the
     * player should not have to learn two vocabularies for one number.
     */
    val statLine: String
        get() = AffixRoll(id, name, AffixKind.SUFFIX, stat, value, damageTypeId).description
}

/**
 * Sockets on a specific weapon.
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
        require(capacity >= 0) { "A weapon cannot have negative sockets" }
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

    /** Empties everything, for a player taking a weapon apart before selling it. */
    fun cleared(): Pair<SocketSet, List<String>> =
        copy(filled = List(capacity) { null }) to insertIds

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
        }
    }
}

/**
 * Applies a weapon's inserts on top of its own stats.
 *
 * Separate from [ItemInstance.toStats] so the base item stays a pure function of
 * what it rolled, and a socketed weapon's contribution can be explained to the
 * player line by line.
 */
object SocketResolver {

    fun statsFor(inserts: List<InsertDefinition>): CombatStats {
        var stats = CombatStats(
            maxHealth = 0, attackPower = 0, armour = 0,
            critChance = 0f, critMultiplier = 0f, attackSpeed = 0f, attackRange = 0,
        )
        val resistances = mutableMapOf<String, Float>()

        inserts.forEach { insert ->
            stats = when (insert.stat) {
                AffixStat.ATTACK_POWER -> stats.copy(attackPower = stats.attackPower + insert.value.roundToInt())
                AffixStat.MAX_HEALTH -> stats.copy(maxHealth = stats.maxHealth + insert.value.roundToInt())
                AffixStat.ARMOUR -> stats.copy(armour = stats.armour + insert.value.roundToInt())
                AffixStat.CRIT_CHANCE -> stats.copy(critChance = stats.critChance + insert.value)
                AffixStat.CRIT_MULTIPLIER -> stats.copy(critMultiplier = stats.critMultiplier + insert.value)
                AffixStat.ATTACK_SPEED -> stats.copy(attackSpeed = stats.attackSpeed + insert.value)
                AffixStat.LIFE_STEAL -> stats.copy(lifeSteal = stats.lifeSteal + insert.value)
                AffixStat.RESISTANCE -> {
                    insert.damageTypeId?.let { resistances[it] = (resistances[it] ?: 0f) + insert.value }
                    stats
                }
                AffixStat.MINING_SPEED -> stats
            }
        }
        return stats.copy(resistances = resistances)
    }

    /** Extra mining speed from inserts, applied by the interaction system. */
    fun miningBonusFor(inserts: List<InsertDefinition>): Float =
        inserts.filter { it.stat == AffixStat.MINING_SPEED }
            .sumOf { it.value.toDouble() }
            .toFloat()

    /**
     * The damage type a socketed weapon deals.
     *
     * The most recently slotted converting insert wins, so re-slotting is how a
     * player changes their element rather than having to find a new weapon.
     */
    fun damageTypeFor(base: String, inserts: List<InsertDefinition>): String =
        inserts.lastOrNull { it.convertsDamageType && it.damageTypeId != null }
            ?.damageTypeId
            ?: base
}
