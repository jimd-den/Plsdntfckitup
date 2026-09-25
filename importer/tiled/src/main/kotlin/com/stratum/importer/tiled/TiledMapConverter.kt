package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportedTexture
import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.importer.common.ImportNaming
import com.stratum.importer.common.ProjectPaths
import kotlin.math.ceil
import kotlin.math.floor

/** One Tiled map as engine content: the level, the blocks it is made of, and their art. */
data class ConvertedMap(
    val map: TileMap,
    val blocks: List<BlockType>,
    val textures: List<ImportedTexture>,
    val warnings: List<String>,
)

/**
 * Converts a [TiledMap] into a [TileMap] the voxel engine can stand on.
 *
 * Every decision about depth is made by [LayerRoles]; this class only walks
 * the layers, collects what they place, and reports what it had to leave out.
 */
class TiledMapConverter(private val namespace: String) {

    fun convert(tiled: TiledMap, mapId: String, biomeId: String? = null): ConvertedMap {
        val catalog = TileBlockCatalog(namespace, tiled)
        val warnings = FormatWarnings.of(tiled).toMutableList()
        val layers = tileLayers(tiled, catalog, warnings) + listOfNotNull(collisionLayer(tiled, catalog))
        val ground = groundBlock(layers, catalog)
        val map = TileMap(
            id = mapId,
            name = tiled.properties.string("name") ?: ImportNaming.displayName(ProjectPaths.baseNameOf(tiled.path)),
            width = tiled.width,
            height = tiled.height,
            layers = layers,
            markers = MarkerReader(tiled).markers(),
            groundBlockId = ground,
            groundLevel = tiled.properties.int("groundLevel") ?: TileMap.DEFAULT_GROUND_LEVEL,
            biomeId = biomeId,
        )
        return ConvertedMap(map, catalog.allBlocks, catalog.allTextures, warnings)
    }

    private fun tileLayers(tiled: TiledMap, catalog: TileBlockCatalog, warnings: MutableList<String>): List<TileLayer> {
        val tileLayers = tiled.flattenedLayers().filterIsInstance<TiledTileLayer>()
        return tileLayers.mapIndexedNotNull { index, layer ->
            val role = LayerRoles.roleOf(layer.name, layer.properties, isFirstTileLayer = index == 0)
            when {
                !layer.visible && role.kind != LayerRole.Kind.WALL -> null.also { warnings += "Hidden layer '${layer.name}' was left out" }
                !role.isPlaced -> null.also { warnings += "Layer '${layer.name}' draws over the player in 2D and was left out; give it an 'elevation' property to place it" }
                else -> toTileLayer(tiled, layer, role, catalog).takeUnless(TileLayer::isEmpty)
            }
        }
    }

    private fun toTileLayer(tiled: TiledMap, layer: TiledTileLayer, role: LayerRole, catalog: TileBlockCatalog): TileLayer {
        val ids = List(tiled.width * tiled.height) { cell ->
            val x = cell % tiled.width
            val y = cell / tiled.width
            if (x < layer.width && y < layer.height) catalog.blockFor(layer.gidAt(x, y), role) else null
        }
        return TileLayer.of(layer.name, tiled.width, tiled.height, ids, role.elevation, role.thickness)
    }

    /**
     * Flame RPGs usually say where you cannot walk with rectangles on an
     * object layer rather than with tiles. Those become a barrier standing on
     * the floor, so the obstacle painted underneath is one you cannot walk
     * through.
     */
    private fun collisionLayer(tiled: TiledMap, catalog: TileBlockCatalog): TileLayer? {
        val cells = CollisionMask.of(tiled)
        if (cells.none { it }) return null
        val barrier = catalog.plainBlock("barrier", BARRIER_COLOR, solid = true, shape = BlockShape.WALL)
        return TileLayer.of("collision", tiled.width, tiled.height, cells.map { if (it) barrier else null }, elevation = 1, thickness = 2)
    }

    /**
     * A plain block fills the ground under and around the level, coloured like
     * the floor's own colour property when it has one. Plain rather than the
     * commonest tile: it shows wherever the author left a cell empty, and a
     * tile repeated into every gap reads as noise, not as ground.
     */
    private fun groundBlock(layers: List<TileLayer>, catalog: TileBlockCatalog): String {
        val floorColor = layers.firstOrNull { it.elevation == 0 }?.let(catalog::commonestColor)
        return catalog.plainBlock("ground", floorColor ?: GROUND_COLOR)
    }

