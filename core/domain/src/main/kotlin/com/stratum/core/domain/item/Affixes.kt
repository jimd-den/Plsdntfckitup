package com.stratum.core.domain.item

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.math.roundToInt

/**
 * A modifier whose value is rolled: "+(10-25) maximum health". Affix tiers,
 * implicits and uniques are all made of these, so every number on an item is
 * a [StatModifier] by the time anybody reads it, and stacks through the one
 * [com.stratum.core.domain.stats.StatSheet] formula with the tree and supports.
 */
data class ModifierRange(
    val stat: Stat,
    val kind: ModifierKind = ModifierKind.FLAT,
    val min: Float,
    val max: Float = min,
    /** For resistance, and anything else scoped to one damage type. */
    val damageTypeId: String? = null,
) {
    init {
        require(max >= min) { "A $stat range runs backwards: $min..$max" }
    }

    /**
     * Rolls a value from a 0..1 sample. Flat amounts of whole-number stats
     * roll whole numbers: "+13.4 health" reads as a bug even when it is not.
     */
    fun roll(sample: Float): StatModifier {
        val raw = min + (max - min) * sample.coerceIn(0f, 1f)
        return StatModifier(stat, kind, if (rollsWhole) raw.roundToInt().toFloat() else raw, damageTypeId)
    }

    private val rollsWhole: Boolean
        get() = kind == ModifierKind.FLAT && !stat.isPercent && min == min.roundToInt().toFloat() && max == max.roundToInt().toFloat()

    /** Whether [modifier] could have come from this range, for tests and for tempering. */
    fun admits(modifier: StatModifier): Boolean =
        modifier.stat == stat && modifier.kind == kind && modifier.damageTypeId == damageTypeId &&
            modifier.value >= min - tolerance && modifier.value <= max + tolerance

    /** The same range with its numbers multiplied, for a stronger tier of a generated base. */
    fun scaled(factor: Float): ModifierRange =
        if (rollsWhole) copy(min = (min * factor).roundToInt().toFloat(), max = (max * factor).roundToInt().toFloat())
        else copy(min = min * factor, max = max * factor)

    private val tolerance: Float get() = if (rollsWhole) 0.5f else 1e-4f
}

enum class AffixKind {
    PREFIX,
    SUFFIX,

    /** The fixed modifiers of a unique or set piece: neither prefix nor suffix, and not rerollable into anything else. */
    UNIQUE,
}

/**
 * One strength of an affix. Deeper tiers only roll on items found deeper,
 * which is what makes item level a number worth reading.
 */
data class AffixTier(
    val modifiers: List<ModifierRange>,
    val minItemLevel: Int = 1,
    /** Overrides the affix's name at this tier: "Weighted" at the bottom, "Colossal" at the top. */
    val name: String? = null,
    val weight: Int = 100,
) {
    init {
        require(modifiers.isNotEmpty()) { "An affix tier must change something" }
    }
}

/**
 * What an affix does, at every strength it comes in. Packs define these; the
 * generator picks and rolls them.
 *
 * Tiers run from weakest to strongest. An affix belongs to a [group], and an
 * item never carries two affixes of one group, so "+health" and "+more health"
 * cannot stack on one ring however many a pack defines.
 */
