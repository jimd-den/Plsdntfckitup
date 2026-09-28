package com.stratum.agents.forge

import com.stratum.agents.FragmentRepair
import com.stratum.agents.PackSections
import com.stratum.agents.Repaired
import com.stratum.agents.StudioBrief
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.content.LoreEntry
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerBudget
import com.stratum.core.domain.item.PowerEstimate
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.plugins.schema.PackJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The forge's repair: reads a reply leniently into domain objects, ties
 * gear to its lore, brings the numbers within the budget asked for, and
 * writes the result back as plugin JSON for the ordinary checks.
 *
 * Going through the domain rather than patching JSON is the point: what
 * comes out is valid by construction -- ranges the right way round, tiers
 * in order, bonuses no bigger than their set -- and only references to the
 * loaded packs are left for [com.stratum.agents.DraftCheck] to confirm.
 */
class ForgeRepair(
    private val vocabulary: ForgeVocabulary,
    private val order: ForgeOrder,
    private val context: ForgeContext,
) : FragmentRepair {

    override fun repair(fragment: JsonObject, role: AgentRoleDefinition, brief: StudioBrief): Repaired {
        val log = RepairLog()
        val body = ForgeSections.normalise(fragment, role.sections, log)
        val numbers = ModifierReader(vocabulary, brief.packId, context.levels, log)
        val gear = GearReader(vocabulary, brief.packId, context.levels, log, numbers)

        val rawSets = Lenient.objects(body, "itemSets").map(gear::set)
        val bases = readBases(body, gear, log)
        val affixes = Lenient.objects(body, "affixes").mapNotNull { gear.affix(it, (order as? ForgeOrder.Affixes)?.slots.orEmpty()) }
            .take(((order as? ForgeOrder.Affixes)?.count ?: 1).coerceAtLeast(1) * 2)
        var uniques = Lenient.objects(body, "uniques").mapNotNull { gear.unique(it, (order as? ForgeOrder.Unique)?.slot, bases, rawSets.map { s -> s.id }) }
        val onlySet = rawSets.singleOrNull()
        if (onlySet != null) {
            uniques = uniques.map { if (it.setId == null) it.copy(setId = onlySet.id).also { u -> log.note("unique '${u.id}' made a piece of '${onlySet.id}'") } else it }
        }
        val sets = rawSets.mapNotNull { set ->
            val pieces = uniques.count { it.setId == set.id }
            when {
                pieces == 0 -> null.also { log.reject("set '${set.id}' has no pieces: write uniques whose \"set\" is \"${set.id}\"") }
                set.bonuses.isEmpty() -> null.also { log.reject("set '${set.id}' has no bonuses: give it \"bonuses\" with \"pieces\" and \"modifiers\"") }
                else -> gear.fitBonuses(set, pieces)
            }
        }
        uniques = uniques.map { if (it.setId != null && sets.none { s -> s.id == it.setId }) it.copy(setId = null) else it }

        val names = namesOf(bases, uniques, sets)
        val lore = readLore(body, numbers, log, bases.map { it.id } + uniques.map { it.id } + sets.map { it.id }, names)
        val (tiedLore, flavoured) = tie(lore, uniques, sets, log)

        val fitted = Budgeted(
            bases = bases.map { fit(it, log) },
            affixes = affixes.map { fit(it, log) },
            uniques = flavoured.map { fit(it, log) },
            sets = sets.map { fit(it, log) },
        )
        requireWhatWasAsked(role.sections, fitted, tiedLore, log)
        if (log.rejections.isNotEmpty()) {
            val context = log.notes.takeLast(MAX_NOTES_IN_REJECTION)
            return Repaired(fragment, log.notes, log.rejections + context.map { "(while reading: $it)" })
        }

        val pack = ContentPack(
            id = brief.packId, name = brief.packName, author = brief.author,
            itemBases = fitted.bases, affixes = fitted.affixes, uniques = fitted.uniques, itemSets = fitted.sets, loreEntries = tiedLore,
        )
        val written = PackSections.slice(PackSections.parse(PackJson.encode(pack)), role.sections)
        return Repaired(written, log.notes)
    }

    private class Budgeted(
        val bases: List<ItemBase>,
        val affixes: List<AffixDefinition>,
        val uniques: List<UniqueDefinition>,
        val sets: List<ItemSetDefinition>,
    )

    /** One base, or a ladder of them climbing the level range when that is what was asked. */
    private fun readBases(body: JsonObject, gear: GearReader, log: RepairLog): List<ItemBase> {
        val wanted = order as? ForgeOrder.Base
        val read = Lenient.objects(body, "itemBases").mapNotNull { gear.base(it, wanted?.slot) }
        if (wanted == null || !wanted.ladder) {
            if (read.size > 1 && wanted != null) log.note("kept the first of ${read.size} bases; ask for a ladder to keep a family")
            return if (wanted != null) read.take(1) else read
        }
        val rungs = read.take(MAX_RUNGS).sortedBy { it.minItemLevel }
        // A ladder whose rungs all drop at one depth is not a ladder; spread them over the range asked for.
        val spread = if (rungs.size > 1 && rungs.map { it.minItemLevel }.distinct().size < rungs.size) {
            log.note("spread the ladder's rungs over item levels ${context.levels.first}..${context.levels.last}")
            val step = (context.levels.last - context.levels.first) / (rungs.size - 1).coerceAtLeast(1)
            rungs.mapIndexed { index, base ->
                val level = context.levels.first + step * index
                base.copy(minItemLevel = level, requiredLevel = minOf(base.requiredLevel, level).coerceAtLeast(1))
            }
        } else {
            rungs
        }
        // The rungs are the family; growing numerals on top of each would be a ladder of ladders.
        return spread.map { it.copy(grows = false) }
    }

    private fun readLore(body: JsonObject, numbers: ModifierReader, log: RepairLog, defined: List<String>, names: (String) -> String?): List<LoreEntry> {
        val reader = LoreReader(vocabulary, numbers.namespace, numbers, log)
        val asked = order as? ForgeOrder.Lore
        val fallback = asked?.category ?: if (order is ForgeOrder.Unique || order is ForgeOrder.ItemSet) LoreCategory.ARTIFACT else LoreCategory.HISTORY
        val entries = Lenient.objects(body, "lore").mapNotNull { reader.entry(it, fallback, defined, names) }
        val limit = asked?.count ?: MAX_TIED_LORE
        if (entries.size > limit) log.note("kept ${limit} of ${entries.size} lore entries")
        return entries.take(limit).mapIndexed { index, entry ->
            var fixed = entry
            if (asked?.category != null && entry.category != asked.category) {
                log.note("lore '${entry.id}' filed under ${asked.category.name.lowercase()}, as asked")
                fixed = fixed.copy(category = asked.category)
            }
            if (asked != null && asked.subjects.isNotEmpty() && fixed.subjectId == null) {
                val subject = asked.subjects[index % asked.subjects.size]
                log.note("lore '${entry.id}' is about '$subject', as asked")
                fixed = fixed.copy(subjectId = subject)
            }
            fixed
        }
    }

    /**
     * Ties a unique or set to its story: one entry is about it, and a
     * unique with no flavour of its own quotes that entry's first line.
     * Names and flavour that echo the codex are what make a unique read as
     * part of a world rather than a stat block.
     */
    private fun tie(lore: List<LoreEntry>, uniques: List<UniqueDefinition>, sets: List<ItemSetDefinition>, log: RepairLog): Pair<List<LoreEntry>, List<UniqueDefinition>> {
        if (order !is ForgeOrder.Unique && order !is ForgeOrder.ItemSet) return lore to uniques
        val target = sets.firstOrNull()?.id ?: uniques.firstOrNull()?.id ?: return lore to uniques
        val own = (uniques.map { it.id } + sets.map { it.id }).toSet()
        val tied = if (lore.isNotEmpty() && lore.none { it.subjectId in own }) {
            log.note("lore '${lore.first().id}' is now about '$target'")
            listOf(lore.first().copy(subjectId = target, category = LoreCategory.ARTIFACT)) + lore.drop(1)
        } else {
            lore
        }
        val story = tied.firstOrNull { it.subjectId in own }
        val flavoured = uniques.map { unique ->
            if (unique.flavour.isNotBlank() || story == null) unique
            else unique.copy(flavour = firstLine(story.body)).also { log.note("unique '${unique.id}' quotes '${story.title}' as its flavour") }
        }
        return tied to flavoured
    }

    private fun requireWhatWasAsked(sections: List<String>, gear: Budgeted, lore: List<LoreEntry>, log: RepairLog) {
        when (order) {
            is ForgeOrder.Lore -> if (lore.isEmpty()) log.reject("no usable lore entry: each needs a \"title\" and a \"body\"")
            is ForgeOrder.Base -> if (gear.bases.isEmpty()) log.reject("no usable item base: it needs a \"name\"${if (order.slot == ItemSlot.WEAPON) ", \"damageType\", \"minDamage\" and \"maxDamage\"" else " and \"defences\""}")
            is ForgeOrder.Affixes -> if (gear.affixes.isEmpty()) log.reject("no usable affix: each needs a \"name\", a \"kind\" (prefix or suffix) and \"tiers\" of \"modifiers\" using the allowed stats")
            is ForgeOrder.Unique -> {
                if (gear.uniques.isEmpty()) log.reject("no usable unique: it needs a \"name\", a \"base\" from the list of bases and \"modifiers\"")
                if ("lore" in sections && lore.isEmpty()) log.reject("write one lore entry (category artifact) whose \"subject\" is the unique's id, telling its story")
            }
            is ForgeOrder.ItemSet -> {
                if (gear.sets.isEmpty() && log.rejections.isEmpty()) log.reject("no usable item set: write one entry in \"itemSets\" with \"bonuses\"")
                gear.sets.forEach { set ->
                    if (gear.uniques.count { it.setId == set.id } < MIN_SET_PIECES) log.reject("set '${set.id}' needs at least $MIN_SET_PIECES pieces in \"uniques\", each with \"set\": \"${set.id}\"")
                }
                if ("lore" in sections && lore.isEmpty()) log.reject("write one lore entry (category artifact) whose \"subject\" is the set's id, telling its story")
            }
        }
    }

    private fun fit(base: ItemBase, log: RepairLog): ItemBase = fitted(base, log, PowerBudget::base) { PowerBudget.fit(it, context.budget) }

    private fun fit(affix: AffixDefinition, log: RepairLog): AffixDefinition = fitted(affix, log, PowerBudget::affix) { PowerBudget.fit(it, context.budget) }

    private fun fit(unique: UniqueDefinition, log: RepairLog): UniqueDefinition = fitted(unique, log, PowerBudget::unique) { PowerBudget.fit(it, context.budget) }

    private fun fit(set: ItemSetDefinition, log: RepairLog): ItemSetDefinition =
        fitted(set, log, { PowerBudget.set(it, context.levels.first) }) { PowerBudget.fit(it, context.budget, context.levels.first) }

    private inline fun <T> fitted(thing: T, log: RepairLog, estimate: (T) -> PowerEstimate, fit: (T) -> T): T {
        val after = fit(thing)
        if (after != thing) log.note("${nameOf(thing)} read ${estimate(thing).label}; brought to ${estimate(after).label} for a ${context.budget.label.lowercase()} request")
        return after
    }

    private fun nameOf(thing: Any?): String = when (thing) {
        is ItemBase -> "'${thing.name}'"
        is AffixDefinition -> "'${thing.name}'"
        is UniqueDefinition -> "'${thing.name}'"
        is ItemSetDefinition -> "'${thing.name}'"
        else -> "it"
    }

    private fun namesOf(bases: List<ItemBase>, uniques: List<UniqueDefinition>, sets: List<ItemSetDefinition>): (String) -> String? {
        val own = bases.associate { it.id to it.name } + uniques.associate { it.id to it.name } + sets.associate { it.id to it.name }
        return { id -> own[id] ?: vocabulary.nameOf(id) }
    }

    private fun firstLine(text: String): String = text.split(SENTENCE_END).firstOrNull()?.trim()?.take(MAX_FLAVOUR)?.let { if (it.endsWith('.')) it else "$it." }.orEmpty()

    private companion object {
        const val MAX_RUNGS = 5
        const val MAX_TIED_LORE = 2
        const val MIN_SET_PIECES = 2
        const val MAX_NOTES_IN_REJECTION = 4
        const val MAX_FLAVOUR = 140
        val SENTENCE_END = Regex("(?<=[.!?])\\s+")
    }
}

