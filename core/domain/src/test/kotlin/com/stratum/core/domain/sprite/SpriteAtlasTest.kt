package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A 100x100 source cut into four 50x50 cells, with nothing mapped yet. */
private fun quartered(role: ActorRole = ActorRole.MONSTER): SpriteAtlas {
    val spec = SliceSpec(columns = 2, rows = 2, cellWidth = 50, cellHeight = 50)
    return SpriteAtlas(
        id = "test:atlas",
        name = "Test",
        sourceId = "test:image",
        sourceWidth = 100,
        sourceHeight = 100,
        frames = SpriteSlicing.slice(spec, 100, 100),
        role = role,
    )
}

private val topLeft = SpriteSlicing.frameId(0, 0)
private val topRight = SpriteSlicing.frameId(1, 0)
private val bottomLeft = SpriteSlicing.frameId(0, 1)
private val bottomRight = SpriteSlicing.frameId(1, 1)

class SpriteAtlasTest {

    @Test
    fun `a clip resolves its frames in the order they were mapped`() {
        val atlas = quartered()
            .appendToClip(AnimationState.WALK, bottomLeft)
            .appendToClip(AnimationState.WALK, topRight)

        assertEquals(
            listOf(bottomLeft, topRight),
            atlas.framesOf(AnimationState.WALK).map { it.id },
        )
    }

    @Test
    fun `the same frame may be mapped twice, which is how a pose is held`() {
        val atlas = quartered()
            .appendToClip(AnimationState.ATTACK, topLeft)
            .appendToClip(AnimationState.ATTACK, topRight)
            .appendToClip(AnimationState.ATTACK, topRight)

        assertEquals(3, atlas.framesOf(AnimationState.ATTACK).size)
    }

    @Test
    fun `switching a frame off drops it from clips without forgetting the mapping`() {
        val atlas = quartered()
            .appendToClip(AnimationState.WALK, topLeft)
            .appendToClip(AnimationState.WALK, topRight)

        val off = atlas.toggleFrame(topRight)
        assertEquals(listOf(topLeft), off.framesOf(AnimationState.WALK).map { it.id })

        // Back on, and the clip is whole again: the id never left it.
        val on = off.toggleFrame(topRight)
        assertEquals(listOf(topLeft, topRight), on.framesOf(AnimationState.WALK).map { it.id })
    }

    @Test
    fun `deleting a frame does take it out of every clip`() {
        val atlas = quartered()
            .appendToClip(AnimationState.WALK, topLeft)
            .appendToClip(AnimationState.IDLE, topLeft)
            .removeFrame(topLeft)

        assertNull(atlas.frame(topLeft))
        assertTrue(atlas.framesOf(AnimationState.WALK).isEmpty())
        assertTrue(atlas.clips.none { topLeft in it.frameIds })
    }

    @Test
    fun `a duplicated frame is mapped on its own`() {
        val atlas = quartered().duplicateFrame(topLeft)
        val copy = atlas.frames.first { it.id != topLeft && it.source == atlas.frame(topLeft)!!.source }

        val flipped = atlas.flipFrame(copy.id)
        assertFalse(flipped.frame(topLeft)!!.flippedX, "the original was flipped too")
        assertTrue(flipped.frame(copy.id)!!.flippedX)
    }

    @Test
    fun `frames reorder within a clip`() {
        val atlas = quartered()
            .appendToClip(AnimationState.WALK, topLeft)
            .appendToClip(AnimationState.WALK, topRight)
            .appendToClip(AnimationState.WALK, bottomLeft)
            .moveInClip(AnimationState.WALK, from = 2, to = 0)

        assertEquals(
            listOf(bottomLeft, topLeft, topRight),
            atlas.framesOf(AnimationState.WALK).map { it.id },
        )
    }

    @Test
    fun `a state with no frames is unmapped rather than invalid`() {
        val atlas = quartered().updateClip(AnimationState.DIE) { it }
        assertTrue(atlas.clip(AnimationState.DIE)!!.isEmpty)
        assertFalse(AnimationState.DIE in atlas.mappedStates)
        assertTrue(atlas.pruned().clips.none { it.state == AnimationState.DIE })
    }

    @Test
    fun `clip timing is clamped to something that reads as animation`() {
        val atlas = quartered().setClipTiming(AnimationState.WALK, frameDurationMs = 1)
        assertEquals(SpriteAtlas.MIN_FRAME_MS, atlas.clip(AnimationState.WALK)!!.frameDurationMs)
    }
}

