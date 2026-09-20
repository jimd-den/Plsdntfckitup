package com.stratum.core.domain.sprite

import com.stratum.core.domain.ai.SheetLayout

enum class SpriteTarget(
    val label: String,
    val namespace: String,
    /** Whether this target draws one named action, so the screen offers a choice of it. */
    val usesAction: Boolean = false,
) {
    /** The character you play. Worth the denser sheet and the extra frames. */
    HERO("Hero", SpriteNamespace.HERO),

    /** A monster fights and dies; it does not need a signature power. */
    MONSTER("Monster", SpriteNamespace.MONSTER),

    /**
     * Six frames of one action, and the most likely of these to come back
     * usable. A full sheet asks a model to hold a character consistent across
     * forty-two cells and seven activities; this asks for six cells of one.
     */
    ACTION("One action", SpriteNamespace.ACTION, usesAction = true),

    /**
     * One drawing. What a weak model produces anyway -- asked for deliberately,
     * so it arrives composed and centred rather than as a grid-shaped accident.
     */
    POSE("One pose", SpriteNamespace.POSE),
    ;

    fun layoutFor(action: AnimationState): SheetLayout = when (this) {
        HERO -> SheetLayout.detailed()
        MONSTER -> SheetLayout.standard()
        ACTION -> SheetLayout.action(action)
        POSE -> SheetLayout.pose()
    }
}
