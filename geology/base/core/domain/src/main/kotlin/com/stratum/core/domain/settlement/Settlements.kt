package com.stratum.core.domain.settlement

import com.stratum.core.domain.actor.PackMember

/** What a building is for. Drives where a layout puts it and what the town does with it. */
enum class BuildingRole {
    HOUSE,
    SHOP,
    SMITHY,
    TAVERN,
    TEMPLE,
    BARRACKS,
    TOWER,
    WAREHOUSE,
    /** The centrepiece: a keep, a cathedral, a warboss's hut. At most one per town. */
    HALL,
    FARM,
}

/** Which way a building's door faces, in world terms. */
enum class Facing(val dx: Int, val dy: Int) {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0),
}

/**
 * A building a town can contain: a box with walls, a floor, a roof and a
 * door. Deliberately a box. Every settlement style this engine has been asked
 * for -- a medieval village, a gothic hive city, an orc camp, a frontier fort
 * -- reads from how boxes are sized, spaced, walled and named, and a box is
 * something a plugin author can describe in five fields.
 */
data class BuildingTemplate(
    val id: String,
    val name: String,
    val role: BuildingRole = BuildingRole.HOUSE,
    val width: Int = 5,
    val depth: Int = 5,
    /** Wall height in blocks, not counting the roof. */
    val height: Int = 3,
    val wallBlockId: String,
    val floorBlockId: String? = null,
    /** Null leaves it open to the sky: a yard, a ruin, a tent frame. */
    val roofBlockId: String? = null,
    /** Set into the walls at head height, every few blocks. */
    val windowBlockId: String? = null,
    /** A block placed in the middle of the floor: an anvil, an altar, a bed. */
    val furnitureBlockId: String? = null,
    val weight: Int = 100,
    /** How many a town must have, when it has room. */
    val minCount: Int = 0,
    val maxCount: Int = Int.MAX_VALUE,
) {
    init {
        require(width in MIN_SIZE..MAX_SIZE && depth in MIN_SIZE..MAX_SIZE) { "Building '$id' is ${width}x$depth; buildings are $MIN_SIZE..$MAX_SIZE a side" }
        require(height in 1..MAX_HEIGHT) { "Building '$id' is $height tall; the limit is $MAX_HEIGHT" }
    }

    companion object {
        const val MIN_SIZE = 3
        const val MAX_SIZE = 15
        const val MAX_HEIGHT = 10
    }
}

/**
 * How a kind of settlement is built, as plugin data.
 *
 * The recipe says what a town is made of -- its buildings, its roads, its
 * walls, who lives there and whose it is. [layoutId] says how those pieces
 * are arranged, by naming a layout the engine knows: a grid of streets, an
 * organic sprawl from a square, a walled fortress round a keep, a camp in
 * rings. Layouts are code and can be added by a code plugin; recipes are data
 * and can be written by anyone.
 */
data class SettlementRecipe(
    val id: String,
    /** Shown when the player enters, e.g. "Hive Primus". Towns draw a name from [names] when it is not empty. */
    val name: String,
    val layoutId: String = ORGANIC,
    val factionId: String? = null,
    val biomeIds: List<String> = emptyList(),
    /** Radius range in blocks. */
    val minRadius: Int = 18,
    val maxRadius: Int = 28,
    /** Share of candidate sites that get one of these, 0..1. */
    val chance: Float = 0.35f,
    val roadBlockId: String,
    /** Laid where nothing else is: the ground between buildings. Null keeps the land's own surface. */
    val groundBlockId: String? = null,
    /** Levelled under the whole town. */
    val foundationBlockId: String,
    val wallBlockId: String? = null,
    val wallHeight: Int = 4,
    val buildings: List<BuildingTemplate> = emptyList(),
    val names: List<String> = emptyList(),
    /** Who holds the town. Hostile to the player, they make it a stronghold to take. */
    val garrison: List<PackMember> = emptyList(),
    val weight: Int = 100,
) {
    init {
        require(minRadius in MIN_RADIUS..MAX_RADIUS && maxRadius in minRadius..MAX_RADIUS) {
            "Settlement '$id' radius $minRadius..$maxRadius is outside $MIN_RADIUS..$MAX_RADIUS"
        }
        require(chance in 0f..1f) { "Settlement '$id' chance $chance is not a share" }
        require(wallHeight in 1..BuildingTemplate.MAX_HEIGHT) { "Settlement '$id' wall height $wallHeight" }
    }

    /** Every block a recipe names, for load-time validation. */
    fun referencedBlockIds(): Set<String> = buildSet {
        add(roadBlockId)
        add(foundationBlockId)
        groundBlockId?.let(::add)
        wallBlockId?.let(::add)
        buildings.forEach { b -> listOfNotNull(b.wallBlockId, b.floorBlockId, b.roofBlockId, b.windowBlockId, b.furnitureBlockId).forEach(::add) }
    }

    companion object {
        const val GRID = "stratum:grid"
        const val ORGANIC = "stratum:organic"
        const val FORTRESS = "stratum:fortress"
        const val CAMP = "stratum:camp"
        const val MIN_RADIUS = 10
        /** Keeps a town inside one site cell with a margin, so two never overlap. */
        const val MAX_RADIUS = 56
    }
}

