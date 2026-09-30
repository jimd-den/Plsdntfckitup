package com.stratum.core.domain.sprite

/**
 * What an actor is doing, for animation purposes.
 *
 * A fixed set rather than pack data: the engine drives these from simulation
 * state, so a pack inventing a seventh state would have nothing to trigger it.
 * A pack that lacks frames for one of these falls back to [IDLE].
 */
enum class AnimationState {
    IDLE,
    WALK,
    ATTACK,

    /**
     * A skill, as opposed to a basic swing. Separate because a class's power
     * should not look like its ordinary attack — if the two read the same, the
     * resource you spent bought nothing you can see.
     */
    SPECIAL,
    HURT,
    ROLL,
    DIE;

    /**
     * How long one frame of this state should hold.
     *
     * Lives on the state rather than in each layout so a sheet re-cut from what
     * a model actually drew animates at the same speed as one cut as asked.
     */
    val defaultFrameDurationMs: Int
        get() = when (this) {
            IDLE -> 200
            WALK -> 110
            ATTACK -> 70
            SPECIAL -> 80
            HURT -> 90
            ROLL -> 60
            DIE -> 130
        }

    companion object {
        /** States that play once and stop rather than looping. */
        val oneShot = setOf(ATTACK, SPECIAL, HURT, DIE)

        /**
         * The order rows are drawn in on a generated sheet, most useful first.
         *
         * A sheet with fewer rows than this simply stops early, which is why
         * idle and walk come before the ones a character can do without.
         */
        val generatedRowOrder = listOf(IDLE, WALK, ATTACK, SPECIAL, HURT, ROLL, DIE)
    }
}

/** Which way an actor is drawn. Four is enough for a 2:1 isometric view. */
enum class SpriteFacing {
    SOUTH_EAST,
    SOUTH_WEST,
    NORTH_WEST,
    NORTH_EAST;

    /**
     * Whether this facing should be drawn flipped.
     *
     * A generated sheet reliably contains one facing, not four. Mirroring buys
     * the other side for nothing, and at a three-quarter camera a mirrored
     * character reads correctly — which is cheaper and more dependable than
     * asking a model for four consistent angles of the same figure.
     */
    val mirrored: Boolean get() = this == SOUTH_WEST || this == NORTH_WEST

    companion object {
        /**
         * Maps a world direction onto the four drawn angles. North and west both
         * read as "away", which is why four sprites cover eight directions
         * convincingly at this camera angle.
         */
        fun of(dx: Int, dy: Int): SpriteFacing = when {
            dx > 0 && dy >= 0 -> SOUTH_EAST
            dx <= 0 && dy > 0 -> SOUTH_WEST
            dx < 0 && dy <= 0 -> NORTH_WEST
            else -> NORTH_EAST
        }
    }
}

/**
 * One animation: a run of frames at a fixed rate.
 *
 * Frames are indices into the sheet's grid, read left to right and top to
 * bottom, so a pack describes a row rather than listing coordinates.
 */
data class AnimationClip(
    val state: AnimationState,
    val firstFrame: Int,
    val frameCount: Int,
    val frameDurationMs: Int = 120,
    val loops: Boolean = true,
    /**
     * The state this clip's frames were really drawn for, when they were
     * borrowed rather than drawn for this one.
     *
     * Carried all the way to the renderer so it can compensate. A death made of
     * idle frames needs the body to sag and fade or it reads as a bug, and the
     * only place that is knowable is here -- by the time the pixels are on
     * screen, borrowed frames and drawn ones look identical.
     */
    val standsInFor: AnimationState? = null,
) {
    init {
        require(frameCount > 0) { "Clip for $state has no frames" }
        require(frameDurationMs > 0) { "Clip for $state has a zero frame duration" }
    }

    val durationMs: Int get() = frameCount * frameDurationMs

    /**
     * Which frame to show after [elapsedMs].
     *
     * A one-shot clip holds its last frame rather than snapping back, so an
     * attack ends on the follow-through instead of the wind-up.
     */
    fun frameAt(elapsedMs: Long): Int {
        if (frameCount == 1) return firstFrame
        val step = if (loops) {
            ((elapsedMs / frameDurationMs) % frameCount).toInt()
        } else {
            ((elapsedMs / frameDurationMs).toInt()).coerceAtMost(frameCount - 1)
        }
        return firstFrame + step
    }

    fun isFinished(elapsedMs: Long): Boolean = !loops && elapsedMs >= durationMs
}

/**
 * A sheet of frames plus the clips that index into it.
 *
 * Holds no image data: the bytes live wherever the platform keeps them, keyed
 * by [id]. That is what lets this stay in the pure domain and be generated,
 * validated and tested without a bitmap.
 */
