package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.world.TerrainContext
import com.stratum.engine.settlement.SettlementPlanner

/**
 * The climate at one column: which biome it is, and the height parameters
 * blended across the borders near it.
 *
 * Mutable and reused, because it is asked for every column of every chunk and
 * an object per column is exactly the garbage a phone's collector stutters on.
 */
class ClimateSample {
    lateinit var biome: BiomeDefinition
    var temperature: Float = 0f
    var moisture: Float = 0f
    /** The biome height bias, blended with its neighbours in climate. */
    var bias: Float = 0f
    /** The biome roughness, blended the same way. */
    var roughness: Float = 1f
}

/** Which biome a column belongs to, as a pure function of the world seed and the column. */
interface BiomeMap {
    fun sample(x: Int, y: Int, out: ClimateSample): ClimateSample

    fun biomeAt(x: Int, y: Int): BiomeDefinition = sample(x, y, ClimateSample()).biome

    /** The underground biome [depth] blocks below a column sampled as [climate], or null for none. */
    fun undergroundAt(climate: ClimateSample, depth: Int): BiomeDefinition? = null

    /** Whether any biome lives underground, so the surface pass can skip asking. */
    val hasUnderground: Boolean get() = false
}

/** The natural ground height of a column, before anything is carved or built. */
fun interface HeightField {
    fun heightAt(x: Int, y: Int, climate: ClimateSample): Int
}

/**
 * What passes share: the world's seed and data, and whatever earlier passes
 * published for later ones.
 *
 * Everything here answers for any column in the world without generating a
 * chunk, which is what lets a structure look at the ground a hundred blocks
 * away, or a tree at a root in the next chunk, and still come out the same in
 * whatever order chunks are made.
 */
class WorldServices(
    val context: TerrainContext,
    /** Counts noise samples when a test is measuring work; null otherwise. */
    val counter: SampleCounter? = null,
) {
    val seed: Long get() = context.config.seed

    /** A noise field of its own for each [salt], so passes never share a pattern by accident. */
    fun noise(salt: Long): ValueNoise = ValueNoise(seed * NOISE_MIX + salt, counter)

    private var publishedBiomes: BiomeMap? = null

    /** The biome map. Built from the recipe's climate unless a pass published another. */
    var biomes: BiomeMap
        get() = publishedBiomes ?: ClimateMap(this, context.recipe.climate).also { publishedBiomes = it }
        set(value) {
            publishedBiomes = value
        }

    /** The ground height. Level ground at sea level unless a shape pass published another. */
    var height: HeightField = HeightField { _, _, _ -> context.config.seaLevel }

    /** Paths, landmarks and clearings, once the decoration pass has published them. */
    var composition: Composition? = null

    /** Towns this generator builds itself, when the pipeline has a settlements pass. */
    var towns: SettlementPlanner? = null

    /** Structures, once the structures pass has published them, so later passes can keep clear. */
    var structures: StructurePlanner? = null

    /**
     * Where towns will stand, whether this pipeline builds them or the
     * session lays them over it afterwards.
     *
     * A town's site is a function of the seed, its cell and the biome at its
     * centre alone -- not of the ground height -- so a planner built here
     * finds exactly the sites the session's own will, and structures can keep
     * out of them without the two ever talking.
     */
    val townSites: SettlementPlanner? by lazy {
        towns ?: context.settlements.takeIf { it.isNotEmpty() && context.config.rules.townDensity > 0f }?.let { recipes ->
            SettlementPlanner(
                seed = seed,
                recipes = recipes,
                biomeAt = { x, y -> biomes.biomeAt(x, y).id },
                groundAt = { x, y -> surfaceAt(x, y) },
                startingTown = context.config.rules.startInTown,
                density = context.config.rules.townDensity,
                welcoming = context.welcoming,
            )
        }
    }

    /** The natural ground height at any column. */
    fun surfaceAt(x: Int, y: Int): Int = height.heightAt(x, y, biomes.sample(x, y, ClimateSample()))

    private companion object {
        const val NOISE_MIX = 0x5DEECE66DL
    }
}
