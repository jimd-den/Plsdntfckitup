package com.stratum.core.domain.world

import com.stratum.core.domain.content.BiomeDefinition

/**
 * One octave of elevation noise.
 *
 * Exposed as data so a pack can describe the *shape* of its landscape —
 * broad hills, sharp ridges, a dead flat plain — without anyone writing a
 * generator. [scale] is how fast the terrain changes across the world;
 * [amplitude] is how much of the height budget this octave spends.
 */
data class NoiseLayer(
    val scale: Float,
    val amplitude: Float,
    /** Shifts this octave off the others so they do not line up into stripes. */
    val seedOffset: Int = 0,
) {
    init {
        require(scale > 0f) { "A noise layer with no scale produces a flat plane" }
    }
}

/** One band of material under the surface, from the top down. */
data class Stratum(val blockId: String, val thickness: Int) {
    init {
        require(thickness > 0) { "A stratum with no thickness is not a layer" }
    }
}

/**
 * How a world is shaped, as data.
 *
 * A pack ships one of these instead of a generator, which is what lets a world
 * be described by something that cannot write Kotlin — the AI pack forge, a
 * JSON file, a slider in a menu. [generatorId] names which algorithm reads it,
 * so a pack that wants an entirely different generator says so here and the
 * rest of the recipe becomes that generator's business.
 */
data class TerrainRecipe(
    val generatorId: String = LAYERED,
    /** Summed to produce the surface. Empty means a flat world at sea level. */
    val elevation: List<NoiseLayer> = DEFAULT_ELEVATION,
    /**
     * Surface heights snap to multiples of this.
     *
     * The single most important knob for readability. Smooth noise gives a
     * landscape of one-block steps that reads as texture and is miserable to
     * walk or build on. Snapping to 2 or 3 produces plateaus with clean ledges,
     * so you can see at a glance which level you are on and where you can climb.
     */
    val terraceStep: Int = 2,
    /**
     * Material bands below the surface, top first. Empty falls back to the
     * biome's own subsurface and filler blocks.
     */
    val strata: List<Stratum> = emptyList(),
    /** Overrides [WorldConfig.caveDensity] when set. */
    val caveDensity: Float? = null,
    /**
     * How strongly scattered decoration clumps, from 0 (evenly spread) to 1.
     *
     * Scatter is rolled per column, and independent rolls produce a perfectly
     * even sprinkle of trees -- which is the single thing that makes a
     * generated landscape read as wallpaper rather than as a place. Real
     * ground has thickets and it has clearings, and the clearings are what
     * make somewhere look lived in: open ground is where a path, a camp or a
     * field would be, and a uniform field of trees has nowhere for any of
     * that to have happened.
     *
     * It is a multiplier on each rule's chance rather than a rule of its own,
     * so a biome that wants an even sprinkle still gets one by asking for
     * zero, and the overall amount of scatter is roughly preserved: what
     * clearings lose, groves gain.
     */
    val scatterClustering: Float = 0.75f,
    /** How large the clumps are. Bigger is broader groves and wider clearings. */
    val scatterClusterScale: Float = 0.06f,
    /** Anything a third-party generator wants; the built-in one ignores it. */
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(terraceStep >= 1) { "terraceStep of $terraceStep would divide by zero" }
        require(scatterClustering in 0f..1f) {
            "scatterClustering of $scatterClustering is not a share"
        }
        require(scatterClusterScale > 0f) { "scatterClusterScale must be positive" }
    }

    /**
     * How much scatter a place gets, given a clumping sample in 0..1.
     *
     * Centred on 1 so the world keeps roughly the amount of decoration the
     * biome asked for: at full clustering a grove gets nearly twice its rule's
     * chance and a clearing gets nearly none, and the average comes out where
     * it started. Without that, turning clustering up would quietly strip the
     * world bare or bury it.
     */
    fun scatterDensity(sample: Float): Float {
        // The raw sample is stretched before it is used. Fractal value noise
        // is an average of octaves and so spends almost all of its time near
        // the middle: measured over forty thousand samples, ninety per cent of
        // it fell between 0.25 and 0.79 and it reached neither end. Fed in
        // directly it produced a field that was slightly thicker in places and
        // slightly thinner in others, which is not a grove and not a clearing
        // -- it is the even sprinkle it was meant to replace, and the first
        // version of this shipped looking identical to no clustering at all.
        val stretched = ((sample - 0.5f) * SPREAD + 0.5f).coerceIn(0f, 1f)
        // Eased so the edges of a grove are a gradient rather than a line.
        val eased = stretched * stretched * (3f - 2f * stretched)
        return (1f - scatterClustering) + scatterClustering * 2f * eased
    }

    /** Snaps a height to the recipe's terraces. */
    fun terraced(height: Int): Int =
        if (terraceStep <= 1) height else (height / terraceStep) * terraceStep

    companion object {
        const val LAYERED = "stratum:layered"

        /**
         * How far the clumping sample is pushed towards its extremes.
         *
         * Chosen from the measured spread rather than by taste: it maps the
         * noise's real fifth-to-ninety-fifth percentile onto the whole range,
         * so the thin places actually get thin.
         */
        private const val SPREAD = 2.4f

        /**
         * Two octaves: one broad landform, one finer detail at a third the
         * amplitude. Enough to read as terrain, not so much that it becomes
         * noise.
         */
        val DEFAULT_ELEVATION = listOf(
            NoiseLayer(scale = 0.012f, amplitude = 1f),
            NoiseLayer(scale = 0.05f, amplitude = 0.35f, seedOffset = 101),
        )

        /** A perfectly flat world, for testing and for building sandboxes. */
        val FLAT = TerrainRecipe(elevation = emptyList(), terraceStep = 1)
    }
}

/**
 * A generator that can also say which biome a column belongs to.
 *
 * Optional on purpose. Naming the region you are standing in is a nicety, and
 * requiring it would make "write your own generator" a bigger job than it needs
 * to be — a dungeon generator has no biomes and should not have to pretend.
 */
interface BiomeSource {
    fun biomeAt(worldX: Int, worldY: Int): BiomeDefinition
}

/** Everything a generator needs to build itself. */
data class TerrainContext(
    val config: WorldConfig,
    val biomes: List<BiomeDefinition>,
    val recipe: TerrainRecipe,
)

/** Builds a generator from a recipe. This is the seam a new algorithm plugs into. */
fun interface TerrainGeneratorFactory {
    fun create(context: TerrainContext): TerrainGenerator
}

/**
 * The generators this build knows about, by id.
 *
 * Open by construction: register a factory and any pack naming that id gets it.
 * A completely different world generator — caves-only, dungeon rooms, a
 * heightmap loaded from a file — needs nothing from this class but an id.
 */
class TerrainGeneratorRegistry {

    private val factories = LinkedHashMap<String, TerrainGeneratorFactory>()

    val ids: Set<String> get() = factories.keys

    fun register(id: String, factory: TerrainGeneratorFactory): TerrainGeneratorRegistry {
        factories[id] = factory
        return this
    }

    fun has(id: String): Boolean = id in factories

    /**
     * Builds the generator a recipe asks for.
     *
     * An unknown id is an error rather than a silent fallback: a pack that asks
     * for a generator this build does not have should say so, not quietly hand
     * the player a different world and let them wonder why it looks wrong.
     */
    fun create(context: TerrainContext): TerrainGenerator {
        val factory = factories[context.recipe.generatorId]
            ?: throw IllegalArgumentException(
                "No terrain generator named '${context.recipe.generatorId}'. " +
                    "Known generators: ${factories.keys.joinToString()}",
            )
        return factory.create(context)
    }
}
