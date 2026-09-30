package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * How strong a piece of gear reads against what the game expects at its
 * depth. A label, not a cap: this is a sandbox and a broken build is a thing
 * a player may ask for on purpose. What matters is that it is *known* to be
 * broken, so a person picks it rather than stumbles on it.
 */
enum class PowerTier(val label: String) {
    BALANCED("Balanced"),
    STRONG("Strong"),
    BROKEN("Broken"),
    ;

    companion object {
        /** The tier a ratio of score to allowance reads as. */
        fun of(ratio: Float): PowerTier = when {
            ratio <= PowerBudget.BALANCED_LIMIT -> BALANCED
            ratio <= PowerBudget.STRONG_LIMIT -> STRONG
            else -> BROKEN
        }
    }
}

/**
 * One piece of gear weighed: [score] in affix points (one ordinary affix at
 * its depth is worth one), against the [allowance] a thing of its kind gets.
 */
data class PowerEstimate(val score: Float, val allowance: Float) {
    val ratio: Float get() = if (allowance <= 0f) 0f else score / allowance
    val tier: PowerTier get() = PowerTier.of(ratio)

    /** "Strong · 1.6× budget", for a card. */
    val label: String get() = "${tier.label} · ${(ratio * 10f).roundToInt() / 10f}× budget"
}

/**
 * Estimates how strong gear is, and scales it down to a tier when asked.
 *
 * Every modifier is measured in *affix points*: its value divided by what an
 * ordinary affix of that stat and kind gives at the item's level. Flat
 * amounts of whole stats grow with depth the way base numbers do, shares
 * grow slowly, and "more" is worth a third again as much as "increased",
 * because it multiplies alone. Penalties count for less than their size --
 * a drawback should not buy its full price back -- and rule-changing flags
 * carry a fixed weight of their own.
 *
 * The numbers are a yardstick rather than a simulation: calibrated so the
 * built-in pack's gear reads as balanced or strong, and a model's "+500%
 * damage" reads as broken.
 */
object PowerBudget {

    const val BALANCED_LIMIT = 1.25f
    const val STRONG_LIMIT = 2.0f

    /** Where a scaled-down result aims, a little under the limit so rounding cannot push it over. */
    private const val BALANCED_TARGET = 1.0f
    private const val STRONG_TARGET = 1.9f

    /** An ordinary unique is worth about a rare's worth of affixes. */
    const val UNIQUE_ALLOWANCE = 5f

    /** A set bonus's allowance per piece it asks for. */
    const val SET_BONUS_ALLOWANCE_PER_PIECE = 1f

    /** What a base's implicits may be worth before they count against it. */
    const val IMPLICIT_ALLOWANCE = 1f

    /** An ordinary weapon's damage per second at item level 1. */
    const val REFERENCE_DPS = 17f

    private const val WHOLE_GROWTH = 0.08f
    private const val SHARE_GROWTH = 0.02f
    private const val BASE_GROWTH = 0.05f
    private const val MORE_WORTH = 4f / 3f
    private const val PENALTY_WEIGHT = 0.6f

    /** For a stat added after these tables were written: measured like an average one until it gets its own line. */
    private const val DEFAULT_FLAT = 10f
    private const val DEFAULT_SHARE = 0.2f

    /** One ordinary affix's worth of each stat as a flat amount, at item level 1. */
    private val flatReference: Map<Stat, Float> = mapOf(
        Stat.MAX_HEALTH to 25f, Stat.DAMAGE to 8f, Stat.ARMOUR to 20f, Stat.CRIT_CHANCE to 0.05f,
        Stat.CRIT_MULTIPLIER to 0.25f, Stat.ATTACK_SPEED to 0.15f, Stat.LIFE_STEAL to 0.06f, Stat.RESISTANCE to 0.15f,
        Stat.SKILL_DAMAGE to 8f, Stat.AREA to 0.15f, Stat.COOLDOWN_RECOVERY to 0.12f, Stat.RESOURCE_COST to -5f,
        Stat.MOVE_SPEED to 0.08f, Stat.MAX_RESOURCE to 20f, Stat.EXPERIENCE_GAIN to 0.1f, Stat.ITEM_RARITY to 0.15f,
        Stat.ITEM_QUANTITY to 0.08f, Stat.MINING_SPEED to 0.4f,
    )

