package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldMarker

/**
 * A registry's answers precomputed into arrays, so a pass asking "is this
 * solid" for every cell of a chunk pays an array read, not a lookup.
 *
 * Immutable once built, so one can be shared by every thread generating
 * chunks for the same world.
 */
class BlockIndex(val registry: BlockRegistry) {
    val bedrock: Int = registry.indexOf(BlockType.BEDROCK.id)
    private val solid = BooleanArray(registry.size) { registry.typeOf(it).isSolid }
    private val liquid = BooleanArray(registry.size) { registry.typeOf(it).material == BlockMaterial.LIQUID }

    fun of(id: String): Int = registry.indexOf(id)

    fun orNull(id: String?): Int? = id?.let(registry::indexOrNull)

    fun isSolid(index: Int): Boolean = solid.getOrElse(index) { false }

    fun isLiquid(index: Int): Boolean = liquid.getOrElse(index) { false }

    /** Solid rock a vein or a carver may take: not air, not bedrock, not a liquid. */
    fun isRock(index: Int): Boolean = index != BlockRegistry.AIR_INDEX && index != bedrock && isSolid(index)
}

/**
 * One chunk being generated, and what the passes know about its columns.
 *
 * Per-column facts -- the biome, the blended height parameters, the ground
 * height -- live in flat arrays filled once and read by every later pass,
 * rather than recomputed or allocated per block. Passes address blocks in
 * world coordinates; writes outside the chunk are dropped, which is what lets
 * a feature that crosses a border be drawn by code that never thinks about
 * the border.
 */
class ChunkContext(
    val pos: ChunkPos,
    val blocks: BlockIndex,
    val services: WorldServices,
) {
    val chunk: Chunk = Chunk(pos)
    val registry: BlockRegistry get() = blocks.registry
    val originX: Int = pos.originX
    val originY: Int = pos.originY

    private val biome = arrayOfNulls<BiomeDefinition>(COLUMNS)
    private val temperature = FloatArray(COLUMNS)
    private val moisture = FloatArray(COLUMNS)
    private val bias = FloatArray(COLUMNS)
    private val roughness = FloatArray(COLUMNS)

    /**
     * The ground height of each column: the top of the land, not of whatever
     * stands on it. Filled by the shape pass and kept true by any pass that
     * raises or lowers the ground.
     */
    val surface = IntArray(COLUMNS)

    /** Spots marked for the game layer while this chunk was made. */
    val markers = ArrayList<WorldMarker>()

    private var climateReady = false
    private var surfaceReady = false
    private val scratch = ClimateSample()

    /** Fills every column's climate once, whichever pass asks first. */
    fun ensureClimate() {
        if (climateReady) return
        val map = services.biomes
        for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
            map.sample(originX + lx, originY + ly, scratch)
            val i = column(lx, ly)
            biome[i] = scratch.biome
            temperature[i] = scratch.temperature
            moisture[i] = scratch.moisture
            bias[i] = scratch.bias
            roughness[i] = scratch.roughness
        }
        climateReady = true
    }

    /** Fills every column's ground height from [field], once. */
    fun fillSurface(field: HeightField) {
        ensureClimate()
        for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
            val i = column(lx, ly)
            surface[i] = field.heightAt(originX + lx, originY + ly, climateAt(lx, ly, scratch))
        }
        surfaceReady = true
    }

    /** The ground heights, from the published height field if no shape pass filled them. */
    fun ensureSurface() {
        if (!surfaceReady) fillSurface(services.height)
    }

    /** The climate of a column, copied into [out]. */
    fun climateAt(lx: Int, ly: Int, out: ClimateSample): ClimateSample {
        ensureClimate()
        val i = column(lx, ly)
        out.biome = biome[i]!!
        out.temperature = temperature[i]
        out.moisture = moisture[i]
        out.bias = bias[i]
        out.roughness = roughness[i]
        return out
    }

    fun biomeAt(lx: Int, ly: Int): BiomeDefinition {
        ensureClimate()
        return biome[column(lx, ly)]!!
    }

    fun surfaceAt(lx: Int, ly: Int): Int {
        ensureSurface()
        return surface[column(lx, ly)]
    }

    /** After a pass opened the ground of a column, finds where the ground now is. */
    fun lowerSurface(lx: Int, ly: Int) {
        val i = column(lx, ly)
        var z = surface[i]
        while (z > 0 && chunk.blockAt(lx, ly, z) == BlockRegistry.AIR_INDEX) z--
        surface[i] = z
    }

    /** Sets a column's ground height after a pass levelled it. */
    fun setSurface(lx: Int, ly: Int, z: Int) {
        ensureSurface()
        surface[column(lx, ly)] = z
    }

    fun containsWorld(worldX: Int, worldY: Int): Boolean =
        worldX - originX in 0 until Chunk.SIZE && worldY - originY in 0 until Chunk.SIZE

    /** Reads a block by world coordinates; air outside this chunk. */
    fun get(worldX: Int, worldY: Int, z: Int): Int = chunk.blockAt(worldX - originX, worldY - originY, z)

    /** Writes a block by world coordinates; anything outside this chunk is dropped. */
    fun set(worldX: Int, worldY: Int, z: Int, index: Int) {
        chunk.setBlock(worldX - originX, worldY - originY, z, index)
    }

    companion object {
        const val COLUMNS = Chunk.SIZE * Chunk.SIZE

        fun column(lx: Int, ly: Int): Int = ly * Chunk.SIZE + lx
    }
}
