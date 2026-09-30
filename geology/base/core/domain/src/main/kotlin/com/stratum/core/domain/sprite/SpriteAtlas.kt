package com.stratum.core.domain.sprite

/**
 * A hand-made mapping from a source image onto animation clips.
 *
 * This is the layer [SpriteSheet] cannot be. A sheet is a uniform grid read in
 * order, which is the right thing to *play* and the wrong thing to *author*:
 * it can only describe art that already landed on a perfect grid, with the
 * states in the order the engine happens to expect. Generated art almost never
 * does. It comes back with uneven gutters, three duplicate cells, one superb
 * attack pose, a walk row a few pixels low, and no death frames at all.
 *
 * So an atlas names frames instead of counting them. A clip is a list of frame
 * ids, any frame may appear in several clips or in none, and a state with no
 * frames is simply unmapped rather than wrong. That is what lets a person
 * salvage a flawed image: pick the six cells that read, put the good pose in
 * ATTACK twice, leave DIE empty and let the renderer fade the body out.
 *
 * Holds no pixels, for the same reason [SpriteSheet] holds none — the mapping
 * is the part worth testing, and it should be testable without a bitmap.
 */
data class SpriteAtlas(
    val id: String,
    val name: String,
    /** The image being mapped. The bytes live wherever the platform keeps them, under this id. */
    val sourceId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val frames: List<FrameRef> = emptyList(),
    val clips: List<ClipMapping> = emptyList(),
    val facing: FacingLayout = FacingLayout.MIRRORED,
    val role: ActorRole = ActorRole.MONSTER,
    val origin: SpriteOrigin = SpriteOrigin.IMPORTED,
) {

    val enabledFrames: List<FrameRef> get() = frames.filter { it.enabled }

    fun frame(frameId: String): FrameRef? = frames.firstOrNull { it.id == frameId }

    fun clip(state: AnimationState): ClipMapping? = clips.firstOrNull { it.state == state }

    /** States that have at least one usable frame behind them. */
    val mappedStates: Set<AnimationState>
        get() = clips.filter { framesOf(it.state).isNotEmpty() }.map { it.state }.toSet()

    /**
     * The frames a clip actually plays.
     *
     * Dangling and disabled ids are dropped here rather than rejected at the
     * point of mapping, because the editor is a place where things are
     * half-done on purpose: turning a cell off to see how a walk reads without
     * it should not silently delete it from four clips.
     */
    fun framesOf(state: AnimationState): List<FrameRef> =
        clip(state)?.frameIds?.mapNotNull { id -> frame(id)?.takeIf { it.enabled } }.orEmpty()

    // ---- frames ----------------------------------------------------------

    /** Replaces the frame list wholesale, as a re-slice does. Clips keep whatever still resolves. */
    fun withFrames(next: List<FrameRef>): SpriteAtlas = copy(frames = next)

    fun addFrame(frame: FrameRef): SpriteAtlas =
        if (frames.any { it.id == frame.id }) this else copy(frames = frames + frame)

    fun updateFrame(frameId: String, change: (FrameRef) -> FrameRef): SpriteAtlas =
        copy(frames = frames.map { if (it.id == frameId) change(it) else it })

    fun toggleFrame(frameId: String): SpriteAtlas =
        updateFrame(frameId) { it.copy(enabled = !it.enabled) }

    fun flipFrame(frameId: String): SpriteAtlas =
        updateFrame(frameId) { it.copy(flippedX = !it.flippedX) }

    fun setPivot(frameId: String, pivot: FramePivot): SpriteAtlas =
        updateFrame(frameId) { it.copy(pivot = pivot) }

    /**
     * A second copy of a frame, mapped independently.
     *
     * The reason this exists is holding a pose: a two-frame attack reads as a
     * twitch, and the usual fix is the strike frame held for three beats while
     * the wind-up gets one. Two ids for the same pixels is how that is said.
     */
    fun duplicateFrame(frameId: String): SpriteAtlas {
        val source = frame(frameId) ?: return this
        val copy = source.copy(id = freeFrameId(frameId))
        val at = frames.indexOfFirst { it.id == frameId }
        return copy(frames = frames.toMutableList().apply { add(at + 1, copy) })
    }

    /** Drops a frame and every reference to it, which is the one case that must not dangle. */
    fun removeFrame(frameId: String): SpriteAtlas = copy(
        frames = frames.filterNot { it.id == frameId },
        clips = clips.map { it.copy(frameIds = it.frameIds.filterNot { id -> id == frameId }) },
    )

    /**
     * A free id for a copy of [base], marked so a re-slice can carry the copy
     * onto its source cell's new rectangle rather than losing it.
     */
    private fun freeFrameId(base: String): String {
        val root = base.substringBefore(SpriteSlicing.COPY_MARK)
        var suffix = 2
        while (frames.any { it.id == "$root${SpriteSlicing.COPY_MARK}$suffix" }) suffix++
        return "$root${SpriteSlicing.COPY_MARK}$suffix"
    }

    // ---- clips -----------------------------------------------------------

    fun withClip(mapping: ClipMapping): SpriteAtlas {
        val at = clips.indexOfFirst { it.state == mapping.state }
        return if (at < 0) {
            copy(clips = clips + mapping)
        } else {
            copy(clips = clips.toMutableList().apply { set(at, mapping) })
        }
    }

    fun updateClip(state: AnimationState, change: (ClipMapping) -> ClipMapping): SpriteAtlas =
        withClip(change(clip(state) ?: ClipMapping.empty(state)))

    /**
     * Adds a frame to the end of a clip.
     *
     * Appending a frame already in the clip is allowed and meant: tapping the
     * same cell twice is how a person says "hold this pose", and a set would
     * quietly refuse them.
     */
    fun appendToClip(state: AnimationState, frameId: String): SpriteAtlas =
        if (frame(frameId) == null) this
        else updateClip(state) {
            it.copy(frameIds = it.frameIds + frameId, borrowedFrom = null)
        }

    fun removeFromClip(state: AnimationState, index: Int): SpriteAtlas =
        updateClip(state) { clip ->
            if (index !in clip.frameIds.indices) clip
            else clip.copy(frameIds = clip.frameIds.toMutableList().apply { removeAt(index) })
        }

    fun moveInClip(state: AnimationState, from: Int, to: Int): SpriteAtlas =
        updateClip(state) { clip ->
            if (from !in clip.frameIds.indices || to !in clip.frameIds.indices) {
                clip
            } else {
                clip.copy(
                    frameIds = clip.frameIds.toMutableList().apply { add(to, removeAt(from)) },
                )
            }
        }

    fun clearClip(state: AnimationState): SpriteAtlas =
        updateClip(state) { it.copy(frameIds = emptyList()) }

    fun setClipTiming(
        state: AnimationState,
        frameDurationMs: Int = clip(state)?.frameDurationMs ?: state.defaultFrameDurationMs,
        loops: Boolean = clip(state)?.loops ?: (state !in AnimationState.oneShot),
    ): SpriteAtlas = updateClip(state) {
        it.copy(frameDurationMs = frameDurationMs.coerceIn(MIN_FRAME_MS, MAX_FRAME_MS), loops = loops)
    }

    /** Forgets empty clips and dangling ids. Used on the way to disk, not while editing. */
    fun pruned(): SpriteAtlas = copy(
        clips = clips
            .map { clip -> clip.copy(frameIds = clip.frameIds.filter { frame(it)?.enabled == true }) }
            .filter { it.frameIds.isNotEmpty() },
    )

    companion object {
        /** Faster than this is not animation, it is a strobe. */
        const val MIN_FRAME_MS = 20
        const val MAX_FRAME_MS = 2_000
    }
}

