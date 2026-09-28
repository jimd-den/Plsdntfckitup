package com.stratum.core.domain.creation

import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage

/**
 * Turns what a provider adapter reports into a job's steps, so every AI call
 * shows its real sub-steps without a port changing shape.
 *
 * The adapters already report stages to a [GenerationObserver]; this is one
 * more observer, and it writes: "Writing the request", "Waiting for <model>"
 * with the seconds counting, "Reading the reply", and then what came back or
 * the provider's own reason it did not. A second call on the same job (a retry)
 * says so in its labels rather than rewriting the first call's steps.
 *
 * [model] names the model before the provider does; the provider's own name for
 * it, from the attempt, replaces it once one has come back.
 */
class JobGenerationObserver(
    private val job: JobReporter,
    model: String? = null,
    /** Another observer that still wants the stages, such as a screen's own progress bar. */
    private val also: GenerationObserver = GenerationObserver.None,
) : GenerationObserver {

    private val lock = Any()
    private var model: String? = model?.takeIf { it.isNotBlank() }
    private var calls = 0
    private var waitingLabel: String? = null

    override fun onStage(stage: GenerationStage) {
        synchronized(lock) { report(stage) }
        also.onStage(stage)
    }

    override fun onAttempt(attempt: GenerationAttempt) {
        synchronized(lock) {
            if (attempt.model.isNotBlank()) model = attempt.model
            val label = waitingLabel ?: return@synchronized
            val took = formatElapsed(attempt.durationMillis)
            job.detailOf(
                label,
                when {
                    attempt.succeeded -> "answered in $took"
                    attempt.status != null -> "HTTP ${attempt.status}: ${attempt.failure ?: "rejected"}"
                    else -> "no reply: ${attempt.failure ?: "the connection failed"}"
                },
            )
        }
        also.onAttempt(attempt)
    }

    private fun report(stage: GenerationStage) {
        when (stage) {
            GenerationStage.PREPARING -> {
                calls++
                waitingLabel = null
                job.begin(if (calls > 1) "Writing the request again (try $calls)" else "Writing the request")
            }
            // Adapters differ on whether they report SENDING, WAITING or both; either
            // one means the request has left and the wait has started.
            GenerationStage.SENDING, GenerationStage.WAITING -> if (waitingLabel == null) {
                val label = "Waiting for ${model ?: "the model"}"
                waitingLabel = label
                job.beginTimed(label)
            }
            GenerationStage.READING -> job.begin("Reading the reply")
            GenerationStage.DECODING -> job.begin("Decoding the image")
            GenerationStage.MEASURING -> job.detail("measuring the sheet")
            GenerationStage.SAVING -> job.begin("Saving")
            GenerationStage.DONE -> job.done()
            GenerationStage.FAILED -> job.stepFailed()
        }
    }
}

/** This reporter as a [GenerationObserver], for handing to a port. */
fun JobReporter.observer(model: String? = null, also: GenerationObserver = GenerationObserver.None): GenerationObserver =
    JobGenerationObserver(this, model, also)