    /** One ordinary affix's worth of each stat as "increased". "More" is worth [MORE_WORTH] of these. */
    private val increasedReference: Map<Stat, Float> = mapOf(
        Stat.MAX_HEALTH to 0.12f, Stat.DAMAGE to 0.3f, Stat.ARMOUR to 0.3f, Stat.CRIT_CHANCE to 0.3f,
        Stat.CRIT_MULTIPLIER to 0.25f, Stat.ATTACK_SPEED to 0.12f, Stat.LIFE_STEAL to 0.3f, Stat.RESISTANCE to 0.3f,
        Stat.SKILL_DAMAGE to 0.25f, Stat.AREA to 0.2f, Stat.COOLDOWN_RECOVERY to 0.12f, Stat.RESOURCE_COST to -0.12f,
        Stat.MOVE_SPEED to 0.08f, Stat.MAX_RESOURCE to 0.15f, Stat.EXPERIENCE_GAIN to 0.12f, Stat.ITEM_RARITY to 0.15f,
        Stat.ITEM_QUANTITY to 0.08f, Stat.MINING_SPEED to 0.4f,
    )

    /**
     * What a base's defences may be worth, in points, by where it is worn:
     * a breastplate is mostly armour, a ring hardly any.
     */
    private fun defenceAllowance(slot: ItemSlot): Float = when (slot) {
        ItemSlot.CHEST -> 1.25f
        ItemSlot.OFFHAND -> 1f
        ItemSlot.HELM -> 0.7f
        ItemSlot.BOOTS -> 0.5f
        ItemSlot.GLOVES -> 0.45f
        ItemSlot.WEAPON -> 0.3f
        ItemSlot.BELT, ItemSlot.AMULET -> 0.2f
        ItemSlot.RING -> 0.15f
    }

    /**
     * A flag's weight in points. Positive flags open builds; a flag that
     * takes something away is a drawback and counts against the item.
     */
    fun flag(flag: BuildFlag): Float = when (flag) {
        BuildFlag.SKILLS_COST_HEALTH -> 1f
        BuildFlag.CANNOT_CRIT -> -0.5f
        BuildFlag.RESOURCE_SHIELDS_HEALTH -> 2f
        BuildFlag.SKILLS_USE_WEAPON_TYPE -> 1f
        BuildFlag.HITS_IGNORE_RESISTANCE -> 3f
        BuildFlag.LIFE_STEAL_UNCAPPED -> 2.5f
    }

    /** What one ordinary affix of [stat] and [kind] gives at [itemLevel]. */
    fun reference(stat: Stat, kind: ModifierKind, itemLevel: Int): Float {
        val atOne = when (kind) {
            ModifierKind.FLAT -> flatReference[stat] ?: if (stat.isPercent) DEFAULT_SHARE else DEFAULT_FLAT
            ModifierKind.INCREASED -> increasedReference[stat] ?: DEFAULT_SHARE
            ModifierKind.MORE -> (increasedReference[stat] ?: DEFAULT_SHARE) / MORE_WORTH
        }
        val growth = if (kind == ModifierKind.FLAT && !stat.isPercent) WHOLE_GROWTH else SHARE_GROWTH
        return atOne * (1f + (itemLevel.coerceAtLeast(1) - 1) * growth)
    }

    /**
     * One modifier's worth in points: positive when it helps its wearer,
     * negative when it is a penalty. A resistance to every type at once
     * counts twice, since it covers what a scoped one covers and the rest.
     */
    fun modifier(stat: Stat, kind: ModifierKind, value: Float, itemLevel: Int, damageTypeId: String? = null): Float {
        val raw = value / reference(stat, kind, itemLevel)
        val scoped = if (stat == Stat.RESISTANCE && damageTypeId == null) raw * 2f else raw
        return if (scoped < 0f) scoped * PENALTY_WEIGHT else scoped
    }

    fun modifier(m: StatModifier, itemLevel: Int): Float = modifier(m.stat, m.kind, m.value, itemLevel, m.damageTypeId)

    /** A range at its best roll, since what matters is how strong it can be. */
    fun range(r: ModifierRange, itemLevel: Int): Float {
        val best = if (modifier(r.stat, r.kind, r.max, itemLevel, r.damageTypeId) >= modifier(r.stat, r.kind, r.min, itemLevel, r.damageTypeId)) r.max else r.min
        return modifier(r.stat, r.kind, best, itemLevel, r.damageTypeId)
    }

    private fun ranges(ranges: List<ModifierRange>, itemLevel: Int): Float = ranges.sumOf { range(it, itemLevel).toDouble() }.toFloat()

    /** One tier of an affix against one affix's worth at the depth it first rolls. */
    fun affixTier(tier: AffixTier): PowerEstimate = PowerEstimate(ranges(tier.modifiers, tier.minItemLevel), 1f)

    /** An affix reads as its strongest tier. */
    fun affix(affix: AffixDefinition): PowerEstimate = affix.tiers.map(::affixTier).maxBy { it.ratio }

