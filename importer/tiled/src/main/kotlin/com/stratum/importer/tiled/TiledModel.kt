package com.stratum.importer.tiled

/**
 * A Tiled map as read from disk, before anything engine-specific happens.
 *
 * Both file formats -- `.tmj` JSON and `.tmx` XML -- read into this, so the
 * conversion to engine content is written once. Paths are already resolved
 * to project-relative paths.
 */
data class TiledMap(
    val path: String,
    val orientation: TiledOrientation,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val infinite: Boolean,
    val layers: List<TiledLayer>,
    val tilesets: List<TiledTileset>,
    val properties: TiledProperties = TiledProperties.EMPTY,
) {
    /** The tileset a global tile id belongs to: the one with the highest first gid not above it. */
    fun tilesetFor(gid: Int): TiledTileset? = tilesets.filter { it.firstGid <= gid }.maxByOrNull { it.firstGid }

    /** Every layer, with groups flattened in drawing order. */
    fun flattenedLayers(): List<TiledLayer> = layers.flatMap(TiledLayer::flatten)
}

enum class TiledOrientation {
    ORTHOGONAL, ISOMETRIC, STAGGERED, HEXAGONAL;

    companion object {
        fun parse(text: String?): TiledOrientation =
            entries.firstOrNull { it.name.equals(text, ignoreCase = true) } ?: ORTHOGONAL
    }
}

/** Custom properties, looked up without regard to case because authors are inconsistent about it. */
class TiledProperties(values: Map<String, String>) {

    private val values = values.mapKeys { (key, _) -> key.lowercase() }

    fun string(name: String): String? = values[name.lowercase()]

    fun int(name: String): Int? = string(name)?.trim()?.toIntOrNull()

    fun bool(name: String): Boolean? = when (string(name)?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    /** The first of [names] that is set. */
    fun firstString(vararg names: String): String? = names.firstNotNullOfOrNull(::string)

    fun firstInt(vararg names: String): Int? = names.firstNotNullOfOrNull(::int)

    fun firstBool(vararg names: String): Boolean? = names.firstNotNullOfOrNull(::bool)

    operator fun plus(other: TiledProperties) = TiledProperties(values + other.values)

    companion object {
        val EMPTY = TiledProperties(emptyMap())
    }
}

sealed interface TiledLayer {
    val name: String
    val visible: Boolean
    val properties: TiledProperties

    /** This layer and, for a group, everything in it -- with the group's properties inherited. */
    fun flatten(): List<TiledLayer> = listOf(this)
}

class TiledTileLayer(
    override val name: String,
    override val visible: Boolean,
    override val properties: TiledProperties,
    val width: Int,
    val height: Int,
    /** Raw global ids, flip bits included, row by row. 0 is an empty cell. */
    val gids: IntArray,
) : TiledLayer {
    init {
        require(gids.size == width * height) { "Layer '$name' has ${gids.size} tiles for a ${width}x$height grid" }
    }

    fun gidAt(x: Int, y: Int): Int = gids[y * width + x]
}

data class TiledObjectGroup(
    override val name: String,
    override val visible: Boolean,
    override val properties: TiledProperties,
    val objects: List<TiledObject>,
) : TiledLayer

data class TiledImageLayer(
    override val name: String,
    override val visible: Boolean,
    override val properties: TiledProperties,
) : TiledLayer

data class TiledGroupLayer(
    override val name: String,
    override val visible: Boolean,
    override val properties: TiledProperties,
    val layers: List<TiledLayer>,
) : TiledLayer {
    override fun flatten(): List<TiledLayer> = layers.flatMap { child -> inherit(child).flatten() }

    private fun inherit(child: TiledLayer): TiledLayer = when (child) {
        is TiledTileLayer -> TiledTileLayer(child.name, visible && child.visible, properties + child.properties, child.width, child.height, child.gids)
        is TiledObjectGroup -> child.copy(visible = visible && child.visible, properties = properties + child.properties)
        is TiledImageLayer -> child.copy(visible = visible && child.visible, properties = properties + child.properties)
        is TiledGroupLayer -> child.copy(visible = visible && child.visible, properties = properties + child.properties)
    }
}

data class TiledObject(
    val id: Int,
    val name: String,
    /** Tiled's "class" field, called "type" before Tiled 1.9. */
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val isPoint: Boolean,
    val properties: TiledProperties,
)

/**
 * A tileset: either one image cut into a grid, or a collection of separate
 * images, one per tile.
 */
data class TiledTileset(
    val firstGid: Int,
    val name: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val tileCount: Int,
    val columns: Int,
    val margin: Int = 0,
    val spacing: Int = 0,
    /** The grid image, for an image tileset. */
    val image: String? = null,
    val tiles: Map<Int, TiledTile> = emptyMap(),
    val properties: TiledProperties = TiledProperties.EMPTY,
) {
    /** Pixel rectangle of a tile inside [image], for an image tileset. */
    fun sourceRect(localId: Int): TileRect? {
        if (image == null || columns <= 0) return null
        return TileRect(
            x = margin + (localId % columns) * (tileWidth + spacing),
            y = margin + (localId / columns) * (tileHeight + spacing),
            width = tileWidth,
            height = tileHeight,
        )
    }

    fun withFirstGid(gid: Int) = copy(firstGid = gid)
}

data class TiledTile(
    val id: Int,
    val type: String = "",
    val properties: TiledProperties = TiledProperties.EMPTY,
    /** The tile's own image, for a collection-of-images tileset. */
    val image: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

data class TileRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Tiled packs flip and rotation flags into the top bits of each tile id. */
object TiledGid {
    private const val FLIPPED_HORIZONTALLY = 0x80000000.toInt()
    private const val FLIPPED_VERTICALLY = 0x40000000
    private const val FLIPPED_DIAGONALLY = 0x20000000
    private const val ROTATED_HEXAGONAL = 0x10000000
    private const val FLAGS = FLIPPED_HORIZONTALLY or FLIPPED_VERTICALLY or FLIPPED_DIAGONALLY or ROTATED_HEXAGONAL

    fun tileOf(raw: Int): Int = raw and FLAGS.inv()

    fun isTransformed(raw: Int): Boolean = raw and FLAGS != 0
}