/**
 * One frame: a rectangle of the source image and how to hang it.
 *
 * [id] rather than an index because the whole point is that frames survive
 * being reordered, re-sliced around and turned off. An index would renumber
 * under every one of those and take four clips with it.
 */
data class FrameRef(
    val id: String,
    val source: SourceRect,
    /**
     * The point of the frame that sits where the actor is.
     *
     * This is what stops a walk from bobbing. Cells cropped from a generated
     * image rarely put the figure at the same height twice; aligning them by
     * the feet at bake time costs nothing and fixes it, while aligning by the
     * cell's centre — the obvious thing — makes it worse.
     */
    val pivot: FramePivot = FramePivot.BOTTOM_CENTER,
    val flippedX: Boolean = false,
    /**
     * Off means "not art": an empty cell, a duplicate, a label the model drew
     * in the corner. Kept rather than deleted so turning it back on is one tap.
     */
    val enabled: Boolean = true,
    /** Where it came from, for a person reading a contact sheet: "r2c3". */
    val label: String = "",
)

/** A rectangle of the source image, in its own pixels. */
data class SourceRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "A frame rectangle has no area: ${width}x$height" }
    }

    val right: Int get() = left + width
    val bottom: Int get() = top + height

    /**
     * The part of this rectangle that is really on the image, or null when none
     * of it is. A grid offset past the right edge produces cells that are pure
     * arithmetic, and cutting them yields a band of nothing.
     */
    fun clampedTo(imageWidth: Int, imageHeight: Int): SourceRect? {
        val l = left.coerceIn(0, imageWidth)
        val t = top.coerceIn(0, imageHeight)
        val r = right.coerceIn(0, imageWidth)
        val b = bottom.coerceIn(0, imageHeight)
        if (r <= l || b <= t) return null
        return SourceRect(l, t, r - l, b - t)
    }

    fun isWithin(imageWidth: Int, imageHeight: Int): Boolean =
        left >= 0 && top >= 0 && right <= imageWidth && bottom <= imageHeight

    fun movedBy(dx: Int, dy: Int): SourceRect = copy(left = left + dx, top = top + dy)

    /**
     * The same rectangle with each edge pushed outward by the given amount, or
     * pulled in by a negative one.
     *
     * Refuses to collapse rather than clamping silently to a sliver: a person
     * holding the shrink button expects it to stop at something they can still
     * see and grab, not to leave a two-pixel line they have to hunt for.
     */
    fun expanded(
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        minEdge: Int = MIN_EDGE,
    ): SourceRect {
        val width = this.width + left + right
        val height = this.height + top + bottom
        if (width < minEdge || height < minEdge) return this
        return SourceRect(this.left - left, this.top - top, width, height)
    }

    /**
     * Shifted back onto the image, rather than cut down to the part that fits.
     *
     * The difference matters while dragging. Clipping at the edge means a
     * rectangle pushed off the side comes back narrower than it went out, so a
     * frame loses width every time it touches a border -- which is exactly when
     * someone is dragging quickly and not watching the numbers.
     */
    fun nudgedInside(imageWidth: Int, imageHeight: Int): SourceRect {
        if (width > imageWidth || height > imageHeight) {
            return clampedTo(imageWidth, imageHeight) ?: this
        }
        return copy(
            left = left.coerceIn(0, imageWidth - width),
            top = top.coerceIn(0, imageHeight - height),
        )
    }

    companion object {
        /** Smaller than this is not a frame anyone can see, let alone tap. */
        const val MIN_EDGE = 2
    }
}

