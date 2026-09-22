package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.ClipCut
import com.stratum.core.domain.sprite.ClipSampling
import com.stratum.core.domain.sprite.PoseCell
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateClipRowUseCaseTest {

    /** Frame zero of the animation, drawn under its guide -- not the T-pose. */
    private val drawing = ImageReference("the walk's first pose".toByteArray())

    private class FakeVideoModel(
        private val result: Result<GeneratedClip> = Result.success(
            GeneratedClip("clip".toByteArray(), "video/mp4", durationMillis = 4000L),
        ),
    ) : VideoModelPort {
        var sent: ClipRequest? = null

        override suspend fun generateClip(
            request: ClipRequest,
            observer: GenerationObserver,
        ): Result<GeneratedClip> {
            sent = request
            return result
        }
    }

    /** Every cut decodes, which is the ordinary case. */
    private fun everyFrame(): (ByteArray, List<ClipCut>) -> Map<Int, ByteArray> =
        { _, cuts -> cuts.associate { it.index to "frame ${it.index}".toByteArray() } }

    private fun requestFor(state: AnimationState) = ClipRowRequest(
        state = state,
        motion = "a steady walk cycle",
        openingPose = drawing,
    )

    /**
     * A frame out of a clip is keyed as a frame drawn on its own.
     *
     * Which is the whole reason this path can exist beside the other one. The
     * pipeline saves, keys, anchors and packs by that key, so a row can be half
     * cut frames and half stills, a set can be started one way and finished the
     * other, and a frame that came back wrong can be redrawn as a still and
     * dropped back in. A key of its own would have made it a second pipeline.
     */
    @Test
    fun `cut frames are keyed exactly as drawn ones are`() = runTest {
        val row = GenerateClipRowUseCase(FakeVideoModel(), everyFrame())
            .invoke(requestFor(AnimationState.WALK))
            .getOrThrow()

        assertTrue(row.frames.isNotEmpty())
        row.frames.keys.forEachIndexed { index, key ->
            assertEquals(PoseCell.keyOf(AnimationState.WALK, index), key)
        }
    }

    /**
     * A cycle is pinned to the same drawing at both ends.
     *
     * A movement that returns to where it started, which is a closed loop --
     * and it is the one thing separately drawn stills cannot produce, because
     * nothing joins the last frame to the first except the hope that the two
     * poses happen to meet.
     */
    @Test
    fun `a looping animation starts and ends on the same drawing`() = runTest {
        val model = FakeVideoModel()

        GenerateClipRowUseCase(model, everyFrame()).invoke(requestFor(AnimationState.WALK))

        val sent = assertNotNull(model.sent)
        assertEquals(drawing, sent.firstFrame)
        assertEquals(drawing, sent.lastFrame, "the walk was not asked to come back round")
    }

    /**
     * A death is not, because it does not end where it began.
     *
     * Pinning its last frame to its first would ask for a corpse that stands
     * back up.
     */
    @Test
    fun `a one-shot is not asked to return to its first frame`() = runTest {
        val model = FakeVideoModel()

        GenerateClipRowUseCase(model, everyFrame()).invoke(requestFor(AnimationState.DIE))

        assertNull(assertNotNull(model.sent).lastFrame)
    }

    /** Sound is charged for at double and thrown away, so it is never asked for. */
    @Test
    fun `a clip is never asked for with audio`() = runTest {
        val model = FakeVideoModel()

        GenerateClipRowUseCase(model, everyFrame()).invoke(requestFor(AnimationState.WALK))

        assertEquals(false, assertNotNull(model.sent).generateAudio)
    }

    /**
     * The rate decides how many frames come out.
     *
     * Rather than a count chosen somewhere else, which is what makes the same
     * clip re-cuttable into a different sheet for nothing.
     */
    @Test
    fun `the frame rate decides how many frames the row has`() = runTest {
        val useCase = GenerateClipRowUseCase(FakeVideoModel(), everyFrame())

        val slow = useCase.invoke(requestFor(AnimationState.WALK).copy(fps = 6)).getOrThrow()
        val fast = useCase.invoke(requestFor(AnimationState.WALK).copy(fps = 24)).getOrThrow()

        assertTrue(
            fast.frameCount > slow.frameCount,
            "24fps gave ${fast.frameCount} frames against ${slow.frameCount} at 6fps",
        )
    }

    /**
     * A frame the decoder could not read is reported rather than swallowed.
     *
     * A hole in the middle of a row looks exactly like a frame the character is
     * invisible for, which is the one failure nobody can diagnose from watching
     * it play. Saying which cuts are missing is what lets them be redrawn.
     */
    @Test
    fun `cuts that could not be decoded are named`() = runTest {
        val dropsTheThird: (ByteArray, List<ClipCut>) -> Map<Int, ByteArray> = { _, cuts ->
            cuts.filterNot { it.index == 2 }
                .associate { it.index to "frame".toByteArray() }
        }

        val row = GenerateClipRowUseCase(FakeVideoModel(), dropsTheThird)
            .invoke(requestFor(AnimationState.WALK))
            .getOrThrow()

        assertEquals(listOf(2), row.missing)
    }

    /**
     * The clip comes back with the row.
     *
     * It is the expensive artefact -- about ten times a still, holding far more
     * than the row takes out of it -- so the caller keeps it and re-cuts for
     * nothing rather than paying again to change the rate.
     */
    @Test
    fun `the clip is handed back so it can be re-cut`() = runTest {
        val row = GenerateClipRowUseCase(FakeVideoModel(), everyFrame())
            .invoke(requestFor(AnimationState.WALK))
            .getOrThrow()

        assertEquals("clip", String(row.clip.bytes))
    }

    /** A clip that yields nothing is a failure, not an empty row saved over good frames. */
    @Test
    fun `a clip nothing can be read out of fails`() = runTest {
        val result = GenerateClipRowUseCase(FakeVideoModel()) { _, _ -> emptyMap() }
            .invoke(requestFor(AnimationState.WALK))

        assertTrue(result.isFailure)
    }

    @Test
    fun `a provider failure is passed through`() = runTest {
        val model = FakeVideoModel(Result.failure(GenerationException("no credit", fatal = true)))

        val result = GenerateClipRowUseCase(model, everyFrame()).invoke(requestFor(AnimationState.WALK))

        assertTrue(result.isFailure)
        assertEquals("no credit", result.exceptionOrNull()?.message)
    }

    /**
     * Every cut stays inside the part of the clip that holds its facing.
     *
     * Measured: a four second walk turns to show its back about eight tenths of
     * a second in, because nothing sells a shorter clip and the model fills the
     * rest by inventing. Cutting across the whole thing gives a row that is
     * mostly a character turning round.
     */
    @Test
    fun `no frame is cut from after the character starts turning`() = runTest {
        var seen: List<ClipCut> = emptyList()
        val capture: (ByteArray, List<ClipCut>) -> Map<Int, ByteArray> = { _, cuts ->
            seen = cuts
            cuts.associate { it.index to "frame".toByteArray() }
        }

        GenerateClipRowUseCase(FakeVideoModel(), capture).invoke(requestFor(AnimationState.WALK))

        assertTrue(seen.isNotEmpty())
        assertTrue(
            seen.all { it.fraction <= ClipSampling.USABLE_FRACTION },
            "a cut landed past the turn: ${seen.map { it.fraction }}",
        )
    }
}
