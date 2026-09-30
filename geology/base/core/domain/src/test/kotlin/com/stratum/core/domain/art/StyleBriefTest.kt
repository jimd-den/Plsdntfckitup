package com.stratum.core.domain.art

import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.LanguageModelPort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleBriefTest {

    private class FakeModel(private val reply: Result<String>) : LanguageModelPort {
        var lastRequest: CompletionRequest? = null
        override suspend fun complete(
            request: CompletionRequest,
            observer: GenerationObserver,
        ): Result<String> {
            lastRequest = request
            return reply
        }
    }

    @Test
    fun `a brief edits the style the player is already in`() = runTest {
        val model = FakeModel(
            Result.success(
                """{ "name": "Salt Flats", "sun": "#FFE9C0", "hazeStrength": 0.5, "moteKind": "DUST" }""",
            ),
        )
        val base = StyleLexicon.interpret("dark").direction
        val result = StyleBriefUseCase(model)("bleached salt flats", base).getOrThrow()

        assertEquals("Salt Flats", result.name)
        assertEquals(0.5f, result.atmosphere.hazeStrength)
        assertEquals(MoteKind.DUST, result.atmosphere.moteKind)
        // Everything the model did not mention is the player's world, unchanged.
        assertEquals(base.contrast.terrainSaturation, result.contrast.terrainSaturation)
        assertEquals(base.light.depthFalloff, result.light.depthFalloff)
    }

    @Test
    fun `what the lexicon could not read is what the model is asked about`() = runTest {
        val model = FakeModel(Result.success("{}"))
        StyleBriefUseCase(model)("dark, in the manner of an obscure lithographer")
        val prompt = model.lastRequest?.userPrompt.orEmpty()
        assertTrue("lithographer" in prompt, "the model was not told which part of the request needed it")
        assertTrue("dark" in prompt)
    }

    @Test
    fun `a model that answers out of bounds does not get to hide the monsters`() = runTest {
        val model = FakeModel(
            Result.success(
                """{ "terrainSaturation": 4.0, "terrainValueCeiling": 1.0,
                     "outlineWidth": 0.0, "ambientStrength": 0.0, "moteDensity": 90000 }""",
            ),
        )
        val result = StyleBriefUseCase(model)("blinding", ArtDirection.HOUSE).getOrThrow()

        assertTrue(result.contrast.terrainSaturation <= ArtDirection.MAX_TERRAIN_SATURATION)
        assertTrue(result.contrast.terrainValueCeiling <= ArtDirection.MAX_TERRAIN_CEILING)
        assertTrue(result.contrast.outlineWidth >= ArtDirection.MIN_OUTLINE)
        assertTrue(result.light.ambientStrength >= ArtDirection.MIN_AMBIENT)
        assertTrue(result.atmosphere.moteDensity <= ArtDirection.MAX_MOTES)
    }

    @Test
    fun `a reply that is not a style still leaves the player with a world`() = runTest {
        val model = FakeModel(Result.success("I'd be glad to help! What kind of world did you have in mind?"))
        val result = StyleBriefUseCase(model)("dark grimdark").getOrThrow()

        // The lexicon's reading of the same words, which is always playable.
        assertEquals(StyleLexicon.interpret("dark grimdark").direction.palette, result.palette)
    }

    @Test
    fun `fenced json is still json`() = runTest {
        val model = FakeModel(
            Result.success("Here you go:\n```json\n{ \"name\": \"Ember\" }\n```\nHope that helps."),
        )
        assertEquals("Ember", StyleBriefUseCase(model)("ember").getOrThrow().name)
    }

    @Test
    fun `a failed call is a failed call`() = runTest {
        val model = FakeModel(Result.failure(IllegalStateException("no key")))
        assertTrue(StyleBriefUseCase(model)("anything").isFailure)
    }

    @Test
    fun `the same request twice is the same world`() = runTest {
        val reply = Result.success("""{ "sun": "#FFEECC" }""")
        val first = StyleBriefUseCase(FakeModel(reply))("dusk", seed = 5).getOrThrow()
        val second = StyleBriefUseCase(FakeModel(reply))("dusk", seed = 5).getOrThrow()
        assertEquals(first, second)
    }
}
