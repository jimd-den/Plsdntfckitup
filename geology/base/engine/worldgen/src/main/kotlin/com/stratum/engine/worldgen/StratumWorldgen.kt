package com.stratum.engine.worldgen

import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainGeneratorFactory
import com.stratum.core.domain.world.TerrainGeneratorRegistry
import com.stratum.core.domain.world.TerrainRecipe

/**
 * The staged generator's built-in passes, carvers and presets.
 *
 * A custom pass is one line and a pack naming it:
 *
 * ```
 * StratumWorldgen.passes.register("mypack:lava_lakes") { setup -> MyLavaLakes(setup) }
 * ```
 *
 * Shared, like the generator registry, so registering once makes a pass or
 * carver available to every world built afterwards.
 */
object StratumWorldgen {

    const val CLIMATE = "stratum:climate"
    const val HILLS = "stratum:hills"
    const val ISLAND_SHAPE = "stratum:island_shape"
    const val FLAT_SHAPE = "stratum:flat_shape"
    const val SURFACE = "stratum:surface"
    const val CARVERS = "stratum:carvers"
    const val LIQUIDS = "stratum:liquids"
    const val ORES = "stratum:ores"
    const val DECORATION = "stratum:decoration"
    const val TREES = "stratum:trees"
    const val STRUCTURES = "stratum:structures"
    const val SETTLEMENTS = "stratum:settlements"
    const val SPAWNS = "stratum:spawns"

    val carvers: CarverRegistry = CarverRegistry.standard()

    val passes: PassRegistry = PassRegistry()
        .register(CLIMATE, ClimatePass)
        .register(HILLS, HillsPass)
        .register(ISLAND_SHAPE, IslandsPass)
        .register(FLAT_SHAPE, FlatPass)
        .register(SURFACE, SurfacePass)
        .register(CARVERS, CarversPass(carvers))
        .register(LIQUIDS, LiquidsPass)
        .register(ORES, OresPass)
        .register(DECORATION, DecorationPass)
        .register(TREES, TreesPass)
        .register(STRUCTURES, StructuresPass)
        .register(SETTLEMENTS, SettlementsPass)
        .register(SPAWNS, SpawnsPass)

    private fun pass(id: String, vararg options: Pair<String, String>) = PassSpec(id, mapOf(*options))

    /**
     * Each preset's passes. Towns are not among them: the session lays towns
     * over any generator, as it always has; a pack that wants them ordered
     * among its passes lists [SETTLEMENTS] itself.
     */
    val presets: Map<String, List<PassSpec>> = mapOf(
        TerrainRecipe.OVERWORLD to listOf(
            pass(CLIMATE), pass(HILLS), pass(SURFACE), pass(CARVERS), pass(LIQUIDS), pass(ORES),
            pass(DECORATION), pass(TREES), pass(STRUCTURES), pass(SPAWNS),
        ),
        TerrainRecipe.ISLANDS to listOf(
            pass(CLIMATE), pass(ISLAND_SHAPE), pass(SURFACE), pass(CARVERS), pass(LIQUIDS), pass(ORES),
            pass(DECORATION), pass(TREES), pass(STRUCTURES), pass(SPAWNS),
        ),
        TerrainRecipe.CAVERNS to caverns(),
        TerrainRecipe.UNDERWORLD to caverns(),
        TerrainRecipe.FLAT_GENERATOR to listOf(
            pass(CLIMATE), pass(FLAT_SHAPE), pass(SURFACE), pass(DECORATION, "paths" to "false", "landmarks" to "false"),
            pass(STRUCTURES),
        ),
    )

    /** A thin crust high in the column, so almost all of it is underground to be layered and hollowed. */
    private fun caverns() = listOf(
        pass(CLIMATE), pass(HILLS, "base" to "38", "variation" to "3", "topMargin" to "4"), pass(SURFACE),
        pass(CARVERS, "defaults" to CarversPass.CAVERNS), pass(LIQUIDS), pass(ORES), pass(DECORATION),
        pass(TREES), pass(STRUCTURES), pass(SPAWNS, "perChunk" to "4"),
    )

    /** Builds the pipeline a recipe asks for: its own passes if it lists any, else its preset's. */
    fun factory(presetId: String) = TerrainGeneratorFactory { context ->
        val specs = context.recipe.passes.ifEmpty { presets.getValue(presetId) }
        PipelineTerrainGenerator.build(context, specs, passes)
    }

    /** Adds every preset to [registry]. */
    fun registerPresets(registry: TerrainGeneratorRegistry): TerrainGeneratorRegistry {
        presets.keys.forEach { registry.register(it, factory(it)) }
        return registry
    }
}
