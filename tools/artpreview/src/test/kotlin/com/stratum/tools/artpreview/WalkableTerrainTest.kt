package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainContext
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.world.PlayerMotion
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipped world has to be walkable, not just generatable.
 *
 * Checked against the real pack and the real generator over a large area, on
 * several seeds, because the failure this guards against — cliffs a player
 * cannot climb back up — only shows up in the terrain an actual pack makes.
 */
class WalkableTerrainTest {

    @Test
    fun `the igbo world has no cliff a player cannot climb`() {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        listOf(1L, 20260922L, 777L).forEach { seed ->
            val config = WorldConfig(seed = seed, simulationRadius = 4)
            val world = StreamingWorld(
                content.registry,
                StratumTerrain.create(TerrainContext(config, content.biomes, content.terrain)),
                config,
            )
            world.focusOn(ChunkPos(0, 0))
            fun ground(x: Int, y: Int): Int {
                var z = world.surfaceAt(x, y)
                while (z > 0 && world.blockAt(BlockPos(x, y, z)).glyph != null) z--
                return z
            }
            // Chunks -4..4 are loaded: blocks -64..79. Stay inside them.
            val min = -4 * 16
            val max = 4 * 16 + 14
            var pairs = 0
            var steps = 0
            var worst = 0
            var worstAt = ""
            for (y in min until max) for (x in min until max) {
                for ((nx, ny) in listOf(x + 1 to y, x to y + 1)) {
                    val d = abs(ground(x, y) - ground(nx, ny))
                    pairs++
                    if (d > PlayerMotion.STEP_UP) steps++
                    if (d > worst) { worst = d; worstAt = "($x,$y)=${ground(x, y)} vs ($nx,$ny)=${ground(nx, ny)}" }
                }
            }
            assertTrue(worst <= PlayerMotion.CLIMB_UP, "seed $seed has a $worst-block cliff at $worstAt")
            assertTrue(steps < pairs / 100, "seed $seed: $steps of $pairs borders need a climb")
        }
    }
}
