package com.stratum.core.domain.world

/**
 * What part of its cell a block fills.
 *
 * Every block used to be a whole cube, which is right for terrain and wrong for
 * anything built. A house whose walls are a full block thick has rooms a full
 * block smaller than the footprint the player dragged out, corridors you cannot
 * stand beside the wall of, and a silhouette that reads as a bunker. A wall a
 * third of a block thick, standing in the middle of its cell, leaves the outer
 * two thirds of the cell walkable on either side — which is the difference
 * between building a fort and building a room.
 */
enum class BlockShape {
    /** Fills the cell. Terrain, and anything that should read as mass. */
    CUBE,

    /**
     * A third-of-a-block thick pane, full height, that joins its neighbours.
     *
     * Walls carry no orientation. They connect towards any neighbouring wall or
     * solid cube, the way panes and fences do, so dragging a line of them makes
     * a straight wall, a corner makes an L and a crossing makes a T — without
     * the player ever rotating anything, which on a touchscreen is the thing
     * that makes building miserable.
     */
    WALL,
}

/** An axis-aligned box inside one cell, in cell-local units of 0..1. */
data class ShapeBox(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    fun containsXY(x: Float, y: Float, margin: Float = 0f): Boolean =
        x >= minX - margin && x <= maxX + margin && y >= minY - margin && y <= maxY + margin

    companion object {
        val FULL = ShapeBox(0f, 0f, 0f, 1f, 1f, 1f)
    }
}

/**
 * The geometry of shaped blocks, shared by collision and rendering.
 *
 * One source for both on purpose. A wall that draws in one place and collides
 * in another is the most frustrating bug a building game can have, because the
 * player can see exactly where the wall is and is being told it is somewhere
 * else.
 */
object BlockShapes {

    /** How thick a wall is, as a share of a cell. */
    const val WALL_THICKNESS = 1f / 3f

    private const val LOW = (1f - WALL_THICKNESS) / 2f
    private const val HIGH = 1f - LOW

    /** Neighbour bits for [connections]. */
    const val NORTH = 1
    const val EAST = 2
    const val SOUTH = 4
    const val WEST = 8

    /**
     * Which sides of a wall cell join something.
     *
     * North is -y, east is +x, matching [Direction]. A wall joins another wall
     * or a full solid cube; it does not join air, props or other thin things
     * like torches, or a wall built against a tree would reach out and touch it.
     */
    fun connections(world: World, pos: BlockPos): Int {
        var mask = 0
        if (joins(world.blockAt(BlockPos(pos.x, pos.y - 1, pos.z)))) mask = mask or NORTH
        if (joins(world.blockAt(BlockPos(pos.x + 1, pos.y, pos.z)))) mask = mask or EAST
        if (joins(world.blockAt(BlockPos(pos.x, pos.y + 1, pos.z)))) mask = mask or SOUTH
        if (joins(world.blockAt(BlockPos(pos.x - 1, pos.y, pos.z)))) mask = mask or WEST
        return mask
    }

    /**
     * The boxes a block occupies, given what it connects to.
     *
     * A wall with nothing to join runs east to west rather than shrinking to a
     * post. A single placed wall should look like a piece of wall; a lone
     * pillar the size of a fence post is almost never what was meant.
     */
    fun boxes(shape: BlockShape, connections: Int): List<ShapeBox> = when (shape) {
        BlockShape.CUBE -> CUBE_BOXES
        BlockShape.WALL -> WALL_BOXES[if (connections == 0) EAST or WEST else connections]
    }

    /**
     * Whether a block at [pos] occupies the point at cell-local ([localX], [localY]).
     *
     * [margin] thickens the shape slightly for collision, so a body cannot slide
     * close enough to a wall that it visibly overlaps it.
     */
    fun occupies(world: World, pos: BlockPos, localX: Float, localY: Float, margin: Float = 0f): Boolean {
        val block = world.blockAt(pos)
        if (!block.isSolid) return false
        if (block.shape == BlockShape.CUBE) return true
        return boxes(block.shape, connections(world, pos)).any { it.containsXY(localX, localY, margin) }
    }

    private fun joins(neighbour: BlockType): Boolean =
        neighbour.shape == BlockShape.WALL || (neighbour.shape == BlockShape.CUBE && neighbour.isSolid && neighbour.glyph == null)

    private val CUBE_BOXES = listOf(ShapeBox.FULL)

    /** Every connection pattern precomputed: sixteen lists, built once. */
    private val WALL_BOXES: Array<List<ShapeBox>> = Array(16) { mask ->
        buildList {
            add(ShapeBox(LOW, LOW, 0f, HIGH, HIGH, 1f))
            if (mask and NORTH != 0) add(ShapeBox(LOW, 0f, 0f, HIGH, LOW, 1f))
            if (mask and SOUTH != 0) add(ShapeBox(LOW, HIGH, 0f, HIGH, 1f, 1f))
            if (mask and WEST != 0) add(ShapeBox(0f, LOW, 0f, LOW, HIGH, 1f))
            if (mask and EAST != 0) add(ShapeBox(HIGH, LOW, 0f, 1f, HIGH, 1f))
        }
    }
}
