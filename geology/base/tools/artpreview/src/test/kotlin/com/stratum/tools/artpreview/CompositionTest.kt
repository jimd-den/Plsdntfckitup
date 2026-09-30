package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped world is composed, not only scattered: paths wind through it,
 * clear of scenery, and landmarks stand on level pads in the open.
 */
class CompositionTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))

    /** A world loaded around the middle of a large stretch of grove, and that middle. */
    private fun world(seed: Long): Triple<StreamingWorld, BiomeSource, Pair<Int, Int>> {
        val config = WorldConfig(seed = seed, simulationRadius = 6)
        val generator = StratumTerrain.create(content.terrainContext(config))
        val biomes = generator as BiomeSource
        fun groveShare(cx: Int, cy: Int) = (-4..4).sumOf { j -> (-4..4).count { i -> biomes.biomeAt(cx + i * 20, cy + j * 20).id == GROVE } }
        val centre = (-40..40).flatMap { j -> (-40..40).map { i -> i * 50 to j * 50 } }.maxBy { (x, y) -> groveShare(x, y) }
        val world = StreamingWorld(content.registry, generator, config)
        world.focusOn(ChunkPos(Math.floorDiv(centre.first, 16), Math.floorDiv(centre.second, 16)))
        return Triple(world, biomes, centre)
    }

    @Test
    fun `landmarks stand on level pads with their ring around them`() {
        var found = 0
        listOf(1L, 20260922L, 777L).forEach { seed ->
            val (world, _, c) = world(seed)
            for (y in c.second - 90..c.second + 90) for (x in c.first - 90..c.first + 90) {
                val z = world.surfaceAt(x, y)
                if (world.blockAt(BlockPos(x, y, z)).id != "igbo:ofo_shrine") continue
                found++
                // Level: every column of the pad has its ground at the same height.
                val ground = z - 1
                for (dy in -3..3) for (dx in -3..3) {
                    if (dx * dx + dy * dy > 9) continue
                    // The ground itself: solid, and not the shrine or a brazier standing on it.
                    val column = (ground + 1 downTo 0).first {
                        val block = world.blockAt(BlockPos(x + dx, y + dy, it))
                        block.isSolid && block.glyph == null && block.id != "igbo:ofo_shrine"
                    }
                    assertEquals(ground, column, "the pad around ($x,$y) is not level at ($dx,$dy)")
                }
                val braziers = (-3..3).sumOf { dy -> (-3..3).count { dx -> world.blockAt(BlockPos(x + dx, y + dy, z)).id == "igbo:bronze_brazier" } }
                assertEquals(4, braziers, "the shrine at ($x,$y) has its four braziers")
            }
        }
        assertTrue(found >= 2, "expected shrines across three seeds of grove, found $found")
    }

    @Test
    fun `paths are a small share of the ground, and nothing grows on them`() {
        val (world, biomes, c) = world(20260922L)
        var grove = 0
        var path = 0
        for (y in c.second - 90..c.second + 90) for (x in c.first - 90..c.first + 90) {
            if (biomes.biomeAt(x, y).id != GROVE) continue
            grove++
            val top = world.blockAt(BlockPos(x, y, world.surfaceAt(x, y)))
            if (top.id == "igbo:red_earth") {
                path++
                assertTrue(top.glyph == null, "no prop stands on the path at ($x,$y)")
            }
        }
        assertTrue(grove > 2000, "the sample should hold some grove, held $grove columns")
        val share = path.toFloat() / grove
        assertTrue(share in 0.02f..0.2f, "paths should be a thread through the grove, not all of it or none: $share")
    }

    private companion object {
        const val GROVE = "igbo:sacred_grove"
    }
}
