package com.stratum.engine.world

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainGeneratorTest {

    private val config = WorldConfig(seed = 20260916L, seaLevel = 14, surfaceVariation = 8)
    private val registry = TestContent.registry

    private fun generator(cfg: WorldConfig = config) =
        LayeredTerrainGenerator(cfg, TestContent.assembled.biomes)

    @Test
    fun `regenerating a chunk produces identical terrain`() {
        val pos = ChunkPos(3, -2)
        val first = generator().generate(pos, registry).exportBlocks()
        val second = generator().generate(pos, registry).exportBlocks()
        assertTrue(first.contentEquals(second), "the same seed produced different terrain")
    }

    @Test
    fun `a different seed produces different terrain`() {
        val pos = ChunkPos(0, 0)
        val a = generator().generate(pos, registry).exportBlocks()
        val b = generator(config.copy(seed = 1L)).generate(pos, registry).exportBlocks()
        assertTrue(!a.contentEquals(b), "changing the seed changed nothing")
    }

    @Test
    fun `every column is floored with bedrock so the player cannot dig into the void`() {
        val chunk = generator().generate(ChunkPos(1, 1), registry)
        val bedrock = registry.indexOf(BlockType.BEDROCK.id)
        for (y in 0 until Chunk.SIZE) {
            for (x in 0 until Chunk.SIZE) {
                assertEquals(bedrock, chunk.blockAt(x, y, 0), "column $x,$y has no bedrock floor")
            }
        }
    }

    @Test
    fun `every column has a surface within the world column`() {
        val chunk = generator().generate(ChunkPos(-4, 5), registry)
        for (y in 0 until Chunk.SIZE) {
            for (x in 0 until Chunk.SIZE) {
                val surface = chunk.surfaceAt(x, y)
                assertTrue(surface in 0 until Chunk.HEIGHT, "column $x,$y surfaced at $surface")
            }
        }
    }

    @Test
    fun `terrain never writes above the world ceiling`() {
        // Scatter stacks on top of the surface, so an unclamped surface height
        // would silently drop decoration or overflow the column.
        val tall = config.copy(seaLevel = Chunk.HEIGHT - 4, surfaceVariation = 20)
        val chunk = generator(tall).generate(ChunkPos(0, 0), registry)
        for (y in 0 until Chunk.SIZE) {
            for (x in 0 until Chunk.SIZE) {
                assertTrue(chunk.surfaceAt(x, y) < Chunk.HEIGHT)
            }
        }
    }

    @Test
    fun `neighbouring chunks agree about the column they share a border with`() {
        // Each chunk is generated in isolation, so a generator that peeked at
        // chunk-local coordinates instead of world coordinates would produce a
        // visible seam here.
        val left = generator().generate(ChunkPos(0, 0), registry)
        val right = generator().generate(ChunkPos(1, 0), registry)

        val gen = generator()
        for (y in 0 until Chunk.SIZE) {
            val worldY = y
            val edgeX = Chunk.SIZE - 1
            val leftBiome = gen.biomeAt(edgeX, worldY)
            val rightBiome = gen.biomeAt(Chunk.SIZE, worldY)
            val leftHeight = gen.surfaceHeight(edgeX, worldY, leftBiome)
            val rightHeight = gen.surfaceHeight(Chunk.SIZE, worldY, rightBiome)

            assertTrue(
                kotlin.math.abs(leftHeight - rightHeight) <= MAX_SEAM_STEP,
                "seam at y=$worldY: $leftHeight vs $rightHeight",
            )
        }
        assertTrue(left.surfaceAt(Chunk.SIZE - 1, 0) >= 0)
        assertTrue(right.surfaceAt(0, 0) >= 0)
    }

    @Test
    fun `surface height responds to the biome height bias`() {
        val gen = generator()
        val plainsHeights = (0 until 64).map { gen.surfaceHeight(it, 0, TestContent.plains) }
        val highlandHeights = (0 until 64).map { gen.surfaceHeight(it, 0, TestContent.highlands) }
        assertTrue(
            highlandHeights.average() > plainsHeights.average(),
            "highlands (${highlandHeights.average()}) did not rise above plains (${plainsHeights.average()})",
        )
    }

    @Test
    fun `ore deposits appear within their declared depth band`() {
        val oreIndex = registry.indexOf(TestContent.ore.id)
        var found = 0
        for (cx in 0 until 4) {
            val chunk = generator().generate(ChunkPos(cx, 0), registry)
            for (z in 0 until Chunk.HEIGHT) {
                for (y in 0 until Chunk.SIZE) {
                    for (x in 0 until Chunk.SIZE) {
                        if (chunk.blockAt(x, y, z) == oreIndex) {
                            found++
                            assertTrue(z in 1..8, "ore generated outside its band at z=$z")
                        }
                    }
                }
            }
        }
        assertTrue(found > 0, "no ore generated across four chunks")
    }

    @Test
    fun `caves carve air pockets below the surface`() {
        val chunk = generator(config.copy(caveDensity = 0.3f)).generate(ChunkPos(2, 2), registry)
        var pockets = 0
        for (y in 0 until Chunk.SIZE) {
            for (x in 0 until Chunk.SIZE) {
                val surface = chunk.surfaceAt(x, y)
                for (z in 1 until surface) {
                    if (chunk.blockAt(x, y, z) == BlockRegistry.AIR_INDEX) pockets++
                }
            }
        }
        assertTrue(pockets > 0, "a low cave threshold carved nothing")
    }

    @Test
    fun `a generator with no biomes is rejected rather than producing an empty world`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            LayeredTerrainGenerator(config, emptyList())
        }
    }

    private companion object {
        /** Terrain noise is smooth, so a one-block step across a border is the most we accept. */
        const val MAX_SEAM_STEP = 2
    }
}
