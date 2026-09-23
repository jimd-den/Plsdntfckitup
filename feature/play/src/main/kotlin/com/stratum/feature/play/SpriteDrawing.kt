package com.stratum.feature.play

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.stratum.core.domain.sprite.AnimationPlayback
import com.stratum.core.domain.sprite.SpriteFacing

/**
 * The character-sprite drawing the 2D canvas uses, offered to the 3D view.
 *
 * One implementation of "draw this animated character here, this tall", so the
 * two views cannot disagree about clips, weapons, mirroring or hit flashes.
 */
object SpriteDrawing {
    fun DrawScope.draw(
        x: Float,
        groundY: Float,
        height: Float,
        sprite: DrawableSprite,
        playback: AnimationPlayback,
        facing: SpriteFacing,
        flash: Float,
    ) = drawActorSprite(x, groundY, height, sprite, playback, facing, flash, shadow = false)
}
