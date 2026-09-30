package com.stratum.engine.scene

import com.stratum.core.domain.art.CombatCue
import com.stratum.core.domain.art.EffectKind
import com.stratum.core.domain.art.VisualEffect
import com.stratum.core.domain.art.WorldArtDirector
import kotlin.math.cos
import kotlin.math.sin

/** One piece of combat theatre playing at a place in the world. */
data class ActiveEffect(
    val effect: VisualEffect,
    val x: Float,
    val y: Float,
    val z: Float,
    /** Which way the one who caused it was facing; arcs and afterimages use it. */
    val facingX: Float = 0f,
    val facingY: Float = 1f,
    val age: Float = 0f,
    /** Scatters debris and shakes differently for each effect, deterministically. */
    val seed: Int = 0,
) {
    val isOver: Boolean get() = age >= effect.duration

    /** 0 at birth, 1 at the end. */
    val progress: Float get() = if (effect.duration <= 0f) 1f else (age / effect.duration).coerceIn(0f, 1f)
}

/**
 * The combat effects currently playing.
 *
 * The art director says what a moment of combat looks like — a ring, a flash,
 * a shake — and this keeps those going for their duration. It holds no
 * drawing: [SceneBuilder] turns [active] into decals and glows, and the camera
 * reads [shake]. Pure and clocked by the caller, so a fight replays the same
 * way in a test, a preview image and on a phone.
 */
class EffectTrack(
    private val director: WorldArtDirector,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val live = ArrayList<ActiveEffect>()
    private var nextSeed = 1

    val active: List<ActiveEffect> get() = live

    /** Starts everything the director has for [cue], at a point in the world. */
    fun play(cue: CombatCue, x: Float, y: Float, z: Float, facingX: Float = 0f, facingY: Float = 1f) {
        director.effectsFor(cue).forEach { effect ->
            // Numbers are text, and text is the overlay's job.
            if (effect.kind == EffectKind.NUMBER) return@forEach
            live += ActiveEffect(effect, x, y, z, facingX, facingY, 0f, nextSeed++)
        }
        // Drop the oldest: the newest hit is the one the player is looking at.
        while (live.size > capacity) live.removeAt(0)
    }

    fun advance(deltaSeconds: Float) {
        if (deltaSeconds <= 0f) return
        for (i in live.indices) live[i] = live[i].copy(age = live[i].age + deltaSeconds)
        live.removeAll { it.isOver }
    }

    fun clear() = live.clear()

    /**
     * How far the camera is shoved right now, in blocks.
     *
     * A fast shudder that dies away quadratically: big at the moment of the
     * hit, gone before it gets in the way of aiming the next one.
     */
    fun shake(): Vec3 {
        var sx = 0f; var sy = 0f; var sz = 0f
        live.forEach { active ->
            if (active.effect.kind != EffectKind.SCREEN_SHAKE) return@forEach
            val fade = (1f - active.progress).let { it * it }
            val amount = active.effect.intensity * SHAKE_AMPLITUDE * fade
            val phase = active.age * SHAKE_FREQUENCY + active.seed * 1.7f
            sx += sin(phase) * amount
            sy += cos(phase * 1.31f) * amount
            sz += sin(phase * 0.77f + 1f) * amount * 0.4f
        }
        return Vec3(sx, sy, sz)
    }

    companion object {
        const val DEFAULT_CAPACITY = 48
        const val SHAKE_AMPLITUDE = 0.35f
        const val SHAKE_FREQUENCY = 55f
    }
}
