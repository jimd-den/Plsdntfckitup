package com.stratum.plugins.schema

import com.stratum.core.domain.content.BiomeComposition
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.content.Landmark
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.map.TileLayer
import com.stratum.core.domain.map.TileMap
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.NoiseLayer
import com.stratum.core.domain.world.Stratum
import com.stratum.core.domain.world.TerrainRecipe
import kotlinx.serialization.Serializable

// The world half of a pack: blocks, regions, terrain and hand-made maps.

private val BLOCK = BlockType(id = "", displayName = "")

@Serializable
internal data class BlockSchema(
    val id: String,
    val name: String,
    val material: String = SchemaValues.name(BLOCK.material),
    val hardness: Float = BLOCK.hardness,
    val requiredTier: Int = BLOCK.requiredTier,
    val solid: Boolean = BLOCK.isSolid,
    val opaque: Boolean = BLOCK.isOpaque,
    val gravity: Boolean = BLOCK.hasGravity,
    val needsSupport: Boolean = BLOCK.needsSupport,
    val light: Int = BLOCK.lightEmission,
    val drop: String? = BLOCK.dropId,
    val glyph: String? = BLOCK.glyph,
    val glyphScale: Float = BLOCK.glyphScale,
    val topColor: String = SchemaValues.color(BLOCK.topColor),
    val sideColor: String = SchemaValues.color(BLOCK.sideColor),
    val accentColor: String = SchemaValues.color(BLOCK.accentColor),
    val shape: String = SchemaValues.name(BLOCK.shape),
) {
    fun toDomain() = BlockType(
        id = id, displayName = name, material = SchemaValues.enum<BlockMaterial>(material, "block '$id' material"),
        hardness = hardness, requiredTier = requiredTier, isSolid = solid, isOpaque = opaque, hasGravity = gravity,
        needsSupport = needsSupport, lightEmission = light, dropId = drop, glyph = glyph, glyphScale = glyphScale,
        topColor = SchemaValues.color(topColor, "block '$id' topColor"), sideColor = SchemaValues.color(sideColor, "block '$id' sideColor"),
        accentColor = SchemaValues.color(accentColor, "block '$id' accentColor"), shape = SchemaValues.enum<BlockShape>(shape, "block '$id' shape"),
    )

    companion object {
        fun of(b: BlockType) = BlockSchema(
            b.id, b.displayName, SchemaValues.name(b.material), b.hardness, b.requiredTier, b.isSolid, b.isOpaque, b.hasGravity,
            b.needsSupport, b.lightEmission, b.dropId, b.glyph, b.glyphScale, SchemaValues.color(b.topColor),
            SchemaValues.color(b.sideColor), SchemaValues.color(b.accentColor), SchemaValues.name(b.shape),
        )
    }
}

private val BIOME = BiomeDefinition(id = "", name = "", surfaceBlockId = "", subsurfaceBlockId = "", bedrockFillerBlockId = "")
private val LANDMARK = Landmark(centreBlockId = "")

@Serializable
internal data class ScatterSchema(val block: String, val chance: Float, val height: Int = 1, val cap: String? = null) {
    fun toDomain() = ScatterRule(block, chance, height, cap)

    companion object {
        fun of(r: ScatterRule) = ScatterSchema(r.blockId, r.chance, r.height, r.capBlockId)
    }
}

@Serializable
internal data class DepositSchema(val block: String, val minZ: Int, val maxZ: Int, val chance: Float, val clusterSize: Int = 4) {
    fun toDomain() = DepositRule(block, minZ, maxZ, chance, clusterSize)

    companion object {
        fun of(r: DepositRule) = DepositSchema(r.blockId, r.minZ, r.maxZ, r.chance, r.clusterSize)
    }
}

@Serializable
internal data class LandmarkSchema(
    val centre: String,
    val ring: String? = LANDMARK.ringBlockId,
    val ringRadius: Int = LANDMARK.ringRadius,
    val ringCount: Int = LANDMARK.ringCount,
    val floor: String? = LANDMARK.floorBlockId,
    val floorRadius: Int = LANDMARK.floorRadius,
    val chance: Float = LANDMARK.chance,
    val clearRadius: Int = LANDMARK.clearRadius,
) {
    fun toDomain() = Landmark(centre, ring, ringRadius, ringCount, floor, floorRadius, chance, clearRadius)

    companion object {
        fun of(l: Landmark) = LandmarkSchema(l.centreBlockId, l.ringBlockId, l.ringRadius, l.ringCount, l.floorBlockId, l.floorRadius, l.chance, l.clearRadius)
    }
}

