package com.stratum.feature.forge

import com.stratum.core.domain.sprite.ActorRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.FacingLayout
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteSheet

/**
 * Everything the mapper screen can ask for.
 *
 * A sealed list rather than thirty lambdas. The editor has a lot of small verbs
 * by its nature, and threading each one through as its own parameter turns
 * every intermediate composable into a switchboard — which is where the
 * mistakes go: one misrouted callback and the flip button trims the sheet.
 */
sealed interface SpriteMapperAction {

    data class Open(val sheet: SpriteSheet) : SpriteMapperAction
    data object Close : SpriteMapperAction
    data object Undo : SpriteMapperAction

    data class SetGrid(
        val columns: Int? = null,
        val rows: Int? = null,
        val offsetX: Int? = null,
        val offsetY: Int? = null,
        val gutterX: Int? = null,
        val gutterY: Int? = null,
    ) : SpriteMapperAction

    /** Sets the grid from the cell size, the way downloaded art is described. */
    data class SetCellSize(val pixels: Int) : SpriteMapperAction

    /** Whether cells are kept square. On for almost every sheet. */
    data class SetSquareCells(val on: Boolean) : SpriteMapperAction

    /**
     * Sets the grid by drawing one cell on the sheet.
     *
     * The numbers are the hard way to describe a grid you can see. Dragging a
     * box around a single frame gives the margin and the cell size at once,
     * and the column and row counts follow from the image.
     */
    data class SetGridFromBox(val rect: SourceRect) : SpriteMapperAction

    data object TrimToContent : SpriteMapperAction

    data class ToggleFrame(val frameId: String) : SpriteMapperAction
    data class FlipFrame(val frameId: String) : SpriteMapperAction
    data class CyclePivot(val frameId: String) : SpriteMapperAction
    data class DuplicateFrame(val frameId: String) : SpriteMapperAction

    /** Opens one frame in the large view, where it can actually be worked on. */
    data class OpenFrame(val frameId: String) : SpriteMapperAction
    data object CloseFrame : SpriteMapperAction
    data class StepFrame(val forward: Boolean) : SpriteMapperAction
    data class SetStep(val pixels: Int) : SpriteMapperAction
    data class NudgeFrame(val dx: Int, val dy: Int) : SpriteMapperAction

    /** Positive amounts push an edge outward, negative pull it in. */
    data class ResizeFrame(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
    ) : SpriteMapperAction

    data class SetFrameRect(val rect: SourceRect) : SpriteMapperAction
    data object SnapFrameToContent : SpriteMapperAction
    data object AddFrame : SpriteMapperAction
    data class RemoveFrame(val frameId: String) : SpriteMapperAction

    data class SelectState(val state: AnimationState) : SpriteMapperAction
    data class AddToClip(val frameId: String) : SpriteMapperAction
    data class RemoveFromClip(val index: Int) : SpriteMapperAction
    data class MoveInClip(val from: Int, val to: Int) : SpriteMapperAction
    data object ClearClip : SpriteMapperAction
    data class SetFps(val fps: Float) : SpriteMapperAction
    data object ToggleLoops : SpriteMapperAction
    data object FillFallbacks : SpriteMapperAction

    data class SetName(val name: String) : SpriteMapperAction
    data class SetRole(val role: ActorRole) : SpriteMapperAction
    data class SetFacing(val facing: FacingLayout) : SpriteMapperAction

    data object SaveDraft : SpriteMapperAction
    data object Bake : SpriteMapperAction
    data object DismissMessage : SpriteMapperAction
}
