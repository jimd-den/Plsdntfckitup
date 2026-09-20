package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.AtlasBaker
import com.stratum.core.domain.sprite.MocapPoses
import com.stratum.core.domain.sprite.SpriteFacing
import com.stratum.core.domain.sprite.PoseCell
import com.stratum.core.domain.sprite.PoseSheetPlanner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingImageModel(
    private val reply: Result<GeneratedImage> = Result.success(
        GeneratedImage(ByteArray(16) { 7 }, "image/png", 1024, 1024),
    ),
) : ImageModelPort {
    val requests = mutableListOf<ImageRequest>()
    override suspend fun generateImage(
        request: ImageRequest,
        observer: GenerationObserver,
    ): Result<GeneratedImage> {
        requests += request
        return reply
    }
}

class PoseScriptTest {

    @Test
    fun `a script runs the states in the canonical order`() {
        val script = PoseScript.of(listOf(AnimationState.DIE, AnimationState.IDLE, AnimationState.WALK))

        assertEquals(
            listOf(AnimationState.IDLE, AnimationState.WALK, AnimationState.DIE),
            script.states,
        )
    }

    @Test
    fun `a full script is six frames of every state, which is a 6x7 sheet`() {
        val script = PoseScript.full()
        AnimationState.entries.forEach { state ->
            assertEquals(6, script.stepsFor(state).size, "$state is not six frames")
        }
        assertEquals(42, script.steps.size)
        assertEquals(script.steps.size, script.frameCounts().values.sum())

        // Six across and seven down, which is the shape the sheet comes out.
        assertEquals(6, script.frameCounts().values.max())
        assertEquals(7, script.frameCounts().size)
    }

    @Test
    fun `an animation can be asked for more frames, and they are all different`() {
        AnimationState.entries.forEach { state ->
            PoseScript.FRAME_CHOICES.forEach { count ->
                val poses = PoseScript.posesFor(state, count)
                assertEquals(count, poses.size, "$state at $count frames")
                // The whole point of asking for more: paying twice for the
                // same instruction would buy a duplicate frame, not a
                // smoother animation.
                assertEquals(
                    count,
                    poses.toSet().size,
                    "$state at $count frames repeated an instruction",
                )
            }
        }
    }

    @Test
    fun `raising the frame count adds work instead of invalidating it`() {
        // Frames already drawn are keyed by state and index. If a longer
        // script renumbered them, every frame already paid for would be
        // silently wrong, and nothing would say so.
        val four = PoseScript.posesFor(AnimationState.WALK, 4)
        val twelve = PoseScript.posesFor(AnimationState.WALK, 12)
        assertEquals(four.first(), twelve.first())
        assertTrue(four.all { it in twelve }, "a shorter walk is not a subset of a longer one")
    }

    @Test
    fun `each animation is counted on its own`() {
        val script = PoseScript.full(
            mapOf(AnimationState.IDLE to 12, AnimationState.DIE to 3),
        )
        assertEquals(12, script.stepsFor(AnimationState.IDLE).size)
        assertEquals(3, script.stepsFor(AnimationState.DIE).size)
        // Everything unnamed keeps the default rather than following the last
        // thing that was set.
        assertEquals(
            PoseScript.DEFAULT_FRAMES,
            script.stepsFor(AnimationState.WALK).size,
        )
    }

    @Test
    fun `every step of every frame count has a skeleton to pose from`() {
        // The guides are authored at one length and the script can now be
        // asked for another, so the two have to meet for any count rather
        // than only at the one they were written at.
        AnimationState.entries.forEach { state ->
            PoseScript.FRAME_CHOICES.forEach { count ->
                val posed = (0 until count).map { index ->
                    MocapPoses.poseFor(state, index, count)
                }
                assertEquals(count, posed.size)
                if (count > 1) {
                    assertTrue(
                        posed.toSet().size > 1,
                        "$state at $count frames posed every frame identically",
                    )
                }
            }
        }
    }