    fun unique(unique: UniqueDefinition): PowerEstimate = PowerEstimate(
        ranges(unique.modifiers + unique.localModifiers, unique.minItemLevel) + unique.flags.sumOf { flag(it).toDouble() }.toFloat(),
        UNIQUE_ALLOWANCE,
    )

    /**
     * A base's two budgets -- what it is (its hit, or its defences) and what
     * it rolls with (its implicits) -- each against its own allowance, read
     * as the worse. One cannot be bought with the other: a weak sword with a
     * huge implicit is still a huge implicit.
     */
    fun base(base: ItemBase): PowerEstimate {
        val main = mainRatio(base)
        val implicit = ranges(base.implicits, base.minItemLevel) / IMPLICIT_ALLOWANCE
        return PowerEstimate(maxOf(main, implicit), 1f)
    }

    private fun mainRatio(base: ItemBase): Float {
        val weapon = base.weapon
        return if (weapon != null) {
            dps(weapon) / referenceDps(base.minItemLevel)
        } else {
            base.defences.sumOf { modifier(it, base.minItemLevel).toDouble() }.toFloat() / defenceAllowance(base.slot)
        }
    }

    private fun dps(weapon: WeaponProfile): Float = (weapon.minDamage + weapon.maxDamage) / 2f * weapon.attackSpeed

    /** Authored numbers are level-one numbers that grow as they drop deeper, so a deep base only earns a little more. */
    private fun referenceDps(itemLevel: Int): Float = REFERENCE_DPS * (1f + (itemLevel.coerceAtLeast(1) - 1) * BASE_GROWTH)

    /** A set's bonuses together, against what the pieces they ask for may unlock. Its pieces are weighed as uniques. */
    fun set(set: ItemSetDefinition, itemLevel: Int): PowerEstimate = PowerEstimate(
        set.bonuses.sumOf { bonus -> bonusScore(bonus, itemLevel).toDouble() }.toFloat(),
        set.bonuses.sumOf { it.pieces }.coerceAtLeast(1) * SET_BONUS_ALLOWANCE_PER_PIECE,
    )

    private fun bonusScore(bonus: SetBonus, itemLevel: Int): Float =
        bonus.modifiers.sumOf { modifier(it, itemLevel).toDouble() }.toFloat() + bonus.flags.sumOf { flag(it).toDouble() }.toFloat()

    /** The ratio a result should be brought down to for [tier], or null when anything goes. */
    fun ceiling(tier: PowerTier): Float? = when (tier) {
        PowerTier.BALANCED -> BALANCED_TARGET
        PowerTier.STRONG -> STRONG_TARGET
        PowerTier.BROKEN -> null
    }

    /** The limit above which a result no longer reads as [tier]. */
    fun limit(tier: PowerTier): Float = when (tier) {
        PowerTier.BALANCED -> BALANCED_LIMIT
        PowerTier.STRONG -> STRONG_LIMIT
        PowerTier.BROKEN -> Float.MAX_VALUE
    }

    /**
     * The factor to multiply the helpful part of something by so that the
     * whole reads at [target]: `f * helpful + rest = target * allowance`.
     * One when it is already there, null when even removing all the help
     * would not get it there -- the rest (flags) is the problem then.
     */
    fun factor(helpful: Float, rest: Float, allowance: Float, target: Float): Float? {
        val wanted = target * allowance
        if (helpful + rest <= wanted) return 1f
        if (helpful <= 0f) return null
        val factor = (wanted - rest) / helpful
        return factor.takeIf { it > MIN_FACTOR }
    }

    /** Below this a scaled item would be a shadow of what was asked for; drop a flag instead. */
    private const val MIN_FACTOR = 0.05f

    /** [unique] brought within [tier], dropping its strongest flags when scaling alone cannot. Unchanged when it already fits. */
    fun fit(unique: UniqueDefinition, tier: PowerTier): UniqueDefinition {
        val target = ceiling(tier) ?: return unique
        if (unique(unique).ratio <= limit(tier)) return unique
        var flags = unique.flags
        while (true) {
            val candidate = unique.copy(flags = flags)
            val all = candidate.modifiers + candidate.localModifiers
            val helpful = all.sumOf { range(it, unique.minItemLevel).coerceAtLeast(0f).toDouble() }.toFloat()
            val rest = unique(candidate).score - helpful
            val factor = factor(helpful, rest, UNIQUE_ALLOWANCE, target)
            if (factor != null) {
                return candidate.copy(
                    modifiers = candidate.modifiers.map { scaledIfHelpful(it, factor, unique.minItemLevel) },
                    localModifiers = candidate.localModifiers.map { scaledIfHelpful(it, factor, unique.minItemLevel) },
                )
            }
            val strongest = flags.maxByOrNull { flag(it) } ?: return candidate
            flags = flags - strongest
        }
    }

