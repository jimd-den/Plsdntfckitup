package com.stratum.feature.play

import com.stratum.core.domain.actor.SkillCost
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.engine.world.Held

/** A held support against one skill: whether it links, why not, and what it would do. */
data class SupportOption(
    val support: SupportDefinition,
    val held: Int,
    val fits: Boolean,
    /** What the skill would have to be tagged for it to fit, when it does not. */
    val needs: String? = null,
    val changes: List<String> = emptyList(),
)

/**
 * What the skills page says about a skill and its supports, in words.
 *
 * Kept out of the composables so the rule it reads -- a support links only
 * to a skill carrying one of its tags -- is the one the workbench applies,
 * and can be tested without a screen.
 */
object SkillFacts {

    /** One line per fact that matters when pressing the button: cost, timing, charges, reach, tags. */
    fun facts(skill: SkillDefinition, cost: SkillCost, resourceName: String): List<String> = buildList {
        val paid = listOfNotNull(
            "${cost.resource} ${resourceName.lowercase()}".takeIf { cost.resource > 0 },
            "${cost.life} life".takeIf { cost.life > 0 },
        )
        add(if (paid.isEmpty()) "Free" else "Costs " + paid.joinToString(" and "))
        add("×%.2f power".format(skill.powerMultiplier))
        if (skill.castTime > 0f) add("%.2fs to cast".format(skill.castTime)) else add("Instant")
        if (skill.cooldownSeconds > 0f) add("%.1fs cooldown".format(skill.cooldownSeconds))
        if (skill.charges > 1) add("${skill.charges} charges")
        add("${skill.delivery.name.lowercase()}, reach ${skill.range}")
        if (skill.projectile.count > 1) add("${skill.projectile.count} projectiles")
    }

    fun tags(skill: SkillDefinition): List<String> = skill.allTags.map { it.substringAfter(':') }.sorted()

    /** What linking [support] changes, the way its gem would read. */
    fun changes(support: SupportDefinition, damageTypeName: (String) -> String = { it.substringAfter(':') }): List<String> = buildList {
        support.modifiers.forEach { add(it.describe()) }
        support.convertsToDamageTypeId?.let { add("Deals ${damageTypeName(it)} damage instead") }
        support.conversions.forEach { add("${(it.share * 100).toInt()}% of ${it.fromDamageTypeId?.let(damageTypeName) ?: "its damage"} becomes ${damageTypeName(it.toDamageTypeId)}") }
        if (support.addsTags.isNotEmpty()) add("Counts as " + support.addsTags.joinToString { it.substringAfter(':') })
        support.effects.forEach { effect ->
            when (effect) {
                is SkillEffect.ApplyStatus -> add("${(effect.chance * 100).toInt()}% chance to inflict ${effect.statusId.substringAfter(':')}")
                is SkillEffect.Damage -> add("Also deals ${damageTypeName(effect.damageTypeId)} damage")
                is SkillEffect.Knockback -> add("Knocks back")
                is SkillEffect.CastSkill -> add("Casts ${effect.skillId.substringAfter(':')} where it lands")
                is SkillEffect.Heal -> add("Heals")
                is SkillEffect.RestoreResource -> add("Restores ${effect.amount}")
                is SkillEffect.Terrain -> add(
                    when (effect.change) {
                        com.stratum.core.domain.actor.TerrainChange.CRATER -> "Blasts a crater"
                        com.stratum.core.domain.actor.TerrainChange.WALL -> "Raises a wall of earth"
                        com.stratum.core.domain.actor.TerrainChange.IGNITE -> "Burns the brush away"
                        com.stratum.core.domain.actor.TerrainChange.FREEZE -> "Freezes water to ice"
                    },
                )
            }
        }
        support.trigger?.let { trigger ->
            add("Casts itself on ${trigger.event.name.lowercase().removePrefix("on_").replace('_', ' ')}" + if (trigger.cooldownSeconds > 0f) ", every %.1fs at most".format(trigger.cooldownSeconds) else "")
        }
        if (isEmpty() && support.description.isNotBlank()) add(support.description)
    }

    /** Every held support against [skill], the ones that fit first. */
    fun options(skill: SkillDefinition, held: List<Held<SupportDefinition>>, damageTypeName: (String) -> String = { it.substringAfter(':') }): List<SupportOption> =
        held.map { (support, count) ->
            val fits = support.fits(skill)
            SupportOption(
                support, count, fits,
                needs = if (fits) null else support.requiresTags.joinToString(" or ") { it.substringAfter(':') },
                changes = changes(support, damageTypeName),
            )
        }.sortedByDescending { it.fits }
}
