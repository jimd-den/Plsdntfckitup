package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.session.PlayerState

/**
 * What every part of a session touches: the player, the other bodies in the
 * world, and what happened between ticks.
 *
 * One holder rather than a copy per system, because the player is the thing
 * they all change -- an equip, a kill's experience, a meal -- and two copies
 * of the player are two players. Each value in it is immutable; only the
 * references move, so a snapshot taken at any moment is consistent.
 */
internal class SessionState {
    lateinit var player: PlayerState

    var enemies: List<EnemyInstance> = emptyList()

    /** Things that happened outside a tick, such as a town freed by a skill, reported with the next one. */
    val pending = mutableListOf<CombatEvent>()
}
