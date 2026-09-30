package com.stratum.feature.forge

import com.stratum.core.domain.creation.JobLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A generation run, owned by the application rather than by the screen.
 *
 * Forty-two frames is a quarter of an hour of waiting, and a person does not
 * spend that staring at a progress bar -- they answer a message and come back.
 * While this ran on the screen's own scope, that lost the run: leaving the
 * forge cleared the view model, clearing the view model cancelled the job, and
 * the frames already paid for stayed on disk while the rest were simply never
 * asked for. Nothing said so, because from the screen's point of view nothing
 * had gone wrong.
 *
 * Only the *lifetime* moves here. The loop itself stays in the view model
 * where it can be read next to the state it reports, which is also what keeps
 * this object small enough to be obviously correct: it starts one job, it
 * cancels one job, and it says whether one is running.
 *
 * One run at a time, deliberately. Two characters generating at once would
 * race for the same rate limit and make each other's backoff meaningless, and
 * the second would silently halve the first's throughput with no way to see
 * why.
 */
object PoseRun {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var job: Job? = null

    private val _running = MutableStateFlow(false)

    /**
     * Whether a run is in flight.
     *
     * Watched by the foreground service, which is what stops the system
     * reclaiming the process mid-run. A flow rather than a callback because
     * the service and the screen both need it and neither owns the other.
     */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** What is being drawn, for a notification that has to say something useful. */
    private val _progress = MutableStateFlow(RunProgress())
    val progress: StateFlow<RunProgress> = _progress.asStateFlow()

    /**
     * Where each run also shows as a creation job, so a quarter of an hour of
     * frames is visible in the tray from anywhere. Set once by the app; the run
     * itself keeps its own lifetime here, and the job only mirrors it.
     */
    @Volatile
    var jobs: JobLauncher? = null

    private var mirror: String? = null

    fun start(block: suspend CoroutineScope.() -> Unit): Job {
        // Replacing rather than refusing: the screen already guards against a
        // second start, and if one slips through, the newer intent is the one
        // the person just expressed.
        job?.cancel()
        _running.value = true
        val started = scope.launch {
            try {
                block()
            } finally {
                _running.value = false
                _progress.value = RunProgress()
            }
        }
        job = started
        mirrorAsJob(started)
        return started
    }

    /** A job that follows [run]: its frames as the step's detail, its ending as the job's. */
    private fun mirrorAsJob(run: Job) {
        val launcher = jobs ?: return
        mirror?.let(launcher::cancel)
        mirror = launcher.launch(kind = "hero-art", title = "Drawing a character's poses", steps = listOf(DRAWING)) {
            begin(DRAWING)
            try {
                coroutineScope {
                    val follow = launch {
                        progress.collect { p -> if (p.hasWork) detail("${p.label} · ${p.done} of ${p.total} frames") }
                    }
                    run.join()
                    follow.cancel()
                }
            } catch (stopped: CancellationException) {
                // Stopping the job from the tray stops the run it stands for.
                if (job === run) stop()
                throw stopped
            }
            if (run.isCancelled) fail("The run ended early. The frames already drawn are kept, and a new run resumes from them.")
            "Frames drawn. Pack them into a sheet in the pose forge."
        }
    }

    fun report(label: String, done: Int, total: Int) {
        _progress.value = RunProgress(label = label, done = done, total = total)
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        _progress.value = RunProgress()
    }
}

private const val DRAWING = "Drawing frames"

/** Where a run has got to, in the words a notification would use. */
data class RunProgress(
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
) {
    val hasWork: Boolean get() = total > 0
}
