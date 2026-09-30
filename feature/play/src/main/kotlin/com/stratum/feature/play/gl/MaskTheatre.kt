package com.stratum.feature.play.gl

import com.stratum.engine.model.mask.AfricanMaskCodec
import com.stratum.engine.model.mask.CharacterMasks
import com.stratum.engine.model.mask.MaskCharacters
import com.stratum.engine.model.mask.MaskCodec
import com.stratum.engine.model.mask.MaskCue
import com.stratum.engine.scene.SpiritInstance
import com.stratum.engine.world.FeedbackKind
import com.stratum.feature.play.MaskLooks
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The characters as masks: the hero and every monster drawn as floating,
 * glowing mask spirits instead of sprite billboards.
 *
 * Like [CombatTheatre] it reads only what the engine already reports -- where
 * each body is, its animation state, its hit flash and knockback, a monster's
 * wind-up, and the feedback marks for crits, blocks and kills -- and turns
 * them into motion ([MaskCharacters]). The simulation is untouched: the
 * ground shadow and rank ring are still laid at the true position, and a
 * mask is only ever drawn a hand's width from it.
 *
 * A monster whose mask is still being built (a few milliseconds, off the UI
 * thread) draws as it always did until it is ready.
 */
internal class MaskTheatre(looks: MaskLooks) {
    private val builder: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mask-spirit-mesher").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val masks = MaskCharacters(looks.profiles, builder)
    private val maskOf = looks.masks
    private val motionOf = looks.motions

    private val seenMarks = HashSet<Long>()
    private val drawn = HashSet<String>()
    private var lastTime = Float.NaN
    private var heroCode: String? = UNSET

    /** What to draw this frame. */
    val spirits: List<SpiritInstance> get() = masks.spirits

    /** True when [actorId] is drawn as a mask this frame, so its sprite and stand-in body are not. */
    fun drawsAsMask(actorId: String): Boolean = actorId in drawn

    fun update(input: Scene3DInput) {
        val now = input.time.elapsedSeconds
        val dt = if (lastTime.isNaN()) 0f else now - lastTime
        lastTime = now

        if (input.heroMask != heroCode) {
            heroCode = input.heroMask
            // A mask from the mask maker, or an older sculpted mask's genome code.
            val art = input.heroMask?.takeIf(AfricanMaskCodec::isCode)?.let { AfricanMaskCodec.decode(it, "Hero") }
            masks.heroArt = art
            masks.heroGenome = if (art != null) CharacterMasks.DEFAULT_HERO
            else input.heroMask?.let { MaskCodec.decode(it.removePrefix(MaskCodec.TAG_PREFIX), "Hero") } ?: CharacterMasks.DEFAULT_HERO
        }

        masks.begin()
        drawn.clear()
        val p = input.player
        // The hero faces up to the nearest monster in reach: that is what a swing will strike.
        var aimX = Float.NaN; var aimY = Float.NaN; var best = AIM_REACH * AIM_REACH
        for (enemy in input.enemies) {
            if (!enemy.isAlive) continue
            val dx = enemy.position.x - p.x; val dy = enemy.position.y - p.y
            val d = dx * dx + dy * dy
            if (d < best) { best = d; aimX = enemy.position.x; aimY = enemy.position.y }
        }
        masks.hero(
            PLAYER, p.x, p.y, p.z, input.playerFacingX, input.playerFacingY,
            input.playerAnimation.state, input.playerFlash, input.impactFor(PLAYER), aimX, aimY,
        )?.let { drawn += PLAYER }

        for (enemy in input.enemies) {
            if (!enemy.isAlive) continue
            val body = masks.monster(
                enemy.instanceId, enemy.definitionId, enemy.rank, enemy.position.x, enemy.position.y, enemy.position.z, enemy.facingX, enemy.facingY,
                input.animationFor(enemy.instanceId).state, input.flashFor(enemy.instanceId), input.impactFor(enemy.instanceId),
                casting = enemy.casting != null, aimX = p.x, aimY = p.y,
                maskOverride = maskOf[enemy.definitionId], motionOverride = motionOf[enemy.definitionId],
            )
            if (body != null) drawn += enemy.instanceId
        }

        // Crits, blocks and kills, from the feedback the fight already shows as numbers.
        for (mark in input.feedback) {
            if (!seenMarks.add(mark.id)) continue
            val o = mark.origin
            when (mark.kind) {
                FeedbackKind.CRITICAL -> masks.cue(MaskCue.CRIT, o.x, o.y, fromX = p.x, fromY = p.y, power = (mark.emphasis / 2f).coerceIn(0.5f, 1.5f))
                FeedbackKind.BLOCKED -> masks.cue(MaskCue.BLOCK, o.x, o.y)
                FeedbackKind.KILL -> masks.cue(MaskCue.DIE, o.x, o.y)
                else -> Unit
            }
        }
        if (seenMarks.size > input.feedback.size * 2 + SEEN_SLACK) seenMarks.retainAll(input.feedback.mapTo(HashSet()) { it.id })

        masks.advance(dt.coerceIn(0f, MAX_STEP))
    }

    fun release() {
        builder.shutdownNow()
        masks.clear()
    }

    private companion object {
        const val PLAYER = "player"
        const val AIM_REACH = 5f
        const val MAX_STEP = 0.1f
        const val SEEN_SLACK = 32
        const val UNSET = "\u0000unset"
    }
}
