package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillTags
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageResult
import com.stratum.core.domain.combat.HitAttacker
import com.stratum.core.domain.combat.HitDefender
import com.stratum.core.domain.combat.HitResolver
import com.stratum.core.domain.combat.HitRolls
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/**
 * Instant, stateless attacks between one attacker and a crowd: the pure core
 * of the fight, with no statuses, projectiles or triggers.
 *
 * The session runs the full fight through [CombatSystem]; this is the same
 * targeting and the same [HitResolver], with nothing remembered between
 * calls, for tools and tests that want one exchange and its numbers.
 */
class CombatResolver(private val rules: CombatRules = CombatRules()) {

    /** A basic swing at whatever is in reach, nearest first. */
    fun playerAttack(
        attacker: CombatStats,
        attackerPosition: WorldPoint,
        facing: Direction,
        enemies: List<EnemyInstance>,
        damageTypeId: String,
        random: Random,
    ): AttackOutcome {
        val swing = SkillDefinition(
            id = CombatSystem.BASIC_ATTACK_ID, name = "Attack", damageTypeId = damageTypeId, powerMultiplier = 1f,
            delivery = SkillDelivery.MELEE, range = attacker.attackRange, tags = setOf(SkillTags.ATTACK, SkillTags.MELEE),
        )
        return castSkill(attacker, attackerPosition, facing, enemies, swing, random)
    }

    /**
     * Casts a skill that lands at once. The caller has already checked cost
     * and cooldown; this decides who it reaches and how hard. Deliveries that
     * land later -- projectiles, zones -- reach nobody here.
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
        val candidates = alive.map { Candidate(it.instanceId, it.position) }
        val aim = Aim.of(facing.dx.toFloat(), facing.dy.toFloat())
        val point = SkillTargeting.landingPoint(skill, attackerPosition, aim, candidates)
        val ids = SkillTargeting.targets(skill, attackerPosition, aim, point, candidates)
        if (ids.isEmpty()) return AttackOutcome.NoTarget
        val byId = alive.associateBy { it.instanceId }
        val hitter = HitAttacker(attacker, evadable = skill.hasTag(SkillTags.ATTACK))
        return AttackOutcome.Hits(
            ids.map { id ->
                val enemy = byId.getValue(id)
                val result = HitResolver.resolve(hitter, HitDefender(enemy.stats), damageOf(skill, attacker), HitRolls(crit = random.nextFloat()), rules)
                EnemyHit(id, enemy.damaged(result.amount), result)
            },
        )
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
            if (!enemy.isAlive || enemy.attackCooldown > 0f) return@map enemy
            val distance = enemy.position.horizontalDistanceTo(defenderPosition)
            if (distance > enemy.stats.attackRange + SkillTargeting.REACH_FORGIVENESS) return@map enemy
            results += HitResolver.resolve(
                HitAttacker(enemy.stats), HitDefender(defender),
                mapOf(enemy.damageTypeId to enemy.stats.attackPower.toFloat()), HitRolls(crit = random.nextFloat()), rules,
            )
            enemy.copy(attackCooldown = cooldownFor(enemy))
        }
        return EnemyAttackOutcome(updated, results, results.sumOf { it.amount })
    }

    private fun damageOf(skill: SkillDefinition, attacker: CombatStats): Map<String, Float> {
        val power = attacker.attackPower * skill.powerMultiplier
        return skill.resolvedEffects.filterIsInstance<SkillEffect.Damage>()
            .groupBy { it.damageTypeId }.mapValues { (_, parts) -> parts.sumOf { (power * it.share).toDouble() }.toFloat() }
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
