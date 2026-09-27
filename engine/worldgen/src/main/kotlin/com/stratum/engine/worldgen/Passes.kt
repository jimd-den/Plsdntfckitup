package com.stratum.engine.worldgen

import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig

/**
 * One stage of generation, run over a chunk.
 *
 * The rule every pass keeps: what it writes is a function of the world seed
 * and world coordinates, never of which chunks were generated before. A
 * feature that crosses a chunk border -- a tunnel, a canopy, a dungeon -- is
 * derived from the cell or region it starts in, and every chunk it touches
 * derives the same thing and draws its own part.
 */
fun interface WorldgenPass {
    fun apply(chunk: ChunkContext)
}

/**
 * Builds a pass when a world is created.
 *
 * Factories run in pipeline order and may publish to [PassSetup.services] --
 * the biome map, the height field -- for later passes to read. A pass reads
 * only what earlier passes published, so the order in pack data is the order
 * of dependence too.
 */
fun interface PassFactory {
    fun create(setup: PassSetup): WorldgenPass
}

/** What a pass factory is given: the world, its own options, and the services earlier passes published. */
class PassSetup(
    val context: TerrainContext,
    val options: PassOptions,
    val services: WorldServices,
) {
    val config: WorldConfig get() = context.config
    val recipe: TerrainRecipe get() = context.recipe
}

/**
 * A pass's options from pack data, read with a default and a clear error.
 *
 * Options arrive as strings because pack JSON is written by hand; a value
 * that does not parse fails the world's creation naming the pass and the key,
 * rather than quietly falling back to a default the author did not ask for.
 */
class PassOptions(private val passId: String, private val values: Map<String, String>) {

    fun string(key: String, default: String): String = values[key] ?: default

    fun int(key: String, default: Int): Int = values[key]?.let {
        it.trim().toIntOrNull() ?: throw IllegalArgumentException("Pass '$passId' option '$key' is not a whole number: '$it'")
    } ?: default

    fun float(key: String, default: Float): Float = values[key]?.let {
        it.trim().toFloatOrNull() ?: throw IllegalArgumentException("Pass '$passId' option '$key' is not a number: '$it'")
    } ?: default

    fun boolean(key: String, default: Boolean): Boolean = values[key]?.let {
        it.trim().lowercase().toBooleanStrictOrNull() ?: throw IllegalArgumentException("Pass '$passId' option '$key' is not true or false: '$it'")
    } ?: default
}

/**
 * The passes this build knows, by id.
 *
 * Open like the generator registry: register a factory and any pack naming
 * that id in its pass list gets it, placed wherever the pack put it.
 */
class PassRegistry {

    private val factories = LinkedHashMap<String, PassFactory>()

    val ids: Set<String> get() = factories.keys

    fun register(id: String, factory: PassFactory): PassRegistry {
        factories[id] = factory
        return this
    }

    fun has(id: String): Boolean = id in factories

    /** An unknown pass is an error, for the same reason an unknown generator is: say so, do not guess. */
    fun create(spec: PassSpec, context: TerrainContext, services: WorldServices): WorldgenPass {
        val factory = factories[spec.id] ?: throw IllegalArgumentException(
            "No world generation pass named '${spec.id}'. Known passes: ${factories.keys.joinToString()}",
        )
        return factory.create(PassSetup(context, PassOptions(spec.id, spec.options), services))
    }
}