    @Test
    fun `every pose describes a body rather than a character`() {
        // The identity comes from the reference image. A step that described
        // the character would invite the model to redraw it, which is the drift
        // this whole pipeline exists to avoid.
        PoseScript.full().steps.forEach { step ->
            assertFalse(step.instruction.isBlank(), "${step.key} has no instruction")
            assertFalse(
                "character" in step.instruction.lowercase(),
                "${step.key} describes the character: ${step.instruction}",
            )
        }
    }

    @Test
    fun `a step and the cell that receives it agree on the name`() {
        val step = PoseScript.full().stepsFor(AnimationState.ATTACK)[2]
        assertEquals(PoseCell.keyOf(AnimationState.ATTACK, 2), step.key)
    }

    @Test
    fun `an interrupted run carries on from what is already drawn`() {
        val script = PoseScript.enemy()
        val done = script.steps.take(5).map { it.key }.toSet()

        assertEquals(script.steps.size - 5, script.remaining(done).size)
        assertTrue(script.remaining(done).none { it.key in done })
        assertEquals(5f / script.steps.size, script.progress(done))
    }

    @Test
    fun `an enemy script skips the states nobody watches it perform`() {
        val states = PoseScript.enemy().states
        assertTrue(AnimationState.ROLL !in states)
        assertTrue(AnimationState.DIE in states)
    }
}

class PoseSheetPlannerTest {

