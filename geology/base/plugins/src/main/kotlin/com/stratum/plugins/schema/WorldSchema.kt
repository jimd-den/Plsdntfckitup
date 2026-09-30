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
import com.stratum.core.domain.world.CarverRule
import com.stratum.core.domain.world.ClimatePoint
import com.stratum.core.domain.world.ClimateSpec
import com.stratum.core.domain.world.LiquidRule
import com.stratum.core.domain.world.LiquidTarget
import com.stratum.core.domain.world.OreRule
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TreeRule
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
internal data class PassSchema(val id: String, val options: Map<String, String> = emptyMap()) {
    fun toDomain() = PassSpec(id, options)

    companion object {
        fun of(p: PassSpec) = PassSchema(p.id, p.options)
    }
}

@Serializable
internal data class ClimatePointSchema(
    val biome: String,
    val temperature: Float,
    val moisture: Float,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
) {
    fun toDomain() = ClimatePoint(biome, temperature, moisture, minDepth, maxDepth)

    companion object {
        fun of(p: ClimatePoint) = ClimatePointSchema(p.biomeId, p.temperature, p.moisture, p.minDepth, p.maxDepth)
    }
}

private val CLIMATE = ClimateSpec()

@Serializable
internal data class ClimateSchema(
    val points: List<ClimatePointSchema> = emptyList(),
    val scale: Float = CLIMATE.scale,
    val blend: Float = CLIMATE.blend,
) {
    fun toDomain() = ClimateSpec(points.map { it.toDomain() }, scale, blend)

    companion object {
        fun of(c: ClimateSpec) = ClimateSchema(c.points.map(ClimatePointSchema::of), c.scale, c.blend)
    }
}

private val CARVER = CarverRule(kind = "caves")

@Serializable
internal data class CarverSchema(
    val kind: String,
    val minZ: Int = CARVER.minZ,
    val maxZ: Int = CARVER.maxZ,
    val amount: Float = CARVER.amount,
    val size: Float = CARVER.size,
    val headroom: Int = CARVER.headroom,
    val options: Map<String, String> = emptyMap(),
) {
    fun toDomain() = CarverRule(kind, minZ, maxZ, amount, size, headroom, options)

    companion object {
        fun of(c: CarverRule) = CarverSchema(c.kind, c.minZ, c.maxZ, c.amount, c.size, c.headroom, c.options)
    }
}

@Serializable
internal data class OreSchema(
    val block: String,
    val minZ: Int,
    val maxZ: Int,
    val veinsPerChunk: Float,
    val veinSize: Int = 6,
    val biomes: List<String> = emptyList(),
    val replaces: List<String> = emptyList(),
) {
    fun toDomain() = OreRule(block, minZ, maxZ, veinsPerChunk, veinSize, biomes, replaces)

    companion object {
        fun of(o: OreRule) = OreSchema(o.blockId, o.minZ, o.maxZ, o.veinsPerChunk, o.veinSize, o.biomeIds, o.replaces)
    }
}

@Serializable
internal data class TreeSchema(
    val trunk: String,
    val leaves: String,
    val chance: Float,
    val minHeight: Int = 3,
    val maxHeight: Int = 5,
    val canopyRadius: Int = 2,
    val biomes: List<String> = emptyList(),
) {
    fun toDomain() = TreeRule(trunk, leaves, chance, minHeight, maxHeight, canopyRadius, biomes)

    companion object {
        fun of(t: TreeRule) = TreeSchema(t.trunkBlockId, t.leafBlockId, t.chance, t.minHeight, t.maxHeight, t.canopyRadius, t.biomeIds)
    }
}

@Serializable
internal data class LiquidSchema(
    val block: String,
    val maxZ: Int,
    val minZ: Int = 1,
    val target: String = SchemaValues.name(LiquidTarget.OPEN),
    val share: Float = 1f,
) {
    fun toDomain() = LiquidRule(block, maxZ, minZ, SchemaValues.enum<LiquidTarget>(target, "liquid '$block' target"), share)

    companion object {
        fun of(l: LiquidRule) = LiquidSchema(l.blockId, l.maxZ, l.minZ, SchemaValues.name(l.target), l.share)
    }
}

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
    val passes: List<PassSchema> = emptyList(),
    val climate: ClimateSchema? = null,
    val carvers: List<CarverSchema> = emptyList(),
    val ores: List<OreSchema> = emptyList(),
    val trees: List<TreeSchema> = emptyList(),
    val liquids: List<LiquidSchema> = emptyList(),
) {
    fun toDomain() = TerrainRecipe(
        generator, elevation.map { NoiseLayer(it.scale, it.amplitude, it.seedOffset) }, terraceStep,
        strata.map { Stratum(it.block, it.thickness) }, caveDensity, scatterClustering, scatterClusterScale, options,
        passes.map { it.toDomain() }, climate?.toDomain(), carvers.map { it.toDomain() }, ores.map { it.toDomain() },
        trees.map { it.toDomain() }, liquids.map { it.toDomain() },
    )

    companion object {
        fun of(r: TerrainRecipe) = TerrainSchema(
            r.generatorId, r.elevation.map { NoiseSchema(it.scale, it.amplitude, it.seedOffset) }, r.terraceStep,
            r.strata.map { StratumSchema(it.blockId, it.thickness) }, r.caveDensity, r.scatterClustering, r.scatterClusterScale, r.options,
            r.passes.map(PassSchema::of), r.climate?.let(ClimateSchema::of), r.carvers.map(CarverSchema::of), r.ores.map(OreSchema::of),
            r.trees.map(TreeSchema::of), r.liquids.map(LiquidSchema::of),
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