/** A straight strip of road, [width] blocks across, from one point to another. */
data class Road(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val width: Int = 3)

/** A building placed in a town, in world coordinates; ([x], [y]) is its north-west corner. */
data class PlacedBuilding(
    val template: BuildingTemplate,
    val x: Int,
    val y: Int,
    val width: Int,
    val depth: Int,
    val door: Facing,
) {
    fun contains(wx: Int, wy: Int): Boolean = wx in x until x + width && wy in y until y + depth

    /** The door's cell, on the wall that faces [door]. */
    val doorX: Int get() = when (door) {
        Facing.EAST -> x + width - 1
        Facing.WEST -> x
        else -> x + width / 2
    }
    val doorY: Int get() = when (door) {
        Facing.SOUTH -> y + depth - 1
        Facing.NORTH -> y
        else -> y + depth / 2
    }
}

/**
 * One town, fully decided: where it is, how high its ground is, and every
 * road, building and wall in it. Pure data, computed once per site from the
 * world seed, so every chunk that overlaps the town draws its part of the
 * same town.
 */
data class SettlementPlan(
    val id: String,
    val name: String,
    val recipe: SettlementRecipe,
    val centerX: Int,
    val centerY: Int,
    val groundZ: Int,
    val radius: Int,
    val roads: List<Road> = emptyList(),
    val buildings: List<PlacedBuilding> = emptyList(),
    /** Whether a wall rings the town, [radius] from the centre, with gaps where roads leave. */
    val walled: Boolean = recipe.wallBlockId != null,
    /** Square rather than round, for grid and fortress layouts. */
    val square: Boolean = false,
) {
    val factionId: String? get() = recipe.factionId

    fun contains(wx: Int, wy: Int, margin: Int = 0): Boolean = distance(wx, wy) <= radius + margin

    /** Distance from the centre, square or round as the plan's wall is. */
    fun distance(wx: Int, wy: Int): Float {
        val dx = (wx - centerX).toFloat()
        val dy = (wy - centerY).toFloat()
        return if (square) maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) else kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun buildingAt(wx: Int, wy: Int): PlacedBuilding? = buildings.firstOrNull { it.contains(wx, wy) }

    fun onRoad(wx: Int, wy: Int): Boolean = roads.any { road -> RoadGeometry.covers(road, wx, wy) }
}

/** The geometry of roads, shared by the planners that lay them and the stamper that paves them. */
object RoadGeometry {
    /** Whether (x, y) lies on [road]: within half its width of the segment. */
    fun covers(road: Road, x: Int, y: Int): Boolean {
        val half = road.width / 2f
        val px = x + 0.5f
        val py = y + 0.5f
        val ax = road.fromX + 0.5f
        val ay = road.fromY + 0.5f
        val dx = road.toX - road.fromX.toFloat()
        val dy = road.toY - road.fromY.toFloat()
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy <= half * half + EPSILON
    }

    private const val EPSILON = 0.01f
}

/**
 * The towns in a world, for everything that is not terrain: the HUD naming
 * where you are, the director keeping monsters out of friendly streets, the
 * garrison spawning in a hostile one.
 */
interface SettlementAtlas {
    /** Towns whose area comes within [radius] of (x, y). */
    fun settlementsNear(x: Int, y: Int, radius: Int): List<SettlementPlan>

    fun settlementAt(x: Int, y: Int): SettlementPlan? = settlementsNear(x, y, 0).firstOrNull { it.contains(x, y) }
}
