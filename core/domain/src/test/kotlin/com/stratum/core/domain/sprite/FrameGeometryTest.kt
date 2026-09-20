package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sheetOf(width: Int = 100, height: Int = 100): SpriteAtlas = SpriteAtlas(
    id = "test:atlas",
    name = "Test",
    sourceId = "test:image",
    sourceWidth = width,
    sourceHeight = height,
    frames = SpriteSlicing.slice(
        SliceSpec(columns = 2, rows = 2, cellWidth = width / 2, cellHeight = height / 2),
        width,
        height,
    ),
)

private val cell = SpriteSlicing.frameId(0, 0)

class SourceRectGeometryTest {

    @Test
    fun `an edge pushed outward grows the rectangle on that side only`() {
        val rect = SourceRect(10, 10, 20, 20)
        assertEquals(SourceRect(6, 10, 24, 20), rect.expanded(left = 4))
        assertEquals(SourceRect(10, 10, 24, 20), rect.expanded(right = 4))
    }

    @Test
    fun `a rectangle refuses to be shrunk out of existence`() {
        val rect = SourceRect(10, 10, 4, 4)
        // Rather than leaving a sliver nobody can see or grab.
        assertEquals(rect, rect.expanded(left = -4, right = -4))
    }

    @Test
    fun `a rectangle pushed off the edge comes back the same size`() {
        val rect = SourceRect(90, 90, 20, 20).nudgedInside(100, 100)
        assertEquals(SourceRect(80, 80, 20, 20), rect)

        val negative = SourceRect(-10, -10, 20, 20).nudgedInside(100, 100)
        assertEquals(SourceRect(0, 0, 20, 20), negative)
    }

    @Test
    fun `a rectangle larger than the image is cut down, since it cannot fit`() {
        val rect = SourceRect(-5, -5, 200, 200).nudgedInside(100, 100)
        assertEquals(SourceRect(0, 0, 100, 100), rect)
    }
}

class FrameGeometryTest {

    @Test
    fun `moving a frame against the edge does not narrow it`() {
        var atlas = sheetOf()
        // Far more than the room available, repeatedly, the way a drag does.
        repeat(5) { atlas = FrameGeometry.move(atlas, cell, dx = 40, dy = 40) }

        val frame = assertNotNull(atlas.frame(cell))
        assertEquals(50, frame.source.width, "the frame lost width against the border")
        assertEquals(50, frame.source.height)
        assertTrue(frame.source.isWithin(100, 100))
    }

    @Test
    fun `growing a frame keeps it on the image`() {
        val atlas = FrameGeometry.expand(sheetOf(), cell, left = 30, top = 30)
        val frame = assertNotNull(atlas.frame(cell))

        assertTrue(frame.source.isWithin(100, 100))
        assertEquals(80, frame.source.width)
    }

    @Test
    fun `snapping one frame leaves the others alone`() {
        val pixels = IntArray(100 * 100)
        for (y in 10..19) for (x in 10..19) pixels[y * 100 + x] = OPAQUE

        val atlas = FrameGeometry.snapToContent(sheetOf(), cell, pixels)

        assertEquals(SourceRect(10, 10, 10, 10), atlas.frame(cell)!!.source)
        assertEquals(
            SourceRect(50, 0, 50, 50),
            atlas.frame(SpriteSlicing.frameId(1, 0))!!.source,
            "a neighbour was trimmed too",
        )
    }

    @Test
    fun `snapping an empty frame leaves it where it is`() {
        val atlas = sheetOf()
        assertEquals(atlas, FrameGeometry.snapToContent(atlas, cell, IntArray(100 * 100)))
    }

