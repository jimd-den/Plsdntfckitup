package com.stratum.core.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoseRunPolicyTest {

    @Test
    fun `a rate limit is waited out, not lost`() {
        // The whole reason this exists: forty image requests in a row will meet
        // a rate limit, and a run that treated one as a lost frame would
        // routinely finish with holes in it for no reason.
        val limited = GenerationException("rate limited", retryable = true)
        assertEquals(RunDecision.RETRY, PoseRunPolicy.decide(attempt = 1, failure = limited))
        assertEquals(RunDecision.RETRY, PoseRunPolicy.decide(attempt = 3, failure = limited))
    }

    @Test
    fun `a frame that keeps failing is left behind rather than holding up the rest`() {
        val limited = GenerationException("rate limited", retryable = true)
        assertEquals(
            RunDecision.SKIP,
            PoseRunPolicy.decide(PoseRunPolicy.MAX_ATTEMPTS, limited),
        )
    }

    @Test
    fun `a rejected key stops the run instead of failing forty times`() {
        val fatal = GenerationException("key rejected", fatal = true)
        assertEquals(RunDecision.ABANDON, PoseRunPolicy.decide(attempt = 1, failure = fatal))
    }

    @Test
    fun `a frame's own problem is skipped without waiting`() {
        // A prompt this model will not draw is not going to become drawable in
        // two seconds.
        val refused = GenerationException("the model refused this one")
        assertEquals(RunDecision.SKIP, PoseRunPolicy.decide(attempt = 1, failure = refused))
    }

    @Test
    fun `an unexpected error is treated as this frame's problem, not the run's`() {
        // Anything that is not a GenerationException came from somewhere that
        // has no opinion about retrying, and abandoning forty frames over one
        // unknown is the wrong default.
        assertEquals(RunDecision.SKIP, PoseRunPolicy.decide(1, IllegalStateException("odd")))
    }

    @Test
    fun `waiting doubles and then stops growing`() {
        assertEquals(2_000L, PoseRunPolicy.backoffMillis(1))
        assertEquals(4_000L, PoseRunPolicy.backoffMillis(2))
        assertEquals(8_000L, PoseRunPolicy.backoffMillis(3))
        assertEquals(16_000L, PoseRunPolicy.backoffMillis(4))
        // A run that waited minutes between attempts would be indistinguishable
        // from one that had hung.
        assertTrue(PoseRunPolicy.backoffMillis(20) <= 30_000L)
    }

    @Test
    fun `an outcome knows whether it actually finished`() {
        assertTrue(RunOutcome(drawn = 24, failed = emptyList()).isComplete)
        assertFalse(RunOutcome(drawn = 23, failed = listOf("walk_2")).isComplete)
        assertFalse(
            RunOutcome(drawn = 4, failed = emptyList(), abandonedBecause = "key").isComplete,
        )
    }
}
