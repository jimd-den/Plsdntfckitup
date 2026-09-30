package com.stratum.core.domain.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldgenDataTest {

    @Test
    fun `a structure is a dungeon or a set of pieces, never both or neither`() {
        val dungeon = DungeonLayout(floorBlockId = "t:floor", wallBlockId = "t:wall")
        val piece = StructurePiece("t:p", mapOf('#' to "t:wall"), listOf(listOf("#")))
        assertFailsWith<IllegalArgumentException> { StructureTemplate("t:s", "S") }
        assertFailsWith<IllegalArgumentException> { StructureTemplate("t:s", "S", dungeon = dungeon, pieces = listOf(piece)) }
        assertEquals(setOf("t:floor", "t:wall"), StructureTemplate("t:s", "S", dungeon = dungeon).referencedBlockIds())
    }

    @Test
    fun `a piece reports ragged rows, missing palette entries and connectors off its edge`() {
        val piece = StructurePiece(
            "t:p", mapOf('#' to "t:wall", 'E' to "@enemy"),
            layers = listOf(listOf("##", "#X"), listOf("#", "E ")),
            connectors = listOf(PieceConnector(PieceSide.NORTH, 5)),
        )
        val problems = piece.problems("t:s").joinToString()
        assertTrue("rows of different widths" in problems, problems)
        assertTrue("uses 'X'" in problems, problems)
        assertTrue("connector off its north edge" in problems, problems)
        assertEquals("@enemy", piece.entryAt(0, 1, 1))
        assertEquals(StructurePiece.AIR, StructurePiece("t:q", emptyMap(), listOf(listOf("."))).entryAt(0, 0, 0))
    }

    @Test
    fun `markers near a column come from every chunk that could hold one`() {
        val world = object : MarkedWorld {
            override fun markersIn(pos: ChunkPos): List<WorldMarker> =
                listOf(WorldMarker(WorldMarkerKind.LOOT, pos.originX, pos.originY, 5, "t:s"))
        }
        val near = world.markersNear(0, 0, 16)
        assertEquals(setOf(0 to 0, -16 to 0, 0 to -16, 16 to 0, 0 to 16), near.map { it.x to it.y }.toSet())
    }

    @Test
    fun `climate points and rules refuse nonsense`() {
        assertFailsWith<IllegalArgumentException> { ClimatePoint("t:b", 1.5f, 0.5f) }
        assertFailsWith<IllegalArgumentException> { ClimatePoint("t:b", 0.5f, 0.5f, minDepth = 4) }
        assertFailsWith<IllegalArgumentException> { OreRule("t:o", 2, 8, 1f, veinSize = 40) }
        assertFailsWith<IllegalArgumentException> { StructurePlacement(spacing = 8) }
        assertTrue(ClimatePoint("t:b", 0.5f, 0.5f, minDepth = 4, maxDepth = 9).isUnderground)
    }
}