    @Test
    fun `poses are laid out one animation per row`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "hero:warrior",
                name = "Warrior",
                frameCounts = mapOf(
                    AnimationState.IDLE to 2,
                    AnimationState.WALK to 4,
                    AnimationState.DIE to 4,
                ),
                cellSize = 96,
            ),
        )

        assertEquals(4, plan.columns)
        assertEquals(3, plan.rows)
        assertEquals(384, plan.width)
        assertEquals(288, plan.height)

        val walk = assertNotNull(plan.sheet.clip(AnimationState.WALK))
        assertEquals(4, walk.firstFrame)
        assertEquals(4, walk.frameCount)
        // Short rows are padded rather than packed, so the death starts on its
        // own line instead of in the middle of the walk.
        val die = assertNotNull(plan.sheet.clip(AnimationState.DIE))
        assertEquals(8, die.firstFrame)
        assertTrue(plan.sheet.isCoherent)
    }

    @Test
    fun `every pose has a cell and knows where it lands`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = mapOf(AnimationState.IDLE to 2, AnimationState.WALK to 4),
                cellSize = 64,
            ),
        )

        assertEquals(6, plan.cells.size)
        val secondWalk = assertNotNull(plan.cellFor(PoseCell.keyOf(AnimationState.WALK, 1)))
        assertEquals(1, secondWalk.row)
        assertEquals(1, secondWalk.column)
        assertEquals(5, secondWalk.cellIndex(plan.columns))

        val rect = plan.rectFor(secondWalk)
        assertEquals(64, rect.left)
        assertEquals(64, rect.top)
        assertEquals(64, rect.width)
    }

    @Test
    fun `a cell size no sheet could use is brought back into range`() {
        val huge = assertNotNull(
            PoseSheetPlanner.plan("t", "T", mapOf(AnimationState.IDLE to 1), cellSize = 4096),
        )
        assertEquals(PoseSheetPlanner.MAX_CELL, huge.cellHeight)

        val tiny = assertNotNull(
            PoseSheetPlanner.plan("t", "T", mapOf(AnimationState.IDLE to 1), cellSize = 1),
        )
        assertEquals(PoseSheetPlanner.MIN_CELL, tiny.cellHeight)
    }

    @Test
    fun `cells are cut to the figure, not to a square`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = mapOf(AnimationState.IDLE to 2),
                cellSize = 192,
            ),
        )
        assertEquals(192, plan.cellWidth, "a plan starts square, having measured nothing")

        // The real numbers from a generated set: the widest pose was 486
        // source pixels across and the tallest 906 down.
        val fitted = plan.fittedTo(contentWidth = 486, contentHeight = 906)
        assertEquals(192, fitted.cellHeight, "the height asked for was not honoured")
        assertEquals(103, fitted.cellWidth)

        // What this buys is sheet, not detail. The character is drawn 103
        // pixels across either way -- the common scale is set by the height in
        // both cases -- but a square cell would surround it with 89 columns of
        // nothing, and it is paying for that emptiness across every cell that
        // makes a taller cell look unaffordable.
        val scale = minOf(
            fitted.cellWidth.toFloat() / 486,
            fitted.cellHeight.toFloat() / 906,
        )
        assertTrue(
            (486 * scale) / fitted.cellWidth > 0.95f,
            "the widest pose still does not fill the width of its cell",
        )
        assertTrue(
            fitted.width < plan.width,
            "fitting the cells did not make the sheet any smaller",
        )

        // The runtime cuts frames on the sheet's own frame size, so a sheet
        // that disagreed with the cells it was drawn in would shear.
        assertEquals(fitted.cellWidth, fitted.sheet.frameWidth)
        assertEquals(fitted.cellHeight, fitted.sheet.frameHeight)
        assertEquals(fitted.cellWidth * fitted.columns, fitted.width)
    }

    @Test
    fun `a set too big for one texture is brought back inside it`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = AnimationState.entries.associateWith { 8 },
                cellSize = PoseSheetPlanner.MAX_CELL,
            ),
        )
        // A wide figure at the largest cell across a full script would run off
        // the end of any texture a GPU will take.
        val fitted = plan.fittedTo(contentWidth = 900, contentHeight = 900)
        assertTrue(fitted.width <= AtlasBaker.MAX_SHEET_EDGE, "sheet is ${fitted.width} wide")
        assertTrue(fitted.height <= AtlasBaker.MAX_SHEET_EDGE, "sheet is ${fitted.height} tall")
    }

    @Test
    fun `measuring nothing leaves the plan alone`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan("t", "T", mapOf(AnimationState.IDLE to 1), cellSize = 192),
        )
        // Not a hypothetical: every pose in the set failing to decode is what
        // an exhausted key looks like, and a divide by zero on top of that
        // would replace a clear failure with a crash.
        assertEquals(plan, plan.fittedTo(0, 0))
    }

    @Test
    fun `an away view is a second block of rows the facing reaches`() {
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = mapOf(AnimationState.IDLE to 6, AnimationState.WALK to 6),
                views = listOf(
                    "" to listOf(SpriteFacing.SOUTH_EAST, SpriteFacing.SOUTH_WEST),
                    "_away" to listOf(SpriteFacing.NORTH_EAST, SpriteFacing.NORTH_WEST),
                ),
            ),
        )

        // Two states, two angles: four rows, not two and not eight.
        assertEquals(4, plan.rows)
        assertEquals(6, plan.columns)

        // Every pose of both angles has a cell, and they are different cells.
        val front = assertNotNull(plan.cellFor("walk_2"))
        val away = assertNotNull(plan.cellFor("walk_2_away"))
        assertEquals(front.column, away.column)
        assertEquals(front.row + 2, away.row, "the away block did not start after the front one")

        // The clip is written once, against the first block. This is the part
        // that would break silently: a second set of clips would be the same
        // animation named twice, and the sheet has one clip per state.
        assertEquals(2, plan.sheet.clips.size)

        // And the facing is what carries a frame into the right block.
        val walk = assertNotNull(plan.sheet.clip(AnimationState.WALK))
        val frame = walk.firstFrame + 2
        assertEquals(frame, plan.sheet.frameFor(frame, SpriteFacing.SOUTH_EAST))
        assertEquals(
            frame + 2 * plan.columns,
            plan.sheet.frameFor(frame, SpriteFacing.NORTH_EAST),
            "walking away drew the frame facing the viewer",
        )
        // The mirror still covers the other side of each pair, which is why
        // two drawn angles serve four.
        assertEquals(
            plan.sheet.frameFor(frame, SpriteFacing.NORTH_EAST),
            plan.sheet.frameFor(frame, SpriteFacing.NORTH_WEST),
        )
        assertTrue(SpriteFacing.NORTH_WEST.mirrored)
    }

    @Test
    fun `a front-only sheet is exactly what it was before views existed`() {
        // The default has to stay byte-for-byte what every character already
        // on disk was planned as, or opening one re-cuts it.
        val plan = assertNotNull(
            PoseSheetPlanner.plan("t", "T", mapOf(AnimationState.IDLE to 6)),
        )
        assertEquals(1, plan.rows)
        assertEquals("idle_0", plan.cells.first().key)
        assertEquals(0, plan.sheet.frameFor(0, SpriteFacing.NORTH_WEST))
    }

    @Test
    fun `a script draws every front frame before any away frame`() {
        // A run that stops halfway should leave one complete angle rather than
        // half of each: a character with no back is playable, a character with
        // half a walk is not.
        val script = PoseScript.full(views = listOf(PoseView.FRONT, PoseView.AWAY))
        assertEquals(84, script.steps.size)
        val firstAway = script.steps.indexOfFirst { it.view == PoseView.AWAY }
        assertEquals(42, firstAway)
        assertTrue(script.steps.take(42).all { it.view == PoseView.FRONT })

        // The sheet is still six frames wide: both angles hold the same
        // animation at the same length, and counting across them would plan a
        // sheet twice as wide as the walk it is laying out.
        assertEquals(6, script.frameCounts().values.max())
    }

    @Test
    fun `an away pose is filed under its own key`() {
        // Sharing a key with the front would overwrite it on disk, and the
        // symptom would be a character whose front is its back.
        val front = PoseScript.full().steps.first { it.state == AnimationState.IDLE }
        val away = PoseScript.full(views = listOf(PoseView.AWAY))
            .steps.first { it.state == AnimationState.IDLE }
        assertEquals("idle_0", front.key)
        assertEquals("idle_0_away", away.key)

        // And the away prompt says the face is not visible, which is the whole
        // difference between a back view and the same drawing again.
        assertTrue(away.view.turnClause.contains("not visible"))
        assertTrue(front.view.turnClause.isBlank())
    }

    @Test
    fun `a twelve frame character with an away view plans no empty cells`() {
        // The reported failure, exactly: twelve frames a state with away
        // views, every pose drawn, and the sheet came back saying a hundred
        // and sixty-eight poses could not be read. Counting frames across
        // both views doubled the column count, so every row was planned twice
        // as wide as the animation in it and the second half of each row
        // named poses that were never asked for.
        val frames = AnimationState.entries.associateWith { 12 }
        val script = PoseScript.full(frames, views = listOf(PoseView.FRONT, PoseView.AWAY))
        val drawn = script.steps.map { it.key }.toSet()
        assertEquals(168, drawn.size, "the run itself is 12 x 7 x 2")

        val counts = script.drawnCounts(drawn)
        assertTrue(
            counts.values.all { it == 12 },
            "a state was counted as ${counts.values.distinct()} frames, not 12",
        )

        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = counts,
                views = script.drawnViews(drawn).map { it.keySuffix to it.serves },
            ),
        )
        assertEquals(12, plan.columns)
        assertEquals(14, plan.rows, "seven states, two views")

        // The real assertion: every cell the sheet plans has a pose behind it.
        val orphans = plan.cells.filterNot { it.key in drawn }
        assertTrue(orphans.isEmpty(), "${orphans.size} cells had no pose: ${orphans.take(4)}")
        assertEquals(drawn.size, plan.cells.size)
    }

    @Test
    fun `a half-drawn away block does not narrow the animation`() {
        // An away view one frame short should report that one frame missing,
        // not quietly cut a column off every animation in the sheet.
        val frames = mapOf(AnimationState.IDLE to 6)
        val script = PoseScript.of(
            listOf(AnimationState.IDLE),
            frames,
            views = listOf(PoseView.FRONT, PoseView.AWAY),
        )
        val drawn = script.steps.map { it.key }.toSet() - "idle_5_away"

        assertEquals(6, script.drawnCounts(drawn)[AnimationState.IDLE])
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = script.drawnCounts(drawn),
                views = script.drawnViews(drawn).map { it.keySuffix to it.serves },
            ),
        )
        assertEquals(6, plan.columns)
        assertEquals(listOf("idle_5_away"), plan.cells.map { it.key }.filterNot { it in drawn })
    }

    @Test
    fun `a front-only run plans a front-only sheet`() {
        val script = PoseScript.full(views = listOf(PoseView.FRONT, PoseView.AWAY))
        // Only the front was drawn before the run was stopped.
        val drawn = script.stepsFor(PoseView.FRONT).map { it.key }.toSet()

        assertEquals(listOf(PoseView.FRONT), script.drawnViews(drawn))
        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = script.drawnCounts(drawn),
                views = script.drawnViews(drawn).map { it.keySuffix to it.serves },
            ),
        )
        // Seven rows, not fourteen: planning the away block would leave half
        // the sheet empty and the character would walk north as a hole.
        assertEquals(7, plan.rows)
        assertTrue(plan.cells.all { it.key in drawn })
    }

    @Test
    fun `away art on disk reaches the sheet even when nobody asked for it`() {
        // The failure: the script is built from a toggle, the toggle is UI
        // state that defaults to off and was not restored when a character was
        // reopened -- so a set generated with away frames packed as front-only
        // and the away art, already paid for, was quietly left out.
        //
        // Counting against every angle a character *could* have is what makes
        // the sheet follow the disk instead of following a checkbox.
        val full = PoseScript.full(views = PoseView.entries)
        val drawn = full.steps.map { it.key }.toSet()

        // The front-only script -- what the toggle would have produced -- can
        // see none of the away work.
        val frontOnly = PoseScript.full(views = listOf(PoseView.FRONT))
        assertEquals(listOf(PoseView.FRONT), frontOnly.drawnViews(drawn))
        assertEquals(listOf(PoseView.FRONT, PoseView.AWAY), full.drawnViews(drawn))

        val plan = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = full.drawnCounts(drawn),
                views = full.drawnViews(drawn).map { it.keySuffix to it.serves },
            ),
        )
        assertEquals(14, plan.rows, "the away block was left out of the sheet")
        assertTrue(plan.cells.any { it.key.endsWith("_away") })
    }

    @Test
    fun `every facing reaches art, and the two drawn angles differ`() {
        // The end of the chain: a sheet is only useful if asking it for a
        // direction lands on the right half of it. All four world facings have
        // to resolve, the two that share a drawn angle have to agree, and the
        // front and away pairs have to disagree -- otherwise the back art is
        // on the sheet and nothing ever reads it.
        val full = PoseScript.full(views = PoseView.entries)
        val drawn = full.steps.map { it.key }.toSet()
        val sheet = assertNotNull(
            PoseSheetPlanner.plan(
                id = "t",
                name = "T",
                frameCounts = full.drawnCounts(drawn),
                views = full.drawnViews(drawn).map { it.keySuffix to it.serves },
            ),
        ).sheet

        val walk = assertNotNull(sheet.clip(AnimationState.WALK))
        val frame = walk.firstFrame + 1
        val south = sheet.frameFor(frame, SpriteFacing.SOUTH_EAST)
        val southWest = sheet.frameFor(frame, SpriteFacing.SOUTH_WEST)
        val north = sheet.frameFor(frame, SpriteFacing.NORTH_EAST)
        val northWest = sheet.frameFor(frame, SpriteFacing.NORTH_WEST)

        assertEquals(south, southWest, "the two front facings read different art")
        assertEquals(north, northWest, "the two away facings read different art")
        assertTrue(north != south, "walking away read the front art")
        // And every one of them is a frame the sheet actually has.
        listOf(south, southWest, north, northWest).forEach {
            assertTrue(it in 0 until sheet.columns * sheet.rows, "frame $it is off the sheet")
        }
    }

    @Test
    fun `nothing to draw is no plan`() {
        assertNull(PoseSheetPlanner.plan("t", "T", emptyMap()))
        assertNull(PoseSheetPlanner.plan("t", "T", mapOf(AnimationState.IDLE to 0)))
    }

    @Test
    fun `the plan exists before a single pose is drawn, which is what makes it restartable`() {
        val script = PoseScript.enemy()
        val plan = assertNotNull(
            PoseSheetPlanner.plan("t", "T", script.frameCounts(), cellSize = 96),
        )
        // Every step the script will ask for already has a cell waiting, so a
        // frame that comes back wrong can be replaced on its own.
        assertTrue(script.steps.all { plan.cellFor(it.key) != null })
    }
}

