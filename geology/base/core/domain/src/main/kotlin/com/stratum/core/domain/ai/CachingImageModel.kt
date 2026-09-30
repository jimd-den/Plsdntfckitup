package com.stratum.core.domain.ai

import java.security.MessageDigest

/**
 * Where generated images are kept against the request that produced them.
 *
 * A port because keeping bytes is storage and the domain only decides what the
 * key is and when it may be used.
 */
interface ImageCache {
    fun get(key: String): GeneratedImage?

    fun put(key: String, image: GeneratedImage)
}

/**
 * A stable name for everything about a request that shapes the answer.
 *
 * Hashed rather than concatenated because a request carries one or two
 * megabytes of reference image, and the key is a file name.
 */
object ImageRequestFingerprint {

    /**
     * @param resolvedModel the model that will actually answer. A request
     *   leaves the model null to mean "whichever is configured", and two
     *   identical requests sent either side of changing that setting are not
     *   the same request.
     */
    fun of(request: ImageRequest, resolvedModel: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(bytes)
        }
        field(VERSION)
        field(request.prompt)
        field(request.modelId ?: resolvedModel.orEmpty())
        field("${request.width}x${request.height}")
        field(request.requireTransparency.toString())
        field(request.take.toString())
        request.references.forEach { reference ->
            field(reference.mimeType)
            digest.update(reference.bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(reference.bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bumped if what goes into the key changes, so old entries are never misread. */
    private const val VERSION = "1"
    private val SEPARATOR = byteArrayOf(0)
}

/**
 * An image model that never charges twice for the same request.
 *
 * A pose run is forty requests, each a real cost, and each one's answer is
 * written to disk a moment after it arrives. That moment is the gap this
 * closes: a process killed between the reply and the write, a save that failed
 * on a full disk, a frame whose post-processing threw — every one of them used
 * to mean asking, and paying, again for a picture that had already been drawn.
 * With the answer kept against the request, the retry is free.
 *
 * Only identical requests are served from the cache, [ImageRequest.take]
 * included. Redrawing a frame on purpose asks with a new take, so a bad frame
 * is never handed back to the person who just rejected it.
 *
 * A cache that fails is a cache that misses: every error from the store is
 * swallowed, because losing a saving is never a reason to lose a generation.
 */
class CachingImageModel(
    private val delegate: ImageModelPort,
    private val cache: ImageCache,
    /** The model the provider will use when a request names none. */
    private val resolvedModel: () -> String? = { null },
) : ImageModelPort {

    override suspend fun generateImage(
        request: ImageRequest,
        observer: GenerationObserver,
    ): Result<GeneratedImage> {
        val key = ImageRequestFingerprint.of(request, resolvedModel())
        runCatching { cache.get(key) }.getOrNull()?.let { cached ->
            if (cached.bytes.isNotEmpty()) {
                observer.onStage(GenerationStage.DONE)
                return Result.success(cached)
            }
        }
        val result = delegate.generateImage(request, observer)
        result.getOrNull()?.takeIf { it.bytes.isNotEmpty() }?.let { image ->
            runCatching { cache.put(key, image) }
        }
        return result
    }
}

/** A cache held in memory, for tests and for a device with nowhere to write. */
class InMemoryImageCache : ImageCache {
    private val entries = LinkedHashMap<String, GeneratedImage>()

    val size: Int get() = entries.size

    override fun get(key: String): GeneratedImage? = entries[key]

    override fun put(key: String, image: GeneratedImage) {
        entries[key] = image
    }
}
