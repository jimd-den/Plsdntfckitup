package com.stratum.engine.scene

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Terrain meshed a chunk at a time, and remeshed only where the world changed.
 *
 * Meshing the whole view as one batch meant a single dug block re-meshed and
 * re-uploaded every chunk in sight: on a slow phone, a stall every time a
 * block broke. Here each chunk keeps its mesh until it is edited or reloaded,
 * or a neighbour changes along their shared side -- the mesh reads one cell
 * past its edge for the faces it shares and the shading at its corners, and
 * no further, so only the neighbour across that side is remeshed.
 *
 * Meshing is also paced. Chunks the camera can see are meshed the frame they
 * need it, so an edit never shows a hole; chunks off screen -- the far edge
 * of the square coming into range as the player walks -- are meshed a few a
 * frame, nearest first, keeping their old mesh (or none) until their turn.
 * Walking used to mesh a whole row of them in one frame: a visible hitch
 * every few seconds, for ground nobody could see yet.
 *
 * ## Edits never drop detail
 *
 * A chunk drawn from microvoxels that is edited stays drawn from
 * microvoxels. It used to fall back to its textured block mesh for the few
 * frames its new detail took on a worker, then swap back: every block placed
 * flickered its whole chunk (and the neighbours sharing the side) between
 * two looks -- the jitter a player felt when building. Now an edited chunk
 * on screen has just the layers the edit touched remeshed in detail, on the
 * frame (see [MicroDetailMesher.meshLayers]); past a small per-frame budget,
 * or off screen, it keeps drawing its previous detail until the worker
 * brings the new one. Only a chunk changed so much that the detail gives up
 * on it is ever drawn in blocks again.
 */
