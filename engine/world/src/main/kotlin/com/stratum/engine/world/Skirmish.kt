package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.combat.DamageCalculator
import com.stratum.core.domain.combat.DamageResult
import kotlin.random.Random

/** One blow between two bodies that are neither of them the player. */
data class Blow(val attackerId: String, val defenderId: String, val result: DamageResult)

/**
 * Fights that do not involve the player's own hands: followers against
 * raiders, raiders against followers, a garrison against a warband. The same
 * damage rules as everything else, applied between two groups.
 */
internal object Skirmish {

    /**
     * Every attacker off cooldown strikes the nearest defender in its reach.
     * Returns both groups updated, and the blows, for the floating numbers.
     */
    fun exchange(
        attackers: List<EnemyInstance>,
        defenders: List<EnemyInstance>,
        random: Random,
    ): Triple<List<EnemyInstance>, List<EnemyInstance>, List<Blow>> {
        val struck = defenders.associateByTo(LinkedHashMap()) { it.instanceId }
        val blows = mutableListOf<Blow>()
        val swung = attackers.map { attacker ->
            if (!attacker.isAlive || attacker.attackCooldown > 0f) return@map attacker
            val target = struck.values.filter { it.isAlive && inReach(attacker, it) }
                .minByOrNull { it.position.horizontalDistanceTo(attacker.position) } ?: return@map attacker
            val result = DamageCalculator.resolve(attacker.stats, target.stats, attacker.damageTypeId, random.nextFloat())
            struck[target.instanceId] = target.damaged(result.amount)
            blows += Blow(attacker.instanceId, target.instanceId, result)
            attacker.copy(attackCooldown = attacker.stats.secondsBetweenAttacks)
        }
        return Triple(swung, struck.values.toList(), blows)
    }

    private fun inReach(attacker: EnemyInstance, target: EnemyInstance): Boolean =
        attacker.position.horizontalDistanceTo(target.position) <= attacker.stats.attackRange + REACH_FORGIVENESS

    private const val REACH_FORGIVENESS = 0.6f
}
