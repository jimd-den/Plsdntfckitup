package com.stratum.core.domain.settlement.culture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityGenomeTest {

    @Test
    fun `the same seed and people give the same town, every time`() {
        Cultures.ids.forEach { id ->
            assertEquals(CityGenerator.roll(42L, id), CityGenerator.roll(42L, id))
        }
    }

    @Test
    fun `every people makes its own kinds of places`() {
        Cultures.all.forEach { people ->
            val towns = (0L until 400L).map { CityGenerator.roll(it * 7919L, people.id) }
            assertTrue(towns.all { it.culture == people.id })
            val forms = towns.map { it.form }.toSet()
            assertTrue(forms.all { f -> people.forms.any { it.first == f } }, "${people.id} made a ${forms - people.forms.map { it.first }.toSet()}")
            assertTrue(forms.size >= minOf(3, people.forms.size), "${people.id} only made $forms")
            // Every town is ruled, has a seat and its homes, and makes something.
            assertTrue(towns.all { Quarter.SEAT in it.quarters && Quarter.COMPOUNDS in it.quarters && it.crafts.isNotEmpty() })
            // A walled town has gates; an open one none.
            assertTrue(towns.all { (it.gates > 0) == it.walled }, "${people.id}: walls without gates or gates without walls")
        }
    }

    @Test
    fun `a herding people's kraal rings its byre, a hausa city walls itself`() {
        val kraal = CityGenerator.roll(3L, "zulu", Form.KRAAL)
        assertEquals(Heart.CATTLE_BYRE, kraal.heart)
        val birni = CityGenerator.roll(5L, "hausa", Form.WALLED_CITY)
        assertTrue(birni.walled && birni.gates >= 3 && Quarter.GATES in birni.quarters)
    }

    @Test
    fun `towns hardly ever repeat`() {
        val seen = HashSet<String>()
        var total = 0
        Cultures.ids.forEach { id ->
            repeat(2000) { i ->
                val g = CityGenerator.roll(i * 104729L + id.hashCode(), id)
                seen += "${g.culture}|${g.form}|${g.rule}|${g.crafts}|${g.heart}|${g.walled}|${g.founding}|${g.history.map { it.text }}"
                total++
            }
        }
        assertTrue(seen.size > total * 0.995, "${total - seen.size} repeats in $total towns")
    }

    @Test
    fun `there are millions of cities, millions of smaller settlements, and many names for each people`() {
        val cities = Cultures.ids.sumOf { CityGenerator.permutations(it, cities = true) }
        val settlements = Cultures.ids.sumOf { CityGenerator.permutations(it, cities = false) }
        assertTrue(cities > 100_000_000L, "only $cities cities")
        assertTrue(settlements > 100_000_000L, "only $settlements settlements")
        // Every people on its own, not only all together.
        Cultures.ids.forEach { assertTrue(CityGenerator.permutations(it, cities = false) > 1_000_000L, "$it: few settlements") }
        Cultures.ids.forEach { assertTrue(CityGenerator.names(it) >= 20, "$it has only ${CityGenerator.names(it)} names") }
    }

    @Test
    fun `history leaves its marks and the story tells it`() {
        val towns = (0L until 3000L).map { CityGenerator.roll(it, Cultures.ids[(it % Cultures.ids.size).toInt()]) }
        Mark.entries.forEach { m -> assertTrue(towns.any { m in it.marks }, "no town was ever marked $m") }
        towns.filter { Mark.STRANGERS in it.marks }.forEach { assertTrue(Quarter.STRANGERS in it.quarters) }
        towns.filter { Mark.WALL in it.marks }.forEach { assertTrue(it.walled) }
        val story = towns.first { it.history.size >= 3 }.describe()
        assertTrue(story.contains("Founded some") && story.contains("years ago,"), story)
        // Events run oldest first.
        towns.forEach { t -> assertEquals(t.history.map { it.yearsAgo }.sortedDescending(), t.history.map { it.yearsAgo }) }
    }
}
