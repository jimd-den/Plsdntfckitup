package com.stratum.core.domain.actor

/**
 * A class ability. Packs define them; the engine only knows how to spend
 * resource, start a cooldown and apply a shape.
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val damageTypeId: String,
    /** Multiplies attack power, so a skill scales with gear rather than replacing it. */
    val powerMultiplier: Float = 1.5f,
    val resourceCost: Int = 10,
    val cooldownSeconds: Float = 4f,
    val shape: SkillShape = SkillShape.STRIKE,
    /** Blocks. For [SkillShape.NOVA] this is the radius; for others, the reach. */
    val range: Int = 3,
    val color: Long = 0xFFFFC107,
)

/**
 * How a skill picks its targets. Kept to three because each one has to be
 * readable at a glance on a phone-sized isometric view.
 */
enum class SkillShape {
    /** One target, hardest hitting. */
    STRIKE,

    /** Everything within range of the caster. */
    NOVA,

    /** Everything in a line toward the facing direction. */
    LANCE,
}

/** Live cooldown state, keyed by skill id. */
data class SkillCooldowns(private val remaining: Map<String, Float> = emptyMap()) {

    fun secondsLeft(skillId: String): Float = remaining[skillId] ?: 0f

    fun isReady(skillId: String): Boolean = secondsLeft(skillId) <= 0f

    fun started(skill: SkillDefinition): SkillCooldowns =
        SkillCooldowns(remaining + (skill.id to skill.cooldownSeconds))

    fun advanced(deltaSeconds: Float): SkillCooldowns = SkillCooldowns(
        remaining.mapValues { (_, value) -> (value - deltaSeconds).coerceAtLeast(0f) }
            .filterValues { it > 0f },
    )

    fun fractionRemaining(skill: SkillDefinition): Float =
        if (skill.cooldownSeconds <= 0f) 0f
        else (secondsLeft(skill.id) / skill.cooldownSeconds).coerceIn(0f, 1f)
}
