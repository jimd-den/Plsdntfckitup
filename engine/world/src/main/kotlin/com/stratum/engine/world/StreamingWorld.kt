package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig

/**
 * A world that keeps only the chunks near the player resident and generates the
 * rest on demand.
 *
 * Chunks the player has edited are held in [modifiedChunks] when unloaded, so
 * walking away from a mine and coming back does not silently regenerate the
 * terrain over the tunnel. Untouched chunks are dropped outright, because the
 * generator can recreate them byte-for-byte.
 */
class StreamingWorld(
    override val registry: BlockRegistry,
    private val generator: TerrainGenerator,
    private val config: WorldConfig,
) : MutableWorld {

    private val chunks = LinkedHashMap<ChunkPos, Chunk>()
    private val modifiedChunks = HashMap<ChunkPos, Chunk>()
    private val editedPositions = HashSet<ChunkPos>()

    override val loadedChunks: Collection<Chunk> get() = chunks.values

    /** Chunk the streaming window is currently centred on. */
    var focus: ChunkPos = ChunkPos(0, 0)
        private set

    override fun chunkAt(pos: ChunkPos): Chunk? = chunks[pos]

    override fun blockIndexAt(pos: BlockPos): Int {
        if (pos.z !in 0 until Chunk.HEIGHT) return BlockRegistry.AIR_INDEX
        val chunk = chunks[pos.chunkPos] ?: return BlockRegistry.AIR_INDEX
        return chunk.blockAt(
            Math.floorMod(pos.x, Chunk.SIZE),
            Math.floorMod(pos.y, Chunk.SIZE),
            pos.z,
        )
    }

    override fun blockAt(pos: BlockPos): BlockType = registry.typeOf(blockIndexAt(pos))

    override fun lightAt(pos: BlockPos): Int {
        val chunk = chunks[pos.chunkPos] ?: return 0
        return chunk.lightAt(
            Math.floorMod(pos.x, Chunk.SIZE),
            Math.floorMod(pos.y, Chunk.SIZE),
            pos.z,
        )
    }

    override fun surfaceAt(x: Int, y: Int): Int {
        val chunk = chunks[ChunkPos.containing(x, y)] ?: return -1
        return chunk.surfaceAt(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE))
    }

    override fun isLoaded(pos: ChunkPos): Boolean = chunks.containsKey(pos)

    override fun setBlock(pos: BlockPos, index: Int): Boolean {
        if (pos.z !in 0 until Chunk.HEIGHT) return false
        val chunkPos = pos.chunkPos
        val chunk = chunks[chunkPos] ?: return false
        val changed = chunk.setBlock(
            Math.floorMod(pos.x, Chunk.SIZE),
            Math.floorMod(pos.y, Chunk.SIZE),
            pos.z,
            index,
        )
        if (changed) editedPositions += chunkPos
        return changed
    }

    override fun loadChunk(pos: ChunkPos): Chunk = chunks.getOrPut(pos) {
        modifiedChunks.remove(pos) ?: generator.generate(pos, registry)
    }

    override fun unloadChunk(pos: ChunkPos) {
        val chunk = chunks.remove(pos) ?: return
        if (pos in editedPositions) {
            modifiedChunks[pos] = chunk
        }
    }

    /**
     * Moves the streaming window. Returns what changed so a renderer can rebuild
     * only the chunks that actually appeared or vanished.
     */
    fun focusOn(pos: BlockPos): StreamingDelta = focusOn(pos.chunkPos)

    fun focusOn(centre: ChunkPos): StreamingDelta {
        focus = centre
        val wanted = buildSet {
            for (dy in -config.simulationRadius..config.simulationRadius) {
                for (dx in -config.simulationRadius..config.simulationRadius) {
                    add(ChunkPos(centre.x + dx, centre.y + dy))
                }
            }
        }

        val toUnload = chunks.keys.filterNot(wanted::contains)
        toUnload.forEach(::unloadChunk)

        val toLoad = wanted.filterNot(chunks::containsKey)
        toLoad.forEach(::loadChunk)

        return StreamingDelta(loaded = toLoad.toList(), unloaded = toUnload)
    }

    /** Chunks the player has changed, whether resident or not. Used when saving. */
    fun dirtyChunks(): List<Chunk> =
        (chunks.filterKeys(editedPositions::contains).values + modifiedChunks.values).toList()

    /** Drops an existing chunk in, bypassing generation. Used when loading a save. */
    fun installChunk(chunk: Chunk, markEdited: Boolean = true) {
        chunks[chunk.pos] = chunk
        if (markEdited) editedPositions += chunk.pos
    }
}

data class StreamingDelta(
    val loaded: List<ChunkPos>,
    val unloaded: List<ChunkPos>,
) {
    val isEmpty: Boolean get() = loaded.isEmpty() && unloaded.isEmpty()
}
