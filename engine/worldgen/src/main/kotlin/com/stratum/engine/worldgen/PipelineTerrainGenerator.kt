package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MarkedWorld
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldMarker
import java.util.concurrent.ConcurrentHashMap

/**
 * A world generator that is an ordered list of passes.
 *
 * Each chunk runs every pass in order over a fresh [ChunkContext]; nothing a
 * pass keeps between chunks may depend on which chunks came before, so the
 * world is the same whatever order it is streamed in. It knows its biomes, and
 * it can say where its structures and caves marked things for the game layer.
 */
open class PipelineTerrainGenerator internal constructor(
    /** The pass ids, in order, for display and tests. */
    val passIds: List<String>,
    private val passes: List<WorldgenPass>,
    private val services: WorldServices,
) : TerrainGenerator, BiomeSource, MarkedWorld {

    /** Resolved once per registry; a world keeps one registry for its lifetime. */
    @Volatile
    private var index: BlockIndex? = null

    /** Markers of recently generated chunks, so asking about a chunk just made costs nothing. */
    private val recentMarkers = ConcurrentHashMap<ChunkPos, List<WorldMarker>>()

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        val blocks = index?.takeIf { it.registry === registry } ?: BlockIndex(registry).also { index = it }
        val context = ChunkContext(pos, blocks, services)
        passes.forEach { it.apply(context) }
        if (recentMarkers.size > MARKER_CACHE) recentMarkers.clear()
        recentMarkers[pos] = context.markers.toList()
        return context.chunk
    }

    override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition = services.biomes.biomeAt(worldX, worldY)

    /**
     * What a chunk marks. Structure markers are known from their plans; cave
     * spawns need the rock, so a chunk this generator has not made recently is
     * made again -- identically -- to find them.
     */
    override fun markersIn(pos: ChunkPos): List<WorldMarker> {
        recentMarkers[pos]?.let { return it }
        val registry = index?.registry ?: return structureMarkersIn(pos)
        generate(pos, registry)
        return recentMarkers[pos].orEmpty()
    }

    private fun structureMarkersIn(pos: ChunkPos): List<WorldMarker> {
        val planner = services.structures ?: return emptyList()
        val x1 = pos.originX + Chunk.SIZE - 1
        val y1 = pos.originY + Chunk.SIZE - 1
        return planner.layoutsTouching(pos.originX, pos.originY, x1, y1).flatMap { layout ->
            layout.markers.filter { it.x in pos.originX..x1 && it.y in pos.originY..y1 }
        }
    }

    /** The natural ground height at a column, before anything is carved or built. */
    fun groundAt(x: Int, y: Int): Int = services.surfaceAt(x, y)

    /** The same generator when it builds its own towns, answering for them. */
    private class WithTowns(passIds: List<String>, passes: List<WorldgenPass>, services: WorldServices, private val towns: SettlementAtlas) :
        PipelineTerrainGenerator(passIds, passes, services), SettlementAtlas {
        override fun settlementsNear(x: Int, y: Int, radius: Int): List<SettlementPlan> = towns.settlementsNear(x, y, radius)
    }

    companion object {
        private const val MARKER_CACHE = 512

        /** Builds the pipeline [specs] describe, each pass from [passes], in order. */
        fun build(
            context: TerrainContext,
            specs: List<PassSpec>,
            passes: PassRegistry,
            counter: SampleCounter? = null,
        ): PipelineTerrainGenerator {
            require(context.biomes.isNotEmpty()) { "Terrain generation needs at least one biome" }
            require(specs.isNotEmpty()) { "A pipeline needs at least one pass" }
            val services = WorldServices(context, counter)
            val built = specs.map { passes.create(it, context, services) }
            val ids = specs.map(PassSpec::id)
            val towns = services.towns
            return if (towns != null) WithTowns(ids, built, services, towns) else PipelineTerrainGenerator(ids, built, services)
        }
    }
}
