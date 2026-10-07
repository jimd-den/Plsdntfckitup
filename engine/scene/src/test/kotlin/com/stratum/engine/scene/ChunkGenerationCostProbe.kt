package com.stratum.engine.scene

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.world.StratumTerrain
import kotlin.test.Test

/** What one chunk of the shipped world costs to generate, on whatever thread asks. */
class ChunkGenerationCostProbe {
    @Test
    fun `report generation cost per chunk`() {
        for (recipe in listOf(TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL), TerrainRecipe())) {
            val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = recipe)
            val config = WorldConfig(seed = 20260928L, simulationRadius = 2)
            val generator = StratumTerrain.create(content.terrainContext(config))
            var i = 0
            fun next() = generator.generate(ChunkPos(i % 40, i++ / 40 + 3), content.registry)
            repeat(30) { next() }
            val n = 60
            val start = System.nanoTime()
            repeat(n) { next() }
            val ms = (System.nanoTime() - start) / 1e6 / n
            println("chunk generation [${recipe.generatorId}]: ${"%.2f".format(ms)} ms per chunk")
        }
    }
}
