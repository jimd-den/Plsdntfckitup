package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.importer.common.DirectoryImportSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A world configured as data -- what a player installs, or the studio's AI writes -- builds a microvoxel world. */
class MicrovoxelPluginTest {

    @Test
    fun `the microvoxel example switches the built-in world to microvoxels`() {
        val result = PluginImporter().import(DirectoryImportSource(File("../examples/plugins/microvoxel-realms")))
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack, result.pack))

        assertEquals(TerrainRecipe.MICROVOXEL, content.terrain.generatorId)
        assertEquals(
            listOf("micro:terrain", "micro:city_plan", "micro:roads", "micro:buildings", "micro:groundcover", "micro:trees"),
            content.terrain.passes.map { it.id },
        )
        assertEquals("2", content.terrain.passes.single { it.id == "micro:city_plan" }.options["maxFloors"])

        val generator = MicrovoxelTerrainGenerator(content.terrainContext(WorldConfig(seed = 7L)))
        val chunk = generator.generate(ChunkPos(0, 0), content.registry)
        assertTrue(chunk.surfaceAt(8, 8) in 1 until 47, "no ground at the origin")
    }
}
