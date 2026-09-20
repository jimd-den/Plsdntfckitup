package com.stratum.core.domain.ai

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.world.BlockMaterial
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenerateContentPackUseCaseTest {

    private class FakeModel(private val reply: Result<String>) : LanguageModelPort {
        var lastRequest: CompletionRequest? = null
        var stages = mutableListOf<GenerationStage>()
        override suspend fun complete(
            request: CompletionRequest,
            observer: GenerationObserver,
        ): Result<String> {
            lastRequest = request
            observer.onStage(GenerationStage.SENDING)
            return reply
        }
    }

    private fun useCase(reply: String) =
        GenerateContentPackUseCase(FakeModel(Result.success(reply))) to FakeModel(Result.success(reply))

    private val goodPack = """
        {
          "id": "ashfall",
          "name": "Ashfall",
          "description": "A burned world.",
          "palette": { "accent": "#FF8844" },
          "blocks": [
            { "id": "ashfall:ash", "name": "Ash", "material": "SOIL", "hardness": 0.4,
              "topColor": "#554b45", "sideColor": "#3a332f" },
            { "id": "ashfall:slag", "name": "Slag", "material": "STONE", "hardness": 2.5,
              "requiredTier": 1, "topColor": "#4a4a52", "sideColor": "#33333a" },
            { "id": "ashfall:emberore", "name": "Ember Ore", "material": "ORE", "hardness": 4,
              "requiredTier": 2, "light": 7, "drop": "ashfall:ember", "topColor": "#7a4a2a",
              "sideColor": "#553320" }
          ],
          "biomes": [
            { "id": "ashfall:plain", "name": "Cinder Plain", "surfaceBlock": "ashfall:ash",
              "subsurfaceBlock": "ashfall:ash", "fillerBlock": "ashfall:slag",
              "heightBias": 0, "roughness": 0.8,
              "deposits": [ { "block": "ashfall:emberore", "minZ": 3, "maxZ": 12, "chance": 0.1 } ] }
          ],
          "classes": [
            { "id": "ashfall:cinderwright", "name": "Cinderwright", "health": 240,
              "startingBlocks": ["ashfall:ash"] }
          ],
          "lore": [
            { "id": "ashfall:lore_fall", "title": "The Fall", "body": "It burned for a year.",
              "category": "HISTORY", "subject": "ashfall:ash" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a well formed reply becomes a playable pack`() = runTest {
        val model = FakeModel(Result.success(goodPack))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()

        assertEquals("ashfall", pack.id)
        assertEquals("Ashfall", pack.name)
        assertEquals(PackOrigin.AI_GENERATED, pack.origin)
        assertEquals(3, pack.blocks.size)
        assertTrue(pack.isPlayable)
        // It must survive the same assembler the game runs.
        ContentPackAssembler().assemble(listOf(pack))
    }

    @Test
    fun `the theme reaches the model`() = runTest {
        val model = FakeModel(Result.success(goodPack))
        GenerateContentPackUseCase(model)(
            PackGenerationRequest(theme = "sunken cathedral", blockCount = 9),
        ).getOrThrow()

        val prompt = model.lastRequest!!.userPrompt
        assertTrue(prompt.contains("sunken cathedral"))
        assertTrue(prompt.contains("9 blocks"))
    }

    @Test
    fun `json wrapped in markdown fences and commentary is still parsed`() = runTest {
        // Models do this constantly, however firmly the prompt forbids it.
        val messy = "Sure! Here is your pack:\n\n```json\n$goodPack\n```\n\nLet me know if you want changes."
        val model = FakeModel(Result.success(messy))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()
        assertEquals(3, pack.blocks.size)
    }

    @Test
    fun `unknown fields from a chattier model are ignored`() = runTest {
        val withExtras = goodPack.replace(
            """"name": "Ashfall",""",
            """"name": "Ashfall", "difficulty": "hard", "authorNotes": "enjoy",""",
        )
        val model = FakeModel(Result.success(withExtras))
        assertTrue(GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).isSuccess)
    }

    @Test
    fun `block ids missing their namespace are repaired rather than rejected`() = runTest {
        val unqualified = goodPack
            .replace("\"ashfall:ash\"", "\"ash\"")
            .replace("\"ashfall:slag\"", "\"slag\"")
        val model = FakeModel(Result.success(unqualified))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()

        assertTrue(pack.blocks.any { it.id == "ashfall:ash" }, "namespace was not added")
        assertEquals("ashfall:ash", pack.biomes.first().surfaceBlockId)
    }

    @Test
    fun `a biome naming a block that was never defined falls back instead of failing assembly`() = runTest {
        val dangling = goodPack.replace(
            """"surfaceBlock": "ashfall:ash",""",
            """"surfaceBlock": "ashfall:never_defined",""",
        )
        val model = FakeModel(Result.success(dangling))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()

        assertTrue(pack.blocks.any { it.id == pack.biomes.first().surfaceBlockId })
        ContentPackAssembler().assemble(listOf(pack))
    }

    @Test
    fun `scatter rules naming unknown blocks are dropped`() = runTest {
        val badScatter = goodPack.replace(
            """"deposits": [""",
            """"scatter": [ { "block": "ashfall:ghost_tree", "chance": 0.1, "height": 4 } ], "deposits": [""",
        )
        val model = FakeModel(Result.success(badScatter))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()
        assertTrue(pack.biomes.first().scatter.isEmpty(), "a rule for a nonexistent block survived")
    }

    @Test
    fun `out of range numbers are clamped into what the engine can run`() = runTest {
        val absurd = goodPack.replace(
            """"hardness": 2.5,""",
            """"hardness": 9999, "light": 400,""",
        )
        val model = FakeModel(Result.success(absurd))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()

        val slag = pack.blockOrNull("ashfall:slag")!!
        assertTrue(slag.hardness <= 20f, "hardness ${slag.hardness} was not clamped")
        assertTrue(slag.lightEmission <= 15, "light ${slag.lightEmission} was not clamped")
    }

    @Test
    fun `an unknown material falls back to stone rather than throwing`() = runTest {
        val model = FakeModel(Result.success(goodPack.replace("\"material\": \"SOIL\"", "\"material\": \"MAGIC\"")))
        val pack = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()
        assertEquals(BlockMaterial.STONE, pack.blockOrNull("ashfall:ash")!!.material)
    }

    @Test
    fun `a pack with no blocks is reported as unusable`() = runTest {
        val empty = """{ "id": "x", "name": "X", "blocks": [], "biomes": [] }"""
        val model = FakeModel(Result.success(empty))
        val result = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash"))

        assertTrue(result.isFailure)
        assertIs<GenerationException>(result.exceptionOrNull())
    }

    @Test
    fun `a reply with no json at all fails with a message a player can act on`() = runTest {
        val model = FakeModel(Result.success("I'm sorry, I can't help with that."))
        val result = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash"))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("usable content pack"), "unhelpful message: $message")
    }

    @Test
    fun `malformed json fails rather than producing a half built pack`() = runTest {
        val model = FakeModel(Result.success("""{ "id": "x", "blocks": [ { "id": """))
        assertTrue(GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).isFailure)
    }

    @Test
    fun `a transport failure is passed through untouched`() = runTest {
        val boom = java.io.IOException("no network")
        val model = FakeModel(Result.failure(boom))
        val result = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash"))

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `colours are accepted in every notation models use`() {
        assertEquals(0xFF112233L, "#112233".toArgb(0L))
        assertEquals(0xFF112233L, "112233".toArgb(0L))
        assertEquals(0xFF112233L, "0x112233".toArgb(0L))
        assertEquals(0x80112233L, "#80112233".toArgb(0L))
        assertEquals(0xFFAABBCCL, "#abc".toArgb(0L))
        assertEquals(0xFF000001L, "not a colour".toArgb(0xFF000001L))
        assertEquals(0xFF000001L, "".toArgb(0xFF000001L))
    }

    @Test
    fun `a generated pack can be layered on top of another`() = runTest {
        val model = FakeModel(Result.success(goodPack))
        val generated = GenerateContentPackUseCase(model)(PackGenerationRequest(theme = "ash")).getOrThrow()

        val base = com.stratum.core.domain.content.ContentPack(
            id = "base",
            name = "Base",
            author = "test",
            blocks = listOf(
                com.stratum.core.domain.world.BlockType(id = "base:rock", displayName = "Rock"),
            ),
            biomes = listOf(
                com.stratum.core.domain.content.BiomeDefinition(
                    id = "base:plain",
                    name = "Plain",
                    surfaceBlockId = "base:rock",
                    subsurfaceBlockId = "base:rock",
                    bedrockFillerBlockId = "base:rock",
                ),
            ),
        )

        val content = ContentPackAssembler().assemble(listOf(base, generated))
        assertTrue(content.registry.contains("base:rock"))
        assertTrue(content.registry.contains("ashfall:ash"))
        // The later pack supplies the palette, so a generated pack reskins the UI.
        assertEquals(generated.palette, content.palette)
        assertFalse(content.biomes.isEmpty())
    }
}
