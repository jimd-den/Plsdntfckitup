package com.stratum.core.domain.sprite

/**
 * Moving and resizing one frame at a time.
 *
 * The grid handles the sheets that are nearly right. This handles the ones that
 * are not: a collage, a character drawn twice at different scales, a row that
 * drifts a pixel per cell, a pose sitting in the corner of an image that was
 * never a sheet at all. No arrangement of columns, margins and gutters
 * describes any of those, and pretending otherwise is what forces a person to
 * throw away a good drawing.
 *
 * Every operation keeps the frame on the image and refuses to collapse it, so
 * the editor above can hold a button down without watching for a frame that has
 * quietly become two pixels wide or wandered off the canvas.
 */
object FrameGeometry {

    fun move(atlas: SpriteAtlas, frameId: String, dx: Int, dy: Int): SpriteAtlas =
        atlas.updateFrame(frameId) { frame ->
            frame.copy(
                source = frame.source
                    .movedBy(dx, dy)
                    .nudgedInside(atlas.sourceWidth, atlas.sourceHeight),
            )
        }

    /** Pushes edges outward, or pulls them in with negative amounts. */
    fun expand(
        atlas: SpriteAtlas,
        frameId: String,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): SpriteAtlas = atlas.updateFrame(frameId) { frame ->
        frame.copy(
            source = frame.source
                .expanded(left, top, right, bottom)
                .nudgedInside(atlas.sourceWidth, atlas.sourceHeight),
        )
    }

    fun setRect(atlas: SpriteAtlas, frameId: String, rect: SourceRect): SpriteAtlas =
        atlas.updateFrame(frameId) { frame ->
            frame.copy(source = rect.nudgedInside(atlas.sourceWidth, atlas.sourceHeight))
        }

    /**
     * One frame shrunk to what is drawn in it.
     *
     * The per-frame version of [SpriteSlicing.trimToContent]. Separate because
     * trimming everything is a decision about the sheet and trimming one cell
     * is a decision about that cell -- and someone working through frames one
     * at a time wants the second without being given the first.
     *
     * A frame with nothing in it is left alone rather than switched off here:
     * at this size the person is looking straight at it, and moving it is the
     * likelier intent.
     */
    fun snapToContent(atlas: SpriteAtlas, frameId: String, pixels: IntArray): SpriteAtlas {
        val frame = atlas.frame(frameId) ?: return atlas
        val tight = SpriteSlicing.contentBounds(
            pixels = pixels,
            imageWidth = atlas.sourceWidth,
            imageHeight = atlas.sourceHeight,
            rect = frame.source,
        ) ?: return atlas
        return atlas.updateFrame(frameId) { it.copy(source = tight) }
    }

    /**
     * A frame that came from nobody's grid.
     *
     * Added at the end, so stepping forward from the last frame lands on the
     * one just made. Its id is marked as free-standing so a later re-slice
     * leaves it alone — a rectangle placed by hand is the one thing on the sheet
     * a grid has no opinion about.
     */
    fun addFrame(
        atlas: SpriteAtlas,
        rect: SourceRect,
        pivot: FramePivot = FramePivot.BOTTOM_CENTER,
    ): SpriteAtlas {
        val id = freeFrameId(atlas)
        return atlas.addFrame(
            FrameRef(
                id = id,
                source = rect.nudgedInside(atlas.sourceWidth, atlas.sourceHeight),
                pivot = pivot,
                label = "free",
            ),
        )
    }

    /**
     * A sensible rectangle for a new frame: the same size as the one being
     * looked at, offset so it does not land exactly on top of it and read as
     * nothing having happened.
     */
    fun nextFreeRect(atlas: SpriteAtlas, near: FrameRef?): SourceRect {
        val base = near?.source ?: SourceRect(
            left = 0,
            top = 0,
            width = (atlas.sourceWidth / DEFAULT_DIVISIONS).coerceAtLeast(SourceRect.MIN_EDGE),
            height = (atlas.sourceHeight / DEFAULT_DIVISIONS).coerceAtLeast(SourceRect.MIN_EDGE),
        )
        return base
            .movedBy(base.width / 4, base.height / 4)
            .nudgedInside(atlas.sourceWidth, atlas.sourceHeight)
    }

    private fun freeFrameId(atlas: SpriteAtlas): String {
        var index = 1
        while (atlas.frame("$FREE_PREFIX$index") != null) index++
        return "$FREE_PREFIX$index"
    }

    /**
     * Marks a frame nobody's grid placed.
     *
     * [SpriteSlicing.reslice] rebuilds frames from grid positions, and a
     * hand-placed rectangle has none — so without a way to tell them apart,
     * changing the column count would silently delete every frame a person
     * drew by hand.
     */
    const val FREE_PREFIX = "free"

    /** A new frame on a blank sheet starts at a quarter of the image. */
    private const val DEFAULT_DIVISIONS = 4
}
