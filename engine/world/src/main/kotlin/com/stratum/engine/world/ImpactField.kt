package com.stratum.engine.world

import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.sqrt

/**
 * Knockback: the shove a hit puts on whatever took it.
 *
 * A hit that changes only a number is a hit the player has to read rather than
 * feel. Numbers float up and the body flashes, but nothing in the world moves,
 * so a heavy blow and a glancing one look identical while they land. Pushing
 * the target is the cheapest way to make the difference physical.
 *
 * Impulses decay rather than teleporting: a shove that resolved in one frame
 * would be a position change, not an impact.
 */
class ImpactField(private val world: World) {

    private val impulses = HashMap<String, WorldPoint>()

    /** True while [actorId] is still being pushed, for the renderer to lean on. */
    fun isReeling(actorId: String): Boolean = impulses.containsKey(actorId)

    /** How hard, 0..1, so a renderer can scale a recoil to the blow. */
    fun intensity(actorId: String): Float {
        val impulse = impulses[actorId] ?: return 0f
        val speed = sqrt(impulse.x * impulse.x + impulse.y * impulse.y)
        return (speed / MAX_SPEED).coerceIn(0f, 1f)
    }

    /**
     * Shoves [actorId] away from [from].
     *
     * [force] scales with the blow, so a critical visibly throws and a chip
     * barely rocks. A hit landed from exactly on top of the target has no
     * direction to push in and is ignored rather than sending it to infinity.
     */
    fun strike(actorId: String, from: WorldPoint, to: WorldPoint, force: Float) {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0.0001f) return

        val speed = (force * KNOCKBACK_PER_FORCE).coerceAtMost(MAX_SPEED)
        val existing = impulses[actorId]
        val pushX = dx / length * speed
        val pushY = dy / length * speed
        // Impulses add rather than replace, so being hit twice in a moment
        // throws harder than being hit once.
        impulses[actorId] = if (existing == null) {
            WorldPoint(pushX, pushY, 0f)
        } else {
            WorldPoint(
                (existing.x + pushX).coerceIn(-MAX_SPEED, MAX_SPEED),
                (existing.y + pushY).coerceIn(-MAX_SPEED, MAX_SPEED),
                0f,
            )
        }
    }

    /**
     * Applies and decays every impulse, returning where each actor ends up.
     *
     * Only actors in [positions] are moved; anything that died is dropped, so a
     * corpse's shove does not outlive it.
     */
    fun advance(
        deltaSeconds: Float,
        positions: Map<String, WorldPoint>,
    ): Map<String, WorldPoint> {
        if (impulses.isEmpty()) return emptyMap()

        // Clamped like movement is. A long frame must not turn a shove into a
        // teleport — the same stall that makes the world run slow should not
        // also fling every struck body across the map.
        val step = deltaSeconds.coerceIn(0f, MAX_STEP)

        val moved = HashMap<String, WorldPoint>()
        val finished = mutableListOf<String>()

        impulses.forEach { (actorId, impulse) ->
            val from = positions[actorId]
            if (from == null) {
                finished += actorId
                return@forEach
            }

            val target = WorldPoint(
                from.x + impulse.x * step,
                from.y + impulse.y * step,
                from.z,
            )
            // Knockback respects walls. Being shoved through terrain would be a
            // more obvious bug than no knockback at all.
            moved[actorId] = if (world.isSolid(target.toBlockPos())) from else target

            val decayed = WorldPoint(
                impulse.x * DECAY,
                impulse.y * DECAY,
                0f,
            )
            val speed = sqrt(decayed.x * decayed.x + decayed.y * decayed.y)
            if (speed < REST_SPEED) finished += actorId else impulses[actorId] = decayed
        }

        finished.forEach(impulses::remove)
        return moved
    }

    fun forget(actorId: String) {
        impulses.remove(actorId)
    }

    fun clear() = impulses.clear()

    private companion object {
        /**
         * Blocks per second of shove per point of damage.
         *
         * Deliberately small. Knocking a monster back on every hit sounds
         * satisfying and ruins melee: one swing pushes it out of reach and the
         * fight becomes chase-and-poke. Weight comes from the heavy hits, which
         * the caller marks by passing a bigger force.
         */
        const val KNOCKBACK_PER_FORCE = 0.08f
        /** However big the hit, a body never flies across the screen. */
        const val MAX_SPEED = 9f
        /** Fraction of the impulse kept each frame. */
        const val DECAY = 0.82f
        /** Below this the shove is over. */
        const val REST_SPEED = 0.4f
        /** A frame longer than this is a stall, not a longer shove. */
        const val MAX_STEP = 1f / 15f
    }
}
