package com.stratum.core.domain.world

/**
 * How a block behaves in the simulation. Everything a content pack can say
 * about a block lives here; the engine reads these flags and never special-cases
 * a block by name.
 */
data class BlockType(
    /** Namespaced and stable, e.g. `igbo:bronze_ore`. Save files store this, not the index. */
    val id: String,
    val displayName: String,
    val material: BlockMaterial = BlockMaterial.STONE,
    /** Seconds of uninterrupted mining at tier parity. [UNBREAKABLE] refuses all tools. */
    val hardness: Float = 1f,
    val requiredTier: Int = 0,
    /** Blocks movement and line of sight. */
    val isSolid: Boolean = true,
    /** Hides whatever is behind it, which is what lets the renderer cull. */
    val isOpaque: Boolean = true,
    /** Falls when the block beneath it is removed, like sand or gravel. */
    val hasGravity: Boolean = false,
    /** Breaks when it loses the neighbour it is attached to, like a torch. */
    val needsSupport: Boolean = false,
    /** 0..15, matching the light propagation range. */
    val lightEmission: Int = 0,
    /** What ends up in the bag. Null means the block drops itself. */
    val dropId: String? = null,
    /**
     * Drawn as this glyph instead of a cube.
     *
     * A tree rendered as stacked brown boxes is a brown box; rendered as 🌲 it
     * is a tree, and a player can tell at a glance whether a thing is scenery,
     * a resource or a hazard. Props opt in — terrain stays voxels, because the
     * depth and material grammar is the point of the terrain.
     *
     * One glyph is drawn per column, at the top of the run, so a four-block
     * trunk is one tree rather than four.
     */
    val glyph: String? = null,
    /**
     * Multiplies the base prop size. A tree and a pebble are both props and
     * should not be the same size, and a pack knows which is which.
     */
    val glyphScale: Float = 1f,
    /** Renderer hints. Packs own these so a pack can restyle the world wholesale. */
    val topColor: Long = 0xFF9E9E9E,
    val sideColor: Long = 0xFF6E6E6E,
    val accentColor: Long = 0xFF000000,
) {
    val isAir: Boolean get() = id == AIR_ID

    val isBreakable: Boolean get() = hardness >= 0f

    /** The item this block yields when mined. */
    val drop: String get() = dropId ?: id

    companion object {
        const val AIR_ID = "stratum:air"
        const val UNBREAKABLE = -1f

        val AIR = BlockType(
            id = AIR_ID,
            displayName = "Air",
            material = BlockMaterial.AIR,
            hardness = UNBREAKABLE,
            isSolid = false,
            isOpaque = false,
            topColor = 0x00000000,
            sideColor = 0x00000000,
        )

        /** The floor of the world, so players cannot dig into the void. */
        val BEDROCK = BlockType(
            id = "stratum:bedrock",
            displayName = "Bedrock",
            material = BlockMaterial.STONE,
            hardness = UNBREAKABLE,
            topColor = 0xFF2B2B33,
            sideColor = 0xFF1C1C22,
        )
    }
}

/**
 * Broad material classes. Tools express effectiveness against a material rather
 * than against individual blocks, so a pack can add fifty stone variants without
 * touching a single tool definition.
 */
enum class BlockMaterial {
    AIR,
    SOIL,
    STONE,
    ORE,
    WOOD,
    FOLIAGE,
    LIQUID,
    CLOTH,
    METAL,
    RITUAL,
}

/**
 * Interns [BlockType]s to dense indices so a chunk can store a `ShortArray`
 * instead of thousands of object references.
 *
 * Index 0 is always air. The registry is immutable once built: the assembler
 * builds one per loaded content pack set, and the world holds it for its lifetime.
 */
class BlockRegistry private constructor(
    private val types: List<BlockType>,
    private val indexById: Map<String, Int>,
) {
    val size: Int get() = types.size

    val all: List<BlockType> get() = types

    fun typeOf(index: Int): BlockType =
        types.getOrNull(index) ?: throw NoSuchBlockException("No block registered at index $index")

    fun typeOf(id: String): BlockType =
        indexById[id]?.let(types::get) ?: throw NoSuchBlockException("No block registered for id '$id'")

    fun indexOf(id: String): Int =
        indexById[id] ?: throw NoSuchBlockException("No block registered for id '$id'")

    fun indexOrNull(id: String): Int? = indexById[id]

    fun contains(id: String): Boolean = indexById.containsKey(id)

    /** Blocks whose [BlockType.hasGravity] is set, precomputed for the settle pass. */
    val gravityIndices: Set<Int> =
        types.indices.filter { types[it].hasGravity }.toSet()

    companion object {
        const val AIR_INDEX = 0

        fun build(blocks: List<BlockType>): BlockRegistry {
            val ordered = buildList {
                add(BlockType.AIR)
                add(BlockType.BEDROCK)
                blocks.forEach { candidate ->
                    if (candidate.id != BlockType.AIR_ID && candidate.id != BlockType.BEDROCK.id) {
                        add(candidate)
                    }
                }
            }
            val byId = LinkedHashMap<String, Int>(ordered.size)
            ordered.forEachIndexed { index, type ->
                require(byId.put(type.id, index) == null) {
                    "Duplicate block id '${type.id}' in registry"
                }
            }
            require(ordered.size <= Short.MAX_VALUE) {
                "Registry holds ${ordered.size} blocks, more than a chunk cell can address"
            }
            return BlockRegistry(ordered, byId)
        }
    }
}

class NoSuchBlockException(message: String) : IllegalArgumentException(message)
