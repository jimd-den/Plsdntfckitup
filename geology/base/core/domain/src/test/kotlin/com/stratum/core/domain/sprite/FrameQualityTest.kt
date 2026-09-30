package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A frame that came back blank, cropped, doubled or untouched used to be saved
 * as drawn. These are the checks that catch it while it is still cheap to ask
 * again.
 */
class FrameQualityTest {

    private val size = 96
    private val solid = 0xFFB07840.toInt()

    /** A canvas with the given boxes drawn on it, the rest clear. */
    private fun canvas(vararg boxes: IntArray): IntArray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        if (boxes.any { (l, t, w, h) -> x in l until l + w && y in t until t + h }) solid else 0
    }

    private fun box(left: Int, top: Int, width: Int, height: Int) = intArrayOf(left, top, width, height)

    private fun analyse(pixels: IntArray) = FrameAnalyser.analyse(pixels, size, size)

    private val standing = box(40, 20, 16, 70)

    @Test
    fun `a figure is measured once, box and feet together`() {
        val analysis = analyse(canvas(standing))

        assertEquals(SourceRect(40, 20, 16, 70), analysis.bounds)
        assertEquals(47, analysis.anchorX, "the feet are under the middle of the figure")
        assertEquals(16 * 70, analysis.opaquePixels)
        assertEquals(1, analysis.blobs.size)
        assertTrue(analysis.touchedEdges.isEmpty())
    }

    @Test
    fun `a good frame has no defects`() {
        val tPose = analyse(canvas(box(10, 20, 76, 10), box(40, 20, 16, 70)))
        assertEquals(emptyList(), FrameQuality.inspect(analyse(canvas(standing)), tPose))
    }

    @Test
    fun `a blank frame is empty`() {
        assertEquals(listOf(FrameDefect.EMPTY), FrameQuality.inspect(analyse(IntArray(size * size))))
    }

    @Test
    fun `a frame still wearing its background is not a sprite`() {
        val opaque = IntArray(size * size) { solid }
        assertEquals(listOf(FrameDefect.UNKEYED), FrameQuality.inspect(analyse(opaque)))
    }

    @Test
    fun `a figure run off the top is clipped, but standing on the bottom edge is fine`() {
        assertTrue(FrameDefect.CLIPPED in FrameQuality.inspect(analyse(canvas(box(40, 0, 16, 60)))))
        assertTrue(FrameDefect.CLIPPED !in FrameQuality.inspect(analyse(canvas(box(40, 30, 16, 66)))))
    }

    @Test
    fun `two figures side by side are scattered`() {
        val twice = analyse(canvas(box(10, 20, 16, 70), box(60, 20, 16, 70)))
        assertEquals(2, twice.blobs.size)
        assertTrue(FrameDefect.SCATTERED in FrameQuality.inspect(twice))
    }

    @Test
    fun `a stray speck is not a second figure`() {
        val speck = analyse(canvas(standing, box(80, 80, 3, 3)))
        assertTrue(FrameDefect.SCATTERED !in FrameQuality.inspect(speck))
    }

    @Test
    fun `a figure far smaller than the reference is tiny, unless it is meant to be low`() {
        val reference = analyse(canvas(box(20, 10, 56, 80)))
        val small = analyse(canvas(box(44, 70, 8, 20)))

        assertTrue(FrameDefect.TINY in FrameQuality.inspect(small, reference, AnimationState.WALK))
        assertTrue(FrameDefect.TINY !in FrameQuality.inspect(small, reference, AnimationState.DIE))
    }

    @Test
    fun `the reference handed back untouched is caught, even moved and scaled`() {
        // A T-pose: a bar across the top of a column.
        val reference = analyse(canvas(box(10, 20, 76, 10), box(40, 20, 16, 70)))
        val same = analyse(canvas(box(20, 30, 57, 7), box(42, 30, 12, 52)))
        val posed = analyse(canvas(standing))

        assertTrue(FrameDefect.UNCHANGED in FrameQuality.inspect(same, reference))
        assertTrue(FrameDefect.UNCHANGED !in FrameQuality.inspect(posed, reference))
    }

    @Test
    fun `neighbouring frames that are the same drawing are found`() {
        val a = analyse(canvas(standing))
        val b = analyse(canvas(box(10, 20, 76, 10), box(40, 20, 16, 70)))
        assertEquals(listOf(0 to 1), FrameQuality.repeatedNeighbours(listOf(a, a, b)))
    }

    @Test
    fun `a sheet with a repeated frame in a clip says which`() {
        val sheet = SpriteSheet(
            id = "t", name = "t", columns = 3, rows = 1, frameWidth = size, frameHeight = size,
            clips = listOf(AnimationClip(AnimationState.WALK, 0, 3)),
        )
        val width = size * 3
        val cellA = canvas(standing)
        val cellB = canvas(box(10, 20, 76, 10), box(40, 20, 16, 70))
        val pixels = IntArray(width * size) { i ->
            val x = i % width
            val y = i / width
            val cell = if (x / size == 2) cellB else cellA
            cell[y * size + x % size]
        }

        assertEquals(listOf(0 to 1), SheetRepeats.find(pixels, width, size, sheet))
    }
}
