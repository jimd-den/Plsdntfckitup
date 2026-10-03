package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** How a squad stands against the player. */
enum class FormationPattern {
    /** Two wings closing from either side of the player's approach. */
    PINCER,
    /** Casters and the leader held back, the fighters thrown out in front of them. */
    ESCORT,
    /** Hidden on the player's flanks and behind them, close, waiting to rush. */
    AMBUSH_FLANK,
    /** Brutes holding the narrow way in, shooters on the high ground behind them. */
    BUNKER,
}

/** The ground a formation needs nearby before it is worth choosing: at least [count] spots with any of [anyOf]. */
data class TerrainRequirement(val anyOf: Set<TerrainFeature> = emptySet(), val count: Int = 0) {
    fun metBy(spots: List<TacticalSpot>): Boolean =
        count <= 0 || anyOf.isEmpty() || spots.count { s -> s.features.any(anyOf::contains) } >= count

    companion object {
        val NONE = TerrainRequirement()
    }
}

/**
 * A squad as a fighting unit rather than a list of monsters: how many of
 * each role, how they stand, and what ground they need to stand that way.
 */
data class EncounterCell(
    val roleComposition: Map<CombatRole, Int>,
    val formation: FormationPattern,
    val terrainRequirement: TerrainRequirement = requirementOf(formation),
) {
    val size: Int get() = roleComposition.values.sum()

    companion object {
        fun requirementOf(formation: FormationPattern): TerrainRequirement = when (formation) {
            FormationPattern.BUNKER -> TerrainRequirement(setOf(TerrainFeature.CHOKE, TerrainFeature.VANTAGE), 2)
            FormationPattern.AMBUSH_FLANK -> TerrainRequirement(setOf(TerrainFeature.CONCEALED), 3)
            FormationPattern.ESCORT, FormationPattern.PINCER -> TerrainRequirement.NONE
        }

        /**
         * The formation a squad's roles suit, best first; the placer takes the
         * first whose ground is there.
         *
         * Swarmers in numbers ambush; a brute with shooters holds a bunker;
         * anyone worth protecting is escorted; everyone else closes from two sides.
         */
        fun formationsFor(roles: Map<CombatRole, Int>): List<FormationPattern> {
            fun n(role: CombatRole) = roles[role] ?: 0
            val total = roles.values.sum().coerceAtLeast(1)
            val order = ArrayList<FormationPattern>(4)
            if (n(CombatRole.SWARMER) * 2 >= total) order += FormationPattern.AMBUSH_FLANK
            if (n(CombatRole.BRUTE) > 0 && n(CombatRole.RANGED) + n(CombatRole.SUPPORT) > 0) order += FormationPattern.BUNKER
            if (n(CombatRole.SUPPORT) > 0 || n(CombatRole.RANGED) > 0) order += FormationPattern.ESCORT
            order += FormationPattern.PINCER
            return order.distinct()
        }

        fun of(roles: List<CombatRole>): EncounterCell {
            val composition = roles.groupingBy { it }.eachCount()
            return EncounterCell(composition, formationsFor(composition).first())
        }
    }
}

/**
 * Stands a squad on the ground the way its formation and its roles want:
 * shooters on the heights with a line to the player, brutes in the
 * narrows, swarmers out of sight on the flanks.
 *
 * Every member gets a slot, a point the formation wants it near, and then
 * the best free spot around that slot by what its role prefers. Where the
 * ground offers nothing, it simply stands at its slot.
 */
class TacticalPlacer(private val terrain: TacticalTerrain) {

    /** Where each member stands, in [roles] order, and the formation they took. */
    data class Placement(val formation: FormationPattern, val positions: List<WorldPoint>)

    /**
     * @param focus the player.
     * @param anchor where the squad arrives, out in the spawn ring.
     * @param facing where the player is looking, radians; null if not known.
     * @param allowed false for spots nothing may stand on (a town's grounds).
     */
    fun place(
        roles: List<CombatRole>,
        focus: WorldPoint,
        anchor: WorldPoint,
        inner: Float,
        outer: Float,
        random: Random,
        facing: Float? = null,
        allowed: (WorldPoint) -> Boolean = { true },
    ): Placement {
        val spots = terrain.profile(focus, inner, outer, facing).filter { allowed(it.at) }
        val composition = roles.groupingBy { it }.eachCount()
        val formation = EncounterCell.formationsFor(composition)
            .firstOrNull { EncounterCell.requirementOf(it).metBy(spots) } ?: FormationPattern.PINCER
        val slots = slots(roles, formation, focus, anchor, inner, outer, facing)
        val taken = ArrayList<WorldPoint>(roles.size)
        // The pickiest go first, so a single choke goes to the brute and not to whoever was listed before it.
        val order = roles.indices.sortedBy { pickiness(roles[it]) }
        val placed = arrayOfNulls<WorldPoint>(roles.size)
        for (i in order) {
            val best = best(spots, roles[i], slots[i], taken, random)
            val at = best ?: terrain.grounded(slots[i]) ?: anchor
            placed[i] = at
            taken += at
        }
        return Placement(formation, placed.map { it ?: anchor })
    }

