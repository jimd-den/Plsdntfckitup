package com.stratum.core.domain.creation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The job centre the app runs: each job a coroutine in [scope], its state one
 * immutable list in a [StateFlow].
 *
 * In memory on purpose. A job is the live view of work in progress; what the
 * work produces is saved by the work itself, so there is nothing here worth a
 * restart. Finished jobs are kept for [retainMillis] so a result can still be
 * read after the player wanders off and back, and at most [capacity] of them,
 * so a long session of forging does not become a long list.
 */
class InMemoryJobCenter(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retainMillis: Long = DEFAULT_RETAIN_MILLIS,
    private val capacity: Int = DEFAULT_CAPACITY,
    /** How often a timed step's seconds are rewritten. */
    private val tickMillis: Long = TICK_MILLIS,
) : JobLauncher {

    private val _jobs = MutableStateFlow<List<CreationJob>>(emptyList())
    override val jobs: StateFlow<List<CreationJob>> = _jobs.asStateFlow()

    private val running = ConcurrentHashMap<String, Job>()
    private val counter = AtomicLong()

    override fun launch(
        kind: String,
        title: String,
        steps: List<String>,
        work: suspend JobReporter.() -> String?,
    ): String {
        val id = "job-${counter.incrementAndGet()}"
        val queued = CreationJob(id, kind, title, JobStatus.QUEUED, steps.map { JobStep(it, JobStatus.QUEUED) }, startedAt = clock())
        _jobs.update { listOf(queued) + prune(it) }
        val job = scope.launch {
            change(id) { copy(status = JobStatus.RUNNING) }
            val reporter = Reporter(id, this)
            try {
                val summary = reporter.work()
                reporter.stopTicking()
                settle(id, JobStatus.DONE, summary ?: "Done.")
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: JobFailure) {
                reporter.stopTicking()
                settle(id, JobStatus.FAILED, failure.reason)
            } catch (failure: Exception) {
                reporter.stopTicking()
                settle(id, JobStatus.FAILED, failure.message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${failure::class.simpleName}).")
            }
        }
        running[id] = job
        // Settled here as well as in the body, because a job cancelled before it
        // ever started never runs its body at all.
        job.invokeOnCompletion { cause ->
            running.remove(id)
            if (cause != null) settle(id, JobStatus.CANCELLED, "Stopped.")
        }
        return id
    }

    override fun cancel(id: String) {
        running[id]?.cancel()
    }

    override fun dismiss(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && it.status.settled } }
    }

    /** Forgets every finished job at once. */
    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.status.settled } }
    }

    private fun change(id: String, edit: CreationJob.() -> CreationJob) {
        _jobs.update { list -> list.map { if (it.id == id && !it.status.settled) it.edit() else it } }
    }

    /**
     * Ends a job once. Whatever step was still running takes the job's ending;
     * planned steps it never reached are dropped when it succeeded, since they
     * turned out not to be needed, and shown as cancelled when it did not.
     */
    private fun settle(id: String, status: JobStatus, summary: String) {
        val now = clock()
        change(id) {
            val steps = steps.mapNotNull { step ->
                when {
                    step.status == JobStatus.RUNNING -> step.copy(status = if (status == JobStatus.DONE) JobStatus.DONE else status)
                    step.status == JobStatus.QUEUED && status == JobStatus.DONE -> null
                    step.status == JobStatus.QUEUED -> step.copy(status = JobStatus.CANCELLED)
                    else -> step
                }
            }
            copy(status = status, steps = steps, finishedAt = now, summary = summary)
        }
        _jobs.update(::prune)
    }

    /** Drops finished jobs older than the retention window, then the oldest past [capacity]. */
    private fun prune(list: List<CreationJob>): List<CreationJob> {
        val now = clock()
        val fresh = list.filter { job -> job.finishedAt == null || now - job.finishedAt <= retainMillis }
        var finished = fresh.count { it.status.settled }
        if (finished <= capacity) return fresh
        // Newest first, so the ones to drop are at the end.
        return fresh.asReversed().filter { job ->
            val drop = job.status.settled && finished > capacity
            if (drop) finished--
            !drop
        }.asReversed()
    }

    private inner class Reporter(override val jobId: String, private val jobScope: CoroutineScope) : JobReporter {

        private val lock = Any()
        private var current = -1
        private var ticker: Job? = null
        private var timedSince: Long? = null

        override fun begin(label: String, detail: String?) = synchronized(lock) {
            closeCurrent(JobStatus.DONE, null)
            change(jobId) {
                val planned = steps.withIndex().firstOrNull { (i, step) -> i > current && step.status == JobStatus.QUEUED && step.label == label }?.index
                if (planned != null) {
                    current = planned
                    copy(steps = steps.replace(planned) { it.copy(status = JobStatus.RUNNING, detail = detail) })
                } else {
                    // A new step goes after the last one that has started, ahead of the
                    // planned ones still waiting, so the timeline stays in the order it ran.
                    val at = (steps.indexOfLast { it.status != JobStatus.QUEUED } + 1).coerceAtLeast(current + 1)
                    current = at
                    copy(steps = steps.take(at) + JobStep(label, JobStatus.RUNNING, detail) + steps.drop(at))
                }
            }
        }

        override fun beginTimed(label: String) = synchronized(lock) {
            begin(label, formatElapsed(0))
            val step = current
            val since = clock()
            timedSince = since
            ticker = jobScope.launch {
                while (true) {
                    delay(tickMillis)
                    synchronized(lock) {
                        if (current == step && timedSince == since) setDetail(step, formatElapsed(clock() - since))
                    }
                }
            }
        }

        override fun detail(detail: String?) = synchronized(lock) {
            if (current >= 0) setDetail(current, detail)
        }

        override fun detailOf(label: String, detail: String?) = synchronized(lock) {
            val index = _jobs.value.firstOrNull { it.id == jobId }?.steps?.indexOfLast { it.label == label } ?: -1
            if (index >= 0) setDetail(index, detail)
        }

        override fun done(detail: String?) = synchronized(lock) { closeCurrent(JobStatus.DONE, detail) }

        override fun stepFailed(detail: String?) = synchronized(lock) { closeCurrent(JobStatus.FAILED, detail) }

        fun stopTicking() = synchronized(lock) {
            timedSince?.let { since -> if (current >= 0) setDetail(current, "took ${formatElapsed(clock() - since)}") }
            ticker?.cancel()
            ticker = null
            timedSince = null
        }

        /** Finishes the running step, if one is; a timed one keeps how long it took. */
        private fun closeCurrent(status: JobStatus, detail: String?) {
            if (current < 0) return
            val step = current
            val took = timedSince?.let { "took ${formatElapsed(clock() - it)}" }
            ticker?.cancel()
            ticker = null
            timedSince = null
            change(jobId) {
                copy(steps = steps.replace(step) { if (it.status == JobStatus.RUNNING) it.copy(status = status, detail = detail ?: took ?: it.detail) else it })
            }
        }

        private fun setDetail(index: Int, detail: String?) {
            change(jobId) { copy(steps = steps.replace(index) { it.copy(detail = detail) }) }
        }
    }

    companion object {
        const val DEFAULT_RETAIN_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_CAPACITY = 20
        const val TICK_MILLIS = 1000L
    }
}

private inline fun <T> List<T>.replace(index: Int, edit: (T) -> T): List<T> =
    if (index !in indices) this else mapIndexed { i, item -> if (i == index) edit(item) else item }
