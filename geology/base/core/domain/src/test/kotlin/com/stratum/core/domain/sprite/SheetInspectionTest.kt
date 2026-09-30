package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetInspectionTest {

    private val clear = 0x00000000
    private val ink = 0xFF203040.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A sheet with a blob centred in each cell and empty seams between them. */
    private fun sheet(
        grid: SheetGrid,
        size: Int = 240,
        background: Int = clear,
        fill: Float = 0.5f,
    ): IntArray {
        val pixels = IntArray(size * size) { background }
        val cellWidth = size / grid.columns
        val cellHeight = size / grid.rows
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val inset = ((1f - fill) / 2f)
                val x0 = column * cellWidth + (cellWidth * inset).toInt()
                val x1 = (column + 1) * cellWidth - (cellWidth * inset).toInt()
                val y0 = row * cellHeight + (cellHeight * inset).toInt()
                val y1 = (row + 1) * cellHeight - (cellHeight * inset).toInt()
                for (y in y0 until y1) {
                    for (x in x0 until x1) pixels[y * size + x] = ink
                }
            }
        }
        return pixels
    }

    /** One big figure across the whole canvas: what a weak model draws instead. */
    private fun onePicture(size: Int = 240, background: Int = clear): IntArray {
        val pixels = IntArray(size * size) { background }
        val inset = size / 8
        for (y in inset until size - inset) {
            for (x in inset until size - inset) pixels[y * size + x] = ink
        }
        return pixels
    }

    @Test
    fun `a sheet drawn on the grid it was asked for is taken as drawn`() {
        val grid = SheetGrid(4, 4)
        val verdict = SheetInspection.inspect(sheet(grid), 240, 240, grid)

        assertEquals(GridOutcome.AS_ASKED, verdict.outcome)
        assertEquals(grid, verdict.grid)
        assertTrue(verdict.confidence >= SheetInspection.MIN_CONFIDENCE)
    }

    @Test
    fun `one big picture is kept whole rather than cut into frames`() {
        // The whole point. Cutting a picture on a grid it was never drawn on is
        // what makes a sprite pan instead of animate.
        val verdict = SheetInspection.inspect(onePicture(), 240, 240, SheetGrid(6, 7))

        assertEquals(GridOutcome.SINGLE_FRAME, verdict.outcome)
        assertEquals(SheetGrid(1, 1), verdict.grid)
    }

    @Test
    fun `a sheet drawn on a different grid is cut on the one it has`() {
        val actual = SheetGrid(2, 2)
        val verdict = SheetInspection.inspect(sheet(actual), 240, 240, SheetGrid(6, 7))

        assertEquals(GridOutcome.RECUT, verdict.outcome)
        assertEquals(actual, verdict.grid)
    }

    @Test
    fun `an opaque background is no obstacle to finding the grid`() {
        // A sheet whose background survived keying still has seams; they are
        // just white rather than empty. Reading only alpha would call every
        // such sheet a single picture.
        val grid = SheetGrid(4, 4)
        val verdict = SheetInspection.inspect(
            sheet(grid, background = white),
            240,
            240,
            grid,
        )

        assertEquals(GridOutcome.AS_ASKED, verdict.outcome)
    }

    @Test
    fun `one big picture on an opaque background is still one picture`() {
        val verdict = SheetInspection.inspect(
            onePicture(background = white),
            240,
            240,
            SheetGrid(6, 7),
        )

        assertEquals(GridOutcome.SINGLE_FRAME, verdict.outcome)
    }

    @Test
    fun `a sheet with one lonely figure in a corner is not a sheet`() {
        // Empty seams everywhere are easy when almost nothing is drawn. Most
        // cells have to be used before a grid is believed.
        val size = 240
        val pixels = IntArray(size * size) { clear }
        for (y in 10 until 50) {
            for (x in 10 until 50) pixels[y * size + x] = ink
        }

        val verdict = SheetInspection.inspect(pixels, size, size, SheetGrid(6, 7))

        assertEquals(GridOutcome.SINGLE_FRAME, verdict.outcome)
    }

    @Test
    fun `a blank image is not judged at all`() {
        val size = 64
        val verdict = SheetInspection.inspect(
            IntArray(size * size) { white },
            size,
            size,
            SheetGrid(4, 4),
        )

        assertEquals(GridOutcome.UNVERIFIED, verdict.outcome)
        assertEquals(SheetGrid(4, 4), verdict.grid)
    }

    @Test
    fun `a nonsense image is left on the grid it was asked for`() {
        val verdict = SheetInspection.inspect(IntArray(4), 100, 100, SheetGrid(4, 4))

        assertEquals(GridOutcome.UNVERIFIED, verdict.outcome)
    }

    @Test
    fun `re-cutting rebuilds the clips rather than keeping ones that point nowhere`() {
        val sheet = SpriteSheet(
            id = "hero:x",
            name = "x",
            columns = 6,
            rows = 7,
            frameWidth = 40,
            frameHeight = 34,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 6),
                AnimationClip(AnimationState.DIE, firstFrame = 36, frameCount = 6),
            ),
        )

        val recut = sheet.recut(SheetGrid(2, 2), imageWidth = 240, imageHeight = 240)

        assertEquals(2, recut.columns)
        assertEquals(120, recut.frameWidth)
        assertTrue(recut.isCoherent, "a re-cut sheet still pointed past its own frames")
        assertEquals(
            listOf(AnimationState.IDLE, AnimationState.WALK),
            recut.clips.map { it.state },
        )
    }

    @Test
    fun `a single cell becomes one still frame`() {
        val sheet = SpriteSheet(
            id = "hero:x",
            name = "x",
            columns = 6,
            rows = 7,
            frameWidth = 40,
            frameHeight = 34,
            clips = listOf(AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 6)),
        )

        val recut = sheet.recut(SheetGrid(1, 1), imageWidth = 1024, imageHeight = 1024)

        assertEquals(1, recut.frameCount)
        assertEquals(1024, recut.frameWidth)
        assertEquals(1, recut.clips.single().frameCount)
        assertTrue(recut.isCoherent)
    }
}
