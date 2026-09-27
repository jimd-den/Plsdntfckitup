package com.stratum.engine.world

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.TerrainGeneratorFactory
import com.stratum.core.domain.world.TerrainRecipe
import kotlin.math.floor

/**
 * Builds the world from a hand-authored [TileMap] rather than from noise.
 *
 * The map is placed so its player spawn -- or its centre, if it has none --
 * sits on the world origin, which is where a session drops the player. Beyond
 * the map's edge the ground rises into a wall, so a level made for a bounded
 * screen stays bounded rather than falling away into nothing.
 */
class TileMapTerrainGenerator(
    private val map: TileMap,
    private val biome: BiomeDefinition? = null,
) : TerrainGenerator, BiomeSource, MarkedLevel {

    private val placement = MapPlacement.centredOnSpawn(map)

    /** Resolved once per registry; a world keeps one registry for its lifetime. */
    private var palette: ColumnPalette? = null

    private fun paletteFor(registry: BlockRegistry): ColumnPalette =
        palette?.takeIf { it.registry === registry } ?: ColumnPalette.resolve(map, registry).also { palette = it }

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        val palette = paletteFor(registry)
        val chunk = Chunk(pos)
        for (localY in 0 until Chunk.SIZE) {
            for (localX in 0 until Chunk.SIZE) {
                val mapX = placement.mapX(pos.originX + localX)
                val mapY = placement.mapY(pos.originY + localY)
                ColumnWriter(chunk, localX, localY, palette).write(map, mapX, mapY)
            }
        }
        return chunk
    }

    /** The whole map is one region; a map with no biome leaves the region unnamed. */
    override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition =
        biome ?: throw IllegalStateException("Map '${map.id}' has no biome to report")

    override val markers: List<PlacedMarker>
        get() = map.markers.map { PlacedMarker(it, x = it.x - placement.offsetX, y = it.y - placement.offsetY) }

    companion object {
        /** How far above the ground the wall around the map rises. */
        const val BORDER_HEIGHT = 4

        /** Reads the recipe's map option and builds a generator for it. */
        val factory = TerrainGeneratorFactory { context -> fromContext(context) }

        fun fromContext(context: TerrainContext): TerrainGenerator {
            val mapId = context.recipe.options[TerrainRecipe.MAP_OPTION]
                ?: throw IllegalArgumentException("A ${TerrainRecipe.TILE_MAP} recipe must name a map in options['${TerrainRecipe.MAP_OPTION}']")
            val map = context.maps.firstOrNull { it.id == mapId }
                ?: throw IllegalArgumentException("No map named '$mapId'. Loaded maps: ${context.maps.joinToString { it.id }}")
            val biome = context.biomes.firstOrNull { it.id == map.biomeId } ?: context.biomes.firstOrNull()
            val generator = TileMapTerrainGenerator(map, biome)
            return if (biome == null) BiomelessGenerator(generator) else generator
        }
    }
}

/** Hides [BiomeSource] when there is no biome to report, so the session never asks. */
private class BiomelessGenerator(private val inner: TileMapTerrainGenerator) :
    TerrainGenerator by inner, MarkedLevel by inner

/** Where the map sits in the world: map cell = world block + offset. */
internal data class MapPlacement(val offsetX: Int, val offsetY: Int) {

    fun mapX(worldX: Int): Int = worldX + offsetX

    fun mapY(worldY: Int): Int = worldY + offsetY

    companion object {
        fun centredOnSpawn(map: TileMap): MapPlacement {
            val spawn = map.playerSpawn
            return MapPlacement(
                offsetX = spawn?.let { floor(it.x).toInt() } ?: (map.width / 2),
                offsetY = spawn?.let { floor(it.y).toInt() } ?: (map.height / 2),
            )
        }
    }
}

/** A map's block ids resolved to registry indices once, rather than per cell. */
internal class ColumnPalette private constructor(
    val registry: BlockRegistry,
    val bedrock: Int,
    val ground: Int,
    private val indices: Map<String, Int>,
) {
    fun indexOf(blockId: String): Int = indices.getValue(blockId)

    companion object {
        fun resolve(map: TileMap, registry: BlockRegistry) = ColumnPalette(
            registry = registry,
            bedrock = registry.indexOf(BlockType.BEDROCK.id),
            ground = registry.indexOf(map.groundBlockId),
            indices = map.referencedBlockIds().associateWith(registry::indexOf),
        )
    }
}

/** Fills one column of a chunk from one cell of the map. */
internal class ColumnWriter(
    private val chunk: Chunk,
    private val localX: Int,
    private val localY: Int,
    private val palette: ColumnPalette,
) {
    fun write(map: TileMap, mapX: Int, mapY: Int) {
        chunk.setBlock(localX, localY, 0, palette.bedrock)
        if (!map.contains(mapX, mapY)) {
            fillGround(upTo = map.groundLevel + TileMapTerrainGenerator.BORDER_HEIGHT)
            return
        }
        fillGround(upTo = map.groundLevel)
        map.layers.forEach { layer -> paint(map, layer, mapX, mapY) }
    }

    private fun fillGround(upTo: Int) {
        for (z in 1..upTo) chunk.setBlock(localX, localY, z, palette.ground)
    }

    private fun paint(map: TileMap, layer: TileLayer, mapX: Int, mapY: Int) {
        val blockId = layer.blockIdAt(mapX, mapY) ?: return
        val index = palette.indexOf(blockId)
        for (z in map.baseOf(layer)..map.topOf(layer)) chunk.setBlock(localX, localY, z, index)
    }
}
