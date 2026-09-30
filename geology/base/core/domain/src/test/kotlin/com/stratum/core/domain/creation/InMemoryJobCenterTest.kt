package com.stratum.core.domain.creation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every job here runs on virtual time: the clock is the test scheduler's. The
 * centre gets a scope of its own on that scheduler rather than the test's
 * background scope, whose work `advanceUntilIdle` would not wait for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryJobCenterTest {

    private fun TestScope.center(retain: Long = 60_000, capacity: Int = 20) =
        InMemoryJobCenter(CoroutineScope(StandardTestDispatcher(testScheduler)), clock = { testScheduler.currentTime }, retainMillis = retain, capacity = capacity)

    private fun InMemoryJobCenter.job(id: String) = jobs.value.first { it.id == id }

    @Test
    fun `a job is queued with its planned steps, then walks through them in order`() = runTest {
        val jobs = center()
        val gate = CompletableDeferred<Unit>()
        val id = jobs.launch("lore", "The river spirit", steps = listOf("Writing the request", "Checking the reply", "Saved")) {
            begin("Writing the request")
            gate.await()
            begin("Checking the reply", "2 entries")
            begin("Saved")
            "Two entries about the river spirit"
        }
        assertEquals(JobStatus.QUEUED, jobs.job(id).status)
        assertTrue(jobs.job(id).steps.all { it.status == JobStatus.QUEUED })

        runCurrent()
        val midway = jobs.job(id)
        assertEquals(JobStatus.RUNNING, midway.status)
        assertEquals(listOf(JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.QUEUED), midway.steps.map { it.status })

        gate.complete(Unit)
        advanceUntilIdle()
        val done = jobs.job(id)
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(listOf("Writing the request", "Checking the reply", "Saved"), done.steps.map { it.label })
        assertTrue(done.steps.all { it.status == JobStatus.DONE })
        assertEquals("2 entries", done.steps[1].detail)
        assertEquals("Two entries about the river spirit", done.summary)
    }

    @Test
    fun `a step nobody planned is inserted where it happened, ahead of the planned ones`() = runTest {
        val jobs = center()
        val id = jobs.launch("gear", "A cleaver", steps = listOf("Asking", "Saved")) {
            begin("Asking")
            stepFailed("3 problems")
            begin("Asking again")
            begin("Saved")
            null
        }
        advanceUntilIdle()
        val job = jobs.job(id)
        assertEquals(listOf("Asking", "Asking again", "Saved"), job.steps.map { it.label })
        assertEquals(listOf(JobStatus.FAILED, JobStatus.DONE, JobStatus.DONE), job.steps.map { it.status })
        assertEquals("Done.", job.summary)
    }

    @Test
    fun `a timed step counts the seconds and keeps how long it took`() = runTest {
        val jobs = center()
        val reply = CompletableDeferred<Unit>()
        val id = jobs.launch("lore", "Waiting") {
            beginTimed("Waiting for the model")
            reply.await()
            begin("Reading the reply")
            null
        }
        runCurrent()
        assertEquals("0s", jobs.job(id).steps[0].detail)
        advanceTimeBy(12_001)
        assertEquals("12s", jobs.job(id).steps[0].detail)
        advanceTimeBy(2_000)
        reply.complete(Unit)
        advanceUntilIdle()
        val job = jobs.job(id)
        assertEquals("took 14s", job.steps[0].detail)
        assertEquals(14_001, job.elapsedMillis(now = 1_000_000))
    }

    @Test
    fun `a job that fails says why, and the steps it never reached read as cancelled`() = runTest {
        val jobs = center()
        val id = jobs.launch("lore", "Doomed", steps = listOf("Asking", "Saved")) {
            begin("Asking")
            fail("The model could not be reached")
        }
        advanceUntilIdle()
        val job = jobs.job(id)
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals("The model could not be reached", job.summary)
        assertEquals(listOf(JobStatus.FAILED, JobStatus.CANCELLED), job.steps.map { it.status })
    }

    @Test
    fun `an unexpected exception fails the job with its message instead of crashing the app`() = runTest {
        val jobs = center()
        val id = jobs.launch("art", "Crash") { error("disk full") }
        advanceUntilIdle()
        assertEquals(JobStatus.FAILED, jobs.job(id).status)
        assertEquals("disk full", jobs.job(id).summary)
    }

    @Test
    fun `cancelling stops the work, even before it started`() = runTest {
        val jobs = center()
        var reached = false
        val running = jobs.launch("world", "A long crew run") {
            beginTimed("Waiting for the model")
            CompletableDeferred<Unit>().await()
            reached = true
            null
        }
        val queued = jobs.launch("lore", "Never started") { reached = true; null }
        jobs.cancel(queued)
        runCurrent()
        jobs.cancel(running)
        advanceUntilIdle()

        assertEquals(false, reached)
        assertEquals(JobStatus.CANCELLED, jobs.job(queued).status)
        val stopped = jobs.job(running)
        assertEquals(JobStatus.CANCELLED, stopped.status)
        assertEquals("Stopped.", stopped.summary)
        assertEquals(JobStatus.CANCELLED, stopped.steps.single().status)
    }

    @Test
    fun `several jobs run at once, newest first`() = runTest {
        val jobs = center()
        val gates = List(3) { CompletableDeferred<Unit>() }
        val ids = gates.mapIndexed { i, gate -> jobs.launch("lore", "Job $i") { gate.await(); "done $i" } }
        runCurrent()
        assertEquals(ids.reversed(), jobs.jobs.value.map { it.id })
        assertTrue(jobs.jobs.value.all { it.status == JobStatus.RUNNING })
        gates[1].complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(JobStatus.RUNNING, JobStatus.DONE, JobStatus.RUNNING), jobs.jobs.value.map { it.status })
    }

    @Test
    fun `finished jobs are kept for a while, then forgotten, and never more than the cap`() = runTest {
        val jobs = center(retain = 10_000, capacity = 2)
        repeat(3) { i -> jobs.launch("lore", "Old $i") { null } }
        advanceUntilIdle()
        assertEquals(listOf("Old 2", "Old 1"), jobs.jobs.value.map { it.title }, "the oldest past the cap is dropped")

        advanceTimeBy(10_001)
        val fresh = jobs.launch("lore", "Fresh") { CompletableDeferred<Unit>().await(); null }
        runCurrent()
        assertEquals(listOf(fresh), jobs.jobs.value.map { it.id }, "finished jobs past their window are gone; running ones stay")
    }

    @Test
    fun `a finished job can be dismissed, a running one cannot`() = runTest {
        val jobs = center()
        val done = jobs.launch("lore", "Done") { null }
        val running = jobs.launch("lore", "Running") { CompletableDeferred<Unit>().await(); null }
        advanceUntilIdle()
        jobs.dismiss(done)
        jobs.dismiss(running)
        assertEquals(listOf(running), jobs.jobs.value.map { it.id })
        assertNull(jobs.jobs.value.single().finishedAt)
    }

    @Test
    fun `elapsed time reads like a timer`() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("59s", formatElapsed(59_999))
        assertEquals("1m 05s", formatElapsed(65_000))
    }
}