    @Test
    fun `a hand placed frame survives changing the grid`() {
        // The point of free frames: art no grid describes. Re-slicing rebuilds
        // frames from grid positions, and a hand-placed one has none -- so
        // without carrying it across, changing the column count would delete
        // every rectangle the person drew.
        var atlas = FrameGeometry.addFrame(sheetOf(), SourceRect(12, 34, 20, 20))
        val free = atlas.frames.last()
        atlas = atlas.appendToClip(AnimationState.ATTACK, free.id)

        val resliced = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 3, rows = 3, cellWidth = 33, cellHeight = 33),
        )

        assertEquals(SourceRect(12, 34, 20, 20), resliced.frame(free.id)?.source)
        assertEquals(listOf(free.id), resliced.framesOf(AnimationState.ATTACK).map { it.id })
    }

    @Test
    fun `hand placed frames stay after the cells, where they were added`() {
        // Stepping through a sheet in the large view walks this list, so the
        // order is the order a person meets the frames in.
        val atlas = FrameGeometry.addFrame(sheetOf(), SourceRect(12, 34, 20, 20))
        val resliced = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 2, rows = 2, cellWidth = 50, cellHeight = 50),
        )

        assertTrue(resliced.frames.last().id.startsWith(FrameGeometry.FREE_PREFIX))
        assertEquals(5, resliced.frames.size)
    }

    @Test
    fun `free frames get their own ids rather than colliding`() {
        var atlas = sheetOf()
        repeat(3) { atlas = FrameGeometry.addFrame(atlas, SourceRect(0, 0, 10, 10)) }

        val free = atlas.frames.filter { it.id.startsWith(FrameGeometry.FREE_PREFIX) }
        assertEquals(3, free.size)
        assertEquals(3, free.map { it.id }.toSet().size)
    }

    @Test
    fun `a new frame lands beside the one being looked at, not on top of it`() {
        val atlas = sheetOf()
        val near = assertNotNull(atlas.frame(cell))
        val rect = FrameGeometry.nextFreeRect(atlas, near)

        assertEquals(near.source.width, rect.width)
        assertTrue(rect.left != near.source.left || rect.top != near.source.top)
        assertTrue(rect.isWithin(100, 100))
    }

    private companion object {
        const val OPAQUE = 0xFF102030.toInt()
    }
}

class CellSizeSlicingTest {

    @Test
    fun `a sheet described by its cell size works out its own grid`() {
        val spec = assertNotNull(SliceSpec.ofCellSize(64, 64, imageWidth = 320, imageHeight = 192))
        assertEquals(5, spec.columns)
        assertEquals(3, spec.rows)
        assertEquals(64, spec.cellWidth)
    }

    @Test
    fun `gutters are counted between cells and not after the last one`() {
        // Three 20px cells with 10px between them is 80px, not 90.
        val spec = assertNotNull(
            SliceSpec.ofCellSize(20, 20, imageWidth = 80, imageHeight = 20, gutterX = 10),
        )
        assertEquals(3, spec.columns)
        assertEquals(80, spec.coveredWidth)
    }

    @Test
    fun `a cell larger than the image is not a grid`() {
        assertNull(SliceSpec.ofCellSize(200, 200, imageWidth = 100, imageHeight = 100))
        assertNull(SliceSpec.ofCellSize(0, 64, imageWidth = 100, imageHeight = 100))
    }

    @Test
    fun `the worked out grid slices to whole cells`() {
        val spec = assertNotNull(SliceSpec.ofCellSize(64, 64, imageWidth = 320, imageHeight = 192))
        val frames = SpriteSlicing.slice(spec, 320, 192)

        assertEquals(15, frames.size)
        assertTrue(frames.all { it.source.width == 64 && it.source.height == 64 })
    }
}

class ProceduralMotionTest {

    @Test
    fun `real animation is left completely alone`() {
        val motion = ProceduralMotion.forFrame(
            state = AnimationState.DIE,
            elapsedMs = 300,
            clipDurationMs = 780,
            frameCount = 6,
            standsIn = false,
        )
        // A drawn death does not want a procedural collapse fighting it.
        assertTrue(motion.isIdentity)
    }

    @Test
    fun `a borrowed death sinks, flattens and fades`() {
        val start = ProceduralMotion.forFrame(AnimationState.DIE, 0, 400, 1, standsIn = true)
        val end = ProceduralMotion.forFrame(AnimationState.DIE, 400, 400, 1, standsIn = true)

        assertTrue(start.isIdentity, "a death that starts collapsed never fell")
        assertTrue(end.offsetY > 0f, "the body did not sink")
        assertTrue(end.scaleY < 1f, "the body did not flatten")
        assertTrue(end.alpha < 1f, "the body did not fade")
        // Not to nothing: the engine decides when a corpse leaves, and a sprite
        // that has erased itself cannot be shown for the rest of that.
        assertTrue(end.alpha > 0f)
    }

