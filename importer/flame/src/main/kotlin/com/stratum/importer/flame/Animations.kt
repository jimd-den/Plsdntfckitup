package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.sprite.AnimationState

/** One animation as a source project describes it, before it becomes part of a sheet. */
data class SourceAnimation(
    val name: String,
    val frames: List<ImageRegion>,
    val frameDurationMs: Int,
) {
    init {
        require(frames.isNotEmpty()) { "Animation '$name' has no frames" }
    }
}

/**
 * Reads which engine state an animation is from what its author called it.
 *
 * Projects name the same things many ways -- `run`, `walking`, `PlayerState.moving`
 * -- and the engine drives a fixed set of states, so the names are matched by
 * the words in them. A name that matches nothing is reported, not guessed.
 */
object AnimationNames {

    private val WORDS: List<Pair<AnimationState, List<String>>> = listOf(
        AnimationState.DIE to listOf("die", "death", "dead", "dying"),
        AnimationState.HURT to listOf("hurt", "hit", "damage", "damaged", "pain"),
        AnimationState.SPECIAL to listOf("special", "cast", "casting", "spell", "skill", "magic"),
        AnimationState.ATTACK to listOf("attack", "attacking", "slash", "swing", "strike", "shoot", "melee"),
        AnimationState.ROLL to listOf("roll", "rolling", "dash", "dodge"),
        AnimationState.WALK to listOf("walk", "walking", "run", "running", "move", "moving"),
        AnimationState.IDLE to listOf("idle", "stand", "standing", "rest"),
    )

    /** Directions a project may split one state into; the one facing the camera is preferred. */
    private val FACING_CAMERA = listOf("down", "south", "front", "se", "s")

    fun stateFor(name: String): AnimationState? {
        val words = wordsOf(name)
        return WORDS.firstOrNull { (_, candidates) -> candidates.any(words::contains) }?.first
    }

    /** Of several animations for one state, the one facing the camera, else the first. */
    fun preferred(animations: List<SourceAnimation>): SourceAnimation =
        animations.firstOrNull { anim -> wordsOf(anim.name).any(FACING_CAMERA::contains) } ?: animations.first()

    /** `PlayerState.runLeft` and `run_left` both read as `player state run left`. */
    fun wordsOf(name: String): Set<String> =
        name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z]+")).filter(String::isNotEmpty).toSet()
}
