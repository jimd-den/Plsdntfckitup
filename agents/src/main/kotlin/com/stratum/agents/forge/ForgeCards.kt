package com.stratum.agents.forge

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.ItemCatalogue
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.PowerBudget
import com.stratum.core.domain.item.PowerEstimate
import com.stratum.core.domain.stats.StatModifier
import kotlin.random.Random

/** The two things a person can rewrite on a card: what it is called, and what it says. */
enum class ForgeField(val label: String) { NAME("Name"), TEXT("Text") }

/**
 * One forged thing as a person reads it: a tooltip for gear, an entry for
 * lore. Built from the item model -- the same base line and modifier
 * wording the satchel shows -- so what the forge shows is what the game
 * will.
 */
data class ForgeCard(
    val section: String,
    val id: String,
    val glyph: String,
    val title: String,
    /** "Unique ring · on Bronze Ring", "Lore · deity · about Amadioha". */
    val subtitle: String,
    val lines: List<String>,
    /** Flavour for gear, the body for lore. */
    val text: String = "",
    val power: PowerEstimate? = null,
)

object ForgeCards {

    /**
     * Cards for everything in [fragment], read against [base] for the
     * names of what it refers to. Items are rolled from a fixed seed, so
     * the same fragment always shows the same card.
     */
    fun of(fragment: ContentPack, base: List<ContentPack>, itemLevel: Int = 1): List<ForgeCard> {
        val catalogue = catalogueFor(fragment, base)
        val names = (base + fragment).let { packs ->
            packs.flatMap { p -> p.itemBases.map { it.id to it.name } + p.weapons.map { it.id to it.name } + p.uniques.map { it.id to it.name } + p.itemSets.map { it.id to it.name } } +
                packs.flatMap { p -> p.biomes.map { it.id to it.name } + p.factions.map { it.id to it.name } + p.enemies.map { it.id to it.name } + p.heroClasses.map { it.id to it.name } }
        }.toMap()
        val generator = ItemGenerator(catalogue)
        val random = Random(SEED)

        val bases = fragment.itemBases.map { base ->
            val item = generator.craft(catalogue.base(base.id) ?: base, maxOf(itemLevel, base.minItemLevel), ItemRarity.COMMON, random)
            ForgeCard(
                "itemBases", base.id, base.glyph, base.name,
                "${base.slot.name.lowercase().replaceFirstChar { it.uppercase() }} base${if (base.twoHanded) " · two-handed" else ""}",
                listOf(item.baseLine) + base.implicits.map { "Implicit: ${describe(it)}" } +
                    listOfNotNull(base.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Tags: ")) +
                    "Requires level ${base.requiredLevel} · drops from item level ${base.minItemLevel}",
                base.description, PowerBudget.base(base),
            )
        }
        val affixes = fragment.affixes.map { affix ->
            ForgeCard(
                "affixes", affix.id, if (affix.kind == AffixKind.PREFIX) "◂" else "▸", affix.name,
                "${affix.kind.name.lowercase().replaceFirstChar { it.uppercase() }}${affix.slots.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") { it.name.lowercase() }.orEmpty()}${if (affix.local) " · local" else ""}",
                affix.tiers.mapIndexed { index, tier ->
                    "T${index + 1} ${tier.name ?: affix.name} (item level ${tier.minItemLevel}): ${tier.modifiers.joinToString(", ") { describe(it) }}"
                },
                power = PowerBudget.affix(affix),
            )
        }
        val uniques = fragment.uniques.map { unique ->
            val item = generator.unique(unique, maxOf(itemLevel, unique.minItemLevel), random)
            val baseName = names[unique.baseId] ?: unique.baseId.substringAfter(':')
            ForgeCard(
                "uniques", unique.id, unique.glyph ?: item?.glyph ?: "✦", unique.name,
                "${if (unique.setId != null) "Set piece" else "Unique"} · ${item?.slot?.name?.lowercase() ?: "gear"} · on $baseName",
                listOfNotNull(item?.baseLine) + (unique.modifiers + unique.localModifiers).map { describe(it) } +
                    unique.flags.map { "✶ ${it.label}" } + listOfNotNull(unique.setId?.let { "Part of ${names[it] ?: it.substringAfter(':')}" }),
                unique.flavour, PowerBudget.unique(unique),
            )
        }
        val sets = fragment.itemSets.map { set ->
            ForgeCard(
                "itemSets", set.id, "❖", set.name,
                "Set of ${fragment.uniques.count { it.setId == set.id }}",
                set.bonuses.map { "(${it.pieces}) ${it.description}" } + fragment.uniques.filter { it.setId == set.id }.map { "• ${it.name}" },
                set.description, PowerBudget.set(set, itemLevel),
            )
        }
        val lore = fragment.loreEntries.map { entry ->
            ForgeCard(
                "lore", entry.id, "📜", entry.title,
                "Lore · ${entry.category.name.lowercase()}${entry.subjectId?.let { " · about ${names[it] ?: it.substringAfter(':')}" }.orEmpty()}",
                emptyList(), entry.body,
            )
        }
        return uniques + sets + bases + affixes + lore
    }

    /**
     * How a range reads on a tooltip: "+(10–25) maximum health", "(20–30)%
     * increased damage". A fixed number reads exactly as the item shows it.
     */
    fun describe(range: ModifierRange): String {
        val high = StatModifier(range.stat, range.kind, range.max, range.damageTypeId).describe()
        if (range.min == range.max) return high
        val low = StatModifier(range.stat, range.kind, range.min, range.damageTypeId).describe()
        val lowNumber = NUMBER.find(low)?.value ?: return high
        val highNumber = NUMBER.find(high) ?: return high
        return high.replaceRange(highNumber.range, "($lowNumber–${highNumber.value})")
    }

    /**
     * [fragment] with one field of one thing rewritten: a name or title,
     * or its flavour, description or body. Nothing else can be edited here
     * -- numbers are the budget's, and a person who wants to change them
     * has a plugin file to do it in.
     */
    fun edit(fragment: ContentPack, id: String, field: ForgeField, value: String): ContentPack = when (field) {
        ForgeField.NAME -> fragment.copy(
            itemBases = fragment.itemBases.map { if (it.id == id) it.copy(name = value) else it },
            affixes = fragment.affixes.map { if (it.id == id) it.copy(name = value) else it },
            uniques = fragment.uniques.map { if (it.id == id) it.copy(name = value) else it },
            itemSets = fragment.itemSets.map { if (it.id == id) it.copy(name = value) else it },
            loreEntries = fragment.loreEntries.map { if (it.id == id) it.copy(title = value) else it },
        )
        ForgeField.TEXT -> fragment.copy(
            itemBases = fragment.itemBases.map { if (it.id == id) it.copy(description = value) else it },
            uniques = fragment.uniques.map { if (it.id == id) it.copy(flavour = value) else it },
            itemSets = fragment.itemSets.map { if (it.id == id) it.copy(description = value) else it },
            loreEntries = fragment.loreEntries.map { if (it.id == id) it.copy(body = value) else it },
        )
    }

    private fun catalogueFor(fragment: ContentPack, base: List<ContentPack>): ItemCatalogue =
        runCatching { ContentPackAssembler().assemble(base + fragment).itemCatalogue }.getOrElse {
            ItemCatalogue.of(
                base.flatMap { p -> p.itemBases + p.weapons.map { it.toItemBase() } } + fragment.itemBases,
                fragment.affixes, fragment.uniques + base.flatMap { it.uniques }, fragment.itemSets + base.flatMap { it.itemSets },
            )
        }

    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private const val SEED = 7L
}