class SpriteSlicingTest {

    @Test
    fun `a grid with a margin and gutters lands on the cells it should`() {
        val spec = SliceSpec(
            columns = 2,
            rows = 1,
            offsetX = 10,
            offsetY = 4,
            cellWidth = 30,
            cellHeight = 40,
            gutterX = 6,
        )
        val frames = SpriteSlicing.slice(spec, 100, 100)

        assertEquals(2, frames.size)
        assertEquals(SourceRect(10, 4, 30, 40), frames[0].source)
        assertEquals(SourceRect(46, 4, 30, 40), frames[1].source)
    }

    @Test
    fun `cells that fall off the image are dropped rather than cut from nothing`() {
        val spec = SliceSpec(columns = 4, rows = 1, cellWidth = 30, cellHeight = 30)
        val frames = SpriteSlicing.slice(spec, 70, 30)

        // Three cells: two whole, one clipped to the 10px that exist, and the
        // fourth is entirely past the edge.
        assertEquals(3, frames.size)
        assertEquals(10, frames.last().source.width)
    }

    @Test
    fun `fitting divides an image evenly and refuses a grid finer than its pixels`() {
        val spec = assertNotNull(SliceSpec.fitting(SheetGrid(4, 4), 400, 200))
        assertEquals(100, spec.cellWidth)
        assertEquals(50, spec.cellHeight)

        assertNull(SliceSpec.fitting(SheetGrid(40, 4), 20, 200))
    }

    @Test
    fun `square fitting takes the smaller division so the last cell stays on the image`() {
        // The same 400x200 image the even fit cuts into 100x50 oblongs.
        val spec = assertNotNull(SliceSpec.squareFitting(SheetGrid(4, 4), 400, 200))
        assertTrue(spec.isSquare)
        assertEquals(50, spec.cellWidth)
        assertEquals(50, spec.cellHeight)
        // Taking the larger division would have been 100, and the fourth row
        // would have started at 300 on an image 200 tall.
        assertTrue(
            spec.coveredHeight <= 200,
            "the grid runs ${spec.coveredHeight} down an image 200 tall",
        )
        assertTrue(spec.coveredWidth <= 400)
    }

    @Test
    fun `square fitting keeps a sheet that is already square exactly as it is`() {
        // A sheet the pose forge packed: 4x3 cells of 192.
        val spec = assertNotNull(SliceSpec.squareFitting(SheetGrid(4, 3), 768, 576))
        assertEquals(192, spec.cellWidth)
        assertEquals(192, spec.cellHeight)
        assertEquals(768, spec.coveredWidth, "the grid no longer covers the whole sheet")
        assertEquals(576, spec.coveredHeight)
    }

    @Test
    fun `square fitting respects the margin and the gap it is given`() {
        val spec = assertNotNull(
            SliceSpec.squareFitting(
                SheetGrid(3, 3), 200, 200, offsetX = 8, offsetY = 8, gutterX = 4, gutterY = 4,
            ),
        )
        assertTrue(spec.isSquare)
        // 200 less an 8px margin and two 4px gaps is 184, three ways: 61.
        assertEquals(61, spec.cellWidth)
        assertTrue(spec.coveredWidth <= 200 && spec.coveredHeight <= 200)
        assertEquals(SourceRect(8, 8, 61, 61), spec.rectAt(0, 0))
        // Second cell clears the first by the gap, not by nothing.
        assertEquals(8 + 61 + 4, spec.rectAt(1, 0).left)
    }

    @Test
    fun `a grid drawn as one cell describes the whole sheet`() {
        // What dragging a box around the top-left frame of a 4x3 sheet of 192s
        // has to produce: the box is the margin and the cell together, and the
        // counts come from the image.
        val spec = assertNotNull(
            SliceSpec.ofCellSize(
                cellWidth = 192, cellHeight = 192,
                imageWidth = 768, imageHeight = 576,
                offsetX = 0, offsetY = 0,
            ),
        )
        assertEquals(4, spec.columns)
        assertEquals(3, spec.rows)

        // And drawn around the *second* cell, the margin shifts the grid and
        // one column falls off the right, which is correct rather than a bug:
        // the person said the grid starts there.
        val shifted = assertNotNull(
            SliceSpec.ofCellSize(192, 192, 768, 576, offsetX = 192, offsetY = 0),
        )
        assertEquals(3, shifted.columns)
        assertEquals(192, shifted.rectAt(0, 0).left)
    }

