package com.stratum.core.data.ai

import com.stratum.core.domain.ai.CompletionRequest
import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.ai.ModelCatalogPort
import com.stratum.core.domain.ai.ModelDescriptor
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible chat endpoint, which is what OpenRouter,
 * together.ai, a local llama.cpp server and most others expose.
 *
 * This class knows about HTTP and nothing about content packs. Prompt building,
 * parsing and validation all live in `:core:domain`, so swapping providers never
 * touches a rule.
 */
class OpenRouterLanguageModel(
    private val configProvider: () -> ProviderConfig,
    private val client: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().build(),
) : LanguageModelPort, ModelCatalogPort {

    override suspend fun complete(
        request: CompletionRequest,
        observer: GenerationObserver,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            observer.onStage(GenerationStage.PREPARING)
            val config = configProvider()
            if (config.apiKey.isBlank()) {
                observer.onStage(GenerationStage.FAILED)
                return@withContext Result.failure(
                    GenerationException("No API key is configured for ${config.displayName}"),
                )
            }

            val model = request.modelId ?: config.model
            val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"
            val payload = moshi.adapter(ChatRequestDto::class.java).toJson(
                ChatRequestDto(
                    model = model,
                    messages = listOf(
                        ChatMessageDto("system", request.systemPrompt),
                        ChatMessageDto("user", request.userPrompt),
                    ),
                    temperature = request.temperature.toDouble(),
                    max_tokens = request.maxTokens,
                ),
            )
            // The key travels in a header and is never recorded.
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
                    .apply {
                        // OpenRouter attributes requests with these; other
                        // OpenAI-compatible servers ignore them.
                        addHeader("HTTP-Referer", config.refererUrl)
                        addHeader("X-Title", config.appTitle)
                    }
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                observer.onStage(GenerationStage.SENDING)
                client.newCall(httpRequest).execute().use { response ->
                    observer.onStage(GenerationStage.READING)
                    status = response.code
                    val body = response.body?.string().orEmpty()
                    responseBody = body.take(MAX_RECORDED_BODY)
                    if (!response.isSuccessful) {
                        throw GenerationException(describeFailure(response.code, body))
                    }
                    val parsed = moshi.adapter(ChatResponseDto::class.java).fromJson(body)
                    parsed?.choices?.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
                        ?: throw GenerationException("The model returned an empty reply")
                }
            }

            observer.onAttempt(
                GenerationAttempt(
                    id = "txt_$startedAt",
                    label = "Text · ${request.maxTokens} tokens",
                    endpoint = endpoint,
                    model = model,
                    requestBody = payload,
                    redactedHeaders = headers,
                    status = status,
                    responseBody = responseBody,
                    failure = result.exceptionOrNull()?.message,
                    durationMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            observer.onStage(if (result.isSuccess) GenerationStage.DONE else GenerationStage.FAILED)
            result
        }

    override suspend fun availableModels(): Result<List<ModelDescriptor>> =
        withContext(Dispatchers.IO) {
            val config = configProvider()
            runCatching {
                val httpRequest = Request.Builder()
                    .url("${config.baseUrl.trimEnd('/')}/models")
                    .apply {
                        if (config.apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${config.apiKey}")
                        }
                    }
                    .get()
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw GenerationException(describeFailure(response.code, body))
                    }
                    val parsed = moshi.adapter(ModelListDto::class.java).fromJson(body)
                    parsed?.data.orEmpty().map { it.toDescriptor() }
                }
            }
        }

    /**
     * Turns a status code into something the player can act on. "HTTP 402" tells
     * them nothing; "your account is out of credit" tells them what to do.
     */
    private fun describeFailure(code: Int, body: String): String = when (code) {
        401, 403 -> "The API key was rejected. Check it in settings."
        402 -> "The provider reports no remaining credit for this key."
        404 -> "That model is not available on this provider."
        429 -> "The provider is rate limiting. Wait a moment and try again."
        in 500..599 -> "The provider is having trouble (HTTP $code). Try again shortly."
        else -> "The provider refused the request (HTTP $code): ${body.take(200)}"
    }

    private companion object {
        /** Enough to read an error without keeping a whole generation in memory. */
        const val MAX_RECORDED_BODY = 4000
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Generating a whole content pack is a long completion; the default
            // 10 second read timeout cuts it off mid-answer.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** Everything needed to reach a provider. Held by settings, not by the adapter. */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "google/gemini-2.0-flash-exp:free",
    /**
     * Kept separate from [model]: the model that writes a content pack is
     * almost never the one that draws a sprite sheet, and making the player
     * swap a single field between tasks is a trap.
     */
    val imageModel: String = "meta/muse-image",
    val baseUrl: String = "https://openrouter.ai/api/v1/",
    val displayName: String = "OpenRouter",
    val refererUrl: String = "https://github.com/jimd-den/Trendaddy",
    val appTitle: String = "Stratum",
)

@JsonClass(generateAdapter = true)
internal data class ChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double,
    val max_tokens: Int,
)

@JsonClass(generateAdapter = true)
internal data class ChatMessageDto(val role: String, val content: String)

@JsonClass(generateAdapter = true)
internal data class ChatResponseDto(val choices: List<ChatChoiceDto>?)

@JsonClass(generateAdapter = true)
internal data class ChatChoiceDto(val message: ChatMessageDto?)

@JsonClass(generateAdapter = true)
internal data class ModelListDto(val data: List<ModelDto>?)

@JsonClass(generateAdapter = true)
internal data class ModelDto(
    val id: String,
    val name: String?,
    val description: String?,
    val architecture: ArchitectureDto?,
    val pricing: PricingDto?,
) {
    fun toDescriptor(): ModelDescriptor {
        val outputs = architecture?.output_modalities.orEmpty()
        val inputs = architecture?.input_modalities.orEmpty()
        val promptPrice = pricing?.prompt?.toDoubleOrNull() ?: 0.0
        val completionPrice = pricing?.completion?.toDoubleOrNull() ?: 0.0
        val free = promptPrice == 0.0 && completionPrice == 0.0

        return ModelDescriptor(
            id = id,
            name = name ?: id,
            description = description.orEmpty(),
            isFree = free,
            // Only models that actually emit images can make a sprite sheet;
            // vision models merely read them, and offering those for generation
            // is the most confusing thing a model picker can do.
            producesImages = outputs.any { it.equals("image", ignoreCase = true) },
            acceptsImages = inputs.any { it.equals("image", ignoreCase = true) },
            pricingLabel = if (free) "Free" else "$%.4f/1K".format(promptPrice * 1000),
        )
    }
}

@JsonClass(generateAdapter = true)
internal data class ArchitectureDto(
    val input_modalities: List<String>?,
    val output_modalities: List<String>?,
)

@JsonClass(generateAdapter = true)
internal data class PricingDto(val prompt: String?, val completion: String?)