    private companion object {
        const val BARRIER_COLOR = 0xFF5B5347
        const val GROUND_COLOR = 0xFF6B7A4F
    }
}

/** What in a Tiled map this engine understands only partly, said once each. */
internal object FormatWarnings {
    fun of(tiled: TiledMap): List<String> = buildList {
        if (tiled.orientation == TiledOrientation.STAGGERED || tiled.orientation == TiledOrientation.HEXAGONAL) {
            add("'${tiled.path}' is ${tiled.orientation.name.lowercase()}; it was read as a square grid")
        }
        val layers = tiled.flattenedLayers()
        if (layers.any { it is TiledImageLayer }) add("Image layers in '${tiled.path}' have no place in a 3D world and were left out")
        if (layers.filterIsInstance<TiledTileLayer>().any { layer -> layer.gids.any(TiledGid::isTransformed) }) {
            add("Some tiles in '${tiled.path}' are flipped or rotated; they are placed unflipped")
        }
    }
}

/** Grid cells covered by collision rectangles, row by row. */
internal object CollisionMask {

    fun of(tiled: TiledMap): List<Boolean> {
        val mask = BooleanArray(tiled.width * tiled.height)
        val scale = PixelScale.of(tiled)
        tiled.flattenedLayers().filterIsInstance<TiledObjectGroup>().forEach { group ->
            val groupBlocks = LayerRoles.isCollision(group.name, group.properties)
            group.objects.filter { it.width > 0f && it.height > 0f }
                .filter { obj -> groupBlocks || LayerRoles.isCollision(obj.type, obj.properties) }
                .forEach { obj -> cover(mask, tiled, scale, obj) }
        }
        return mask.toList()
    }

    private fun cover(mask: BooleanArray, tiled: TiledMap, scale: PixelScale, obj: TiledObject) {
        val left = floor(scale.cellX(obj.x)).toInt().coerceAtLeast(0)
        val top = floor(scale.cellY(obj.y)).toInt().coerceAtLeast(0)
        val right = ceil(scale.cellX(obj.x + obj.width)).toInt().coerceAtMost(tiled.width)
        val bottom = ceil(scale.cellY(obj.y + obj.height)).toInt().coerceAtMost(tiled.height)
        for (y in top until bottom) for (x in left until right) mask[y * tiled.width + x] = true
    }
}

/** Tiled object coordinates are pixels; isometric maps measure both axes in tile heights. */
internal data class PixelScale(val perCellX: Float, val perCellY: Float) {
    fun cellX(pixels: Float): Float = pixels / perCellX

    fun cellY(pixels: Float): Float = pixels / perCellY

    companion object {
        fun of(tiled: TiledMap): PixelScale =
            if (tiled.orientation == TiledOrientation.ISOMETRIC) PixelScale(tiled.tileHeight.toFloat(), tiled.tileHeight.toFloat())
            else PixelScale(tiled.tileWidth.toFloat(), tiled.tileHeight.toFloat())
    }
}

/** Reads spawn points and places of interest from a map's objects. */
internal class MarkerReader(private val tiled: TiledMap) {

    private val scale = PixelScale.of(tiled)

    fun markers(): List<MapMarker> = tiled.flattenedLayers()
        .filterIsInstance<TiledObjectGroup>()
        .flatMap { it.objects }
        .mapNotNull(::markerFor)

    private fun markerFor(obj: TiledObject): MapMarker? {
        val kind = kindOf(obj) ?: return null
        return MapMarker(
            kind = kind,
            x = scale.cellX(obj.x + obj.width / 2f),
            y = scale.cellY(obj.y + obj.height / 2f),
            name = obj.name,
            refId = obj.properties.firstString("enemy", "monster", "ref", "id") ?: obj.name.takeIf { kind == MarkerKind.ENEMY_SPAWN && it.isNotBlank() },
        )
    }

    private fun kindOf(obj: TiledObject): MarkerKind? {
        val words = "${obj.type} ${obj.name}".lowercase()
        return when {
            // Enemies first: "enemy_spawn" is an enemy, not the player's spawn.
            ENEMY_WORDS.any(words::contains) -> MarkerKind.ENEMY_SPAWN
            PLAYER_WORDS.any(words::contains) -> MarkerKind.PLAYER_SPAWN
            obj.isPoint && obj.name.isNotBlank() -> MarkerKind.POINT_OF_INTEREST
            else -> null
        }
    }

    private companion object {
        val PLAYER_WORDS = listOf("player", "spawn", "start", "hero")
        val ENEMY_WORDS = listOf("enemy", "monster", "mob", "spawner")
    }
}
