package com.stratum.core.data.ai

import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Asks an image endpoint to draw something, or to redraw something it is given.
 *
 * Both go to /images/generations. Editing once had to be posted as a chat turn
 * with the picture inside it, which was the only shape providers served it in;
 * the image API now takes reference images directly, and the chat route is not
 * merely redundant but broken -- a model whose only output is an image has no
 * chat endpoint to answer on, and answers a chat request with a 404.
 *
 * Providers still disagree about how the image comes back, so both shapes that
 * occur are tried: an inline base64 payload, or a URL to fetch.
 */
class OpenRouterImageModel(
    private val configProvider: () -> ProviderConfig,
    private val client: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) : ImageModelPort {

    override suspend fun generateImage(
        request: ImageRequest,
        observer: GenerationObserver,
    ): Result<GeneratedImage> =
        withContext(Dispatchers.IO) {
            observer.onStage(GenerationStage.PREPARING)
            val config = configProvider()
            if (config.apiKey.isBlank()) {
                observer.onStage(GenerationStage.FAILED)
                return@withContext Result.failure(
                    GenerationException("No API key is configured for ${config.displayName}"),
                )
            }

            val model = request.modelId ?: config.imageModel
            // One endpoint for both operations. Drawing from nothing and
            // redrawing something that exists are the same request to the
            // image API; they differ only by whether references came with it.
            val editing = request.references.isNotEmpty()
            val endpoint = "${config.baseUrl.trimEnd('/')}/images/generations"
            val payload = moshi.adapter(ImageRequestDto::class.java).toJson(
                ImageRequestDto(
                    model = model,
                    prompt = request.prompt,
                    n = 1,
                    response_format = "b64_json",
                    input_references = request.references
                        .map { reference ->
                            val encoded = Base64.encodeToString(reference.bytes, Base64.NO_WRAP)
                            ImageReferenceDto(
                                image_url = ImageUrlDto(
                                    "data:${reference.mimeType};base64,$encoded",
                                ),
                            )
                        }
                        // Absent rather than empty: a model that does not edit
                        // refuses the field outright, and a plain generation
                        // has no business carrying it.
                        .ifEmpty { null },
                ),
            )
            // The key lives in a header and is never recorded. Everything else
            // is, because a rejection is only explicable if you can see exactly
            // what was asked for.
            val headers = mapOf(
                "Authorization" to "Bearer ****",
                "Content-Type" to "application/json",
                "HTTP-Referer" to config.refererUrl,
                "X-Title" to config.appTitle,
            )
            var status: Int? = null
            var responseBody: String? = null
            val startedAt = System.currentTimeMillis()

            val result = runCatching {
                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", config.refererUrl)
                    .addHeader("X-Title", config.appTitle)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                observer.onStage(GenerationStage.SENDING)
                client.newCall(httpRequest).execute().use { response ->
                    observer.onStage(GenerationStage.READING)
                    status = response.code
                    val body = response.body?.string().orEmpty()
                    // Truncated: a successful reply is a megabyte of base64 and
                    // nobody needs to read that, but a failure is short.
                    responseBody = body.take(MAX_RECORDED_BODY)
                    if (!response.isSuccessful) {
                        throw failureFor(response.code, body, model)
                    }
                    observer.onStage(GenerationStage.DECODING)
                    val bytes = drawnImage(body, model)
                    observer.onStage(GenerationStage.MEASURING)
                    toGeneratedImage(bytes, request)
                }
            }

            observer.onAttempt(
                GenerationAttempt(
                    id = "img_$startedAt",
                    label = if (editing) {
                        "Pose edit · ${request.width}×${request.height}"
                    } else {
                        "Image · ${request.width}×${request.height}"
                    },
                    endpoint = endpoint,
                    model = model,
                    // The reference image is a megabyte of base64 and the
                    // panel that shows this is for reading. The prompt is the
                    // part anyone debugging a bad pose needs to see.
                    requestBody = redactImageData(payload),
                    redactedHeaders = headers,
                    status = status,
                    responseBody = responseBody,
                    failure = result.exceptionOrNull()?.message,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            observer.onStage(if (result.isSuccess) GenerationStage.DONE else GenerationStage.FAILED)
            // A socket that died halfway through is worth another go for the
            // same reason a 429 is: the request was fine, the moment was not.
            result.recoverCatching { cause ->
                throw if (cause is java.io.IOException) {
                    GenerationException(
                        cause.message ?: "The connection dropped.",
                        cause = cause,
                        retryable = true,
                    )
                } else {
                    cause
                }
            }
        }

    /** The image from an /images/generations reply. */
    private fun drawnImage(body: String, model: String): ByteArray {
        val parsed = moshi.adapter(ImageResponseDto::class.java).fromJson(body)
        val first = parsed?.data?.firstOrNull()
            ?: throw GenerationException(
                "'$model' replied with no image. It is probably a text model.",
            )
        return when {
            !first.b64_json.isNullOrBlank() -> decodeBase64(first.b64_json)
            !first.url.isNullOrBlank() -> download(first.url)
            else -> throw GenerationException("The model's reply contained no image data")
        }
    }

    /**
     * Replaces embedded image payloads with their size.
     *
     * A recorded request is for a person to read. One that is 99% base64 is not
     * readable, and storing several of them per character is a real amount of
     * memory held for no purpose.
     */
    private fun redactImageData(payload: String): String =
        DATA_URI.replace(payload) { match ->
            val prefix = match.value.substringBefore("base64,") + "base64,"
            "$prefix<${match.value.length / 1024}KB of image omitted>"
        }

    private fun decodeBase64(encoded: String): ByteArray {
        // Providers variously send a bare payload or a full data URI.
        val payload = encoded.substringAfterLast("base64,", encoded)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }
            .getOrElse { throw GenerationException("The model's image could not be decoded", it) }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GenerationException("The image link the model returned could not be fetched")
            }
            return response.body?.bytes()
                ?: throw GenerationException("The image link returned nothing")
        }
    }

    /**
     * Measures the image rather than trusting the request: providers round to
     * their own supported sizes, and a sheet cut on the requested dimensions
     * would shear every frame.
     */
    private fun toGeneratedImage(bytes: ByteArray, request: ImageRequest): GeneratedImage {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth.takeIf { it > 0 } ?: request.width
        val height = options.outHeight.takeIf { it > 0 } ?: request.height
        return GeneratedImage(
            bytes = bytes,
            mimeType = options.outMimeType ?: "image/png",
            width = width,
            height = height,
        )
    }

    /**
     * The status code turned into something a run can act on.
     *
     * Three outcomes, not one. A rate limit or a provider wobble is worth
     * waiting out, because the next attempt usually works. A rejected key or a
     * model that does not exist will do exactly the same thing to every
     * remaining frame, so a long run should stop rather than spend four minutes
     * proving it. Everything else is this frame's problem alone: skip it, keep
     * going, report it at the end.
     */
    private fun failureFor(code: Int, body: String, model: String): GenerationException = when (code) {
        400 -> GenerationException("'$model' rejected the request. It may not be an image model.")
        401, 403 -> GenerationException(
            "The API key was rejected. Check it in settings.",
            fatal = true,
        )
        402 -> GenerationException(
            "The provider reports no remaining credit for this key.",
            fatal = true,
        )
        404 -> GenerationException("'$model' is not available on this provider.", fatal = true)
        408, 429 -> GenerationException(
            "The provider is rate limiting. Waiting before the next attempt.",
            retryable = true,
        )
        in 500..599 -> GenerationException(
            "The provider is having trouble (HTTP $code).",
            retryable = true,
        )
        else -> GenerationException(
            "The provider refused the request (HTTP $code): ${body.take(200)}",
        )
    }

    private companion object {
        /** Enough to read an error; far less than a base64 image. */
        const val MAX_RECORDED_BODY = 4000

        /** A data URI wherever it turns up: in a reply, or in our own request. */
        val DATA_URI = Regex("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+")
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Image generation is slow even when it works.
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * No `size`.
 *
 * It looks like the obvious field to set and it is a trap. Providers round the
 * requested size to whatever they actually produce, so it was never load
 * bearing -- the returned image is measured rather than trusted, precisely
 * because of that. But a size a provider will not accept is not rounded, it is
 * refused: 512x512 comes back as a flat HTTP 400 from Google, which turns "an
 * image of a slightly different size" into "no image at all". Asking for
 * nothing and measuring what arrives cannot fail that way.
 */
@JsonClass(generateAdapter = true)
internal data class ImageRequestDto(
    val model: String,
    val prompt: String,
    val n: Int,
    val response_format: String,
    /** The pictures to work from. Null, not empty, when drawing from nothing. */
    val input_references: List<ImageReferenceDto>? = null,
)

/**
 * A picture handed to the model to work from.
 *
 * The `type` discriminator is required and has exactly one accepted value, so
 * it is not a parameter -- a caller cannot get it right by choosing, only
 * wrong by choosing.
 */
@JsonClass(generateAdapter = true)
internal data class ImageReferenceDto(
    val image_url: ImageUrlDto,
    val type: String = "image_url",
)

@JsonClass(generateAdapter = true)
internal data class ImageResponseDto(val data: List<ImageDatumDto>?)

@JsonClass(generateAdapter = true)
internal data class ImageDatumDto(val b64_json: String?, val url: String?)

@JsonClass(generateAdapter = true)
internal data class ImageUrlDto(val url: String)
