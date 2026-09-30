package com.stratum.core.domain.survival

import com.stratum.core.domain.world.SurvivalMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivalTest {

    private val needs = StandardSurvival.needs
    private val hunger = StandardSurvival.hunger.id
    private val warmth = StandardSurvival.warmth.id

    @Test
    fun `hunger drains by the minute and never below empty`() {
        val after = Survival.advanced(emptyMap(), needs, Environment(), seconds = 60f)
        assertEquals(Survival.MAX - StandardSurvival.hunger.drainPerMinute, after.getValue(hunger))
        assertEquals(0f, Survival.advanced(after, needs, Environment(), seconds = 3600f).getValue(hunger))
    }

    @Test
    fun `nights are cold, and a roof and a fire each push back`() {
        val night = Environment(temperature = 0.55f, night = true)
        assertTrue(Survival.comfort(night) < StandardSurvival.warmth.lowBelow)
        assertTrue(Survival.comfort(night.copy(sheltered = true)) > Survival.comfort(night))
        assertTrue(Survival.comfort(night.copy(nearFire = true)) > StandardSurvival.warmth.lowBelow)
    }

    @Test
    fun `warmth settles toward comfort, faster by a fire`() {
        val cold = mapOf(warmth to 10f)
        val outside = Survival.advanced(cold, needs, Environment(temperature = 0.55f), 10f).getValue(warmth)
        val byFire = Survival.advanced(cold, needs, Environment(temperature = 0.55f, nearFire = true), 10f).getValue(warmth)
        assertTrue(outside > 10f)
        assertTrue(byFire > outside)
    }

    @Test
    fun `running low costs stats in gentle and harsh worlds, and nothing with survival off`() {
        val starving = mapOf(hunger to 5f)
        assertEquals(StandardSurvival.hunger.lowModifiers, Survival.penalties(starving, needs, SurvivalMode.GENTLE))
        assertEquals(emptyList(), Survival.penalties(starving, needs, SurvivalMode.OFF))
    }

    @Test
    fun `only a harsh world lets an empty need kill`() {
        val empty = mapOf(hunger to 0f)
        assertEquals(0f, Survival.healthLossPerSecond(empty, needs, SurvivalMode.GENTLE, 100))
        assertTrue(Survival.healthLossPerSecond(empty, needs, SurvivalMode.HARSH, 100) > 0f)
    }

    @Test
    fun `eating restores what the food says, capped at full`() {
        val fed = Survival.consumed(mapOf(hunger to 90f), StandardSurvival.cookedMeat)
        assertEquals(Survival.MAX, fed.getValue(hunger))
    }
}