class ChunkMeshCache(
    private val mesher: TerrainMesher,
    /**
     * Draws chunks near the centre from the microvoxels behind them, when the
     * world has any. Null keeps every chunk on the block mesher.
     */
    private val detail: MicroDetailMesher? = null,
    /** Blocks from the centre within which a chunk is drawn in microvoxel detail. */
    private val detailRadius: Int = 0,
    /**
     * Blocks from the centre out to which chunks past [detailRadius] are
     * still drawn from microvoxels, at half-block resolution: with it at the
     * view's edge, the whole view is microvoxels. 0 draws them as blocks.
     */
    private val farRadius: Int = 0,
    /**
     * Detail layers an edit may remesh on the frame itself, per call. A layer
     * is a third of a chunk and costs about 5 ms on a desktop core, 15-25 on
     * a phone's, so the game leaves this at 0: an edit's layers go to a
     * worker of their own that no streaming work queues ahead of, and land a
     * frame or two later, with the scene's placement pop drawn over the gap
     * (see [awaiting]). Tools and tests that want every edit on its own
     * frame raise it.
     */
    private val editLayersPerCall: Int = 0,
) {

    /**
     * A chunk's meshes: one block mesh ([lod] 0), or one detail mesh per
     * layer ([lod] 1 full microvoxels, 2 half-block). [snapshot] is the blocks
     * the detail was made from, so an edit can tell which layers it touched;
     * null when the entry is not detail, or detail gave up on the chunk.
     */
    private class Entry(
        val signature: Signature,
        val results: List<TerrainMesher.Result>,
        val lod: Int,
        val snapshot: BlockSnapshot? = null,
    ) {
        val detailed get() = lod > 0

        /** The per-layer detail meshes, when these are them. */
        val layers: List<TerrainMesher.Result>? get() = results.takeIf { snapshot != null }
    }

    /** A detail mesh being made on a worker, for the chunk as it was when [signature] was taken. */
    private class Job(
        val signature: Signature,
        val lod: Int,
        val snapshot: BlockSnapshot,
        val future: Future<List<TerrainMesher.Result>?>,
    )

    /**
     * Detail is meshed on its own low-priority thread from a snapshot of the
     * blocks, never on the frame: a chunk shows its block mesh until its
     * detail is ready, then swaps. Walking into town costs no hitch.
     */
    private val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_WORKERS)
    private val worker: ExecutorService? = detail?.let {
        Executors.newFixedThreadPool(workers) { r -> Thread(r, "micro-detail").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    }
    private val jobs = HashMap<ChunkPos, Job>()

    /**
     * Edits get a thread of their own, at normal priority. On the shared
     * workers an edit waited behind whatever streaming had queued -- up to a
     * dozen chunks of new ground -- so a tapped block could take a tenth of
     * a second to show; here it waits for at most the edit before it.
     */
    private val editWorker: ExecutorService? = detail?.let {
        Executors.newSingleThreadExecutor { r -> Thread(r, "micro-edit").apply { isDaemon = true } }
    }

    /**
     * Chunks drawn from meshes older than the world they show: an edit whose
     * new mesh is still being made. The scene draws a placed block's pop over
     * these until they catch up, so an edit is on screen the frame it happens.
     */
    var awaiting: Set<ChunkPos> = emptySet()
        private set

    /** Chunks drawn from microvoxels by the last call, for profiling. */
    var detailedLastCall: Int = 0
        private set

    /** Detail meshes still being made; a tool that wants a finished picture waits for this to reach zero. */
    val detailPending: Int get() = jobs.size

    private val entries = HashMap<ChunkPos, Entry>()

    /** Chunks meshed by the last call; what a profiler or a test wants to know. */
    var meshedLastCall: Int = 0
        private set

    /** Chunks that wanted meshing but were left for a later call. */
    var deferredLastCall: Int = 0
        private set

    /** Detail layers an edit remeshed on the frame, in the last call. */
    var editLayersLastCall: Int = 0
        private set

    /**
     * Chunks drawn from microvoxels by the call before that the last call drew
     * as blocks while still inside the detail ring: a visible pop. Zero unless
     * detail gave up on a chunk changed too much.
     */
    var poppedLastCall: Int = 0
        private set

    // The last call's question and answer. When the world has not changed and
    // nothing is waiting its turn, the answer cannot have either -- and
    // working that out chunk by chunk was most of what a frame allocated.
    private var lastKey: LongArray? = null
    private var lastResults: List<TerrainMesher.Result> = emptyList()

    /**
     * Each loaded chunk's blocks, copied once per revision and shared by every
     * snapshot that reads it. A snapshot used to copy all nine chunks it
     * covers, afresh, for every chunk meshed: a couple of hundred kilobytes
     * of garbage per edit, and more per step of a walk.
     */
    private class Export(val chunk: Chunk, val revision: Int, val cells: ShortArray)
    private val exports = HashMap<ChunkPos, Export>()

    /** Bumped whenever the set of meshes changes, so callers can cache what they derive from it. */
    var generation: Long = 0
        private set

    /**
     * Meshes for the loaded chunks touching the square of [radius] blocks
     * around ([centreX], [centreY]), reusing every one that is still current.
     *
     * @param worldRevision stands in for chunk revisions in worlds that do not
     *   expose chunks, so they still remesh when they change.
     * @param urgent whether a chunk must be current this call, typically
     *   because it is on screen. Urgent chunks are always meshed.
     * @param offscreenBudget how many non-urgent chunks may be meshed this
     *   call, once something has been meshed. The rest keep their last mesh,
     *   and one never meshed is left out.
     */
    fun around(
        world: World,
        centreX: Int,
        centreY: Int,
        radius: Int,
        worldRevision: Int,
        urgent: (ChunkPos) -> Boolean = { true },
        offscreenBudget: Int = Int.MAX_VALUE,
    ): List<TerrainMesher.Result> {
        val key = longArrayOf(centreX.toLong(), centreY.toLong(), radius.toLong(), worldRevision.toLong(), System.identityHashCode(world).toLong())
        if (deferredLastCall == 0 && jobs.isEmpty() && lastKey?.contentEquals(key) == true && world.loadedChunks.isNotEmpty()) {
            meshedLastCall = 0
            editLayersLastCall = 0
            poppedLastCall = 0
            return lastResults
        }
        meshedLastCall = 0
        deferredLastCall = 0
        editLayersLastCall = 0
        val wasDetailed = entries.filterValues { it.detailed && it.layers != null }.keys
        val wanted = chunksCovering(centreX, centreY, radius).filter(world::isLoaded)
        val signatures = wanted.map { Signature.of(world, it, worldRevision) }
        val detailed = wanted.map { lodFor(it, centreX, centreY) }
        collectDetail(world, wanted, signatures, detailed)
        patchEdits(world, wanted, signatures, detailed, urgent)
        // Block meshes are made now wherever a chunk has none that is current:
        // new ground, an edited block chunk, or one leaving the detail ring.
        // An edited chunk still in the ring keeps its detail (see patchEdits).
        val stale = wanted.indices.filter { i ->
            val e = entries[wanted[i]]
            e == null || (e.detailed && detailed[i] == 0) || (e.signature != signatures[i] && !(e.detailed && detailed[i] > 0))
        }

        val (now, later) = stale.partition { urgent(wanted[it]) }
        val paced = later.sortedBy { i -> distanceSquared(wanted[i], centreX, centreY) }
        // A cold start -- entering a world, new textures -- meshes everything:
        // it happens behind a loading moment, and a view that filled in over
        // a second would look broken rather than fast.
        val budget = if (entries.isEmpty()) Int.MAX_VALUE else offscreenBudget.coerceAtLeast(0)
        (now + paced.take(budget)).forEach { i -> mesh(world, wanted[i], signatures[i]) }
        requestDetail(world, wanted, signatures, detailed, centreX, centreY)
        detailedLastCall = wanted.count { entries[it]?.detailed == true }
        deferredLastCall = (paced.size - budget).coerceAtLeast(0)
        poppedLastCall = wanted.indices.count { i -> wanted[i] in wasDetailed && detailed[i] > 0 && entries[wanted[i]]?.layers == null }
        awaiting = wanted.indices.filter { i -> entries[wanted[i]].let { it != null && it.signature != signatures[i] } }.mapTo(HashSet()) { wanted[it] }

        val results = wanted.flatMap { entries[it]?.results.orEmpty() }
        val keep = wanted.toSet()
        jobs.keys.filterNot(keep::contains).forEach { jobs.remove(it)?.future?.cancel(false) }
        if (entries.keys.retainAll(keep)) generation++
        forgetExportsOutside(wanted)
        lastKey = key
        lastResults = results
        return results
    }

    fun invalidate() {
        jobs.values.forEach { it.future.cancel(false) }
        jobs.clear()
        entries.clear()
        exports.clear()
        awaiting = emptySet()
        lastKey = null
        generation++
    }

    /** How a chunk is drawn by its distance from the centre: 1 full microvoxels, 2 half-block microvoxels, 0 blocks. */
    private fun lodFor(pos: ChunkPos, centreX: Int, centreY: Int): Int {
        if (detail == null || (detailRadius <= 0 && farRadius <= 0)) return 0
        val dx = maxOf(pos.originX - centreX, centreX - (pos.originX + Chunk.SIZE - 1), 0)
        val dy = maxOf(pos.originY - centreY, centreY - (pos.originY + Chunk.SIZE - 1), 0)
        val d = maxOf(dx, dy)
        return when {
            d <= detailRadius -> 1
            d <= farRadius -> 2
            else -> 0
        }
    }

    private fun mesh(world: World, pos: ChunkPos, signature: Signature) {
        entries[pos] = Entry(signature, listOf(blockMesh(world, pos)), lod = 0)
        meshedLastCall++
        generation++
    }

    private fun blockMesh(world: World, pos: ChunkPos): TerrainMesher.Result =
        mesher.mesh(world, pos.originX, pos.originX + Chunk.SIZE - 1, pos.originY, pos.originY + Chunk.SIZE - 1)

    private fun snapshotOf(world: World, pos: ChunkPos): BlockSnapshot = BlockSnapshot.of(world, pos) { chunk ->
        val known = exports[chunk.pos]
        if (known != null && known.chunk === chunk && known.revision == chunk.revision) known.cells
        else chunk.exportBlocks().also { exports[chunk.pos] = Export(chunk, chunk.revision, it) }
    }

    /** Exports no wanted chunk's snapshot can reach any more are let go. */
    private fun forgetExportsOutside(wanted: List<ChunkPos>) {
        if (wanted.isEmpty()) { exports.clear(); return }
        val minX = wanted.minOf { it.x } - 1; val maxX = wanted.maxOf { it.x } + 1
        val minY = wanted.minOf { it.y } - 1; val maxY = wanted.maxOf { it.y } + 1
        exports.keys.retainAll { it.x in minX..maxX && it.y in minY..maxY }
    }

    /** Layers of a detailed entry an edit touched, or every layer when that cannot be told. */
    private fun dirtyLayers(entry: Entry, now: BlockSnapshot): BooleanArray {
        val d = detail ?: return BooleanArray(0)
        val dirty = entry.snapshot?.let { now.changedLayers(it, d.layers, d.blocksPerLayer) }
        // No block differs, yet the chunk changed: the microvoxels behind it did (a stamp). Any layer may show it.
        return if (dirty == null || dirty.none { it }) BooleanArray(d.layers) { true } else dirty
    }

    /**
     * Edited chunks that are drawn in detail are remeshed in detail, only in
     * the layers the edit touched: on the frame while [editLayersPerCall]
     * allows, otherwise on the edit worker straight away if they are on
     * screen. Until then they keep their previous detail -- still a true
     * picture of everything but the edit. Off screen, they wait their turn
     * with the rest in [requestDetail].
     */
    private fun patchEdits(world: World, wanted: List<ChunkPos>, signatures: List<Signature>, detailed: List<Int>, urgent: (ChunkPos) -> Boolean) {
        val d = detail ?: return
        var budget = editLayersPerCall
        for (i in wanted.indices) {
            val pos = wanted[i]
            val e = entries[pos] ?: continue
            if (!e.detailed || detailed[i] == 0 || e.signature == signatures[i]) continue
            if (jobs[pos]?.let { it.signature == signatures[i] && it.lod == e.lod } == true || !urgent(pos)) continue
            val snapshot = snapshotOf(world, pos)
            val dirty = dirtyLayers(e, snapshot)
            val cost = dirty.count { it }
            jobs.remove(pos)?.future?.cancel(false)
            if (cost > budget) {
                val previous = e.layers
                val lod = e.lod
                editWorker?.let { pool -> jobs[pos] = Job(signatures[i], lod, snapshot, pool.submit(Callable { d.meshLayers(snapshot, pos, lod, dirty, previous) })) }
                continue
            }
            budget -= cost
            val layers = d.meshLayers(snapshot, pos, e.lod, dirty, e.layers)
            entries[pos] = if (layers != null) Entry(signatures[i], layers, e.lod, snapshot)
            // Changed too much for detail: blocks from here on, remembered as the detail this chunk gets.
            else Entry(signatures[i], listOf(blockMesh(world, pos)), e.lod).also { meshedLastCall++ }
            editLayersLastCall += cost
            generation++
        }
    }

    /** Swaps in finished detail meshes that still describe their chunk as it is now. */
    private fun collectDetail(world: World, wanted: List<ChunkPos>, signatures: List<Signature>, detailed: List<Int>) {
        if (jobs.isEmpty()) return
        for (i in wanted.indices) {
            val pos = wanted[i]
            val job = jobs[pos] ?: continue
            if (!job.future.isDone) continue
            jobs.remove(pos)
            if (detailed[i] != job.lod || job.signature != signatures[i] || job.future.isCancelled) continue
            val fine = runCatching { job.future.get() }.getOrNull()
            entries[pos] = if (fine != null) Entry(signatures[i], fine, job.lod, job.snapshot)
            else {
                // A chunk the microvoxels cannot draw (changed too much) keeps its blocks, and is remembered as done.
                val blocks = entries[pos]?.takeIf { it.signature == signatures[i] && !it.detailed }?.results
                    ?: listOf(blockMesh(world, pos)).also { meshedLastCall++ }
                Entry(signatures[i], blocks, job.lod)
            }
            generation++
        }
    }

    /** Queues detail for chunks in the ring that lack it, nearest first, a few at a time. */
    private fun requestDetail(world: World, wanted: List<ChunkPos>, signatures: List<Signature>, detailed: List<Int>, centreX: Int, centreY: Int) {
        val pool = worker ?: return
        val mesherOnWorker = detail ?: return
        val missing = wanted.indices.filter { i ->
            detailed[i] > 0 && entries[wanted[i]]?.let { it.lod == detailed[i] && it.signature == signatures[i] } != true &&
                jobs[wanted[i]]?.let { it.signature == signatures[i] && it.lod == detailed[i] } != true
        }.sortedBy { i -> distanceSquared(wanted[i], centreX, centreY) }
        for (i in missing) {
            if (jobs.size >= MAX_DETAIL_JOBS * workers) break
            val pos = wanted[i]
            jobs.remove(pos)?.future?.cancel(false)
            val snapshot = snapshotOf(world, pos) // taken here, on the thread that owns the world
            val lod = detailed[i]
            // An edit to a chunk already in detail at this level remakes only the layers it touched.
            val previous = entries[pos]?.takeIf { it.lod == lod }
            val only = previous?.layers?.let { dirtyLayers(previous, snapshot) }
            val layers = previous?.layers
            jobs[pos] = Job(signatures[i], lod, snapshot, pool.submit(Callable { mesherOnWorker.meshLayers(snapshot, pos, lod, only, layers) }))
        }
    }

    private fun distanceSquared(pos: ChunkPos, centreX: Int, centreY: Int): Long {
        val dx = pos.originX + Chunk.SIZE / 2 - centreX
        val dy = pos.originY + Chunk.SIZE / 2 - centreY
        return dx.toLong() * dx + dy.toLong() * dy
    }

    /**
     * What a chunk's mesh depends on: which chunk objects are loaded in its
     * three-by-three neighbourhood, how often it has changed, and how often
     * each neighbour's side facing it has. Identity as well as counts,
     * because a chunk unloaded and generated again starts counting from zero.
     */
    private class Signature(private val chunks: Array<Chunk?>, private val revisions: IntArray, private val worldRevision: Int?) {

        override fun equals(other: Any?): Boolean =
            other is Signature &&
                worldRevision == other.worldRevision &&
                revisions.contentEquals(other.revisions) &&
                chunks.indices.all { chunks[it] === other.chunks[it] }

        override fun hashCode(): Int = revisions.contentHashCode()

        companion object {
            fun of(world: World, pos: ChunkPos, worldRevision: Int): Signature {
                val chunks = Array(NEIGHBOURHOOD * NEIGHBOURHOOD) { i -> world.chunkAt(ChunkPos(pos.x + i % 3 - 1, pos.y + i / 3 - 1)) }
                val revisions = IntArray(chunks.size) { i ->
                    val chunk = chunks[i] ?: return@IntArray -1
                    // The neighbour at (dx, dy) touches this chunk along its side facing back, (-dx, -dy).
                    if (i == CENTRE) chunk.revision else chunk.sideRevision(1 - i % 3, 1 - i / 3)
                }
                // A world with no chunk objects cannot say which part changed; fall back on the whole.
                return Signature(chunks, revisions, worldRevision.takeIf { chunks[CENTRE] == null })
            }

            private const val NEIGHBOURHOOD = 3
            private const val CENTRE = 4
        }
    }

    companion object {
        /** Detail meshes queued at once; walking fast re-queues what it still needs rather than building a backlog. */
        const val MAX_DETAIL_JOBS = 4
        /** Meshing threads at most; a phone keeps at least one core for the game. */
        const val MAX_WORKERS = 3
        /** See the constructor's `editLayersPerCall`. */
        const val EDIT_LAYERS_PER_CALL = 3

        fun chunksCovering(centreX: Int, centreY: Int, radius: Int): List<ChunkPos> {
            val min = ChunkPos.containing(centreX - radius, centreY - radius)
            val max = ChunkPos.containing(centreX + radius, centreY + radius)
            return (min.y..max.y).flatMap { y -> (min.x..max.x).map { x -> ChunkPos(x, y) } }
        }
    }
}
