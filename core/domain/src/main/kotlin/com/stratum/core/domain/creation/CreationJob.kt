package com.stratum.core.domain.creation

import kotlinx.coroutines.flow.StateFlow

/** Where a job, or one step of it, stands. */
enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** True once nothing more will happen to it. */
val JobStatus.settled: Boolean get() = this == JobStatus.DONE || this == JobStatus.FAILED || this == JobStatus.CANCELLED

/**
 * One thing a job does, in the words a player would use for it: "Writing the
 * request", "Waiting for the model", "Repairing 2 fields".
 *
 * [detail] is the live line under it -- seconds waited, what came back, why it
 * was rejected -- and is rewritten as the step goes, rather than appended, so a
 * step never grows into a log. The log is the journal; this is the dashboard.
 */
data class JobStep(val label: String, val status: JobStatus, val detail: String? = null)

/**
 * A piece of creation running in the background: lore being written, a world's
 * pack being drafted by the crew, a hero's look being painted.
 *
 * Jobs exist so nothing the AI does is a spinner. Every creator hands its work
 * to the [JobCenter] and gets its screen back; the job says what it is doing
 * now, how long it has taken and, when it settles, what came back or why it did
 * not, in one plain line.
 */
data class CreationJob(
    val id: String,
    /** What sort of thing is being made: "lore", "world", "hero-art", ... */
    val kind: String,
    val title: String,
    val status: JobStatus,
    val steps: List<JobStep>,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** One line: what came back or why it failed. */
    val summary: String? = null,
) {
    /** How long it has run, or ran, at [now]. */
    fun elapsedMillis(now: Long): Long = ((finishedAt ?: now) - startedAt).coerceAtLeast(0)

    /** The step being worked on, or the last one to have finished. */
    val currentStep: JobStep?
        get() = steps.lastOrNull { it.status == JobStatus.RUNNING } ?: steps.lastOrNull { it.status != JobStatus.QUEUED }

    val isActive: Boolean get() = !status.settled
}

/**
 * Every creation job the app is running or recently ran, newest first.
 *
 * One app-wide centre rather than one per screen, so a job outlives the screen
 * that started it and the jobs tray can show all of them from anywhere.
 */
interface JobCenter {
    val jobs: StateFlow<List<CreationJob>>
    fun cancel(id: String)
}

/**
 * The centre as the creators see it: somewhere to start work, not just watch it.
 * Kept apart from [JobCenter] so a screen that only shows jobs cannot start one.
 */
interface JobLauncher : JobCenter {
    /**
     * Starts [work] as a job and returns its id at once.
     *
     * [steps] are the labels the work expects to go through, shown as queued so
     * the player sees the whole road before the first step starts; work can
     * still add steps it did not plan, such as a retry. Whatever [work] returns
     * is the job's summary. Throwing [JobFailure] fails the job with its reason;
     * cancelling the job cancels [work].
     */
    fun launch(
        kind: String,
        title: String,
        steps: List<String> = emptyList(),
        work: suspend JobReporter.() -> String?,
    ): String

    /** Forgets a finished job; a running one must be cancelled first. */
    fun dismiss(id: String)
}

/** Ends a job as failed, with [reason] as the summary a person reads. */
class JobFailure(val reason: String) : Exception(reason)
