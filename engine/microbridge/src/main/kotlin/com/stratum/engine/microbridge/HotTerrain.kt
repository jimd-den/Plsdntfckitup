package com.stratum.engine.microbridge

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
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroTerrainSource
import com.stratum.engine.microvoxel.gen.StageInfo
import com.stratum.engine.microvoxel.gen.StageSpec

/**
 * A microvoxel world whose generator can be swapped while it is played.
 *
 * Shaping the land is a core part of play, not a setup screen: the World
 * panel moves a slider, and the hills rise around the player. This holds the
 * live [MicrovoxelTerrainGenerator] and answers for it -- terrain, regions,
 * markers, towns, microvoxels -- so the session, the renderers and the
 * encounter system keep one reference for the whole run while what stands
 * behind it changes.
 *
 * [retune] builds a complete new generator from the edited stage list *before*
 * swapping, so a bad value is an error message and the old land stays; there
 * is never a half-built world. Readers on other threads (the detail mesher)
 * see either the old generator or the new one, never a mix within one call.
 * The session then regenerates the chunks the player has not changed; edited
 * chunks keep what the player made of them.
 *
 * Everything that makes a world the same world stays put across a retune: the
 * seed, the packs, the sea level. Only [passes] changes, and it is what a
 * save keeps ([com.stratum.core.domain.world.WorldConfig.terrainPasses]).
 */
class HotTerrain(private val context: TerrainContext) : TerrainGenerator, BiomeSource, MarkedWorld, MicroTerrainSource, SettlementAtlas {

    @Volatile
    var current: MicrovoxelTerrainGenerator = MicrovoxelTerrainGenerator.create(context)
        private set

    @Volatile private var registry: BlockRegistry? = null

    /** Goes up by one on every successful [retune]; anything caching generated land compares it. */
    @Volatile var revision: Int = 0
        private set

    /** The stages running now, in order, with their options as the player set them. */
    val passes: List<StageSpec> get() = current.passes

    /**
     * Every stage the world could run, described for a panel: those running
     * first, in their order, then the rest in [MicrovoxelTerrainGenerator.ORDER].
     */
    fun catalogue(): List<StageInfo> {
        val registry = current.stages
        val running = passes.map { it.id }
        val others = (MicrovoxelTerrainGenerator.ORDER + registry.ids).distinct().filter { it !in running && it in registry.ids }
        return (running + others).mapNotNull { registry.describe(it) }
    }

    /** A new generator, built and checked, waiting to be [install]ed; see [prepare]. */
    sealed interface Prepared {
        class Ready internal constructor(internal val generator: MicrovoxelTerrainGenerator, internal val basedOn: Int) : Prepared
        class Refused(val reason: String) : Prepared
    }

    /**
     * Builds the generator [passes] describe, without touching the live one.
     * Safe on any thread, so the World panel builds on a worker and the game
     * keeps its frame rate; [install] then swaps it in on the game thread.
     */
    fun prepare(passes: List<StageSpec>): Prepared {
        val basedOn = revision
        return try {
            val recipe = context.recipe.copy(passes = passes.map { PassSpec(it.id, it.options) })
            val next = MicrovoxelTerrainGenerator.create(context.copy(recipe = recipe))
            // Build the fields and one chunk now, so a stage that throws throws here and not in the render thread.
            next.micro.generate(MicroChunkPos(0, 0, context.config.seaLevel * MicrovoxelTerrainGenerator.MICRO_PER_BLOCK / MicroChunk.SIZE))
            registry?.let(next::paletteFor)
            Prepared.Ready(next, basedOn)
        } catch (e: IllegalArgumentException) {
            Prepared.Refused(e.message ?: REFUSED)
        } catch (e: IllegalStateException) {
            Prepared.Refused(e.message ?: REFUSED)
        }
    }

    /**
     * Makes a [prepare]d generator the live one. Returns null when it is, or
     * why not: refused when prepared, or overtaken by another install since.
     */
    fun install(prepared: Prepared): String? = when (prepared) {
        is Prepared.Refused -> prepared.reason
        is Prepared.Ready -> if (prepared.basedOn != revision) "Overtaken by a newer change" else {
            registry?.let(prepared.generator::paletteFor)
            current = prepared.generator
            revision++
            null
        }
    }

    /**
     * Rebuilds the world from [passes], on this thread. Returns null when the
     * new generator is live, or what was wrong with the list -- an unknown
     * stage, a value out of range -- in which case nothing changed.
     */
    fun retune(passes: List<StageSpec>): String? = install(prepare(passes))

    /** [retune] with one stage's options changed, or the stage switched on with them. */
    fun retuneStage(id: String, options: Map<String, String>): String? =
        retune(MicrovoxelTerrainGenerator.withStage(passes, StageSpec(id, options)))

    /** [retune] without stage [id]. The land itself cannot be switched off. */
    fun dropStage(id: String): String? {
        if (id == com.stratum.engine.microvoxel.gen.TerrainStage.ID) return "The land itself cannot be switched off"
        return retune(passes.filter { it.id != id })
    }

    // ---- The live generator's answers --------------------------------------------------

    override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk {
        this.registry = registry
        return current.generate(pos, registry)
    }

    override fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition = current.biomeAt(worldX, worldY)

    override fun markersIn(pos: ChunkPos): List<WorldMarker> = current.markersIn(pos)

    override fun settlementsNear(x: Int, y: Int, radius: Int): List<SettlementPlan> =
        (current as? SettlementAtlas)?.settlementsNear(x, y, radius) ?: emptyList()

    override val palette: MaterialPalette get() = current.palette

    override fun microChunk(pos: MicroChunkPos): MicroChunk = current.microChunk(pos)

    override fun generatedBlock(x: Int, y: Int, z: Int): Int = current.generatedBlock(x, y, z)

    override fun materialForBlock(blockIndex: Int): Short = current.materialForBlock(blockIndex)

    override fun generatedChunk(x: Int, y: Int): ShortArray? = current.generatedChunk(x, y)

    private companion object {
        const val REFUSED = "Those settings do not make a world"
    }
}
