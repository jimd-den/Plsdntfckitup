package com.stratum.core.domain.sprite

/**
 * What an atlas is going to be used for, and therefore what it has to contain.
 *
 * The requirements differ by role for one reason: how often a player looks at
 * it. A hero is on screen every second of every session, so a reused idle
 * standing in for a walk is noticed within a minute. A rat in a corridor is
 * seen for four seconds, and reusing its idle costs nothing anyone will ever
 * register. Holding both to the hero's bar would stop a playable dungeon from
 * ever being finished; holding both to the rat's puts a sliding hero in it.
 */
enum class ActorRole(
    val label: String,
    val required: Set<AnimationState>,
    val recommended: Set<AnimationState>,
    /**
     * Whether a missing required state may be filled by standing another state
     * in for it. False means the gap has to be real art before this is bound to
     * anything.
     */
    val fallbackAllowed: Boolean,
) {
    HERO(
        label = "Hero",
        required = setOf(AnimationState.IDLE, AnimationState.WALK, AnimationState.ATTACK),
        recommended = setOf(
            AnimationState.SPECIAL,
            AnimationState.HURT,
            AnimationState.ROLL,
            AnimationState.DIE,
        ),
        fallbackAllowed = false,
    ),
    MONSTER(
        label = "Enemy",
        required = setOf(
            AnimationState.IDLE,
            AnimationState.WALK,
            AnimationState.ATTACK,
            AnimationState.DIE,
        ),
        recommended = setOf(AnimationState.HURT),
        fallbackAllowed = true,
    ),
    ELITE(
        label = "Elite",
        required = setOf(
            AnimationState.IDLE,
            AnimationState.WALK,
            AnimationState.ATTACK,
            AnimationState.SPECIAL,
            AnimationState.DIE,
        ),
        recommended = setOf(AnimationState.HURT),
        fallbackAllowed = true,
    ),
    BOSS(
        label = "Boss",
        required = setOf(
            AnimationState.IDLE,
            AnimationState.WALK,
            AnimationState.ATTACK,
            AnimationState.SPECIAL,
            AnimationState.HURT,
            AnimationState.DIE,
        ),
        recommended = emptySet(),
        fallbackAllowed = false,
    ),
    PROP(
        label = "Prop",
        required = setOf(AnimationState.IDLE),
        recommended = setOf(AnimationState.DIE),
        fallbackAllowed = true,
    ),
    PROJECTILE(
        label = "Projectile",
        required = setOf(AnimationState.IDLE),
        recommended = emptySet(),
        fallbackAllowed = true,
    ),
    PICKUP(
        label = "Pickup",
        required = setOf(AnimationState.IDLE),
        recommended = emptySet(),
        fallbackAllowed = true,
    ),
}

/**
 * Which state may stand in for which, best first.
 *
 * Every one of these is a judgement about what a viewer will forgive. A walk
 * played as a held idle reads as gliding, which is odd but legible; an attack
 * played as an idle reads as the swing having no animation at all, which looks
 * broken — so an attack borrows the special before it borrows the idle, because
 * any lunge is better than none. Death falls back to the flinch for the same
 * reason: a body that recoils and then fades is a death, a body that stands
 * there and vanishes is a bug.
 *
 * Idle has no fallback on purpose. An atlas with nothing to show while standing
 * still has no art in it, and inventing a chain for that would only hide it.
 */
object AnimationFallback {

    fun chainFor(state: AnimationState): List<AnimationState> = when (state) {
        AnimationState.IDLE -> emptyList()
        AnimationState.WALK -> listOf(AnimationState.IDLE)
        AnimationState.ATTACK -> listOf(AnimationState.SPECIAL, AnimationState.IDLE)
        AnimationState.SPECIAL -> listOf(AnimationState.ATTACK, AnimationState.IDLE)
        AnimationState.HURT -> listOf(AnimationState.IDLE)
        AnimationState.ROLL -> listOf(AnimationState.WALK, AnimationState.IDLE)
        AnimationState.DIE -> listOf(AnimationState.HURT, AnimationState.IDLE)
    }

    /** The first mapped state that can stand in for [state], or null when none can. */
    fun standInFor(state: AnimationState, mapped: Set<AnimationState>): AnimationState? =
        chainFor(state).firstOrNull { it in mapped }

