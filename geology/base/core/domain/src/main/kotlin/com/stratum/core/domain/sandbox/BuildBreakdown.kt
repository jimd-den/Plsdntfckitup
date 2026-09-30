package com.stratum.core.domain.sandbox

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a number in a build came from, as the breakdown names it. */
enum class SourceKind(val label: String) {
    CLASS("Class"),
    LEVEL("Level"),
    WEAPON("Weapon"),
    PASSIVE("Passive"),
    GEAR("Gear"),
    SET_BONUS("Set bonus"),
    SUPPORT("Support"),
    TRAIT("Trait"),
    KEYSTONE("Keystone"),
    STATUS("Status"),
    CONDITIONAL("Conditional"),
    SURVIVAL("Survival"),
    TABLE("Boon"),
    SKILL("Skill"),

    /** The number the layer before this one produced, carried in as this layer's base. */
    CARRIED("Carried"),
}

/** One modifier, and who is responsible for it: "Plated Helm", "Glass Oath", "Full life". */
data class Contribution(val kind: SourceKind, val label: String, val modifier: StatModifier)

/** One part of a base: the class's 12, the level's 8, the weapon's 30. */
data class BaseContribution(val kind: SourceKind, val label: String, val value: Float)

/**
 * The limits a stat is held to after the formula, in the order the engine
 * applies them: floored at zero, rounded to a whole number, then clamped.
 */
data class StatBounds(
    val floorAtZero: Boolean = true,
    val rounded: Boolean = false,
    val atLeast: Float? = null,
    val atMost: Float? = null,
) {
    companion object {
        val PLAIN = StatBounds()
        val WHOLE = StatBounds(rounded = true)

        /** Resistances go negative: that is a vulnerability, and it is not floored. */
        val SIGNED = StatBounds(floorAtZero = false)
    }
}

/**
 * One pass of `(base + flat) × (1 + Σ increased) × Π (1 + more)` over one
 * set of modifiers. Most stats are one layer; a stat the engine resolves in
 * stages -- the build's sheet, then survival's, then a tabletop boon -- is one
 * layer per stage, each carrying the last one's result in as its base.
 */
data class BreakdownLayer(
    val title: String,
    val base: List<BaseContribution>,
    val flat: List<Contribution>,
    val increased: List<Contribution>,
    val more: List<Contribution>,
    /** What the formula gave before [bounds]. */
    val raw: Float,
    val result: Float,
    val bounds: StatBounds = StatBounds.PLAIN,
) {
    val baseTotal: Float get() = base.sumOf { it.value.toDouble() }.toFloat()
    val flatTotal: Float get() = flat.sumOf { it.modifier.value.toDouble() }.toFloat()
    val increasedTotal: Float get() = increased.sumOf { it.modifier.value.toDouble() }.toFloat()
    val moreProduct: Float get() = more.fold(1f) { product, it -> product * (1f + it.modifier.value) }

    /** Every modifier the layer counted, flat first. */
    val contributions: List<Contribution> get() = flat + increased + more

    /** The sum with its numbers in: "(40 + 12) × (1 + 0.35) × 1.2 = 84.24". */
    val formula: String
        get() = "(${number(baseTotal)} + ${number(flatTotal)}) × (1 + ${number(increasedTotal)}) × ${number(moreProduct)} = ${number(raw)}"
}

/** Everything that made one number, and the number. */
data class StatBreakdown(
    val title: String,
    val layers: List<BreakdownLayer>,
    /** What combat reads, after every layer and bound. */
    val value: Float,
    /** [value] as a tooltip would print it. */
    val display: String,
    /** What the formula does not show: caps, keystones, modifiers applied at the moment of the hit. */
    val notes: List<String> = emptyList(),
) {
    val contributions: List<Contribution> get() = layers.flatMap { it.contributions }
}

/**
 * Explains a number: every modifier that touched it, by source, and the one
 * formula that combined them.
 *
 * It computes the number itself, the same way [com.stratum.core.domain.stats.StatSheet]
 * does -- the same filter by damage type, the same sums in the same order,
 * the same floor, rounding and clamp -- rather than annotating a number
 * computed elsewhere. So when the engine hands it the sources combat uses, the
 * explanation and the fight cannot disagree, and a test holds them to it.
 */
object BuildBreakdown {

    /**
     * One layer: [base] with every contribution to [stat] applied. [damageTypeId]
     * scopes it the way the sheet does -- a typed query counts modifiers for
     * that type and unscoped ones; an untyped query counts only unscoped ones.
     */
    fun layer(
        title: String,
        stat: Stat,
        base: List<BaseContribution>,
        sources: List<Contribution>,
        damageTypeId: String? = null,
        bounds: StatBounds = StatBounds.PLAIN,
    ): BreakdownLayer {
        val matching = sources.filter { it.modifier.stat == stat && (it.modifier.damageTypeId == null || it.modifier.damageTypeId == damageTypeId) }
        val flat = matching.filter { it.modifier.kind == ModifierKind.FLAT }
        val increased = matching.filter { it.modifier.kind == ModifierKind.INCREASED }
        val more = matching.filter { it.modifier.kind == ModifierKind.MORE }
        val baseTotal = base.sumOf { it.value.toDouble() }.toFloat()
        val raw = (baseTotal + flat.sumOf { it.modifier.value.toDouble() }.toFloat()) *
            (1f + increased.sumOf { it.modifier.value.toDouble() }.toFloat()) *
            more.fold(1f) { product, it -> product * (1f + it.modifier.value) }
        return BreakdownLayer(title, base, flat, increased, more, raw, bound(raw, bounds), bounds)
    }

    /** A layer whose base is the layer before's result. */
    fun carried(previous: BreakdownLayer, title: String, stat: Stat, sources: List<Contribution>, damageTypeId: String? = null, bounds: StatBounds = previous.bounds): BreakdownLayer =
        layer(title, stat, listOf(BaseContribution(SourceKind.CARRIED, previous.title, previous.result)), sources, damageTypeId, bounds)

    fun bound(raw: Float, bounds: StatBounds): Float {
        var value = if (bounds.floorAtZero) raw.coerceAtLeast(0f) else raw
        if (bounds.rounded) value = value.roundToInt().toFloat()
        bounds.atLeast?.let { value = value.coerceAtLeast(it) }
        bounds.atMost?.let { value = value.coerceAtMost(it) }
        return value
    }
}

/** A number as a breakdown prints it: whole when it is whole, to two places when it is not. */
internal fun number(value: Float): String {
    val hundredths = (abs(value) * 100f).roundToInt()
    val sign = if (value < 0f && hundredths != 0) "-" else ""
    return when {
        hundredths % 100 == 0 -> "$sign${hundredths / 100}"
        hundredths % 10 == 0 -> "$sign${hundredths / 100}.${(hundredths % 100) / 10}"
        else -> "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