    /** Each tier of [affix] brought within [tier] against its own depth. */
    fun fit(affix: AffixDefinition, tier: PowerTier): AffixDefinition {
        val target = ceiling(tier) ?: return affix
        return affix.copy(
            tiers = affix.tiers.map { t ->
                if (affixTier(t).ratio <= limit(tier)) return@map t
                val helpful = t.modifiers.sumOf { range(it, t.minItemLevel).coerceAtLeast(0f).toDouble() }.toFloat()
                val rest = affixTier(t).score - helpful
                val factor = factor(helpful, rest, 1f, target) ?: return@map t
                t.copy(modifiers = t.modifiers.map { scaledIfHelpful(it, factor, t.minItemLevel) })
            },
        )
    }

    /** [base] with its hit or defences, and its implicits, each brought within [tier]. */
    fun fit(base: ItemBase, tier: PowerTier): ItemBase {
        val target = ceiling(tier) ?: return base
        val limit = limit(tier)
        var fitted = base
        val main = mainRatio(base)
        if (main > limit) {
            val factor = target / main
            fitted = fitted.copy(
                weapon = base.weapon?.let {
                    val min = (it.minDamage * factor).roundToInt().coerceAtLeast(1)
                    it.copy(minDamage = min, maxDamage = (it.maxDamage * factor).roundToInt().coerceAtLeast(min))
                },
                defences = if (base.weapon == null) base.defences.map { scaledIfHelpful(it, factor, base.minItemLevel) } else base.defences,
            )
        }
        val implicit = ranges(base.implicits, base.minItemLevel) / IMPLICIT_ALLOWANCE
        if (implicit > limit) {
            val helpful = base.implicits.sumOf { range(it, base.minItemLevel).coerceAtLeast(0f).toDouble() }.toFloat()
            val rest = ranges(base.implicits, base.minItemLevel) - helpful
            factor(helpful, rest, IMPLICIT_ALLOWANCE, target)?.let { factor ->
                fitted = fitted.copy(implicits = base.implicits.map { scaledIfHelpful(it, factor, base.minItemLevel) })
            }
        }
        return fitted
    }

    /** [set]'s bonuses brought within [tier]; flags go, strongest first, when scaling alone cannot. */
    fun fit(set: ItemSetDefinition, tier: PowerTier, itemLevel: Int): ItemSetDefinition {
        val target = ceiling(tier) ?: return set
        if (set(set, itemLevel).ratio <= limit(tier)) return set
        var candidate = set
        while (true) {
            val estimate = set(candidate, itemLevel)
            val helpful = candidate.bonuses.flatMap { it.modifiers }.sumOf { modifier(it, itemLevel).coerceAtLeast(0f).toDouble() }.toFloat()
            val factor = factor(helpful, estimate.score - helpful, estimate.allowance, target)
            if (factor != null) {
                return candidate.copy(
                    bonuses = candidate.bonuses.map { b -> b.copy(modifiers = b.modifiers.map { scaledIfHelpful(it, factor, itemLevel) }) },
                )
            }
            val holder = candidate.bonuses.filter { it.flags.isNotEmpty() }.maxByOrNull { b -> b.flags.maxOf { flag(it) } } ?: return candidate
            val strongest = holder.flags.maxBy { flag(it) }
            candidate = candidate.copy(bonuses = candidate.bonuses.map { if (it === holder) it.copy(flags = it.flags - strongest) else it })
        }
    }

    private fun scaledIfHelpful(r: ModifierRange, factor: Float, itemLevel: Int): ModifierRange {
        if (range(r, itemLevel) <= 0f) return r
        val scaled = r.scaled(factor)
        // A whole-number range can round to nothing; one point is still the thing that was asked for.
        return if (scaled.max == 0f && r.max != 0f) scaled.copy(min = minOf(scaled.min, 1f), max = 1f) else scaled
    }

    private fun scaledIfHelpful(m: StatModifier, factor: Float, itemLevel: Int): StatModifier {
        if (modifier(m, itemLevel) <= 0f) return m
        val value = m.value * factor
        val whole = m.kind == ModifierKind.FLAT && !m.stat.isPercent && m.value == m.value.roundToInt().toFloat()
        return m.copy(value = if (whole) value.roundToInt().toFloat().let { if (it == 0f) sign(m.value) else it } else value)
    }
}
