package com.stratum.engine.worldgen

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.engine.settlement.SettlementPlanner
import com.stratum.engine.settlement.SettlementStamp

/**
 * Dungeons, ruins and shrines from the packs' structure templates.
 *
 * Option: `only`, a comma-separated list of template ids, for a pipeline that
 * wants some structures in one pass and others in another.
 */
internal object StructuresPass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val only = setup.options.string("only", "").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        val templates = setup.context.structures.filter { only.isEmpty() || it.id in only }
        val planner = StructurePlanner(setup.services, templates)
        setup.services.structures = planner
        val palettes = java.util.concurrent.ConcurrentHashMap<Pair<String, BlockIndex>, IntArray>()
        return WorldgenPass { chunk ->
            if (templates.isEmpty()) return@WorldgenPass
            chunk.ensureSurface()
            val x1 = chunk.originX + Chunk.SIZE - 1
            val y1 = chunk.originY + Chunk.SIZE - 1
            planner.layoutsTouching(chunk.originX, chunk.originY, x1, y1).forEach { layout ->
                val palette = palettes.getOrPut(layout.template.id to chunk.blocks) { TemplateBlocks(layout.template).resolve(chunk.blocks) }
                layout.draw(chunk, palette)
                layout.markers.filterTo(chunk.markers) { chunk.containsWorld(it.x, it.y) }
            }
        }
    }
}

/**
 * Towns, built by the settlement layer as one stage of the pipeline rather
 * than laid over it afterwards, so a pack can order them among the other
 * passes. A world whose pipeline has this pass is its own town atlas, and the
 * session does not lay a second set of towns over it.
 */
internal object SettlementsPass : PassFactory {
    override fun create(setup: PassSetup): WorldgenPass {
        val services = setup.services
        val rules = setup.config.rules
        val recipes = setup.context.settlements
        if (recipes.isEmpty() || rules.townDensity <= 0f) return WorldgenPass { }
        val planner = SettlementPlanner(
            seed = services.seed,
            recipes = recipes,
            biomeAt = { x, y -> services.biomes.biomeAt(x, y).id },
            groundAt = services::surfaceAt,
            startingTown = rules.startInTown,
            density = rules.townDensity,
            welcoming = setup.context.welcoming,
        )
        services.towns = planner
        return WorldgenPass { chunk ->
            val centreX = chunk.originX + Chunk.SIZE / 2
            val centreY = chunk.originY + Chunk.SIZE / 2
            planner.settlementsNear(centreX, centreY, Chunk.SIZE).forEach { SettlementStamp.stamp(chunk.chunk, it, chunk.registry) }
        }
    }
}

/**
 * Where monsters wait underground: cave floors with room to stand, marked
 * for the game layer to people.
 *
 * Options: `perChunk` (markers tried per chunk, 2 by default) and `refId`
 * (named on every marker; empty lets the region decide).
 */
internal object SpawnsPass : PassFactory {

    private const val SPAWN_SALT = 12_001

    override fun create(setup: PassSetup): WorldgenPass {
        val perChunk = setup.options.float("perChunk", 2f)
        val ref = setup.options.string("refId", "").takeIf(String::isNotEmpty)
        val seed = setup.services.seed
        return WorldgenPass { chunk ->
            chunk.ensureSurface()
            val tries = perChunk.toInt() + if (PositionalRandom.floatAt(seed, chunk.pos.x, chunk.pos.y, SPAWN_SALT) < perChunk - perChunk.toInt()) 1 else 0
            repeat(tries) { k ->
                val lx = PositionalRandom.intAt(seed, chunk.pos.x, chunk.pos.y, SPAWN_SALT + 1 + k * 3, Chunk.SIZE)
                val ly = PositionalRandom.intAt(seed, chunk.pos.x, chunk.pos.y, SPAWN_SALT + 2 + k * 3, Chunk.SIZE)
                val floor = caveFloor(chunk, lx, ly) ?: return@repeat
                chunk.markers += WorldMarker(WorldMarkerKind.ENEMY_SPAWN, chunk.originX + lx, chunk.originY + ly, floor + 1, "stratum:spawns", ref)
            }
        }
    }

    /** The lowest cave floor in a column with two blocks of air over it, or null. */
    private fun caveFloor(chunk: ChunkContext, lx: Int, ly: Int): Int? {
        val top = chunk.surfaceAt(lx, ly) - 3
        for (z in 1 until top) {
            if (!chunk.blocks.isSolid(chunk.chunk.blockAt(lx, ly, z))) continue
            if (chunk.chunk.blockAt(lx, ly, z + 1) == BlockRegistry.AIR_INDEX && chunk.chunk.blockAt(lx, ly, z + 2) == BlockRegistry.AIR_INDEX) return z
        }
        return null
    }
}
