package com.stratum.core.domain.sprite

import com.stratum.core.domain.ai.ClipRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipSamplingTest {

    /**
     * A cycle's frames are spread over the hand-back too.
     *
     * The bug this is the shape of has already been paid for twice. Six poses
     * stretched across twelve frames as an open span put eleven intervals where
     * a walk has twelve, and the missing one was the step from the last frame
     * round to the first -- so the stride hitched once per loop. Frames cut out
     * of a clip are the same frames and lose it the same way: cut a two second
     * walk at 0, 1/11, ... 11/11 and the last frame is the first frame, held.
     */
    @Test
    fun `a looping clip leaves room for the step back to its first frame`() {
        val cuts = ClipSampling.cutsFor(AnimationState.WALK, 12)

        assertEquals(12, cuts.size)
        assertEquals(0f, cuts.first().fraction)
        assertTrue(
            cuts.last().fraction < 1f,
            "the last frame of a cycle sits at the end of the clip, so it repeats the first",
        )
        // Evenly spaced, with the gap back round to the start the same as the rest.
        val gaps = cuts.zipWithNext { a, b -> b.fraction - a.fraction } + (1f - cuts.last().fraction)
        gaps.forEach { assertEquals(gaps.first(), it, 1e-5f, "uneven spacing: $gaps") }
    }

    /**
     * A clip that does not loop runs to its final instant.
     *
     * The opposite requirement, and the reason this is not one rule. A death
     * has to reach the body being still; stopping a frame short of the end
     * leaves the corpse mid-fall, which is the one pose a death animation must
     * not finish on.
     */
    @Test
    fun `a one-shot clip runs all the way to its end`() {
        val cuts = ClipSampling.cutsFor(AnimationState.DIE, 12)

        assertEquals(0f, cuts.first().fraction)
        assertEquals(1f, cuts.last().fraction, 1e-5f)
    }

    /**
     * A frame cut from a clip is the frame a still would have been.
     *
     * This is what lets the two paths mix, and mixing them is the point: the
     * authored poses are drawn as stills under a guide, because a guide is the
     * only thing that makes them *these* poses, and only the gaps between them
     * are filled from video. That only works if frame seven means the same
     * moment whichever way it was made -- otherwise a set started one way and
     * finished the other has two different animations in one row, and a frame
     * redrawn as a still lands somewhere the clip did not put it.
     */
    @Test
    fun `a segment cut lands exactly where the drawn pose would`() {
        AnimationState.entries.forEach { state ->
            val keys = MocapPoses.framesFor(state).size
            listOf(4, 6, 12).forEach { count ->
                ClipSampling.segmentCutsFor(state, count).forEach { cut ->
                    val asPose = cut.index.toFloat() * keys / count
                    assertEquals(
                        asPose,
                        cut.fromKey + cut.fraction,
                        1e-4f,
                        "$state frame ${cut.index} of $count is cut at a different moment " +
                            "than MocapPoses would pose it",
                    )
                }
            }
        }
    }

    /**
     * At the authored length every frame is an authored pose.
     *
     * So the video path costs nothing and generates nothing at six frames, and
     * the row is the still pipeline unchanged. A path that quietly paid for six
     * clips to reproduce six stills it already had would be worse than not
     * having it.
     */
    @Test
    fun `at the authored length there is nothing for a clip to fill`() {
        AnimationState.entries.forEach { state ->
            val keys = MocapPoses.framesFor(state).size
            val cuts = ClipSampling.segmentCutsFor(state, keys)

            assertTrue(cuts.all { it.isKeyframe }, "$state wanted clips at its authored length")
            assertTrue(
                ClipSampling.segmentsNeeded(cuts).isEmpty(),
                "$state would generate clips it has no frames for",
            )
        }
    }

    /**
     * A cycle's last segment closes the loop; a one-shot's holds its end.
     *
     * The segment from the last authored pose back to the first is the loop,
     * and it is the frame nothing else can supply -- a whole-animation clip
     * cannot be asked to end where it began. Asking a death for the same
     * segment would interpolate a corpse back to standing.
     */
    @Test
    fun `only a cycle is asked for the segment back to its first pose`() {
        val keys = MocapPoses.framesFor(AnimationState.WALK).size
        val walk = ClipSampling.segmentCutsFor(AnimationState.WALK, 12)
        assertTrue(
            walk.any { it.fromKey == keys - 1 && it.toKey == 0 },
            "the walk never closes its loop: ${walk.map { it.fromKey to it.toKey }}",
        )

        val die = ClipSampling.segmentCutsFor(AnimationState.DIE, 12)
        assertTrue(
            die.none { it.toKey < it.fromKey },
            "the death interpolates back towards standing: ${die.map { it.fromKey to it.toKey }}",
        )
    }

    /** Twelve frames from six poses is one in-between per beat, not a dozen clips. */
    @Test
    fun `doubling the frames asks each segment for one in-between`() {
        val needed = ClipSampling.segmentsNeeded(ClipSampling.segmentCutsFor(AnimationState.WALK, 12))

        assertEquals(MocapPoses.framesFor(AnimationState.WALK).size, needed.size)
        needed.forEach { (segment, frames) ->
            assertEquals(1, frames, "segment $segment owes $frames frames")
        }
    }

    @Test
    fun `nothing is asked for when nothing is wanted`() {
        assertTrue(ClipSampling.cutsFor(0, cycle = true).isEmpty())
        assertTrue(ClipSampling.segmentCutsFor(0, keys = 6, cycle = true).isEmpty())
        assertEquals(listOf(ClipCut(0, 0f)), ClipSampling.cutsFor(1, cycle = true))
    }
}

