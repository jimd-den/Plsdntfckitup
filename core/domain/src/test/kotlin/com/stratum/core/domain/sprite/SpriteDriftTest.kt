package com.stratum.core.domain.sprite

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteDriftTest {

    /**
     * The heights a real character came back at.
     *
     * Twelve walk frames, each generated on its own under its own stick
     * figure, measured after keying. They span 1713 to 1995 -- fifteen per
     * cent of the median -- and a walk bobs about three. The rest is the model
     * drawing the same character a different size each time it was asked.
     */
    private val measuredWalk = listOf(
        1871f, 1995f, 1912f, 1943f, 1967f, 1878f,
        1934f, 1837f, 1787f, 1713f, 1746f, 1902f,
    )

    private fun spreadOf(values: List<Float>): Float {
        val sorted = values.sorted()
        return (sorted.last() - sorted.first()) / sorted[sorted.size / 2]
    }

    /**
     * A corrected row is as tall, frame by frame, as its poses say it should be.
     *
     * Which is the actual contract, and it is stricter than "less uneven". The
     * drift that gets removed is all of it; what is left over is not residue,
     * it is the walk genuinely rising and falling -- authored at eight and a
     * half per cent, which is the body dropping at the down and coming up
     * through the passing. A row corrected flat would have lost that.
     *
     * Written this way after the looser version gave a number that looked like
     * a half-finished job: 14.8 per cent to 8.5. The 8.5 turned out to be the
     * authored spread exactly, so nothing was left to fix.
     */
    @Test
    fun `a corrected row follows the height its poses were drawn at`() {
        val authored = SpriteDrift.authoredHeightsFor(AnimationState.WALK, measuredWalk.size)
        val corrections = SpriteDrift.correctionsFor(AnimationState.WALK, measuredWalk)
        val corrected = measuredWalk.mapIndexed { at, height -> height * corrections[at] }

        assertTrue(spreadOf(measuredWalk) > 0.14f, "the sample should carry the drift it came with")

        // Every frame the same fraction of its row's middle as its pose is of
        // the poses' middle. The units never have to match; the ratios do.
        val authoredMid = authored.sorted()[authored.size / 2]
        val correctedMid = corrected.sorted()[corrected.size / 2]
        corrected.forEachIndexed { at, height ->
            assertEquals(
                authored[at] / authoredMid,
                height / correctedMid,
                1e-3f,
                "frame $at is not the height its pose was drawn at",
            )
        }
    }

    /** And the bobbing that survives is the authored bobbing, not leftover drift. */
    @Test
    fun `what is left after correcting is the walk rising and falling`() {
        val corrections = SpriteDrift.correctionsFor(AnimationState.WALK, measuredWalk)
        val corrected = measuredWalk.mapIndexed { at, height -> height * corrections[at] }

        assertEquals(
            spreadOf(SpriteDrift.authoredHeightsFor(AnimationState.WALK, measuredWalk.size)),
            spreadOf(corrected),
            1e-3f,
        )
    }

    /**
     * A frame the poses say is genuinely shorter stays shorter.
     *
     * The failure this guards against is the correction flattening the
     * animation into a row of identically sized figures, which would take the
     * crouch out of a roll and stand a corpse up.
     */
    @Test
    fun `a pose that is really shorter is not stretched to match`() {
        val authored = SpriteDrift.authoredHeightsFor(AnimationState.ROLL, 6)
        // Art that matched its poses exactly: nothing drifted, so nothing
        // should move.
        val corrections = SpriteDrift.correctionsFor(AnimationState.ROLL, authored)

        corrections.forEach {
            assertEquals(1f, it, 1e-3f, "a row that already agrees with its poses was rescaled")
        }
    }

    /**
     * A roll really is much shorter than a stand.
     *
     * Which is what makes the correction possible at all: if the authored
     * heights were flat there would be nothing to compare a drawing against.
     */
    @Test
    fun `the authored poses disagree about height, which is the signal`() {
        val roll = SpriteDrift.authoredHeightsFor(AnimationState.ROLL, 6)
        val idle = SpriteDrift.authoredHeightsFor(AnimationState.IDLE, 6)

        assertTrue(roll.min() < roll.max() * 0.8f, "the roll never curls: $roll")
        assertTrue(idle.max() - idle.min() < idle.max() * 0.1f, "the idle changes height: $idle")
    }

    /**
     * No frame is moved further than the clamp.
     *
     * A frame the model drew at double size is wrong in a way this cannot
     * repair -- more likely it came back in a different pose entirely -- and
     * dragging it all the way in would distort the art to hide a failure worth
     * seeing.
     */
    @Test
    fun `one wildly wrong frame is pulled only so far`() {
        val withAnOutlier = measuredWalk.toMutableList().also { it[4] = it[4] * 3f }

        val corrections = SpriteDrift.correctionsFor(AnimationState.WALK, withAnOutlier)

        corrections.forEach {
            assertTrue(
                it <= SpriteDrift.MAX_CORRECTION + 1e-4f && it >= 1f / SpriteDrift.MAX_CORRECTION - 1e-4f,
                "a correction of $it escaped the clamp",
            )
        }
    }

    /**
     * One outlier does not shrink every other frame.
     *
     * Which is why the middle is taken as a median rather than a mean: a mean
     * would be dragged by the frame this exists to catch, and the whole row
     * would be scaled to meet it.
     */
    @Test
    fun `an outlier does not drag the frames around it`() {
        val clean = SpriteDrift.correctionsFor(AnimationState.WALK, measuredWalk)
        val withAnOutlier = measuredWalk.toMutableList().also { it[4] = it[4] * 3f }
        val polluted = SpriteDrift.correctionsFor(AnimationState.WALK, withAnOutlier)

        measuredWalk.indices.filter { it != 4 }.forEach { at ->
            assertTrue(
                abs(clean[at] - polluted[at]) < 0.02f,
                "frame $at moved from ${clean[at]} to ${polluted[at]} because another frame was wrong",
            )
        }
    }

    @Test
    fun `nothing to correct is not an error`() {
        assertTrue(SpriteDrift.correctionsFor(AnimationState.WALK, emptyList()).isEmpty())
        assertTrue(SpriteDrift.authoredHeightsFor(AnimationState.WALK, 0).isEmpty())
        // Lists of different lengths cannot be compared frame to frame, so
        // nothing is claimed about any of them.
        assertEquals(
            listOf(1f, 1f, 1f),
            SpriteDrift.correctionsFor(listOf(1f, 2f, 3f), listOf(1f, 2f)),
        )
    }
}
