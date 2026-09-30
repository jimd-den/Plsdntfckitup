package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelJobPhase
import com.stratum.core.domain.ai.ModelRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each provider's dialect, read against replies recorded from its documented shape. No network. */
class ModelProtocolTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("model3d/$name")) { "no fixture $name" }.readText()

    private fun json(text: String?) = Json.parseToJsonElement(text!!).jsonObject

    private val text = ModelJobInput(ModelRequest(prompt = "a mossy stone shrine", negativePrompt = "floor", maxTriangles = 8000))
    private val image = ModelJobInput(ModelRequest(prompt = "idol", reference = ImageReference(byteArrayOf(1))), referenceDataUrl = "data:image/png;base64,AQ==")

    // ---- Meshy ----------------------------------------------------------------

    private val meshy = ModelProviderConfig(ModelProvider.MESHY, apiKey = "msy-key")

    @Test
    fun `meshy text asks for a preview with the budget and polls the same endpoint`() {
        val call = MeshyProtocol.submit(meshy, text)
        assertEquals("https://api.meshy.ai/openapi/v2/text-to-3d", call.url)
        val body = json(call.jsonBody)
        assertEquals("preview", body["mode"]!!.jsonPrimitive.content)
        assertEquals(8000, body["target_polycount"]!!.jsonPrimitive.content.toInt())
        assertEquals("floor", body["negative_prompt"]!!.jsonPrimitive.content)
        assertEquals("Bearer msy-key", call.headers["Authorization"])
        assertEquals("<redacted>", call.redactedHeaders["Authorization"])

        val handle = MeshyProtocol.parseSubmit(meshy, fixture("meshy_submit.json"), text)
        assertEquals("https://api.meshy.ai/openapi/v2/text-to-3d/018a210d-8ba4-705c-b111-1f1776f7f578", MeshyProtocol.poll(meshy, handle).url)
    }

    @Test
    fun `meshy pictures go to image-to-3d inline`() {
        val call = MeshyProtocol.submit(meshy, image)
        assertTrue(call.url.endsWith("openapi/v1/image-to-3d"))
        assertEquals("data:image/png;base64,AQ==", json(call.jsonBody)["image_url"]!!.jsonPrimitive.content)
        val handle = MeshyProtocol.parseSubmit(meshy, fixture("meshy_submit.json"), image)
        assertTrue(handle.pollUrl!!.contains("image-to-3d/"))
    }

    @Test
    fun `meshy progress, the texturing pass, the result and a failure`() {
        val handle = JobHandle("018a210d-8ba4-705c-b111-1f1776f7f578")
        assertEquals(JobState.Working(ModelJobPhase.QUEUED, 0f), MeshyProtocol.parsePoll(meshy, fixture("meshy_pending.json"), handle, text))
        assertEquals(JobState.Working(ModelJobPhase.RUNNING, 0.42f), MeshyProtocol.parsePoll(meshy, fixture("meshy_in_progress.json"), handle, text))

        // A finished untextured preview chains the refine pass.
        val next = assertIs<JobState.Next>(MeshyProtocol.parsePoll(meshy, fixture("meshy_succeeded.json"), handle, text))
        assertEquals(1, next.stage)
        assertEquals("refine", json(next.call.jsonBody)["mode"]!!.jsonPrimitive.content)
        assertEquals(handle.id, json(next.call.jsonBody)["preview_task_id"]!!.jsonPrimitive.content)

        val ready = assertIs<JobState.Ready>(MeshyProtocol.parsePoll(meshy, fixture("meshy_succeeded.json"), handle.copy(stage = 1), text))
        assertTrue(ready.url.startsWith("https://assets.meshy.ai/") && ".glb" in ready.url)

        // Geometry only stops after the preview.
        val bare = text.copy(request = text.request.copy(textured = false))
        assertIs<JobState.Ready>(MeshyProtocol.parsePoll(meshy, fixture("meshy_succeeded.json"), handle, bare))

        val failed = assertIs<JobState.Failed>(MeshyProtocol.parsePoll(meshy, fixture("meshy_failed.json"), handle, text))
        assertTrue("moderation" in failed.message)
    }

    // ---- Tripo ----------------------------------------------------------------

    private val tripo = ModelProviderConfig(ModelProvider.TRIPO, apiKey = "tsk_key")

    @Test
    fun `tripo uploads the picture and names its token in the task`() {
        val upload = TripoProtocol.upload(tripo, byteArrayOf(1, 2), "image/jpeg")
        assertEquals("https://api.tripo3d.ai/v2/openapi/upload", upload.url)
        assertEquals("reference.jpg", upload.multipart!!.fileName)
        val token = TripoProtocol.parseUpload(fixture("tripo_upload.json"))

        val task = TripoProtocol.submit(tripo, ModelJobInput(image.request.copy(reference = ImageReference(byteArrayOf(1), "image/jpeg")), referenceToken = token))
        val body = json(task.jsonBody)
        assertEquals("image_to_model", body["type"]!!.jsonPrimitive.content)
        assertEquals(token, body["file"]!!.jsonObject["file_token"]!!.jsonPrimitive.content)
        assertEquals("jpg", body["file"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("v2.5-20250123", body["model_version"]!!.jsonPrimitive.content)

        assertEquals("text_to_model", json(TripoProtocol.submit(tripo, text).jsonBody)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tripo reads status, prefers the textured model, and treats a nonzero code as a refusal`() {
        val handle = TripoProtocol.parseSubmit(tripo, fixture("tripo_submit.json"), text)
        assertEquals("https://api.tripo3d.ai/v2/openapi/task/1ec04ced-4b87-44f6-a296-beee80777941", TripoProtocol.poll(tripo, handle).url)
        assertEquals(JobState.Working(ModelJobPhase.RUNNING, 0.35f), TripoProtocol.parsePoll(tripo, fixture("tripo_running.json"), handle, text))
        val ready = assertIs<JobState.Ready>(TripoProtocol.parsePoll(tripo, fixture("tripo_success.json"), handle, text))
        assertTrue("pbr_model.glb" in ready.url)

        val refused = assertFailsWith<ModelProtocolException> { TripoProtocol.parseSubmit(tripo, fixture("tripo_refused.json"), text) }
        assertTrue("2010" in refused.message.orEmpty() && "credit" in refused.message.orEmpty())
    }

    // ---- fal --------------------------------------------------------------------

    private val fal = ModelProviderConfig(ModelProvider.FAL, apiKey = "fal-key")

    @Test
    fun `fal queues on the model path, polls the status url and fetches the result`() {
        assertEquals(false, FalQueueProtocol.capabilities(fal).textToModel, "trellis is image-only")
        val call = FalQueueProtocol.submit(fal, image)
        assertEquals("https://queue.fal.run/fal-ai/trellis", call.url)
        assertEquals("Key fal-key", call.headers["Authorization"])
        assertNull(json(call.jsonBody)["prompt"], "an image-only model is not sent a prompt")

        val handle = FalQueueProtocol.parseSubmit(fal, fixture("fal_submit.json"), image)
        assertTrue(FalQueueProtocol.poll(fal, handle).url.endsWith("/status"))
        val queued = assertIs<JobState.Working>(FalQueueProtocol.parsePoll(fal, fixture("fal_queued.json"), handle, image))
        assertEquals(ModelJobPhase.QUEUED, queued.phase)
        assertTrue("2" in queued.detail)

        val fetch = assertIs<JobState.Fetch>(FalQueueProtocol.parsePoll(fal, fixture("fal_completed.json"), handle, image))
        assertEquals(handle.resultUrl, fetch.call.url)
        val ready = assertIs<JobState.Ready>(FalQueueProtocol.parseFetched(fixture("fal_result.json")))
        assertEquals("https://v3.fal.media/files/lion/abc123_mesh.glb", ready.url)
    }

    @Test
    fun `a fal text model takes a prompt`() {
        val textModel = fal.copy(modelId = "fal-ai/some-model/text-to-3d")
        assertTrue(FalQueueProtocol.capabilities(textModel).textToModel)
        assertEquals("a mossy stone shrine", json(FalQueueProtocol.submit(textModel, text).jsonBody)["prompt"]!!.jsonPrimitive.content)
    }

    // ---- Replicate --------------------------------------------------------------

    private val replicate = ModelProviderConfig(ModelProvider.REPLICATE, apiKey = "r8_key")

    @Test
    fun `replicate runs a named model or a pinned version`() {
        assertEquals("https://api.replicate.com/v1/models/firtoz/trellis/predictions", ReplicateProtocol.submit(replicate, image).url)
        val pinned = ReplicateProtocol.submit(replicate.copy(modelId = "firtoz/trellis:e8f6c452"), image)
        assertEquals("https://api.replicate.com/v1/predictions", pinned.url)
        assertEquals("e8f6c452", json(pinned.jsonBody)["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replicate polls urls get and finds the glb in whatever output the model has`() {
        val handle = ReplicateProtocol.parseSubmit(replicate, fixture("replicate_submit.json"), image)
        assertEquals("https://api.replicate.com/v1/predictions/gm3qorzdhgbfurvjtvhg6dckhu", ReplicateProtocol.poll(replicate, handle).url)
        assertEquals(ModelJobPhase.RUNNING, assertIs<JobState.Working>(ReplicateProtocol.parsePoll(replicate, fixture("replicate_processing.json"), handle, image)).phase)
        val ready = assertIs<JobState.Ready>(ReplicateProtocol.parsePoll(replicate, fixture("replicate_succeeded.json"), handle, image))
        assertEquals("https://replicate.delivery/xezq/abc/output.glb", ready.url)
        assertEquals(ModelFormat.GLB, ready.format)
        val failed = assertIs<JobState.Failed>(ReplicateProtocol.parsePoll(replicate, fixture("replicate_failed.json"), handle, image))
        assertTrue("CUDA" in failed.message)
    }

    @Test
    fun `a reply that is not JSON is a protocol error, not a crash`() {
        assertFailsWith<ModelProtocolException> { MeshyProtocol.parseSubmit(meshy, "<html>502</html>", text) }
        assertEquals(ModelProvider.MESHY, ModelProvider.parse("nonsense"))
        assertEquals(ModelProvider.FAL, ModelProvider.parse("fal"))
        assertEquals("https://example.test/", ModelProviderConfig(baseUrl = "https://example.test///").base)
    }
}
