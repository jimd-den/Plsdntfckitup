package com.stratum.engine.worldgen

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.engine.worldgen.Fixtures.count
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chunks come out the same whatever order they are made in, and whether or
 * not their neighbours were made first.
 *
 * Checked on a world full of things that cross borders -- tunnels, ore veins,
 * canopies, dungeons, jigsaw ruins -- because a pass that quietly depended on
 * generation order would show up as a seam exactly there.
 */
class SeamTest {

    private val registry = Fixtures.registry
    private val area = Fixtures.grid(-4, 3)

    private fun blocks(generator: PipelineTerrainGenerator, order: List<ChunkPos>): Map<ChunkPos, ShortArray> =
        order.associateWith { generator.generate(it, registry).exportBlocks() }

    @Test
    fun `chunks are identical in any order, alone or after their neighbours`() {
        val forward = blocks(Fixtures.generator(), area)
        val backward = blocks(Fixtures.generator(), area.reversed())
        val shuffled = blocks(Fixtures.generator(), area.shuffled(kotlin.random.Random(7)))
        area.forEach { pos ->
            val alone = Fixtures.generator().generate(pos, registry).exportBlocks()
            assertTrue(forward.getValue(pos).contentEquals(backward.getValue(pos)), "$pos differs between forward and backward order")
            assertTrue(forward.getValue(pos).contentEquals(shuffled.getValue(pos)), "$pos differs in shuffled order")
            assertTrue(forward.getValue(pos).contentEquals(alone), "$pos differs when generated alone")
        }
    }

    @Test
    fun `the features the seam test relies on actually cross borders`() {
        val generator = Fixtures.generator()
        val chunks = area.associateWith { generator.generate(it, registry) }
        val leaves = registry.indexOf(Fixtures.leaves.id)
        val tile = registry.indexOf(Fixtures.tile.id)
        assertTrue(chunks.values.sumOf { it.count(leaves) } > 0, "no trees grew in the sample")
        // A dungeon floor on both sides of a chunk border.
        val crossing = chunks.any { (pos, chunk) ->
            val east = chunks[ChunkPos(pos.x + 1, pos.y)] ?: return@any false
            (0 until Chunk.SIZE).any { y ->
                (1 until Chunk.HEIGHT).any { z -> chunk.blockAt(Chunk.SIZE - 1, y, z) == tile && east.blockAt(0, y, z) == tile }
            }
        }
        assertTrue(crossing, "no structure floor crossed a chunk border in the sample")
    }

    @Test
    fun `markers are the same whether asked before, during or after generation`() {
        val made = Fixtures.generator()
        val during = area.associateWith { made.generate(it, registry); made.markersIn(it) }
        val asked = Fixtures.generator()
        asked.generate(ChunkPos(100, 100), registry)
        area.reversed().forEach { pos -> assertEquals(during.getValue(pos), asked.markersIn(pos), "markers of $pos changed") }
        val kinds = during.values.flatten().map { it.kind }.toSet()
        assertTrue(WorldMarkerKind.BOSS in kinds, "the sample has a dungeon with a boss room: $kinds")
        assertTrue(WorldMarkerKind.LOOT in kinds && WorldMarkerKind.ENEMY_SPAWN in kinds && WorldMarkerKind.ENTRANCE in kinds)
    }

    @Test
    fun `a boss stands in air on the dungeon floor`() {
        val generator = Fixtures.generator()
        val boss = area.flatMap { generator.markersIn(it.also { p -> generator.generate(p, registry) }) }
            .first { it.kind == WorldMarkerKind.BOSS }
        val chunk = generator.generate(ChunkPos.containing(boss.x, boss.y), registry)
        val lx = Math.floorMod(boss.x, Chunk.SIZE)
        val ly = Math.floorMod(boss.y, Chunk.SIZE)
        assertEquals(BlockRegistry.AIR_INDEX, chunk.blockAt(lx, ly, boss.z))
        assertEquals(BlockRegistry.AIR_INDEX, chunk.blockAt(lx, ly, boss.z + 1))
        assertEquals(registry.indexOf(Fixtures.tile.id), chunk.blockAt(lx, ly, boss.z - 1))
        assertEquals("t:lich", boss.refId)
    }
}
