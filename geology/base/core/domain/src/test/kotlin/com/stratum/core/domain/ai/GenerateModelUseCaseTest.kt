package com.stratum.core.domain.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateModelUseCaseTest {

    private val glb = byteArrayOf('g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte(), 2, 0, 0, 0, 12, 0, 0, 0)

    private class FakeModels(
        override val capabilities: ModelCapabilities = ModelCapabilities(),
        private val answer: ByteArray,
    ) : ModelGenerationPort {
        val requests = ArrayList<ModelRequest>()
        override suspend fun generateModel(request: ModelRequest, observer: ModelGenerationObserver): Result<GeneratedModel> {
            requests += request
            observer.onProgress(ModelJobProgress(ModelJobPhase.RUNNING, 0.5f))
            return Result.success(GeneratedModel(answer, ModelFormat.GLB, "job-1"))
        }
    }

    private class FakeImages : ImageModelPort {
        val prompts = ArrayList<String>()
        override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> {
            prompts += request.prompt
            return Result.success(GeneratedImage(byteArrayOf(1, 2, 3), "image/png", 8, 8))
        }
    }

    @Test
    fun `a text request carries the game's contract and returns the model`() = runTest {
        val models = FakeModels(answer = glb)
        val progress = ArrayList<ModelJobProgress>()
        val result = GenerateModelUseCase(models)(
            ModelBrief("a mossy stone shrine", ModelSubjectKind.PROP),
            ModelGenerationObserver.of(GenerationObserver.None) { progress += it },
        )

        assertEquals(ModelFormat.GLB, result.getOrThrow().format)
        val prompt = models.requests.single().prompt
        assertTrue("a mossy stone shrine" in prompt && "One object alone" in prompt && "standing upright" in prompt)
        assertNull(models.requests.single().reference)
        assertEquals(0.5f, progress.single().fraction)
    }

    @Test
    fun `an image-only provider gets a reference drawn first`() = runTest {
        val models = FakeModels(ModelCapabilities(textToModel = false), glb)
        val images = FakeImages()
        GenerateModelUseCase(models, images)(ModelBrief("a bronze idol", ModelSubjectKind.STATUE)).getOrThrow()

        assertTrue("a bronze idol" in images.prompts.single())
        assertNotNull(models.requests.single().reference)
    }

    @Test
    fun `an image-only provider with no picture and no painter fails fatally`() = runTest {
        val failure = GenerateModelUseCase(FakeModels(ModelCapabilities(textToModel = false), glb))(ModelBrief("idol"))
            .exceptionOrNull() as GenerationException
        assertTrue(failure.fatal)
    }

    @Test
    fun `an answer that is not a model is refused`() = runTest {
        val html = "<html>error</html>".toByteArray()
        val result = GenerateModelUseCase(FakeModels(answer = html))(ModelBrief("barrel"))
        assertTrue(result.isFailure)
        assertTrue("not a GLB" in result.exceptionOrNull()!!.message.orEmpty())
    }

    @Test
    fun `formats are sniffed from bytes`() {
        assertEquals(ModelFormat.GLB, ModelFormat.sniff(glb))
        assertEquals(ModelFormat.OBJ, ModelFormat.sniff("# obj\nv 0 0 0\nf 1 1 1".toByteArray()))
        assertNull(ModelFormat.sniff(ByteArray(3)))
        assertEquals("prop_old_well", ModelBrief("Old well!").slug())
    }
}
