package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What a patch of ground offers a fighter, read from the blocks.
 *
 * - **Vantage**: standing three or more blocks above the player with a clear
 *   line to them. Archers and casters want it.
 * - **Choke**: a passage two blocks wide or narrower, walls on both sides and
 *   open ground along it. A brute in one is a door.
 * - **Concealed**: no line from the player's eyes, behind a rise, a wall or
 *   brush. Swarmers wait there.
 * - **Flank**: off the player's shoulder or behind them, where they are not
 *   looking.
 */
enum class TerrainFeature { VANTAGE, CHOKE, CONCEALED, FLANK, OPEN }

/** One column someone can stand on: where, how high above the player, and what it offers. */
data class TacticalSpot(
    val at: WorldPoint,
    /** Blocks above (positive) or below the player's feet. */
    val rise: Int,
    /** Horizontal distance to the player. */
    val distance: Float,
    /** Bearing from the player, radians, 0 along +x. */
    val bearing: Float,
    val features: Set<TerrainFeature>,
) {
    fun has(feature: TerrainFeature) = feature in features
}

/**
 * Reads the ground around a point into [TacticalSpot]s.
 *
 * Pure over a [World]: the same blocks always profile the same, so a fight
 * placed from a seed replays exactly. A profile is a few hundred columns and
 * a ray each, cheap next to the spawn it serves.
 */
class TacticalTerrain(private val world: World) {

    /**
     * Every standable column in the ring between [inner] and [outer] blocks
     * of [focus], profiled against a player standing there and looking
     * along [facing] (radians; null when it is not known, and nothing is a flank).
     */
    fun profile(focus: WorldPoint, inner: Float, outer: Float, facing: Float? = null): List<TacticalSpot> {
        val r = ceil(outer).toInt()
        val fx = floor(focus.x).toInt(); val fy = floor(focus.y).toInt()
        val feet = floor(focus.z).toInt()
        val out = ArrayList<TacticalSpot>()
        for (dx in -r..r) for (dy in -r..r) {
            val d = sqrt((dx * dx + dy * dy).toFloat())
            if (d < inner || d > outer) continue
            val x = fx + dx; val y = fy + dy
            val ground = standable(x, y) ?: continue
            out += spot(focus, feet, x, y, ground, facing)
        }
        return out
    }

    /** One column's profile, or null when nothing can stand there. */
    fun spotAt(focus: WorldPoint, x: Int, y: Int, facing: Float? = null): TacticalSpot? {
        val ground = standable(x, y) ?: return null
        return spot(focus, floor(focus.z).toInt(), x, y, ground, facing)
    }

    private fun spot(focus: WorldPoint, feet: Int, x: Int, y: Int, ground: Int, facing: Float?): TacticalSpot {
        val at = WorldPoint(x + 0.5f, y + 0.5f, ground + 1f)
        val rise = ground + 1 - feet
        val dx = at.x - focus.x; val dy = at.y - focus.y
        val bearing = atan2(dy, dx)
        val features = HashSet<TerrainFeature>(4)
        val seen = lineOfSight(focus, at)
        if (rise >= VANTAGE_RISE && seen) features += TerrainFeature.VANTAGE
        if (!seen || brushAt(x, y, ground)) features += TerrainFeature.CONCEALED
        if (choke(x, y, ground)) features += TerrainFeature.CHOKE
        if (facing != null && abs(angleBetween(facing, bearing)) > FLANK_ANGLE) features += TerrainFeature.FLANK
        if (features.isEmpty()) features += TerrainFeature.OPEN
        return TacticalSpot(at, rise, sqrt(dx * dx + dy * dy), bearing, features)
    }

    /** The surface of a column with two clear blocks above it, or null. */
    fun standable(x: Int, y: Int): Int? {
        val surface = world.surfaceAt(x, y)
        if (surface < 0 || surface >= Chunk.HEIGHT - 3) return null
        val under = world.blockAt(BlockPos(x, y, surface))
        if (under.material == BlockMaterial.LIQUID) return null
        if (world.isSolid(BlockPos(x, y, surface + 1)) || world.isSolid(BlockPos(x, y, surface + 2))) return null
        return surface
    }

