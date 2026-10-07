package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.RoadGeometry
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.settlement.culture.CityGenerator
import com.stratum.core.domain.settlement.culture.Cultures
import com.stratum.core.domain.settlement.culture.Form
import com.stratum.core.domain.settlement.culture.Pattern
import com.stratum.core.domain.settlement.culture.Quarter
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AfricanLayoutTest {

    private val house = BuildingTemplate("t:house", "House", BuildingRole.HOUSE, 5, 5, 3, "t:mud", roofBlockId = "t:thatch")
    private val recipe = SettlementRecipe(
        id = "t:town", name = "Town", layoutId = SettlementRecipe.AFRICAN, minRadius = 22, maxRadius = 28, chance = 1f,
        roadBlockId = "t:laterite", foundationBlockId = "t:earth", buildings = listOf(house),
    )

    private fun layout(form: Form, culture: String, seed: Long = 11L): Pair<com.stratum.core.domain.settlement.culture.CityGenome, Layout> {
        val g = CityGenerator.roll(seed, culture, form)
        val radius = (25 * form.scale.radius).toInt().coerceIn(SettlementRecipe.MIN_RADIUS, SettlementRecipe.MAX_RADIUS)
        val site = SettlementSite(0, 0, 10, radius, g)
        return g to AfricanLayout.arrange(site, recipe, Random(seed))
    }

    /** One people that makes each pattern, for the tests to lay out. */
    private val samples = mapOf(
        Pattern.RINGED to (Form.KRAAL to "zulu"),
        Pattern.COMPOUND to (Form.HOMESTEAD to "kassena"),
        Pattern.DISPERSED to (Form.VILLAGE_GROUP to "igbo"),
        Pattern.TERRACES to (Form.CLIFF_VILLAGE to "dogon"),
        Pattern.KSAR to (Form.KSAR to "amazigh"),
        Pattern.WALLED to (Form.WALLED_CITY to "hausa"),
        Pattern.RADIAL to (Form.ROYAL_CAPITAL to "yoruba"),
        Pattern.LANES to (Form.STONE_TOWN to "swahili"),
        Pattern.CITADEL to (Form.HILL_CITADEL to "great_zimbabwe"),
        Pattern.LINEAR to (Form.RIVER_PORT to "nubian"),
    )

    @Test
    fun `every pattern lays out a full town with no building on another or on a road`() {
        assertEquals(Pattern.entries.toSet(), samples.keys)
        for (seed in 1L..16L) samples.forEach { (pattern, sample) ->
            val (g, l) = layout(sample.first, sample.second, seed)
            val built = assertNotNull(l.recipe)
            assertTrue(l.buildings.size >= if (g.form.scale == com.stratum.core.domain.settlement.culture.Scale.SMALL) 5 else 14, "$pattern seed $seed: only ${l.buildings.size} buildings")
            l.buildings.forEachIndexed { i, a ->
                l.buildings.drop(i + 1).forEach { b ->
                    assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.depth <= b.y || b.y + b.depth <= a.y, "$pattern: ${a.template.name} overlaps ${b.template.name}")
                }
                for (y in a.y until a.y + a.depth) for (x in a.x until a.x + a.width) {
                    assertTrue(l.roads.none { RoadGeometry.covers(it, x, y) }, "$pattern: ${a.template.name} stands on a road")
                }
            }
            // The seat stands, once, named for who rules.
            val seats = l.buildings.filter { it.template.role == BuildingRole.HALL }
            assertEquals(1, seats.size, "$pattern seed $seed: ${seats.size} seats")
            assertEquals(g.rule.seat, seats.single().template.name)
            // Walled exactly when the town is.
            assertEquals(g.walled, built.wallBlockId != null, "$pattern")
        }
    }

    @Test
    fun `a town's quarters are built -- granaries, gatehouses and its crafts' workshops, named its way`() {
        val (g, l) = layout(Form.WALLED_CITY, "hausa", 21L)
        val roles = l.buildings.map { it.template.role }.toSet()
        assertTrue(BuildingRole.TOWER in roles, "no gatehouses in a walled city")
        g.quarters.mapNotNull { it.role }.filter { it != BuildingRole.HALL }.forEach { role ->
            assertTrue(l.recipe!!.buildings.any { it.role == role }, "no $role made for ${g.quarters}")
        }
        val craftNames = g.crafts.map { it.workplace }
        assertTrue(l.recipe!!.buildings.any { it.name in craftNames }, "no workshop named for its crafts $craftNames")
    }

    @Test
    fun `a kraal rings its byre with houses facing in, and has one way out`() {
        val (_, l) = layout(Form.KRAAL, "zulu", 4L)
        val byre = l.buildings.minByOrNull { hypot(it.x + it.width / 2f, it.y + it.depth / 2f) }!!
        assertEquals(BuildingRole.FARM, byre.template.role, "the heart is a ${byre.template.name}")
        assertEquals(1, l.roads.size, "a kraal has one way in")
        val houses = l.buildings.filter { it.template.role == BuildingRole.HOUSE }
        assertTrue(houses.size >= 5)
        // Doors face the byre.
        houses.forEach { h ->
            val dx = -(h.x + h.width / 2f); val dy = -(h.y + h.depth / 2f)
            assertTrue(h.door.dx * dx + h.door.dy * dy > 0, "a house turns its back on the byre")
        }
    }

    @Test
    fun `towns are planned by the world's planner with their own character, size and name`() {
        val planner = SettlementPlanner(9L, listOf(recipe), biomeAt = { _, _ -> null }, groundAt = { _, _ -> 10 }, cultureAt = { x, _ -> Cultures.ids[Math.floorMod(x / 100, Cultures.ids.size)] })
        val plans = (-6..6).flatMap { cy -> (-6..6).mapNotNull { cx -> planner.planFor(cx, cy) } }
        assertTrue(plans.size > 60)
        assertTrue(plans.all { it.character != null && it.name == it.character!!.name })
        assertTrue(plans.map { it.character!!.form.pattern }.toSet().size >= 6, "few kinds of town: ${plans.map { it.character!!.form }.toSet()}")
        // Cities are bigger than hamlets.
        val big = plans.filter { it.character!!.form.scale == com.stratum.core.domain.settlement.culture.Scale.LARGE }.map { it.radius }
        val small = plans.filter { it.character!!.form.scale == com.stratum.core.domain.settlement.culture.Scale.SMALL }.map { it.radius }
        if (big.isNotEmpty() && small.isNotEmpty()) assertTrue(big.average() > small.average() * 1.5)
        // The same world gives the same towns.
        val again = SettlementPlanner(9L, listOf(recipe), biomeAt = { _, _ -> null }, groundAt = { _, _ -> 10 }, cultureAt = { x, _ -> Cultures.ids[Math.floorMod(x / 100, Cultures.ids.size)] })
        assertEquals(plans, (-6..6).flatMap { cy -> (-6..6).mapNotNull { cx -> again.planFor(cx, cy) } })
    }

    @Test
    fun `a whole region of towns plans quickly`() {
        val planner = SettlementPlanner(13L, listOf(recipe), biomeAt = { _, _ -> null }, groundAt = { _, _ -> 10 }, cultureAt = { x, y -> Cultures.ids[Math.floorMod(x * 7 + y, Cultures.ids.size)] })
        val start = System.nanoTime()
        val n = (0 until 20).sumOf { cy -> (0 until 20).count { cx -> planner.planFor(cx, cy) != null } }
        val ms = (System.nanoTime() - start) / 1e6
        println("planned $n towns in ${"%.1f".format(ms)} ms: ${"%.2f".format(ms / n)} ms a town")
        assertTrue(ms / n.coerceAtLeast(1) < 60, "${"%.1f".format(ms / n)} ms a town over $n towns")
    }

    @Test
    fun `the quarter list drives what is built`() {
        // A herding camp has no market; a stone town has its waterfront.
        val (camp, campLayout) = layout(Form.CAMP, "pastoral", 2L)
        assertTrue(Quarter.MARKET !in camp.quarters || campLayout.buildings.isNotEmpty())
        val (port, portLayout) = layout(Form.STONE_TOWN, "swahili", 8L)
        if (Quarter.WATERFRONT in port.quarters) assertTrue(portLayout.buildings.any { it.template.role == BuildingRole.WAREHOUSE })
    }
}
