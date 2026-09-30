package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelJobPhase
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Replicate's predictions API.
 *
 * A model is named `owner/name`, which runs its latest version through the
 * models endpoint, or `owner/name:version` for a community model pinned to a
 * version, which goes through `/predictions`. The prediction's own `urls.get`
 * is polled until it succeeds, and the mesh is whatever GLB its output holds.
 *
 * Inputs differ per model, so the picture is sent under the names the common
 * mesh models use (`image`, `images`) and the prompt as `prompt`; Replicate
 * ignores inputs a model does not declare.
 */
internal object ReplicateProtocol : ModelJobProtocol {

    override fun capabilities(config: ModelProviderConfig) =
        ModelCapabilities(textToModel = "text" in config.model.lowercase(), imageToModel = true)

    override fun submit(config: ModelProviderConfig, input: ModelJobInput): HttpCall {
        val model = config.model
        val version = model.substringAfter(':', "").takeIf { it.isNotBlank() }
        val body = ModelJson.encode {
            if (version != null) put("version", version)
            putJsonObject("input") {
                input.referenceDataUrl?.let { reference ->
                    put("image", reference)
                    putJsonArray("images") { add(kotlinx.serialization.json.JsonPrimitive(reference)) }
                }
                put("prompt", input.request.prompt)
                put("generate_model", true)
            }
        }
        val url = if (version != null) config.base + "predictions" else config.base + "models/" + model + "/predictions"
        return HttpCall("POST", url, headers(config), jsonBody = body)
    }

    override fun parseSubmit(config: ModelProviderConfig, body: String, input: ModelJobInput, stage: Int): JobHandle {
        val root = ModelJson.parse(body)
        val id = ModelJson.string(root, "id") ?: throw ModelProtocolException("Replicate named no prediction id: ${body.take(200)}")
        return JobHandle(id, stage, pollUrl = ModelJson.string(root, "urls", "get") ?: "${config.base}predictions/$id")
    }

    override fun poll(config: ModelProviderConfig, handle: JobHandle): HttpCall =
        HttpCall("GET", handle.pollUrl ?: "${config.base}predictions/${handle.id}", headers(config))

    override fun parsePoll(config: ModelProviderConfig, body: String, handle: JobHandle, input: ModelJobInput): JobState {
        val root = ModelJson.parse(body)
        return when (ModelJson.string(root, "status")?.lowercase()) {
            "starting" -> JobState.Working(ModelJobPhase.QUEUED)
            "processing" -> JobState.Working(ModelJobPhase.RUNNING)
            "succeeded" -> ModelJson.findModelUrl(root["output"])?.let { (url, format) -> JobState.Ready(url, format) }
                ?: JobState.Failed("Replicate finished but the output holds no GLB or OBJ")
            "failed", "canceled", "aborted" -> JobState.Failed(
                "Replicate could not build the model: ${ModelJson.string(root, "error") ?: ModelJson.path(root, "error")?.toString() ?: "no reason given"}",
            )
            else -> JobState.Working(ModelJobPhase.RUNNING)
        }
    }

    private fun headers(config: ModelProviderConfig) = mapOf("Authorization" to "Bearer ${config.apiKey}")
}
