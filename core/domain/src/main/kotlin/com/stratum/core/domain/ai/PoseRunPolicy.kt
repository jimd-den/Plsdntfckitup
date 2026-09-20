package com.stratum.core.domain.ai

/** What a run should do about a frame that did not come back. */
enum class RunDecision {
    /** Wait and ask again for the same frame. */
    RETRY,

    /** Give up on this frame, keep the run going, report it at the end. */
    SKIP,

    /** Stop. Every remaining frame would fail the same way. */
    ABANDON,
}

/** How a sequential run ended, for something to say afterwards. */
data class RunOutcome(
    val drawn: Int,
    val failed: List<String>,
    val abandonedBecause: String? = null,
) {
    val isComplete: Boolean get() = failed.isEmpty() && abandonedBecause == null
}

/**
 * The rules a long run follows when something goes wrong.
 *
 * Pure, and separate from the screen that runs it, because these are the
 * decisions worth being sure about and they are impossible to exercise through
 * a ViewModel: reproducing a rate limit on frame thirty-one of forty is not a
 * thing anyone tests by hand.
 *
 * The shape of the problem is what makes it worth writing down. Forty image
 * generations back to back is several minutes and several dollars, and a rate
 * limit partway through is not an edge case — it is the expected behaviour of
 * every provider under exactly this load. A run that treated one 429 as a lost
 * frame would routinely finish with holes in it for no reason at all.
 */
object PoseRunPolicy {

    /**
     * Four attempts, then move on.
     *
     * Enough to ride out a rate limit, which clears in seconds. Not so many
     * that a genuinely broken frame holds up the thirty-nine behind it — the
     * frame can be redrawn on its own afterwards, and a run that stalls is
     * worse than a run with one gap in it.
     */
    const val MAX_ATTEMPTS = 4

    /**
     * How long to wait before attempt [attempt], counting from 1.
     *
     * Doubling, because a provider that just said no to one request will say no
     * to the next one sent immediately, and the usual cause is a per-minute
     * budget that only time refills.
     */
    fun backoffMillis(attempt: Int): Long {
        val step = attempt.coerceAtLeast(1) - 1
        return (FIRST_WAIT_MS shl step.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_WAIT_MS)
    }

    /**
     * What to do after [failure] on [attempt] of a frame.
     *
     * A failure that will repeat identically for every remaining frame — a
     * rejected key, no credit, a model that does not exist — stops the run.
     * Grinding through another thirty-nine to prove it wastes four minutes to
     * learn nothing, and leaves the person watching a progress bar fill with
     * errors.
     */
    fun decide(attempt: Int, failure: Throwable): RunDecision {
        val generation = failure as? GenerationException
        if (generation?.fatal == true) return RunDecision.ABANDON
        val retryable = generation?.retryable ?: false
        return if (retryable && attempt < MAX_ATTEMPTS) RunDecision.RETRY else RunDecision.SKIP
    }

    /** Two seconds, doubling: 2, 4, 8, 16. */
    private const val FIRST_WAIT_MS = 2_000L
    private const val MAX_WAIT_MS = 30_000L
    private const val MAX_SHIFT = 4
}
