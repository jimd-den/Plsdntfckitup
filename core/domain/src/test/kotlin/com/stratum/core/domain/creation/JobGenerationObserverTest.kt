package com.stratum.core.domain.creation

import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class JobGenerationObserverTest {

    private fun attempt(model: String, status: Int?, failure: String? = null, millis: Long = 4_000) =
        GenerationAttempt("a", "lore", "https://example", model, "{}", status = status, failure = failure, durationMillis = millis)

    @Test
    fun `a provider's stages become the job's steps, with the wait counted and the answer noted`() = runTest {
        val jobs = InMemoryJobCenter(CoroutineScope(StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime })
        val replied = CompletableDeferred<Unit>()
        val seen = mutableListOf<GenerationStage>()
        val id = jobs.launch("lore", "River spirit") {
            val observer = observer(model = "writer-1", also = object : GenerationObserver {
                override fun onStage(stage: GenerationStage) { seen += stage }
            })
            observer.onStage(GenerationStage.PREPARING)
            observer.onStage(GenerationStage.SENDING)
            replied.await()
            observer.onStage(GenerationStage.READING)
            observer.onAttempt(attempt("provider/writer-1", 200, millis = 12_400))
            observer.onStage(GenerationStage.DONE)
            null
        }
        runCurrent()
        advanceTimeBy(5_001)
        val waiting = jobs.jobs.value.single().steps
        assertEquals(listOf("Writing the request", "Waiting for writer-1"), waiting.map { it.label })
        assertEquals("5s", waiting[1].detail)

        replied.complete(Unit)
        advanceUntilIdle()
        val steps = jobs.jobs.value.single { it.id == id }.steps
        assertEquals(listOf("Writing the request", "Waiting for writer-1", "Reading the reply"), steps.map { it.label })
        assertEquals("answered in 12s", steps[1].detail)
        assertEquals(listOf(GenerationStage.PREPARING, GenerationStage.SENDING, GenerationStage.READING, GenerationStage.DONE), seen)
    }

    @Test
    fun `a retry shows as its own steps, named for the model that answered, and a failure keeps the provider's words`() = runTest {
        val jobs = InMemoryJobCenter(CoroutineScope(StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime })
        jobs.launch("lore", "Retry") {
            val observer = observer()
            observer.onStage(GenerationStage.PREPARING)
            observer.onStage(GenerationStage.SENDING)
            observer.onStage(GenerationStage.READING)
            observer.onAttempt(attempt("writer-2", 429, failure = "rate limited"))
            observer.onStage(GenerationStage.FAILED)
            observer.onStage(GenerationStage.PREPARING)
            observer.onStage(GenerationStage.WAITING)
            null
        }
        advanceUntilIdle()
        val steps = jobs.jobs.value.single().steps
        assertEquals(
            listOf("Writing the request", "Waiting for the model", "Reading the reply", "Writing the request again (try 2)", "Waiting for writer-2"),
            steps.map { it.label },
        )
        assertEquals("HTTP 429: rate limited", steps[1].detail)
        assertEquals(JobStatus.FAILED, steps[2].status)
    }
}
