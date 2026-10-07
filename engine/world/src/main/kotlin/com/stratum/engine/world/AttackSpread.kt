package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance

/**
 * Gives each monster its own few forged attacks from its kind's pool.
 *
 * A kind carries a pool of forged attacks (see
 * [com.stratum.core.domain.attack.AttackCompiler.pack]); each body that
 * appears takes [perMonster] of them. With [distinct] on, it takes the ones
 * no other body near it already carries, so a fight of five of the same
 * beast is five different fights. The choice is seeded by the body's id, so
 * a replay deals the same hands.
 */
internal class AttackSpread(
    private val definitionOf: (String) -> EnemyDefinition?,
    private val perMonster: Int,
    private val distinct: Boolean,
) {
    /** [enemies] with every newcomer dealt its attacks; bodies already dealt keep theirs. */
    fun deal(enemies: List<EnemyInstance>): List<EnemyInstance> {
        if (enemies.none { it.skillPool == null && !it.civilian }) return enemies
        val out = enemies.toMutableList()
        for (i in out.indices) {
            val e = out[i]
            if (e.skillPool != null || e.civilian) continue
            val forged = definitionOf(e.definitionId)?.allSkills.orEmpty().map { it.skillId }.filter { it.startsWith(FORGED) }.distinct()
            if (forged.isEmpty()) { out[i] = e.copy(skillPool = emptyList()); continue }
            val taken = if (!distinct) emptyMap() else out.asSequence()
                .filter { it !== e && it.isAlive && it.position.horizontalDistanceTo(e.position) <= NEIGHBOURHOOD }
                .flatMap { it.skillPool.orEmpty().asSequence() }
                .groupingBy { it }.eachCount()
            // Least used nearby first; ties broken by the body's own seed, so the same body always draws the same hand.
            val seed = e.instanceId.hashCode()
            val hand = forged.sortedWith(compareBy<String>({ taken[it] ?: 0 }, { (it.hashCode() xor seed) }))
                .take(perMonster.coerceAtLeast(1))
            out[i] = e.copy(skillPool = hand)
        }
        return out
    }

    companion object {
        const val FORGED = "forge:"
        /** Bodies within this many blocks count as fighting together. */
        const val NEIGHBOURHOOD = 24f
    }
}
