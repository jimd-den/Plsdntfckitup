package com.stratum.engine.settlement

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainGenerator
import java.util.concurrent.ConcurrentHashMap

/**
 * Towns on top of any terrain: wraps a generator, lets it build the land,
 * then stamps every town that overlaps the chunk.
 *
 * A layer rather than a feature of one generator, so a pack that ships its
 * own terrain algorithm still gets cities, and a plugin that ships only
 * settlement recipes adds towns to whatever world it is loaded into.
 */
open class SettlementTerrain internal constructor(
    private val base: TerrainGenerator,
    /** The blocks the world is made of, needed up front to measure the land a town stands on. */
    private val registry: BlockRegistry,
    seed: Long,
    recipes: List<SettlementRecipe>,
    layouts: SettlementLayouts,
    startingTown: Boolean,
    density: Float,
    welcoming: (SettlementRecipe) -> Boolean,
) : TerrainGenerator, SettlementAtlas {

    private val natural = ConcurrentHashMap<ChunkPos, Chunk>()

    private val planner = SettlementPlanner(
        seed = seed,
        recipes = recipes,
        biomeAt = { x, y -> (base as? BiomeSource)?.biomeAt(x, y)?.id },
        groundAt = ::naturalSurface,
        layouts = layouts,
        startingTown = startingTown,
        density = density,
        welcoming = welcoming,
    )

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        val chunk = base.generate(pos, registry)
        val centreX = pos.originX + Chunk.SIZE / 2
        val centreY = pos.originY + Chunk.SIZE / 2
        planner.settlementsNear(centreX, centreY, Chunk.SIZE).forEach { SettlementStamp.stamp(chunk, it, registry) }
        return chunk
    }

    override fun settlementsNear(x: Int, y: Int, radius: Int): List<SettlementPlan> = planner.settlementsNear(x, y, radius)

    /** The land's own height at a column, before any town, from a chunk the base builds and this keeps. */
    private fun naturalSurface(x: Int, y: Int): Int {
        val pos = ChunkPos.containing(x, y)
        val chunk = natural.getOrPut(pos) { base.generate(pos, registry) }
        if (natural.size > NATURAL_CACHE) natural.clear()
        return chunk.surfaceAt(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE))
    }

    /** The same layer over a generator that knows its regions, passing that knowledge through. */
    private class WithBiomes(
        private val biomes: BiomeSource,
        base: TerrainGenerator,
        registry: BlockRegistry,
        seed: Long,
        recipes: List<SettlementRecipe>,
        layouts: SettlementLayouts,
        startingTown: Boolean,
        density: Float,
        welcoming: (SettlementRecipe) -> Boolean,
    ) : SettlementTerrain(base, registry, seed, recipes, layouts, startingTown, density, welcoming), BiomeSource {
        override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition = biomes.biomeAt(worldX, worldY)
    }

    companion object {
        /**
         * [base] with towns from [recipes] laid over it, or [base] itself when
         * there are none. The result is a [BiomeSource] exactly when [base] is.
         */
        fun over(
            base: TerrainGenerator,
            registry: BlockRegistry,
            seed: Long,
            recipes: List<SettlementRecipe>,
            layouts: SettlementLayouts = SettlementLayouts.standard,
            startingTown: Boolean = true,
            density: Float = 1f,
            /** Which recipes the player may safely begin in; see [SettlementPlanner]. */
            welcoming: (SettlementRecipe) -> Boolean = { it.garrison.isEmpty() },
        ): TerrainGenerator = when {
            recipes.isEmpty() || density <= 0f -> base
            base is BiomeSource -> WithBiomes(base, base, registry, seed, recipes, layouts, startingTown, density, welcoming)
            else -> SettlementTerrain(base, registry, seed, recipes, layouts, startingTown, density, welcoming)
        }

        private const val NATURAL_CACHE = 64
    }
}
