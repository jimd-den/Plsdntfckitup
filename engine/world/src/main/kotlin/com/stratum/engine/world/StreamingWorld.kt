package com.stratum.engine.world

import kotlin.math.abs
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * A world that keeps only the chunks near the player resident and generates the
 * rest on demand.
 *
 * Chunks the player has edited are held in [modifiedChunks] when unloaded, so
 * walking away from a mine and coming back does not silently regenerate the
 * terrain over the tunnel. Untouched chunks are dropped outright, because the
 * generator can recreate them byte-for-byte.
 *
 * ## Generation off the game thread
 *
 * With [workers], chunks queued for [pump] are generated ahead on those
 * threads and only installed on the game thread, which costs nothing. A
 * microvoxel chunk takes several milliseconds to generate; two a tick on the
 * game thread was a hitch every time the player walked. A chunk needed at
 * once ([focusOn]'s urgent ring) takes its worker's result if one is under
 * way rather than starting over, and is made on the spot only if none is.
 * The generators are pure functions of position and safe to call from any
 * thread, so a chunk is the same whichever thread made it.
 */
class StreamingWorld(
    override val registry: BlockRegistry,
    private val generator: TerrainGenerator,
    private val config: WorldConfig,
    /** Threads to generate queued chunks on; null generates everything on the caller's thread, in [pump]. */
    private val workers: ExecutorService? = sharedWorkers,
) : MutableWorld {

    /** A chunk being generated on a worker, by the generator as it was in [epoch]. */
    private class Pending(val future: Future<Chunk>, val epoch: Int)

    private val pending = HashMap<ChunkPos, Pending>()

    /** Bumped by [regenerate]: chunks a worker made for an older generator are not used. */
    private var epoch = 0

    private val chunks = LinkedHashMap<ChunkPos, Chunk>()
    private val modifiedChunks = HashMap<ChunkPos, Chunk>()
    private val editedPositions = HashSet<ChunkPos>()

    override val loadedChunks: Collection<Chunk> get() = chunks.values

    /**
     * Counts every chunk arriving or leaving, so something that follows the
     * resident set -- markers waiting to be peopled -- can tell in one
     * comparison that nothing changed since it last looked.
     */
    var residency: Int = 0
        private set

    /** Chunk the streaming window is currently centred on. */
    var focus: ChunkPos = ChunkPos(0, 0)
        private set

    /** Chunks wanted but not yet generated, nearest first; see [pump]. */
    private val queued = ArrayList<ChunkPos>()

    /** The chunk the last lookup landed in. Most lookups come in runs over one chunk, and a map lookup per block was a large share of a tick. */
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastChunk: Chunk? = null

    /** Chunks queued for [pump] that are still wanted. */
    val pendingCount: Int get() = queued.size

    override fun chunkAt(pos: ChunkPos): Chunk? = chunks[pos]

    private fun chunkContaining(x: Int, y: Int): Chunk? {
        val cx = Math.floorDiv(x, Chunk.SIZE)
        val cy = Math.floorDiv(y, Chunk.SIZE)
        if (cx == lastX && cy == lastY) return lastChunk
        val chunk = chunks[ChunkPos(cx, cy)]
        lastX = cx; lastY = cy; lastChunk = chunk
        return chunk
    }

    private fun forgetLookup() {
        lastX = Int.MIN_VALUE
        lastChunk = null
    }

    override fun blockIndexAt(pos: BlockPos): Int {
        if (pos.z !in 0 until Chunk.HEIGHT) return BlockRegistry.AIR_INDEX
        val chunk = chunkContaining(pos.x, pos.y) ?: return BlockRegistry.AIR_INDEX
        return chunk.blockAt(
            Math.floorMod(pos.x, Chunk.SIZE),
            Math.floorMod(pos.y, Chunk.SIZE),
            pos.z,
        )
    }

    override fun blockAt(pos: BlockPos): BlockType = registry.typeOf(blockIndexAt(pos))

    override fun lightAt(pos: BlockPos): Int {
        val chunk = chunkContaining(pos.x, pos.y) ?: return 0
        return chunk.lightAt(
            Math.floorMod(pos.x, Chunk.SIZE),
            Math.floorMod(pos.y, Chunk.SIZE),
            pos.z,
        )
    }

    override fun surfaceAt(x: Int, y: Int): Int {
        val chunk = chunkContaining(x, y) ?: return -1
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
        residency++
        forgetLookup()
        modifiedChunks.remove(pos) ?: fromWorker(pos) ?: generator.generate(pos, registry)
    }

    /** A worker's chunk for [pos], waiting for it if it is still being made; null when there is none to use. */
    private fun fromWorker(pos: ChunkPos): Chunk? {
        val job = pending.remove(pos) ?: return null
        if (job.epoch != epoch) { job.future.cancel(false); return null }
        return runCatching { job.future.get() }.getOrNull()
    }

    override fun unloadChunk(pos: ChunkPos) {
        val chunk = chunks.remove(pos) ?: return
        residency++
        forgetLookup()
        if (pos in editedPositions) {
            modifiedChunks[pos] = chunk
        }
    }

    /**
     * Moves the streaming window. Returns what changed so a renderer can rebuild
     * only the chunks that actually appeared or vanished.
     */
    fun focusOn(pos: BlockPos, urgentRadius: Int = config.simulationRadius): StreamingDelta = focusOn(pos.chunkPos, urgentRadius)

    /**
     * Moves the streaming window to [centre]. Chunks within [urgentRadius] of
     * it are generated now; the rest of the window is queued for [pump], so
     * crossing a chunk border does not generate a whole row in one frame.
     * The default loads the whole window at once, as a new world should.
     */
    fun focusOn(centre: ChunkPos, urgentRadius: Int = config.simulationRadius): StreamingDelta {
        // Called every tick; the window only moves when the player crosses into another chunk.
        if (centre == focus && chunks.isNotEmpty()) return StreamingDelta.NONE
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
        // Ground the player turned away from before it was made is not waited for.
        pending.keys.filterNot(wanted::contains).forEach { pending.remove(it)?.future?.cancel(false) }

        val missing = wanted.filterNot(chunks::containsKey)
        val (now, later) = missing.partition { maxOf(abs(it.x - centre.x), abs(it.y - centre.y)) <= urgentRadius }
        now.forEach(::loadChunk)
        queued.clear()
        queued += later.sortedBy { (it.x - centre.x) * (it.x - centre.x) + (it.y - centre.y) * (it.y - centre.y) }

        return StreamingDelta(loaded = now, unloaded = toUnload)
    }

    /**
     * Generates up to [budget] queued chunks, nearest first. Returns how many it made.
     *
     * With [workers], it instead installs every queued chunk a worker has
     * finished -- installing is free, so [budget] does not limit it -- and
     * keeps the workers busy with the nearest of the rest.
     */
    fun pump(budget: Int): Int {
        val pool = workers ?: return pumpHere(budget)
        var made = 0
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val pos = iterator.next()
            val ready = chunks.containsKey(pos) || pos in modifiedChunks || pending[pos]?.future?.isDone == true
            if (!ready) continue
            iterator.remove()
            if (chunks.containsKey(pos)) pending.remove(pos)?.future?.cancel(false) else { loadChunk(pos); made++ }
        }
        for (pos in queued) {
            if (pending.size >= MAX_IN_FLIGHT) break
            if (pos in pending) continue
            pending[pos] = Pending(pool.submit(Callable { generator.generate(pos, registry) }), epoch)
        }
        return made
    }

    private fun pumpHere(budget: Int): Int {
        var made = 0
        while (made < budget && queued.isNotEmpty()) {
            val pos = queued.removeAt(0)
            if (chunks.containsKey(pos)) continue
            loadChunk(pos)
            made++
        }
        return made
    }

    /** Chunks the player has changed, whether resident or not. Used when saving. */
    fun dirtyChunks(): List<Chunk> =
        (chunks.filterKeys(editedPositions::contains).values + modifiedChunks.values).toList()

    /**
     * Hands over chunks a save kept, to be used in place of generating them
     * when the streaming window reaches them. Nothing is made resident, so
     * this belongs before the first [focusOn]: a chunk already streamed in
     * keeps what it has, and the saved one waits until it streams out and
     * back.
     */
    fun restoreEdited(saved: Collection<Chunk>) {
        saved.forEach { chunk ->
            editedPositions += chunk.pos
            if (!chunks.containsKey(chunk.pos)) modifiedChunks[chunk.pos] = chunk
        }
    }

    /**
     * Throws away every chunk the generator can remake and makes it again --
     * after the generator itself has changed (a retuned [com.stratum.engine.microbridge.HotTerrain]).
     * Chunks within [urgentRadius] of the focus are made now; the rest of the
     * window is queued for [pump]. Chunks the player has edited are kept as
     * they are: a retune never undoes a wall someone built. Returns how many
     * chunks were dropped.
     */
    fun regenerate(urgentRadius: Int = 1): Int {
        epoch++
        pending.values.forEach { it.future.cancel(false) }
        pending.clear()
        val stale = chunks.keys.filterNot(editedPositions::contains)
        stale.forEach { chunks.remove(it) }
        if (stale.isNotEmpty()) residency++
        forgetLookup()
        val centre = focus
        focus = ChunkPos(Int.MIN_VALUE, Int.MIN_VALUE)
        focusOn(centre, urgentRadius)
        return stale.size
    }

    /** Drops an existing chunk in, bypassing generation. Used when loading a save. */
    fun installChunk(chunk: Chunk, markEdited: Boolean = true) {
        chunks[chunk.pos] = chunk
        residency++
        forgetLookup()
        if (markEdited) editedPositions += chunk.pos
    }

    companion object {
        /** Chunks generated ahead at once: enough to keep the workers busy, few enough to drop cheaply when the player turns. */
        const val MAX_IN_FLIGHT = 8

        /**
         * The threads new worlds generate their queued chunks on, installed
         * once by the app at startup. Null -- the default, and what tests
         * use -- generates on the game thread, a fixed number a tick.
         */
        @Volatile var sharedWorkers: ExecutorService? = null
    }
}

data class StreamingDelta(
    val loaded: List<ChunkPos>,
    val unloaded: List<ChunkPos>,
) {
    val isEmpty: Boolean get() = loaded.isEmpty() && unloaded.isEmpty()

    companion object {
        val NONE = StreamingDelta(emptyList(), emptyList())
    }
}
