package com.stratum.core.domain.actor

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Levelling.
 *
 * The curve is engine policy rather than pack data: a pack that could flatten it
 * could trivialise every fight in every other pack loaded alongside it.
 */
object Progression {

    const val MAX_LEVEL = 60

    /** Experience needed to go from [level] to the next one. */
    fun experienceForNextLevel(level: Int): Int {
        if (level >= MAX_LEVEL) return Int.MAX_VALUE
        return (BASE_REQUIREMENT * level.toDouble().pow(CURVE_EXPONENT)).roundToInt()
    }

    /**
     * Applies experience, returning the new level and remainder. Loops because a
     * boss kill at a low level can legitimately grant several levels at once.
     */
    fun apply(level: Int, currentExperience: Int, gained: Int): LevelUpResult {
        var newLevel = level
        var pool = currentExperience + gained
        var levelsGained = 0

        while (newLevel < MAX_LEVEL) {
            val needed = experienceForNextLevel(newLevel)
            if (pool < needed) break
            pool -= needed
            newLevel++
            levelsGained++
        }

        return LevelUpResult(
            level = newLevel,
            experience = if (newLevel >= MAX_LEVEL) 0 else pool,
            levelsGained = levelsGained,
        )
    }

    /** Stat growth per level, applied on top of the class's base. */
    fun healthBonusFor(level: Int): Int = (level - 1) * HEALTH_PER_LEVEL

    fun attackBonusFor(level: Int): Int = (level - 1) * ATTACK_PER_LEVEL

    /**
     * Difficulty of what spawns around a character of this level. Enemies scale
     * with the player so a world stays dangerous as they grow.
     */
    fun itemLevelFor(level: Int, depthBelowSurface: Int = 0): Int =
        (level + depthBelowSurface / DEPTH_PER_ITEM_LEVEL).coerceAtLeast(1)

    private const val BASE_REQUIREMENT = 60.0
    private const val CURVE_EXPONENT = 1.45
    private const val HEALTH_PER_LEVEL = 12
    private const val ATTACK_PER_LEVEL = 3
    /** Every few blocks down is worth a level of item quality. */
    private const val DEPTH_PER_ITEM_LEVEL = 4
}

data class LevelUpResult(val level: Int, val experience: Int, val levelsGained: Int) {
    val leveledUp: Boolean get() = levelsGained > 0
}
