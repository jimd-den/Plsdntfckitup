package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MapperEditingTest {

    @Test
    fun `history undoes in reverse and forgets past its depth`() {
        var history = EditHistory<Int>(depth = 2)
        history = history.record(1).record(2).record(3)
        val (last, rest) = assertNotNull(history.undo())
        assertEquals(3, last)
        assertEquals(2, rest.undo()?.first)
        assertNull(rest.undo()?.second?.undo(), "the oldest entry should have been dropped")
        assertFalse(history.cleared().canUndo)
    }

    @Test
    fun `a box around one cell sets the grid, and a slipped tap does not`() {
        val spec = assertNotNull(MapperGrid.fromBox(SourceRect(8, 8, 64, 60), 520, 400, square = true))
        assertEquals(60, spec.cellWidth)
        assertEquals(60, spec.cellHeight)
        assertEquals(8, spec.offsetX)
        assertEquals((520 - 8) / 60, spec.columns)
        assertNull(MapperGrid.fromBox(SourceRect(0, 0, 3, 40), 520, 400, square = false))
    }

    @Test
    fun `a grid is clamped to what can be tapped`() {
        val spec = assertNotNull(MapperGrid.of(99, 0, 1024, 1024, square = false))
        assertEquals(MapperGrid.MAX_DIVISIONS, spec.columns)
        assertEquals(1, spec.rows)
    }

    @Test
    fun `the cursor wraps both ways and follows a deletion`() {
        val frames = listOf("a", "b", "c").map { FrameRef(it, SourceRect(0, 0, 1, 1)) }
        assertEquals("a", FrameCursor.step(frames, "c", forward = true))
        assertEquals("c", FrameCursor.step(frames, "a", forward = false))
        assertEquals("a", FrameCursor.step(frames, null, forward = true))
        assertEquals("c", FrameCursor.afterRemoval(frames, 5))
        assertNull(FrameCursor.afterRemoval(emptyList(), 0))
    }
}
