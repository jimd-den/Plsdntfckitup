package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a drag into a set of blocks to place.
 *
 * Placing a house one cube at a time on a touchscreen is miserable, so a build
 * is described as a shape between two corners and resolved here. The planner is
 * pure: it says what *would* be placed, which is exactly what the ghost preview
 * needs to draw before anything is committed.
 */
object BuildPlanner {

    /** The most blocks one drag may place, so a mis-drag cannot flatten a region. */
    const val MAX_PLAN_SIZE = 2048

    fun plan(tool: BuildTool, from: BlockPos, to: BlockPos, height: Int = DEFAULT_WALL_HEIGHT): List<BlockPos> {
        val positions = when (tool) {
            BuildTool.SINGLE -> listOf(to)
            BuildTool.LINE -> line(from, to)
            BuildTool.FLOOR -> floor(from, to, to.z)
            BuildTool.WALLS -> walls(from, to, height)
            BuildTool.ROOM -> room(from, to, height)
        }
        return positions.distinct().take(MAX_PLAN_SIZE)
    }

    /**
     * A straight run, snapped to whichever axis the drag mostly follows.
     *
     * Snapping rather than drawing a true diagonal: a diagonal line of cubes
     * makes a staircase that is neither a wall nor a path, and nobody drags
     * perfectly straight on a phone.
     */
    private fun line(from: BlockPos, to: BlockPos): List<BlockPos> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return if (abs(dx) >= abs(dy)) {
            val step = if (dx >= 0) 1 else -1
            (0..abs(dx)).map { BlockPos(from.x + it * step, from.y, to.z) }
        } else {
            val step = if (dy >= 0) 1 else -1
            (0..abs(dy)).map { BlockPos(from.x, from.y + it * step, to.z) }
        }
    }

    private fun floor(from: BlockPos, to: BlockPos, z: Int): List<BlockPos> {
        val minX = min(from.x, to.x)
        val maxX = max(from.x, to.x)
        val minY = min(from.y, to.y)
        val maxY = max(from.y, to.y)
        return buildList {
            for (y in minY..maxY) {
                for (x in minX..maxX) add(BlockPos(x, y, z))
            }
        }
    }

    /** The perimeter of the rectangle, raised [height] blocks. */
    private fun walls(from: BlockPos, to: BlockPos, height: Int): List<BlockPos> {
        val minX = min(from.x, to.x)
        val maxX = max(from.x, to.x)
        val minY = min(from.y, to.y)
        val maxY = max(from.y, to.y)

        return buildList {
            for (level in 0 until height) {
                val z = to.z + level
                for (x in minX..maxX) {
                    add(BlockPos(x, minY, z))
                    add(BlockPos(x, maxY, z))
                }
                for (y in minY..maxY) {
                    add(BlockPos(minX, y, z))
                    add(BlockPos(maxX, y, z))
                }
            }
        }
    }

    /**
     * Floor, walls and roof: a shell you can stand inside.
     *
     * The interior is left empty rather than filled, and a doorway is cut in the
     * middle of the longest wall. A sealed box you cannot enter is not a house,
     * and cutting the door by hand afterwards is the tedious part.
     */
    private fun room(from: BlockPos, to: BlockPos, height: Int): List<BlockPos> {
        val minX = min(from.x, to.x)
        val maxX = max(from.x, to.x)
        val minY = min(from.y, to.y)
        val maxY = max(from.y, to.y)

        // Too small to have an inside; fall back to a solid pad.
        if (maxX - minX < 2 || maxY - minY < 2) return floor(from, to, to.z)

        val shell = buildList {
            addAll(floor(from, to, to.z))
            addAll(walls(from, to, height))
            addAll(floor(from, to, to.z + height))
        }

        val doorway = doorwayFor(minX, maxX, minY, maxY, to.z)
        return shell.filterNot(doorway::contains)
    }

    /**
     * Two blocks tall, in the middle of the longer wall, so the opening reads as
     * deliberate and the player can actually walk through it.
     */
    private fun doorwayFor(minX: Int, maxX: Int, minY: Int, maxY: Int, baseZ: Int): Set<BlockPos> {
        val width = maxX - minX
        val depth = maxY - minY
        val doorZs = listOf(baseZ + 1, baseZ + 2)

        return if (width >= depth) {
            val midX = (minX + maxX) / 2
            doorZs.map { BlockPos(midX, minY, it) }.toSet()
        } else {
            val midY = (minY + maxY) / 2
            doorZs.map { BlockPos(minX, midY, it) }.toSet()
        }
    }

    private const val DEFAULT_WALL_HEIGHT = 3
}

/** How a drag is interpreted. */
enum class BuildTool(val label: String, val needsDrag: Boolean) {
    SINGLE("Block", false),
    LINE("Line", true),
    FLOOR("Floor", true),
    WALLS("Walls", true),
    ROOM("Room", true),
}