/** Finding the asked-for sections in a reply however the model wrapped them. */
internal object ForgeSections {

    /** Near names for each section, as models write them. */
    private val aliases: Map<String, List<String>> = mapOf(
        "itemBases" to listOf("bases", "base", "items", "item_bases", "item_base", "weapons", "weapon", "armour", "armor", "gear"),
        "affixes" to listOf("affix", "mods", "modifiers", "prefixes", "suffixes"),
        "uniques" to listOf("unique", "unique_items", "items", "item"),
        "itemSets" to listOf("sets", "set", "item_sets", "item_set"),
        "lore" to listOf("lore_entries", "codex", "entries", "entry", "story", "stories", "history"),
    )

    private val identity = setOf("id", "name", "author", "version", "description")

    /**
     * [fragment] with its sections under their plugin names. A reply nested
     * one level down, a section under a near name, and a bare entry with no
     * section at all are all read as what they plainly were.
     */
    fun normalise(fragment: JsonObject, wanted: List<String>, log: RepairLog): JsonObject {
        val hasSection = fragment.keys.any { canonical(it, wanted) != null }
        val nested = if (hasSection) null else fragment.values.filterIsInstance<JsonObject>().firstOrNull { inner -> inner.keys.any { canonical(it, wanted) != null } }
        val source = nested ?: fragment
        val out = LinkedHashMap<String, JsonElement>()
        source.forEach { (key, value) ->
            val section = canonical(key, wanted)
            when {
                section == null -> if (key !in identity) log.note("ignored '$key', which was not asked for")
                else -> {
                    if (section != key) log.note("read '$key' as '$section'")
                    val list = when (value) {
                        is JsonArray -> value
                        is JsonObject -> JsonArray(listOf(value))
                        else -> JsonArray(emptyList())
                    }
                    out[section] = (out[section] as? JsonArray)?.let { JsonArray(it + list) } ?: list
                }
            }
        }
        if (out.isEmpty() && (fragment.containsKey("name") || fragment.containsKey("title"))) {
            log.note("read the reply as one entry of '${wanted.first()}'")
            out[wanted.first()] = JsonArray(listOf(fragment))
        }
        return JsonObject(out)
    }

    private fun canonical(key: String, wanted: List<String>): String? {
        val k = Lenient.key(key)
        wanted.firstOrNull { Lenient.key(it) == k }?.let { return it }
        return wanted.firstOrNull { section -> aliases[section].orEmpty().any { it == k } }
    }
}
