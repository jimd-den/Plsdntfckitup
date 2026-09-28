package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The world has no edge: chunks are functions of the seed and their position,
 * so land a million blocks out is made the same way as land at the door.
 */
class InfiniteTerrainTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
    private val config = WorldConfig(seed = 20260928L, simulationRadius = 1)
    private val hot = WorldSession(content, config).hotTerrain!!

    private fun surfaces(x0: Int, y0: Int, span: Int): IntArray {
        val world = StreamingWorld(content.registry, hot, config)
        world.focusOn(BlockPos(x0 + span / 2, y0 + span / 2, 0), urgentRadius = config.simulationRadius)
        return IntArray(span * span) { i -> world.surfaceAt(x0 + i % span, y0 + i / span) }
    }

    @Test
    fun `far lands are as varied and as seamless as the land at home`() {
        for ((x, y) in listOf(0 to 0, 100_000 to -70_000, 1_000_000 to 1_000_000, -2_000_000 to 3_000_000)) {
            val span = Chunk.SIZE * 2
            val s = surfaces(x - Chunk.SIZE, y - Chunk.SIZE, span)
            assertTrue(s.all { it in 1 until Chunk.HEIGHT }, "a hole in the world near $x,$y")
            // Neighbouring columns differ by a step or two, chunk borders included: no seams, no noise breaking down.
            var worst = 0
            for (j in 0 until span) for (i in 1 until span) worst = maxOf(worst, kotlin.math.abs(s[j * span + i] - s[j * span + i - 1]))
            assertTrue(worst <= 8, "a cliff of $worst between neighbouring columns near $x,$y")
        }
    }

    @Test
    fun `a walk far from home streams in land the whole way`() {
        val world = StreamingWorld(content.registry, hot, config)
        var x = 0
        while (x <= 4_000) {
            world.focusOn(BlockPos(x, x / 3, 0))
            assertTrue(world.surfaceAt(x, x / 3) in 1 until Chunk.HEIGHT, "no ground at $x")
            x += 97
        }
        assertTrue(world.loadedChunks.size <= config.loadedChunkCount, "chunks behind the walker were not released")
    }
}
