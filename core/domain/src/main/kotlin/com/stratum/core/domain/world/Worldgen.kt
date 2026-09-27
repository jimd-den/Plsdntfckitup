package com.stratum.core.domain.world

/**
 * One stage of a staged world generator, named in pack data.
 *
 * A pipeline generator is an ordered list of these. A pack that lists passes
 * replaces its preset's list outright, which is how it reorders, drops, adds
 * or configures a stage without anyone writing Kotlin; a pass registered in
 * code is named here exactly like a built-in one.
 */
data class PassSpec(
    val id: String,
    /** Whatever the pass reads; each built-in pass documents its keys. */
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "A pass needs an id" }
    }
}

/**
 * Where a biome sits in climate space.
 *
 * Choosing biomes by climate rather than by one noise index is what puts the
 * marsh next to the grove and the ash peaks next to the dry courtyards: two
 * smooth fields, heat and wet, and every region is the one nearest the
 * climate at that spot. Neighbours in climate are neighbours on the map.
 */
data class ClimatePoint(
    val biomeId: String,
    /** 0 cold to 1 hot. */
    val temperature: Float,
    /** 0 dry to 1 wet. */
    val moisture: Float,
    /**
     * Blocks below the surface this biome occupies, for an underground biome.
     *
     * Null for a surface biome. An underground biome replaces the filler in
     * its band of depth wherever the climate above is nearest it, which is
     * how a world gets a mushroom layer or a frozen deep under its hills.
     */
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
) {
    init {
        require(temperature in 0f..1f && moisture in 0f..1f) { "Climate of '$biomeId' must be 0..1 on both axes" }
        require((minDepth == null) == (maxDepth == null)) { "An underground biome needs both minDepth and maxDepth" }
        if (minDepth != null && maxDepth != null) {
            require(minDepth in 1..maxDepth) { "Depth band of '$biomeId' is inverted or starts at the surface" }
        }
    }

    val isUnderground: Boolean get() = minDepth != null
}

/**
 * How climate varies across a world, and how biomes are placed in it.
 *
 * Biomes without a [ClimatePoint] still take part: their temperature is the
 * biome's own, and they are spread evenly along the moisture axis, so a pack
 * written before climate existed still gets every one of its regions.
 */
data class ClimateSpec(
    val points: List<ClimatePoint> = emptyList(),
    /** How fast climate changes across the world. Smaller means larger regions. */
    val scale: Float = 0.005f,
    /**
     * How far apart in climate two biomes must be before their heights stop
     * blending, in climate units. Wider is gentler borders and fewer cliffs.
     */
    val blend: Float = 0.15f,
) {
    init {
        require(scale > 0f) { "Climate scale must be positive" }
        require(blend > 0f) { "Climate blend must be positive" }
    }
}

/**
 * One carver: what cuts caves, tunnels, ravines and caverns out of the rock.
 *
 * [kind] names a carver the engine registered -- `caves`, `tunnels`,
 * `ravines`, `caverns` ship with it -- and the numbers mean what that kind
 * says they mean. Kept to one shape of rule so a pack can list several and
 * layer them, the way cavern layers stack in a deep world.
 */
data class CarverRule(
    val kind: String,
    /** The band this carver may cut, in blocks above bedrock. */
    val minZ: Int = 2,
    val maxZ: Int = Chunk.HEIGHT - 1,
    /**
     * For noise carvers, the value noise must exceed to cut: higher is less
     * rock removed. For path carvers, how many start in each region.
     */
    val amount: Float = 0.6f,
    /** Horizontal radius of a tunnel, or the feature size of a noise carver. */
    val size: Float = 2f,
    /** Solid blocks always kept under the surface, so caves do not open into holes. */
    val headroom: Int = 3,
    /** Anything a registered carver wants beyond these. */
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(kind.isNotBlank()) { "A carver needs a kind" }
        require(minZ >= 1 && maxZ >= minZ) { "Carver band $minZ..$maxZ is empty or cuts bedrock" }
        require(size > 0f) { "Carver size must be positive" }
        require(headroom >= 0) { "Carver headroom cannot be negative" }
    }
}

/**
 * An ore that grows as veins: a short random walk of blocks from a seeded
 * start, which may cross a chunk border and still come out whole.
 */
data class OreRule(
    val blockId: String,
    val minZ: Int,
    val maxZ: Int,
    /** Veins started per chunk on average, scaled by [WorldConfig.oreRichness]. */
    val veinsPerChunk: Float,
    /** Blocks in one vein. */
    val veinSize: Int = 6,
    /** Only under these biomes; empty for everywhere. */
    val biomeIds: List<String> = emptyList(),
    /** The blocks a vein may replace; empty for any solid block but bedrock. */
    val replaces: List<String> = emptyList(),
) {
    init {
        require(minZ >= 1 && maxZ >= minZ) { "Ore band $minZ..$maxZ for '$blockId' is empty or inside bedrock" }
        require(veinsPerChunk >= 0f) { "veinsPerChunk cannot be negative" }
        require(veinSize in 1..MAX_VEIN) { "veinSize must be 1..$MAX_VEIN so a vein never reaches past a neighbouring chunk" }
    }

    companion object {
        const val MAX_VEIN = 16
    }
}

/**
 * A tree with a canopy wider than its trunk.
 *
 * Unlike a [com.stratum.core.domain.content.ScatterRule], a tree's leaves
 * spill onto neighbouring columns and across chunk borders, which is why the
 * generator roots trees on a seeded roll per column and every chunk asks
 * about the roots near its edge, not only its own.
 */
data class TreeRule(
    val trunkBlockId: String,
    val leafBlockId: String,
    /** 0..1 chance per surface column, before the grove clustering. */
    val chance: Float,
    val minHeight: Int = 3,
    val maxHeight: Int = 5,
    val canopyRadius: Int = 2,
    val biomeIds: List<String> = emptyList(),
) {
    init {
        require(chance in 0f..1f) { "Tree chance of $chance is not a share" }
        require(minHeight in 1..maxHeight) { "Tree height $minHeight..$maxHeight is inverted" }
        require(canopyRadius in 0..MAX_CANOPY) { "Canopy radius must be 0..$MAX_CANOPY" }
    }

    companion object {
        const val MAX_CANOPY = 6
    }
}

/** Where a liquid rule pours. */
enum class LiquidTarget {
    /** Open air above the ground: seas, lakes, a flooded lowland. */
    OPEN,

    /** Air under the ground: flooded caves and aquifers. */
    CAVES,

    /** Both. */
    ALL,
}

/** Fills air up to a level with a liquid block: the sea, or water and lava in caves. */
data class LiquidRule(
    val blockId: String,
    /** The highest z filled. */
    val maxZ: Int,
    val minZ: Int = 1,
    val target: LiquidTarget = LiquidTarget.OPEN,
    /**
     * The share of cave regions that are flooded, for [LiquidTarget.CAVES].
     * Aquifers are regional, so a cave system is wet or dry as a whole rather
     * than every other pocket holding a puddle.
     */
    val share: Float = 1f,
) {
    init {
        require(minZ >= 1 && maxZ >= minZ) { "Liquid band $minZ..$maxZ for '$blockId' is empty" }
        require(share in 0f..1f) { "Liquid share of $share is not a share" }
    }
}
