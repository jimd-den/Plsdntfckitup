package com.stratum.engine.microbridge

import com.stratum.core.domain.world.WorldRules
import com.stratum.engine.microvoxel.gen.StageSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenePromptTest {

    @Test
    fun `plain words pick the land, the tradition and the mood`() {
        val scene = ScenePrompt.read("A peaceful walled kasbah town among the red dunes of the Sahara")
        assertEquals("erg", scene["landscape"])
        assertEquals("amazigh", scene["homeStyle"])
        assertEquals("on", scene["walls"])
        assertEquals(0.6f, scene.float("monsters"))
        assertTrue(scene.notes.isNotEmpty())
        val rules = scene.rules(WorldRules())
        assertTrue(rules.enemyDamage < 1f, "a peaceful scene still hits as hard")
    }

    @Test
    fun `places imply their land and their builders`() {
        assertEquals("sudano_sahelian", ScenePrompt.read("the mud mosques of Djenné")["homeStyle"])
        assertEquals("sahel_plain", ScenePrompt.read("the mud mosques of Djenné")["landscape"])
        assertEquals("coral_coast", ScenePrompt.read("an old Swahili port like Lamu")["landscape"])
        assertEquals("inselbergs", ScenePrompt.read("the stone walls of Great Zimbabwe")["landscape"])
        val continent = ScenePrompt.read("the whole continent, starting in the Ethiopian highlands")
        assertEquals("africa", continent["landscape"])
        assertEquals("highland_traps", continent["home"])
    }

    @Test
    fun `a scene lays its options over the stages`() {
        val base = listOf(StageSpec("micro:terrain", mapOf("geology" to "africa")), StageSpec("micro:trees"))
        val passes = ScenePrompt.read("lush jungle hills with domed towers, dangerous").passes(base)
        val land = passes.first { it.id == "micro:terrain" }.options
        assertEquals("rainforest_basin", land["geology"])
        assertEquals("0.65", land["height"])
        val towns = passes.first { it.id == SettlementsStage.ID }.options
        assertEquals("dome,onion,flat", towns["roofs"])
        assertTrue(passes.any { it.id == "micro:groundcover" })
    }

    @Test
    fun `a model's reply is read leniently and checked`() {
        val reply = """Sure! {"landscape": "rift_valley", "relief": 7, "roofs": ["cone", "spaceship"], "towns": "2.5", "colour": "blue", "why": "Maasai country"} done"""
        val scene = ScenePrompt.parse(reply)
        assertEquals("rift_valley", scene["landscape"])
        assertEquals("1.00", scene["relief"])
        assertEquals("cone", scene["roofs"])
        assertEquals("2.50", scene["towns"])
        assertEquals("Maasai country", scene.notes.first())
        assertTrue(scene.notes.any { "colour" in it })
        assertTrue(ScenePrompt.systemPrompt().contains("landscape"))
    }
}