class BasePoseGenerationTest {

    @Test
    fun `the reference is asked for as a T-pose with nothing overlapping`() = runTest {
        val model = RecordingImageModel()
        GenerateBasePoseUseCase(model)(
            BasePoseRequest(subject = "a bronze-masked warrior"),
        ).getOrThrow()

        val request = model.requests.single()
        assertEquals(1024, request.width)
        val prompt = request.prompt
        assertTrue("T-pose" in prompt, prompt)
        assertTrue("Nothing overlapping anything else" in prompt, prompt)
        // An editor can only preserve what it can see.
        assertTrue("fully visible and separated" in prompt, prompt)
    }

    @Test
    fun `the reference is drawn empty handed and at the game's camera`() = runTest {
        val model = RecordingImageModel()
        GenerateBasePoseUseCase(model)(BasePoseRequest(subject = "a warrior")).getOrThrow()

        val prompt = model.requests.single().prompt
        // A reference holding a sword bakes that sword into every pose edited
        // from it, and then the character can never put it down.
        assertTrue("Both hands empty and open" in prompt, prompt)
        assertTrue("isometric game camera" in prompt, prompt)
        // Not the flat hero shot every model reaches for.
        assertTrue("Not a flat front view" in prompt, prompt)
    }

    @Test
    fun `the reference asks for flat chroma rather than pleading for alpha`() = runTest {
        val model = RecordingImageModel()
        GenerateBasePoseUseCase(model)(BasePoseRequest(subject = "a warrior")).getOrThrow()

        val prompt = model.requests.single().prompt
        assertTrue("#00FF00" in prompt, prompt)
        assertTrue("checkerboard" in prompt, prompt)
        // Nothing about grids or cells: this is one drawing.
        assertTrue("cell" !in prompt.lowercase(), prompt)
    }

