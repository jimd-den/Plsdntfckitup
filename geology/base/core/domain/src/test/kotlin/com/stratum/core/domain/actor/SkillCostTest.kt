package com.stratum.core.domain.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCostTest {

    private val skill = SkillDefinition("t:bolt", "Bolt", damageTypeId = "t:fire", resourceCost = 20, lifeCost = 5)

    @Test
    fun `a life-pays build pays the resource cost in life`() {
        assertEquals(SkillCost(20, 5), SkillCost.of(skill, lifePaysCosts = false))
        assertEquals(SkillCost(0, 25), SkillCost.of(skill, lifePaysCosts = true))
    }

    @Test
    fun `an empty resource pool does not stop a life-paid skill, but a life cost must leave you standing`() {
        val paidInLife = SkillCost.of(skill, lifePaysCosts = true)
        assertTrue(paidInLife.affordable(resource = 0, health = 100))
        assertFalse(paidInLife.affordable(resource = 0, health = 25))
        assertFalse(SkillCost.of(skill, lifePaysCosts = false).affordable(resource = 0, health = 100))
    }
}
