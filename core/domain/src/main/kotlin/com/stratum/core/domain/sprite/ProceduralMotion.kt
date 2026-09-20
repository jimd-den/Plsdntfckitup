package com.stratum.core.domain.sprite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A transform laid over a frame at draw time.
 *
 * Fractions of the drawn sprite rather than pixels, so the same values read the
 * same at any zoom. [offsetX] is positive towards the direction the sprite
 * faces, which the renderer gets for free by applying the offset before it
 * mirrors.
 */
data class SpriteEmbellishment(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
) {
    val isIdentity: Boolean
        get() = offsetX == 0f && offsetY == 0f && scaleX == 1f && scaleY == 1f && alpha == 1f

    companion object {
        val none = SpriteEmbellishment()
    }
}

/**
 * Motion the renderer supplies when the art does not.
 *
 * This is the other half of the fallback rules. Letting a rat's death borrow
 * its idle keeps a dungeon shippable, but on its own it ships a rat that stands
 * still and then vanishes — which reads as a bug, not as a death, and is worse
 * than the missing animation it was meant to paper over. A body that recoils,
 * sags and fades reads as dying even when every frame of it is the idle pose.
 *
 * The rule that keeps this honest is the guard: nothing is applied to a clip
 * that has real frames behind it. Embellishing actual art would fight the
 * animator — a drawn attack does not need a procedural lunge on top of it, and
 * getting both is how a game ends up looking like it is made of rubber. So this
 * fires only where there is nothing to fight: a clip standing in for another,
 * or a single frame held as a still.
 */
object ProceduralMotion {

    /**
     * What to lay over the current frame, or nothing at all.
     *
     * [standsIn] is true when the clip being played was mapped for a different
     * state, whether a person filled it in the editor or the renderer
     * substituted it at draw time.
     */
    fun forFrame(
        state: AnimationState,
        elapsedMs: Long,
        clipDurationMs: Int,
        frameCount: Int,
        standsIn: Boolean,
    ): SpriteEmbellishment {
        // Real animation, drawn by someone, playing as itself. Leave it alone.
        if (!standsIn && frameCount > 1) return SpriteEmbellishment.none

        val progress = if (clipDurationMs <= 0) {
            0f
        } else {
            (elapsedMs.toFloat() / clipDurationMs).coerceIn(0f, 1f)
        }
        // One rise and fall across a one-shot: a lunge that starts and ends
        // where the body is, rather than snapping back.
        val arc = sin(progress * PI.toFloat())

        return when (state) {
            AnimationState.IDLE -> breathe(elapsedMs)
            AnimationState.WALK -> step(elapsedMs)
            AnimationState.ATTACK -> SpriteEmbellishment(
                offsetX = LUNGE * arc,
                scaleX = 1f + SWELL * arc,
                scaleY = 1f + SWELL * arc,
            )
            // Bigger and lifted rather than further forward: a skill should not
            // look like a harder version of the same swing.
            AnimationState.SPECIAL -> SpriteEmbellishment(
                offsetY = -RISE * arc,
                scaleX = 1f + SWELL * 2f * arc,
                scaleY = 1f + SWELL * 2f * arc,
            )
            // Backwards, because the flinch has to say who won the trade. The
            // white flash the renderer already draws does the rest.
            AnimationState.HURT -> SpriteEmbellishment(offsetX = -RECOIL * arc)
            AnimationState.ROLL -> SpriteEmbellishment(
                offsetY = SQUASH * arc,
                scaleX = 1f + SQUASH * arc,
                scaleY = 1f - SQUASH * arc,
            )
            AnimationState.DIE -> collapse(progress)
        }
    }

    /**
     * A held still, breathing.
     *
     * Two percent of the sprite's height. It is deliberately almost invisible:
     * the job is to stop a character reading as a decal stuck to the floor, and
     * anything large enough to notice as motion is large enough to notice as
     * wrong.
     */
    private fun breathe(elapsedMs: Long): SpriteEmbellishment {
        val phase = wave(elapsedMs, BREATH_MS)
        return SpriteEmbellishment(
            offsetY = -BREATH * (0.5f + 0.5f * phase),
            scaleY = 1f + BREATH * 0.5f * phase,
        )
    }

    /**
     * A walk with no walk frames.
     *
     * The body rises on each step and falls between them, so the legs being
     * identical matters less than it should. Absolute value gives two bounces
     * per cycle -- one per foot -- which is what stops it reading as a float.
     */
    private fun step(elapsedMs: Long): SpriteEmbellishment {
        val bounce = abs(wave(elapsedMs, STEP_MS))
        return SpriteEmbellishment(
            offsetY = -BOB * bounce,
            scaleY = 1f - BOB * 0.5f * bounce,
        )
    }

    /**
     * A death built from a pose that is not one.
     *
     * Sinking, spreading and fading together. Fading alone reads as a bug in
     * the renderer; sinking alone reads as falling through the floor. The
     * remaining alpha is deliberately not zero, because the engine decides when
     * a corpse leaves, and a sprite that has already erased itself cannot be
     * shown for the rest of that.
     */
    private fun collapse(progress: Float): SpriteEmbellishment = SpriteEmbellishment(
        offsetY = SINK * progress,
        scaleX = 1f + SPREAD * progress,
        scaleY = 1f - FLATTEN * progress,
        alpha = 1f - FADE * progress,
    )

    private fun wave(elapsedMs: Long, periodMs: Int): Float =
        sin((elapsedMs % periodMs) / periodMs.toFloat() * 2f * PI.toFloat())

    /** How far forward a borrowed attack throws the body. */
    private const val LUNGE = 0.22f
    private const val RECOIL = 0.18f
    private const val RISE = 0.12f
    private const val SWELL = 0.06f
    private const val SQUASH = 0.2f
    private const val BOB = 0.05f
    private const val BREATH = 0.02f
    private const val SINK = 0.25f
    private const val SPREAD = 0.15f
    private const val FLATTEN = 0.55f
    private const val FADE = 0.75f
    private const val BREATH_MS = 1_800
    private const val STEP_MS = 520
}