    @Test
    fun `an empty image is a failure rather than a blank character`() = runTest {
        val model = RecordingImageModel(
            Result.success(GeneratedImage(ByteArray(0), "image/png", 1024, 1024)),
        )
        val result = GenerateBasePoseUseCase(model)(BasePoseRequest(subject = "a warrior"))
        assertTrue(result.isFailure)
    }
}

class PoseFrameGenerationTest {

    private val reference = ImageReference(ByteArray(32) { 3 }, "image/png")

    @Test
    fun `every frame is edited from the reference, not drawn from nothing`() = runTest {
        val model = RecordingImageModel()
        val step = PoseScript.full().stepsFor(AnimationState.WALK).first()

        GeneratePoseFrameUseCase(model)(
            PoseFrameRequest(reference = reference, step = step),
        ).getOrThrow()

        val request = model.requests.single()
        assertEquals(listOf(reference), request.references)
        assertTrue(step.instruction in request.prompt, request.prompt)
    }

    @Test
    fun `the prompt spends itself on what must not change`() = runTest {
        val model = RecordingImageModel()
        GeneratePoseFrameUseCase(model)(
            PoseFrameRequest(
                reference = reference,
                step = PoseScript.full().stepsFor(AnimationState.ATTACK).first(),
            ),
        ).getOrThrow()

        val prompt = model.requests.single().prompt
        assertTrue("The same colours, exactly" in prompt, prompt)
        assertTrue("The same scale" in prompt, prompt)
        assertTrue("Change only the pose" in prompt, prompt)
        // Effects between frames read as flicker, not as polish.
        assertTrue("motion blur" in prompt, prompt)
    }

