package com.stratum.core.domain.ai

import com.stratum.core.domain.character.CharacterRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.PoseCell
import com.stratum.core.domain.sprite.PoseSheetPlan
import com.stratum.core.domain.sprite.PoseSheetPlanner

/**
 * How many animations to draw.
 *
 * Labelled by what it does rather than by who it is for. It used to be
 * "Enemy" and "Full character", sitting one row above a Hero/Enemy chip row
 * that decides something else entirely -- two chips reading "Enemy", side by
 * side, controlling different things.
 */
enum class PoseScope(val label: String) {
    /** Idle, walk, attack, death: what an enemy is actually seen doing. */
    ENEMY("4 animations"),

    /** Everything, for the character a player looks at all session. */
    FULL("All 7");

    /** The script for this scope at the frame counts and angles chosen. */
    fun scriptFor(frames: Map<AnimationState, Int>, views: List<PoseView>): PoseScript = when (this) {
        ENEMY -> PoseScript.enemy(frames, views)
        FULL -> PoseScript.full(frames, views)
    }

    val states: List<AnimationState>
        get() = when (this) {
            ENEMY -> listOf(AnimationState.IDLE, AnimationState.WALK, AnimationState.ATTACK, AnimationState.DIE)
            FULL -> AnimationState.generatedRowOrder
        }
}

/**
 * Where a character's poses are filed.
 *
 * Derived from what it is, so typing the same description again finds the same
 * set rather than paying for it twice, and from what it is for, so an enemy is
 * filed where the game looks for monsters.
 */
object CharacterSetId {

    fun of(subject: String, role: CharacterRole): String? {
        val slug = subject.lowercase().replace(NON_ID, "_").trim('_').take(MAX_SLUG)
        return if (slug.isBlank()) null else "${role.namespace}$slug"
    }

    private val NON_ID = Regex("[^a-z0-9]+")
    private const val MAX_SLUG = 32
}

/**
 * The movement, in words, for a clip that has no stick figure to follow.
 *
 * Short and plain. A clip is given no per-frame guide, so this is the whole of
 * what it knows about the motion -- and every extra clause is something the
 * model can decide to illustrate with a camera move.
 */
object ClipMotion {
    fun of(state: AnimationState): String = when (state) {
        AnimationState.IDLE -> "A character standing still, breathing, weight settling"
        AnimationState.WALK -> "A steady walk cycle"
        AnimationState.ATTACK -> "One weapon swing, wind-up through follow-through"
        AnimationState.SPECIAL -> "Gathering, then throwing both arms wide"
        AnimationState.HURT -> "Taking a hit and staggering back"
        AnimationState.ROLL -> "A forward roll and back onto the feet"
        AnimationState.DIE -> "Collapsing to the ground and going still"
    }
}

/**
 * Turns what is on disk into the sheet it should be packed as.
 *
 * The forge asked this question twice — once when a run finished and once
 * when the person pressed pack — with two copies of the same arithmetic, and
 * that is how it came to be right in one and wrong in the other. It is one
 * function now, and it answers from the keys on disk alone: what the toggles on
 * the screen happen to say is not what was paid for.
 */
object PosePacking {

    /** The angles that have anything drawn, in sheet order; the front when nothing is. */
    fun drawnViews(drawn: Set<String>): List<PoseView> =
        PoseView.entries.filter { view -> PoseCell.rowLengths(drawn, listOf(view.keySuffix)).isNotEmpty() }
            .ifEmpty { listOf(PoseView.FRONT) }

    /**
     * The plan for packing [drawn], or null when nothing is drawn.
     *
     * Row lengths are read off the keys, not the script: a row cut from a clip
     * is as long as the clip and the rate make it, which a script capped at
     * twelve cannot describe.
     */
    fun planFor(
        setId: String,
        name: String,
        drawn: Set<String>,
        cellSize: Int = PoseSheetPlanner.DEFAULT_CELL,
        frameRate: Int? = null,
    ): PoseSheetPlan? {
        if (drawn.isEmpty()) return null
        val views = drawnViews(drawn)
        val counts = PoseCell.rowLengths(drawn, views.map { it.keySuffix })
        return PoseSheetPlanner.plan(
            id = setId,
            name = name.trim().ifBlank { "Character" },
            frameCounts = counts,
            cellSize = cellSize,
            frameRate = frameRate,
            views = views.map { it.keySuffix to it.serves },
        )
    }

    /**
     * Whether a finished run should pack the sheet on its own.
     *
     * Always when there is no sheet yet — a character that can be worn the
     * moment its frames exist is the point. When there is one already, only if
     * the run left no holes: replacing a working sheet with one that has gaps
     * in it trades something playable for something broken.
     */
    fun shouldAutoPack(outcome: RunOutcome, drawn: Set<String>, hasSheet: Boolean): Boolean =
        outcome.abandonedBecause == null && drawn.isNotEmpty() && (!hasSheet || outcome.failed.isEmpty())
}
