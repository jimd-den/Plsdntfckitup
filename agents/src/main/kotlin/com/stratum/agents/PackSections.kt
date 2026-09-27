package com.stratum.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A content pack as plugin JSON, cut into the sections agents write.
 *
 * The studio works on JSON rather than domain objects so that what an agent
 * writes is exactly what a person would write in a plugin, and a new section
 * added to the plugin format is writable by agents the day it lands.
 */
object PackSections {

    /** Every top-level section of plugin JSON an agent may write. */
    val known: Set<String> = linkedSetOf(
        "palette", "blocks", "biomes", "terrain", "classes", "lore", "damageTypes", "affixes", "inserts",
        "weapons", "enemies", "skills", "rarities", "checks", "passiveTrees", "currencies", "supports",
        "waystoneMods", "factions", "enemyPacks", "settlements", "rules", "needs", "consumables", "forage",
        "recipes", "resources", "structures", "units", "agents",
    )

    internal val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("expected a JSON object")

    fun render(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    /** The start of a draft: an id and a name, and nothing yet in it. */
    fun empty(packId: String, name: String, author: String): JsonObject = JsonObject(
        mapOf("id" to JsonPrimitive(packId), "name" to JsonPrimitive(name), "author" to JsonPrimitive(author)),
    )

    /** Only [sections] of [pack]. */
    fun slice(pack: JsonObject, sections: Collection<String>): JsonObject = JsonObject(pack.filterKeys { it in sections })

    /**
     * Lays [fragment] over [draft], taking only [sections] from it. Lists
     * merge by id -- a fragment can revise an entry without restating the
     * rest -- and single objects such as `rules` are replaced whole.
     */
    fun merge(draft: JsonObject, fragment: JsonObject, sections: Collection<String>): JsonObject {
        val merged = LinkedHashMap(draft)
        fragment.filterKeys { it in sections }.forEach { (key, value) ->
            merged[key] = when {
                value is JsonArray && draft[key] is JsonArray -> mergeLists(draft[key] as JsonArray, value)
                else -> value
            }
        }
        return JsonObject(merged)
    }

    /** Ids in a list section, in order. Entries without one (forage rules) are skipped. */
    fun ids(pack: JsonObject, section: String): List<String> =
        (pack[section] as? JsonArray).orEmpty().mapNotNull { idOf(it) }

    /** Sections present in [fragment] that [allowed] does not include: work the agent was not asked for. */
    fun trespass(fragment: JsonObject, allowed: Collection<String>): List<String> =
        fragment.keys.filter { it !in allowed && it !in IDENTITY }

    /** The first entry of [section] in [reference], as a model's pattern to copy. Null when the reference has none. */
    fun example(reference: JsonObject, section: String): String? = when (val value = reference[section]) {
        is JsonArray -> value.firstOrNull()?.let(::render)
        null -> null
        else -> render(value)
    }

    /** The ids each list section of [fragment] adds or revises, for the journal. */
    fun added(fragment: JsonObject, sections: Collection<String>): Map<String, List<String>> =
        sections.associateWith { ids(fragment, it) }.filterValues { it.isNotEmpty() } +
            sections.filter { fragment[it] is JsonObject }.associateWith { listOf("(replaced)") }

    private fun mergeLists(old: JsonArray, new: JsonArray): JsonArray {
        val byId = LinkedHashMap<String, JsonElement>()
        val loose = mutableListOf<JsonElement>()
        (old + new).forEach { entry -> idOf(entry)?.let { byId[it] = entry } ?: loose.add(entry) }
        return JsonArray(byId.values.toList() + loose)
    }

    private fun idOf(entry: JsonElement): String? = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull

    /** Keys every fragment may carry without trespassing. */
    private val IDENTITY = setOf("id", "name", "author", "version", "description")

    /** The id field of a pack JSON object. */
    fun packId(pack: JsonObject): String? = pack["id"]?.jsonPrimitive?.contentOrNull
}
