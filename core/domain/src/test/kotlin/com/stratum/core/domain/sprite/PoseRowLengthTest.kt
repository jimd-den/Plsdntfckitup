package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals

class PoseRowLengthTest {

    private fun rowOf(state: AnimationState, count: Int, suffix: String = "") =
        (0 until count).map { PoseCell.keyOf(state, it, suffix) }.toSet()

    /**
     * A row is as long as the frames that exist, not as long as a script.
     *
     * The failure this replaces: a row cut from a clip at twenty-four frames a
     * second holds nineteen, and every count in the pipeline was taken against
     * a script capped at twelve. So seven frames sat on disk, paid for, and
     * were dropped from the sheet and left off the screen -- which is also why
     * a long cycle and a short one looked the same.
     */
    @Test
    fun `a row longer than any script is counted in full`() {
        val drawn = rowOf(AnimationState.WALK, 19)

        assertEquals(19, PoseCell.rowLength(drawn, AnimationState.WALK))
    }

    /**
     * Counting stops at the first gap.
     *
     * A row laid out past a hole plans cells for keys that are not there, and
     * the animation plays through the blanks -- which looks exactly like the
     * character flickering out of existence, and is the one failure nobody can
     * diagnose by watching it.
     */
    @Test
    fun `a row with a hole in it stops at the hole`() {
        val drawn = rowOf(AnimationState.WALK, 19) - PoseCell.keyOf(AnimationState.WALK, 5)

        assertEquals(5, PoseCell.rowLength(drawn, AnimationState.WALK))
    }

    /** Each angle is counted on its own, and the row is as wide as the widest. */
    @Test
    fun `a row is as long as its longest angle`() {
        val drawn = rowOf(AnimationState.WALK, 19) + rowOf(AnimationState.WALK, 12, "_away")

        assertEquals(
            19,
            PoseCell.rowLengths(drawn, listOf("", "_away"))[AnimationState.WALK],
        )
    }

    /** Animations with nothing drawn are not rows at all. */
    @Test
    fun `an animation with no frames is left out`() {
        val lengths = PoseCell.rowLengths(rowOf(AnimationState.IDLE, 6))

        assertEquals(mapOf(AnimationState.IDLE to 6), lengths)
    }

    /** One state's frames are never counted as another's. */
    @Test
    fun `states are counted apart`() {
        val drawn = rowOf(AnimationState.WALK, 19) + rowOf(AnimationState.DIE, 4)

        assertEquals(
            mapOf(AnimationState.WALK to 19, AnimationState.DIE to 4),
            PoseCell.rowLengths(drawn),
        )
    }
}
