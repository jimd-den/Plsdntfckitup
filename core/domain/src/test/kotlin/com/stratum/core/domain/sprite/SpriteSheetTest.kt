package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnimationClipTest {

    private val walk = AnimationClip(AnimationState.WALK, firstFrame = 4, frameCount = 4, frameDurationMs = 100)
    private val attack = AnimationClip(AnimationState.ATTACK, firstFrame = 8, frameCount = 3, frameDurationMs = 100, loops = false)

    @Test
    fun `a looping clip cycles`() {
        assertEquals(4, walk.frameAt(0))
        assertEquals(5, walk.frameAt(100))
        assertEquals(7, walk.frameAt(300))
        assertEquals(4, walk.frameAt(400), "the loop did not wrap")
        assertEquals(5, walk.frameAt(500))
    }

    @Test
    fun `a one shot clip holds its last frame`() {
        assertEquals(8, attack.frameAt(0))
        assertEquals(10, attack.frameAt(200))
        // An attack that snapped back to the wind-up would read as a second swing.
        assertEquals(10, attack.frameAt(9999))
    }

    @Test
    fun `a one shot clip reports when it is done and a loop never does`() {
        assertFalse(attack.isFinished(100))
        assertTrue(attack.isFinished(300))
        assertFalse(walk.isFinished(999_999))
    }

    @Test
    fun `a single frame clip is stable`() {
        val still = AnimationClip(AnimationState.IDLE, firstFrame = 2, frameCount = 1)
        assertEquals(2, still.frameAt(0))
        assertEquals(2, still.frameAt(5000))
    }

    @Test
    fun `a clip with no frames or no duration is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 2, frameDurationMs = 0)
        }
    }
}

class SpriteSheetTest {

    private val sheet = SpriteSheet(
        id = "test:hero",
        name = "Hero",
        columns = 4,
        rows = 4,
        frameWidth = 48,
        frameHeight = 64,
        clips = listOf(
            AnimationClip(AnimationState.IDLE, 0, 4),
            AnimationClip(AnimationState.WALK, 4, 4),
        ),
    )

    @Test
    fun `frames cut left to right then top to bottom`() {
        assertEquals(FrameRect(0, 0, 48, 64), sheet.frameRect(0))
        assertEquals(FrameRect(144, 0, 48, 64), sheet.frameRect(3))
        assertEquals(FrameRect(0, 64, 48, 64), sheet.frameRect(4))
        assertEquals(FrameRect(48, 128, 48, 64), sheet.frameRect(9))
    }

    @Test
    fun `a frame index outside the sheet is clamped rather than throwing`() {
        assertEquals(sheet.frameRect(15), sheet.frameRect(999))
        assertEquals(sheet.frameRect(0), sheet.frameRect(-5))
    }

    @Test
    fun `a missing clip falls back to idle`() {
        assertEquals(AnimationState.IDLE, sheet.clipOrFallback(AnimationState.DIE)?.state)
        assertEquals(AnimationState.WALK, sheet.clipOrFallback(AnimationState.WALK)?.state)
    }

    @Test
    fun `a sheet with no idle still falls back to something`() {
        val sparse = sheet.copy(clips = listOf(AnimationClip(AnimationState.WALK, 0, 2)))
        assertNotNull(sparse.clipOrFallback(AnimationState.ATTACK))
    }

    @Test
    fun `a sheet with no clips at all returns nothing rather than crashing`() {
        val empty = sheet.copy(clips = emptyList())
        assertEquals(null, empty.clipOrFallback(AnimationState.IDLE))
    }

    @Test
    fun `facing rows offset the frame, and a sheet without them is unaffected`() {
        val faced = sheet.copy(
            facingRows = mapOf(
                SpriteFacing.SOUTH_EAST to 0,
                SpriteFacing.SOUTH_WEST to 1,
                SpriteFacing.NORTH_WEST to 2,
            ),
        )
        assertEquals(2, faced.frameFor(2, SpriteFacing.SOUTH_EAST))
        assertEquals(6, faced.frameFor(2, SpriteFacing.SOUTH_WEST))
        assertEquals(10, faced.frameFor(2, SpriteFacing.NORTH_WEST))
        // No row declared for this facing, and none at all on the plain sheet.
        assertEquals(2, faced.frameFor(2, SpriteFacing.NORTH_EAST))
        assertEquals(2, sheet.frameFor(2, SpriteFacing.SOUTH_WEST))
    }

    @Test
    fun `a sheet whose clips run past the grid is reported as incoherent`() {
        assertTrue(sheet.isCoherent)
        val broken = sheet.copy(clips = listOf(AnimationClip(AnimationState.IDLE, 14, 8)))
        assertFalse(broken.isCoherent, "a clip running off the end was accepted")
    }

    @Test
    fun `a zero sized sheet is incoherent`() {
        assertFalse(sheet.copy(columns = 0).isCoherent)
        assertFalse(sheet.copy(frameWidth = 0).isCoherent)
    }

    @Test
    fun `world directions map onto the four drawn angles`() {
        assertEquals(SpriteFacing.SOUTH_EAST, SpriteFacing.of(1, 0))
        assertEquals(SpriteFacing.SOUTH_WEST, SpriteFacing.of(0, 1))
        assertEquals(SpriteFacing.NORTH_WEST, SpriteFacing.of(-1, 0))
        assertEquals(SpriteFacing.NORTH_EAST, SpriteFacing.of(0, -1))
    }
}

class AnimationPlaybackTest {

    private val sheet = SpriteSheet(
        id = "test:hero", name = "Hero", columns = 4, rows = 3,
        frameWidth = 32, frameHeight = 32,
        clips = listOf(
            AnimationClip(AnimationState.IDLE, 0, 2, 200),
            AnimationClip(AnimationState.WALK, 2, 4, 100),
            AnimationClip(AnimationState.ATTACK, 6, 3, 80, loops = false),
        ),
    )

    @Test
    fun `advancing walks the frames`() {
        var playback = AnimationPlayback(AnimationState.WALK)
        assertEquals(2, playback.frameIn(sheet))
        playback = playback.advanced(100)
        assertEquals(3, playback.frameIn(sheet))
        playback = playback.advanced(100)
        assertEquals(4, playback.frameIn(sheet))
    }

    @Test
    fun `transitioning to the same state does not restart the clock`() {
        val playback = AnimationPlayback(AnimationState.WALK).advanced(250)
        val same = playback.transitionTo(AnimationState.WALK)
        assertEquals(250, same.elapsedMs, "a looping walk was frozen on its first frame")
    }

    @Test
    fun `transitioning to a new state restarts the clock`() {
        val playback = AnimationPlayback(AnimationState.WALK).advanced(250)
        val changed = playback.transitionTo(AnimationState.ATTACK)
        assertEquals(AnimationState.ATTACK, changed.state)
        assertEquals(0, changed.elapsedMs)
    }

    @Test
    fun `a one shot reports finished, a loop does not`() {
        val attack = AnimationPlayback(AnimationState.ATTACK).advanced(300)
        assertTrue(attack.isFinishedIn(sheet))
        val walk = AnimationPlayback(AnimationState.WALK).advanced(10_000)
        assertFalse(walk.isFinishedIn(sheet))
    }
}

class AnimationSelectorTest {

    @Test
    fun `death beats everything`() {
        assertEquals(
            AnimationState.DIE,
            AnimationSelector.select(
                isDead = true, isRolling = true, wasHitRecently = true,
                isAttacking = true, isMoving = true,
            ),
        )
    }

    @Test
    fun `a roll beats being hit, because the roll is why the hit missed`() {
        assertEquals(
            AnimationState.ROLL,
            AnimationSelector.select(isDead = false, isRolling = true, wasHitRecently = true),
        )
    }

    @Test
    fun `being hit interrupts an attack`() {
        assertEquals(
            AnimationState.HURT,
            AnimationSelector.select(isDead = false, wasHitRecently = true, isAttacking = true),
        )
    }

    @Test
    fun `attacking beats walking`() {
        assertEquals(
            AnimationState.ATTACK,
            AnimationSelector.select(isDead = false, isAttacking = true, isMoving = true),
        )
    }

    @Test
    fun `standing still is idle`() {
        assertEquals(AnimationState.IDLE, AnimationSelector.select(isDead = false))
        assertEquals(AnimationState.WALK, AnimationSelector.select(isDead = false, isMoving = true))
    }
}
