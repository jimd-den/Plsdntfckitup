package com.stratum.tools.artpreview

import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.forge.ImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * OpenRouter's image endpoint, for the JVM.
 *
 * The app has its own adapter on OkHttp; this one exists so the forge can run
 * on a build machine with nothing but a JDK. Same endpoint, same request shape,
 * same port — which is the point of having a port.
 *
 * The key is taken from the caller and sent in a header. It is never logged,
 * never written to disk and never included in an error message.
 */
class OpenRouterImages(
    private val apiKey: String,
    private val defaultModel: String = "meta/muse-image",
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) : ImageModelPort {

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(GenerationException("No OpenRouter key", fatal = true))
            // PNG, not the default WebP. The JVM has no WebP decoder of its own,
            // and the plugin that adds one decoded some of Muse's WebPs as a
            // flat green channel — which looked exactly like a bad generation
            // and was blamed on the model until the raw files were compared.
            val body = """{"model":${json(request.modelId ?: defaultModel)},"prompt":${json(request.prompt)},""" +
                """"n":1,"response_format":"b64_json","output_format":"png",""" +
                """"size":"${request.width}x${request.height}"}"""
            var attempt = 0
            while (true) {
                attempt++
                val response = runCatching {
                    client.send(
                        HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/images/generations"))
                            .timeout(Duration.ofMinutes(4))
                            .header("Authorization", "Bearer $apiKey")
                            .header("Content-Type", "application/json")
                            .header("X-Title", "Stratum asset forge")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }.getOrElse { error ->
                    if (attempt < MAX_ATTEMPTS) { delay(BACKOFF_MS * attempt); continue }
                    return@withContext Result.failure(GenerationException("Network error: ${error.message}", error, retryable = true))
                }
                val status = response.statusCode()
                if (status == 429 || status >= 500) {
                    if (attempt < MAX_ATTEMPTS) { delay(BACKOFF_MS * attempt); continue }
                    return@withContext Result.failure(GenerationException("OpenRouter answered $status", retryable = true))
                }
                if (status !in 200..299) {
                    return@withContext Result.failure(
                        GenerationException("OpenRouter answered $status: ${response.body().take(300)}", fatal = status == 401 || status == 402),
                    )
                }
                val b64 = extract(response.body(), "b64_json")
                    ?: return@withContext Result.failure(GenerationException("No image in the reply: ${response.body().take(200)}"))
                val mime = extract(response.body(), "media_type") ?: "image/png"
                val bytes = Base64.getDecoder().decode(b64)
                return@withContext Result.success(GeneratedImage(bytes, mime, 0, 0))
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun extract(body: String, field: String): String? {
        val marker = "\"$field\":\""
        val start = body.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val end = body.indexOf('"', start).takeIf { it > start } ?: return null
        return body.substring(start, end)
    }

    private fun json(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val BACKOFF_MS = 4000L
    }
}

/** ImageIO as an [ImageCodec]. Answers null for anything it cannot read, WebP included. */
object AwtCodec : ImageCodec {
    override fun decode(bytes: ByteArray): Texture? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return Texture(image.width, image.height, pixels)
    }

    override fun encodePng(texture: Texture): ByteArray {
        val image = BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, texture.width, texture.height, texture.argb, 0, texture.width)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
