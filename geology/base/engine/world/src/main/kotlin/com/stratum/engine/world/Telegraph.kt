package com.stratum.engine.world

import com.stratum.core.domain.actor.PendingCast
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.world.WorldPoint

/** The outline a telegraph draws on the ground. */
enum class TelegraphShape { CIRCLE, CONE, LANE }

/**
 * A wind-up made visible: the ground a cast will land on, and how close it
 * is to landing. Engine state rather than a renderer effect, because whether
 * the player was standing in it when it filled is a rule, not a look.
 */
data class Telegraph(
    val casterId: String,
    val skillId: String,
    val shape: TelegraphShape,
    /** Centre of a circle; apex of a cone or lane. */
    val center: WorldPoint,
    /** Radius of a circle, length of a cone or lane. */
    val radius: Float,
    val angleDegrees: Float = 0f,
    val halfWidth: Float = 0f,
    val aimX: Float = 0f,
    val aimY: Float = 1f,
    /** 0 as the wind-up begins, 1 as it lands. */
    val progress: Float,
    val color: Long,
    /** Aimed at the player, rather than cast by them. */
    val hostile: Boolean,
) {
    companion object {
        /** How [cast] of [skill], wound up by someone standing at [origin], looks on the ground. */
        fun of(casterId: String, skill: SkillDefinition, cast: PendingCast, origin: WorldPoint, hostile: Boolean): Telegraph {
            val base = Telegraph(
                casterId, skill.id, TelegraphShape.CIRCLE, cast.target, SkillTargeting.areaRadius(skill),
                aimX = cast.aimX, aimY = cast.aimY, progress = cast.progress, color = skill.color, hostile = hostile,
            )
            return when (skill.delivery) {
                SkillDelivery.NOVA, SkillDelivery.SELF, SkillDelivery.SUMMON -> base.copy(center = origin, radius = skill.reach)
                SkillDelivery.AREA, SkillDelivery.ZONE, SkillDelivery.CHAIN -> base
                SkillDelivery.MELEE -> base.copy(radius = 1f)
                SkillDelivery.CONE -> base.copy(shape = TelegraphShape.CONE, center = origin, radius = skill.reach + SkillTargeting.REACH_FORGIVENESS, angleDegrees = skill.area.angleDegrees)
                SkillDelivery.BEAM, SkillDelivery.DASH, SkillDelivery.PROJECTILE ->
                    base.copy(shape = TelegraphShape.LANE, center = origin, radius = skill.range.toFloat(), halfWidth = skill.area.halfWidth)
            }
        }
    }
}
