package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelJobPhase
import com.stratum.core.domain.ai.ModelRequest

/**
 * Which 3D provider answers, and the defaults that make it work out of the box.
 *
 * Each is a public REST API that sells meshes as jobs. They differ in every
 * detail — where the job id is, what "done" is called, whether a picture is
 * sent inline or uploaded first, whether the mesh URL is in the status reply or
 * behind another call — and all of that lives in one [ModelJobProtocol] each,
 * so the adapter that moves the bytes is the same for all of them.
 */
enum class ModelProvider(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    MESHY("Meshy", "https://api.meshy.ai/", "latest"),
    TRIPO("Tripo3D", "https://api.tripo3d.ai/v2/openapi/", "v2.5-20250123"),
    FAL("fal.ai queue", "https://queue.fal.run/", "fal-ai/trellis"),
    REPLICATE("Replicate", "https://api.replicate.com/v1/", "firtoz/trellis"),
    ;

    companion object {
        fun parse(name: String?): ModelProvider = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MESHY
    }
}

/** The player's settings for the 3D provider: separate from the text and image provider, because it is a different account. */
data class ModelProviderConfig(
    val provider: ModelProvider = ModelProvider.MESHY,
    val apiKey: String = "",
    val baseUrl: String = provider.defaultBaseUrl,
    val modelId: String = provider.defaultModel,
) {
    /** The base URL with exactly one trailing slash, so paths can be appended. */
    val base: String get() = baseUrl.trim().ifBlank { provider.defaultBaseUrl }.trimEnd('/') + "/"
    val model: String get() = modelId.trim().ifBlank { provider.defaultModel }
}

/** A file sent as multipart form data, for providers that take uploads rather than data URLs. */
class MultipartFile(val field: String, val fileName: String, val mimeType: String, val bytes: ByteArray)

/**
 * One HTTP request, described rather than made, so a protocol can be tested by
 * reading what it would send.
 */
data class HttpCall(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: String? = null,
    val multipart: MultipartFile? = null,
) {
    /** The headers as they may be written to the generation journal: credentials never are. */
    val redactedHeaders: Map<String, String>
        get() = headers.mapValues { (name, value) -> if (name.equals("Authorization", ignoreCase = true)) "<redacted>" else value }
}

/** What a submitted job is known by, and where to ask about it. */
data class JobHandle(
    val id: String,
    /** For providers with more than one pass: 0 for the first, 1 for texturing. */
    val stage: Int = 0,
    val pollUrl: String? = null,
    val resultUrl: String? = null,
)

/** What a status reply said. */
sealed interface JobState {
    data class Working(val phase: ModelJobPhase, val fraction: Float? = null, val detail: String = "") : JobState

    /** Finished; the mesh is at [url]. */
    data class Ready(val url: String, val format: ModelFormat = ModelFormat.GLB) : JobState

    /** Finished, but the mesh URL is behind one more call. */
    data class Fetch(val call: HttpCall) : JobState

    /** The first pass finished and a second must be submitted, e.g. texturing an untextured preview. */
    data class Next(val call: HttpCall, val stage: Int) : JobState

    data class Failed(val message: String, val retryable: Boolean = false) : JobState
}

/** What a protocol is given to build a submission. */
data class ModelJobInput(
    val request: ModelRequest,
    /** The reference inline, as a `data:` URL, when there is one. */
    val referenceDataUrl: String? = null,
    /** The reference's upload token, for providers that took an upload first. */
    val referenceToken: String? = null,
)

/**
 * One provider's REST dialect: how to ask, and how to read the answers.
 *
 * Pure mapping, no I/O. Every method either builds an [HttpCall] or reads a
 * body the adapter already fetched, which is what lets each dialect be tested
 * against recorded replies without a network.
 */
interface ModelJobProtocol {
    fun capabilities(config: ModelProviderConfig): ModelCapabilities

    /** The call that uploads a reference picture, or null when pictures travel inline as data URLs. */
    fun upload(config: ModelProviderConfig, bytes: ByteArray, mimeType: String): HttpCall? = null

    /** The token an upload reply names. */
    fun parseUpload(body: String): String = throw ModelProtocolException("This provider takes no uploads")

    fun submit(config: ModelProviderConfig, input: ModelJobInput): HttpCall

    fun parseSubmit(config: ModelProviderConfig, body: String, input: ModelJobInput, stage: Int = 0): JobHandle

    fun poll(config: ModelProviderConfig, handle: JobHandle): HttpCall

    fun parsePoll(config: ModelProviderConfig, body: String, handle: JobHandle, input: ModelJobInput): JobState

    /** Reads the reply to a [JobState.Fetch] call. */
    fun parseFetched(body: String): JobState = throw ModelProtocolException("This provider has no result call")

    companion object {
        fun of(provider: ModelProvider): ModelJobProtocol = when (provider) {
            ModelProvider.MESHY -> MeshyProtocol
            ModelProvider.TRIPO -> TripoProtocol
            ModelProvider.FAL -> FalQueueProtocol
            ModelProvider.REPLICATE -> ReplicateProtocol
        }
    }
}

/** A reply that does not have the shape its provider documents. */
class ModelProtocolException(message: String) : IllegalStateException(message)
