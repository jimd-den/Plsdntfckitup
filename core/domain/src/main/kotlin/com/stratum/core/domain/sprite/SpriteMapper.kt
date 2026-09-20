package com.stratum.core.domain.sprite

/**
 * Where a hand-mapped atlas comes from.
 *
 * Nobody should open this editor to an empty canvas. Every route in starts
 * from a guess that is right often enough to be worth correcting: what the
 * inspector read off the pixels, or how a sheet was already cut. The manual
 * work is meant to be *repair*, not authoring from nothing — a person who has
 * to place sixteen rectangles before seeing anything move will close the screen.
 */
object SpriteMapper {

    /**
     * A first mapping for an image nobody has looked at yet.
     *
     * The grid comes from [SheetInspection], which reads it off the gutters, so
     * a sheet the model laid out differently from the request opens on the grid
     * it actually drew. Clips are assigned a row at a time in the canonical
     * order — the same assumption the old renderer hard-coded, except now it is
     * a starting point a person can drag frames out of rather than a rule.
     */
    fun suggest(
        id: String,
        name: String,
        sourceId: String,
        imageWidth: Int,
        imageHeight: Int,
        verdict: GridVerdict,
        role: ActorRole = ActorRole.MONSTER,
        origin: SpriteOrigin = SpriteOrigin.AI_GENERATED,
    ): SpriteAtlas {
        val grid = verdict.grid.takeIf { it.columns > 0 && it.rows > 0 } ?: SheetGrid(1, 1)
        val spec = SliceSpec.fitting(grid, imageWidth, imageHeight)
            ?: SliceSpec(1, 1, cellWidth = imageWidth.coerceAtLeast(1), cellHeight = imageHeight.coerceAtLeast(1))
        val frames = SpriteSlicing.slice(spec, imageWidth, imageHeight)

        val atlas = SpriteAtlas(
            id = id,
            name = name,
            sourceId = sourceId,
            sourceWidth = imageWidth,
            sourceHeight = imageHeight,
            frames = frames,
            role = role,
            origin = origin,
        )
        return atlas.copy(clips = rowClips(frames, spec))
    }

    /**
     * The mapping implied by a sheet that was already cut.
     *
     * This is the door back in. A sheet generated last week, stored, and found
     * to walk badly can be opened, re-cut and re-mapped without regenerating
     * it — which matters because the image was the expensive part and the
     * mapping was the part that was wrong.
     */
    fun fromSheet(
        sheet: SpriteSheet,
        sourceId: String = sheet.id,
        imageWidth: Int = sheet.columns * sheet.frameWidth,
        imageHeight: Int = sheet.rows * sheet.frameHeight,
        role: ActorRole = ActorRole.MONSTER,
    ): SpriteAtlas {
        val spec = SliceSpec(
            columns = sheet.columns.coerceAtLeast(1),
            rows = sheet.rows.coerceAtLeast(1),
            cellWidth = sheet.frameWidth.coerceAtLeast(1),
            cellHeight = sheet.frameHeight.coerceAtLeast(1),
        )
        val frames = SpriteSlicing.slice(spec, imageWidth, imageHeight)
        val byIndex = frames.associateBy { frame ->
            val column = frame.source.left / spec.cellWidth
            val row = frame.source.top / spec.cellHeight
            row * spec.columns + column
        }

        val clips = sheet.clips.mapNotNull { clip ->
            val ids = (clip.firstFrame until clip.firstFrame + clip.frameCount)
                .mapNotNull { byIndex[it]?.id }
            if (ids.isEmpty()) null
            else ClipMapping(clip.state, ids, clip.frameDurationMs, clip.loops)
        }

        return SpriteAtlas(
            id = sheet.id,
            name = sheet.name,
            sourceId = sourceId,
            sourceWidth = imageWidth,
            sourceHeight = imageHeight,
            frames = frames,
            clips = clips,
            facing = if (sheet.mirrorsFacings) FacingLayout.MIRRORED else FacingLayout.STATIC,
            role = role,
            origin = sheet.origin,
        )
    }

    /**
     * An image treated as one drawing rather than a sheet.
     *
     * The honest reading of what weaker image models return, and a perfectly
     * good asset: a still that idles. The renderer can bob it, flash it and fade
     * it, which is a great deal more than the fallback shape it would otherwise
     * be drawing.
     */
    fun singleStill(
        id: String,
        name: String,
        sourceId: String,
        imageWidth: Int,
        imageHeight: Int,
        role: ActorRole = ActorRole.PROP,
        origin: SpriteOrigin = SpriteOrigin.AI_GENERATED,
    ): SpriteAtlas {
        val frame = FrameRef(
            id = SpriteSlicing.frameId(0, 0),
            source = SourceRect(0, 0, imageWidth.coerceAtLeast(1), imageHeight.coerceAtLeast(1)),
            label = "whole image",
        )
        return SpriteAtlas(
            id = id,
            name = name,
            sourceId = sourceId,
            sourceWidth = imageWidth.coerceAtLeast(1),
            sourceHeight = imageHeight.coerceAtLeast(1),
            frames = listOf(frame),
            clips = listOf(ClipMapping(AnimationState.IDLE, listOf(frame.id))),
            role = role,
            origin = origin,
        )
    }

    /**
     * One clip per row, in the canonical order — the same reading
     * [clipsForGrid] applies to a discovered grid, said in frame ids.
     */
    private fun rowClips(frames: List<FrameRef>, spec: SliceSpec): List<ClipMapping> {
        if (frames.isEmpty()) return emptyList()
        if (spec.cells == 1) {
            return listOf(ClipMapping(AnimationState.IDLE, listOf(frames.first().id)))
        }
        val byId = frames.associateBy { it.id }
        return AnimationState.generatedRowOrder.take(spec.rows).mapIndexedNotNull { row, state ->
            val ids = (0 until spec.columns).mapNotNull { byId[SpriteSlicing.frameId(it, row)]?.id }
            if (ids.isEmpty()) null else ClipMapping(state, ids)
        }
    }
}
