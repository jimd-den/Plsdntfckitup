package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelFormat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reading provider replies as trees.
 *
 * A tree rather than a class per reply: four providers, each with a status
 * reply that grows fields every release, and only three or four fields of each
 * that matter here. A missing field is a null to handle, never a crash.
 */
internal object ModelJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonObject = try {
        json.parseToJsonElement(body).jsonObject
    } catch (failure: SerializationException) {
        throw ModelProtocolException("The provider replied with something that is not JSON: ${body.take(200)}")
    } catch (failure: IllegalArgumentException) {
        throw ModelProtocolException("The provider replied with JSON that is not an object: ${body.take(200)}")
    }

    fun encode(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    /** Follows a path of keys, e.g. `path(root, "data", "output", "model")`. */
    fun path(root: JsonElement?, vararg keys: String): JsonElement? =
        keys.fold(root) { node, key -> (node as? JsonObject)?.get(key) }

    fun string(root: JsonElement?, vararg keys: String): String? =
        (path(root, *keys) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    fun number(root: JsonElement?, vararg keys: String): Float? = (path(root, *keys) as? JsonPrimitive)?.floatOrNull

    /**
     * The first URL anywhere in [root] that looks like a model, preferring GLB.
     *
     * For replies whose output shape depends on the model behind them — a
     * queue or prediction service returns whatever that model returns, and
     * every model names its mesh differently.
     */
    fun findModelUrl(root: JsonElement?): Pair<String, ModelFormat>? {
        val urls = ArrayList<String>()
        fun walk(node: JsonElement?) {
            when (node) {
                is JsonPrimitive -> if (node.isString && node.content.startsWith("http")) urls += node.content
                is JsonArray -> node.forEach(::walk)
                is JsonObject -> node.values.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        fun extension(url: String) = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        urls.firstOrNull { extension(it) == "glb" }?.let { return it to ModelFormat.GLB }
        urls.firstOrNull { extension(it) == "obj" }?.let { return it to ModelFormat.OBJ }
        return null
    }
}