    @Test
    fun `re-slicing keeps the mapping and what the person decided about each cell`() {
        val atlas = quartered()
            .appendToClip(AnimationState.WALK, topLeft)
            .appendToClip(AnimationState.WALK, bottomRight)
            .toggleFrame(topRight)
            .flipFrame(bottomRight)

        // The same grid, nudged four pixels right: every cell still exists.
        val nudged = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 2, rows = 2, offsetX = 4, cellWidth = 48, cellHeight = 50),
        )

        assertEquals(listOf(topLeft, bottomRight), nudged.framesOf(AnimationState.WALK).map { it.id })
        assertEquals(SourceRect(4, 0, 48, 50), nudged.frame(topLeft)!!.source)
        assertFalse(nudged.frame(topRight)!!.enabled, "the cell that was switched off came back on")
        assertTrue(nudged.frame(bottomRight)!!.flippedX)
    }

    @Test
    fun `a held pose survives a grid nudge`() {
        // The reason this matters: someone maps an attack as wind-up, strike,
        // strike, strike to hold the follow-through, then nudges the grid two
        // pixels. Losing the three copies would lose the animation, not a cell.
        var atlas = quartered().duplicateFrame(topRight)
        val copy = atlas.frames.first { it.id != topRight && it.source == atlas.frame(topRight)!!.source }
        atlas = atlas.flipFrame(copy.id)
            .appendToClip(AnimationState.ATTACK, topRight)
            .appendToClip(AnimationState.ATTACK, copy.id)

        val nudged = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 2, rows = 2, offsetX = 2, cellWidth = 49, cellHeight = 50),
        )

        assertEquals(
            listOf(topRight, copy.id),
            nudged.framesOf(AnimationState.ATTACK).map { it.id },
        )
        // The copy moved with the cell it was made from, and kept its flip.
        assertEquals(nudged.frame(topRight)!!.source, nudged.frame(copy.id)!!.source)
        assertTrue(nudged.frame(copy.id)!!.flippedX)
        assertFalse(nudged.frame(topRight)!!.flippedX)
    }

    @Test
    fun `a copy of a cell the new grid does not reach goes with it`() {
        val atlas = quartered().duplicateFrame(bottomRight)
        val coarse = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 1, rows = 1, cellWidth = 100, cellHeight = 100),
        )

        assertEquals(1, coarse.frames.size)
    }

    @Test
    fun `re-slicing to a coarser grid drops the frames that no longer exist`() {
        val atlas = quartered().appendToClip(AnimationState.WALK, bottomRight)
        val coarse = SpriteSlicing.reslice(
            atlas,
            SliceSpec(columns = 1, rows = 1, cellWidth = 100, cellHeight = 100),
        )

        assertEquals(1, coarse.frames.size)
        assertTrue(coarse.framesOf(AnimationState.WALK).isEmpty())
    }

    @Test
    fun `content bounds find the drawing inside a cell`() {
        val pixels = IntArray(8 * 8)
        // A two-by-two blob at (3,4).
        for (y in 4..5) for (x in 3..4) pixels[y * 8 + x] = OPAQUE

        val bounds = SpriteSlicing.contentBounds(pixels, 8, 8, SourceRect(0, 0, 8, 8))
        assertEquals(SourceRect(3, 4, 2, 2), bounds)
    }

    @Test
    fun `an empty cell has no content bounds`() {
        assertNull(SpriteSlicing.contentBounds(IntArray(8 * 8), 8, 8, SourceRect(0, 0, 8, 8)))
    }

    @Test
    fun `trimming tightens the drawn cells and switches the blank ones off`() {
        // 4x2 image, two 2x2 cells; only the left one has anything in it.
        val pixels = IntArray(4 * 2)
        pixels[1 * 4 + 1] = OPAQUE

        val atlas = SpriteAtlas(
            id = "t", name = "t", sourceId = "i", sourceWidth = 4, sourceHeight = 2,
            frames = SpriteSlicing.slice(SliceSpec(2, 1, cellWidth = 2, cellHeight = 2), 4, 2),
        )
        val trimmed = SpriteSlicing.trimToContent(atlas, pixels)

        assertEquals(SourceRect(1, 1, 1, 1), trimmed.frames[0].source)
        assertTrue(trimmed.frames[0].enabled)
        assertFalse(trimmed.frames[1].enabled, "a blank cell was left switched on")
    }

    private companion object {
        const val OPAQUE = 0xFF204060.toInt()
    }
}