data class AffixDefinition(
    val id: String,
    /** Prefixes read before the base name, suffixes after: "Roped Blade of Storms". */
    val name: String,
    val kind: AffixKind,
    val tiers: List<AffixTier>,
    val group: String = id,
    /** The item must carry at least one of these tags to roll it. Empty rolls on anything. */
    val tags: Set<String> = emptySet(),
    /** The kinds of item it rolls on. Empty rolls on every kind. */
    val slots: Set<ItemSlot> = emptySet(),
    /**
     * Whether it changes the item's own numbers -- "40% increased armour"
     * scaling only this helmet's armour, "adds 5 damage" to this weapon's
     * hit -- rather than its wearer's. Local modifiers on a stat the item has
     * no base value for fall back to applying to the wearer.
     */
    val local: Boolean = false,
    val weight: Int = 100,
) {
    init {
        require(kind != AffixKind.UNIQUE) { "Affix '$id': unique modifiers belong to a unique, not an affix" }
        require(tiers.isNotEmpty()) { "Affix '$id' has no tiers" }
    }

    /** The shallowest item level any tier of it rolls at. */
    val minItemLevel: Int get() = tiers.minOf { it.minItemLevel }

    /** Whether it may roll on [item] at all, ignoring item level. */
    fun fits(slot: ItemSlot, itemTags: Set<String>): Boolean =
        (slots.isEmpty() || slot in slots) && (tags.isEmpty() || tags.any { it in itemTags })

    /** Tier numbers (1 is the weakest) that roll at [itemLevel]. */
    fun tiersAt(itemLevel: Int): List<Int> = tiers.indices.filter { tiers[it].minItemLevel <= itemLevel }.map { it + 1 }

    fun tier(number: Int): AffixTier? = tiers.getOrNull(number - 1)

    /** Rolls [tierNumber] of this affix from the supplied samples, one per modifier. */
    fun roll(tierNumber: Int, sample: () -> Float): AffixRoll {
        val tier = requireNotNull(tier(tierNumber)) { "Affix '$id' has no tier $tierNumber" }
        return AffixRoll(id, tier.name ?: name, kind, tier.modifiers.map { it.roll(sample()) }, tierNumber, group, local)
    }

    companion object {
        /** An affix that comes in one strength: the shape packs wrote before tiers existed. */
        fun single(
            id: String,
            name: String,
            kind: AffixKind,
            range: ModifierRange,
            minItemLevel: Int = 1,
            weight: Int = 100,
            group: String = id,
            slots: Set<ItemSlot> = emptySet(),
            tags: Set<String> = emptySet(),
            local: Boolean = false,
        ) = AffixDefinition(id, name, kind, listOf(AffixTier(listOf(range), minItemLevel)), group, tags, slots, local, weight)

        /** An old-style affix, from one of the nine [AffixStat]s and a value range. */
        fun legacy(
            id: String,
            name: String,
            kind: AffixKind,
            stat: AffixStat,
            minValue: Float,
            maxValue: Float,
            damageTypeId: String? = null,
            minItemLevel: Int = 1,
            weight: Int = 100,
        ) = single(id, name, kind, stat.range(minValue, maxValue, damageTypeId), minItemLevel, weight)
    }
}

/** A rolled affix on a specific item: its modifiers, fixed at the roll. */
data class AffixRoll(
    val definitionId: String,
    val name: String,
    val kind: AffixKind,
    val modifiers: List<StatModifier>,
    /** Which tier of its definition it rolled, 1 being the weakest. */
    val tier: Int = 1,
    val group: String = definitionId,
    val local: Boolean = false,
) {
    /** How it reads in a tooltip. */
    val description: String
        get() = modifiers.joinToString(", ") { it.describe() }
}

/**
 * The nine stats affixes and inserts named before they were modifiers.
 *
 * Kept so the packs and saves written in that vocabulary still read; each
 * maps to the modifier that reproduces its old effect. New content names a
 * [Stat] and a [ModifierKind] directly.
 */
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
    ;

    /**
     * The stat and kind this used to mean. Attack and mining speed read as
     * percentages on the old tooltips, so they become "increased"; the rest
     * were flat additions and stay flat.
     */
    val stat: Stat
        get() = when (this) {
            ATTACK_POWER -> Stat.DAMAGE
            MAX_HEALTH -> Stat.MAX_HEALTH
            ARMOUR -> Stat.ARMOUR
            CRIT_CHANCE -> Stat.CRIT_CHANCE
            CRIT_MULTIPLIER -> Stat.CRIT_MULTIPLIER
            ATTACK_SPEED -> Stat.ATTACK_SPEED
            RESISTANCE -> Stat.RESISTANCE
            LIFE_STEAL -> Stat.LIFE_STEAL
            MINING_SPEED -> Stat.MINING_SPEED
        }

    val kind: ModifierKind
        get() = if (this == ATTACK_SPEED || this == MINING_SPEED) ModifierKind.INCREASED else ModifierKind.FLAT

    /** The damage type only resistance was ever scoped to; other stats ignore it. */
    private fun scope(damageTypeId: String?): String? = damageTypeId.takeIf { this == RESISTANCE }

    fun modifier(value: Float, damageTypeId: String? = null) = StatModifier(stat, kind, value, scope(damageTypeId))

    fun range(min: Float, max: Float, damageTypeId: String? = null) = ModifierRange(stat, kind, min, max, scope(damageTypeId))
}