    /**
     * Every unmapped state filled from the ones that are, keeping each state's
     * own timing.
     *
     * Borrowed frames, not a borrowed clip: a death built from the flinch
     * should hold its last frame for a third of a second rather than flinch at
     * flinch speed, and that only works if the copy owns its duration. States
     * nothing can stand in for are left empty, which the validator then reports
     * — filling silently is how you ship an actor with no art and no warning.
     */
    fun fill(atlas: SpriteAtlas): SpriteAtlas {
        val mapped = atlas.mappedStates
        if (mapped.isEmpty()) return atlas

        var filled = atlas
        for (state in AnimationState.entries) {
            if (state in mapped) continue
            val source = standInFor(state, mapped) ?: continue
            filled = filled.withClip(
                ClipMapping(
                    state = state,
                    frameIds = atlas.clip(source)?.frameIds.orEmpty(),
                    frameDurationMs = state.defaultFrameDurationMs,
                    loops = state !in AnimationState.oneShot,
                    borrowedFrom = source,
                ),
            )
        }
        return filled
    }
}

/** How much trouble an atlas is in. */
enum class ValidationSeverity { READY, WARNING, BLOCKED }

/** One thing worth telling the person, in their words rather than the model's. */
data class SpriteValidationMessage(
    val severity: ValidationSeverity,
    val text: String,
    /** Set when the message is about one state, so the editor can point at its tab. */
    val state: AnimationState? = null,
)

/**
 * What an atlas can and cannot do yet.
 *
 * Opinionated on purpose. An editor that lets anything be saved and then draws
 * nothing in the world has moved the problem from a screen where it could be
 * fixed to one where it can only be wondered about.
 */
data class SpriteValidationReport(
    val severity: ValidationSeverity,
    val messages: List<SpriteValidationMessage>,
    val usableStates: Set<AnimationState>,
    val missingRequired: Set<AnimationState>,
    val missingRecommended: Set<AnimationState>,
    /** What could stand in for each missing state, if the person wants that. */
    val suggestedFallbacks: Map<AnimationState, AnimationState>,
) {
    val isBlocked: Boolean get() = severity == ValidationSeverity.BLOCKED

    /** True when every gap left has something that could fill it in one tap. */
    val fillableGaps: Boolean
        get() = suggestedFallbacks.isNotEmpty() &&
            (missingRequired + missingRecommended).all { it in suggestedFallbacks }
}

object SpriteValidation {

    fun validate(atlas: SpriteAtlas): SpriteValidationReport {
        val messages = mutableListOf<SpriteValidationMessage>()
        val usable = atlas.mappedStates
        val role = atlas.role

        if (atlas.enabledFrames.isEmpty()) {
            messages += SpriteValidationMessage(
                ValidationSeverity.BLOCKED,
                "No frames are switched on, so there is nothing to draw. Slice the image, " +
                    "or turn some cells back on.",
            )
        }

        val missingRequired = role.required - usable
        val missingRecommended = role.recommended - usable
        val fallbacks = (missingRequired + missingRecommended)
            .mapNotNull { state -> AnimationFallback.standInFor(state, usable)?.let { state to it } }
            .toMap()

        for (state in missingRequired.sortedBy { it.ordinal }) {
            val standIn = fallbacks[state]
            messages += when {
                standIn == null -> SpriteValidationMessage(
                    ValidationSeverity.BLOCKED,
                    "${role.label}s need ${name(state)} and nothing here can stand in for it.",
                    state,
                )
                role.fallbackAllowed -> SpriteValidationMessage(
                    ValidationSeverity.WARNING,
                    "No ${name(state)} frames. ${name(standIn).replaceFirstChar { it.uppercase() }} " +
                        "can stand in, which is fine for a ${role.label.lowercase()}.",
                    state,
                )
                else -> SpriteValidationMessage(
                    ValidationSeverity.BLOCKED,
                    "A ${role.label.lowercase()} is on screen constantly, so a reused " +
                        "${name(standIn)} will be noticed. Map real ${name(state)} frames.",
                    state,
                )
            }
        }

        for (state in missingRecommended.sortedBy { it.ordinal }) {
            messages += SpriteValidationMessage(
                ValidationSeverity.WARNING,
                "No ${name(state)} frames. The renderer will improvise, which works but is " +
                    "plainer than real art.",
                state,
            )
        }

        messages += frameProblems(atlas)
        messages += clipProblems(atlas)

        val severity = when {
            messages.any { it.severity == ValidationSeverity.BLOCKED } -> ValidationSeverity.BLOCKED
            messages.any { it.severity == ValidationSeverity.WARNING } -> ValidationSeverity.WARNING
            else -> ValidationSeverity.READY
        }

        return SpriteValidationReport(
            severity = severity,
            messages = messages,
            usableStates = usable,
            missingRequired = missingRequired,
            missingRecommended = missingRecommended,
            suggestedFallbacks = fallbacks,
        )
    }