data class SpriteSheet(
    val id: String,
    val name: String,
    val columns: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val clips: List<AnimationClip> = emptyList(),
    /** Present when the sheet has a row per facing; null means one row serves all. */
    val facingRows: Map<SpriteFacing, Int> = emptyMap(),
    /**
     * Whether the far side may be drawn by flipping this art.
     *
     * True for almost everything, because a mirrored figure reads correctly at
     * a three-quarter camera and costs nothing. False for art carrying a
     * readable asymmetry -- a glyph, a banner, lettering on a prop -- where the
     * flip is immediately legible as a mistake rather than as a second angle.
     */
    val mirrorsFacings: Boolean = true,
    val origin: SpriteOrigin = SpriteOrigin.PACK,
) {
    val frameCount: Int get() = columns * rows

    fun clip(state: AnimationState): AnimationClip? = clips.firstOrNull { it.state == state }

    /** The clip for a state, falling back to idle, falling back to anything. */
    fun clipOrFallback(state: AnimationState): AnimationClip? =
        clip(state) ?: clip(AnimationState.IDLE) ?: clips.firstOrNull()

    /** Grid position of a frame index, for the renderer to cut. */
    fun frameRect(frame: Int): FrameRect {
        val safe = frame.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        return FrameRect(
            left = (safe % columns) * frameWidth,
            top = (safe / columns) * frameHeight,
            width = frameWidth,
            height = frameHeight,
        )
    }

    /**
     * Adds the row offset for a facing, when the sheet has one. Sheets without
     * per-facing rows return the frame unchanged, so a one-row sheet still
     * animates -- it just faces the same way whichever direction you walk.
     */
    fun frameFor(frame: Int, facing: SpriteFacing): Int {
        val row = facingRows[facing] ?: return frame
        return frame + row * columns
    }

    /**
     * The same sheet read on a different grid.
     *
     * Used when the image that came back is not laid out the way it was asked
     * for. Clips are rebuilt from the rows that actually exist rather than
     * carried over, because a clip pointing at a frame the new grid does not
     * have would be cut from whatever happens to be there.
     */
    fun recut(grid: SheetGrid, imageWidth: Int, imageHeight: Int): SpriteSheet = copy(
        columns = grid.columns,
        rows = grid.rows,
        frameWidth = (imageWidth / grid.columns).coerceAtLeast(1),
        frameHeight = (imageHeight / grid.rows).coerceAtLeast(1),
        clips = clipsForGrid(grid),
        facingRows = emptyMap(),
    )

    /** A sheet whose grid does not match its declared frames cannot be cut. */
    val isCoherent: Boolean
        get() = columns > 0 && rows > 0 && frameWidth > 0 && frameHeight > 0 &&
            clips.all { it.firstFrame + it.frameCount <= frameCount }
}

/**
 * One clip per row, in the canonical order, for a grid we discovered rather
 * than asked for.
 *
 * A single cell is a still: one idle frame that does not loop anywhere. That is
 * the honest reading of a model that drew a picture instead of a sheet, and it
 * is far better than pretending the picture has frames in it.
 */
fun clipsForGrid(grid: SheetGrid): List<AnimationClip> {
    if (grid.columns <= 0 || grid.rows <= 0) return emptyList()
    if (grid.cells == 1) {
        return listOf(
            AnimationClip(
                state = AnimationState.IDLE,
                firstFrame = 0,
                frameCount = 1,
                frameDurationMs = AnimationState.IDLE.defaultFrameDurationMs,
            ),
        )
    }
    return AnimationState.generatedRowOrder.take(grid.rows).mapIndexed { row, state ->
        AnimationClip(
            state = state,
            firstFrame = row * grid.columns,
            frameCount = grid.columns,
            frameDurationMs = state.defaultFrameDurationMs,
            loops = state !in AnimationState.oneShot,
        )
    }
}

enum class SpriteOrigin { PACK, AI_GENERATED, IMPORTED }

data class FrameRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where an actor is in its animation.
 *
 * Immutable and advanced by the caller, so the renderer never owns timing and
 * two actors in the same state stay independent.
 */
data class AnimationPlayback(
    val state: AnimationState = AnimationState.IDLE,
    val elapsedMs: Long = 0L,
) {
    fun advanced(deltaMs: Long): AnimationPlayback = copy(elapsedMs = elapsedMs + deltaMs)

    /**
     * Switches state, restarting the clock only when the state actually changes.
     * Restarting on every call would freeze a looping walk on its first frame.
     */
    fun transitionTo(next: AnimationState): AnimationPlayback =
        if (next == state) this else AnimationPlayback(next, 0L)

    fun frameIn(sheet: SpriteSheet): Int {
        val clip = sheet.clipOrFallback(state) ?: return 0
        return clip.frameAt(elapsedMs)
    }

    fun isFinishedIn(sheet: SpriteSheet): Boolean {
        val clip = sheet.clip(state) ?: return true
        return clip.isFinished(elapsedMs)
    }
}

/**
 * Chooses the animation state from what an actor is actually doing.
 *
 * One place, shared by the player and by monsters, so a monster being hit and a
 * player being hit never drift apart.
 */
object AnimationSelector {

    fun select(
        isDead: Boolean,
        isRolling: Boolean = false,
        wasHitRecently: Boolean = false,
        isAttacking: Boolean = false,
        isCasting: Boolean = false,
        isMoving: Boolean = false,
    ): AnimationState = when {
        isDead -> AnimationState.DIE
        isRolling -> AnimationState.ROLL
        // Being hit interrupts an attack: the flinch is the more urgent
        // information, because it tells the player they are losing the trade.
        wasHitRecently -> AnimationState.HURT
        // A skill outranks a swing: it costs something, so it should be the
        // thing you see when both are in flight.
        isCasting -> AnimationState.SPECIAL
        isAttacking -> AnimationState.ATTACK
        isMoving -> AnimationState.WALK
        else -> AnimationState.IDLE
    }
}
