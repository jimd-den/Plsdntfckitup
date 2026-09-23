package com.stratum.engine.world

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.Landmark
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.TerrainGeneratorFactory
import com.stratum.core.domain.world.TerrainGeneratorRegistry
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig

/**
 * Builds terrain in passes: pick a biome, raise a surface, fill the column,
 * carve caves, seed deposits, scatter decoration.
 *
 * Every pass reads only the chunk's own coordinates, so chunks can be generated
 * in any order, in parallel, and regenerate identically. No pass may look at a
 * neighbouring chunk -- that is the rule that keeps streaming seam-free.
 */
class LayeredTerrainGenerator(
    private val config: WorldConfig,
    private val biomes: List<BiomeDefinition>,
    /** The shape of the landscape, as data. See [TerrainRecipe]. */
    private val recipe: TerrainRecipe = TerrainRecipe(),
) : TerrainGenerator, BiomeSource {

    init {
        require(biomes.isNotEmpty()) { "Terrain generation needs at least one biome" }
    }

    /** One noise field per elevation octave, offset so they do not line up. */
    private val elevationNoise = recipe.elevation.map { ValueNoise(config.seed + it.seedOffset) }

    private val heightNoise = ValueNoise(config.seed)
    private val biomeNoise = ValueNoise(config.seed * 31 + 7)
    private val caveNoise = ValueNoise(config.seed * 17 + 13)

    /** Where things grow thickly and where they do not. See [TerrainRecipe.scatterClustering]. */
    private val groveNoise = ValueNoise(config.seed * 53 + 29)

    /** Paths follow one contour of this field. See [pathDistance]. */
    private val pathNoise = ValueNoise(config.seed * 71 + 3)

    /** Landmark sites by grid cell; [NO_SITE] where a cell has none. Filled lazily, safe across threads. */
    private val sites = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    /** A landmark placed in the world, with the height its pad is levelled to. */
    private class Site(val x: Int, val y: Int, val z: Int, val landmark: Landmark)

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        val chunk = Chunk(pos)
        val bedrockIndex = registry.indexOf(BlockType.BEDROCK.id)

        for (localY in 0 until Chunk.SIZE) {
            for (localX in 0 until Chunk.SIZE) {
                val worldX = pos.originX + localX
                val worldY = pos.originY + localY
                val biome = biomeAt(worldX, worldY)
                val site = siteAround(worldX, worldY)
                val fromSite = site?.let { distance(worldX - it.x, worldY - it.y) }
                // A landmark stands on level ground: its pad and one ring past
                // it take the centre's height.
                val surfaceZ = if (site != null && fromSite!! <= site.landmark.floorRadius + 1f) site.z
                else surfaceHeight(worldX, worldY, biome)

                chunk.setBlock(localX, localY, 0, bedrockIndex)
                fillColumn(chunk, registry, localX, localY, worldX, worldY, surfaceZ, biome)
                carveCaves(chunk, registry, localX, localY, worldX, worldY, surfaceZ)
                placeDeposits(chunk, registry, localX, localY, worldX, worldY, surfaceZ, biome, bedrockIndex)

                // Paths: trodden into the surface, and kept clear either side.
                val path = biome.composition.pathBlockId?.let(registry::indexOrNull)
                val fromPath = if (path != null) pathDistance(worldX, worldY) else Float.MAX_VALUE
                val halfWidth = biome.composition.pathWidth / 2f
                if (path != null && fromPath <= halfWidth && (site == null || fromSite!! > site.landmark.floorRadius)) {
                    chunk.setBlock(localX, localY, surfaceZ, path)
                }
                val cleared = fromPath <= halfWidth + PATH_VERGE || (site != null && fromSite!! <= site.landmark.clearRadius)
                if (!cleared) scatterDecoration(chunk, registry, localX, localY, worldX, worldY, biome)
                if (site != null) placeLandmark(chunk, registry, localX, localY, worldX, worldY, surfaceZ, site, fromSite!!)
            }
        }
        return chunk
    }

    /**
     * Biomes are chosen by a low-frequency noise field so regions are large and
     * contiguous rather than a per-column lottery.
     */
    override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition {
        if (biomes.size == 1) return biomes.first()
        val sample = biomeNoise.fractal(worldX * BIOME_SCALE, worldY * BIOME_SCALE, octaves = 2)
        val index = (sample * biomes.size).toInt().coerceIn(0, biomes.size - 1)
        return biomes[index]
    }

    fun surfaceHeight(worldX: Int, worldY: Int, biome: BiomeDefinition): Int {
        val signed = elevationAt(worldX, worldY)
        // Bias and roughness are blended across borders rather than taken from
        // this column's biome alone. Taken raw, a border between a high region
        // and a low one was a sheer wall as tall as the difference in their
        // biases -- nine blocks between the thunder peaks and the catacombs --
        // which no player could climb and no monster could path over.
        //
        // The blend is smooth, not merely averaged: biases are read at the
        // nodes of a coarse world-aligned grid, each node averaged with its
        // neighbours, and interpolated between nodes. A plain moving average
        // sampled on a grid jumped by most of a block whenever a row of its
        // samples crossed a border together, and those jumps were exactly the
        // two-block steps the blend was meant to remove.
        //
        // Applied as an offset to the biome the caller named, so asking for a
        // particular biome's surface still gets that biome's height.
        val here = biomeAt(worldX, worldY)
        val (blendedBias, blendedRoughness) = blendedAt(worldX, worldY)
        val bias = biome.heightBias + (blendedBias - here.heightBias)
        val roughness = (biome.roughness + (blendedRoughness - here.roughness)).coerceAtLeast(0f)
        val variation = config.surfaceVariation * roughness
        val raw = config.seaLevel + kotlin.math.round(bias).toInt() + (signed * variation).toInt()

        // Terraced before clamping, so the plateaus stay aligned across biomes
        // with different height biases: a ledge that steps by two in one region
        // and by one in the next reads as a bug rather than as a landscape.
        val terraced = recipe.terraced(raw)
        return terraced.coerceIn(2, Chunk.HEIGHT - TOP_MARGIN)
    }

    /** Height bias and roughness, smoothly blended across biome borders. */
    private fun blendedAt(worldX: Int, worldY: Int): Pair<Float, Float> {
        val gx = Math.floorDiv(worldX, BLEND_GRID)
        val gy = Math.floorDiv(worldY, BLEND_GRID)
        val tx = (worldX - gx * BLEND_GRID).toFloat() / BLEND_GRID
        val ty = (worldY - gy * BLEND_GRID).toFloat() / BLEND_GRID
        val n00 = node(gx, gy); val n10 = node(gx + 1, gy)
        val n01 = node(gx, gy + 1); val n11 = node(gx + 1, gy + 1)
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        // Smoothstepped weights, so the slope eases in and out of a border
        // instead of kinking at every grid line.
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val bias = lerp(lerp(n00.first, n10.first, sx), lerp(n01.first, n11.first, sx), sy)
        val rough = lerp(lerp(n00.second, n10.second, sx), lerp(n01.second, n11.second, sx), sy)
        return bias to rough
    }

    /** One grid node: the biome bias and roughness around it, averaged. */
    private fun node(gx: Int, gy: Int): Pair<Float, Float> {
        var bias = 0f
        var rough = 0f
        for (j in -1..1) for (i in -1..1) {
            val b = biomeAt((gx + i) * BLEND_GRID, (gy + j) * BLEND_GRID)
            bias += b.heightBias
            rough += b.roughness
        }
        return bias / 9f to rough / 9f
    }

    /**
     * Summed elevation octaves in -1..1.
     *
     * An empty recipe is a flat world, which is a legitimate thing to ask for
     * rather than a misconfiguration.
     */
    private fun elevationAt(worldX: Int, worldY: Int): Float {
        if (recipe.elevation.isEmpty()) return 0f

        var total = 0f
        var weight = 0f
        recipe.elevation.forEachIndexed { index, layer ->
            val noise = elevationNoise[index]
            val sample = noise.fractal(worldX * layer.scale, worldY * layer.scale, octaves = 2)
            // Octaves sum around their midpoint, so a raw sample only ever
            // spends a fraction of the height budget and the world comes out a
            // plain. Re-centre and stretch before weighting.
            total += ((sample - 0.5f) * 2f) * layer.amplitude
            weight += layer.amplitude
        }
        if (weight <= 0f) return 0f
        return (total / weight * TERRAIN_GAIN).coerceIn(-1f, 1f)
    }

    private fun fillColumn(
        chunk: Chunk,
        registry: BlockRegistry,
        localX: Int,
        localY: Int,
        worldX: Int,
        worldY: Int,
        surfaceZ: Int,
        biome: BiomeDefinition,
    ) {
        val filler = registry.indexOf(biome.bedrockFillerBlockId)
        val surface = registry.indexOf(biome.surfaceBlockId)

        if (recipe.strata.isEmpty()) {
            val subsurface = registry.indexOf(biome.subsurfaceBlockId)
            for (z in 1 until surfaceZ) {
                val index = if (z >= surfaceZ - SUBSURFACE_DEPTH) subsurface else filler
                chunk.setBlock(localX, localY, z, index)
            }
            chunk.setBlock(localX, localY, surfaceZ, surface)
            return
        }

        // Recipe strata run top down from just under the surface. Anything the
        // bands do not reach is filler, so a short recipe still makes a solid
        // world rather than a hollow one.
        for (z in 1 until surfaceZ) {
            chunk.setBlock(localX, localY, z, filler)
        }
        var z = surfaceZ - 1
        recipe.strata.forEach { stratum ->
            val index = registry.indexOrNull(stratum.blockId) ?: return@forEach
            repeat(stratum.thickness) {
                if (z >= 1) {
                    chunk.setBlock(localX, localY, z, index)
                    z--
                }
            }
        }
        chunk.setBlock(localX, localY, surfaceZ, surface)
    }

    /**
     * Caves are a 3D noise threshold, kept below the surface so the terrain does
     * not dissolve into holes at ground level.
     */
    private fun carveCaves(
        chunk: Chunk,
        registry: BlockRegistry,
        localX: Int,
        localY: Int,
        worldX: Int,
        worldY: Int,
        surfaceZ: Int,
    ) {
        val ceiling = surfaceZ - CAVE_HEADROOM
        if (ceiling <= CAVE_FLOOR) return
        for (z in CAVE_FLOOR..ceiling) {
            val density = caveNoise.at(worldX * CAVE_SCALE, worldY * CAVE_SCALE, z * CAVE_SCALE_Z)
            if (density > config.caveDensity) {
                chunk.setBlock(localX, localY, z, BlockRegistry.AIR_INDEX)
            }
        }
    }

    private fun placeDeposits(
        chunk: Chunk,
        registry: BlockRegistry,
        localX: Int,
        localY: Int,
        worldX: Int,
        worldY: Int,
        surfaceZ: Int,
        biome: BiomeDefinition,
        bedrockIndex: Int,
    ) {
        biome.deposits.forEachIndexed { ruleIndex, rule ->
            val index = registry.indexOf(rule.blockId)
            val top = minOf(rule.maxZ, surfaceZ - 1)
            if (rule.minZ > top) return@forEachIndexed

            val roll = PositionalRandom.floatAt(config.seed, worldX, worldY, DEPOSIT_SALT + ruleIndex)
            if (roll >= rule.chance * config.oreRichness) return@forEachIndexed

            // The roll that selected this column also picks where in the band the
            // blob sits, so a deposit is a vein rather than a single cell.
            val anchor = rule.minZ + ((roll / rule.chance.coerceAtLeast(1e-6f)) * (top - rule.minZ + 1)).toInt()
            val half = (rule.clusterSize / 2).coerceAtLeast(0)
            for (z in (anchor - half)..(anchor + half)) {
                if (z <= 0 || z > top) continue
                val existing = chunk.blockAt(localX, localY, z)
                if (existing == BlockRegistry.AIR_INDEX || existing == bedrockIndex) continue
                chunk.setBlock(localX, localY, z, index)
            }
        }
    }

    /**
     * Roughly how many blocks this column is from the nearest path.
     *
     * Paths are the 0.5 contour of a slow noise field: a contour of a smooth
     * field is a winding line that never ends abruptly and never crosses
     * itself, which is most of what a road needs to be. The field's value
     * divided by its slope is the distance to the contour, so a path keeps its
     * width through both gentle and tight bends.
     */
    private fun pathDistance(worldX: Int, worldY: Int): Float {
        fun at(x: Int, y: Int) = pathNoise.fractal(x * PATH_SCALE, y * PATH_SCALE, octaves = 2)
        val here = at(worldX, worldY) - 0.5f
        val gx = (at(worldX + 1, worldY) - at(worldX - 1, worldY)) / 2f
        val gy = (at(worldX, worldY + 1) - at(worldX, worldY - 1)) / 2f
        val slope = kotlin.math.sqrt(gx * gx + gy * gy).coerceAtLeast(MIN_PATH_SLOPE)
        return kotlin.math.abs(here) / slope
    }

    /**
     * The landmark whose clearing holds this column, if any.
     *
     * Sites sit one per cell of a fixed world grid, jittered but never nearer
     * a cell's edge than the largest clearing, so a column can only ever be in
     * its own cell's clearing: one lookup, and chunks stay independent.
     */
    private fun siteAround(worldX: Int, worldY: Int): Site? {
        val cx = Math.floorDiv(worldX, SITE_GRID)
        val cy = Math.floorDiv(worldY, SITE_GRID)
        val site = sites.getOrPut((cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)) { siteIn(cx, cy) ?: NO_SITE } as? Site
            ?: return null
        return site.takeIf { distance(worldX - it.x, worldY - it.y) <= it.landmark.clearRadius }
    }

    private fun siteIn(cx: Int, cy: Int): Site? {
        val margin = Landmark.MAX_CLEAR_RADIUS
        val span = SITE_GRID - 2 * margin
        // Several candidate spots in the cell; the one nearest a path wins, so
        // roads run through set pieces the way they do in any designed world,
        // instead of passing a shrine by twenty blocks.
        val (x, y) = (0 until SITE_CANDIDATES).map { k ->
            cx * SITE_GRID + margin + PositionalRandom.intAt(config.seed, cx, cy, SITE_SALT + 1 + k * 2, span) to
                cy * SITE_GRID + margin + PositionalRandom.intAt(config.seed, cx, cy, SITE_SALT + 2 + k * 2, span)
        }.minBy { (x, y) -> if (biomeAt(x, y).composition.pathBlockId != null) pathDistance(x, y) else 0f }
        val biome = biomeAt(x, y)
        val landmark = biome.composition.landmark ?: return null
        if (PositionalRandom.floatAt(config.seed, cx, cy, SITE_SALT) >= landmark.chance) return null
        // Only in the open. A shrine half-buried in a thicket is not a place,
        // it is clutter.
        val density = recipe.scatterDensity(
            groveNoise.fractal(x * recipe.scatterClusterScale, y * recipe.scatterClusterScale, octaves = 2),
        )
        if (density > CLEARING_DENSITY) return null
        return Site(x, y, surfaceHeight(x, y, biome), landmark)
    }

    private fun placeLandmark(
        chunk: Chunk, registry: BlockRegistry, localX: Int, localY: Int, worldX: Int, worldY: Int,
        surfaceZ: Int, site: Site, fromSite: Float,
    ) {
        val z = surfaceZ + 1
        if (z >= Chunk.HEIGHT) return
        val landmark = site.landmark
        if (worldX == site.x && worldY == site.y) {
            registry.indexOrNull(landmark.centreBlockId)?.let { chunk.setBlock(localX, localY, z, it) }
            return
        }
        landmark.ringBlockId?.let(registry::indexOrNull)?.let { ring ->
            for (i in 0 until landmark.ringCount) {
                // Starting on a world axis. The camera looks along a world
                // diagonal, so a ring of four set on the axes sits either side
                // of the centre on screen instead of one standing in front of it.
                val angle = i * 2.0 * Math.PI / landmark.ringCount
                val rx = site.x + kotlin.math.round(kotlin.math.cos(angle) * landmark.ringRadius).toInt()
                val ry = site.y + kotlin.math.round(kotlin.math.sin(angle) * landmark.ringRadius).toInt()
                if (worldX == rx && worldY == ry) {
                    chunk.setBlock(localX, localY, z, ring)
                    return
                }
            }
        }
        if (fromSite <= landmark.floorRadius) {
            landmark.floorBlockId?.let(registry::indexOrNull)?.let { chunk.setBlock(localX, localY, z, it) }
        }
    }

    private fun distance(dx: Int, dy: Int): Float = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())

    private fun scatterDecoration(
        chunk: Chunk,
        registry: BlockRegistry,
        localX: Int,
        localY: Int,
        worldX: Int,
        worldY: Int,
        biome: BiomeDefinition,
    ) {
        val surfaceZ = chunk.surfaceAt(localX, localY)
        if (surfaceZ < 0) return

        // One sample for the whole column, not one per rule: undergrowth and
        // trees thin out together, which is what makes a clearing read as a
        // clearing rather than as a gap in one species.
        val density = recipe.scatterDensity(
            groveNoise.fractal(
                worldX * recipe.scatterClusterScale,
                worldY * recipe.scatterClusterScale,
                octaves = 2,
            ),
        )

        biome.scatter.forEachIndexed { ruleIndex, rule ->
            val roll = PositionalRandom.floatAt(config.seed, worldX, worldY, SCATTER_SALT + ruleIndex)
            if (roll >= rule.chance * density) return@forEachIndexed
            val index = registry.indexOf(rule.blockId)
            val cap = rule.capBlockId?.let(registry::indexOrNull)
            for (offset in 1..rule.height) {
                val z = surfaceZ + offset
                if (z >= Chunk.HEIGHT) break
                val isTop = offset == rule.height
                chunk.setBlock(localX, localY, z, if (isTop && cap != null) cap else index)
            }
        }
    }

    private companion object {
        /** Landmark site grid, in blocks: about three screens between set pieces. */
        const val SITE_GRID = 44
        const val SITE_SALT = 9_001
        const val SITE_CANDIDATES = 12
        /** Grove density above which a site is too overgrown to hold a landmark. */
        const val CLEARING_DENSITY = 0.9f
        val NO_SITE = Any()

        const val PATH_SCALE = 0.02f
        /**
         * Floor on the path field's slope. Where the field is nearly flat the
         * distance estimate collapses and a path balloons into a clearing of
         * bare earth; at about the field's typical slope it stays a road.
         */
        const val MIN_PATH_SLOPE = 0.012f
        /** Scenery kept back from a path's edge, in blocks. */
        const val PATH_VERGE = 1.5f

        /**
         * Spacing of the biome-blend grid, in blocks. A border's height change
         * is spread over about two of these, which keeps even the largest bias
         * difference a pack ships under one block per step.
         */
        const val BLEND_GRID = 10

        const val TERRAIN_SCALE = 0.018f
        /**
         * Widens the noise's usable range. Kept low deliberately: this is a
         * game about putting buildings on the ground, and a landscape of
         * one-block steps is both hard to walk and impossible to build on.
         * Relief comes from biome height bias and roughness instead, which
         * varies between regions rather than between adjacent columns.
         */
        const val TERRAIN_GAIN = 1.15f
        const val BIOME_SCALE = 0.008f
        const val CAVE_SCALE = 0.12f
        const val CAVE_SCALE_Z = 0.22f
        const val SUBSURFACE_DEPTH = 3
        const val CAVE_FLOOR = 2
        const val CAVE_HEADROOM = 3
        const val TOP_MARGIN = 8
        const val DEPOSIT_SALT = 1000
        const val SCATTER_SALT = 2000
    }
}

/**
 * The generators this build ships with.
 *
 * A new algorithm needs nothing from the engine but an id and a factory:
 *
 * ```
 * StratumTerrain.registry.register("mypack:caves") { ctx -> MyCaveGenerator(ctx) }
 * ```
 *
 * A pack then names `mypack:caves` in its recipe and gets it.
 */
object StratumTerrain {

    /** Shared, so registering once makes a generator available to every session. */
    val registry: TerrainGeneratorRegistry = TerrainGeneratorRegistry().register(
        TerrainRecipe.LAYERED,
        TerrainGeneratorFactory { context ->
            LayeredTerrainGenerator(context.config, context.biomes, context.recipe)
        },
    )

    fun create(context: TerrainContext): TerrainGenerator = registry.create(context)
}