class GroundAnchorTest {

    private val width = 64
    private val height = 64

    /** A figure standing at [footX] with an arm reaching out to [reachX]. */
    private fun figure(footX: Int, reachX: Int): IntArray {
        val pixels = IntArray(width * height)
        fun set(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = OPAQUE
        }
        // Legs and feet: a narrow column standing on the floor.
        for (y in 40 until 60) for (x in footX - 2..footX + 2) set(x, y)
        // Torso.
        for (y in 24 until 40) for (x in footX - 4..footX + 4) set(x, y)
        // One arm, reaching a long way to one side at shoulder height.
        val from = minOf(footX, reachX)
        val to = maxOf(footX, reachX)
        for (y in 26 until 30) for (x in from..to) set(x, y)
        return pixels
    }

    private fun anchorOf(pixels: IntArray): Int? = SpriteSlicing.groundAnchorX(
        pixels, width, height, SourceRect(0, 0, width, height),
    )

    @Test
    fun `the anchor follows the feet, not the reach`() {
        // The defect this exists for. An animation is a body moving its limbs
        // while its feet stay where they are, so hanging a frame from the
        // middle of its content box slides the whole body whenever an arm
        // comes out. Measured on a real walk cycle that put the feet
        // sixty-two pixels apart across a hundred and ninety-one pixel cell.
        val standing = figure(footX = 20, reachX = 20)
        val reaching = figure(footX = 20, reachX = 52)

        val still = assertNotNull(anchorOf(standing))
        val moved = assertNotNull(anchorOf(reaching))
        assertTrue(
            kotlin.math.abs(moved - still) <= 1,
            "the anchor moved from $still to $moved because an arm came out",
        )

        // And the content box's centre really does move, which is what made
        // this worth fixing rather than a theoretical concern.
        val box = assertNotNull(
            SpriteSlicing.contentBounds(reaching, width, height, SourceRect(0, 0, width, height)),
        )
        val boxCentre = box.left + box.width / 2
        assertTrue(
            kotlin.math.abs(boxCentre - still) > 8,
            "the content box centre did not move, so there was nothing to fix",
        )
    }

    @Test
    fun `a stance with one foot forward is weighted by what is down`() {
        // Centroid rather than midpoint: a figure mid-stride has more of one
        // foot on the ground than the other, and the anchor should sit where
        // the weight is rather than halfway between the toes.
        val pixels = IntArray(width * height)
        fun set(x: Int, y: Int) { pixels[y * width + x] = OPAQUE }
        // A wide back foot and a narrow front foot.
        for (y in 56 until 60) for (x in 14..22) set(x, y)
        for (y in 56 until 60) for (x in 38..40) set(x, y)
        for (y in 30 until 56) for (x in 24..30) set(x, y)

        val anchor = assertNotNull(anchorOf(pixels))
        val midpoint = (14 + 40) / 2
        assertTrue(anchor < midpoint, "the anchor ignored which foot carried the weight")
        assertTrue(anchor in 14..30, "the anchor left the feet entirely: $anchor")
    }

    @Test
    fun `nothing drawn has no anchor`() {
        assertNull(anchorOf(IntArray(width * height)))
    }

    private companion object {
        const val OPAQUE = 0xFF4488CC.toInt()
    }
}

class SpriteValidationTest {

    private fun mapped(role: ActorRole, vararg states: AnimationState): SpriteAtlas {
        var atlas = quartered(role)
        val ids = listOf(topLeft, topRight, bottomLeft, bottomRight)
        states.forEachIndexed { index, state ->
            // Two frames each, so a walk never trips the single-frame warning.
            atlas = atlas
                .appendToClip(state, ids[index % ids.size])
                .appendToClip(state, ids[(index + 1) % ids.size])
        }
        return atlas
    }

