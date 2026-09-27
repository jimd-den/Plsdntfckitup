package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyState
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.sprite.AnimationSelector
import com.stratum.core.domain.sprite.AnimationState

/**
 * Animation clocks, per actor id.
 *
 * Kept in the simulation rather than the renderer so two monsters in the same
 * state stay independent and a recomposition does not restart every walk
 * cycle. States are derived every frame from what the actor is doing, so they
 * can never drift out of step with the simulation that produced them.
 */
internal class ActorAnimator {

    private val playbacks = HashMap<String, AnimationPlayback>()

    /** Seconds left of an actor's attack animation, so a swing is not instant. */
    private val attackHolds = HashMap<String, Float>()

    /**
     * Seconds left of a *skill* animation. Kept apart from [attackHolds] so a
     * power does not look like an ordinary swing -- if the two read the same,
     * the resource you spent bought nothing you can see.
     */
    private val castHolds = HashMap<String, Float>()

    /** What the player's body is doing this frame, for choosing its animation. */
    data class PlayerMotionState(val isAlive: Boolean, val isRolling: Boolean, val isMoving: Boolean)

    fun playbackFor(actorId: String): AnimationPlayback = playbacks[actorId] ?: AnimationPlayback()

    fun holdAttack(actorId: String) {
        attackHolds[actorId] = HOLD_SECONDS
    }

    fun holdCast(actorId: String) {
        attackHolds[actorId] = HOLD_SECONDS
        castHolds[actorId] = HOLD_SECONDS
    }

    /** Counts the holds down. Run before combat, so a swing this frame starts a fresh hold. */
    fun advanceHolds(deltaSeconds: Float) {
        listOf(attackHolds, castHolds).forEach { holds -> countDown(holds, deltaSeconds) }
    }

    fun advance(
        deltaSeconds: Float,
        playerId: String,
        player: PlayerMotionState,
        enemies: List<EnemyInstance>,
        wasHit: (String) -> Boolean,
    ) {
        val deltaMs = (deltaSeconds * 1000f).toLong()
        val playerState = AnimationSelector.select(
            isDead = !player.isAlive,
            isRolling = player.isRolling,
            wasHitRecently = wasHit(playerId),
            isAttacking = playerId in attackHolds,
            isCasting = playerId in castHolds,
            isMoving = player.isMoving,
        )
        playbacks[playerId] = animate(playerId, playerState, deltaMs)

        enemies.forEach { enemy ->
            val state = AnimationSelector.select(
                isDead = !enemy.isAlive,
                wasHitRecently = wasHit(enemy.instanceId),
                isAttacking = enemy.instanceId in attackHolds,
                isMoving = enemy.state == EnemyState.CHASING || enemy.state == EnemyState.FLEEING,
            )
            playbacks[enemy.instanceId] = animate(enemy.instanceId, state, deltaMs)
        }

        // Forget actors that no longer exist, or the map grows for the whole run.
        playbacks.keys.retainAll(enemies.map { it.instanceId }.toSet() + playerId)
    }

    fun clear() {
        playbacks.clear()
        attackHolds.clear()
        castHolds.clear()
    }

    private fun animate(actorId: String, state: AnimationState, deltaMs: Long): AnimationPlayback =
        playbackFor(actorId).transitionTo(state).advanced(deltaMs)

    private fun countDown(holds: MutableMap<String, Float>, deltaSeconds: Float) {
        val iterator = holds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) iterator.remove() else entry.setValue(remaining)
        }
    }

    companion object {
        /** How long an actor is considered mid-swing, for animation only. */
        const val HOLD_SECONDS = 0.3f
    }
}
