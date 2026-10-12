package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every new world is generated in microvoxels; a world made before stays as it was made. */
class MicrovoxelDefaultTest {

    private val igbo = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))

    @Test
    fun `a new world on the built-in pack alone is a microvoxel world, and stays one`() {
        assertTrue(igbo.terrain.generatorId != TerrainRecipe.MICROVOXEL, "the built-in pack names a block generator")
        val passes = MicrovoxelTerrainGenerator.newWorldPasses(igbo.terrain).map { PassSpec(it.id, it.options) }
        assertTrue(passes.isNotEmpty())
        val session = WorldSession(content = igbo, config = WorldConfig(seed = 3L, simulationRadius = 1, terrainPasses = passes))
        val hot = assertNotNull(session.hotTerrain, "a new world is not generated in microvoxels")
        assertEquals(passes.map { it.id }, hot.passes.map { it.id }.filter { id -> passes.any { it.id == id } })
        // Saved and loaded with its stages, it is the same kind of world.
        val again = WorldSession(content = igbo, config = session.config)
        assertNotNull(again.hotTerrain)
    }

    @Test
    fun `a world made before, with no stages kept, is generated as its packs say`() {
        val old = WorldSession(content = igbo, config = WorldConfig(seed = 3L, simulationRadius = 1))
        assertNull(old.hotTerrain, "an old block world was regenerated in microvoxels")
    }

    @Test
    fun `a hand-made map stays a map`() {
        assertTrue(MicrovoxelTerrainGenerator.newWorldPasses(TerrainRecipe.tileMap("t:map")).isEmpty())
    }
}
