package com.stratum.core.domain.content

import com.stratum.core.domain.world.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentPackAssemblerTest {

    private val assembler = ContentPackAssembler()

    private fun pack(
        id: String,
        blocks: List<BlockType> = listOf(stone),
        biomes: List<BiomeDefinition> = listOf(plains),
        origin: PackOrigin = PackOrigin.BUILT_IN,
        palette: PackPalette = PackPalette(),
    ) = ContentPack(
        id = id,
        name = id,
        author = "test",
        origin = origin,
        palette = palette,
        blocks = blocks,
        biomes = biomes,
    )

    private val stone = BlockType(id = "base:stone", displayName = "Stone")
    private val soil = BlockType(id = "base:soil", displayName = "Soil")
    private val plains = BiomeDefinition(
        id = "base:plains",
        name = "Plains",
        surfaceBlockId = "base:stone",
        subsurfaceBlockId = "base:stone",
        bedrockFillerBlockId = "base:stone",
    )

    @Test
    fun `a single pack assembles into a usable registry`() {
        val content = assembler.assemble(listOf(pack("base")))
        assertTrue(content.registry.contains("base:stone"))
        assertEquals(1, content.biomes.size)
    }

    @Test
    fun `a later pack overrides an earlier block of the same id and the override is reported`() {
        val restyled = stone.copy(displayName = "Bronze-veined Stone", topColor = 0xFFCD7F32)
        val content = assembler.assemble(
            listOf(
                pack("base"),
                pack("addon", blocks = listOf(restyled), biomes = emptyList(), origin = PackOrigin.AI_GENERATED),
            ),
        )

        assertEquals("Bronze-veined Stone", content.registry.typeOf("base:stone").displayName)
        val override = content.overrides.single()
        assertEquals("addon", override.packId)
        assertEquals("base:stone", override.targetId)
        assertEquals(OverrideKind.BLOCK, override.kind)
    }

    @Test
    fun `packs that add new blocks are merged rather than replacing each other`() {
        val content = assembler.assemble(
            listOf(pack("base"), pack("addon", blocks = listOf(soil), biomes = emptyList())),
        )
        assertTrue(content.registry.contains("base:stone"))
        assertTrue(content.registry.contains("base:soil"))
        assertTrue(content.overrides.isEmpty())
    }

    @Test
    fun `the last pack supplies the palette so an imported pack can reskin the interface`() {
        val loud = PackPalette(accent = 0xFF00FFAA)
        val content = assembler.assemble(
            listOf(pack("base"), pack("addon", biomes = emptyList(), palette = loud)),
        )
        assertEquals(loud, content.palette)
    }

    @Test
    fun `a biome naming an undefined block fails at load time not mid generation`() {
        val broken = plains.copy(id = "base:broken", surfaceBlockId = "base:missing")
        val failure = assertFailsWith<ContentPackException> {
            assembler.assemble(listOf(pack("base", biomes = listOf(broken))))
        }
        assertTrue(failure.message!!.contains("base:missing"))
    }

    @Test
    fun `scatter and deposit rules are validated too`() {
        val withScatter = plains.copy(scatter = listOf(ScatterRule("base:ghost_tree", 0.1f)))
        assertFailsWith<ContentPackException> {
            assembler.assemble(listOf(pack("base", biomes = listOf(withScatter))))
        }

        val withDeposit = plains.copy(deposits = listOf(DepositRule("base:ghost_ore", 1, 8, 0.1f)))
        assertFailsWith<ContentPackException> {
            assembler.assemble(listOf(pack("base", biomes = listOf(withDeposit))))
        }
    }

    @Test
    fun `assembling nothing is rejected`() {
        assertFailsWith<IllegalArgumentException> { assembler.assemble(emptyList()) }
    }

    @Test
    fun `lore can be looked up by the subject it describes`() {
        val lore = LoreEntry("l1", "Of Stone", "It endures.", LoreCategory.HISTORY, subjectId = "base:stone")
        val content = assembler.assemble(
            listOf(pack("base").copy(loreEntries = listOf(lore))),
        )
        assertEquals(listOf(lore), content.loreFor("base:stone"))
        assertTrue(content.loreFor("base:nothing").isEmpty())
    }

    @Test
    fun `a pack without blocks or biomes is not playable`() {
        assertTrue(pack("base").isPlayable)
        assertTrue(!pack("empty", blocks = emptyList()).isPlayable)
        assertTrue(!pack("empty", biomes = emptyList()).isPlayable)
    }
}
