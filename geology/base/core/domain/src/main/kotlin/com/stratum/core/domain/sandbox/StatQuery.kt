package com.stratum.core.domain.sandbox

/** The numbers a build breakdown can explain, as a player asks for them. */
enum class ExplainedStat(val label: String, val needsDamageType: Boolean = false, val needsSkill: Boolean = false) {
    DAMAGE("Attack power"),
    ATTACK_SPEED("Attack speed"),
    CRIT_CHANCE("Critical chance"),
    CRIT_MULTIPLIER("Critical multiplier"),
    LIFE("Maximum life"),
    ARMOUR("Armour"),
    EVASION("Evasion"),
    BLOCK("Block chance"),
    LIFE_STEAL("Life steal"),
    RESISTANCE("Resistance", needsDamageType = true),
    MAX_RESOURCE("Maximum resource"),
    MOVE_SPEED("Movement speed"),
    SKILL_POWER("Skill power", needsSkill = true),
}

/** One question for the breakdown: a stat, scoped to a damage type or a skill where it needs one. */
data class StatQuery(
    val stat: ExplainedStat,
    val damageTypeId: String? = null,
    val skillId: String? = null,
)
