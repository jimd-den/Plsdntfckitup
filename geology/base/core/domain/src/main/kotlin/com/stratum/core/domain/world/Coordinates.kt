package com.stratum.core.domain.world

import kotlin.math.floor

/**
 * Block-space coordinates. `x` runs east, `y` runs south, `z` runs up.
 *
 * The world is a true voxel grid: columns of blocks stacked on the z axis. The
 * isometric camera is a projection choice made at render time, never something
 * the simulation knows about.
 */
data class BlockPos(val x: Int, val y: Int, val z: Int) {

    fun offset(direction: Direction, distance: Int = 1): BlockPos = BlockPos(
        x + direction.dx * distance,
        y + direction.dy * distance,
        z + direction.dz * distance,
    )

    fun above(distance: Int = 1): BlockPos = copy(z = z + distance)

    fun below(distance: Int = 1): BlockPos = copy(z = z - distance)

    /** Chebyshev distance on the horizontal plane, which is how reach is measured. */
    fun horizontalDistanceTo(other: BlockPos): Int =
        maxOf(kotlin.math.abs(x - other.x), kotlin.math.abs(y - other.y))

    val chunkPos: ChunkPos
        get() = ChunkPos(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(y, Chunk.SIZE))

    /** Index of this position inside its own chunk's backing array. */
    val localIndex: Int
        get() = Chunk.indexOf(Math.floorMod(x, Chunk.SIZE), Math.floorMod(y, Chunk.SIZE), z)

    companion object {
        val ORIGIN = BlockPos(0, 0, 0)
    }
}

/** Identifies a chunk by its position on the chunk grid, not the block grid. */
data class ChunkPos(val x: Int, val y: Int) {

    /** World-space block coordinate of this chunk's north-west corner. */
    val originX: Int get() = x * Chunk.SIZE

    val originY: Int get() = y * Chunk.SIZE

    fun distanceTo(other: ChunkPos): Int =
        maxOf(kotlin.math.abs(x - other.x), kotlin.math.abs(y - other.y))

    fun neighbours(): List<ChunkPos> = listOf(
        ChunkPos(x - 1, y), ChunkPos(x + 1, y),
        ChunkPos(x, y - 1), ChunkPos(x, y + 1),
    )

    companion object {
        fun containing(x: Int, y: Int) = ChunkPos(Math.floorDiv(x, Chunk.SIZE), Math.floorDiv(y, Chunk.SIZE))
    }
}

/**
 * Continuous position used by entities. Blocks are discrete; actors are not, so
 * movement, collision and camera follow all happen in this space and only snap
 * to [BlockPos] when they touch the grid.
 */
data class WorldPoint(val x: Float, val y: Float, val z: Float) {

    fun toBlockPos(): BlockPos = BlockPos(
        floor(x.toDouble()).toInt(),
        floor(y.toDouble()).toInt(),
        floor(z.toDouble()).toInt(),
    )

    fun translated(dx: Float, dy: Float, dz: Float) = WorldPoint(x + dx, y + dy, z + dz)

    fun distanceSquaredTo(other: WorldPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    companion object {
        val ZERO = WorldPoint(0f, 0f, 0f)

        /** Centre of the given block, which is where entities actually stand. */
        fun centerOf(pos: BlockPos) = WorldPoint(pos.x + 0.5f, pos.y + 0.5f, pos.z.toFloat())
    }
}

/** The six block faces, used for placement, support checks and light spread. */
enum class Direction(val dx: Int, val dy: Int, val dz: Int) {
    NORTH(0, -1, 0),
    SOUTH(0, 1, 0),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    UP(0, 0, 1),
    DOWN(0, 0, -1);

    val opposite: Direction
        get() = when (this) {
            NORTH -> SOUTH
            SOUTH -> NORTH
            EAST -> WEST
            WEST -> EAST
            UP -> DOWN
            DOWN -> UP
        }

    val isHorizontal: Boolean get() = dz == 0

    companion object {
        val HORIZONTAL = listOf(NORTH, EAST, SOUTH, WEST)
    }
}