    @Test
    fun `the camera is nailed down, because every model tested turned the figure sideways`() =
        runTest {
            val model = RecordingImageModel()
            GeneratePoseFrameUseCase(model)(
                PoseFrameRequest(
                    reference = reference,
                    step = PoseScript.full().stepsFor(AnimationState.WALK).first(),
                ),
            ).getOrThrow()

            val prompt = model.requests.single().prompt
            // Asked politely as one bullet among nine, this was ignored by
            // every model: a pose described in legs and arms reads as a request
            // for the angle that shows legs and arms best.
            assertTrue("THE CAMERA DOES NOT MOVE" in prompt, prompt)
            assertTrue("Do not draw a flat front view" in prompt, prompt)
            // And it is the game's camera being held, not just "some angle".
            assertTrue("isometric game camera" in prompt, prompt)
        }

    @Test
    fun `the hands stay empty, even in an attack`() = runTest {
        // The weapon that kept disappearing is no longer here to lose: it is a
        // separate drawing attached at the hand. So the prompt's job flipped --
        // it must stop the model helpfully inventing a sword for the attack
        // pose, which would then clip through the real one.
        val model = RecordingImageModel()
        GeneratePoseFrameUseCase(model)(
            PoseFrameRequest(
                reference = reference,
                step = PoseScript.full().stepsFor(AnimationState.ATTACK).first(),
            ),
        ).getOrThrow()

        val prompt = model.requests.single().prompt
        assertTrue("Empty hands" in prompt, prompt)
        assertTrue("even if the pose is an attack" in prompt, prompt)
        // Still gripping, so the hand has the right shape for something to be
        // attached to it.
        assertTrue("as though holding something" in prompt, prompt)
    }

