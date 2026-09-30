package com.stratum.core.domain.actor

/**
 * What one use of a skill takes from its caster, once the build has had its
 * say: the resource, and life.
 *
 * One function answers it for the cast and for the skill bar, because a
 * button that greys out on a rule the cast does not apply -- or lights up on
 * one it does -- is a lie the player finds out in the middle of a fight.
 * Under a life-pays keystone the resource cost is paid in life instead.
 */
data class SkillCost(val resource: Int, val life: Int) {

    /**
     * Whether a caster with [resource] and [health] can pay. A life cost must
     * leave the caster standing: a skill never kills the one casting it.
     */
    fun affordable(resource: Int, health: Int): Boolean =
        resource >= this.resource && (life <= 0 || health > life)

    companion object {
        fun of(skill: SkillDefinition, lifePaysCosts: Boolean): SkillCost =
            if (lifePaysCosts) SkillCost(resource = 0, life = skill.lifeCost + skill.resourceCost)
            else SkillCost(resource = skill.resourceCost, life = skill.lifeCost)
    }
}