@Serializable
internal data class BiomeSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val surface: String,
    val subsurface: String,
    val filler: String,
    val heightBias: Int = BIOME.heightBias,
    val roughness: Float = BIOME.roughness,
    val scatter: List<ScatterSchema> = emptyList(),
    val deposits: List<DepositSchema> = emptyList(),
    val ambientLight: Int = BIOME.ambientLight,
    val path: String? = null,
    val pathWidth: Float = BIOME.composition.pathWidth,
    val landmark: LandmarkSchema? = null,
    val temperature: Float = BIOME.temperature,
) {
    fun toDomain() = BiomeDefinition(
        id, name, description, surface, subsurface, filler, heightBias, roughness, scatter.map { it.toDomain() },
        deposits.map { it.toDomain() }, ambientLight, BiomeComposition(path, pathWidth, landmark?.toDomain()), temperature,
    )

    companion object {
        fun of(b: BiomeDefinition) = BiomeSchema(
            b.id, b.name, b.description, b.surfaceBlockId, b.subsurfaceBlockId, b.bedrockFillerBlockId, b.heightBias, b.roughness,
            b.scatter.map(ScatterSchema::of), b.deposits.map(DepositSchema::of), b.ambientLight, b.composition.pathBlockId,
            b.composition.pathWidth, b.composition.landmark?.let(LandmarkSchema::of), b.temperature,
        )
    }
}

private val RECIPE = TerrainRecipe()

@Serializable
internal data class NoiseSchema(val scale: Float, val amplitude: Float, val seedOffset: Int = 0)

@Serializable
internal data class StratumSchema(val block: String, val thickness: Int)

@Serializable
internal data class TerrainSchema(
    val generator: String = RECIPE.generatorId,
    val elevation: List<NoiseSchema> = RECIPE.elevation.map { NoiseSchema(it.scale, it.amplitude, it.seedOffset) },
    val terraceStep: Int = RECIPE.terraceStep,
    val strata: List<StratumSchema> = emptyList(),
    val caveDensity: Float? = null,
    val scatterClustering: Float = RECIPE.scatterClustering,
    val scatterClusterScale: Float = RECIPE.scatterClusterScale,
    val options: Map<String, String> = emptyMap(),
) {
    fun toDomain() = TerrainRecipe(
        generator, elevation.map { NoiseLayer(it.scale, it.amplitude, it.seedOffset) }, terraceStep,
        strata.map { Stratum(it.block, it.thickness) }, caveDensity, scatterClustering, scatterClusterScale, options,
    )

    companion object {
        fun of(r: TerrainRecipe) = TerrainSchema(
            r.generatorId, r.elevation.map { NoiseSchema(it.scale, it.amplitude, it.seedOffset) }, r.terraceStep,
            r.strata.map { StratumSchema(it.blockId, it.thickness) }, r.caveDensity, r.scatterClustering, r.scatterClusterScale, r.options,
        )
    }
}

/** A layer as a palette and one index per cell, row by row, -1 for empty: compact, and diffable. */
@Serializable
internal data class LayerSchema(
    val name: String,
    val elevation: Int = 0,
    val thickness: Int = 1,
    val palette: List<String>,
    val cells: List<Int>,
)

@Serializable
internal data class MarkerSchema(val kind: String, val x: Float, val y: Float, val name: String = "", val ref: String? = null)

@Serializable
internal data class MapSchema(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val ground: String,
    val groundLevel: Int = TileMap.DEFAULT_GROUND_LEVEL,
    val biome: String? = null,
    val layers: List<LayerSchema> = emptyList(),
    val markers: List<MarkerSchema> = emptyList(),
) {
    fun toDomain(): TileMap {
        val decoded = layers.map { layer ->
            if (layer.cells.size != width * height) throw ImportException("map '$id' layer '${layer.name}' has ${layer.cells.size} cells for ${width}x$height")
            val ids = layer.cells.map { index -> if (index < 0) null else layer.palette.getOrNull(index) ?: throw ImportException("map '$id' layer '${layer.name}' names palette entry $index it does not have") }
            TileLayer.of(layer.name, width, height, ids, layer.elevation, layer.thickness)
        }
        val marks = markers.map { MapMarker(SchemaValues.enum<MarkerKind>(it.kind, "map '$id' marker kind"), it.x, it.y, it.name, it.ref) }
        return TileMap(id, name, width, height, decoded, marks, ground, groundLevel, biome)
    }

    companion object {
        fun of(m: TileMap) = MapSchema(
            m.id, m.name, m.width, m.height, m.groundBlockId, m.groundLevel, m.biomeId,
            m.layers.map { layer ->
                val index = layer.palette.withIndex().associate { (i, id) -> id to i }
                val cells = (0 until m.height).flatMap { y -> (0 until m.width).map { x -> layer.blockIdAt(x, y)?.let(index::getValue) ?: -1 } }
                LayerSchema(layer.name, layer.elevation, layer.thickness, layer.palette, cells)
            },
            m.markers.map { MarkerSchema(SchemaValues.name(it.kind), it.x, it.y, it.name, it.refId) },
        )
    }
}
