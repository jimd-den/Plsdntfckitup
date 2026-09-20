package com.stratum.core.domain.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressionTest {

    @Test
    fun `experience short of the requirement does not level`() {
        val needed = Progression.experienceForNextLevel(1)
        val result = Progression.apply(level = 1, currentExperience = 0, gained = needed - 1)
        assertEquals(1, result.level)
        assertFalse(result.leveledUp)
        assertEquals(needed - 1, result.experience)
    }

    @Test
    fun `meeting the requirement levels and carries the remainder`() {
        val needed = Progression.experienceForNextLevel(1)
        val result = Progression.apply(level = 1, currentExperience = 0, gained = needed + 7)
        assertEquals(2, result.level)
        assertTrue(result.leveledUp)
        assertEquals(7, result.experience)
    }

    @Test
    fun `one huge award can grant several levels`() {
        val result = Progression.apply(level = 1, currentExperience = 0, gained = 100_000)
        assertTrue(result.levelsGained > 1, "a boss kill granted only ${result.levelsGained} level")
        assertTrue(result.level > 2)
    }

    @Test
    fun `the curve gets steeper so later levels cost more`() {
        val early = Progression.experienceForNextLevel(2)
        val late = Progression.experienceForNextLevel(20)
        assertTrue(late > early * 5, "the curve is too flat: $early then $late")
    }

    @Test
    fun `the maximum level is a ceiling, not a wrap`() {
        val result = Progression.apply(
            level = Progression.MAX_LEVEL,
            currentExperience = 0,
            gained = Int.MAX_VALUE / 2,
        )
        assertEquals(Progression.MAX_LEVEL, result.level)
        assertEquals(0, result.experience)
        assertFalse(result.leveledUp)
    }

    @Test
    fun `levelling approaches but does not exceed the cap in one award`() {
        val result = Progression.apply(level = Progression.MAX_LEVEL - 1, currentExperience = 0, gained = 10_000_000)
        assertEquals(Progression.MAX_LEVEL, result.level)
    }

    @Test
    fun `stat bonuses start at zero for a fresh character`() {
        assertEquals(0, Progression.healthBonusFor(1))
        assertEquals(0, Progression.attackBonusFor(1))
        assertTrue(Progression.healthBonusFor(10) > 0)
    }

    @Test
    fun `depth raises item level so descending is worth doing`() {
        val surface = Progression.itemLevelFor(level = 10, depthBelowSurface = 0)
        val deep = Progression.itemLevelFor(level = 10, depthBelowSurface = 20)
        assertEquals(10, surface)
        assertTrue(deep > surface, "digging deeper did not improve loot: $surface then $deep")
    }

    @Test
    fun `item level never drops below one`() {
        assertTrue(Progression.itemLevelFor(level = 0, depthBelowSurface = 0) >= 1)
    }
}

class SkillCooldownsTest {

    private val skill = SkillDefinition(
        id = "test:bolt",
        name = "Bolt",
        damageTypeId = "test:fire",
        cooldownSeconds = 4f,
    )

    @Test
    fun `a skill starts ready`() {
        assertTrue(SkillCooldowns().isReady(skill.id))
    }

    @Test
    fun `casting starts the cooldown and it expires on time`() {
        var cooldowns = SkillCooldowns().started(skill)
        assertFalse(cooldowns.isReady(skill.id))
        assertEquals(1f, cooldowns.fractionRemaining(skill))

        cooldowns = cooldowns.advanced(2f)
        assertFalse(cooldowns.isReady(skill.id))
        assertEquals(0.5f, cooldowns.fractionRemaining(skill), absoluteTolerance = 1e-5f)

        cooldowns = cooldowns.advanced(2f)
        assertTrue(cooldowns.isReady(skill.id))
    }

    @Test
    fun `advancing past zero does not go negative`() {
        val cooldowns = SkillCooldowns().started(skill).advanced(999f)
        assertEquals(0f, cooldowns.secondsLeft(skill.id))
        assertTrue(cooldowns.isReady(skill.id))
    }

    @Test
    fun `cooldowns are tracked per skill`() {
        val other = skill.copy(id = "test:nova", cooldownSeconds = 10f)
        val cooldowns = SkillCooldowns().started(skill)
        assertFalse(cooldowns.isReady(skill.id))
        assertTrue(cooldowns.isReady(other.id))
    }
}
