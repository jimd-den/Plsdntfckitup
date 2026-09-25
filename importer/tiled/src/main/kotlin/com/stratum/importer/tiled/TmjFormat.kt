package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportException
import com.stratum.importer.common.ProjectPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads Tiled's JSON formats: `.tmj` maps and `.tsj` tilesets (and the older
 * `.json` spellings of both).
 *
 * Walks the JSON tree by hand rather than binding classes to it. Tiled has
 * renamed fields across versions -- `type` became `class`, properties went
 * from an object to an array -- and a tolerant reader is shorter than a
 * schema for every version.
 */
internal object TmjFormat {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun readMap(text: String, path: String, externalTileset: (String) -> TiledTileset): TiledMap {
        val root = parseObject(text, path)
        val width = root.requireInt("width", path)
        val height = root.requireInt("height", path)
        return TiledMap(
            path = path,
            orientation = TiledOrientation.parse(root.string("orientation")),
            width = width,
            height = height,
            tileWidth = root.requireInt("tilewidth", path),
            tileHeight = root.requireInt("tileheight", path),
            infinite = root.bool("infinite") ?: false,
            layers = root.array("layers").mapObjects { layer(it, width, height, path) },
            tilesets = root.array("tilesets").mapObjects { tilesetEntry(it, path, externalTileset) },
            properties = properties(root),
        )
    }

    fun readTileset(text: String, path: String): TiledTileset = tileset(parseObject(text, path), firstGid = 0, path)

    // ---- layers ----------------------------------------------------------

    private fun layer(obj: JsonObject, mapWidth: Int, mapHeight: Int, path: String): TiledLayer {
        val name = obj.string("name").orEmpty()
        val visible = obj.bool("visible") ?: true
        val props = properties(obj)
        return when (val type = obj.string("type")) {
            "tilelayer" -> tileLayer(obj, name, visible, props, mapWidth, mapHeight)
            "objectgroup" -> TiledObjectGroup(name, visible, props, obj.array("objects").mapObjects(::mapObject))
            "imagelayer" -> TiledImageLayer(name, visible, props)
            "group" -> TiledGroupLayer(name, visible, props, obj.array("layers").mapObjects { layer(it, mapWidth, mapHeight, path) })
            else -> throw ImportException("'$path' has a layer '$name' of unknown type '$type'")
        }
    }

    private fun tileLayer(obj: JsonObject, name: String, visible: Boolean, props: TiledProperties, mapWidth: Int, mapHeight: Int): TiledTileLayer {
        if (obj["chunks"] != null) throw ImportException("Layer '$name' is from an infinite map; set a fixed map size in Tiled and re-save")
        val width = obj.int("width") ?: mapWidth
        val height = obj.int("height") ?: mapHeight
        return TiledTileLayer(name, visible, props, width, height, tileData(obj, name, width * height))
    }

    private fun tileData(obj: JsonObject, name: String, expected: Int): IntArray = when (val data = obj["data"]) {
        is JsonArray -> LayerDataDecoder.fromNumbers(data.map { (it as? JsonPrimitive)?.longOrNull ?: 0L })
        is JsonPrimitive -> LayerDataDecoder.decode(obj.string("encoding"), obj.string("compression"), data.content, expected)
        else -> throw ImportException("Layer '$name' has no tile data")
    }.also { if (it.size != expected) throw ImportException("Layer '$name' holds ${it.size} tiles where $expected were expected") }

    private fun mapObject(obj: JsonObject) = TiledObject(
        id = obj.int("id") ?: 0,
        name = obj.string("name").orEmpty(),
        type = obj.string("class") ?: obj.string("type").orEmpty(),
        x = obj.float("x") ?: 0f,
        y = obj.float("y") ?: 0f,
        width = obj.float("width") ?: 0f,
        height = obj.float("height") ?: 0f,
        isPoint = obj.bool("point") ?: false,
        properties = properties(obj),
    )

    // ---- tilesets --------------------------------------------------------

    private fun tilesetEntry(obj: JsonObject, mapPath: String, external: (String) -> TiledTileset): TiledTileset {
        val firstGid = obj.requireInt("firstgid", mapPath)
        val source = obj.string("source") ?: return tileset(obj, firstGid, mapPath)
        return external(ProjectPaths.resolve(mapPath, source)).withFirstGid(firstGid)
    }

    private fun tileset(obj: JsonObject, firstGid: Int, path: String) = TiledTileset(
        firstGid = firstGid,
        name = obj.string("name") ?: ProjectPaths.baseNameOf(path),
        tileWidth = obj.requireInt("tilewidth", path),
        tileHeight = obj.requireInt("tileheight", path),
        tileCount = obj.int("tilecount") ?: 0,
        columns = obj.int("columns") ?: 0,
        margin = obj.int("margin") ?: 0,
        spacing = obj.int("spacing") ?: 0,
        image = obj.string("image")?.let { ProjectPaths.resolve(path, it) },
        tiles = obj.array("tiles").mapObjects { tile(it, path) }.associateBy(TiledTile::id),
        properties = properties(obj),
    )

    private fun tile(obj: JsonObject, path: String) = TiledTile(
        id = obj.requireInt("id", path),
        type = obj.string("class") ?: obj.string("type").orEmpty(),
        properties = properties(obj),
        image = obj.string("image")?.let { ProjectPaths.resolve(path, it) },
        imageWidth = obj.int("imagewidth") ?: 0,
        imageHeight = obj.int("imageheight") ?: 0,
    )

    // ---- properties ------------------------------------------------------

    /** Tiled 1.2+ writes an array of `{name, type, value}`; earlier versions wrote a plain object. */
    private fun properties(obj: JsonObject): TiledProperties = when (val props = obj["properties"]) {
        is JsonArray -> TiledProperties(
            props.mapObjects { it.string("name").orEmpty() to (it["value"]?.asText().orEmpty()) }.toMap(),
        )
        is JsonObject -> TiledProperties(props.mapValues { (_, value) -> value.asText() })
        else -> TiledProperties.EMPTY
    }

    // ---- JSON helpers ----------------------------------------------------

    private fun parseObject(text: String, path: String): JsonObject =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ImportException("'$path' is not valid Tiled JSON")

    private fun JsonElement.asText(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.requireInt(key: String, path: String): Int =
        int(key) ?: throw ImportException("'$path' is missing '$key'")

    private fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T): List<T> = filterIsInstance<JsonObject>().map(transform)
}
