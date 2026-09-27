package com.stratum.engine.world

import com.stratum.core.domain.actor.BossPhase
import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.status.StatusSet
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/** A monster's decision to use a skill: which, and the cooldown it goes on. */
internal data class MonsterChoice(val skill: SkillDefinition, val cooldownSeconds: Float)

/**
 * How monsters choose what to cast, and when a boss changes phase.
 *
 * Choice only; the casting itself goes through the same combat system as the
 * player's, so a monster's fireball is the player's fireball in other hands.
 */
internal class MonsterAbilities(private val content: AssembledContent, private val definitionOf: (String) -> EnemyDefinition?) {

    /** The next phase [enemy] has fallen into, if any. Phases are entered in order, one per tick. */
    fun phaseDue(enemy: EnemyInstance): BossPhase? {
        val phases = definitionOf(enemy.definitionId)?.phases.orEmpty()
        val next = phases.getOrNull(enemy.phase) ?: return null
        return next.takeIf { enemy.healthFraction <= it.healthBelow }
    }

    /** The skills [enemy] fights with now: the latest entered phase's, when it names any. */
    fun currentSkills(enemy: EnemyInstance): List<MonsterSkill> {
        val definition = definitionOf(enemy.definitionId) ?: return emptyList()
        return definition.phases.take(enemy.phase).lastOrNull { it.skills.isNotEmpty() }?.skills ?: definition.skills
    }

    /**
     * What [enemy] casts now, if anything: a weighted pick among its ready
     * skills that would do something from where it stands.
     */
    fun choose(
        enemy: EnemyInstance,
        target: WorldPoint?,
        allies: List<EnemyInstance>,
        statusesOf: (String) -> StatusSet,
        random: Random,
    ): MonsterChoice? {
        val usable = currentSkills(enemy).mapNotNull { entry ->
            val skill = content.skill(entry.skillId) ?: return@mapNotNull null
            if (entry.weight <= 0 || enemy.healthFraction > entry.healthBelow) return@mapNotNull null
            if (!enemy.skillCooldowns.isReady(skill)) return@mapNotNull null
            val worth = if (skill.isBeneficial) helps(skill, enemy, allies, statusesOf) else target != null && inReach(skill, enemy.position, target)
            if (!worth) null else entry to skill
        }
        val total = usable.sumOf { it.first.weight }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        val (entry, skill) = usable.first { roll -= it.first.weight; roll < 0 }
        return MonsterChoice(skill, entry.cooldownSeconds ?: skill.cooldownSeconds)
    }

    /** A heal is worth casting on someone hurt; a buff on someone without it. */
    private fun helps(skill: SkillDefinition, caster: EnemyInstance, allies: List<EnemyInstance>, statusesOf: (String) -> StatusSet): Boolean {
        val recipients = (allies + caster).filter { it.position.horizontalDistanceTo(caster.position) <= skill.reach + SkillTargeting.REACH_FORGIVENESS }
        return skill.resolvedEffects.any { effect ->
            when (effect) {
                is SkillEffect.Heal -> recipients.any { it.healthFraction < HURT }
                is SkillEffect.ApplyStatus -> {
                    val on = if (effect.target == EffectTarget.SELF) listOf(caster) else recipients
                    on.any { !statusesOf(it.instanceId).has(effect.statusId) }
                }
                else -> false
            }
        }
    }

    private fun inReach(skill: SkillDefinition, from: WorldPoint, target: WorldPoint): Boolean {
        val distance = from.horizontalDistanceTo(target)
        val reach = when (skill.delivery) {
            SkillDelivery.MELEE, SkillDelivery.CONE, SkillDelivery.CHAIN -> skill.reach + SkillTargeting.REACH_FORGIVENESS
            SkillDelivery.NOVA -> skill.reach
            SkillDelivery.AREA, SkillDelivery.ZONE -> skill.range + SkillTargeting.areaRadius(skill)
            SkillDelivery.BEAM, SkillDelivery.DASH, SkillDelivery.PROJECTILE -> skill.range.toFloat()
            SkillDelivery.SUMMON, SkillDelivery.SELF -> SUMMON_SIGHT
        }
        return distance <= reach
    }

    private companion object {
        const val HURT = 0.8f
        const val SUMMON_SIGHT = 12f
    }
}