class ClipSeekTest {

    /**
     * The last frame of a one-shot is pulled a hair inside the clip.
     *
     * Seeking to the final instant lands past the last frame on some decoders
     * and comes back with nothing. The frame that would lose is the one a
     * death animation most needs -- the body finally still -- and it would
     * lose silently, as a hole in the middle of a row that looks exactly like
     * a frame the character is invisible for.
     */
    @Test
    fun `a cut at the end of a clip seeks just inside it`() {
        val end = ClipSampling.cutsFor(AnimationState.DIE, 12).last()

        assertEquals(1f, end.fraction, 1e-5f)
        assertEquals(1999L, ClipSampling.millisFor(end, 2000L))
    }

    @Test
    fun `cuts land evenly through the clip`() {
        val cuts = ClipSampling.cutsFor(AnimationState.WALK, 4)

        assertEquals(
            listOf(0L, 500L, 1000L, 1500L),
            cuts.map { ClipSampling.millisFor(it, 2000L) },
        )
    }

    /** A clip whose container would not say how long it is cannot be cut at all. */
    @Test
    fun `a clip with no duration seeks nowhere`() {
        val cuts = ClipSampling.cutsFor(AnimationState.WALK, 4)

        assertTrue(cuts.all { ClipSampling.millisFor(it, 0L) == 0L })
    }
}

class ClipRequestTest {

    /**
     * A clip cannot be bought shorter than four seconds.
     *
     * Which is the fact that decides how this path is worth using at all. The
     * first version of this asked for one second, reasoning that a segment
     * between two authored poses is a fraction of a second of animation and
     * anything longer is the model inventing what happens next. Both halves
     * were right and the number was not purchasable: every video model in the
     * catalogue that publishes a duration set starts at four, and a request
     * below it is rejected rather than rounded up.
     */
    @Test
    fun `a clip is never asked for shorter than a provider will sell`() {
        assertEquals(4, ClipRequest.MIN_SECONDS)
        assertEquals(
            ClipRequest.MIN_SECONDS,
            ClipRequest(prompt = "walk", seconds = 1).durationSeconds,
        )
        assertEquals(
            ClipRequest.MAX_SECONDS,
            ClipRequest(prompt = "walk", seconds = 99).durationSeconds,
        )
        assertEquals(6, ClipRequest(prompt = "walk", seconds = 6).durationSeconds)
    }

    /** Sound is charged for and discarded, and leaving it on doubles the rate. */
    @Test
    fun `a clip is asked for without audio`() {
        assertFalse(ClipRequest(prompt = "walk").generateAudio)
    }
}