/**
 * Which point of a frame is anchored, as a fraction of its box.
 *
 * Bottom centre is the default because almost everything that animates stands
 * on the ground, and the ground is the thing a viewer notices sliding.
 */
enum class FramePivot(val label: String, val xFraction: Float, val yFraction: Float) {
    BOTTOM_CENTER("Feet", 0.5f, 1f),
    CENTER("Centre", 0.5f, 0.5f),
    TOP_CENTER("Top", 0.5f, 0f),
}

/**
 * One state's frames, in order, with its timing.
 *
 * Unlike [AnimationClip] this may be empty, because an unmapped state is the
 * normal condition of a sheet halfway through being mapped — not an error to
 * reject at construction.
 */
data class ClipMapping(
    val state: AnimationState,
    val frameIds: List<String> = emptyList(),
    val frameDurationMs: Int = state.defaultFrameDurationMs,
    val loops: Boolean = state !in AnimationState.oneShot,
    /**
     * Set when these frames were borrowed from another state rather than mapped
     * for this one.
     *
     * Kept so the editor can show which animations are real, and so the
     * renderer can be told to make up the difference. Dropping a frame in by
     * hand clears it: once someone has chosen the frames themselves, the clip
     * is theirs however it started.
     */
    val borrowedFrom: AnimationState? = null,
) {
    val isEmpty: Boolean get() = frameIds.isEmpty()

    /** Frames per second, which is the number a person actually thinks in. */
    val fps: Float get() = 1000f / frameDurationMs.coerceAtLeast(1)

    companion object {
        fun empty(state: AnimationState) = ClipMapping(state)
    }
}

/**
 * Whether the drawn facing may be mirrored to buy the other side.
 *
 * Mirroring is free and reads correctly at a three-quarter camera, so it is the
 * default. It is wrong for exactly one class of art — anything carrying a
 * readable asymmetry, a banner with a glyph on it, a portrait, a prop with
 * text — where the flip is instantly legible as a mistake.
 */
enum class FacingLayout(val label: String) {
    MIRRORED("Mirror for the other side"),
    STATIC("Never flip"),
}
