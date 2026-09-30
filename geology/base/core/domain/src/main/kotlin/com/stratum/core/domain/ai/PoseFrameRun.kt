package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.FrameDefect
import com.stratum.core.domain.sprite.FrameQuality
import kotlinx.coroutines.CancellationException

/** What a frame-by-frame run is doing, for a screen or a notification to show. */
sealed interface PoseRunEvent {
    val step: PoseStep

    /** Asking for [step]; [attempt] counts transport retries, [take] counts deliberate redraws. */
    data class Drawing(override val step: PoseStep, val attempt: Int, val take: Int) : PoseRunEvent

    /** Riding out a rate limit before asking again. */
    data class Waiting(override val step: PoseStep, val millis: Long, val reason: String) : PoseRunEvent

    /** The frame came back unusable and is being asked for again. */
    data class Rejected(override val step: PoseStep, val defects: List<FrameDefect>, val take: Int) : PoseRunEvent

    /** Written to disk. [flagged] is non-empty when it was kept despite its defects. */
    data class Saved(override val step: PoseStep, val flagged: List<FrameDefect> = emptyList()) : PoseRunEvent

    /** Given up on for this run; the rest carries on. */
    data class Failed(override val step: PoseStep, val reason: String) : PoseRunEvent
}

/**
 * The loop that draws a character one pose at a time.
 *
 * It lived in the forge's view model, which made it the least tested and most
 * consequential code in the pipeline: a rate limit on frame thirty-one, a
 * rejected key on frame two, a blank frame on frame nine — each a different
 * decision, none of them reproducible by hand. Here every collaborator is a
 * function, including the wait, so a test can play a whole run in
 * microseconds and check what was asked for, what was kept and what was said.
 *
 * Three kinds of failure, three answers:
 * - the transport failed ([PoseRunPolicy] decides): wait and ask again with the
 *   same take, skip the frame, or abandon the run;
 * - the frame arrived but is unusable ([FrameQuality] decides): ask again with
 *   the next take, up to [MAX_REDRAWS] times, and then keep the last one
 *   anyway, flagged — a flawed frame in its cell is still better than a hole,
 *   and the person is told which to redraw;
 * - the save failed: the frame is lost for this run and reported, and because
 *   the image model is cached, asking for it again next run costs nothing.
 */
class PoseFrameRun(
    private val draw: suspend (step: PoseStep, take: Int) -> Result<GeneratedImage>,
    /** Defects in a frame as drawn; empty for a good one, or when it cannot be judged. */
    private val inspect: (step: PoseStep, bytes: ByteArray) -> List<FrameDefect>,
    private val save: suspend (step: PoseStep, bytes: ByteArray) -> Unit,
    private val sleep: suspend (millis: Long) -> Unit,
    private val onEvent: (PoseRunEvent) -> Unit = {},
) {

    /**
     * Draws every step in [todo], in order.
     *
     * @param takes redraws already spent on each key in earlier runs, so a
     *   frame the person threw away is not answered from the cache with the
     *   very picture they threw away.
     */
    suspend fun run(todo: List<PoseStep>, takes: Map<String, Int> = emptyMap()): RunOutcome {
        var drawn = 0
        val failed = mutableListOf<String>()
        val reasons = LinkedHashMap<String, String>()
        val flagged = LinkedHashMap<String, List<FrameDefect>>()
        val usedTakes = LinkedHashMap<String, Int>()

        steps@ for (step in todo) {
            var take = takes[step.key] ?: 0
            var attempt = 1
            var redraws = 0
            while (true) {
                onEvent(PoseRunEvent.Drawing(step, attempt, take))
                val result = try {
                    draw(step, take)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Throwable) {
                    Result.failure(cause)
                }

                val image = result.getOrNull()
                if (image == null) {
                    val cause = result.exceptionOrNull() ?: GenerationException("failed")
                    when (PoseRunPolicy.decide(attempt, cause)) {
                        RunDecision.RETRY -> {
                            val wait = PoseRunPolicy.backoffMillis(attempt)
                            onEvent(PoseRunEvent.Waiting(step, wait, cause.message.orEmpty()))
                            sleep(wait)
                            attempt++
                        }
                        RunDecision.SKIP -> {
                            val reason = cause.message ?: "failed"
                            failed += step.key
                            reasons[step.key] = reason
                            onEvent(PoseRunEvent.Failed(step, reason))
                            continue@steps
                        }
                        RunDecision.ABANDON -> {
                            return RunOutcome(
                                drawn = drawn,
                                failed = failed,
                                abandonedBecause = cause.message ?: "The provider refused the run.",
                                reasons = reasons,
                                flagged = flagged,
                                takes = usedTakes,
                            )
                        }
                    }
                    continue
                }

                val defects = runCatching { inspect(step, image.bytes) }.getOrDefault(emptyList())
                if (defects.isNotEmpty() && redraws < MAX_REDRAWS) {
                    redraws++
                    take++
                    attempt = 1
                    onEvent(PoseRunEvent.Rejected(step, defects, take))
                    continue
                }

                val saved = try {
                    save(step, image.bytes)
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Throwable) {
                    val reason = "could not be saved: ${cause.message ?: cause::class.simpleName}"
                    failed += step.key
                    reasons[step.key] = reason
                    onEvent(PoseRunEvent.Failed(step, reason))
                    false
                }
                if (take > 0) usedTakes[step.key] = take
                if (saved) {
                    drawn++
                    if (defects.isNotEmpty()) flagged[step.key] = defects
                    onEvent(PoseRunEvent.Saved(step, defects))
                }
                continue@steps
            }
        }
        return RunOutcome(drawn, failed, null, reasons, flagged, usedTakes)
    }

    companion object {
        /**
         * Two more tries at a frame that came back unusable.
         *
         * A blank or cropped frame is usually a one-off, and the second try is
         * usually fine. A frame that fails three times in a row is failing for a
         * reason another generation will not fix — a pose the model cannot
         * draw — and spending more on it holds up the frames behind it.
         */
        const val MAX_REDRAWS = 2

        /** The sentence a run ends on, in the words a person needs. */
        fun summary(outcome: RunOutcome, done: Int, total: Int): String = buildString {
            when {
                outcome.abandonedBecause != null ->
                    append("Stopped after $done of $total. Nothing else would have worked either.")
                outcome.failed.isEmpty() -> append("Every pose drawn.")
                else -> {
                    val n = outcome.failed.size
                    append("$n pose${if (n == 1) "" else "s"} failed. Run it again to retry just those.")
                }
            }
            if (outcome.flagged.isNotEmpty()) {
                append(" Kept but worth redrawing: ")
                append(outcome.flagged.entries.joinToString("; ") { (key, defects) -> FrameQuality.describe(key, defects) })
                append('.')
            }
        }
    }
}
