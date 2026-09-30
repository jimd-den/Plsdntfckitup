package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.MaterialPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenomeTest {

    private val m = ArchPalette(MaterialPalette.standard())

    private fun building(seed: Long, w: Int = 8, d: Int = 7, door: Side = Side.SOUTH): Building {
        val r = 4
        val x0 = 40; val y0 = 40
        return Building(
            x0 = x0, y0 = y0, x1 = x0 + w * r - 1, y1 = y0 + d * r - 1, base = 8, wallTop = 8 + 3 * r - 1,
            doorX = (x0 / r) + w / 2, doorY = if (door == Side.SOUTH) (y0 / r) + d - 1 else y0 / r, door = door,
            wall = m["plaster"], window = m["glass"], roof = null, r = r, seed = seed,
            windowAt = { bx, _, bz -> bz == 3 && bx % 3 == 0 }, town = seed / 3,
        )
    }

    @Test
    fun `the same seed rolls the same building`() {
        val rules = GenomeRules()
        assertEquals(BuildingGenome.roll(42L, 7L, rules), BuildingGenome.roll(42L, 7L, rules))
    }

    @Test
    fun `the space of buildings is astronomical`() {
        assertTrue(BuildingGenome.VARIANTS > 1e15, "only ${BuildingGenome.VARIANTS} distinct buildings")
        val seen = (0L until 400L).map { BuildingGenome.roll(it * 7919, it / 10, GenomeRules(variety = 0.8f)) }.toSet()
        assertTrue(seen.size > 390, "400 rolls gave ${seen.size} distinct buildings")
    }

    @Test
    fun `rules are a whitelist`() {
        val rules = GenomeRules.parse(mapOf("roofs" to "dome,flat", "materials" to "stone", "storeys" to "2-3")::get)
        repeat(200) { i ->
            val g = BuildingGenome.roll(i * 31L, i.toLong(), rules)
            assertTrue(g.roof == RoofForm.DOME || g.roof == RoofForm.FLAT, "rolled a ${g.roof}")
            assertEquals("stone", g.family)
            assertTrue(g.upper in 2..3)
        }
    }

    @Test
    fun `a vernacular town builds in its own grammar`() {
        val hausa = Vernacular.rulesFor("hausa")
        repeat(200) { i ->
            val g = BuildingGenome.roll(i * 13L, 5L, hausa)
            assertTrue(g.roof in setOf(RoofForm.FLAT, RoofForm.DOME), "a Hausa house with a ${g.roof} roof")
            assertTrue(g.wall in listOf(A.HAUSA_PLASTER, A.ADOBE, A.RENDER))
        }
        val zimbabwe = Vernacular.rulesFor("great_zimbabwe")
        assertTrue((0 until 100).all { BuildingGenome.roll(it.toLong(), 1L, zimbabwe).wall == A.DRYSTONE })
        assertTrue(Traditions.ids.all { it in Vernacular.rules }, "a tradition has no vernacular grammar")
    }

    @Test
    fun `every plan keeps the door open and the walls standing`() {
        val painter = ParametricTradition(m)
        for (plan in PlanShape.entries) for (door in listOf(Side.SOUTH, Side.NORTH)) for (seed in 0L until 12L) {
            val b = building(seed * 101 + plan.ordinal, w = 10, d = 10, door = door)
            val genome = BuildingGenome.roll(b.seed, b.town).copy(plan = plan)
            val shape = painter.shapeOf(b, genome)
            // The door cell is open two blocks high, at its middle.
            val dx = b.doorX * b.r + b.r / 2
            val dy = if (door == Side.SOUTH) b.y1 - 1 else b.y0 + 1
            for (z in b.base until b.base + 2 * b.r) {
                val v = shape.voxel(dx, dy, z)
                assertTrue(v == MaterialPalette.AIR || v == KEEP, "$plan door blocked at z=$z by ${v}")
            }
            // Something stands: the walls are drawn.
            val solid = (b.x0..b.x1).count { x -> shape.voxel(x, b.cy.toInt(), b.base + 2).let { it != KEEP && it != MaterialPalette.AIR } }
            assertTrue(solid >= 2, "$plan drew no walls")
            assertTrue(shape.top > b.wallTop, "$plan has no roof room")
        }
    }
}
