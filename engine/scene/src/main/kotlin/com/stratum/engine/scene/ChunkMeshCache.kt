package com.stratum.engine.scene

import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World

/**
 * Terrain meshed a chunk at a time, and remeshed only where the world changed.
 *
 * Meshing the whole view as one batch meant a single dug block re-meshed and
 * re-uploaded every chunk in sight: on a slow phone, a stall every time a
 * block broke. Here each chunk keeps its mesh until it, or a neighbour, is
 * edited or reloaded -- neighbours too, because face culling and ambient
 * occlusion read across chunk borders.
 */
class ChunkMeshCache(private val mesher: TerrainMesher) {

    private class Entry(val signature: Signature, val result: TerrainMesher.Result)

    private val entries = HashMap<ChunkPos, Entry>()

    /** Chunks meshed by the last call; what a profiler or a test wants to know. */
    var meshedLastCall: Int = 0
        private set

    /** Bumped whenever the set of meshes changes, so callers can cache what they derive from it. */
    var generation: Long = 0
        private set

    /**
     * Meshes for every loaded chunk touching the square of [radius] blocks
     * around ([centreX], [centreY]), reusing every one that is still current.
     *
     * @param worldRevision stands in for chunk revisions in worlds that do not
     *   expose chunks, so they still remesh when they change.
     */
    fun around(world: World, centreX: Int, centreY: Int, radius: Int, worldRevision: Int): List<TerrainMesher.Result> {
        meshedLastCall = 0
        val wanted = chunksCovering(centreX, centreY, radius).filter(world::isLoaded)
        val results = wanted.map { pos -> current(world, pos, worldRevision) }
        if (entries.keys.retainAll(wanted.toSet())) generation++
        return results
    }

    fun invalidate() {
        entries.clear()
        generation++
    }

    private fun current(world: World, pos: ChunkPos, worldRevision: Int): TerrainMesher.Result {
        val signature = Signature.of(world, pos, worldRevision)
        entries[pos]?.takeIf { it.signature == signature }?.let { return it.result }
        val result = mesher.mesh(world, pos.originX, pos.originX + Chunk.SIZE - 1, pos.originY, pos.originY + Chunk.SIZE - 1)
        entries[pos] = Entry(signature, result)
        meshedLastCall++
        generation++
        return result
    }

    /**
     * What a chunk's mesh depends on: which chunk objects are loaded in its
     * three-by-three neighbourhood, and how often each has changed. Identity
     * as well as revision, because a chunk unloaded and generated again starts
     * counting from zero.
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
                val revisions = IntArray(chunks.size) { chunks[it]?.revision ?: -1 }
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