    /** A single monster: the best ground for its role within [radius] of where it would have stood. */
    fun nudge(role: CombatRole, focus: WorldPoint, near: WorldPoint, radius: Float, inner: Float, facing: Float? = null, allowed: (WorldPoint) -> Boolean = { true }): WorldPoint? {
        val wanted = TacticalTerrain.preferred(role)
        val r = kotlin.math.ceil(radius).toInt()
        var bestSpot: TacticalSpot? = null; var bestScore = 0f
        for (dx in -r..r) for (dy in -r..r) {
            if (dx * dx + dy * dy > radius * radius) continue
            val spot = terrain.spotAt(focus, kotlin.math.floor(near.x).toInt() + dx, kotlin.math.floor(near.y).toInt() + dy, facing) ?: continue
            if (spot.distance < inner || !allowed(spot.at)) continue
            val score = preference(wanted, spot) - near.horizontalDistanceTo(spot.at) * 0.3f
            if (score > bestScore) { bestScore = score; bestSpot = spot }
        }
        return bestSpot?.at
    }

    private fun best(spots: List<TacticalSpot>, role: CombatRole, slot: WorldPoint, taken: List<WorldPoint>, random: Random): WorldPoint? {
        val wanted = TacticalTerrain.preferred(role)
        var best: TacticalSpot? = null; var bestScore = Float.NEGATIVE_INFINITY
        for (s in spots) {
            val away = s.at.horizontalDistanceTo(slot)
            if (away > SLOT_REACH) continue
            if (taken.any { it.horizontalDistanceTo(s.at) < SPACING }) continue
            val score = preference(wanted, s) - away * SLOT_PULL + random.nextFloat() * JITTER
            if (score > bestScore) { bestScore = score; best = s }
        }
        return best?.at
    }

    private fun preference(wanted: List<TerrainFeature>, s: TacticalSpot): Float =
        wanted.foldIndexed(0f) { i, acc, f -> if (s.has(f)) acc + PREFERENCE[i] else acc } +
            // Higher is better for anyone who shoots.
            if (TerrainFeature.VANTAGE in wanted) s.rise.coerceIn(0, 6) * 0.5f else 0f

    private fun pickiness(role: CombatRole) = when (role) {
        CombatRole.BRUTE -> 0
        CombatRole.RANGED -> 1
        CombatRole.SUPPORT -> 2
        CombatRole.SWARMER -> 3
        CombatRole.MELEE -> 4
    }

    /** The point the formation wants each member near. */
    private fun slots(roles: List<CombatRole>, formation: FormationPattern, focus: WorldPoint, anchor: WorldPoint, inner: Float, outer: Float, facing: Float?): List<WorldPoint> {
        val dx = anchor.x - focus.x; val dy = anchor.y - focus.y
        val theta = kotlin.math.atan2(dy, dx)
        val reach = sqrt(dx * dx + dy * dy).coerceIn(inner + 1f, outer - 1f)
        fun at(bearing: Float, distance: Float) = WorldPoint(focus.x + cos(bearing) * distance, focus.y + sin(bearing) * distance, anchor.z)
        var wing = 0
        return roles.mapIndexed { i, role ->
            val spread = ((i % 3) - 1) * 0.25f
            when (formation) {
                FormationPattern.PINCER -> at(theta + if (i % 2 == 0) PINCER_ANGLE else -PINCER_ANGLE, reach)
                FormationPattern.ESCORT -> when (role) {
                    CombatRole.SUPPORT, CombatRole.RANGED -> at(theta + spread, (reach + 3f).coerceAtMost(outer))
                    else -> at(theta + spread * 2f, (reach - 3f).coerceAtLeast(inner))
                }
                FormationPattern.AMBUSH_FLANK -> {
                    val behind = (facing ?: theta) + Math.PI.toFloat()
                    val side = if (wing++ % 2 == 0) 1f else -1f
                    at(behind + side * (0.7f + (i / 2) * 0.35f), inner + 2f)
                }
                FormationPattern.BUNKER -> when (role) {
                    CombatRole.BRUTE -> at(theta + spread, (reach - 2f).coerceAtLeast(inner))
                    CombatRole.RANGED, CombatRole.SUPPORT -> at(theta + spread * 1.5f, (reach + 3f).coerceAtMost(outer))
                    else -> at(theta + spread * 2f, reach)
                }
            }
        }
    }

    private companion object {
        val PREFERENCE = floatArrayOf(10f, 5f)
        /** How far from its slot a member looks for better ground. */
        const val SLOT_REACH = 7f
        const val SLOT_PULL = 0.6f
        const val SPACING = 1.4f
        const val JITTER = 0.5f
        const val PINCER_ANGLE = 0.9f
    }
}

/** [spot] dropped onto standable ground beneath it, or null. */
internal fun TacticalTerrain.grounded(spot: WorldPoint): WorldPoint? {
    val x = kotlin.math.floor(spot.x).toInt(); val y = kotlin.math.floor(spot.y).toInt()
    val ground = standable(x, y) ?: return null
    return WorldPoint(x + 0.5f, y + 0.5f, ground + 1f)
}
