package com.stratum.agents

import com.stratum.content.igbo.IgboContentPack
import kotlin.test.Test
import kotlin.test.assertTrue

/** The studio's Surveyor writes microvoxel terrain, and its mistakes come back as fixable problems. */
class SurveyorTest {

    private val check = DraftCheck(listOf(IgboContentPack.pack))
    private val draft = PackSections.empty("test.realm", "Test Realm", "Studio")

    private fun terrain(json: String) = PackSections.parse("""{ "terrain": $json }""")

    @Test
    fun `a well-formed microvoxel terrain passes`() {
        val fragment = terrain(
            """{ "generator": "stratum:microvoxel", "options": { "block.asphalt": "igbo:granite" },
                 "passes": [ { "id": "micro:terrain", "options": { "height": "0.3" } }, { "id": "micro:city_plan", "options": { "maxFloors": "2" } },
                             { "id": "micro:roads" }, { "id": "micro:buildings" }, { "id": "micro:trees" } ] }""",
        )
        val problems = check.problems(draft, fragment, listOf("terrain"), "test.realm")
        assertTrue(problems.isEmpty(), "problems: $problems")
    }

    @Test
    fun `an unknown stage, a bad option and a missing block are each reported`() {
        fun problemsOf(json: String) = check.problems(draft, terrain(json), listOf("terrain"), "test.realm")
        val stage = problemsOf("""{ "generator": "stratum:microvoxel", "passes": [ { "id": "micro:volcanoes" } ] }""")
        assertTrue(stage.any { "micro:volcanoes" in it && "micro:terrain" in it }, "$stage")
        val option = problemsOf("""{ "generator": "stratum:microvoxel", "passes": [ { "id": "micro:terrain", "options": { "height": "tall" } } ] }""")
        assertTrue(option.any { "height" in it }, "$option")
        val block = problemsOf("""{ "generator": "stratum:microvoxel", "options": { "block.brick": "test.realm:nope" } }""")
        assertTrue(block.any { "test.realm:nope" in it }, "$block")
    }

    @Test
    fun `the surveyor's brief names every stage it may use`() {
        val brief = StandardCrew.surveyor.brief
        listOf("micro:terrain", "micro:city_plan", "micro:roads", "micro:buildings", "micro:groundcover", "micro:trees").forEach {
            assertTrue(it in brief, "brief does not mention $it")
        }
    }
}
