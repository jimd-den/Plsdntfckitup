package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportException
import com.stratum.importer.common.ProjectPaths
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads Tiled's XML formats: `.tmx` maps and `.tsx` tilesets.
 *
 * `javax.xml` rather than a library, because both the JVM and Android ship
 * it. External entities are switched off: the file came from a stranger.
 */
internal object TmxFormat {

    fun readMap(text: String, path: String, externalTileset: (String) -> TiledTileset): TiledMap {
        val root = parse(text, path).requireTag("map", path)
        val width = root.requireInt("width", path)
        val height = root.requireInt("height", path)
        return TiledMap(
            path = path,
            orientation = TiledOrientation.parse(root.attr("orientation")),
            width = width,
            height = height,
            tileWidth = root.requireInt("tilewidth", path),
            tileHeight = root.requireInt("tileheight", path),
            infinite = root.attr("infinite") == "1",
            layers = layersIn(root, width, height, path),
            tilesets = root.children("tileset").map { tilesetEntry(it, path, externalTileset) },
            properties = properties(root),
        )
    }

    fun readTileset(text: String, path: String): TiledTileset =
        tileset(parse(text, path).requireTag("tileset", path), firstGid = 0, path)

    // ---- layers ----------------------------------------------------------

    /** Layers of every kind, in document order, which is Tiled's drawing order. */
    private fun layersIn(parent: Element, width: Int, height: Int, path: String): List<TiledLayer> =
        parent.childElements().mapNotNull { child ->
            when (child.tagName) {
                "layer" -> tileLayer(child, width, height)
                "objectgroup" -> TiledObjectGroup(child.name(), child.visible(), properties(child), child.children("object").map(::mapObject))
                "imagelayer" -> TiledImageLayer(child.name(), child.visible(), properties(child))
                "group" -> TiledGroupLayer(child.name(), child.visible(), properties(child), layersIn(child, width, height, path))
                else -> null
            }
        }

    private fun tileLayer(element: Element, mapWidth: Int, mapHeight: Int): TiledTileLayer {
        val width = element.int("width") ?: mapWidth
        val height = element.int("height") ?: mapHeight
        return TiledTileLayer(element.name(), element.visible(), properties(element), width, height, tileData(element, width * height))
    }

    private fun tileData(layer: Element, expected: Int): IntArray {
        val data = layer.children("data").firstOrNull() ?: throw ImportException("Layer '${layer.name()}' has no data")
        if (data.children("chunk").isNotEmpty()) {
            throw ImportException("Layer '${layer.name()}' is from an infinite map; set a fixed map size in Tiled and re-save")
        }
        val encoding = data.attr("encoding")
        if (encoding == null) return data.children("tile").map { it.long("gid")?.toInt() ?: 0 }.toIntArray()
        return LayerDataDecoder.decode(encoding, data.attr("compression"), data.textContent, expected)
    }

    private fun mapObject(element: Element) = TiledObject(
        id = element.int("id") ?: 0,
        name = element.attr("name").orEmpty(),
        type = element.attr("class") ?: element.attr("type").orEmpty(),
        x = element.float("x") ?: 0f,
        y = element.float("y") ?: 0f,
        width = element.float("width") ?: 0f,
        height = element.float("height") ?: 0f,
        isPoint = element.children("point").isNotEmpty(),
        properties = properties(element),
    )

    // ---- tilesets --------------------------------------------------------

    private fun tilesetEntry(element: Element, mapPath: String, external: (String) -> TiledTileset): TiledTileset {
        val firstGid = element.requireInt("firstgid", mapPath)
        val source = element.attr("source") ?: return tileset(element, firstGid, mapPath)
        return external(ProjectPaths.resolve(mapPath, source)).withFirstGid(firstGid)
    }

    private fun tileset(element: Element, firstGid: Int, path: String): TiledTileset {
        val image = element.children("image").firstOrNull()
        return TiledTileset(
            firstGid = firstGid,
            name = element.attr("name") ?: ProjectPaths.baseNameOf(path),
            tileWidth = element.requireInt("tilewidth", path),
            tileHeight = element.requireInt("tileheight", path),
            tileCount = element.int("tilecount") ?: 0,
            columns = element.int("columns") ?: 0,
            margin = element.int("margin") ?: 0,
            spacing = element.int("spacing") ?: 0,
            image = image?.attr("source")?.let { ProjectPaths.resolve(path, it) },
            tiles = element.children("tile").map { tile(it, path) }.associateBy(TiledTile::id),
            properties = properties(element),
        )
    }

    private fun tile(element: Element, path: String): TiledTile {
        val image = element.children("image").firstOrNull()
        return TiledTile(
            id = element.requireInt("id", path),
            type = element.attr("class") ?: element.attr("type").orEmpty(),
            properties = properties(element),
            image = image?.attr("source")?.let { ProjectPaths.resolve(path, it) },
            imageWidth = image?.int("width") ?: 0,
            imageHeight = image?.int("height") ?: 0,
        )
    }

    private fun properties(element: Element): TiledProperties {
        val block = element.children("properties").firstOrNull() ?: return TiledProperties.EMPTY
        return TiledProperties(
            block.children("property").associate { it.attr("name").orEmpty() to (it.attr("value") ?: it.textContent.orEmpty()) },
        )
    }

    // ---- XML helpers -----------------------------------------------------

    private fun parse(text: String, path: String): Element = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    }.getOrElse { throw ImportException("'$path' is not valid Tiled XML", it) }

    private fun Element.requireTag(tag: String, path: String): Element =
        takeIf { it.tagName == tag } ?: throw ImportException("'$path' is a <$tagName>, not a <$tag>")

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.children(tag: String): List<Element> = childElements().filter { it.tagName == tag }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { hasAttribute(name) }

    private fun Element.int(name: String): Int? = attr(name)?.trim()?.toIntOrNull()

    private fun Element.long(name: String): Long? = attr(name)?.trim()?.toLongOrNull()

    private fun Element.float(name: String): Float? = attr(name)?.trim()?.toFloatOrNull()

    private fun Element.requireInt(name: String, path: String): Int =
        int(name) ?: throw ImportException("'$path' <$tagName> is missing '$name'")

    private fun Element.name(): String = attr("name").orEmpty()

    private fun Element.visible(): Boolean = attr("visible") != "0"
}
