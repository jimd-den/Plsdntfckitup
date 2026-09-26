package com.stratum.core.domain.map

import com.stratum.core.domain.world.Chunk

/**
 * A hand-authored level: tile layers laid over a grid, the way Tiled -- and
 * the Flame RPGs built on it -- describe a map.
 *
 * The voxel engine reads it as a stack. Every column is filled with
 * [groundBlockId] up to [groundLevel], and each layer then paints its cells
 * at its own [TileLayer.elevation] above that, so a floor layer repaints the
 * surface, a wall layer stands on it, and a roof layer floats above both.
 *
 * Pure data, with no idea which editor or file format it came from.
 */
class TileMap(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val layers: List<TileLayer>,
    val markers: List<MapMarker> = emptyList(),
    /** Fills every column from bedrock up to [groundLevel]. */
    val groundBlockId: String,
    /** The z of the surface a floor layer repaints. */
    val groundLevel: Int = DEFAULT_GROUND_LEVEL,
    /** The region the whole map belongs to; null takes the first loaded biome. */
    val biomeId: String? = null,
) {
    init {
        require(width > 0 && height > 0) { "Map '$id' is ${width}x$height; a map needs at least one cell" }
        require(groundLevel in 1 until Chunk.HEIGHT) { "Map '$id' ground level $groundLevel is outside the world column" }
        layers.forEach { layer ->
            require(layer.width == width && layer.height == height) {
                "Layer '${layer.name}' is ${layer.width}x${layer.height} but map '$id' is ${width}x$height"
            }
            require(topOf(layer) < Chunk.HEIGHT) {
                "Layer '${layer.name}' reaches z=${topOf(layer)}, above the world column"
            }
        }
    }

    /** Where the player starts, when the author said. */
    val playerSpawn: MapMarker? get() = markers.firstOrNull { it.kind == MarkerKind.PLAYER_SPAWN }

    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Every block id the map places, so a pack can be validated before any chunk is built. */
    fun referencedBlockIds(): Set<String> = layers.flatMapTo(mutableSetOf(groundBlockId)) { it.palette }

    /** The z of the lowest cell a layer paints. */
    fun baseOf(layer: TileLayer): Int = groundLevel + layer.elevation

    /** The z of the highest cell a layer paints. */
    fun topOf(layer: TileLayer): Int = baseOf(layer) + layer.thickness - 1

    companion object {
        const val DEFAULT_GROUND_LEVEL = 8
    }
}

/**
 * One layer of a [TileMap]: which block, if any, fills each cell.
 *
 * Cells are stored as indices into [palette] rather than as strings, so a
 * large map costs an int per cell rather than a reference per cell.
 */
class TileLayer private constructor(
    val name: String,
    val width: Int,
    val height: Int,
    val palette: List<String>,
    private val cells: IntArray,
    /** Blocks above the ground surface the layer's lowest cell sits; 0 repaints the surface. */
    val elevation: Int,
    /** How many blocks tall each filled cell stands. A wall is two or three. */
    val thickness: Int,
) {
    init {
        require(cells.size == width * height) { "Layer '$name' has ${cells.size} cells for a ${width}x$height grid" }
        require(elevation >= 0) { "Layer '$name' elevation $elevation would dig below the ground" }
        require(thickness >= 1) { "Layer '$name' thickness $thickness paints nothing" }
        require(cells.all { it == EMPTY || it in palette.indices }) { "Layer '$name' names a palette entry it does not have" }
    }

    fun blockIdAt(x: Int, y: Int): String? {
        if (x !in 0 until width || y !in 0 until height) return null
        val entry = cells[y * width + x]
        return if (entry == EMPTY) null else palette[entry]
    }

    val isEmpty: Boolean get() = cells.all { it == EMPTY }

    companion object {
        private const val EMPTY = -1

        /** Builds a layer from one block id per cell, row by row; null leaves a cell empty. */
        fun of(
            name: String,
            width: Int,
            height: Int,
            blockIds: List<String?>,
            elevation: Int = 0,
            thickness: Int = 1,
        ): TileLayer {
            val palette = blockIds.filterNotNull().distinct()
            val indexOf = palette.withIndex().associate { (index, id) -> id to index }
            val cells = IntArray(blockIds.size) { cell -> blockIds[cell]?.let(indexOf::getValue) ?: EMPTY }
            return TileLayer(name, width, height, palette, cells, elevation, thickness)
        }
    }
}

/** A point the author placed on the map that means something to the game. */
data class MapMarker(
    val kind: MarkerKind,
    /** In map cells, fractional, with the origin at the map's top-left. */
    val x: Float,
    val y: Float,
    val name: String = "",
    /** What to spawn or show here, such as an enemy id. */
    val refId: String? = null,
)

enum class MarkerKind { PLAYER_SPAWN, ENEMY_SPAWN, POINT_OF_INTEREST }
