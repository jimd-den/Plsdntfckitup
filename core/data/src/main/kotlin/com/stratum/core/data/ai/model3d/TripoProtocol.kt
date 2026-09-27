package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelJobPhase
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Tripo3D's task API.
 *
 * Every reply is wrapped as `{"code": 0, "data": {...}}`, and a non-zero code
 * is a refusal even when the HTTP status is 200 — so the code is checked on
 * every reply rather than trusting the status. Pictures are not sent inline:
 * they are uploaded first and the task names the upload's token.
 */
internal object TripoProtocol : ModelJobProtocol {

    override fun capabilities(config: ModelProviderConfig) = ModelCapabilities(textToModel = true, imageToModel = true)

    override fun upload(config: ModelProviderConfig, bytes: ByteArray, mimeType: String): HttpCall =
        HttpCall(
            "POST", config.base + "upload", headers(config),
            multipart = MultipartFile("file", "reference." + extension(mimeType), mimeType, bytes),
        )

    override fun parseUpload(body: String): String {
        val data = unwrap(body)
        return ModelJson.string(data, "image_token") ?: ModelJson.string(data, "file_token")
            ?: throw ModelProtocolException("Tripo accepted the upload but named no token: ${body.take(200)}")
    }

    override fun submit(config: ModelProviderConfig, input: ModelJobInput): HttpCall {
        val request = input.request
        val token = input.referenceToken
        val body = ModelJson.encode {
            if (token != null) {
                put("type", "image_to_model")
                putJsonObject("file") {
                    put("type", extension(request.reference?.mimeType ?: "image/png"))
                    put("file_token", token)
                }
            } else {
                put("type", "text_to_model")
                put("prompt", request.prompt)
                if (request.negativePrompt.isNotBlank()) put("negative_prompt", request.negativePrompt)
            }
            put("model_version", config.model)
            put("texture", request.textured)
            put("face_limit", request.maxTriangles)
        }
        return HttpCall("POST", config.base + "task", headers(config), jsonBody = body)
    }

    override fun parseSubmit(config: ModelProviderConfig, body: String, input: ModelJobInput, stage: Int): JobHandle {
        val id = ModelJson.string(unwrap(body), "task_id")
            ?: throw ModelProtocolException("Tripo accepted the task but named no task id: ${body.take(200)}")
        return JobHandle(id, stage)
    }

    override fun poll(config: ModelProviderConfig, handle: JobHandle): HttpCall =
        HttpCall("GET", config.base + "task/" + handle.id, headers(config))

    override fun parsePoll(config: ModelProviderConfig, body: String, handle: JobHandle, input: ModelJobInput): JobState {
        val data = unwrap(body)
        val progress = ModelJson.number(data, "progress")?.div(100f)
        return when (ModelJson.string(data, "status")?.lowercase()) {
            "queued" -> JobState.Working(ModelJobPhase.QUEUED, progress)
            "running" -> JobState.Working(ModelJobPhase.RUNNING, progress)
            "success" -> {
                // The textured model when there is one, then the bare one.
                val url = ModelJson.string(data, "output", "pbr_model") ?: ModelJson.string(data, "output", "model")
                    ?: ModelJson.string(data, "output", "base_model")
                    ?: ModelJson.string(data, "result", "pbr_model", "url") ?: ModelJson.string(data, "result", "model", "url")
                url?.let { JobState.Ready(it, if (it.substringBefore('?').endsWith(".obj")) ModelFormat.OBJ else ModelFormat.GLB) }
                    ?: JobState.Failed("Tripo finished but offered no model")
            }
            "failed", "cancelled", "banned", "expired", "unknown" ->
                JobState.Failed("Tripo could not build the model (${ModelJson.string(data, "status")})")
            else -> JobState.Working(ModelJobPhase.RUNNING, progress)
        }
    }

    /** The `data` of a reply whose `code` is zero; anything else is the provider saying no. */
    private fun unwrap(body: String): kotlinx.serialization.json.JsonElement? {
        val root = ModelJson.parse(body)
        val code = ModelJson.number(root, "code")?.toInt() ?: 0
        if (code != 0) {
            throw ModelProtocolException("Tripo refused the request (code $code): ${ModelJson.string(root, "message") ?: body.take(200)}")
        }
        return root["data"]
    }

    private fun extension(mimeType: String) = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun headers(config: ModelProviderConfig) = mapOf("Authorization" to "Bearer ${config.apiKey}")
}
