package com.stratum.engine.world

import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Which way a cast is pointed: a unit direction on the ground plane. */
data class Aim(val dx: Float, val dy: Float) {
    companion object {
        /** A direction from [from] to [to], or [fallback] when they coincide. */
        fun toward(from: WorldPoint, to: WorldPoint, fallback: Aim = Aim(0f, 1f)): Aim {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val length = sqrt(dx * dx + dy * dy)
            return if (length < 1e-4f) fallback else Aim(dx / length, dy / length)
        }

        fun of(dx: Float, dy: Float): Aim {
            val length = sqrt(dx * dx + dy * dy)
            return if (length < 1e-4f) Aim(0f, 1f) else Aim(dx / length, dy / length)
        }
    }
}

/** Something a skill might reach: an id and where it stands. */
data class Candidate(val id: String, val position: WorldPoint)

/**
 * Who a skill reaches, by delivery.
 *
 * Target selection is one pure function of positions for every caster --
 * the player, a monster, a trap -- because a skill that picks targets
 * differently for different casters is how "it hit something behind me" bugs
 * happen. Deliveries that do not pick targets up front (projectiles, zones,
 * summons, the caster alone) reach nobody here.
 */
object SkillTargeting {

    /**
     * Ids reached, nearest first.
     *
     * [origin] is where the caster stands; [point] is where an area lands
     * (the caster's chosen spot, or a telegraph's locked one).
     */
    fun targets(skill: SkillDefinition, origin: WorldPoint, aim: Aim, point: WorldPoint, candidates: List<Candidate>): List<String> {
        val reach = skill.reach
        val nearestFirst = candidates.sortedBy { it.position.horizontalDistanceTo(origin) }
        return when (skill.delivery) {
            SkillDelivery.MELEE -> listOfNotNull(nearestFirst.firstOrNull { it.position.horizontalDistanceTo(origin) <= reach + REACH_FORGIVENESS }?.id)
            SkillDelivery.NOVA -> nearestFirst.filter { it.position.horizontalDistanceTo(origin) <= reach }.map { it.id }
            SkillDelivery.AREA -> nearestFirst.filter { it.position.horizontalDistanceTo(point) <= areaRadius(skill) }.map { it.id }
            SkillDelivery.CONE -> nearestFirst.filter { inCone(origin, aim, it.position, reach + REACH_FORGIVENESS, skill.area.angleDegrees) }.map { it.id }
            SkillDelivery.BEAM, SkillDelivery.DASH -> nearestFirst.filter { inLane(origin, aim, it.position, reach, skill.area.halfWidth) }.map { it.id }
            SkillDelivery.CHAIN -> chain(nearestFirst, origin, reach + REACH_FORGIVENESS, hopRadius(skill), skill.projectile.chain)
            SkillDelivery.PROJECTILE, SkillDelivery.SUMMON, SkillDelivery.SELF, SkillDelivery.ZONE -> emptyList()
        }
    }

    /** Where an area skill lands when nothing chose the spot: the nearest candidate in reach, else straight ahead. */
    fun landingPoint(skill: SkillDefinition, origin: WorldPoint, aim: Aim, candidates: List<Candidate>): WorldPoint =
        candidates.filter { it.position.horizontalDistanceTo(origin) <= skill.range + REACH_FORGIVENESS }
            .minByOrNull { it.position.horizontalDistanceTo(origin) }?.position
            ?: WorldPoint(origin.x + aim.dx * skill.range, origin.y + aim.dy * skill.range, origin.z)

    /** An area's radius: its own when set, otherwise a modest splash rather than the whole reach. */
    fun areaRadius(skill: SkillDefinition): Float = if (skill.area.radius > 0f) skill.area.radius else DEFAULT_AREA_RADIUS

    /** How far a chain leaps between targets. */
    fun hopRadius(skill: SkillDefinition): Float = if (skill.area.radius > 0f) skill.area.radius else DEFAULT_HOP

    /**
     * Whether a point lies in the lane the caster is facing.
     *
     * The lane is a block wide either side by default, because demanding
     * pixel-perfect alignment on an isometric grid with a four-way pad is not
     * a skill test, it is an input test.
     */
    fun inLane(origin: WorldPoint, aim: Aim, point: WorldPoint, length: Float, halfWidth: Float): Boolean {
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        val along = dx * aim.dx + dy * aim.dy
        if (along <= 0f || along > length) return false
        return abs(dx * aim.dy - dy * aim.dx) <= halfWidth
    }

    fun inCone(origin: WorldPoint, aim: Aim, point: WorldPoint, length: Float, angleDegrees: Float): Boolean {
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance > length) return false
        // Something standing inside the caster is in every cone.
        if (distance < 1e-3f) return true
        val cosine = (dx * aim.dx + dy * aim.dy) / distance
        return cosine >= cos(Math.toRadians(angleDegrees / 2.0)).toFloat() - 1e-4f
    }

    private fun chain(nearestFirst: List<Candidate>, origin: WorldPoint, reach: Float, hop: Float, leaps: Int): List<String> {
        val first = nearestFirst.firstOrNull { it.position.horizontalDistanceTo(origin) <= reach } ?: return emptyList()
        val hit = mutableListOf(first)
        repeat(leaps) {
            val from = hit.last().position
            val next = nearestFirst.filter { c -> hit.none { it.id == c.id } && c.position.horizontalDistanceTo(from) <= hop }
                .minByOrNull { it.position.horizontalDistanceTo(from) } ?: return hit.map { it.id }
            hit += next
        }
        return hit.map { it.id }
    }

    /**
     * Reach is measured between continuous positions, so a monster standing
     * in the adjacent block is already about one unit away. Without a little
     * slack, adjacent never counts as in range.
     */
    const val REACH_FORGIVENESS = 0.75f
    private const val DEFAULT_AREA_RADIUS = 2f
    private const val DEFAULT_HOP = 4f
}
