package com.stratum.core.domain.session

import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldSaveTest {

    private val stone = BlockType("t:stone", "Stone")
    private val glass = BlockType("t:glass", "Glass", isOpaque = false)

    private fun save(rules: WorldRules = WorldRules()) = WorldSave(
        identity = WorldIdentity("w1", "Riverlands", createdAt = 10L, presetName = "Adventure", heroName = "Ada"),
        lastPlayedAt = 99L,
        playSeconds = 3600L,
        config = WorldConfig(seed = 42L, rules = rules),
        hero = HeroSave("h", "t:digger", level = 7),
        player = WorldPlayer(WorldPoint(1f, 2f, 3f), health = 50, resource = 10),
    )

    @Test
    fun `the summary carries what a menu shows and nothing it has to read chunks for`() {
        val summary = save().summary()
        assertEquals(
            WorldSummary("w1", "Riverlands", "Ada", "t:digger", 7, "Adventure", 42L, 3600L, 99L, 10L),
            summary,
        )
    }

    @Test
    fun `a sandbox world says so from its rules`() {
        assertTrue(save(WorldRules(sandbox = true)).sandbox)
    }

    @Test
    fun `saved chunks compare by their cells`() {
        val cells = ShortArray(Chunk.VOLUME).also { it[5] = 2 }
        assertEquals(SavedChunk(1, 2, cells), SavedChunk(1, 2, cells.copyOf()))
        assertNotEquals(SavedChunk(1, 2, cells), SavedChunk(1, 2, ShortArray(Chunk.VOLUME)))
        assertFailsWith<IllegalArgumentException> { SavedChunk(0, 0, ShortArray(3)) }
    }

    @Test
    fun `saved cells are remapped by id into whatever registry is loaded now`() {
        val then = BlockRegistry.build(listOf(stone, glass))
        val chunk = Chunk(ChunkPos(3, -1)).apply {
            setBlock(0, 0, 0, then.indexOf(stone.id))
            setBlock(1, 0, 0, then.indexOf(glass.id))
        }
        val saved = SavedChunk.of(chunk)
        val ids = then.all.map { it.id }

        // A later load has the blocks in another order, and has lost the glass pack.
        val now = BlockRegistry.build(listOf(BlockType("t:dirt", "Dirt"), stone))
        val restored = saved.toChunk(SavedChunk.remapTable(ids, now))

        assertEquals(ChunkPos(3, -1), restored.pos)
        assertEquals(now.indexOf(stone.id), restored.blockAt(0, 0, 0))
        assertEquals(BlockRegistry.AIR_INDEX, restored.blockAt(1, 0, 0))
    }

    @Test
    fun `a saved chunk is a copy the world can keep digging past`() {
        val chunk = Chunk(ChunkPos(0, 0)).apply { setBlock(0, 0, 0, 2) }
        val saved = SavedChunk.of(chunk)
        chunk.setBlock(0, 0, 0, 0)
        assertEquals(2, saved.blocks[Chunk.indexOf(0, 0, 0)].toInt())
    }

    @Test
    fun `the world's player goes back where they stood, keeping the hero they carry`() {
        val carried = PlayerState("t:digger", WorldPoint(0f, 0f, 0f), level = 9, hotbar = listOf("a"))
        val placed = WorldPlayer(
            WorldPoint(4f, 5f, 6f), Direction.EAST, health = 12, resource = 3, needs = mapOf("hunger" to 40f),
            inventory = mapOf("t:stone" to 5), hotbar = listOf("t:stone"), selectedSlot = 4, toolTier = 2,
        ).applyTo(carried)

        assertEquals(WorldPoint(4f, 5f, 6f), placed.position)
        assertEquals(Direction.EAST, placed.facing)
        assertEquals(9, placed.level)
        assertEquals(12, placed.health)
        assertEquals(mapOf("hunger" to 40f), placed.needs)
        assertEquals(0, placed.selectedSlot, "a slot past the hotbar is clamped")
    }
}
