package com.stratum.feature.play.gl

import com.stratum.core.domain.art.CombatCue
import com.stratum.core.domain.art.CombatMoment
import com.stratum.core.domain.art.WorldArtDirector
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.engine.scene.EffectTrack
import com.stratum.engine.world.FeedbackKind
import com.stratum.engine.world.FeedbackMark

/**
 * Turns what the engine reports about a fight into combat theatre.
 *
 * The engine already says what happened — a mark for every hit, crit, block
 * and kill, and the hero's animation for every swing — so nothing new has to
 * be threaded through it. Each new mark and each new swing becomes a cue, and
 * the art director decides what that cue looks like.
 */
internal class CombatTheatre(director: WorldArtDirector) {
    val track = EffectTrack(director)

    private val seen = HashSet<Long>()
    private var lastTime = Float.NaN
    private var lastPlayerState: AnimationState? = null

    fun update(input: Scene3DInput) {
        val now = input.time.elapsedSeconds
        if (!lastTime.isNaN()) track.advance((now - lastTime).coerceIn(0f, MAX_STEP))
        lastTime = now

        input.feedback.forEach { mark ->
            if (!seen.add(mark.id)) return@forEach
            val cue = cueFor(mark) ?: return@forEach
            track.play(cue, mark.origin.x, mark.origin.y, mark.origin.z, input.playerFacingX, input.playerFacingY)
        }
        // Marks are numbered in order and never reused, so forgetting the ones
        // that have expired keeps this set as small as the screen.
        if (seen.size > input.feedback.size * 2 + SEEN_SLACK) seen.retainAll(input.feedback.mapTo(HashSet()) { it.id })

        val state = input.playerAnimation.state
        if (state != lastPlayerState) {
            val moment = when (state) {
                AnimationState.ATTACK -> CombatMoment.SWING
                AnimationState.SPECIAL -> CombatMoment.CAST
                AnimationState.ROLL -> CombatMoment.DASH
                else -> null
            }
            moment?.let {
                track.play(
                    CombatCue(it, color = input.playerAccent ?: SWING_COLOUR, emphasis = 0.6f, onPlayer = true),
                    input.player.x, input.player.y, input.player.z, input.playerFacingX, input.playerFacingY,
                )
            }
            lastPlayerState = state
        }
    }

    private fun cueFor(mark: FeedbackMark): CombatCue? {
        val emphasis = (mark.emphasis / 2f).coerceIn(0f, 1f)
        val moment = when (mark.kind) {
            FeedbackKind.DAMAGE_DEALT -> CombatMoment.HIT
            FeedbackKind.DAMAGE_TAKEN -> CombatMoment.HIT
            FeedbackKind.CRITICAL -> CombatMoment.CRITICAL
            FeedbackKind.BLOCKED -> CombatMoment.BLOCKED
            FeedbackKind.DODGED -> CombatMoment.DODGED
            FeedbackKind.HEAL -> CombatMoment.HEAL
            FeedbackKind.KILL -> CombatMoment.KILL
            FeedbackKind.LEVEL_UP -> CombatMoment.LEVEL_UP
            FeedbackKind.LOOT -> CombatMoment.LOOT_DROP
        }
        return CombatCue(moment, mark.color, emphasis, onPlayer = mark.kind == FeedbackKind.DAMAGE_TAKEN)
    }

    private companion object {
        /** A stalled frame should not fast-forward a whole fight at once. */
        const val MAX_STEP = 0.1f
        const val SEEN_SLACK = 32
        const val SWING_COLOUR = 0xFFFFE2B0
    }
}
