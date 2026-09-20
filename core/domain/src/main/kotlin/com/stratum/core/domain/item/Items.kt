package com.stratum.core.domain.item

import com.stratum.core.domain.combat.CombatStats
import kotlin.math.roundToInt

/**
 * Item tiers. Structural rather than pack data: the whole loot loop is built on
 * the idea that better items carry more affixes, and a pack redefining that
 * would be redefining the game rather than reskinning it.
 *
 * Packs control the names and colours through [RarityStyle].
 */
enum class ItemRarity(val affixCount: Int, val weight: Int) {
    COMMON(0, 1000),
    UNCOMMON(2, 380),
    RARE(4, 90),
    EPIC(5, 18),
    RELIC(6, 3);

    companion object {
        val ordered = entries.toList()

        /** Total weight, used to turn a single 0..1 roll into a tier. */
        val totalWeight = entries.sumOf { it.weight }
    }
}

/** A pack's naming and colour for one tier. */
data class RarityStyle(
    val rarity: ItemRarity,
    val name: String,
    val color: Long,
)

/** What an affix does. Packs define these; the roller picks and rolls them. */
data class AffixDefinition(
    val id: String,
    /** Prefixes read before the base name, suffixes after: "Roped Blade of Storms". */
    val name: String,
    val kind: AffixKind,
    val stat: AffixStat,
    val minValue: Float,
    val maxValue: Float,
    /** Restricts an affix to a damage type, e.g. thunder resistance. */
    val damageTypeId: String? = null,
    /** Affixes below this item level never roll, which is what makes depth matter. */
    val minItemLevel: Int = 1,
    val weight: Int = 100,
) {
    init {
        require(maxValue >= minValue) { "Affix '$id' has an inverted value range" }
    }

    /** Rolls a value from a 0..1 sample supplied by the caller. */
    fun roll(sample: Float): Float = minValue + (maxValue - minValue) * sample.coerceIn(0f, 1f)
}

enum class AffixKind { PREFIX, SUFFIX }

enum class AffixStat {
    ATTACK_POWER,
    MAX_HEALTH,
    ARMOUR,
    CRIT_CHANCE,
    CRIT_MULTIPLIER,
    ATTACK_SPEED,
    RESISTANCE,
    LIFE_STEAL,
    MINING_SPEED,
}

/** A rolled affix on a specific item. */
data class AffixRoll(
    val definitionId: String,
    val name: String,
    val kind: AffixKind,
    val stat: AffixStat,
    val value: Float,
    val damageTypeId: String? = null,
) {
    /** How it reads in a tooltip. */
    val description: String
        get() = when (stat) {
            AffixStat.ATTACK_POWER -> "+${value.roundToInt()} attack"
            AffixStat.MAX_HEALTH -> "+${value.roundToInt()} health"
            AffixStat.ARMOUR -> "+${value.roundToInt()} armour"
            AffixStat.CRIT_CHANCE -> "+${(value * 100).roundToInt()}% crit chance"
            AffixStat.CRIT_MULTIPLIER -> "+${(value * 100).roundToInt()}% crit damage"
            AffixStat.ATTACK_SPEED -> "+${(value * 100).roundToInt()}% attack speed"
            AffixStat.RESISTANCE -> "+${(value * 100).roundToInt()}% ${damageTypeId?.substringAfter(':') ?: ""} resistance"
            AffixStat.LIFE_STEAL -> "+${(value * 100).roundToInt()}% life steal"
            AffixStat.MINING_SPEED -> "+${(value * 100).roundToInt()}% mining speed"
        }
}

/** A weapon archetype defined by a pack. */
data class WeaponBase(
    val id: String,
    val name: String,
    val description: String = "",
    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    val minDamage: Int = 8,
    val maxDamage: Int = 14,
    val attackSpeed: Float = 1.2f,
    val attackRange: Int = 1,
    val damageTypeId: String,
    /** Mining tier this weapon grants, so a pick is a weapon and a weapon is a pick. */
    val toolTier: Int = 1,
    val armour: Int = 0,
    /** How this weapon reads at a glance, in the world and in the bag alike. */
    val glyph: String = "⚔",
    val minItemLevel: Int = 1,
    val weight: Int = 100,
)

