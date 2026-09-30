package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.engine.microbridge.SettlementsStage
import com.stratum.engine.microvoxel.gen.StageParam
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.gen.TerrainStage
import com.stratum.engine.microvoxel.gen.TreesStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The terrain is reshaped while the world is played, and the home town with it. */
class HotTerrainTest {

    private val igbo = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
    private val content = igbo.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
    private val session = WorldSession(content = content, config = WorldConfig(seed = 20260928L, simulationRadius = 2))
    private val hot = assertNotNull(session.hotTerrain, "a microvoxel world is hot-editable")

    private fun home() = assertNotNull(session.settlementsNear(4).firstOrNull { it.contains(0, 0) }, "no home town")

    private fun heights(): List<Int> = (-40..40 step 8).flatMap { x -> (-40..40 step 8).map { y -> session.world.surfaceAt(x + 60, y + 60) } }

    @Test
    fun `every running stage describes its knobs, with defaults inside their range`() {
        val catalogue = hot.catalogue()
        assertTrue(catalogue.first().id == TerrainStage.ID, "the land comes first: ${catalogue.map { it.id }}")
        val towns = assertNotNull(catalogue.firstOrNull { it.id == SettlementsStage.ID }, "the towns stage is offered")
        assertTrue(towns.params.any { it.key == "homeSize" }, "the home town can be resized")
        for (info in catalogue) for (p in info.params) if (p is StageParam.Number) {
            val d = p.default.toFloat()
            assertTrue(d in p.min..p.max, "${info.id}.${p.key} default $d outside ${p.min}..${p.max}")
        }
    }

    @Test
    fun `raising the land regenerates the world around the player, who stays above ground`() {
        val before = heights()
        val passes = MicrovoxelTerrainGenerator.withStage(hot.passes, StageSpec(TerrainStage.ID, mapOf("height" to "0.9", "mountains" to "1.6")))
        assertNull(session.retuneTerrain(passes))
        assertEquals(1, hot.revision)
        val after = heights()
        assertNotEquals(before, after, "the land did not change")
        val feet = session.player.blockPos
        assertTrue(!session.world.isSolid(feet), "the player is buried at $feet")
        assertEquals("0.9", session.config.terrainPasses!!.first { it.id == TerrainStage.ID }.options["height"], "the tuning is kept for the save")
    }

    @Test
    fun `a bad value is refused and the world is left as it was`() {
        val before = heights()
        val error = session.retuneTerrain(hot.passes + StageSpec("micro:nonsense"))
        assertNotNull(error)
        assertEquals(0, hot.revision)
        assertEquals(before, heights())
        assertNull(session.config.terrainPasses)
    }

    @Test
    fun `the home town can be made bigger and walled`() {
        val small = home()
        val towns = hot.passes.first { it.id == SettlementsStage.ID }
        assertNull(session.retuneTerrain(MicrovoxelTerrainGenerator.withStage(hot.passes, towns.copy(options = towns.options + mapOf("homeSize" to "1.8", "homeWalls" to "on")))))
        val big = home()
        assertTrue(big.radius > small.radius, "home radius ${small.radius} -> ${big.radius}")
        assertTrue(big.walled, "the home town has no wall")
        // Still where the player begins, and still built: a building's floor is solid.
        val b = big.buildings.first()
        assertTrue(session.world.isSolid(BlockPos(b.x + 1, b.y + 1, big.groundZ)), "no floor under ${b.template.id}")
    }

    @Test
    fun `a stage can be switched off and on again, back in its place`() {
        assertNull(hot.dropStage(TreesStage.ID))
        assertTrue(hot.passes.none { it.id == TreesStage.ID })
        assertNull(hot.retuneStage(TreesStage.ID, mapOf("density" to "1.5")))
        assertEquals(TreesStage.ID, hot.passes.last().id, "trees go back after the land: ${hot.passes.map { it.id }}")
        assertNotNull(hot.dropStage(TerrainStage.ID), "the land cannot be switched off")
    }

    @Test
    fun `a tuned world restores tuned`() {
        val passes = MicrovoxelTerrainGenerator.withStage(hot.passes, StageSpec(TerrainStage.ID, mapOf("height" to "0.7")))
        assertNull(session.retuneTerrain(passes))
        val restored = WorldSession(content = content, config = session.config)
        assertEquals("0.7", restored.hotTerrain!!.passes.first { it.id == TerrainStage.ID }.options["height"])
    }
}
