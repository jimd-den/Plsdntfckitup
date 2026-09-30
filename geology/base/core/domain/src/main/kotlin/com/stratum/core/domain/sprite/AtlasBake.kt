package com.stratum.core.domain.sprite

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One frame's move from the source image into the baked sheet.
 *
 * [flippedX] is carried rather than applied because flipping is a pixel
 * operation and this plan has no pixels. Baking the flip in at this point —
 * rather than leaving it to the renderer — is deliberate: the renderer already
 * spends its mirror on facing, and a frame that had to be flipped *and* mirror
 * for the far side would need two flags where it now needs none.
 */
data class BakedFrame(
    val frameId: String,
    val source: SourceRect,
    val flippedX: Boolean,
    /** Where it lands in the baked sheet, already aligned by its pivot. */
    val destination: FrameRect,
    /** Which cell of the baked grid, for a person checking the plan by eye. */
    val cell: Int,
)

/**
 * Everything needed to turn an atlas into a sheet the engine can already draw.
 *
 * The [sheet] is an ordinary [SpriteSheet]: a uniform grid with contiguous
 * clips. That is the whole point of baking. The renderer, the library, the
 * playback clock and the save format all keep working untouched, and none of
 * them ever learns that a person moved cells around by hand — the mapping is a
 * thing that happened before the asset existed, not a thing the engine carries
 * at runtime.
 */
data class AtlasBakePlan(
    val sheet: SpriteSheet,
    val frames: List<BakedFrame>,
    val sheetWidth: Int,
    val sheetHeight: Int,
    /**
     * Applied to every frame on the way in. Below 1 only when the mapped art is
     * so large the sheet would not fit in a texture.
     */
    val scale: Float,
) {
    val isEmpty: Boolean get() = frames.isEmpty()
}

/**
 * Packs a hand-mapped atlas into a uniform sheet.
 *
 * Two decisions carry this, and both trade space for something worth more.
 *
 * One row per clip, padded. It wastes the cells at the end of a short row, and
 * in exchange the baked sheet is *legible*: a person exporting it and opening
 * it in an image editor sees idle on the first line and death on the last,
 * which is what every sprite sheet they have ever seen looks like. Packing
 * tightly would save a few hundred kilobytes and make the result unreadable.
 *
 * A frame used by two clips is copied into both. It costs a duplicate cell, and
 * it is what lets a clip stay a contiguous run — which is what [AnimationClip]
 * is, which is what the renderer already knows how to play.
 */
object AtlasBaker {

    fun plan(
        atlas: SpriteAtlas,
        /** Overrides the cell size, in source pixels. Null measures the mapped frames. */
        cellWidth: Int? = null,
        cellHeight: Int? = null,
    ): AtlasBakePlan? {
        val rows = AnimationState.generatedRowOrder
            .mapNotNull { state -> atlas.framesOf(state).takeIf { it.isNotEmpty() }?.let { state to it } }
        if (rows.isEmpty()) return null

        val columns = rows.maxOf { it.second.size }
        val used = rows.flatMap { it.second }
        val sourceCellWidth = (cellWidth ?: used.maxOf { it.source.width }).coerceAtLeast(1)
        val sourceCellHeight = (cellHeight ?: used.maxOf { it.source.height }).coerceAtLeast(1)

        // A sheet no device will hand back as a texture is not a sheet. The art
        // has to shrink to fit, and shrinking every frame by the same factor is
        // the only version of that which does not change how the animation
        // reads.
        val scale = min(
            1f,
            MAX_SHEET_EDGE.toFloat() /
                max(sourceCellWidth * columns, sourceCellHeight * rows.size).coerceAtLeast(1),
        )
        val cellW = (sourceCellWidth * scale).roundToInt().coerceAtLeast(1)
        val cellH = (sourceCellHeight * scale).roundToInt().coerceAtLeast(1)

        val baked = mutableListOf<BakedFrame>()
        val clips = mutableListOf<AnimationClip>()

        rows.forEachIndexed { row, (state, frames) ->
            frames.forEachIndexed { column, frame ->
                val cell = row * columns + column
                baked += BakedFrame(
                    frameId = frame.id,
                    source = frame.source,
                    flippedX = frame.flippedX,
                    destination = place(frame, column * cellW, row * cellH, cellW, cellH, scale),
                    cell = cell,
                )
            }
            val mapping = atlas.clip(state)
            clips += AnimationClip(
                state = state,
                firstFrame = row * columns,
                frameCount = frames.size,
                frameDurationMs = mapping?.frameDurationMs ?: state.defaultFrameDurationMs,
                loops = mapping?.loops ?: (state !in AnimationState.oneShot),
                standsInFor = mapping?.borrowedFrom,
            )
        }

        return AtlasBakePlan(
            sheet = SpriteSheet(
                id = atlas.id,
                name = atlas.name,
                columns = columns,
                rows = rows.size,
                frameWidth = cellW,
                frameHeight = cellH,
                clips = clips,
                // Baking flattens the facing rows away: a hand-mapped atlas
                // describes one drawn angle, and the far side is the renderer's
                // mirror rather than art anyone had to supply.
                facingRows = emptyMap(),
                mirrorsFacings = atlas.facing == FacingLayout.MIRRORED,
                origin = atlas.origin,
            ),
            frames = baked,
            sheetWidth = columns * cellW,
            sheetHeight = rows.size * cellH,
            scale = scale,
        )
    }

    /**
     * Where one frame sits inside its cell.
     *
     * The pivot does the work. Frames trimmed to their content are all
     * different sizes, and dropping each one at its cell's top-left would make
     * a walk cycle jitter in proportion to how tightly each pose was cropped.
     * Hanging them all from the same point — the feet, by default — is what
     * turns a set of crops back into an animation.
     */
    private fun place(
        frame: FrameRef,
        cellLeft: Int,
        cellTop: Int,
        cellWidth: Int,
        cellHeight: Int,
        scale: Float,
    ): FrameRect {
        val width = (frame.source.width * scale).roundToInt().coerceIn(1, cellWidth)
        val height = (frame.source.height * scale).roundToInt().coerceIn(1, cellHeight)
        return FrameRect(
            left = cellLeft + ((cellWidth - width) * frame.pivot.xFraction).roundToInt(),
            top = cellTop + ((cellHeight - height) * frame.pivot.yFraction).roundToInt(),
            width = width,
            height = height,
        )
    }

    /**
     * The largest baked sheet worth producing.
     *
     * 4096 is the texture size every device in the target range supports. A
     * sheet larger than that is not a better sprite, it is one that fails to
     * load on some phones and works on the developer's.
     */
    const val MAX_SHEET_EDGE = 4096
}
