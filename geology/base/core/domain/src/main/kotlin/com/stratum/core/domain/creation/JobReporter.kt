package com.stratum.core.domain.creation

/**
 * How running work tells its job what it is doing.
 *
 * Steps are begun rather than set: beginning a step finishes whichever one was
 * running, so work written as a straight line of `begin` calls produces a
 * timeline without any bookkeeping. Every method is safe to call from any
 * thread, because the adapters that report through it answer on their own.
 */
interface JobReporter {
    val jobId: String

    /**
     * Starts the step called [label]. A planned step of that name still queued
     * ahead is the one started; otherwise a new step is added, so a second
     * attempt shows as a second step instead of rewriting the first.
     */
    fun begin(label: String, detail: String? = null)

    /**
     * Starts a step whose detail is the time spent in it, rewritten every tick
     * while it runs ("12s") and settled to "took 14s" when the next step begins.
     * For the one wait nobody can shorten: the model thinking.
     */
    fun beginTimed(label: String)

    /** Rewrites the running step's detail line. */
    fun detail(detail: String?)

    /** Rewrites the detail of the most recent step called [label], running or not. */
    fun detailOf(label: String, detail: String?)

    /** Finishes the running step, with [detail] when there is something to say about it. */
    fun done(detail: String? = null)

    /**
     * Marks the running step as failed without ending the job, for work that
     * recovers -- a reply rejected and asked for again.
     */
    fun stepFailed(detail: String? = null)

    /** Ends the job as failed, in words a person can act on. */
    fun fail(reason: String): Nothing = throw JobFailure(reason)
}

/** Seconds and minutes, the way a timer on a screen shows them: "8s", "1m 05s". */
fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
}
