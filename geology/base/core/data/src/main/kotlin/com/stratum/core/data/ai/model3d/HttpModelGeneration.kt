package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.GeneratedModel
import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelGenerationObserver
import com.stratum.core.domain.ai.ModelGenerationPort
import com.stratum.core.domain.ai.ModelJobPhase
import com.stratum.core.domain.ai.ModelJobProgress
import com.stratum.core.domain.ai.ModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Asks whichever 3D provider the player configured for a model, and waits.
 *
 * One adapter for every provider: the dialect is a [ModelJobProtocol] chosen
 * by the settings on every call, and this class only moves bytes — submit,
 * poll until done, maybe chain a second pass, download. Reading the settings
 * each call means switching provider in settings takes effect on the next
 * model, not the next launch.
 *
 * The finished mesh is downloaded **without** the API key. The URLs these
 * providers hand back are signed links on a storage host, and sending the
 * player's credential to a host they never configured would be a leak waiting
 * for a redirect.
 */
class HttpModelGeneration(
    private val configProvider: () -> ModelProviderConfig,
    private val client: OkHttpClient = defaultClient(),
    private val protocolFor: (ModelProvider) -> ModelJobProtocol = ModelJobProtocol::of,
    private val pollInterval: Long = POLL_INTERVAL_MS,
    private val timeout: Long = TIMEOUT_MS,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
    /** Monotonic time, injectable so the timeout can be tested without waiting ten minutes. */
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelGenerationPort {

    override val capabilities: ModelCapabilities
        get() = configProvider().let { protocolFor(it.provider).capabilities(it) }

    override suspend fun generateModel(request: ModelRequest, observer: ModelGenerationObserver): Result<GeneratedModel> =
        withContext(Dispatchers.IO) {
            observer.onStage(GenerationStage.PREPARING)
            val config = configProvider()
            if (config.apiKey.isBlank()) {
                observer.onStage(GenerationStage.FAILED)
                return@withContext Result.failure(GenerationException("No API key is configured for ${config.provider.displayName}", fatal = true))
            }
            runCatching { run(config, protocolFor(config.provider), request, observer) }
                .recoverCatching { failure ->
                    throw when (failure) {
                        is GenerationException -> failure
                        is ModelProtocolException -> GenerationException(failure.message ?: "The provider's reply made no sense", failure)
                        is java.io.IOException -> GenerationException("Could not reach ${config.provider.displayName}: ${failure.message}", failure, retryable = true)
                        else -> failure
                    }
                }
                .onSuccess { observer.onStage(GenerationStage.DONE) }
                .onFailure { observer.onStage(GenerationStage.FAILED) }
        }

    private suspend fun run(config: ModelProviderConfig, protocol: ModelJobProtocol, request: ModelRequest, observer: ModelGenerationObserver): GeneratedModel {
        val reference = request.reference
        var input = ModelJobInput(request)
        if (reference != null) {
            val upload = protocol.upload(config, reference.bytes, reference.mimeType)
            input = if (upload != null) {
                observer.onProgress(ModelJobProgress(ModelJobPhase.UPLOADING))
                input.copy(referenceToken = protocol.parseUpload(execute(upload, config, observer, "Upload reference")))
            } else {
                input.copy(referenceDataUrl = dataUrl(reference.bytes, reference.mimeType))
            }
        }

        observer.onStage(GenerationStage.SENDING)
        var handle = protocol.parseSubmit(config, execute(protocol.submit(config, input), config, observer, "3D model"), input)
        observer.onStage(GenerationStage.WAITING)
        val deadline = clock() + timeout
        while (true) {
            val state = protocol.parsePoll(config, execute(protocol.poll(config, handle), config, observer, null), handle, input)
            val settled = when (state) {
                is JobState.Fetch -> protocol.parseFetched(execute(state.call, config, observer, "Model result"))
                else -> state
            }
            when (settled) {
                is JobState.Working -> observer.onProgress(ModelJobProgress(settled.phase, settled.fraction, settled.detail))
                is JobState.Next -> {
                    handle = protocol.parseSubmit(config, execute(settled.call, config, observer, "3D model, next pass"), input, settled.stage)
                    observer.onProgress(ModelJobProgress(ModelJobPhase.TEXTURING, 0f))
                    continue
                }
                is JobState.Failed -> throw GenerationException(settled.message, retryable = settled.retryable)
                is JobState.Ready -> {
                    observer.onStage(GenerationStage.READING)
                    observer.onProgress(ModelJobProgress(ModelJobPhase.DOWNLOADING))
                    val bytes = download(settled.url)
                    val format = ModelFormat.sniff(bytes) ?: settled.format
                    return GeneratedModel(bytes, format, handle.id)
                }
                is JobState.Fetch -> throw GenerationException("The provider's result pointed at another result")
            }
            if (clock() >= deadline) {
                throw GenerationException("The model was still being built after ${timeout / 60_000} minutes", retryable = true)
            }
            delay(pollInterval)
        }
    }

    /** Makes a call and returns its body, journalling it when [label] is set. */
    private fun execute(call: HttpCall, config: ModelProviderConfig, observer: ModelGenerationObserver, label: String?): String {
        val builder = Request.Builder().url(call.url)
        call.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        val body = when {
            call.multipart != null -> MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(call.multipart.field, call.multipart.fileName, call.multipart.bytes.toRequestBody(call.multipart.mimeType.toMediaType()))
                .build()
            call.jsonBody != null -> call.jsonBody.toRequestBody(JSON)
            else -> null
        }
        builder.method(call.method, body)
        val started = clock()
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (label != null || !response.isSuccessful) {
                observer.onAttempt(
                    GenerationAttempt(
                        id = "${config.provider.name.lowercase()}-$started",
                        label = label ?: "Status",
                        endpoint = call.url.substringBefore('?'),
                        model = config.model,
                        // Inline pictures are megabytes of base64; the journal keeps the shape, not the picture.
                        requestBody = call.jsonBody?.replace(DATA_URL, "data:…").orEmpty(),
                        redactedHeaders = call.redactedHeaders,
                        status = response.code,
                        responseBody = text.take(JOURNAL_LIMIT),
                        failure = if (response.isSuccessful) null else "HTTP ${response.code}",
                        durationMillis = clock() - started,
                    ),
                )
            }
            if (!response.isSuccessful) throw failureFor(config, response.code, text)
            return text
        }
    }

    /** Streams the mesh down, refusing anything larger than a phone should hold. */
    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GenerationException("The finished model could not be downloaded (HTTP ${response.code})", retryable = response.code >= 500)
            }
            val body = response.body ?: throw GenerationException("The finished model downloaded as nothing")
            if (body.contentLength() > maxDownloadBytes) {
                throw GenerationException("The model is ${body.contentLength() / 1_048_576} MB, too large for a phone")
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxDownloadBytes) throw GenerationException("The model is too large for a phone")
                }
            }
            if (out.size() == 0) throw GenerationException("The finished model downloaded as nothing")
            return out.toByteArray()
        }
    }

    private fun failureFor(config: ModelProviderConfig, code: Int, body: String): GenerationException {
        val name = config.provider.displayName
        return when (code) {
            400, 422 -> GenerationException("$name refused the request: ${body.take(300)}")
            401, 403 -> GenerationException("$name rejected the API key. Check it in settings.", fatal = true)
            402 -> GenerationException("$name reports no remaining credit.", fatal = true)
            404 -> GenerationException("$name has no model '${config.model}', or the endpoint is wrong.", fatal = true)
            429 -> GenerationException("$name is rate limiting.", retryable = true)
            in 500..599 -> GenerationException("$name is having trouble (HTTP $code).", retryable = true)
            else -> GenerationException("$name refused the request (HTTP $code): ${body.take(200)}")
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 5_000L

        /** Textured text-to-3D is two passes of a few minutes each at busy times. */
        const val TIMEOUT_MS = 15 * 60_000L

        const val MAX_DOWNLOAD_BYTES = 48L * 1_048_576

        private const val JOURNAL_LIMIT = 4_000
        private val JSON = "application/json".toMediaType()
        private val DATA_URL = Regex("data:[a-z/+-]+;base64,[A-Za-z0-9+/=]+")

        @OptIn(ExperimentalEncodingApi::class)
        fun dataUrl(bytes: ByteArray, mimeType: String): String = "data:$mimeType;base64," + Base64.encode(bytes)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
