package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroTerrainSource
import kotlin.math.floor

/**
 * The ground at quarter-block scale, for a world generated in microvoxels.
 *
 * Bodies stand on the microvoxels themselves rather than on the blocks read
 * off them: a hillside is a slope of quarter steps, a kerb is a quarter-block
 * lip, a carved dip is as deep as it looks. Where the player has changed a
 * block -- dug it, built it -- the block wins, solid or open as it now is,
 * just as the renderer draws it.
 *
 * Leaves, flowers, fruit and water are walked through. A lone tuft or pebble
 * is not a step: the height a body stands at is the middle of five columns
 * (its own and the four beside it), so a spike one microvoxel across is
 * stepped over while a wall, which runs on beside itself, still stops a body.
 */
class MicroGround(private val world: World, private val source: MicroTerrainSource) {

    private val r = source.microPerBlock

    /** Microvoxels a body walks through, by material id. */
    private val passable: BooleanArray = run {
        val palette = source.palette
        val through = setOf(M.LEAVES, M.LEAVES_AUTUMN, M.FLOWER_RED, M.FLOWER_YELLOW, M.FRUIT)
        BooleanArray(palette.all.maxOf { it.id.toInt() } + 1) { i ->
            val m = palette.all.firstOrNull { it.id.toInt() == i }
            m == null || !m.solid || m.name in through
        }
    }

    @Volatile private var lastChunk: MicroChunk? = null

    /** Whether microvoxel ([mx], [my], [mz]) holds something a body stands on or walks into. */
    fun solid(mx: Int, my: Int, mz: Int): Boolean {
        if (mz < 0) return true
        val bz = mz / r
        if (bz >= Chunk.HEIGHT) return false
        val pos = BlockPos(Math.floorDiv(mx, r), Math.floorDiv(my, r), bz)
        val block = world.blockIndexAt(pos)
        // Changed by the player: the block, as it is now.
        if (block != source.generatedBlock(pos.x, pos.y, pos.z)) return block != BlockRegistry.AIR_INDEX && world.isSolid(pos)
        val v = chunkOf(mx, my, mz).let { c -> c[mx - c.pos.originX, my - c.pos.originY, mz - c.pos.originZ] }.toInt()
        return v >= 0 && v < passable.size && !passable[v]
    }

    /** The top of the highest solid microvoxel at or below [fromMz] in column ([mx], [my]), as a microvoxel count; -1 if none within [depth]. */
    fun top(mx: Int, my: Int, fromMz: Int, depth: Int = MAX_DROP): Int {
        var mz = fromMz.coerceAtMost(Chunk.HEIGHT * r - 1)
        val floorMz = (fromMz - depth).coerceAtLeast(-1)
        while (mz > floorMz) {
            if (solid(mx, my, mz)) return mz + 1
            mz--
        }
        return if (floorMz < 0) 0 else -1
    }

    /**
     * Where a body at ([x], [y]) stands, searching down from [fromZ] (in
     * blocks): the middle of its column and the four beside it, in blocks,
     * or null when nothing lies beneath within [MAX_DROP] microvoxels.
     */
    fun standAt(x: Float, y: Float, fromZ: Float): Float? {
        val mx = floor(x * r).toInt(); val my = floor(y * r).toInt()
        val from = floor(fromZ * r).toInt()
        val c = top(mx, my, from)
        if (c < 0) return null
        val around = intArrayOf(c, top(mx + 1, my, from), top(mx - 1, my, from), top(mx, my + 1, from), top(mx, my - 1, from))
        around.sort()
        return around[2].coerceAtLeast(0) / r.toFloat()
    }

    /** Whether a body [height] blocks tall fits standing at [z] over ([x], [y]). */
    fun clear(x: Float, y: Float, z: Float, height: Float): Boolean {
        val mx = floor(x * r).toInt(); val my = floor(y * r).toInt()
        val z0 = floor(z * r + 0.001f).toInt()
        val z1 = floor((z + height) * r).toInt()
        for (mz in z0 until z1) if (solid(mx, my, mz)) return false
        return true
    }

    private fun chunkOf(mx: Int, my: Int, mz: Int): MicroChunk {
        val p = MicroChunkPos(Math.floorDiv(mx, MicroChunk.SIZE), Math.floorDiv(my, MicroChunk.SIZE), Math.floorDiv(mz, MicroChunk.SIZE))
        lastChunk?.takeIf { it.pos == p }?.let { return it }
        return source.microChunk(p).also { lastChunk = it }
    }

    companion object {
        /** Deepest a body looks for ground below it: a long fall, but not to the bottom of the world. */
        const val MAX_DROP = 4 * 48
    }
}
