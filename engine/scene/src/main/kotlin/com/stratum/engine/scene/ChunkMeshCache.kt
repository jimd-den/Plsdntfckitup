package com.stratum.engine.scene

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World

/**
 * Terrain meshed a chunk at a time, and remeshed only where the world changed.
 *
 * Meshing the whole view as one batch meant a single dug block re-meshed and
 * re-uploaded every chunk in sight: on a slow phone, a stall every time a
 * block broke. Here each chunk keeps its mesh until it is edited or reloaded,
 * or a neighbour changes along their shared border -- the mesh reads one cell
 * past its edge for the faces it shares and the shading at its corners, and
 * no further.
 *
 * Meshing is also paced. Chunks the camera can see are meshed the frame they
 * need it, so an edit never shows a hole; chunks off screen -- the far edge
 * of the square coming into range as the player walks -- are meshed a few a
 * frame, nearest first, keeping their old mesh (or none) until their turn.
 * Walking used to mesh a whole row of them in one frame: a visible hitch
 * every few seconds, for ground nobody could see yet.
 */
class ChunkMeshCache(private val mesher: TerrainMesher) {

    private class Entry(val signature: Signature, val result: TerrainMesher.Result)

    private val entries = HashMap<ChunkPos, Entry>()

    /** Chunks meshed by the last call; what a profiler or a test wants to know. */
    var meshedLastCall: Int = 0
        private set

    /** Chunks that wanted meshing but were left for a later call. */
    var deferredLastCall: Int = 0
        private set

    // The last call's question and answer. When the world has not changed and
    // nothing is waiting its turn, the answer cannot have either -- and
    // working that out chunk by chunk was most of what a frame allocated.
    private var lastKey: LongArray? = null
    private var lastResults: List<TerrainMesher.Result> = emptyList()

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
        if (deferredLastCall == 0 && lastKey?.contentEquals(key) == true && world.loadedChunks.isNotEmpty()) {
            meshedLastCall = 0
            return lastResults
        }
        meshedLastCall = 0
        deferredLastCall = 0
        val wanted = chunksCovering(centreX, centreY, radius).filter(world::isLoaded)
        val signatures = wanted.map { Signature.of(world, it, worldRevision) }
        val stale = wanted.indices.filter { entries[wanted[it]]?.signature != signatures[it] }

        val (now, later) = stale.partition { urgent(wanted[it]) }
        val paced = later.sortedBy { i ->
            val pos = wanted[i]
            val dx = pos.originX + Chunk.SIZE / 2 - centreX
            val dy = pos.originY + Chunk.SIZE / 2 - centreY
            dx.toLong() * dx + dy.toLong() * dy
        }
        // A cold start -- entering a world, new textures -- meshes everything:
        // it happens behind a loading moment, and a view that filled in over
        // a second would look broken rather than fast.
        val budget = if (entries.isEmpty()) Int.MAX_VALUE else offscreenBudget.coerceAtLeast(0)
        (now + paced.take(budget)).forEach { i -> mesh(world, wanted[i], signatures[i]) }
        deferredLastCall = (paced.size - budget).coerceAtLeast(0)

        val results = wanted.mapNotNull { entries[it]?.result }
        if (entries.keys.retainAll(wanted.toSet())) generation++
        lastKey = key
        lastResults = results
        return results
    }

    fun invalidate() {
        entries.clear()
        lastKey = null
        generation++
    }

    private fun mesh(world: World, pos: ChunkPos, signature: Signature) {
        val result = mesher.mesh(world, pos.originX, pos.originX + Chunk.SIZE - 1, pos.originY, pos.originY + Chunk.SIZE - 1)
        entries[pos] = Entry(signature, result)
        meshedLastCall++
        generation++
    }

    /**
     * What a chunk's mesh depends on: which chunk objects are loaded in its
     * three-by-three neighbourhood, how often it has changed, and how often
     * each neighbour's border has. Identity as well as counts, because a
     * chunk unloaded and generated again starts counting from zero.
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
                    if (i == CENTRE) chunk.revision else chunk.edgeRevision
                }
                // A world with no chunk objects cannot say which part changed; fall back on the whole.
                return Signature(chunks, revisions, worldRevision.takeIf { chunks[CENTRE] == null })
            }

            private const val NEIGHBOURHOOD = 3
            private const val CENTRE = 4
        }
    }

    companion object {
        fun chunksCovering(centreX: Int, centreY: Int, radius: Int): List<ChunkPos> {
            val min = ChunkPos.containing(centreX - radius, centreY - radius)
            val max = ChunkPos.containing(centreX + radius, centreY + radius)
            return (min.y..max.y).flatMap { y -> (min.x..max.x).map { x -> ChunkPos(x, y) } }
        }
    }
}
