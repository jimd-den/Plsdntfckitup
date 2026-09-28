package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.WorldPoint

/**
 * The fight as one action or tick sees it, while it is being resolved.
 *
 * Deliberately mutable and short-lived: a cast that triggers a cast that
 * kills something has to see the kill, so the systems resolving it share one
 * working copy. The session loads it from its immutable state, lets the
 * combat systems work on it, and takes the results back in one place.
 */
internal class Battlefield(
    var player: PlayerState,
    enemies: List<EnemyInstance>,
    /** The player is mid-roll: incoming hits are dodged. */
    val playerInvulnerable: Boolean,
    /** Never a target of the player's skills. */
    val isAllied: (EnemyInstance) -> Boolean,
    /** Fights the player. */
    val isHostile: (EnemyInstance) -> Boolean,
) {
    private val roster = LinkedHashMap<String, EnemyInstance>().apply { enemies.forEach { put(it.instanceId, it) } }

    val enemies: List<EnemyInstance> get() = roster.values.toList()

    /** Hits the player's side landed on monsters, for the attack report. */
    val hits = mutableListOf<EnemyHit>()

    /** Hits that reached the player, including evaded and blocked ones. */
    val incoming = mutableListOf<DamageResult>()

    /** Damage the player rolled through. */
    var dodged = 0

    /** Monsters killed by the player's side. */
    val killed = LinkedHashSet<String>()

    /** New bodies that should fight the player whatever their faction thinks: a hostile boss's adds. */
    val provoked = LinkedHashSet<String>()

    val events = mutableListOf<CombatEvent>()

    /** Monsters that swung this tick, so the animation can show it. */
    val swung = mutableListOf<String>()

    fun enemy(id: String): EnemyInstance? = roster[id]

    fun put(enemy: EnemyInstance) {
        roster[enemy.instanceId] = enemy
    }

    fun remove(id: String) {
        roster.remove(id)
    }

    fun positionOf(id: String): WorldPoint? =
        if (id == WorldSession.PLAYER_ACTOR_ID) player.position else roster[id]?.position
}
