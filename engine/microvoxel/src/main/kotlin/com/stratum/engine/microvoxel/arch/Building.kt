package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.MaterialPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** "Leave this voxel as it is": a painter owns only the voxels it returns something else for. */
const val KEEP: Short = Short.MIN_VALUE

/** Which way a door faces, as a unit step in x or y. */
enum class Side(val dx: Int, val dy: Int) { NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0) }

/**
 * One building as a tradition sees it: a box of microvoxels, a door, and the
 * materials its plan gives it.
 *
 * The box is the plan's footprint, [r] microvoxels to a block. [wall],
 * [window] and [roof] are the plan's own blocks as materials. Painters keep
 * one promise with them, so the building plays exactly as planned when it
 * is read back into blocks: **inside a wall cell, every voxel but the outer
 * skin is [wall]**; a door cell is open two blocks high; a window cell is
 * [window]. The skin -- the outermost voxel of every wall -- and everything
 * outside the box, above the walls and in the rooms, is the tradition's to
 * shape and colour.
 */
class Building(
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    /** First microvoxel above the floor. */
    val base: Int,
    /** Last microvoxel of the walls. */
    val wallTop: Int,
    /** The door's block cell, and which way it faces. */
    val doorX: Int, val doorY: Int, val door: Side,
    val wall: Short,
    val window: Short?,
    val roof: Short?,
    val r: Int,
    /** A stable number for this building, for choices that must not change from chunk to chunk. */
    val seed: Long,
    /** Whether the plan puts a window in this block cell. */
    private val windowAt: (bx: Int, by: Int, bz: Int) -> Boolean,
    /** The town's own number, for looks a whole town shares (see [BuildingGenome]). */
    val town: Long = 0L,
) {
    val width get() = x1 - x0 + 1
    val depth get() = y1 - y0 + 1
    val height get() = wallTop - base + 1
    val cx: Float get() = (x0 + x1) / 2f
    val cy: Float get() = (y0 + y1) / 2f

    /** Small, square, with its door mid-wall: may be built round. */
    val roundable: Boolean
        get() {
            val w = width / r
            if (width != depth || w !in 3..5) return false
            return when (door) {
                Side.NORTH, Side.SOUTH -> doorX == x0 / r + w / 2
                Side.EAST, Side.WEST -> doorY == y0 / r + w / 2
            }
        }

    fun inside(x: Int, y: Int) = x in x0..x1 && y in y0..y1

    /** Voxels in from the outer face, 0 on the skin; negative outside. */
    fun edge(x: Int, y: Int): Int = min(min(x - x0, x1 - x), min(y - y0, y1 - y))

    /** Voxels out from the box, 0 inside it. */
    fun out(x: Int, y: Int): Int = max(max(x0 - x, x - x1), max(max(y0 - y, y - y1), 0))

    fun blockX(x: Int) = Math.floorDiv(x, r)
    fun blockY(y: Int) = Math.floorDiv(y, r)

    fun isDoor(x: Int, y: Int) = blockX(x) == doorX && blockY(y) == doorY

    /** The quarter-block jambs either side of the door, in the wall. */
    fun isJamb(x: Int, y: Int): Boolean = when (door) {
        Side.NORTH, Side.SOUTH -> blockY(y) == doorY && (x == doorX * r - 1 || x == doorX * r + r)
        Side.EAST, Side.WEST -> blockX(x) == doorX && (y == doorY * r - 1 || y == doorY * r + r)
    }

    fun isWindow(x: Int, y: Int, z: Int) = window != null && windowAt(blockX(x), blockY(y), Math.floorDiv(z, r))

    fun isCornerCell(x: Int, y: Int): Boolean {
        val bx = blockX(x); val by = blockY(y)
        return (bx == x0 / r || bx == x1 / r) && (by == y0 / r || by == y1 / r)
    }

    /** Which face a skin voxel is on, and how far along that face it is. */
    fun face(x: Int, y: Int): Side = when (edge(x, y)) {
        y - y0 -> Side.NORTH
        y1 - y -> Side.SOUTH
        x - x0 -> Side.WEST
        else -> Side.EAST
    }

    fun along(x: Int, y: Int): Int = when (face(x, y)) { Side.NORTH, Side.SOUTH -> x - x0; else -> y - y0 }

    /** Whether (x, y) is on the door's face. */
    fun onDoorFace(x: Int, y: Int) = face(x, y) == door

    /** Distance from the box's centre in the plane, for round forms. */
    fun radial(x: Int, y: Int): Float {
        val dx = x - cx; val dy = y - cy
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance outside the box's rectangle measured as a superellipse: rounded rectangles for domes and loaves. */
    fun squircle(x: Int, y: Int, p: Float = 4f): Float {
        val ax = abs(x - cx) / (width / 2f); val ay = abs(y - cy) / (depth / 2f)
        return Math.pow(Math.pow(ax.toDouble(), p.toDouble()) + Math.pow(ay.toDouble(), p.toDouble()), 1.0 / p).toFloat()
    }
}

/**
 * What a whole town of one tradition looks like around its buildings: the
 * compound wall, the yards, and what stands in the square.
 */
data class TownStyle(
    val wall: WallStyle,
    /** The swept ground between buildings, when the recipe leaves it to the tradition; null keeps the recipe's. */
    val yard: String? = null,
    /** Footworn patches on the yard. */
    val beaten: Boolean = true,
    val sacred: Sacred = Sacred.NONE,
)

/** How a compound or town wall is built. */
enum class WallStyle {
    /** Mud with a thatch coping (Igbo, Hausa, Asante): the recipe's wall block. */
    MUD_COPED,
    /** Coursed dry-stone granite with a chevron frieze (Great Zimbabwe). */
    DRYSTONE,
    /** Rammed earth with crenellations and battered towers (Amazigh ksour). */
    PISE_CRENELLATED,
    /** Adobe with rounded pinnacles over each buttress (Sudano-Sahelian). */
    ADOBE_PINNACLED,
    /** Lime-washed coral stone with stepped merlons (Swahili). */
    LIME_MERLONED,
    /** A ring of thorn branches (Maasai enkang, Zulu cattle kraal). */
    THORN,
    /** The recipe's wall, as it is. */
    PLAIN,
}

/** What stands at the heart of a town. */
enum class Sacred {
    NONE,
    /** A great iroko the town meets under (Igbo, Yoruba, Asante). */
    IROKO,
    /** A baobab: the Sahel's meeting and market tree. */
    BAOBAB,
    /** A conical dry-stone tower, like the Great Enclosure's. */
    CONICAL_TOWER,
    /** A carved stele with false doors and windows, like Aksum's. */
    STELE,
    /** A pillar tomb, like those of Gedi and Malindi. */
    PILLAR_TOMB,
    /** A circular cattle kraal of thorn or stone at the centre of the homestead (Zulu isibaya, Maasai). */
    KRAAL,
    /** A date palm over a well: the oasis ksar's heart. */
    DATE_PALM,
    /** A toguna: the Dogon men's shelter, a thick millet roof on carved pillars. */
    TOGUNA,
}

/**
 * A building tradition: how its people build, drawn voxel by voxel.
 *
 * [voxel] is a pure function of the building and the position, so any
 * chunk can draw its part of any building.
 */
interface Tradition {
    val id: String
    val name: String
    /** Where and when: the history a player can read in the World panel. */
    val origin: String
    val town: TownStyle
    /** How far outside its box a building may draw: eaves, buttresses, verandas. */
    val reach: Int
    /** The highest voxel it may draw for [b]: roofs, pinnacles, turrets. */
    fun top(b: Building): Int
    fun voxel(b: Building, x: Int, y: Int, z: Int): Short
}

/** Material ids a tradition draws with, resolved once per world. */
class ArchPalette(private val palette: MaterialPalette) {
    operator fun get(name: String): Short = palette.id(name)
    val air: Short = MaterialPalette.AIR
}
