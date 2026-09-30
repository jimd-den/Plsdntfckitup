package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.PlacedBuilding
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import kotlin.math.roundToInt

/**
 * Writes the part of a town that falls inside one chunk.
 *
 * Column by column, and reading only the plan and the chunk's own natural
 * ground, so it obeys the same rule as every other generation pass: no chunk
 * looks at its neighbours, and towns come out identical in any order.
 */
object SettlementStamp {

    fun stamp(chunk: Chunk, plan: SettlementPlan, registry: BlockRegistry) {
        val blocks = TownBlocks.resolve(plan, registry) ?: return
        for (localY in 0 until Chunk.SIZE) for (localX in 0 until Chunk.SIZE) {
            val worldX = chunk.pos.originX + localX
            val worldY = chunk.pos.originY + localY
            val distance = plan.distance(worldX, worldY)
            when {
                distance <= plan.radius -> townColumn(chunk, localX, localY, worldX, worldY, distance, plan, blocks)
                distance <= plan.radius + SettlementPlanner.BLEND -> blendColumn(chunk, localX, localY, worldX, worldY, distance, plan, blocks)
            }
        }
    }

    /** Inside the town: level ground, then whatever stands on this spot. */
    private fun townColumn(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, distance: Float, plan: SettlementPlan, blocks: TownBlocks) {
        val natural = chunk.surfaceAt(x, y)
        val naturalTop = if (natural >= 0) chunk.blockAt(x, y, natural) else blocks.foundation
        val building = plan.buildingAt(wx, wy)
        val road = plan.onRoad(wx, wy)
        val top = when {
            building != null -> blocks.floorOf(building) ?: blocks.foundation
            road -> blocks.road
            else -> blocks.ground ?: naturalTop
        }
        level(chunk, x, y, plan.groundZ, blocks.foundation, blocks.solidOr(top, naturalTop))
        overlay(chunk, x, y, plan.groundZ, top, blocks)
        when {
            building != null -> BuildingColumn.write(chunk, x, y, wx, wy, building, plan.groundZ, blocks)
            plan.walled && !road && distance > plan.radius - WALL_THICKNESS -> wall(chunk, x, y, wx, wy, plan, blocks)
        }
    }

    /** Outside the wall: the town's level eases back into the land over [SettlementPlanner.BLEND] blocks. */
    private fun blendColumn(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, distance: Float, plan: SettlementPlan, blocks: TownBlocks) {
        val natural = chunk.surfaceAt(x, y)
        if (natural < 0) return
        val t = ((distance - plan.radius) / SettlementPlanner.BLEND).coerceIn(0f, 1f)
        val target = (plan.groundZ + (natural - plan.groundZ) * t).roundToInt()
        val naturalTop = chunk.blockAt(x, y, natural)
        val top = if (plan.onRoad(wx, wy)) blocks.road else naturalTop
        level(chunk, x, y, target, blocks.foundation, blocks.solidOr(top, naturalTop))
        overlay(chunk, x, y, target, top, blocks)
    }

    /**
     * A floor-shaped block -- paving, a rug, a boardwalk -- is laid on top of
     * the ground rather than replacing it, so a paved street is walked at the
     * same height as the grass beside it.
     */
    private fun overlay(chunk: Chunk, x: Int, y: Int, z: Int, top: Int, blocks: TownBlocks) {
        if (!blocks.isSolid(top) && z + 1 < Chunk.HEIGHT) chunk.setBlock(x, y, z + 1, top)
    }

    /** Fills the column up to [z] with [fill], tops it with [top], and clears everything above. */
    private fun level(chunk: Chunk, x: Int, y: Int, z: Int, fill: Int, top: Int) {
        for (depth in (z - FOUNDATION_DEPTH).coerceAtLeast(1) until z) {
            if (chunk.blockAt(x, y, depth) == BlockRegistry.AIR_INDEX) chunk.setBlock(x, y, depth, fill)
        }
        chunk.setBlock(x, y, z, top)
        for (above in z + 1 until Chunk.HEIGHT) chunk.setBlock(x, y, above, BlockRegistry.AIR_INDEX)
    }

    /** The town wall, with a merlon on every other block so it reads as a wall and not a cliff. */
    private fun wall(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, plan: SettlementPlan, blocks: TownBlocks) {
        val wallBlock = blocks.wall ?: return
        val height = plan.recipe.wallHeight + if ((wx + wy) % 2 == 0) 1 else 0
        for (z in plan.groundZ + 1..(plan.groundZ + height).coerceAtMost(Chunk.HEIGHT - 1)) chunk.setBlock(x, y, z, wallBlock)
    }

    private const val FOUNDATION_DEPTH = 6
    private const val WALL_THICKNESS = 1.5f
}

/** One column of a building: floor, walls with a door and windows, a pitched roof, furniture. */
internal object BuildingColumn {

