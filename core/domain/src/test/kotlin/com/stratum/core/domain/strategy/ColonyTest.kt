package com.stratum.core.domain.strategy

import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.strategy.StandardStrategy.food
import com.stratum.core.domain.strategy.StandardStrategy.metal
import com.stratum.core.domain.strategy.StandardStrategy.stone
import com.stratum.core.domain.strategy.StandardStrategy.timber
import com.stratum.core.domain.world.BlockMaterial
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColonyTest {

    private val book = StrategyBook(StandardStrategy.resources, StandardStrategy.structures, StandardStrategy.units)
    private val start = Outpost("o", "Hold", 0, 0, structures = mapOf(StandardStrategy.hearth.id to 1))
    private fun stocked(vararg pairs: Pair<ResourceDefinition, Float>) = start.copy(stockpile = pairs.associate { (r, n) -> r.id to n })

    @Test
    fun `a farm with hands to work it feeds the outpost by the minute`() {
        val farming = start.copy(structures = start.structures + (StandardStrategy.farm.id to 1))
        val later = Colony.produced(farming, book, minutes = 5f)

        assertEquals(1f, Colony.staffing(farming, book))
        assertEquals(30f, later.has(food.id))
    }

    @Test
    fun `too few people means less of everything`() {
        val busy = start.copy(structures = start.structures + (StandardStrategy.farm.id to 2) + (StandardStrategy.quarry.id to 2))
        assertEquals(0.5f, Colony.staffing(busy, book))
        assertEquals(6f, Colony.produced(busy, book, 1f).has(food.id))
    }

    @Test
    fun `stock never exceeds storage, and a storehouse raises it`() {
        val farming = start.copy(structures = start.structures + (StandardStrategy.farm.id to 1))
        assertEquals(Colony.BASE_STORAGE, Colony.produced(farming, book, 1000f).has(food.id))
        val stored = farming.copy(structures = farming.structures + (StandardStrategy.storehouse.id to 1))
        assertEquals(Colony.BASE_STORAGE + 200f, Colony.produced(stored, book, 1000f).has(food.id))
    }

    @Test
    fun `building costs its price and respects what it requires`() {
        assertIs<Affordability.Missing>(Colony.canBuild(start, book, StandardStrategy.farm.id))
        val rich = stocked(timber to 100f, stone to 100f)
        assertEquals(Affordability.Requires(StandardStrategy.quarry.id), Colony.canBuild(rich, book, StandardStrategy.forge.id))

        val farmed = assertNotNull(Colony.built(rich, book, StandardStrategy.farm.id))
        assertEquals(85f, farmed.has(timber.id))
        assertEquals(1, farmed.count(StandardStrategy.farm.id))
    }

    @Test
    fun `soldiers need a barracks, and guard the outpost once recruited`() {
        val rich = stocked(food to 50f, metal to 10f, timber to 100f, stone to 100f)
        assertEquals(Affordability.Requires(StandardStrategy.barracks.id), Colony.canRecruit(rich, book, StandardStrategy.militia.id))

        val barracks = Colony.built(rich, book, StandardStrategy.barracks.id)!!
        val guarded = Colony.recruited(barracks, book, StandardStrategy.militia.id)!!
        assertEquals(1, guarded.garrison[StandardStrategy.militia.id])
        assertEquals(Colony.defense(barracks, book) + StandardStrategy.militia.defense, Colony.defense(guarded, book))
    }

    @Test
    fun `deposits turn blocks and food into resources by what they are made of`() {
        val materials = mapOf("t:log" to BlockMaterial.WOOD, "t:granite" to BlockMaterial.STONE, "t:gem" to BlockMaterial.RITUAL)
        val (gained, used) = Colony.depositValue(mapOf("t:log" to 4, "t:granite" to 3, "stratum:raw_meat" to 2, "t:gem" to 5), book, materials::get)

        assertEquals(mapOf(timber.id to 4f, stone.id to 3f, food.id to 6f), gained)
        assertEquals(setOf("t:log", "t:granite", "stratum:raw_meat"), used.keys, "a gem is worth nothing here, so it stays in the bag")
    }

    @Test
    fun `a well defended outpost holds, a bare one is sacked but keeps its hearth`() {
        val fortified = start.copy(structures = start.structures + (StandardStrategy.watchtower.id to 3), stockpile = mapOf(food.id to 100f))
        val (held, won) = Colony.resolveRaid(fortified, book, Random(1))
        assertTrue(won.defended)
        assertEquals(1, held.raidsSurvived)

        val bare = start.copy(stockpile = mapOf(food.id to 100f))
        val (sacked, lost) = Colony.resolveRaid(bare, book, Random(1))
        assertTrue(!lost.defended)
        assertEquals(70f, sacked.has(food.id))
        assertEquals(1, sacked.count(StandardStrategy.hearth.id), "the last hearth is never burned")
    }

    @Test
    fun `raids grow with each one survived and with the outpost's wealth`() {
        assertTrue(Colony.raidStrength(start.copy(raidsSurvived = 3)) > Colony.raidStrength(start))
        assertTrue(Colony.raidStrength(stocked(food to 900f)) > Colony.raidStrength(start))
        assertTrue(Colony.raidInterval(start.copy(raidsSurvived = 5)) < Colony.raidInterval(start))
    }

    @Test
    fun `a liberated town's buildings become structures`() {
        val structures = StandardStrategy.structuresFor(listOf(BuildingRole.HOUSE, BuildingRole.HOUSE, BuildingRole.SMITHY, BuildingRole.TEMPLE))
        assertEquals(2, structures[StandardStrategy.hearth.id])
        assertEquals(1, structures[StandardStrategy.forge.id])
        assertEquals(1, structures[StandardStrategy.quarry.id], "a forge's requirement comes with it")
        assertNull(structures["stratum:temple"])
    }
}
