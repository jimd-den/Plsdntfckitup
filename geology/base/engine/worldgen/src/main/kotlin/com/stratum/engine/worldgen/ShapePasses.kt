package com.stratum.engine.worldgen

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.TerrainRecipe
import kotlin.math.roundToInt

/**
 * The climate pass: every column's biome and blended height parameters.
 *
 * Options: `scale` and `blend` override the recipe's climate, for a pack that
 * wants larger or gentler regions without writing out every climate point.
 */
internal object ClimatePass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val base = setup.recipe.climate ?: com.stratum.core.domain.world.ClimateSpec()
        val spec = base.copy(
            scale = setup.options.float("scale", base.scale),
            blend = setup.options.float("blend", base.blend),
        )
        setup.services.biomes = ClimateMap(setup.services, spec)
        return WorldgenPass { it.ensureClimate() }
    }
}

/** A shape pass publishes its height field for later passes and fills each chunk's ground heights from it. */
private class ShapePass(private val field: HeightField) : WorldgenPass {
    override fun apply(chunk: ChunkContext) = chunk.fillSurface(field)
}

/**
 * Summed elevation octaves from the recipe, in -1..1.
 *
 * An empty recipe is a flat world, which is a legitimate thing to ask for
 * rather than a misconfiguration.
 */
internal class Elevation(services: WorldServices, private val recipe: TerrainRecipe) {
    private val noise = recipe.elevation.map { services.noise(ELEVATION_SALT + it.seedOffset) }

    fun at(x: Int, y: Int): Float {
        if (noise.isEmpty()) return 0f
        var total = 0f
        var weight = 0f
        for (i in noise.indices) {
            val layer = recipe.elevation[i]
            val sample = noise[i].fractal(x * layer.scale, y * layer.scale, octaves = 2)
            // Octaves sum around their midpoint; re-centred and stretched so
            // the recipe's height budget is actually spent.
            total += ((sample - 0.5f) * 2f) * layer.amplitude
            weight += layer.amplitude
        }
        return if (weight <= 0f) 0f else (total / weight * GAIN).coerceIn(-1f, 1f)
    }

    private companion object {
        const val ELEVATION_SALT = 101_000L

        /** The same gentle gain the layered generator uses: relief comes from biomes, not from noise. */
        const val GAIN = 1.15f
    }
}

/**
 * Rolling hills: the layered generator's surface, on climate-blended biomes.
 *
 * Options: `base` (the level the land rolls around; sea level by default),
 * `variation` (the height budget; the world's surface variation by default)
 * and `topMargin` (blocks always left free above the highest ground).
 */
internal object HillsPass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val elevation = Elevation(setup.services, setup.recipe)
        val base = setup.options.int("base", setup.config.seaLevel)
        val variation = setup.options.int("variation", setup.config.surfaceVariation)
        val ceiling = Chunk.HEIGHT - setup.options.int("topMargin", TOP_MARGIN)
        val recipe = setup.recipe
        val field = HeightField { x, y, climate ->
            val raw = base + climate.bias.roundToInt() + (elevation.at(x, y) * variation * climate.roughness.coerceAtLeast(0f)).toInt()
            // Terraced before clamping, so plateaus line up across biomes.
            recipe.terraced(raw).coerceIn(MIN_GROUND, ceiling)
        }
        setup.services.height = field
        return ShapePass(field)
    }
}

/**
 * An archipelago: land where a slow island field rises above a threshold,
 * sea floor elsewhere.
 *
 * Options: `landShare` (roughly the share of the world that is land),
 * `islandScale` (smaller is bigger islands), `oceanDepth`, `shoreSlope`
 * (blocks the ground rises per unit of the field past the shore) and
 * `variation` for the hills on land. The sea itself is a liquid rule: this
 * pass shapes the basin, the pack decides what fills it.
 */
internal object IslandsPass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val o = setup.options
        val elevation = Elevation(setup.services, setup.recipe)
        val islands = setup.services.noise(ISLAND_SALT)
        val sea = setup.config.seaLevel
        val landShare = o.float("landShare", 0.4f).coerceIn(0.05f, 0.95f)
        val scale = o.float("islandScale", 0.012f)
        val oceanDepth = o.int("oceanDepth", 6)
        val slope = o.float("shoreSlope", 40f)
        val variation = o.int("variation", setup.config.surfaceVariation)
        val ceiling = Chunk.HEIGHT - o.int("topMargin", TOP_MARGIN)
        val recipe = setup.recipe
        // The island field is stretched to use its whole range, so the share
        // of it above a threshold is close to the share asked for.
        val threshold = 1f - landShare
        val field = HeightField { x, y, climate ->
            val raw = ((islands.fractal(x * scale, y * scale, octaves = 3) - 0.5f) * 2.4f + 0.5f).coerceIn(0f, 1f)
            val past = raw - threshold
            val height = if (past < 0f) {
                sea - minOf(oceanDepth.toFloat(), -past * slope)
            } else {
                // Hills fade in from the beach, so every island has a shore to land on.
                val inland = (past * 4f).coerceAtMost(1f)
                sea + 1 + minOf(past * slope, variation.toFloat()) * 0.5f +
                    climate.bias * inland + elevation.at(x, y) * variation * climate.roughness * inland
            }
            recipe.terraced(height.roundToInt()).coerceIn(MIN_GROUND, ceiling)
        }
        setup.services.height = field
        return ShapePass(field)
    }

    private const val ISLAND_SALT = 42_424L
}

/** Level ground. Option: `level`, sea level by default. */
internal object FlatPass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val level = setup.options.int("level", setup.config.seaLevel).coerceIn(MIN_GROUND, Chunk.HEIGHT - 2)
        val field = HeightField { _, _, _ -> level }
        setup.services.height = field
        return ShapePass(field)
    }
}

private const val MIN_GROUND = 2
private const val TOP_MARGIN = 8
