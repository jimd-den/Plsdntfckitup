package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenerateSpriteSheetUseCaseTest {

    private class FakeImageModel(
        private val reply: Result<GeneratedImage>,
    ) : ImageModelPort {
        var lastRequest: ImageRequest? = null
        override suspend fun generateImage(
            request: ImageRequest,
            observer: GenerationObserver,
        ): Result<GeneratedImage> {
            lastRequest = request
            observer.onStage(GenerationStage.SENDING)
            return reply
        }
    }

    private fun image(width: Int, height: Int, bytes: ByteArray = ByteArray(16) { 1 }) =
        GeneratedImage(bytes = bytes, mimeType = "image/png", width = width, height = height)

    @Test
    fun `a returned image becomes a cuttable sheet`() = runTest {
        val model = FakeImageModel(Result.success(image(256, 256)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a bronze-masked warrior", namespace = "igbo"),
        ).getOrThrow()

        assertEquals("igbo:a_bronze_masked_warrior", result.sheet.id)
        assertEquals(4, result.sheet.columns)
        assertEquals(4, result.sheet.rows)
        assertEquals(64, result.sheet.frameWidth)
        assertEquals(64, result.sheet.frameHeight)
        assertEquals(SpriteOrigin.AI_GENERATED, result.sheet.origin)
        assertTrue(result.sheet.isCoherent)
    }

    @Test
    fun `a single action block is one contiguous clip across the whole grid`() = runTest {
        val model = FakeImageModel(Result.success(image(1024, 1024)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(
                subject = "a bronze-masked warrior",
                namespace = "action",
                layout = SheetLayout.action(AnimationState.WALK),
                variant = "WALK",
            ),
        ).getOrThrow()

        assertEquals(3, result.sheet.columns)
        assertEquals(2, result.sheet.rows)
        val walk = result.sheet.clip(AnimationState.WALK)
        assertEquals(6, walk?.frameCount)
        assertEquals(0, walk?.firstFrame)
        // Reading across the wrap is what makes a two-row block one animation.
        assertEquals(1, walk?.frameAt(walk.frameDurationMs.toLong()))
        assertEquals(3, walk?.frameAt(3L * walk.frameDurationMs))
        assertTrue(result.sheet.isCoherent)
    }

    @Test
    fun `two actions of the same subject do not overwrite each other`() = runTest {
        val model = FakeImageModel(Result.success(image(1024, 1024)))
        val use = GenerateSpriteSheetUseCase(model)

        val walk = use(
            SpriteSheetRequest(
                subject = "a bronze-masked warrior",
                namespace = "action",
                layout = SheetLayout.action(AnimationState.WALK),
                variant = "WALK",
            ),
        ).getOrThrow()
        val attack = use(
            SpriteSheetRequest(
                subject = "a bronze-masked warrior",
                namespace = "action",
                layout = SheetLayout.action(AnimationState.ATTACK),
                variant = "ATTACK",
            ),
        ).getOrThrow()

        assertTrue(walk.sheet.id != attack.sheet.id, "the second generation replaced the first")
    }

    @Test
    fun `an action block is asked for as one action, not as rows`() = runTest {
        val model = FakeImageModel(Result.success(image(1024, 1024)))
        GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(
                subject = "a bronze-masked warrior",
                layout = SheetLayout.action(AnimationState.WALK),
            ),
        ).getOrThrow()

        val prompt = model.lastRequest?.prompt.orEmpty()
        assertTrue("All 6 cells show one action" in prompt, prompt)
        // Saying "row one" would tell the model the other row is something else.
        assertTrue("Row 1:" !in prompt, prompt)
    }

    @Test
    fun `a single pose is not asked for as a grid`() = runTest {
        val model = FakeImageModel(Result.success(image(512, 512)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a bronze mask", layout = SheetLayout.pose()),
        ).getOrThrow()

        assertEquals(1, result.sheet.columns)
        assertEquals(1, result.sheet.rows)
        assertEquals(512, result.sheet.frameWidth)

        val prompt = model.lastRequest?.prompt.orEmpty()
        // A model told about cells draws cell borders; one told "one small
        // picture" draws a small picture in the corner of an empty canvas.
        assertTrue("arranged in a grid" !in prompt, prompt)
        assertTrue("cell" !in prompt.lowercase(), prompt)
        assertTrue("filling most of the canvas" in prompt, prompt)
        // Both failures are still named outright rather than implied, which is
        // what the sheet prompt learned the hard way about the checkerboard.
        assertTrue("No grid" in prompt, prompt)
        assertTrue("checkerboard" in prompt, prompt)
    }

    @Test
    fun `frame size comes from the image that arrived, not the one requested`() = runTest {
        // Models round to their own supported sizes. Cutting on the requested
        // dimensions would shear every frame.
        val model = FakeImageModel(Result.success(image(512, 512)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a leopard"),
        ).getOrThrow()

        assertEquals(128, result.sheet.frameWidth)
        assertEquals(128, result.sheet.frameHeight)
    }

    @Test
    fun `an absurdly small image still yields a sheet rather than dividing to zero`() = runTest {
        val model = FakeImageModel(Result.success(image(2, 2)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a speck"),
        ).getOrThrow()

        assertTrue(result.sheet.frameWidth >= 1)
        assertTrue(result.sheet.frameHeight >= 1)
    }

    @Test
    fun `the sheet carries the clips the layout promised`() = runTest {
        val model = FakeImageModel(Result.success(image(256, 256)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a warrior"),
        ).getOrThrow()

        val states = result.sheet.clips.map { it.state }.toSet()
        assertEquals(
            setOf(AnimationState.IDLE, AnimationState.WALK, AnimationState.ATTACK, AnimationState.HURT),
            states,
        )
        assertEquals(false, result.sheet.clip(AnimationState.ATTACK)!!.loops)
    }

    @Test
    fun `every layout asks for a square canvas a provider will actually accept`() = runTest {
        // Asking for 384x448 because that is what a 6x7 grid of 64px frames
        // measures gets rejected by some providers and silently rounded by
        // others, and a sheet cut on a size that was quietly changed shears
        // every frame. The grid belongs in the prompt, not in the canvas.
        listOf(SheetLayout.detailed(), SheetLayout.standard(), SheetLayout.simple()).forEach { layout ->
            val model = FakeImageModel(Result.success(image(layout.canvas, layout.canvas)))
            GenerateSpriteSheetUseCase(model)(
                SpriteSheetRequest(subject = "a rat", layout = layout),
            ).getOrThrow()

            val request = model.lastRequest!!
            assertEquals(request.width, request.height, "the canvas was not square")
            assertTrue(
                request.width in listOf(SheetLayout.SMALL_CANVAS, SheetLayout.DEFAULT_CANVAS),
                "asked for ${request.width}px, which is not a size providers support",
            )
        }
    }

    @Test
    fun `the prompt forbids the one big character that makes a sprite roll`() = runTest {
        // The failure behind a sprite that pans instead of animating: the model
        // draws one large figure, the sheet is cut on a grid that was never
        // drawn, and every frame is a crop of the same picture.
        val model = FakeImageModel(Result.success(image(1024, 1024)))
        GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a warrior", layout = SheetLayout.detailed()),
        ).getOrThrow()

        val prompt = model.lastRequest!!.prompt
        assertTrue(prompt.contains("42 cells"), "the prompt stopped counting the cells")
        assertTrue(prompt.contains("1024 by 1024 pixels"))
        assertTrue(
            prompt.contains("Do NOT draw one large character"),
            "the prompt stopped naming the rolling-sprite mistake",
        )
    }

    @Test
    fun `the prompt dictates the grid and demands transparency`() = runTest {
        val model = FakeImageModel(Result.success(image(256, 256)))
        GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a storm wisp", styleDirection = "chunky pixel art"),
        ).getOrThrow()

        val prompt = model.lastRequest!!.prompt
        assertTrue(prompt.contains("a storm wisp"))
        assertTrue(prompt.contains("chunky pixel art"))
        assertTrue(prompt.contains("4 columns by 4 rows"))
        // The single most common failure is an opaque background, and the
        // specific way models get it wrong is drawing the checkerboard that
        // represents transparency. Naming the mistake is the part that works,
        // so the prompt has to keep doing it.
        assertTrue(prompt.contains("transparen", ignoreCase = true))
        assertTrue(
            prompt.contains("checkerboard", ignoreCase = true),
            "the prompt stopped naming the checkerboard mistake",
        )
        assertTrue(model.lastRequest!!.requireTransparency)
    }

    @Test
    fun `the prompt describes each row's animation`() = runTest {
        val model = FakeImageModel(Result.success(image(256, 256)))
        GenerateSpriteSheetUseCase(model)(SpriteSheetRequest(subject = "a warrior")).getOrThrow()

        val prompt = model.lastRequest!!.prompt
        assertTrue(prompt.contains("Row 1"))
        assertTrue(prompt.contains("walk cycle"))
        assertTrue(prompt.contains("basic attack"))
    }

    @Test
    fun `a detailed sheet asks for the special to look unlike the basic attack`() = runTest {
        // The whole point of a separate special row: a power that reads the same
        // as an ordinary swing means the resource it cost bought nothing you can
        // see. So the prompt has to distinguish the two rows, not just list them.
        val model = FakeImageModel(Result.success(image(384, 448)))
        val result = GenerateSpriteSheetUseCase(model)(
            SpriteSheetRequest(subject = "a warrior", layout = SheetLayout.detailed()),
        ).getOrThrow()

        val prompt = model.lastRequest!!.prompt
        assertTrue(prompt.contains("6 columns by 7 rows"))
        assertTrue(prompt.contains("basic attack"))
        assertTrue(
            prompt.contains("signature power"),
            "the special row stopped being described apart from the basic attack",
        )
        assertTrue(result.sheet.clips.any { it.state == AnimationState.SPECIAL })
    }

    @Test
    fun `an empty image is a failure, not a sheet with no pixels`() = runTest {
        val model = FakeImageModel(Result.success(image(256, 256, bytes = ByteArray(0))))
        val result = GenerateSpriteSheetUseCase(model)(SpriteSheetRequest(subject = "nothing"))

        assertTrue(result.isFailure)
        assertIs<GenerationException>(result.exceptionOrNull())
    }

    @Test
    fun `a transport failure passes through untouched`() = runTest {
        val boom = java.io.IOException("no network")
        val model = FakeImageModel(Result.failure(boom))
        val result = GenerateSpriteSheetUseCase(model)(SpriteSheetRequest(subject = "a warrior"))

        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `subjects become safe ids`() {
        assertEquals("sprite", SpriteSheetRequest(subject = "!!!").slug())
        assertEquals("a_thing", SpriteSheetRequest(subject = "  A Thing!  ").slug())
        assertTrue(SpriteSheetRequest(subject = "x".repeat(200)).slug().length <= 24)
    }
}
