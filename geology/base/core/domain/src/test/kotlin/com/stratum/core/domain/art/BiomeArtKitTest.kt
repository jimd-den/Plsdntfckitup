package com.stratum.core.domain.art

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.DepositRule
import com.stratum.core.domain.content.ScatterRule
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BiomeArtKitTest {

    private val turf = BlockType("t:turf", "Turf", BlockMaterial.SOIL, topColor = 0xFF4E8B45, sideColor = 0xFF37612F)
    private val earth = BlockType("t:earth", "Earth", BlockMaterial.SOIL, topColor = 0xFF8A5A3B, sideColor = 0xFF5F3E29)
    private val stone = BlockType("t:stone", "Stone", BlockMaterial.STONE, topColor = 0xFF6E6E78, sideColor = 0xFF4A4A52)
    private val tree = BlockType("t:tree", "Tree", BlockMaterial.FOLIAGE, glyph = "T", topColor = 0xFF2F6B37, sideColor = 0xFF23502A)
    private val brazier = BlockType(
        "t:brazier", "Brazier", BlockMaterial.RITUAL,
        lightEmission = 9, glyph = "B", topColor = 0xFFCD7F32, sideColor = 0xFF8A4F1E, accentColor = 0xFFF0C862,
    )
    private val idol = BlockType(
        "t:idol", "Idol", BlockMaterial.RITUAL,
        glyph = "I", topColor = 0xFF9A8C6E, sideColor = 0xFF6E6352,
    )
    private val ore = BlockType("t:ore", "Ore", BlockMaterial.ORE, requiredTier = 2, topColor = 0xFFB87333, sideColor = 0xFF6E6E78)

    private val grove = BiomeDefinition(
        id = "t:grove",
        name = "Grove",
        description = "Mossy flagstones under old trees.",
        surfaceBlockId = turf.id,
        subsurfaceBlockId = earth.id,
        bedrockFillerBlockId = stone.id,
        scatter = listOf(ScatterRule(tree.id, chance = 0.05f), ScatterRule(brazier.id, chance = 0.01f), ScatterRule(idol.id, chance = 0.01f)),
        deposits = listOf(DepositRule(ore.id, minZ = 2, maxZ = 12, chance = 0.1f)),
        ambientLight = 11,
    )

    private val blocks = listOf(turf, earth, stone, tree, brazier, idol, ore).associateBy { it.id }

    @Test
    fun `a kit falls out of rules a pack already had`() {
        // The whole promptable half depends on this: a pack a model invented
        // ninety seconds ago has to be art directed without anyone writing a
        // kit for it by hand.
        val kit = BiomeArtKit.derive(grove, blocks)

        assertEquals("t:grove", kit.biomeId)
        assertTrue(kit.groundRamp.isNotEmpty())
        assertEquals(PropSilhouette.CANOPY, kit.propFamilies[tree.id])
        // Light wins over material: a lit ritual object is a flame, an unlit
        // one is a built thing.
        assertEquals(PropSilhouette.BRAZIER, kit.propFamilies[brazier.id])
        assertEquals(PropSilhouette.SHRINE, kit.propFamilies[idol.id])
        assertEquals(grove.description, kit.subject)
    }

    @Test
    fun `the landmark is the thing the region is about`() {
        val kit = BiomeArtKit.derive(grove, blocks)
        assertEquals(brazier.id, kit.landmarkBlockId, "a region's landmark should be what glows, not what is common")
    }

    @Test
    fun `a leafy lit region gets weather without being told`() {
        val kit = BiomeArtKit.derive(grove, blocks)
        assertNotNull(kit.atmosphere)
        assertEquals(MoteKind.FIREFLIES, kit.atmosphere?.moteKind)
    }

    @Test
    fun `a region made of nothing in particular gets no weather rather than wrong weather`() {
        val bare = grove.copy(id = "t:bare", scatter = emptyList(), ambientLight = 12, heightBias = 0)
        assertEquals(null, BiomeArtKit.derive(bare, blocks).atmosphere)
    }

    @Test
    fun `a region naming blocks that do not exist still produces a kit`() {
        // Generated packs get this wrong, and a missing block should cost the
        // region its ramp entry, not the player their world.
        val broken = grove.copy(surfaceBlockId = "t:missing", scatter = listOf(ScatterRule("t:also_missing", 0.1f)))
        val kit = BiomeArtKit.derive(broken, blocks)
        assertTrue(kit.propFamilies.isEmpty())
        assertTrue(kit.groundRamp.isNotEmpty(), "the two blocks that do exist are still a ramp")
    }

    @Test
    fun `a whole pack is derived in one call`() {
        val pack = ContentPack(
            id = "t",
            name = "Test",
            author = "test",
            blocks = blocks.values.toList(),
            biomes = listOf(grove, grove.copy(id = "t:second")),
        )
        assertEquals(setOf("t:grove", "t:second"), BiomeArtKit.deriveAll(pack).keys)
    }

    @Test
    fun `a kit writes the prompts for its own region`() {
        val orders = ArtBible.kitPrompts(ArtDirection.HOUSE, BiomeArtKit.derive(grove, blocks))
        assertTrue(orders.any { it.id.endsWith("/landmark") })
        assertTrue(orders.all { it.prompt.contains("isometric") })
        assertTrue(orders.all { it.prompt.contains("no text") }, "prohibitions do more work than any positive phrase")
    }

    @Test
    fun `every asset prompt states the same camera and the same palette`() {
        // One noun changes and nothing else does. That is the only reason a set
        // of separately generated props looks like one world.
        val direction = StyleLexicon.interpret("dark woodblock").direction
        val tree = ArtBible.promptFor(direction, "IROKO TREE")
        val rock = ArtBible.promptFor(direction, "GRANITE BOULDER")
        val shared = tree.commonPrefixWith(rock)
        assertTrue(shared.length > MIN_SHARED_PROMPT, "prompts only shared ${shared.length} characters")
        assertTrue(tree.endsWith("Subject: IROKO TREE."))
    }

    @Test
    fun `hero assets ask for more than props do`() {
        val direction = ArtDirection.HOUSE
        assertTrue(ArtBible.promptFor(direction, "X", AssetTier.HERO).contains("character-grade"))
        assertTrue(ArtBible.promptFor(direction, "X", AssetTier.SYSTEMIC).contains("thumbnail"))
    }

    private companion object {
        const val MIN_SHARED_PROMPT = 200
    }
}