    /** Can a body step from a column at [ground] onto (x, y)? */
    private fun passable(x: Int, y: Int, ground: Int): Boolean {
        val s = standable(x, y) ?: return false
        return abs(s - ground) <= STEP
    }

    /**
     * A corridor: across one axis the way is two blocks or narrower, closed
     * by walls or drops on both sides, while along it the way runs on.
     */
    private fun choke(x: Int, y: Int, ground: Int): Boolean {
        fun run(sx: Int, sy: Int): Int {
            var n = 0
            while (n < CHOKE_PROBE && passable(x + sx * (n + 1), y + sy * (n + 1), ground)) n++
            return n
        }
        val acrossX = 1 + run(1, 0) + run(-1, 0)
        val acrossY = 1 + run(0, 1) + run(0, -1)
        val narrow = min(acrossX, acrossY); val long = max(acrossX, acrossY)
        return narrow <= CHOKE_WIDTH && long >= CHOKE_WIDTH + 2
    }

    private fun brushAt(x: Int, y: Int, ground: Int): Boolean =
        (1..2).any { h -> world.blockAt(BlockPos(x, y, ground + h)).material == BlockMaterial.FOLIAGE } ||
            NEIGHBOURS.count { (nx, ny) -> world.blockAt(BlockPos(x + nx, y + ny, ground + 1)).material == BlockMaterial.FOLIAGE } >= 2

    /** Eye to eye: from the player's head to a body's head standing at [to], through clear blocks only. */
    fun lineOfSight(from: WorldPoint, to: WorldPoint): Boolean {
        val ax = from.x; val ay = from.y; val az = from.z + EYE
        val bx = to.x; val by = to.y; val bz = to.z + EYE
        val length = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
        val steps = (length / RAY_STEP).toInt().coerceAtLeast(1)
        // The ends are the two bodies' own blocks; only what lies between can hide one from the other.
        for (i in 1 until steps) {
            val t = i.toFloat() / steps
            val pos = BlockPos(floor(ax + (bx - ax) * t).toInt(), floor(ay + (by - ay) * t).toInt(), floor(az + (bz - az) * t).toInt())
            if (pos.z < 0 || pos.z >= Chunk.HEIGHT) continue
            val block = world.blockAt(pos)
            if (block.isSolid || block.material == BlockMaterial.FOLIAGE) return false
        }
        return true
    }

    companion object {
        /** High ground is this many blocks above the player's feet. */
        const val VANTAGE_RISE = 3
        /** A choke is this wide or narrower. */
        const val CHOKE_WIDTH = 2
        private const val CHOKE_PROBE = 4
        private const val STEP = 1
        private const val EYE = 1.5f
        private const val RAY_STEP = 0.35f
        /** Past this from where the player looks, a spot is on their flank. */
        private const val FLANK_ANGLE = (Math.PI * 0.55).toFloat()
        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        /** Signed smallest angle from [a] to [b], in -pi..pi. */
        fun angleBetween(a: Float, b: Float): Float {
            var d = (b - a) % TWO_PI
            if (d > Math.PI) d -= TWO_PI
            if (d < -Math.PI) d += TWO_PI
            return d
        }

        private const val TWO_PI = (Math.PI * 2).toFloat()

        /** The ground each role wants, best first. */
        fun preferred(role: CombatRole): List<TerrainFeature> = when (role) {
            CombatRole.RANGED, CombatRole.SUPPORT -> listOf(TerrainFeature.VANTAGE, TerrainFeature.CONCEALED)
            CombatRole.BRUTE -> listOf(TerrainFeature.CHOKE, TerrainFeature.OPEN)
            CombatRole.SWARMER -> listOf(TerrainFeature.CONCEALED, TerrainFeature.FLANK)
            CombatRole.MELEE -> listOf(TerrainFeature.FLANK, TerrainFeature.OPEN)
        }
    }
}