    @Test
    fun `an enemy missing its death may borrow one, and is told so`() {
        val report = SpriteValidation.validate(
            mapped(
                ActorRole.MONSTER,
                AnimationState.IDLE,
                AnimationState.WALK,
                AnimationState.ATTACK,
            ),
        )

        assertEquals(ValidationSeverity.WARNING, report.severity)
        assertTrue(AnimationState.DIE in report.missingRequired)
        assertEquals(AnimationState.IDLE, report.suggestedFallbacks[AnimationState.DIE])
    }

    @Test
    fun `a hero may not borrow, because the player looks at it all session`() {
        val report = SpriteValidation.validate(
            mapped(ActorRole.HERO, AnimationState.IDLE, AnimationState.WALK),
        )

        assertEquals(ValidationSeverity.BLOCKED, report.severity)
        assertTrue(AnimationState.ATTACK in report.missingRequired)
        // The stand-in is still offered: refusing to bake is not refusing to
        // say what would fix it.
        assertEquals(AnimationState.IDLE, report.suggestedFallbacks[AnimationState.ATTACK])
    }

    @Test
    fun `an atlas with nothing mapped is blocked and says which end to start at`() {
        val report = SpriteValidation.validate(quartered())

        assertEquals(ValidationSeverity.BLOCKED, report.severity)
        assertTrue(report.messages.any { "No frame is mapped to any state" in it.text })
    }

    @Test
    fun `a pickup that just sits there is ready`() {
        val atlas = quartered(ActorRole.PICKUP)
            .copy(frames = quartered().frames.take(1))
            .appendToClip(AnimationState.IDLE, topLeft)

        val report = SpriteValidation.validate(atlas)
        assertEquals(ValidationSeverity.READY, report.severity, report.messages.joinToString { it.text })
        assertTrue(report.messages.isEmpty())
    }

    @Test
    fun `a prop is nudged towards a destroyed state without being held up`() {
        val atlas = quartered(ActorRole.PROP)
            .copy(frames = quartered().frames.take(1))
            .appendToClip(AnimationState.IDLE, topLeft)

        val report = SpriteValidation.validate(atlas)
        assertEquals(ValidationSeverity.WARNING, report.severity)
        assertTrue(AnimationState.DIE in report.missingRecommended)
        assertFalse(report.isBlocked)
    }

    @Test
    fun `a one frame walk is called out, because it slides`() {
        val atlas = quartered(ActorRole.PROP).appendToClip(AnimationState.WALK, topLeft)
        val report = SpriteValidation.validate(atlas)

        assertTrue(
            report.messages.any { it.state == AnimationState.WALK && "slide" in it.text },
        )
    }

    @Test
    fun `frames running off the image are reported`() {
        val atlas = quartered()
            .updateFrame(topLeft) { it.copy(source = SourceRect(80, 80, 40, 40)) }
            .appendToClip(AnimationState.IDLE, topLeft)

        val report = SpriteValidation.validate(atlas)
        assertTrue(report.messages.any { "past the edge" in it.text })
    }
}

class AnimationFallbackTest {

    @Test
    fun `idle alone fills every state that can borrow from it`() {
        val atlas = quartered().appendToClip(AnimationState.IDLE, topLeft)
        val filled = AnimationFallback.fill(atlas)

        assertEquals(AnimationState.entries.toSet(), filled.mappedStates)
        assertEquals(
            listOf(topLeft),
            filled.framesOf(AnimationState.DIE).map { it.id },
        )
    }

    @Test
    fun `a borrowed clip keeps its own state's timing rather than the lender's`() {
        val atlas = quartered().appendToClip(AnimationState.IDLE, topLeft)
        val filled = AnimationFallback.fill(atlas)

        val die = assertNotNull(filled.clip(AnimationState.DIE))
        assertEquals(AnimationState.DIE.defaultFrameDurationMs, die.frameDurationMs)
        assertFalse(die.loops, "a death that loops is a resurrection")
    }

    @Test
    fun `an attack prefers the special over the idle`() {
        val atlas = quartered()
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.SPECIAL, bottomRight)

        val filled = AnimationFallback.fill(atlas)
        assertEquals(
            listOf(bottomRight),
            filled.framesOf(AnimationState.ATTACK).map { it.id },
        )
    }

    @Test
    fun `filling borrows only from real art, never from another borrowing`() {
        val atlas = quartered().appendToClip(AnimationState.IDLE, topLeft)
        val filled = AnimationFallback.fill(atlas)

        // Roll's chain is walk then idle, and walk was itself filled from idle.
        // Either route lands on the same frame, which is the point: a fill
        // cannot compound into something further from the art than its source.
        assertEquals(listOf(topLeft), filled.framesOf(AnimationState.ROLL).map { it.id })
    }

    @Test
    fun `an atlas with nothing mapped cannot be filled out of thin air`() {
        assertEquals(quartered(), AnimationFallback.fill(quartered()))
    }
}