    @Test
    fun `frames are never chained off each other`() = runTest {
        // Editing frame two from frame one and three from two compounds the
        // drift until the character is somebody else. Every frame comes off the
        // same reference.
        val model = RecordingImageModel()
        val use = GeneratePoseFrameUseCase(model)
        PoseScript.full().stepsFor(AnimationState.WALK).forEach { step ->
            use(PoseFrameRequest(reference = reference, step = step)).getOrThrow()
        }

        assertEquals(6, model.requests.size)
        assertTrue(model.requests.all { it.references == listOf(reference) })
    }

    @Test
    fun `a failed frame names the pose it was for`() = runTest {
        val model = RecordingImageModel(
            Result.success(GeneratedImage(ByteArray(0), "image/png", 1024, 1024)),
        )
        val step = PoseScript.full().stepsFor(AnimationState.DIE)[2]
        val result = GeneratePoseFrameUseCase(model)(
            PoseFrameRequest(reference = reference, step = step),
        )

        assertTrue(result.isFailure)
        assertTrue(step.key in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `a reference compares by its bytes, so what was sent can be asserted`() {
        assertEquals(ImageReference(byteArrayOf(1, 2, 3)), ImageReference(byteArrayOf(1, 2, 3)))
        assertNull(setOf(ImageReference(byteArrayOf(1))).find { it.bytes.size == 2 })
    }
}