    private fun frameProblems(atlas: SpriteAtlas): List<SpriteValidationMessage> = buildList {
        val offImage = atlas.enabledFrames.count {
            !it.source.isWithin(atlas.sourceWidth, atlas.sourceHeight)
        }
        if (offImage > 0) {
            add(
                SpriteValidationMessage(
                    ValidationSeverity.WARNING,
                    "$offImage frame${plural(offImage)} run past the edge of the image and will " +
                        "be cut short. Check the grid offset and gutters.",
                ),
            )
        }

        val unused = atlas.enabledFrames.count { frame ->
            atlas.clips.none { frame.id in it.frameIds }
        }
        if (unused > 0 && unused == atlas.enabledFrames.size) {
            add(
                SpriteValidationMessage(
                    ValidationSeverity.BLOCKED,
                    "No frame is mapped to any state yet. Pick a state and tap the frames it " +
                        "should play, in order.",
                ),
            )
        } else if (unused > 0) {
            add(
                SpriteValidationMessage(
                    ValidationSeverity.WARNING,
                    "$unused frame${plural(unused)} are switched on but not in any clip. They " +
                        "will not be exported.",
                ),
            )
        }
    }

    private fun clipProblems(atlas: SpriteAtlas): List<SpriteValidationMessage> = buildList {
        for (clip in atlas.clips) {
            if (clip.isEmpty) continue
            val dangling = clip.frameIds.count { atlas.frame(it)?.enabled != true }
            if (dangling > 0) {
                add(
                    SpriteValidationMessage(
                        ValidationSeverity.WARNING,
                        "${name(clip.state).replaceFirstChar { it.uppercase() }} refers to " +
                            "$dangling frame${plural(dangling)} that are switched off; they will " +
                            "be skipped.",
                        clip.state,
                    ),
                )
            }
            // One frame is a deliberate and useful thing — a held pose, a still
            // prop — everywhere except a walk, where it is the reason a
            // character appears to glide across the floor.
            if (clip.state == AnimationState.WALK && atlas.framesOf(clip.state).size == 1) {
                add(
                    SpriteValidationMessage(
                        ValidationSeverity.WARNING,
                        "The walk is a single frame, so the character will slide rather than " +
                            "step. Two frames already read as walking.",
                        clip.state,
                    ),
                )
            }
        }
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    /** The state as a person would say it, not as the enum spells it. */
    fun name(state: AnimationState): String = when (state) {
        AnimationState.IDLE -> "idle"
        AnimationState.WALK -> "walk"
        AnimationState.ATTACK -> "attack"
        AnimationState.SPECIAL -> "special"
        AnimationState.HURT -> "hurt"
        AnimationState.ROLL -> "roll"
        AnimationState.DIE -> "death"
    }
}

/**
 * Which actors a sheet's art may stand in for, decided by where it is filed.
 *
 * Sheet ids carry a namespace, and three separate places used to test it with
 * their own copy of a string literal: the class picker, and both halves of the
 * resolver. A fourth kind of sheet then arrived -- a character generated one
 * pose at a time -- and was invisible to all of them at once, so a character
 * somebody had spent forty generations on could not be chosen, could not be
 * drawn, and gave no hint why.
 *
 * The first fix let that fourth kind serve either role, on the reasoning that
 * a pose sheet is a full character sheet and so could be used for anything.
 * True of the art and wrong about the game: the player's own character was
 * then also the art for every monster in the world, so a player met five
 * copies of themselves in a field and had no way to say otherwise. A
 * character is drawn for a purpose, and the purpose is the person's to state
 * -- so it is now chosen in the forge and written into the id, and this only
 * reads it back.
 *
 * [POSE] remains for characters generated before the choice existed. They
 * serve as heroes, which is what almost all of them were made to be, and
 * never as monsters -- being wrongly absent from a monster is a character you
 * can still assign by hand, where being wrongly present is a world full of
 * one face.
 */
object SpriteNamespace {

    const val HERO = "hero:"
    const val MONSTER = "monster:"

    /** Characters generated before a character could say what it was for. */
    const val POSE = "pose:"

    fun servesHero(sheetId: String): Boolean =
        sheetId.startsWith(HERO) || sheetId.startsWith(POSE)

    fun servesMonster(sheetId: String): Boolean = sheetId.startsWith(MONSTER)

    /** Whether this is character art at all, rather than a prop or a weapon. */
    fun isCharacter(sheetId: String): Boolean =
        servesHero(sheetId) || servesMonster(sheetId)
}
