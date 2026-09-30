package com.stratum.core.domain.content

import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.combat.DamageConversion
import com.stratum.core.domain.combat.ExtraDamage
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.status.StatusBehaviour

/**
 * The combat core's cross references: skills naming statuses, statuses
 * naming damage types, monsters naming skills, triggers naming what they
 * cast. A dangling id here would not crash -- it would silently do nothing,
 * which is worse, because a build that "should" work and quietly does not is
 * the hardest bug a player can report.
 *
 * Loops are not errors: a skill that casts itself, or a trigger that feeds
 * another, is a legitimate broken build, and the engine bounds it at run
 * time with the world's trigger depth and budget.
 */
internal object CombatValidation {

    fun problems(content: AssembledContent): List<String> {
        val refs = Refs(content)
        return content.damageTypes.flatMap { type ->
            listOfNotNull(type.ailmentStatusId).filterNot(refs::status).map { "damage type '${type.id}' inflicts unknown status '$it'" }
        } + content.statuses.flatMap { status ->
            status.behaviours.flatMap { behaviour ->
                when (behaviour) {
                    is StatusBehaviour.DamageOverTime -> listOf(behaviour.damageTypeId).filterNot(refs::type)
                        .map { "status '${status.id}' deals unknown damage type '$it'" }
                    else -> emptyList()
                }
            }
        } + content.skills.flatMap { skill ->
            effectProblems("skill '${skill.id}'", skill.effects, refs) +
                listOfNotNull(skill.summon?.enemyId).filterNot(refs::enemy).map { "skill '${skill.id}' summons unknown enemy '$it'" }
        } + content.enemies.flatMap { enemy ->
            val owner = "enemy '${enemy.id}'"
            monsterSkillProblems(owner, enemy.skills, refs) + enemy.phases.flatMap { phase ->
                val inPhase = "$owner phase '${phase.name}'"
                monsterSkillProblems(inPhase, phase.skills, refs) +
                    phase.adds.map { it.enemyId }.filterNot(refs::enemy).map { "$inPhase calls unknown enemy '$it'" } +
                    listOfNotNull(phase.statusId).filterNot(refs::status).map { "$inPhase gains unknown status '$it'" }
            }
        } + content.traits.flatMap { trait ->
            val owner = "trait '${trait.id}'"
            conversionProblems(owner, trait.conversions, trait.extraDamage, refs) + trait.triggers.flatMap { triggerProblems(owner, it, refs) }
        } + content.supports.flatMap { support ->
            val owner = "support '${support.id}'"
            effectProblems(owner, support.effects, refs) + conversionProblems(owner, support.conversions, emptyList(), refs) +
                listOfNotNull(support.trigger).flatMap { triggerProblems(owner, it, refs) }
        } + content.heroClasses.flatMap { hero ->
            hero.traitIds.filterNot(refs::trait).map { "class '${hero.id}' has unknown trait '$it'" }
        } + content.passiveTree?.nodes.orEmpty().flatMap { node ->
            node.traitIds.filterNot(refs::trait).map { "passive node '${node.id}' grants unknown trait '$it'" }
        } + content.flasks.flatMap { flask ->
            listOfNotNull(flask.statusId).filterNot(refs::status).map { "flask '${flask.id}' grants unknown status '$it'" }
        }
    }

    private fun effectProblems(owner: String, effects: List<SkillEffect>, refs: Refs): List<String> = effects.flatMap { effect ->
        when (effect) {
            is SkillEffect.Damage -> listOf(effect.damageTypeId).filterNot(refs::type).map { "$owner deals unknown damage type '$it'" }
            is SkillEffect.ApplyStatus -> listOf(effect.statusId).filterNot(refs::status).map { "$owner applies unknown status '$it'" }
            is SkillEffect.CastSkill -> listOf(effect.skillId).filterNot(refs::skill).map { "$owner casts unknown skill '$it'" }
            else -> emptyList()
        }
    }

    private fun monsterSkillProblems(owner: String, skills: List<MonsterSkill>, refs: Refs): List<String> =
        skills.map { it.skillId }.filterNot(refs::skill).map { "$owner uses unknown skill '$it'" }

    private fun triggerProblems(owner: String, trigger: TriggerDefinition, refs: Refs): List<String> =
        listOfNotNull(trigger.castSkillId).filterNot(refs::skill).map { "$owner triggers unknown skill '$it'" } +
            listOfNotNull(trigger.applyStatusId).filterNot(refs::status).map { "$owner triggers unknown status '$it'" }

    private fun conversionProblems(owner: String, conversions: List<DamageConversion>, extras: List<ExtraDamage>, refs: Refs): List<String> =
        (conversions.flatMap { listOfNotNull(it.fromDamageTypeId, it.toDamageTypeId) } + extras.flatMap { listOfNotNull(it.fromDamageTypeId, it.toDamageTypeId) })
            .distinct().filterNot(refs::type).map { "$owner converts unknown damage type '$it'" }

    private class Refs(content: AssembledContent) {
        private val types = content.damageTypes.mapTo(HashSet()) { it.id }
        private val statuses = content.statuses.mapTo(HashSet()) { it.id }
        private val skills = content.skills.mapTo(HashSet()) { it.id }
        private val enemies = content.enemies.mapTo(HashSet()) { it.id }
        private val traits = content.traits.mapTo(HashSet()) { it.id }

        fun type(id: String) = id in types
        fun status(id: String) = id in statuses
        fun skill(id: String) = id in skills
        fun enemy(id: String) = id in enemies
        fun trait(id: String) = id in traits
    }
}
