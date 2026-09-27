package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.FrameDefect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A whole run played in microseconds: every collaborator is a function. */
class PoseFrameRunTest {

    private val steps = PoseScript.enemy(mapOf(AnimationState.WALK to 4)).stepsFor(AnimationState.WALK)

    private fun image(tag: String) = GeneratedImage(tag.toByteArray(), "image/png", 8, 8)

    private class Harness(
        val answers: MutableMap<String, ArrayDeque<Result<GeneratedImage>>> = mutableMapOf(),
        val defects: (String) -> List<FrameDefect> = { emptyList() },
        val failSave: Set<String> = emptySet(),
    ) {
        val asked = mutableListOf<Pair<String, Int>>()
        val saved = mutableMapOf<String, String>()
        val slept = mutableListOf<Long>()
        val events = mutableListOf<PoseRunEvent>()

        fun run() = PoseFrameRun(
            draw = { step, take ->
                asked += step.key to take
                answers[step.key]?.removeFirstOrNull()
                    ?: Result.success(GeneratedImage("${step.key}#$take".toByteArray(), "image/png", 8, 8))
            },
            inspect = { _, bytes -> defects(String(bytes)) },
            save = { step, bytes ->
                if (step.key in failSave) error("disk full")
                saved[step.key] = String(bytes)
            },
            sleep = { slept += it },
            onEvent = { events += it },
        )
    }

    @Test
    fun `a clean run draws every frame once`() = runTest {
        val h = Harness()
        val outcome = h.run().run(steps)
        assertEquals(4, outcome.drawn)
        assertTrue(outcome.isComplete)
        assertEquals(steps.map { it.key to 0 }, h.asked)
    }

    @Test
    fun `a rate limit is waited out with the same take`() = runTest {
        val h = Harness(
            answers = mutableMapOf(
                "walk_1" to ArrayDeque(listOf(Result.failure(GenerationException("429", retryable = true)))),
            ),
        )
        val outcome = h.run().run(steps)
        assertTrue(outcome.isComplete)
        assertEquals(listOf(PoseRunPolicy.backoffMillis(1)), h.slept)
        assertEquals(listOf("walk_1" to 0, "walk_1" to 0), h.asked.filter { it.first == "walk_1" })
    }

    @Test
    fun `a fatal failure abandons the run where it is`() = runTest {
        val h = Harness(
            answers = mutableMapOf("walk_2" to ArrayDeque(listOf(Result.failure(GenerationException("bad key", fatal = true))))),
        )
        val outcome = h.run().run(steps)
        assertEquals("bad key", outcome.abandonedBecause)
        assertEquals(2, outcome.drawn)
        assertTrue("walk_3" !in h.saved)
    }

    @Test
    fun `a bad frame is redrawn with a fresh take, and the good one is kept`() = runTest {
        val h = Harness(defects = { if (it == "walk_0#0") listOf(FrameDefect.CLIPPED) else emptyList() })
        val outcome = h.run().run(steps)
        assertEquals("walk_0#1", h.saved["walk_0"])
        assertEquals(mapOf("walk_0" to 1), outcome.takes)
        assertTrue(outcome.flagged.isEmpty())
        assertTrue(h.events.any { it is PoseRunEvent.Rejected && it.step.key == "walk_0" })
    }

    @Test
    fun `a frame that stays bad is kept after the redraws and flagged`() = runTest {
        val h = Harness(defects = { if (it.startsWith("walk_3")) listOf(FrameDefect.EMPTY) else emptyList() })
        val outcome = h.run().run(steps)
        assertEquals(1 + PoseFrameRun.MAX_REDRAWS, h.asked.count { it.first == "walk_3" })
        assertEquals(listOf(FrameDefect.EMPTY), outcome.flagged["walk_3"])
        assertEquals(4, outcome.drawn)
        assertTrue(PoseFrameRun.summary(outcome, 4, 4).contains("walk_3 came back blank"))
    }

    @Test
    fun `takes from an earlier run carry on, so a thrown-away frame is not served again`() = runTest {
        val h = Harness()
        h.run().run(steps.take(1), takes = mapOf("walk_0" to 2))
        assertEquals(listOf("walk_0" to 2), h.asked)
    }

    @Test
    fun `a failed save is reported and the run goes on`() = runTest {
        val h = Harness(failSave = setOf("walk_1"))
        val outcome = h.run().run(steps)
        assertEquals(listOf("walk_1"), outcome.failed)
        assertTrue(outcome.reasons.getValue("walk_1").contains("disk full"))
        assertEquals(3, outcome.drawn)
        assertNull(outcome.abandonedBecause)
    }

    @Test
    fun `a step knows the length of its own animation`() {
        val script = PoseScript.full(mapOf(AnimationState.WALK to 12))
        assertTrue(script.stepsFor(AnimationState.WALK).all { it.frameCount == 12 })
        assertTrue(script.stepsFor(AnimationState.IDLE).all { it.frameCount == PoseScript.DEFAULT_FRAMES })
    }
}
