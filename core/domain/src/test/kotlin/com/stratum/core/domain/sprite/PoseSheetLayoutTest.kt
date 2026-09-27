package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alignment rules that used to live inside the Android composer, checked
 * on rectangles alone.
 */
class PoseSheetLayoutTest {

    private val flat: (AnimationState, Int) -> List<Float> = { _, n -> List(n) { 1f } }

    private fun plan(walk: Int = 3) = assertNotNull(
        PoseSheetPlanner.plan("hero:t", "T", mapOf(AnimationState.WALK to walk), cellSize = 128),
    )

    @Test
    fun `every frame stands on the cell floor with its feet in the middle`() {
        val measures = mapOf(
            "walk_0" to FrameMeasure(SourceRect(100, 200, 200, 600), anchorX = 200),
            "walk_1" to FrameMeasure(SourceRect(300, 100, 300, 600), anchorX = 380),
            "walk_2" to FrameMeasure(SourceRect(50, 250, 200, 600), anchorX = 120),
        )
        val layout = assertNotNull(PoseSheetLayout.of(plan(), measures, flat))
        val fitted = layout.plan

        layout.placements.forEach { placement ->
            val cell = fitted.rectFor(fitted.cellFor(placement.key)!!)
            val measure = measures.getValue(placement.key)
            val scale = placement.target.width.toFloat() / measure.bounds.width
            val footX = placement.target.left + (measure.anchorX - measure.bounds.left) * scale
            assertEquals(cell.left + fitted.cellWidth / 2f, footX, 1.5f, "${placement.key} feet drifted")
            assertEquals(cell.top + cell.height, placement.target.top + placement.target.height, "${placement.key} floats")
        }
        assertTrue(layout.missing.isEmpty())
    }

    @Test
    fun `one scale for the set, so a shorter pose stays shorter`() {
        val measures = mapOf(
            "walk_0" to FrameMeasure(SourceRect(0, 0, 100, 400)),
            "walk_1" to FrameMeasure(SourceRect(0, 0, 100, 200)),
        )
        val layout = assertNotNull(PoseSheetLayout.of(plan(2), measures) { _, n -> listOf(2f, 1f).take(n) })
        val (tall, short) = layout.placements
        assertEquals(tall.target.height / 2f, short.target.height.toFloat(), 1.5f)
    }

    @Test
    fun `a frame drawn too small against its authored pose is pulled back, within the clamp`() {
        val measures = mapOf(
            "walk_0" to FrameMeasure(SourceRect(0, 0, 100, 400)),
            "walk_1" to FrameMeasure(SourceRect(0, 0, 100, 360)),
            "walk_2" to FrameMeasure(SourceRect(0, 0, 100, 400)),
        )
        val layout = assertNotNull(PoseSheetLayout.of(plan(), measures, flat))
        val heights = layout.placements.map { it.target.height }
        assertTrue(kotlin.math.abs(heights[0] - heights[1]) <= 2, "the small frame still pulses: $heights")
    }

    @Test
    fun `nothing fits outside its own cell`() {
        val measures = mapOf(
            "walk_0" to FrameMeasure(SourceRect(0, 0, 500, 300), anchorX = 50),
            "walk_1" to FrameMeasure(SourceRect(0, 0, 80, 600), anchorX = 40),
        )
        val layout = assertNotNull(PoseSheetLayout.of(plan(2), measures, flat))
        val fitted = layout.plan
        layout.placements.forEach { placement ->
            val cell = fitted.rectFor(fitted.cellFor(placement.key)!!)
            assertTrue(placement.target.left >= cell.left - 1, "${placement.key} spills left")
            assertTrue(placement.target.left + placement.target.width <= cell.left + cell.width + 1, "${placement.key} spills right")
            assertTrue(placement.target.top >= cell.top - 1)
        }
    }

    @Test
    fun `an unmeasured frame is reported missing, and nothing measured is no layout`() {
        val one = mapOf("walk_0" to FrameMeasure(SourceRect(0, 0, 100, 400)))
        assertEquals(listOf("walk_1", "walk_2"), PoseSheetLayout.of(plan(), one, flat)?.missing)
        assertNull(PoseSheetLayout.of(plan(), emptyMap(), flat))
    }

    @Test
    fun `expected heights come from imported guides when there are any`() {
        val built = SpriteDrift.authoredHeightsFor(AnimationState.WALK, 4)
        val same = SpriteDrift.authoredHeightsFor(PoseGuides(), AnimationState.WALK, 4)
        assertEquals(built, same)
    }
}