class AtlasBakerTest {

    @Test
    fun `baking lays one clip per row and rebuilds contiguous clips`() {
        val atlas = quartered()
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.IDLE, topRight)
            .appendToClip(AnimationState.WALK, bottomLeft)

        val plan = assertNotNull(AtlasBaker.plan(atlas))
        val sheet = plan.sheet

        assertEquals(2, sheet.columns)
        assertEquals(2, sheet.rows)
        assertEquals(50, sheet.frameWidth)

        val idle = assertNotNull(sheet.clip(AnimationState.IDLE))
        assertEquals(0, idle.firstFrame)
        assertEquals(2, idle.frameCount)

        val walk = assertNotNull(sheet.clip(AnimationState.WALK))
        // Second row, so the short clip's unused cell stays empty rather than
        // the walk starting in the middle of the idle's row.
        assertEquals(2, walk.firstFrame)
        assertEquals(1, walk.frameCount)
    }

    @Test
    fun `rows come out in the canonical order whatever order they were mapped`() {
        val atlas = quartered()
            .appendToClip(AnimationState.DIE, bottomRight)
            .appendToClip(AnimationState.IDLE, topLeft)

        val sheet = assertNotNull(AtlasBaker.plan(atlas)).sheet
        assertEquals(
            listOf(AnimationState.IDLE, AnimationState.DIE),
            sheet.clips.map { it.state },
        )
    }

    @Test
    fun `a frame in two clips is copied into both`() {
        val atlas = quartered()
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.HURT, topLeft)

        val plan = assertNotNull(AtlasBaker.plan(atlas))
        assertEquals(2, plan.frames.size)
        assertEquals(listOf(0, 1), plan.frames.map { it.cell })
        assertTrue(plan.frames.all { it.frameId == topLeft })
    }

    @Test
    fun `frames of different sizes hang from their pivot`() {
        val atlas = quartered()
            .updateFrame(topLeft) { it.copy(source = SourceRect(0, 0, 20, 30)) }
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.IDLE, topRight)

        val plan = assertNotNull(AtlasBaker.plan(atlas))
        val small = plan.frames.first { it.frameId == topLeft }

        // Centred across, and sitting on the floor of its 50x50 cell: which is
        // what stops a walk built from differently cropped poses from bobbing.
        assertEquals(15, small.destination.left)
        assertEquals(20, small.destination.top)
        assertEquals(50, small.destination.top + small.destination.height)
    }

    @Test
    fun `a centred pivot hangs from the middle instead`() {
        val atlas = quartered()
            .updateFrame(topLeft) { it.copy(source = SourceRect(0, 0, 20, 30), pivot = FramePivot.CENTER) }
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.IDLE, topRight)

        val plan = assertNotNull(AtlasBaker.plan(atlas))
        assertEquals(10, plan.frames.first { it.frameId == topLeft }.destination.top)
    }

    @Test
    fun `the flip is carried to the baker rather than applied twice`() {
        val atlas = quartered()
            .flipFrame(topLeft)
            .appendToClip(AnimationState.IDLE, topLeft)

        val plan = assertNotNull(AtlasBaker.plan(atlas))
        assertTrue(plan.frames.single().flippedX)
        // The frame is unchanged in the source; only the instruction travels.
        assertEquals(atlas.frame(topLeft)!!.source, plan.frames.single().source)
    }

    @Test
    fun `art too large for a texture is scaled down as a whole`() {
        val huge = SpriteAtlas(
            id = "t", name = "t", sourceId = "i", sourceWidth = 6000, sourceHeight = 6000,
            frames = SpriteSlicing.slice(SliceSpec(2, 1, cellWidth = 3000, cellHeight = 3000), 6000, 6000),
        ).let {
            it.appendToClip(AnimationState.IDLE, it.frames[0].id)
                .appendToClip(AnimationState.IDLE, it.frames[1].id)
        }

        val plan = assertNotNull(AtlasBaker.plan(huge))
        assertTrue(plan.scale < 1f)
        assertTrue(plan.sheetWidth <= AtlasBaker.MAX_SHEET_EDGE)
        assertTrue(plan.sheetHeight <= AtlasBaker.MAX_SHEET_EDGE)
    }

    @Test
    fun `the baked sheet is one the existing renderer can already cut`() {
        val atlas = quartered()
            .appendToClip(AnimationState.IDLE, topLeft)
            .appendToClip(AnimationState.WALK, topRight)
            .appendToClip(AnimationState.WALK, bottomLeft)

        val sheet = assertNotNull(AtlasBaker.plan(atlas)).sheet
        assertTrue(sheet.isCoherent)

        // The clip plays the cells the plan wrote, in order.
        val walk = assertNotNull(sheet.clip(AnimationState.WALK))
        assertEquals(2, walk.frameAt(0))
        assertEquals(3, walk.frameAt(walk.frameDurationMs.toLong()))
    }

    @Test
    fun `facing is baked into the sheet so the renderer knows not to flip a glyph`() {
        val atlas = quartered().copy(facing = FacingLayout.STATIC)
            .appendToClip(AnimationState.IDLE, topLeft)

        assertFalse(assertNotNull(AtlasBaker.plan(atlas)).sheet.mirrorsFacings)
    }

    @Test
    fun `an atlas with nothing mapped has no plan`() {
        assertNull(AtlasBaker.plan(quartered()))
    }
}

