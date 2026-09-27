package com.stratum.core.domain.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CachingImageModelTest {

    private class CountingModel(var fail: Boolean = false) : ImageModelPort {
        var calls = 0
        override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> {
            calls++
            if (fail) return Result.failure(GenerationException("no"))
            return Result.success(GeneratedImage("image $calls".toByteArray(), "image/png", 4, 4))
        }
    }

    private val request = ImageRequest(
        prompt = "walk, frame 2",
        references = listOf(ImageReference("reference".toByteArray())),
    )

    @Test
    fun `an identical request is answered from what was already paid for`() = runTest {
        val model = CountingModel()
        val cached = CachingImageModel(model, InMemoryImageCache())
        val first = cached.generateImage(request).getOrThrow()
        val second = cached.generateImage(request.copy()).getOrThrow()
        assertEquals(1, model.calls)
        assertEquals(first, second)
    }

    @Test
    fun `a new take, a new reference or a new model is a new request`() = runTest {
        val model = CountingModel()
        var configured = "a"
        val cached = CachingImageModel(model, InMemoryImageCache()) { configured }
        cached.generateImage(request)
        cached.generateImage(request.copy(take = 1))
        cached.generateImage(request.copy(references = listOf(ImageReference("other".toByteArray()))))
        configured = "b"
        cached.generateImage(request)
        assertEquals(4, model.calls)
    }

    @Test
    fun `failures are not cached`() = runTest {
        val model = CountingModel(fail = true)
        val cache = InMemoryImageCache()
        val cached = CachingImageModel(model, cache)
        cached.generateImage(request)
        model.fail = false
        cached.generateImage(request)
        assertEquals(2, model.calls)
        assertEquals(1, cache.size)
    }

    @Test
    fun `a broken cache costs a saving, never a generation`() = runTest {
        val broken = object : ImageCache {
            override fun get(key: String): GeneratedImage? = error("read failed")
            override fun put(key: String, image: GeneratedImage) = error("write failed")
        }
        val result = CachingImageModel(CountingModel(), broken).generateImage(request)
        assertEquals(true, result.isSuccess)
    }

    @Test
    fun `the fingerprint is stable and sensitive to the prompt`() {
        assertEquals(ImageRequestFingerprint.of(request, "m"), ImageRequestFingerprint.of(request.copy(), "m"))
        assertNotEquals(ImageRequestFingerprint.of(request, "m"), ImageRequestFingerprint.of(request.copy(prompt = "x"), "m"))
    }
}
