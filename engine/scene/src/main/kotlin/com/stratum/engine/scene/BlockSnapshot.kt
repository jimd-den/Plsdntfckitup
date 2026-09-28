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

    companion object {
        fun of(world: World, pos: ChunkPos): BlockSnapshot = BlockSnapshot(
            world.registry, pos,
            Array(9) { i -> world.chunkAt(ChunkPos(pos.x + i % 3 - 1, pos.y + i / 3 - 1))?.exportBlocks() },
        )
    }
}