enum class EquipmentSlot { WEAPON, ARMOUR, CHARM }

/**
 * A specific item that exists in the world or in a bag.
 *
 * Holds its rolls rather than its computed stats, so the same item read by a
 * stronger character still shows what it actually rolled.
 */
data class ItemInstance(
    val instanceId: String,
    val baseId: String,
    val name: String,
    val rarity: ItemRarity,
    val itemLevel: Int,
    val slot: EquipmentSlot,
    val damageTypeId: String,
    val minDamage: Int,
    val maxDamage: Int,
    val baseAttackSpeed: Float,
    val attackRange: Int,
    val toolTier: Int,
    val baseArmour: Int,
    val affixes: List<AffixRoll> = emptyList(),
    val sockets: SocketSet = SocketSet.NONE,
    /** Carried from the base so a drop on the ground looks like what it is. */
    val glyph: String = "⚔",
) {
    val averageDamage: Int get() = (minDamage + maxDamage) / 2

    /** Extra mining speed from affixes, applied by the interaction system. */
    val miningSpeedBonus: Float
        get() = affixes.filter { it.stat == AffixStat.MINING_SPEED }.sumOf { it.value.toDouble() }.toFloat()

    val socketCount: Int get() = sockets.capacity

    val hasFreeSocket: Boolean get() = sockets.hasSpace

    /**
     * The item's contribution to its wearer's stats, including whatever is
     * slotted into it. Inserts are resolved through the supplied lookup rather
     * than stored, so a pack changing an insert's numbers changes every weapon
     * carrying one.
     */
    fun toStats(inserts: (String) -> InsertDefinition?): CombatStats {
        val slotted = sockets.insertIds.mapNotNull(inserts)
        return toStats() + SocketResolver.statsFor(slotted)
    }

    fun damageTypeWithSockets(inserts: (String) -> InsertDefinition?): String =
        SocketResolver.damageTypeFor(damageTypeId, sockets.insertIds.mapNotNull(inserts))

    fun miningSpeedWithSockets(inserts: (String) -> InsertDefinition?): Float =
        miningSpeedBonus + SocketResolver.miningBonusFor(sockets.insertIds.mapNotNull(inserts))

    /**
     * The item's contribution to its wearer's stats. Base damage folds into
     * attack power; affixes stack on top.
     */
    fun toStats(): CombatStats {
        var stats = CombatStats(
            maxHealth = 0,
            attackPower = averageDamage,
            armour = baseArmour,
            critChance = 0f,
            critMultiplier = 0f,
            attackSpeed = 0f,
            attackRange = attackRange,
        )
        val resistances = mutableMapOf<String, Float>()

        affixes.forEach { affix ->
            stats = when (affix.stat) {
                AffixStat.ATTACK_POWER -> stats.copy(attackPower = stats.attackPower + affix.value.roundToInt())
                AffixStat.MAX_HEALTH -> stats.copy(maxHealth = stats.maxHealth + affix.value.roundToInt())
                AffixStat.ARMOUR -> stats.copy(armour = stats.armour + affix.value.roundToInt())
                AffixStat.CRIT_CHANCE -> stats.copy(critChance = stats.critChance + affix.value)
                AffixStat.CRIT_MULTIPLIER -> stats.copy(critMultiplier = stats.critMultiplier + affix.value)
                AffixStat.ATTACK_SPEED -> stats.copy(attackSpeed = stats.attackSpeed + affix.value)
                AffixStat.LIFE_STEAL -> stats.copy(lifeSteal = stats.lifeSteal + affix.value)
                AffixStat.RESISTANCE -> {
                    affix.damageTypeId?.let { type ->
                        resistances[type] = (resistances[type] ?: 0f) + affix.value
                    }
                    stats
                }
                AffixStat.MINING_SPEED -> stats
            }
        }
        return stats.copy(resistances = resistances)
    }
}
