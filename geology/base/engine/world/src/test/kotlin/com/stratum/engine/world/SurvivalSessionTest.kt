package com.stratum.engine.world

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.survival.StandardSurvival
import com.stratum.core.domain.survival.Survival
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val water = BlockType("t:water", "Water", BlockMaterial.LIQUID, isSolid = false, isOpaque = false)
private val bush = BlockType("t:bush", "Bush", BlockMaterial.FOLIAGE, hardness = 0.05f)

private fun session(mode: SurvivalMode = SurvivalMode.GENTLE, dayMinutes: Float = 20f): WorldSession {
    // No wild monsters: only the body and the weather.
    val pack = TestContent.pack.copy(
        blocks = TestContent.pack.blocks + water + bush,
        enemies = TestContent.enemies.map { it.copy(spawnBiomeIds = listOf("t:nowhere")) },
    )
    val rules = WorldRules(survival = mode, dayLengthMinutes = dayMinutes)
    return WorldSession(ContentPackAssembler().assemble(listOf(pack)), WorldConfig(seed = 8L, simulationRadius = 1, rules = rules))
}

private fun WorldSession.put(dx: Int, dy: Int, dz: Int, block: BlockType) {
    val feet = player.blockPos
    editableWorld.setBlock(BlockPos(feet.x + dx, feet.y + dy, feet.z + dz), content.registry.indexOf(block.id))
}

class SurvivalSessionTest {

    private val hunger = StandardSurvival.hunger.id
    private val thirst = StandardSurvival.thirst.id
    private val warmth = StandardSurvival.warmth.id

    @Test
    fun `needs run down as time passes`() {
        val session = session()
        repeat(60) { session.tick(1f) }

        assertTrue(session.player.needs.getValue(hunger) < Survival.MAX)
        assertTrue(session.player.needs.getValue(thirst) < session.player.needs.getValue(hunger), "thirst drains faster")
    }

    @Test
    fun `with survival off nothing drains and nothing is penalised`() {
        val session = session(SurvivalMode.OFF)
        repeat(60) { session.tick(1f) }

        assertEquals(emptyMap(), session.player.needs)
        assertTrue(!session.survivalActive)
    }

    @Test
    fun `hunger weakens the swing, and eating fixes it`() {
        val session = session()
        val fed = session.playerStats.attackPower
        session.player = session.player.copy(needs = mapOf(hunger to 5f)).withItem(StandardSurvival.cookedMeat.id)
        val starving = session.playerStats.attackPower

        assertTrue(starving < fed)
        assertIs<SurvivalResult.Consumed>(session.consume(StandardSurvival.cookedMeat.id))
        assertEquals(fed, session.playerStats.attackPower)
        assertEquals(SurvivalResult.NoneHeld, session.consume(StandardSurvival.cookedMeat.id))
    }

    @Test
    fun `a harsh world kills the starving, a gentle one does not`() {
        val harsh = session(SurvivalMode.HARSH).apply { player = player.copy(needs = mapOf(hunger to 0f, thirst to 0f)) }
        val gentle = session(SurvivalMode.GENTLE).apply { player = player.copy(needs = mapOf(hunger to 0f, thirst to 0f)) }
        val start = harsh.player.health
        repeat(30) {
            harsh.tick(1f)
            gentle.tick(1f)
        }

        assertTrue(harsh.player.health < start)
        assertEquals(start, gentle.player.health)
    }

    @Test
    fun `nights outdoors are cold, and a fire keeps you warm`() {
        // A two-minute day: it is night after one minute.
        val cold = session(dayMinutes = 2f)
        val warm = session(dayMinutes = 2f).apply { put(1, 0, 0, TestContent.torch) }
        // Into the night, then both start from the same cool evening.
        listOf(cold, warm).forEach { s ->
            repeat(50) { s.tick(1f) }
            s.player = s.player.copy(needs = s.player.needs + (warmth to 32f))
            repeat(30) { s.tick(1f) }
        }

        assertTrue(cold.clock.isNight)
        assertTrue(cold.player.needs.getValue(warmth) < StandardSurvival.warmth.lowBelow, "cold: ${cold.player.needs[warmth]}")
        assertTrue(warm.player.needs.getValue(warmth) > StandardSurvival.warmth.lowBelow, "warm: ${warm.player.needs[warmth]}")
        assertTrue(warm.surroundings.nearFire)
    }

    @Test
    fun `open water quenches thirst, and only near water`() {
        val session = session().apply { player = player.copy(needs = mapOf(thirst to 20f)) }
        assertEquals(SurvivalResult.NoWaterNear, session.drink())

        session.put(1, 0, -1, water)
        assertTrue(session.canDrink)
        assertIs<SurvivalResult.Drank>(session.drink())
        assertEquals(20f + StandardSurvival.DRINK, session.player.needs.getValue(thirst))
    }

    @Test
    fun `cooking needs the ingredients and a fire`() {
        val session = session().apply { player = player.withItem(StandardSurvival.rawMeat.id) }
        val roast = "stratum:cook_meat"
        assertEquals(SurvivalResult.NeedsStation, session.make(roast))

        session.put(1, 0, 0, TestContent.torch)
        assertIs<SurvivalResult.Made>(session.make(roast))
        assertEquals(1, session.player.countOf(StandardSurvival.cookedMeat.id))
        assertEquals(0, session.player.countOf(StandardSurvival.rawMeat.id))
        assertEquals(SurvivalResult.MissingIngredients, session.make(roast))
    }
}
