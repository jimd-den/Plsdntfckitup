package com.stratum.agents.forge

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemCategory
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat

/**
 * Every word a forged thing may use, read from the content it will load
 * with: the engine's stats, kinds, slots and flags, and the loaded packs'
 * damage types, tags, bases and lore.
 *
 * The prompt lists these in full and the repair holds the reply to them,
 * so a model can only name what exists -- and what it nearly named is
 * matched back to what it meant.
 */
class ForgeVocabulary(private val content: AssembledContent) {

    val stats: List<String> = Stat.entries.map { it.name.lowercase() }
    val modifierKinds: List<String> = ModifierKind.entries.map { it.name.lowercase() }
    val slots: List<String> = ItemSlot.entries.map { it.name.lowercase() }
    val flags: List<String> = BuildFlag.entries.map { it.name.lowercase() }
    val affixKinds: List<String> = listOf(AffixKind.PREFIX, AffixKind.SUFFIX).map { it.name.lowercase() }
    val loreCategories: List<String> = LoreCategory.entries.map { it.name.lowercase() }

    val damageTypes: List<String> = content.damageTypes.map { it.id }

    /** Bases the packs wrote, not the rungs grown from them: a unique names the root. */
    val bases: List<ItemBase> = content.itemCatalogue.bases.filter { it.family == null }

    /** Every tag an affix can ask for: the packs' own and the ones every base carries. */
    val tags: List<String> = (
        bases.flatMap { it.tags } + ItemSlot.entries.map { it.name.lowercase() } +
            ItemCategory.entries.map { it.name.lowercase() } + listOf("one_handed", "two_handed")
        ).distinct()

    val lore: List<LoreEntry> = content.lore

    /**
     * What lore may be about, by kind: regions, peoples, monsters, gear and
     * heroes the packs define.
     */
    val subjects: Map<String, List<String>> = linkedMapOf(
        "regions" to content.biomes.map { it.id },
        "factions" to content.factions.map { it.id },
        "monsters" to content.enemies.map { it.id },
        "towns" to content.settlements.map { it.id },
        "classes" to content.heroClasses.map { it.id },
        "bases" to bases.map { it.id },
        "uniques" to content.uniques.map { it.id },
        "sets" to content.itemSets.map { it.id },
    ).filterValues { it.isNotEmpty() }

    val subjectIds: Set<String> = subjects.values.flatten().toSet()

    val uniqueIds: Set<String> = content.uniques.mapTo(HashSet()) { it.id }

    fun basesFor(slot: ItemSlot?): List<ItemBase> = if (slot == null) bases else bases.filter { it.slot == slot }

    /** A human name for an id the packs define, for prompts and cards. */
    fun nameOf(id: String): String? =
        content.biomes.firstOrNull { it.id == id }?.name
            ?: content.factions.firstOrNull { it.id == id }?.name
            ?: content.enemies.firstOrNull { it.id == id }?.name
            ?: content.settlements.firstOrNull { it.id == id }?.name
            ?: content.heroClasses.firstOrNull { it.id == id }?.name
            ?: bases.firstOrNull { it.id == id }?.name
            ?: content.uniques.firstOrNull { it.id == id }?.name
            ?: content.itemSets.firstOrNull { it.id == id }?.name

    /** The engine's words, for any prompt about gear. */
    fun gearWords(): Map<String, List<String>> = linkedMapOf(
        "stat" to stats,
        "kind (of a modifier)" to modifierKinds,
        "slot" to slots,
        "damageType" to damageTypes,
        "tags" to tags,
    )

    /**
     * The lore a forged thing can draw on, one line each: its id, title and
     * first sentence. Enough to echo a name or a deity without the whole
     * codex in the prompt.
     */
    fun loreLines(limit: Int = MAX_LORE_LINES): List<String> = lore.take(limit).map { entry ->
        "${entry.id} \"${entry.title}\" (${entry.category.name.lowercase()}): ${firstSentence(entry.body)}"
    }

    private companion object {
        const val MAX_LORE_LINES = 12

        fun firstSentence(text: String): String = text.substringBefore(". ").take(140).trim()
    }
}
