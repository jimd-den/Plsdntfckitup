package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillShape
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageCalculator
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/**
 * Applies attacks between the player and monsters.
 *
 * Target selection lives here with damage resolution because a skill's shape and
 * its damage are one decision: a nova that picks targets differently from how it
 * damages them is how "it hit something behind me" bugs happen.
 */
class CombatResolver {

    /** A basic swing at whatever is in reach, nearest first. */
    fun playerAttack(
        attacker: CombatStats,
        attackerPosition: WorldPoint,
        facing: Direction,
        enemies: List<EnemyInstance>,
        damageTypeId: String,
        random: Random,
    ): AttackOutcome {
        val target = enemies
            .filter { it.isAlive }
            .filter { it.position.horizontalDistanceTo(attackerPosition) <= attacker.attackRange + REACH_FORGIVENESS }
            .minByOrNull { it.position.horizontalDistanceTo(attackerPosition) }
            ?: return AttackOutcome.NoTarget

        return strike(attacker, listOf(target), damageTypeId, 1f, random)
    }

    /**
     * Casts a skill. The caller has already checked cost and cooldown; this
     * decides who it reaches and how hard.
     */
    fun castSkill(
        attacker: CombatStats,
        attackerPosition: WorldPoint,
        facing: Direction,
        enemies: List<EnemyInstance>,
        skill: SkillDefinition,
        random: Random,
    ): AttackOutcome {
        val alive = enemies.filter { it.isAlive }
        val targets = when (skill.shape) {
            SkillShape.STRIKE -> listOfNotNull(
                alive.filter { it.position.horizontalDistanceTo(attackerPosition) <= skill.range }
                    .minByOrNull { it.position.horizontalDistanceTo(attackerPosition) },
            )

            SkillShape.NOVA -> alive.filter {
                it.position.horizontalDistanceTo(attackerPosition) <= skill.range
            }

            SkillShape.LANCE -> alive.filter { enemy ->
                inLine(attackerPosition, facing, enemy.position, skill.range)
            }
        }

        if (targets.isEmpty()) return AttackOutcome.NoTarget
        return strike(attacker, targets, skill.damageTypeId, skill.powerMultiplier, random)
    }

    /**
     * Whether a point lies in the lane the caster is facing.
     *
     * The lane is a block wide either side, because demanding pixel-perfect
     * alignment on an isometric grid with a four-way pad is not a skill test,
     * it is an input test.
     */
    private fun inLine(
        origin: WorldPoint,
        facing: Direction,
        point: WorldPoint,
        range: Int,
    ): Boolean {
        val dx = point.x - origin.x
        val dy = point.y - origin.y

        val along = dx * facing.dx + dy * facing.dy
        if (along <= 0f || along > range) return false

        // Distance from the lane's centre line.
        val across = kotlin.math.abs(dx * facing.dy - dy * facing.dx)
        return across <= LANE_HALF_WIDTH
    }

    private fun strike(
        attacker: CombatStats,
        targets: List<EnemyInstance>,
        damageTypeId: String,
        powerMultiplier: Float,
        random: Random,
    ): AttackOutcome {
        val hits = targets.map { enemy ->
            val result = DamageCalculator.resolve(
                attacker = attacker,
                defender = enemy.stats,
                damageTypeId = damageTypeId,
                critRoll = random.nextFloat(),
                powerMultiplier = powerMultiplier,
            )
            EnemyHit(enemy.instanceId, enemy.damaged(result.amount), result)
        }
        return AttackOutcome.Hits(hits)
    }

    /**
     * Runs every monster that is in range and off cooldown.
     *
     * Returns the damage the player takes and the monsters with their cooldowns
     * restarted, so a caller cannot apply the damage and forget the cooldown.
     */
    fun enemyAttacks(
        enemies: List<EnemyInstance>,
        defender: CombatStats,
        defenderPosition: WorldPoint,
        cooldownFor: (EnemyInstance) -> Float,
        random: Random,
    ): EnemyAttackOutcome {
        val results = mutableListOf<DamageResult>()
        val updated = enemies.map { enemy ->
            if (!enemy.isAlive) return@map enemy
            if (enemy.attackCooldown > 0f) return@map enemy
            val distance = enemy.position.horizontalDistanceTo(defenderPosition)
            if (distance > enemy.stats.attackRange + REACH_FORGIVENESS) return@map enemy

            val result = DamageCalculator.resolve(
                attacker = enemy.stats,
                defender = defender,
                damageTypeId = enemy.damageTypeId,
                critRoll = random.nextFloat(),
            )
            results += result
            enemy.copy(attackCooldown = cooldownFor(enemy))
        }
        return EnemyAttackOutcome(updated, results, results.sumOf { it.amount })
    }

    private companion object {
        /**
         * Reach is measured between continuous positions, so a monster standing
         * in the adjacent block is already about one unit away. Without a little
         * slack, adjacent never counts as in range.
         */
        const val REACH_FORGIVENESS = 0.75f
        const val LANE_HALF_WIDTH = 1.0f
    }
}

sealed interface AttackOutcome {
    data object NoTarget : AttackOutcome

    data class Hits(val hits: List<EnemyHit>) : AttackOutcome {
        val totalDamage: Int get() = hits.sumOf { it.result.amount }
        val killed: List<EnemyInstance> get() = hits.map { it.enemy }.filterNot { it.isAlive }
        val anyCritical: Boolean get() = hits.any { it.result.wasCritical }
    }
}

data class EnemyHit(
    val enemyId: String,
    /** The monster after the hit, already damaged. */
    val enemy: EnemyInstance,
    val result: DamageResult,
)

data class EnemyAttackOutcome(
    val enemies: List<EnemyInstance>,
    val results: List<DamageResult>,
    val totalDamage: Int,
)
