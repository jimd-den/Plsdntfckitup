package com.stratum.core.domain.world

/** How much the body's needs matter. */
enum class SurvivalMode(val label: String, val description: String) {
    OFF("Off", "No hunger, thirst or cold. Pure action."),
    GENTLE("Gentle", "Needs slow you down when they run low, but never kill you."),
    HARSH("Harsh", "Starvation, thirst and cold wear you down until you die."),
}

/**
 * How a world plays, chosen by the player before it is generated and
 * suggested by the packs that make it.
 *
 * Every rule is a dial rather than a switch where it can be, because the
 * same pack should be able to be a relaxed story game for one player and a
 * survival siege for another -- the systems are the same, only the pressure
 * changes.
 */
data class WorldRules(
    val survival: SurvivalMode = SurvivalMode.GENTLE,
    /** Scales how often towns appear; 0 is a world with none. */
    val townDensity: Float = 1f,
    /** Begin in a welcoming town rather than in the wilds. */
    val startInTown: Boolean = true,
    /** Scales how many monsters roam at once. */
    val monsterDensity: Float = 1f,
    /** Whether hostile factions raid the player's outposts. */
    val raids: Boolean = true,
    /** Real minutes from one dawn to the next. */
    val dayLengthMinutes: Float = 20f,
    /** Scales how much loot drops. */
    val lootMultiplier: Float = 1f,
    /** Scales experience earned. */
    val experienceMultiplier: Float = 1f,
    /** Share of progress toward the next level lost on death. */
    val deathPenalty: Float = 0.25f,
) {
    init {
        require(townDensity in 0f..MAX_DENSITY) { "townDensity $townDensity is outside 0..$MAX_DENSITY" }
        require(monsterDensity in MIN_MONSTERS..MAX_DENSITY) { "monsterDensity $monsterDensity is outside $MIN_MONSTERS..$MAX_DENSITY" }
        require(dayLengthMinutes in MIN_DAY..MAX_DAY) { "a day of $dayLengthMinutes minutes is outside $MIN_DAY..$MAX_DAY" }
        require(lootMultiplier > 0f && experienceMultiplier > 0f) { "multipliers must be positive" }
        require(deathPenalty in 0f..1f) { "deathPenalty $deathPenalty is not a share" }
    }

    companion object {
        const val MAX_DENSITY = 3f
        const val MIN_MONSTERS = 0.25f
        const val MIN_DAY = 2f
        const val MAX_DAY = 120f
    }
}

/** A named set of rules: the quick way to choose how a world plays. */
data class RulesPreset(val id: String, val name: String, val description: String, val rules: WorldRules)

object RulesPresets {
    val adventure = RulesPreset("adventure", "Adventure", "The default: fights, loot, towns, and needs that nudge rather than punish.", WorldRules())
    val story = RulesPreset(
        "story", "Story", "Fewer monsters, no survival, no raids. For exploring and reading.",
        WorldRules(survival = SurvivalMode.OFF, monsterDensity = 0.6f, raids = false, experienceMultiplier = 1.3f, deathPenalty = 0f),
    )
    val survivor = RulesPreset(
        "survivor", "Survivor", "Harsh needs, long nights, fewer towns. Every meal is earned.",
        WorldRules(survival = SurvivalMode.HARSH, townDensity = 0.5f, monsterDensity = 1.3f, dayLengthMinutes = 30f, lootMultiplier = 0.8f),
    )
    val conqueror = RulesPreset(
        "conqueror", "Conqueror", "Many towns, frequent raids. Take strongholds, build outposts, hold the land.",
        WorldRules(survival = SurvivalMode.GENTLE, townDensity = 1.8f, monsterDensity = 1.2f, raids = true),
    )
    val all = listOf(adventure, story, survivor, conqueror)
}
