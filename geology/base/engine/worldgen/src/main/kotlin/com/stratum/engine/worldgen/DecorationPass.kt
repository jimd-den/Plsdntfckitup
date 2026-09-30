package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk

/**
 * Paths, landmarks and scatter: what makes a region read as a place.
 *
 * Paths are trodden into the surface and kept clear either side; landmarks
 * stand on pads levelled to their centre, in clearings nothing grows in;
 * scatter clumps into groves and thins into clearings. Each column is decided
 * from the [Composition] alone, so decoration is seam-free by the same rule as
 * the terrain it stands on.
 *
 * Options: `paths`, `landmarks` and `scatter`, each true by default.
 */
internal object DecorationPass : PassFactory {

    private const val SCATTER_SALT = 2000
    private const val PATH_VERGE = 1.5f

    override fun create(setup: PassSetup): WorldgenPass {
        val composition = Composition(setup.services, setup.recipe)
        setup.services.composition = composition
        val paths = setup.options.boolean("paths", true)
        val landmarks = setup.options.boolean("landmarks", true)
        val scatter = setup.options.boolean("scatter", true)
        val seed = setup.services.seed
        return WorldgenPass { chunk ->
            chunk.ensureSurface()
            for (ly in 0 until Chunk.SIZE) for (lx in 0 until Chunk.SIZE) {
                val wx = chunk.originX + lx
                val wy = chunk.originY + ly
                val biome = chunk.biomeAt(lx, ly)
                val site = if (landmarks) composition.siteAround(wx, wy) else null
                val fromSite = site?.let { distance(wx - it.x, wy - it.y) } ?: Float.MAX_VALUE
                if (site != null && fromSite <= site.landmark.floorRadius + 1f) level(chunk, lx, ly, site.z, biome)
                val surfaceZ = chunk.surfaceAt(lx, ly)

                val pathBlock = if (paths) chunk.blocks.orNull(biome.composition.pathBlockId) else null
                val fromPath = if (pathBlock != null) composition.pathDistance(wx, wy) else Float.MAX_VALUE
                val halfWidth = biome.composition.pathWidth / 2f
                if (pathBlock != null && fromPath <= halfWidth && (site == null || fromSite > site.landmark.floorRadius)) {
                    chunk.chunk.setBlock(lx, ly, surfaceZ, pathBlock)
                }
                val cleared = fromPath <= halfWidth + PATH_VERGE || (site != null && fromSite <= site.landmark.clearRadius)
                if (scatter && !cleared) scatter(chunk, lx, ly, wx, wy, surfaceZ, biome, composition, seed)
                if (site != null) landmark(chunk, lx, ly, wx, wy, surfaceZ, site, fromSite)
            }
        }
    }

    /** Raises or lowers a column's ground to [z], keeping its surface block on top. */
    private fun level(chunk: ChunkContext, lx: Int, ly: Int, z: Int, biome: BiomeDefinition) {
        val current = chunk.surfaceAt(lx, ly)
        if (current == z) return
        val top = chunk.chunk.blockAt(lx, ly, current)
        if (z > current) {
            val fill = chunk.blocks.of(biome.subsurfaceBlockId)
            for (h in current until z) chunk.chunk.setBlock(lx, ly, h, fill)
        } else {
            for (h in z + 1..current) chunk.chunk.setBlock(lx, ly, h, BlockRegistry.AIR_INDEX)
        }
        chunk.chunk.setBlock(lx, ly, z, top)
        chunk.setSurface(lx, ly, z)
    }

    private fun scatter(
        chunk: ChunkContext, lx: Int, ly: Int, wx: Int, wy: Int, surfaceZ: Int,
        biome: BiomeDefinition, composition: Composition, seed: Long,
    ) {
        if (biome.scatter.isEmpty() || surfaceZ + 1 >= Chunk.HEIGHT) return
        // Nothing grows under water or where a cave broke the ground open.
        if (chunk.chunk.blockAt(lx, ly, surfaceZ + 1) != BlockRegistry.AIR_INDEX) return
        if (chunk.chunk.blockAt(lx, ly, surfaceZ) == BlockRegistry.AIR_INDEX) return
        // One sample for the whole column: undergrowth and trees thin out
        // together, which is what makes a clearing read as one.
        val density = composition.density(wx, wy)
        biome.scatter.forEachIndexed { ruleIndex, rule ->
            val roll = PositionalRandom.floatAt(seed, wx, wy, SCATTER_SALT + ruleIndex)
            if (roll >= rule.chance * density) return@forEachIndexed
            val index = chunk.blocks.of(rule.blockId)
            val cap = chunk.blocks.orNull(rule.capBlockId)
            for (offset in 1..rule.height) {
                val z = surfaceZ + offset
                if (z >= Chunk.HEIGHT) break
                chunk.chunk.setBlock(lx, ly, z, if (offset == rule.height && cap != null) cap else index)
            }
        }
    }

    private fun landmark(chunk: ChunkContext, lx: Int, ly: Int, wx: Int, wy: Int, surfaceZ: Int, site: Composition.Site, fromSite: Float) {
        val z = surfaceZ + 1
        if (z >= Chunk.HEIGHT) return
        val landmark = site.landmark
        if (wx == site.x && wy == site.y) {
            chunk.blocks.orNull(landmark.centreBlockId)?.let { chunk.chunk.setBlock(lx, ly, z, it) }
            return
        }
        chunk.blocks.orNull(landmark.ringBlockId)?.let { ring ->
            for (i in 0 until landmark.ringCount) {
                // Starting on a world axis, so a ring of four sits either side
                // of the centre on screen instead of one standing in front of it.
                val angle = i * 2.0 * Math.PI / landmark.ringCount
                val rx = site.x + kotlin.math.round(kotlin.math.cos(angle) * landmark.ringRadius).toInt()
                val ry = site.y + kotlin.math.round(kotlin.math.sin(angle) * landmark.ringRadius).toInt()
                if (wx == rx && wy == ry) {
                    chunk.chunk.setBlock(lx, ly, z, ring)
                    return
                }
            }
        }
        if (fromSite <= landmark.floorRadius) {
            chunk.blocks.orNull(landmark.floorBlockId)?.let { chunk.chunk.setBlock(lx, ly, z, it) }
        }
    }
}