    @Test
    fun `a borrowed attack lunges and comes back`() {
        val mid = ProceduralMotion.forFrame(AnimationState.ATTACK, 50, 100, 1, standsIn = true)
        val done = ProceduralMotion.forFrame(AnimationState.ATTACK, 100, 100, 1, standsIn = true)

        assertTrue(mid.offsetX > 0f, "the swing never moved forward")
        assertEquals(0f, done.offsetX, 0.001f, "the swing ended somewhere other than where it began")
    }

    @Test
    fun `a borrowed flinch goes backwards, because it has to say who won`() {
        val mid = ProceduralMotion.forFrame(AnimationState.HURT, 50, 100, 1, standsIn = true)
        assertTrue(mid.offsetX < 0f)
    }

    @Test
    fun `a still idle breathes`() {
        val motion = ProceduralMotion.forFrame(AnimationState.IDLE, 0, 200, 1, standsIn = false)
        assertFalse(motion.isIdentity, "a single-frame idle reads as a decal without it")
        // Small enough that nobody reads it as motion going wrong.
        assertTrue(motion.offsetY > -0.05f)
    }

    @Test
    fun `a borrowed walk bounces once per foot`() {
        val quarter = ProceduralMotion.forFrame(AnimationState.WALK, 130, 520, 1, standsIn = true)
        val half = ProceduralMotion.forFrame(AnimationState.WALK, 260, 520, 1, standsIn = true)
        val threeQuarters = ProceduralMotion.forFrame(AnimationState.WALK, 390, 520, 1, standsIn = true)

        assertTrue(quarter.offsetY < 0f, "the body did not rise on the first step")
        assertEquals(0f, half.offsetY, 0.001f, "the body did not come back down between steps")
        assertTrue(threeQuarters.offsetY < 0f, "the second step did not happen")
    }

    @Test
    fun `a clip with no duration does not divide by it`() {
        val motion = ProceduralMotion.forFrame(AnimationState.DIE, 500, 0, 1, standsIn = true)
        assertTrue(motion.isIdentity)
    }
}

class BorrowedClipTest {

    private fun idleOnly(): SpriteAtlas =
        sheetOf().appendToClip(AnimationState.IDLE, cell)

    @Test
    fun `filling records which state each clip borrowed from`() {
        val filled = AnimationFallback.fill(idleOnly())

        assertEquals(AnimationState.IDLE, filled.clip(AnimationState.DIE)?.borrowedFrom)
        assertNull(
            filled.clip(AnimationState.IDLE)?.borrowedFrom,
            "the clip that was actually mapped was marked as borrowed",
        )
    }

    @Test
    fun `choosing frames by hand makes a borrowed clip the person's own`() {
        val filled = AnimationFallback.fill(idleOnly())
        val chosen = filled.appendToClip(AnimationState.DIE, SpriteSlicing.frameId(1, 1))

        assertNull(chosen.clip(AnimationState.DIE)?.borrowedFrom)
    }

    @Test
    fun `baking carries the borrowing through to the sheet the renderer plays`() {
        val filled = AnimationFallback.fill(idleOnly())
        val sheet = assertNotNull(AtlasBaker.plan(filled)).sheet

        assertEquals(AnimationState.IDLE, sheet.clip(AnimationState.DIE)?.standsInFor)
        assertNull(sheet.clip(AnimationState.IDLE)?.standsInFor)
    }

    @Test
    fun `a borrowed clip on a sheet is what turns the renderer's compensation on`() {
        val sheet = assertNotNull(AtlasBaker.plan(AnimationFallback.fill(idleOnly()))).sheet
        val die = assertNotNull(sheet.clip(AnimationState.DIE))

        val motion = ProceduralMotion.forFrame(
            state = AnimationState.DIE,
            elapsedMs = die.durationMs.toLong(),
            clipDurationMs = die.durationMs,
            frameCount = die.frameCount,
            standsIn = die.standsInFor != null,
        )
        assertTrue(motion.alpha < 1f)
    }
}
