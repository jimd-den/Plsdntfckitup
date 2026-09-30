package com.stratum.core.data.ai.model3d

import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationException
import com.stratum.core.domain.ai.ModelFormat
import com.stratum.core.domain.ai.ModelGenerationObserver
import com.stratum.core.domain.ai.ModelJobPhase
import com.stratum.core.domain.ai.ModelJobProgress
import com.stratum.core.domain.ai.ModelRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole job, end to end, against a client that answers from recorded
 * replies instead of the network.
 */
class HttpModelGenerationTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("model3d/$name")!!.readText()

    private val glb = byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0, 0, 0, 12, 0, 0, 0)

    /** Answers by URL and method, in order for repeated polls, and remembers what was asked. */
    private class Recorded(private val answers: MutableMap<String, ArrayDeque<Pair<Int, ByteArray>>>) : Interceptor {
        val seen = ArrayList<Pair<String, String?>>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val key = "${request.method} ${request.url}"
            seen += key to request.header("Authorization")
            val queue = answers[key] ?: error("unexpected call $key")
            val (code, body) = if (queue.size > 1) queue.removeFirst() else queue.first()
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun client(interceptor: Interceptor) = OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test
    fun `a textured meshy job runs preview, refine and download, reporting progress`() = runBlocking {
        val base = "https://api.meshy.ai/openapi/v2/text-to-3d"
        val id = "018a210d-8ba4-705c-b111-1f1776f7f578"
        val glbUrl = "https://assets.meshy.ai/tasks/018a210d/output/model.glb?Expires=4079001600&Signature=abc"
        val recorded = Recorded(
            mutableMapOf(
                "POST $base" to ArrayDeque(listOf(200 to fixture("meshy_submit.json").toByteArray())),
                "GET $base/$id" to ArrayDeque(
                    listOf(
                        200 to fixture("meshy_in_progress.json").toByteArray(),
                        200 to fixture("meshy_succeeded.json").toByteArray(),
                        200 to fixture("meshy_succeeded.json").toByteArray(),
                    ),
                ),
                "GET $glbUrl" to ArrayDeque(listOf(200 to glb)),
            ),
        )
        val reports = ArrayList<ModelJobProgress>()
        val attempts = ArrayList<GenerationAttempt>()
        val observer = object : ModelGenerationObserver {
            override fun onProgress(progress: ModelJobProgress) { reports += progress }
            override fun onAttempt(attempt: GenerationAttempt) { attempts += attempt }
        }
        val port = HttpModelGeneration(
            configProvider = { ModelProviderConfig(ModelProvider.MESHY, apiKey = "msy-key") },
            client = client(recorded),
            pollInterval = 0,
        )

        val model = port.generateModel(ModelRequest("a shrine"), observer).getOrThrow()

        assertEquals(ModelFormat.GLB, model.format)
        assertTrue(model.bytes.contentEquals(glb))
        // Submit, poll, (preview done) refine submit, poll, download.
        assertEquals(2, recorded.seen.count { it.first == "POST $base" })
        assertTrue(reports.any { it.phase == ModelJobPhase.RUNNING && it.fraction == 0.42f })
        assertTrue(reports.any { it.phase == ModelJobPhase.DOWNLOADING })
        // The key goes to the provider and never to the storage host.
        assertTrue(recorded.seen.filter { it.first.startsWith("POST") }.all { it.second == "Bearer msy-key" })
        assertEquals(null, recorded.seen.single { it.first == "GET $glbUrl" }.second)
        assertTrue(attempts.all { it.redactedHeaders["Authorization"] == "<redacted>" })
    }

    @Test
    fun `a rejected key is fatal and a missing key never calls out`() = runBlocking {
        val recorded = Recorded(mutableMapOf("POST https://api.meshy.ai/openapi/v2/text-to-3d" to ArrayDeque(listOf(401 to "{}".toByteArray()))))
        val rejected = HttpModelGeneration({ ModelProviderConfig(apiKey = "bad") }, client(recorded), pollInterval = 0)
            .generateModel(ModelRequest("x")).exceptionOrNull() as GenerationException
        assertTrue(rejected.fatal)

        val none = Recorded(mutableMapOf())
        val missing = HttpModelGeneration({ ModelProviderConfig(apiKey = "") }, client(none)).generateModel(ModelRequest("x"))
        assertTrue(missing.isFailure)
        assertTrue(none.seen.isEmpty())
    }

    @Test
    fun `a job that never finishes times out as retryable`() = runBlocking {
        val base = "https://api.meshy.ai/openapi/v2/text-to-3d"
        val recorded = Recorded(
            mutableMapOf(
                "POST $base" to ArrayDeque(listOf(200 to fixture("meshy_submit.json").toByteArray())),
                "GET $base/018a210d-8ba4-705c-b111-1f1776f7f578" to ArrayDeque(listOf(200 to fixture("meshy_pending.json").toByteArray())),
            ),
        )
        var now = 0L
        val port = HttpModelGeneration(
            { ModelProviderConfig(apiKey = "k") }, client(recorded), pollInterval = 0, timeout = 1_000, clock = { now.also { now += 400 } },
        )
        val failure = port.generateModel(ModelRequest("x")).exceptionOrNull() as GenerationException
        assertTrue(failure.retryable)
    }
}
