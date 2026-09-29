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
            // Erasing works on the region the drag covers, top to bottom of the
            // walls you would have built there: a mistake is usually a wall.
            BuildTool.ERASE -> box(from, to, height)
            BuildTool.BOX -> box(from, to, height)
            BuildTool.PILLAR -> (0 until height).map { BlockPos(to.x, to.y, to.z + it) }
            BuildTool.STAIRS -> stairs(from, to)
            BuildTool.ROOF -> roof(from, to)
            BuildTool.DOME -> dome(from, to)
            BuildTool.RING -> ring(from, to, height)
            // Repainting takes the surface the drag covers, and as many layers under it as the height asks.
            BuildTool.PAINT -> box(from.below(height - 1), to.below(height - 1), height)
            // Digging goes down from the ground the drag started on.
            BuildTool.DIG -> box(from.below(height - 1), to.below(height - 1), height)
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
    /** Every cell in the box the drag spans, from the anchor's level up. */
    private fun box(from: BlockPos, to: BlockPos, height: Int): List<BlockPos> {
        val baseZ = min(from.z, to.z)
        return buildList {
            for (z in baseZ until baseZ + height) {
                for (y in min(from.y, to.y)..max(from.y, to.y)) {
                    for (x in min(from.x, to.x)..max(from.x, to.x)) add(BlockPos(x, y, z))
                }
            }
        }
    }

    /**
     * A flight of steps up the drag's long axis, as wide as the drag is
     * across it, each step solid down to where the flight starts: walkable
     * from either end without a jump.
     */
    private fun stairs(from: BlockPos, to: BlockPos): List<BlockPos> {
        val dx = to.x - from.x; val dy = to.y - from.y
        val alongX = abs(dx) >= abs(dy)
        val run = if (alongX) abs(dx) else abs(dy)
        val step = if ((if (alongX) dx else dy) >= 0) 1 else -1
        val across = if (alongX) min(from.y, to.y)..max(from.y, to.y) else min(from.x, to.x)..max(from.x, to.x)
        return buildList {
            for (i in 0..run) for (c in across) for (z in from.z..from.z + i) {
                add(if (alongX) BlockPos(from.x + i * step, c, z) else BlockPos(c, from.y + i * step, z))
            }
        }
    }

    /**
     * A gable roof over the rectangle, its ridge along the long side, rising a
     * block per block in from the eaves; the gable ends are closed.
     */
    private fun roof(from: BlockPos, to: BlockPos): List<BlockPos> {
        val minX = min(from.x, to.x); val maxX = max(from.x, to.x)
        val minY = min(from.y, to.y); val maxY = max(from.y, to.y)
        val ridgeAlongY = maxX - minX <= maxY - minY
        val span = if (ridgeAlongY) maxX - minX + 1 else maxY - minY + 1
        return buildList {
            for (y in minY..maxY) for (x in minX..maxX) {
                val a = if (ridgeAlongY) x - minX else y - minY
                val end = if (ridgeAlongY) y == minY || y == maxY else x == minX || x == maxX
                val rise = min(a, span - 1 - a)
                // The slope, and under it at each end the triangle that closes the gable.
                add(BlockPos(x, y, to.z + rise))
                if (end) for (z in to.z until to.z + rise) add(BlockPos(x, y, z))
            }
        }
    }

    /** A dome over the rectangle: a shell one block thick, as high as it is half wide. */
    private fun dome(from: BlockPos, to: BlockPos): List<BlockPos> {
        val cx = (from.x + to.x) / 2f; val cy = (from.y + to.y) / 2f
        val rx = abs(to.x - from.x) / 2f + 0.5f; val ry = abs(to.y - from.y) / 2f + 0.5f
        val rz = min(rx, ry)
        val inner = 1f - 1.2f / min(rx, ry).coerceAtLeast(1f)
        return buildList {
            for (z in 0..rz.toInt()) for (y in min(from.y, to.y)..max(from.y, to.y)) for (x in min(from.x, to.x)..max(from.x, to.x)) {
                val d = kotlin.math.sqrt(((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it } + ((z + 0.5f) / rz).let { it * it })
                if (d <= 1f && d >= inner) add(BlockPos(x, y, to.z + z))
            }
        }
    }

    /** A round wall: the ellipse the rectangle holds, raised [height] blocks. A tower, a kraal, a hut. */
    private fun ring(from: BlockPos, to: BlockPos, height: Int): List<BlockPos> {
        val cx = (from.x + to.x) / 2f; val cy = (from.y + to.y) / 2f
        val rx = abs(to.x - from.x) / 2f + 0.5f; val ry = abs(to.y - from.y) / 2f + 0.5f
        val inner = 1f - 1.3f / min(rx, ry).coerceAtLeast(1f)
        return buildList {
            for (level in 0 until height) for (y in min(from.y, to.y)..max(from.y, to.y)) for (x in min(from.x, to.x)..max(from.x, to.x)) {
                val d = kotlin.math.sqrt(((x + 0.5f - cx - 0.5f) / rx).let { it * it } + ((y + 0.5f - cy - 0.5f) / ry).let { it * it })
                if (d <= 1f && d >= inner) add(BlockPos(x, y, to.z + level))
            }
        }
    }

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

    /**
     * Removes what the drag covers and hands it back.
     *
     * Digging one cell at a time is right for mining and wrong for undoing a
     * wall you placed in the wrong spot: fixing a mistake should cost the same
     * one gesture that made it.
     */
    ERASE("Erase", true),

    /** A solid block of the selected block: a plinth, a platform, a wall of a given thickness. */
    BOX("Fill", true),

    /** A column [height] blocks tall, from a tap. */
    PILLAR("Pillar", false),

    /** A flight of steps up the drag, as wide as the drag is across. */
    STAIRS("Stairs", true),

    /** A gable roof over the rectangle. */
    ROOF("Roof", true),

    /** A dome over the rectangle. */
    DOME("Dome", true),

    /** A round wall in the rectangle. */
    RING("Round", true),

    /** Swaps what is there for the selected block, handing back what it replaced. */
    PAINT("Paint", true),

    /**
     * Digs the ground out, down from where the drag started: a cellar, a
     * pond, a moat, in one gesture, every block handed back.
     */
    DIG("Dig", true),
    ;

    /** Whether committing this tool removes blocks rather than placing them. */
    val removes: Boolean get() = this == ERASE || this == DIG

    /** Whether it swaps blocks already there rather than filling air. */
    val replaces: Boolean get() = this == PAINT

    /** Whether its shape reads the build height. */
    val usesHeight: Boolean get() = this in setOf(WALLS, ROOM, ERASE, BOX, PILLAR, RING, PAINT, DIG)
}
