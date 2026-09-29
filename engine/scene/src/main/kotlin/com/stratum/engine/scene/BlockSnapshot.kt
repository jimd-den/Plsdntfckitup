package com.stratum.engine.scene

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World

/**
 * A chunk and its eight neighbours, copied: what a mesher on another thread
 * reads instead of the live world, which the game thread keeps changing.
 * Copying nine chunks is a few hundred kilobytes and well under a
 * millisecond; racing the game thread is a crash on some phones and a
 * torn mesh on others.
 */
class BlockSnapshot private constructor(
    private val registry: BlockRegistry,
    private val centre: ChunkPos,
    private val cells: Array<ShortArray?>,
) {
    /** Block index at a position, or -1 where the snapshot has no chunk (unloaded, or out of reach). */
    fun index(x: Int, y: Int, z: Int): Int {
        if (z !in 0 until Chunk.HEIGHT) return if (z < 0) 1 else BlockRegistry.AIR_INDEX
        val cx = Math.floorDiv(x, Chunk.SIZE) - centre.x + 1
        val cy = Math.floorDiv(y, Chunk.SIZE) - centre.y + 1
        if (cx !in 0..2 || cy !in 0..2) return -1
        val c = cells[cy * 3 + cx] ?: return -1
        return c[Chunk.indexOf(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), z)].toInt()
    }

    fun type(index: Int): BlockType = registry.typeOf(index.coerceAtLeast(0))

    /** Highest non-air z in a column, -1 when empty or outside the snapshot. */
    fun surface(x: Int, y: Int): Int {
        for (z in Chunk.HEIGHT - 1 downTo 0) {
            val i = index(x, y, z)
            if (i < 0) return -1
            if (i != BlockRegistry.AIR_INDEX) return z
        }
        return -1
    }

    /**
     * Which of [layerCount] layers, [blocksPerLayer] blocks tall, could mesh
     * differently now than they did from [before]: those holding a changed
     * block of this chunk, or a changed block of a neighbour in the column
     * it shares with this chunk. A layer next to a change on its boundary is
     * marked too, because a face or the shading across the boundary moved.
     *
     * Null when the two cannot be compared cell by cell: a chunk appeared or
     * went, or they are of different chunks. Every layer false means no
     * block differs -- the microvoxels behind the chunk changed instead.
     *
     * Snapshots taken through a shared export (see [of]) hold the very same
     * array for a chunk that has not changed, so the usual case -- eight of
     * nine chunks untouched -- costs one identity check each.
     */
    fun changedLayers(before: BlockSnapshot, layerCount: Int, blocksPerLayer: Int): BooleanArray? {
        if (before.centre != centre) return null
        val dirty = BooleanArray(layerCount)
        fun mark(z: Int) {
            val layer = z / blocksPerLayer
            if (layer >= layerCount) return
            dirty[layer] = true
            if (z % blocksPerLayer == 0 && layer > 0) dirty[layer - 1] = true
            if (z % blocksPerLayer == blocksPerLayer - 1 && layer < layerCount - 1) dirty[layer + 1] = true
        }
        for (k in 0 until 9) {
            val now = cells[k]; val then = before.cells[k]
            if (now === then) continue
            if (now == null || then == null) return null
            val ox = k % 3 - 1; val oy = k / 3 - 1
            // A neighbour matters only along the cells it shares with the centre.
            val xs = when (ox) { -1 -> Chunk.SIZE - 1..Chunk.SIZE - 1; 1 -> 0..0; else -> 0 until Chunk.SIZE }
            val ys = when (oy) { -1 -> Chunk.SIZE - 1..Chunk.SIZE - 1; 1 -> 0..0; else -> 0 until Chunk.SIZE }
            for (z in 0 until Chunk.HEIGHT) {
                var differs = false
                for (y in ys) {
                    for (x in xs) {
                        val i = Chunk.indexOf(x, y, z)
                        if (now[i] != then[i]) { differs = true; break }
                    }
                    if (differs) break
                }
                if (differs) mark(z)
            }
        }
        return dirty
    }

    companion object {
        fun of(world: World, pos: ChunkPos): BlockSnapshot = of(world, pos) { it.exportBlocks() }

        /**
         * A snapshot whose chunk copies come from [export], which may hand out
         * the same array for a chunk that has not changed. The arrays are only
         * ever read, by any thread, so sharing them is safe -- and saves
         * copying nine chunks (over 200 KB) for every chunk meshed.
         */
        fun of(world: World, pos: ChunkPos, export: (Chunk) -> ShortArray): BlockSnapshot = BlockSnapshot(
            world.registry, pos,
            Array(9) { i -> world.chunkAt(ChunkPos(pos.x + i % 3 - 1, pos.y + i / 3 - 1))?.let(export) },
        )
    }
}
