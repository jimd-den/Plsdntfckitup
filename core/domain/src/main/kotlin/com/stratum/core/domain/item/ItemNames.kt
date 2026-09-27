package com.stratum.core.domain.item

import kotlin.random.Random

/**
 * Words a pack names its rare items from: one from [first], one from
 * [second], "Storm" and "Bite". A pool can be kept to some kinds of item, so
 * rings get softer names than axes.
 */
data class ItemNamePool(
    val id: String,
    val first: List<String>,
    val second: List<String>,
    /** The kinds of item it names. Empty names every kind. */
    val slots: Set<ItemSlot> = emptySet(),
    /** Names only items carrying one of these tags. Empty names any. */
    val tags: Set<String> = emptySet(),
) {
    fun fits(slot: ItemSlot, itemTags: Set<String>): Boolean =
        (slots.isEmpty() || slot in slots) && (tags.isEmpty() || tags.any { it in itemTags })
}

/** How items are named. */
object ItemNamer {

    /**
     * "Roped Bronze Blade of Storms": the first prefix, the base, the first
     * suffix. What every rolled item is called unless it is rare enough to
     * earn a name of its own, and what that falls back to in a pack with no
     * words to name it from -- built from the pack's own affix names, so
     * the engine never puts words in a pack's mouth.
     */
    fun compose(baseName: String, affixes: List<AffixRoll>): String {
        val prefix = affixes.firstOrNull { it.kind == AffixKind.PREFIX }?.name
        val suffix = affixes.firstOrNull { it.kind == AffixKind.SUFFIX }?.name
        return buildString {
            if (prefix != null) append("$prefix ")
            append(baseName)
            if (suffix != null) append(" $suffix")
        }
    }

    /** A rare item's own name from the pools that fit it, or null when none do. */
    fun rareName(pools: List<ItemNamePool>, slot: ItemSlot, tags: Set<String>, random: Random): String? {
        val fitting = pools.filter { it.fits(slot, tags) }
        val first = fitting.flatMap { it.first }
        val second = fitting.flatMap { it.second }
        if (first.isEmpty() || second.isEmpty()) return null
        return "${first[random.nextInt(first.size)]} ${second[random.nextInt(second.size)]}"
    }
}
