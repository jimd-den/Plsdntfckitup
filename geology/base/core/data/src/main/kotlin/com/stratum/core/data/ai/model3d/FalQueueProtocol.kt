package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelJobPhase
import kotlinx.serialization.json.put

/**
 * fal.ai's queue API, in front of whichever mesh model is configured —
 * Trellis, Hunyuan3D and the like.
 *
 * Three calls: submit to `/{model}`, poll the `status_url` the reply names, and
 * when it says COMPLETED fetch the `response_url` for the model's own output.
 * That output's shape belongs to the model, not to fal, so the mesh URL is
 * found by looking for the GLB in it rather than by a fixed field.
 *
 * Most of these models are image-to-3D only. A model id that says `text` is
 * taken to accept a prompt; any other is image-only, and the domain draws a
 * reference first.
 */
internal object FalQueueProtocol : ModelJobProtocol {

    override fun capabilities(config: ModelProviderConfig) =
        ModelCapabilities(textToModel = "text" in config.model.lowercase(), imageToModel = true)

    override fun submit(config: ModelProviderConfig, input: ModelJobInput): HttpCall {
        val body = ModelJson.encode {
            input.referenceDataUrl?.let { put("image_url", it) }
            if (capabilities(config).textToModel) put("prompt", input.request.prompt)
        }
        return HttpCall("POST", config.base + config.model, headers(config), jsonBody = body)
    }

    override fun parseSubmit(config: ModelProviderConfig, body: String, input: ModelJobInput, stage: Int): JobHandle {
        val root = ModelJson.parse(body)
        val id = ModelJson.string(root, "request_id")
            ?: throw ModelProtocolException("fal queued the request but named no request id: ${body.take(200)}")
        // Status lives under the model's app id, which for a sub-path model is its first two segments.
        val app = config.model.split('/').take(2).joinToString("/")
        return JobHandle(
            id, stage,
            pollUrl = ModelJson.string(root, "status_url") ?: "${config.base}$app/requests/$id/status",
            resultUrl = ModelJson.string(root, "response_url") ?: "${config.base}$app/requests/$id",
        )
    }

    override fun poll(config: ModelProviderConfig, handle: JobHandle): HttpCall =
        HttpCall("GET", handle.pollUrl ?: error("a fal job always has a status url"), headers(config))

    override fun parsePoll(config: ModelProviderConfig, body: String, handle: JobHandle, input: ModelJobInput): JobState {
        val root = ModelJson.parse(body)
        return when (ModelJson.string(root, "status")?.uppercase()) {
            "IN_QUEUE" -> JobState.Working(
                ModelJobPhase.QUEUED,
                detail = ModelJson.number(root, "queue_position")?.let { "Position ${it.toInt()} in the queue" }.orEmpty(),
            )
            "IN_PROGRESS" -> JobState.Working(ModelJobPhase.RUNNING)
            "COMPLETED" -> ModelJson.string(root, "error")?.let { JobState.Failed("fal could not build the model: $it") }
                ?: JobState.Fetch(HttpCall("GET", handle.resultUrl ?: error("a fal job always has a result url"), headers(config)))
            else -> JobState.Working(ModelJobPhase.RUNNING)
        }
    }

    override fun parseFetched(body: String): JobState {
        val root = ModelJson.parse(body)
        ModelJson.path(root, "detail")?.let { return JobState.Failed("fal could not build the model: ${it.toString().take(300)}") }
        val (url, format) = ModelJson.findModelUrl(root) ?: return JobState.Failed("fal finished but the output holds no GLB or OBJ")
        return JobState.Ready(url, format)
    }

    /** fal's keys are sent as `Key`, not `Bearer`. */
    private fun headers(config: ModelProviderConfig) = mapOf("Authorization" to "Key ${config.apiKey}")
}
