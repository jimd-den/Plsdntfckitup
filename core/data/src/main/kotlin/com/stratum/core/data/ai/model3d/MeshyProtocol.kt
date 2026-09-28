package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ModelCapabilities
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelJobPhase
import kotlinx.serialization.json.put

/**
 * Meshy's task API.
 *
 * Text-to-3D is two passes: a *preview* that is geometry only, then a *refine*
 * that paints it, submitted with the preview's id. Asked for a textured model
 * the protocol chains the second pass itself ([JobState.Next]); asked for bare
 * geometry it stops after the first, which is quicker and a fraction of the
 * price. Image-to-3D is one pass that textures as it goes, and takes the
 * picture inline as a data URL.
 */
internal object MeshyProtocol : ModelJobProtocol {

    private const val TEXT = "openapi/v2/text-to-3d"
    private const val IMAGE = "openapi/v1/image-to-3d"

    override fun capabilities(config: ModelProviderConfig) = ModelCapabilities(textToModel = true, imageToModel = true)

    override fun submit(config: ModelProviderConfig, input: ModelJobInput): HttpCall {
        val request = input.request
        val reference = input.referenceDataUrl
        return if (reference != null) {
            post(config, config.base + IMAGE) {
                put("image_url", reference)
                put("ai_model", config.model)
                put("should_texture", request.textured)
                put("should_remesh", true)
                put("target_polycount", request.maxTriangles)
            }
        } else {
            post(config, config.base + TEXT) {
                put("mode", "preview")
                put("prompt", request.prompt.take(MAX_PROMPT))
                if (request.negativePrompt.isNotBlank()) put("negative_prompt", request.negativePrompt)
                put("ai_model", config.model)
                put("should_remesh", true)
                put("target_polycount", request.maxTriangles)
            }
        }
    }

    override fun parseSubmit(config: ModelProviderConfig, body: String, input: ModelJobInput, stage: Int): JobHandle {
        val id = ModelJson.string(ModelJson.parse(body), "result")
            ?: throw ModelProtocolException("Meshy accepted the task but named no task id: ${body.take(200)}")
        // A task is polled on the endpoint that created it.
        val endpoint = if (input.referenceDataUrl != null) IMAGE else TEXT
        return JobHandle(id, stage, pollUrl = config.base + endpoint + "/" + id)
    }

    override fun poll(config: ModelProviderConfig, handle: JobHandle): HttpCall =
        HttpCall("GET", handle.pollUrl ?: (config.base + TEXT + "/" + handle.id), headers(config))

    override fun parsePoll(config: ModelProviderConfig, body: String, handle: JobHandle, input: ModelJobInput): JobState {
        val root = ModelJson.parse(body)
        val progress = ModelJson.number(root, "progress")?.div(100f)
        return when (ModelJson.string(root, "status")?.uppercase()) {
            "PENDING" -> JobState.Working(ModelJobPhase.QUEUED, progress)
            "IN_PROGRESS" -> JobState.Working(if (handle.stage == 1) ModelJobPhase.TEXTURING else ModelJobPhase.RUNNING, progress)
            "SUCCEEDED" -> {
                val textPreview = input.referenceDataUrl == null && handle.stage == 0
                if (textPreview && input.request.textured) {
                    JobState.Next(
                        post(config, config.base + TEXT) {
                            put("mode", "refine")
                            put("preview_task_id", handle.id)
                        },
                        stage = 1,
                    )
                } else {
                    ModelJson.string(root, "model_urls", "glb")?.let { JobState.Ready(it, ModelFormat.GLB) }
                        ?: ModelJson.string(root, "model_urls", "obj")?.let { JobState.Ready(it, ModelFormat.OBJ) }
                        ?: JobState.Failed("Meshy finished but offered no GLB or OBJ")
                }
            }
            "FAILED", "CANCELED", "EXPIRED" -> JobState.Failed(
                ModelJson.string(root, "task_error", "message") ?: "Meshy could not build the model",
            )
            else -> JobState.Working(ModelJobPhase.RUNNING, progress)
        }
    }

    private fun post(config: ModelProviderConfig, url: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        HttpCall("POST", url, headers(config), jsonBody = ModelJson.encode(body))

    private fun headers(config: ModelProviderConfig) = mapOf("Authorization" to "Bearer ${config.apiKey}")

    /** Meshy refuses prompts past this length rather than truncating them. */
    private const val MAX_PROMPT = 600
}
