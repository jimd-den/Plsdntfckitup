package com.stratum.core.domain.world

/** Read-only view of the block world, which is all a renderer or an AI ever needs. */
interface World {
    val registry: BlockRegistry

    /** Chunks currently resident in memory. */
    val loadedChunks: Collection<Chunk>

    fun chunkAt(pos: ChunkPos): Chunk?

    fun blockAt(pos: BlockPos): BlockType

    fun blockIndexAt(pos: BlockPos): Int

    fun lightAt(pos: BlockPos): Int

    /** Highest non-air z in the column, or -1 if the column is empty or unloaded. */
    fun surfaceAt(x: Int, y: Int): Int

    fun isLoaded(pos: ChunkPos): Boolean

    fun isSolid(pos: BlockPos): Boolean = blockAt(pos).isSolid
}

/** The world plus the ability to change it. Only engine systems hold this type. */
interface MutableWorld : World {
    /**
     * Writes a block and returns whether anything changed. Callers that need
     * gravity, support and light to settle should go through the interaction
     * system rather than calling this directly.
     */
    fun setBlock(pos: BlockPos, index: Int): Boolean

    fun setBlock(pos: BlockPos, type: BlockType): Boolean = setBlock(pos, registry.indexOf(type.id))

    fun loadChunk(pos: ChunkPos): Chunk

    fun unloadChunk(pos: ChunkPos)
}

/**
 * Tuning that a content pack or the player's settings can change without the
 * engine caring which.
 */
data class WorldConfig(
    val seed: Long = 0L,
    /** Chunks kept resident in each direction from the focus chunk. */
    val simulationRadius: Int = 2,
    val seaLevel: Int = 12,
    /**
     * Vertical span the generator may use, capped by [Chunk.HEIGHT]. Small by
     * default: flat ground is walkable ground, and buildable ground.
     */
    val surfaceVariation: Int = 4,
    val caveDensity: Float = 0.42f,
    val oreRichness: Float = 1f,
) {
    init {
        require(simulationRadius >= 0) { "simulationRadius cannot be negative" }
        require(seaLevel in 1 until Chunk.HEIGHT) { "seaLevel must sit inside the world column" }
    }

    val loadedChunkCount: Int get() = (simulationRadius * 2 + 1) * (simulationRadius * 2 + 1)
}

/**
 * Fills a chunk with terrain. Implemented in `:engine:world`, but declared here
 * so use cases can depend on the capability without depending on the engine.
 */
interface TerrainGenerator {
    /** Must be deterministic: same seed and same [pos] always produce the same chunk. */
    fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk
}