class SpriteMapperTest {

    @Test
    fun `a suggested atlas opens on the grid the inspector actually found`() {
        val atlas = SpriteMapper.suggest(
            id = "gen:hero",
            name = "Hero",
            sourceId = "gen:hero",
            imageWidth = 400,
            imageHeight = 200,
            verdict = GridVerdict(SheetGrid(4, 2), GridOutcome.RECUT, 0.8f),
        )

        assertEquals(8, atlas.frames.size)
        assertEquals(SourceRect(0, 0, 100, 100), atlas.frames.first().source)
        // Two rows, so the two most useful states, in the canonical order.
        assertEquals(
            listOf(AnimationState.IDLE, AnimationState.WALK),
            atlas.clips.map { it.state },
        )
        assertEquals(4, atlas.framesOf(AnimationState.WALK).size)
    }

    @Test
    fun `a picture rather than a sheet opens as a still that idles`() {
        val atlas = SpriteMapper.suggest(
            id = "gen:thing",
            name = "Thing",
            sourceId = "gen:thing",
            imageWidth = 512,
            imageHeight = 512,
            verdict = GridVerdict(SheetGrid(1, 1), GridOutcome.SINGLE_FRAME, 0.1f),
        )

        assertEquals(1, atlas.frames.size)
        assertEquals(setOf(AnimationState.IDLE), atlas.mappedStates)
    }

    @Test
    fun `an already cut sheet can be opened and re-mapped`() {
        val sheet = SpriteSheet(
            id = "gen:old",
            name = "Old",
            columns = 4,
            rows = 2,
            frameWidth = 64,
            frameHeight = 64,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 4),
                AnimationClip(AnimationState.WALK, firstFrame = 4, frameCount = 4),
            ),
            origin = SpriteOrigin.AI_GENERATED,
        )

        val atlas = SpriteMapper.fromSheet(sheet)
        assertEquals(8, atlas.frames.size)
        assertEquals(setOf(AnimationState.IDLE, AnimationState.WALK), atlas.mappedStates)
        assertEquals(SpriteOrigin.AI_GENERATED, atlas.origin)

        // Round trip: mapped, then baked back to the same cut.
        val rebaked = assertNotNull(AtlasBaker.plan(atlas)).sheet
        assertEquals(sheet.columns, rebaked.columns)
        assertEquals(sheet.rows, rebaked.rows)
        assertEquals(sheet.frameWidth, rebaked.frameWidth)
        assertEquals(sheet.clips.map { it.state }, rebaked.clips.map { it.state })
    }

    @Test
    fun `a still is a whole image mapped to idle`() {
        val atlas = SpriteMapper.singleStill("t", "T", "i", 300, 200)
        assertEquals(SourceRect(0, 0, 300, 200), atlas.frames.single().source)
        assertEquals(setOf(AnimationState.IDLE), atlas.mappedStates)
    }
}
