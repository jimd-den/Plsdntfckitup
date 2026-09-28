package com.stratum.agents.forge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Reads a model's JSON the way a patient person would: a number written as
 * "20%" or "10-25" is still a number, a list written as "a, b" is still a
 * list, and a field under a near name ("title" for "name") is still that
 * field. Strict reading belongs to the plugin schema; this is what stands
 * between a model and it.
 */
internal object Lenient {

    /** A number as written, and whether it was written as a percentage. */
    data class Number(val value: Float, val percent: Boolean)

    /** The spelling words are compared in: "Fire Resistance" and "fire-resistance" are "fire_resistance". */
    fun key(text: String): String = text.trim().lowercase().replace(SEPARATORS, "_").trim('_')

    /** Words safe for an id: "Storm's Bite" is "storms_bite". */
    fun slug(text: String): String = key(text).replace(NON_ID, "").replace(REPEATS, "_").trim('_').take(MAX_SLUG)

    fun string(obj: JsonObject, vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }

    fun bool(obj: JsonObject, vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { name ->
            val value = obj[name] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
            value.booleanOrNull ?: when (value.contentOrNull?.trim()?.lowercase()) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> null
            }
        }

    fun number(obj: JsonObject, vararg names: String): Number? = names.firstNotNullOfOrNull { name -> numberOf(obj[name]) }

    /** Both ends of a range when one field holds it: "10-25", "10 to 25", [10, 25]. */
    fun span(obj: JsonObject, vararg names: String): Pair<Number, Number>? = names.firstNotNullOfOrNull { name ->
        when (val element = obj[name]) {
            is JsonArray -> {
                val numbers = element.mapNotNull(::numberOf)
                if (numbers.size >= 2) numbers.first() to numbers.last() else null
            }
            is JsonPrimitive -> element.contentOrNull?.let(::spanOf)
            else -> null
        }
    }

    fun numberOf(element: JsonElement?): Number? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) return primitive.floatOrNull?.takeIf { it.isFinite() }?.let { Number(it, false) }
        val text = primitive.content.trim()
        val match = NUMBER.find(text) ?: return null
        val value = match.value.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return Number(value, '%' in text)
    }

    private fun spanOf(text: String): Pair<Number, Number>? {
        val numbers = NUMBER.findAll(text.replace(DASH_RANGE, " to ")).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (numbers.size < 2) return null
        val percent = '%' in text
        return Number(numbers.first(), percent) to Number(numbers.last(), percent)
    }

    fun objects(obj: JsonObject, vararg names: String): List<JsonObject> = names.firstNotNullOfOrNull { name ->
        when (val element = obj[name]) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> null
        }
    }.orEmpty()

    /** A list of words, written as a list or as one comma-separated string. */
    fun strings(obj: JsonObject, vararg names: String): List<String> = names.firstNotNullOfOrNull { name ->
        when (val element = obj[name]) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            is JsonPrimitive -> element.contentOrNull?.split(',', ';')?.map { it.trim() }
            else -> null
        }
    }.orEmpty().filter { it.isNotEmpty() }

    /**
     * The candidate [raw] means, or null. Tried in order of confidence: the
     * exact id, the id in [namespace], then the part after the colon, then
     * the name -- so "bronze_ring", "Bronze Ring" and "other:bronze_ring"
     * all find `igbo:bronze_ring`.
     */
    fun match(raw: String, candidates: Collection<String>, namespace: String, names: (String) -> String? = { null }): String? {
        val wanted = raw.trim()
        if (wanted in candidates) return wanted
        val local = slug(wanted.substringAfterLast(':'))
        if ("$namespace:$local" in candidates) return "$namespace:$local"
        candidates.firstOrNull { slug(it.substringAfterLast(':')) == local }?.let { return it }
        return candidates.firstOrNull { id -> names(id)?.let(::slug) == local }
    }

    private val SEPARATORS = Regex("[\\s\\-]+")
    private val NON_ID = Regex("[^a-z0-9_]")
    private val REPEATS = Regex("_+")
    private val NUMBER = Regex("[-+]?\\d+(?:\\.\\d+)?")
    private val DASH_RANGE = Regex("(?<=\\d)\\s*[-–]\\s*(?=\\d)")
    private const val MAX_SLUG = 40
}

/** What a repair did and what it gave up on, in the order it happened. */
internal class RepairLog {
    val notes = mutableListOf<String>()
    val rejections = mutableListOf<String>()

    fun note(text: String) {
        notes += text
    }

    fun reject(text: String) {
        rejections += text
    }
}
