package com.stratum.core.domain.item

import com.stratum.core.domain.stats.ModifierKind
import kotlin.math.roundToInt

/**
 * One rung of a ladder of stronger bases grown from a hand-authored one.
 *
 * A pack that writes five bases should not play the same at item level 50
 * as at 1. Rather than make every author write "Bronze Blade", "Tempered
 * Bronze Blade" and "Ozo Bronze Blade" by hand, a pack names the rungs once
 * and every base that [ItemBase.grows] gets them.
 */
data class BaseTier(
    /** The grown base's name; `{base}` stands for the root's: "Tempered {base}", "{base} II". */
    val name: String,
    /** How many item levels deeper than its root this rung first drops. */
    val levelsAbove: Int,
    /** How much stronger than its root would be at that depth, so climbing a rung is always an upgrade. */
    val bonus: Float = 1.1f,
) {
    init {
        require(levelsAbove > 0) { "Base tier '$name' must drop deeper than its root" }
        require("{base}" in name) { "Base tier '$name' must say where the base's name goes, as {base}" }
    }
}

/**
 * How an item's base numbers grow with the depth it dropped at, and the
 * ladders of bases grown from a pack's own.
 */
object BaseFamilies {

    /** Each item level adds this share of the base's numbers, so a deeper blade of the same kind is still an upgrade. */
    const val GROWTH_PER_ITEM_LEVEL = 0.08f

    /** Roman numerals, so the fallback names say nothing a pack did not. */
    val standard: List<BaseTier> = listOf(
        BaseTier("{base} II", levelsAbove = 12),
        BaseTier("{base} III", levelsAbove = 24),
        BaseTier("{base} IV", levelsAbove = 36),
    )

    /** How much a base's numbers have grown by [itemLevel]. */
    fun scale(base: ItemBase, itemLevel: Int): Float =
        1f + (itemLevel - scalesFrom(base)).coerceAtLeast(0) * GROWTH_PER_ITEM_LEVEL

    /**
     * The level a base's numbers are written for. A pack's own base is
     * written for level 1, as it always was; a grown one already carries its
     * growth up to the depth it first drops at.
     */
    private fun scalesFrom(base: ItemBase): Int = if (base.family == null) 1 else base.minItemLevel

    /** [bases] and every rung grown from those that grow. Deterministic: the same pack always grows the same ladder. */
    fun grow(bases: List<ItemBase>, tiers: List<BaseTier>): List<ItemBase> {
        val authored = bases.associateBy { it.id }
        val grown = bases.filter { it.grows && it.family == null }.flatMap { root ->
            tiers.sortedBy { it.levelsAbove }.mapIndexedNotNull { index, tier ->
                grown(root, tier, index + 2).takeIf { it.id !in authored }
            }
        }
        return bases + grown
    }

    private fun grown(root: ItemBase, tier: BaseTier, rung: Int): ItemBase {
        val depth = root.minItemLevel + tier.levelsAbove
        val factor = scale(root, depth) * tier.bonus
        return root.copy(
            id = "${root.id}~$rung",
            name = tier.name.replace("{base}", root.name),
            defences = root.defences.map { it.copy(value = grownValue(it.value, factor, it.stat.isPercent)) },
            implicits = root.implicits.map { if (it.kind == ModifierKind.FLAT && !it.stat.isPercent) it.scaled(factor) else it },
            weapon = root.weapon?.let {
                it.copy(minDamage = (it.minDamage * factor).roundToInt(), maxDamage = (it.maxDamage * factor).roundToInt())
            },
            requiredLevel = root.requiredLevel + tier.levelsAbove * 3 / 4,
            minItemLevel = depth,
            family = root.id,
        )
    }

    /**
     * Whole stats grow and stay whole. A share such as a resistance does not
     * grow at all: a charm's 10% fire resistance compounding to 40% by the
     * deep levels would make the stat's caps the only thing that mattered.
     */
    fun grownValue(value: Float, factor: Float, isPercent: Boolean): Float =
        if (isPercent) value else (value * factor).roundToInt().toFloat()
}
