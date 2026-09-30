package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.Facing
import com.stratum.core.domain.settlement.PlacedBuilding
import com.stratum.core.domain.settlement.Road
import com.stratum.core.domain.settlement.RoadGeometry
import com.stratum.core.domain.settlement.SettlementRecipe
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Places buildings into a town without overlaps: inside the wall, off the
 * roads, clear of the central square, a block apart, each facing the street
 * it stands on. Shared by every layout, so a new layout only has to say where
 * its streets run and where it would like buildings to go.
 */
internal class LotPacker(
    private val site: SettlementSite,
    private val recipe: SettlementRecipe,
    private val random: Random,
    private val roads: List<Road>,
    private val square: Boolean,
    /** Kept empty around the centre: where the player arrives and the town gathers. */
    private val plazaRadius: Int,
) {
    val placed = mutableListOf<PlacedBuilding>()
    private val counts = HashMap<String, Int>()
    private val margin = if (recipe.wallBlockId != null) WALL_MARGIN else OPEN_MARGIN

    /** Places [template] with its door facing [door], its north-west corner at ([x], [y]), if it fits. */
    fun place(template: BuildingTemplate, x: Int, y: Int, door: Facing): Boolean {
        val (width, depth) = footprint(template, door)
        if (!fits(x, y, width, depth)) return false
        placed += PlacedBuilding(template, x, y, width, depth, door)
        counts[template.id] = (counts[template.id] ?: 0) + 1
        return true
    }

    /** Places a building centred on ([cx], [cy]). */
    fun placeCentred(template: BuildingTemplate, cx: Float, cy: Float, door: Facing): Boolean {
        val (width, depth) = footprint(template, door)
        return place(template, (cx - width / 2f).roundToInt(), (cy - depth / 2f).roundToInt(), door)
    }

    /**
     * Lines both sides of every road with buildings, doors to the street,
     * until the town runs out of room or of buildings it is allowed.
     */
    fun lineRoads(roads: List<Road> = this.roads) {
        roads.forEach { road -> Side.entries.forEach { side -> lineSide(road, side) } }
    }

    /** The next template to place: required ones first, then by weight among those under their limit. */
    fun nextTemplate(exclude: Set<BuildingRole> = setOf(BuildingRole.HALL)): BuildingTemplate? {
        val candidates = recipe.buildings.filter { it.role !in exclude && count(it) < it.maxCount }
        candidates.firstOrNull { count(it) < it.minCount }?.let { return it }
        val total = candidates.sumOf { it.weight.coerceAtLeast(0) }
        if (total <= 0) return candidates.firstOrNull()
        var roll = random.nextInt(total)
        return candidates.firstOrNull { roll -= it.weight.coerceAtLeast(0); roll < 0 }
    }

    fun hall(): BuildingTemplate? = recipe.buildings.firstOrNull { it.role == BuildingRole.HALL }

    private fun count(template: BuildingTemplate) = counts[template.id] ?: 0

    private enum class Side(val sign: Int) { LEFT(1), RIGHT(-1) }

    private fun lineSide(road: Road, side: Side) {
        val dx = (road.toX - road.fromX).toFloat()
        val dy = (road.toY - road.fromY).toFloat()
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return
        val ux = dx / length
        val uy = dy / length
        // The side's outward normal: the building stands along it, its door faces back.
        val nx = -uy * side.sign
        val ny = ux * side.sign
        val door = facingToward(-nx, -ny)
        var t = STREET_INSET
        var misses = 0
        while (t < length - STREET_INSET && placed.size < MAX_BUILDINGS && misses < MAX_MISSES) {
            val template = nextTemplate() ?: return
            val (width, depth) = footprint(template, door)
            val alongNormal = if (door == Facing.NORTH || door == Facing.SOUTH) depth else width
            val alongRoad = if (door == Facing.NORTH || door == Facing.SOUTH) width else depth
            val offset = road.width / 2f + SETBACK + alongNormal / 2f
            val cx = road.fromX + 0.5f + ux * (t + alongRoad / 2f) + nx * offset
            val cy = road.fromY + 0.5f + uy * (t + alongRoad / 2f) + ny * offset
            if (placeCentred(template, cx, cy, door)) {
                t += alongRoad + GAP
                misses = 0
            } else {
                t += STEP
                misses++
            }
        }
    }

    private fun fits(x: Int, y: Int, width: Int, depth: Int): Boolean {
        for (wy in y until y + depth) for (wx in x until x + width) {
            if (distance(wx, wy) > site.radius - margin) return false
            if (distance(wx, wy) <= plazaRadius) return false
            if (roads.any { RoadGeometry.covers(it, wx, wy) }) return false
        }
        return placed.none { other ->
            x < other.x + other.width + GAP && other.x < x + width + GAP &&
                y < other.y + other.depth + GAP && other.y < y + depth + GAP
        }
    }

    private fun distance(wx: Int, wy: Int): Float {
        val dx = (wx - site.centerX).toFloat()
        val dy = (wy - site.centerY).toFloat()
        return if (square) maxOf(abs(dx), abs(dy)) else sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** A building's footprint on the ground: width along x, depth along y, turned to face its door. */
        fun footprint(template: BuildingTemplate, door: Facing): Pair<Int, Int> =
            if (door == Facing.NORTH || door == Facing.SOUTH) template.width to template.depth else template.depth to template.width

        /** The compass facing nearest a direction. */
        fun facingToward(dx: Float, dy: Float): Facing = when {
            abs(dx) >= abs(dy) -> if (dx >= 0) Facing.EAST else Facing.WEST
            else -> if (dy >= 0) Facing.SOUTH else Facing.NORTH
        }

        private const val GAP = 1
        private const val SETBACK = 1
        private const val STEP = 2f
        private const val STREET_INSET = 2f
        private const val WALL_MARGIN = 3
        private const val OPEN_MARGIN = 1
        private const val MAX_MISSES = 6
        const val MAX_BUILDINGS = 64
    }
}