    fun write(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, b: PlacedBuilding, groundZ: Int, blocks: TownBlocks) {
        val top = groundZ + b.template.height
        val onEdge = wx == b.x || wx == b.x + b.width - 1 || wy == b.y || wy == b.y + b.depth - 1
        val isDoor = wx == b.doorX && wy == b.doorY
        if (onEdge && !isDoor) walls(chunk, x, y, wx, wy, b, groundZ, top, blocks)
        if (isDoor) doorway(chunk, x, y, groundZ, top, b, blocks)
        if (!onEdge && wx == b.x + b.width / 2 && wy == b.y + b.depth / 2) blocks.furnitureOf(b)?.let { chunk.setBlock(x, y, groundZ + 1, it) }
        roof(chunk, x, y, wx, wy, b, top, blocks)
    }

    private fun walls(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, b: PlacedBuilding, groundZ: Int, top: Int, blocks: TownBlocks) {
        val wall = blocks.wallOf(b)
        val window = blocks.windowOf(b)
        val corner = (wx == b.x || wx == b.x + b.width - 1) && (wy == b.y || wy == b.y + b.depth - 1)
        for (z in groundZ + 1..top.coerceAtMost(Chunk.HEIGHT - 1)) {
            val windowHere = window != null && !corner && z == groundZ + WINDOW_HEIGHT && (wx + wy) % WINDOW_EVERY == 0
            chunk.setBlock(x, y, z, if (windowHere) window!! else wall)
        }
    }

    /** Two blocks of opening, wall above it to the eaves. */
    private fun doorway(chunk: Chunk, x: Int, y: Int, groundZ: Int, top: Int, b: PlacedBuilding, blocks: TownBlocks) {
        for (z in groundZ + 1 + DOOR_HEIGHT..top.coerceAtMost(Chunk.HEIGHT - 1)) chunk.setBlock(x, y, z, blocks.wallOf(b))
    }

    /**
     * A gabled roof: it rises toward a ridge along the building's long axis,
     * and the end walls are filled up to meet it. Flat roofs on boxes read as
     * crates from an isometric camera; a ridge reads as a house.
     */
    private fun roof(chunk: Chunk, x: Int, y: Int, wx: Int, wy: Int, b: PlacedBuilding, top: Int, blocks: TownBlocks) {
        val roof = blocks.roofOf(b) ?: return
        val ridgeAlongY = b.width <= b.depth
        val across = if (ridgeAlongY) wx - b.x else wy - b.y
        val span = if (ridgeAlongY) b.width else b.depth
        val rise = minOf(across, span - 1 - across).coerceAtLeast(0)
        val roofZ = top + 1 + rise
        if (roofZ >= Chunk.HEIGHT) return
        val gableEnd = if (ridgeAlongY) wy == b.y || wy == b.y + b.depth - 1 else wx == b.x || wx == b.x + b.width - 1
        if (gableEnd) for (z in top + 1 until roofZ) chunk.setBlock(x, y, z, blocks.wallOf(b))
        chunk.setBlock(x, y, roofZ, roof)
    }

    private const val DOOR_HEIGHT = 2
    private const val WINDOW_HEIGHT = 2
    private const val WINDOW_EVERY = 3
}

/** A town's block ids resolved to registry indices once, rather than per column. */
internal class TownBlocks private constructor(
    val road: Int,
    val foundation: Int,
    val ground: Int?,
    val wall: Int?,
    private val registry: BlockRegistry,
) {
    private val cache = HashMap<String, Int?>()

    private fun index(id: String?): Int? = id?.let { cache.getOrPut(it) { registry.indexOrNull(it) } }

    fun isSolid(index: Int): Boolean = registry.typeOf(index).isSolid

    /** [block] when it can be stood on, else [fallback]: the ground under a floor laid on it. */
    fun solidOr(block: Int, fallback: Int): Int = if (isSolid(block)) block else if (isSolid(fallback)) fallback else foundation

    fun wallOf(b: PlacedBuilding): Int = index(b.template.wallBlockId) ?: foundation
    fun floorOf(b: PlacedBuilding): Int? = index(b.template.floorBlockId)
    fun roofOf(b: PlacedBuilding): Int? = index(b.template.roofBlockId)
    fun windowOf(b: PlacedBuilding): Int? = index(b.template.windowBlockId)
    fun furnitureOf(b: PlacedBuilding): Int? = index(b.template.furnitureBlockId)

    companion object {
        /** Null when the recipe's essential blocks are not loaded; the town is then skipped rather than half built. */
        fun resolve(plan: SettlementPlan, registry: BlockRegistry): TownBlocks? {
            val recipe = plan.recipe
            val road = registry.indexOrNull(recipe.roadBlockId) ?: return null
            val foundation = registry.indexOrNull(recipe.foundationBlockId) ?: return null
            return TownBlocks(road, foundation, recipe.groundBlockId?.let(registry::indexOrNull), recipe.wallBlockId?.let(registry::indexOrNull), registry)
        }
    }
}
