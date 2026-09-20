package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSheet

/**
 * Asks an image model for a sprite sheet.
 *
 * The model returns pixels and nothing else: it cannot be trusted to report a
 * grid, and asking it to describe its own output in JSON fails far more often
 * than the drawing does. So the grid is *dictated* in the prompt and assumed on
 * the way back, and the returned image is scaled to fit whatever it actually is.
 * That is why a low-quality generation still produces a usable sheet.
 */
class GenerateSpriteSheetUseCase(
    private val imageModel: ImageModelPort,
) {

    suspend operator fun invoke(
        request: SpriteSheetRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<GeneratedSpriteSheet> {
        val layout = request.layout
        val generated = imageModel.generateImage(
            ImageRequest(
                prompt = buildPrompt(request),
                modelId = request.modelId,
                width = layout.sheetWidth,
                height = layout.sheetHeight,
                requireTransparency = true,
            ),
            observer,
        ).getOrElse { return Result.failure(it) }

        if (generated.bytes.isEmpty()) {
            return Result.failure(GenerationException("The model returned an empty image"))
        }

        val sheet = SpriteSheet(
            id = "${request.namespace}:${request.slug()}",
            name = request.subject,
            columns = layout.columns,
            rows = layout.rows,
            // Derived from the image that actually came back, not from what was
            // asked for. Models round sizes, and a sheet cut on the requested
            // dimensions would shear every frame.
            frameWidth = (generated.width / layout.columns).coerceAtLeast(1),
            frameHeight = (generated.height / layout.rows).coerceAtLeast(1),
            clips = layout.clips,
            origin = SpriteOrigin.AI_GENERATED,
        )

        return Result.success(GeneratedSpriteSheet(sheet = sheet, image = generated))
    }

    /**
     * The prompt does the heavy lifting. Image models do not follow a schema, so
     * the grid, the background and the framing are stated plainly and repeated.
     *
     * Two failures dominate, and both are named here rather than implied. The
     * first is a beautiful character on an opaque background, which is useless
     * as a sprite. The second is worse and harder to spot: the model draws *one
     * large character* filling the canvas instead of a grid of small ones, and
     * since the sheet is then cut on a grid that was never drawn, every frame
     * is a crop of the same picture — the sprite appears to roll rather than
     * animate. "Sprite sheet" alone does not prevent it; saying how many cells,
     * how big they are, and that the figure must fit inside one, does.
     */
    private fun buildPrompt(request: SpriteSheetRequest): String {
        val layout = request.layout
        val cells = layout.columns * layout.rows
        if (cells == 1) return singlePosePrompt(request)
        // A clip that spans the whole grid is not "row one"; saying so would
        // tell the model the other row is something else, which is exactly the
        // confusion that produces a half-drawn sheet.
        val rows = if (layout.clips.size == 1 && layout.clips.first().frameCount >= cells) {
            val only = layout.clips.first()
            "All $cells cells show one action: ${describe(only.state)}, in order, read left to " +
                "right and then down. Nothing else appears anywhere on the image."
        } else {
            layout.clips.joinToString("\n") { clip ->
                "Row ${clip.firstFrame / layout.columns + 1}: ${clip.frameCount} frames of " +
                    describe(clip.state) + "."
            }
        }

        return buildString {
            appendLine("A 2D game sprite sheet: one image made of $cells small separate pictures")
            appendLine("of the same character, arranged in a grid.")
            appendLine()
            appendLine("The character: ${request.subject}.")
            appendLine(request.styleDirection.ifBlank { DEFAULT_STYLE })
            appendLine()
            appendLine("Layout, exactly:")
            appendLine("- A strict grid of ${layout.columns} columns by ${layout.rows} rows, $cells cells in all.")
            appendLine(
                "- The canvas is ${layout.canvas} by ${layout.canvas} pixels, so each cell is about " +
                    "${layout.approximateFrameWidth} by ${layout.approximateFrameHeight} pixels.",
            )
            appendLine("- Every cell the same size, evenly spaced, no gaps, no padding, no borders.")
            appendLine("- One whole character per cell, drawn small enough to fit inside that cell")
            appendLine("  with empty space around it, centred, feet near the bottom of the cell.")
            // The failure that produces a rolling sprite, named outright.
            appendLine("- Do NOT draw one large character across the whole image. Every cell holds")
            appendLine("  its own small, complete drawing of the character.")
            appendLine("- Consistent size, proportions and colours in every cell.")
            appendLine(rows)
            appendLine()
            // Spelled out because models reliably answer a request for
            // transparency by *drawing* the grey checkerboard that represents
            // it in an image editor. Saying "no checkerboard" once is not
            // enough; naming the mistake is.
            appendLine("Background: real alpha transparency, not a picture of it.")
            appendLine("Do NOT draw the grey and white checkerboard pattern that image editors")
            appendLine("use to show transparency. Do not fill the background with any colour,")
            appendLine("white or black included. No scenery, no ground shadow, no drop shadow.")
            appendLine("Do not label the frames. Do not draw a grid, guide lines, borders or text.")
            appendLine()
            appendLine(IsometricCamera.clause)
        }
    }

    /**
     * A prompt for one drawing.
     *
     * Everything the sheet prompt says about grids and cells is not merely
     * unnecessary here, it is actively harmful: a model told about cells will
     * draw cell borders, and a model told "one small picture" will draw a small
     * picture in the corner of a large empty canvas. So this shares only the
     * parts that are about the character and the background, and says the
     * opposite about framing -- fill the canvas, one figure, nothing else.
     */
    private fun singlePosePrompt(request: SpriteSheetRequest): String = buildString {
        appendLine("A single 2D game sprite: one character, drawn once, on its own.")
        appendLine()
        appendLine("The character: ${request.subject}.")
        appendLine(request.styleDirection.ifBlank { DEFAULT_STYLE })
        appendLine()
        appendLine("Framing, exactly:")
        appendLine("- One figure only. No second view, no turnaround, no variations.")
        appendLine("- Full body, head to feet, nothing cropped at any edge.")
        appendLine("- Centred, filling most of the canvas, feet near the bottom.")
        appendLine("- A strong readable silhouette: this is seen small and from above.")
        appendLine("- No grid, no panel, no frame, no border, no caption, no text.")
        appendLine()
        appendLine("Background: real alpha transparency, not a picture of it.")
        appendLine("Do NOT draw the grey and white checkerboard pattern that image editors")
        appendLine("use to show transparency. Do not fill the background with any colour,")
        appendLine("white or black included. No scenery, no ground shadow, no drop shadow.")
        appendLine()
        appendLine(IsometricCamera.clause)
    }

    private fun describe(state: AnimationState): String = when (state) {
        AnimationState.IDLE -> "standing still, breathing"
        AnimationState.WALK -> "a walk cycle"
        AnimationState.ATTACK -> "swinging a basic attack, wind-up to follow-through"
        AnimationState.SPECIAL -> "unleashing a signature power, clearly different from the basic attack"
        AnimationState.HURT -> "recoiling from a hit"
        AnimationState.ROLL -> "diving into a roll"
        AnimationState.DIE -> "falling and collapsing"
    }

    private companion object {
        const val DEFAULT_STYLE =
            "Pixel art game sprites, limited palette, bold readable silhouette, dark outline."
    }
}

data class SpriteSheetRequest(
    /** What to draw: "a bronze-masked warrior with a curved blade". */
    val subject: String,
    val namespace: String = "generated",
    val styleDirection: String = "",
    val layout: SheetLayout = SheetLayout.standard(),
    val modelId: String? = null,
    /**
     * Distinguishes two asks about the same subject.
     *
     * Ids are built from the subject, which is right until someone generates a
     * walk block and then an attack block of the same warrior -- at which point
     * the second silently replaces the first, and the work of mapping the first
     * goes with it.
     */
    val variant: String = "",
) {
    fun slug(): String {
        val base = subject.lowercase().replace(NON_ID, "_").trim('_')
            .take(MAX_SLUG).ifBlank { "sprite" }
        val tag = variant.lowercase().replace(NON_ID, "_").trim('_')
        return if (tag.isEmpty()) base else "${base}_$tag"
    }

    private companion object {
        val NON_ID = Regex("[^a-z0-9]+")
        const val MAX_SLUG = 24
    }
}

/**
 * The grid the model is asked for and the clips that read it back.
 *
 * The canvas is a square from the handful of sizes image providers actually
 * support, *not* columns x rows x a frame size. Asking for 384x448 because that
 * is what a 6x7 grid of 64px frames measures gets the request rejected outright
 * by some providers and silently rounded by others — and a sheet cut on a size
 * that was silently changed shears every frame. The grid lives in the prompt;
 * the canvas is whatever can be asked for without being renegotiated.
 */
data class SheetLayout(
    val columns: Int,
    val rows: Int,
    /** The square canvas requested of the provider, in pixels. */
    val canvas: Int,
    val clips: List<AnimationClip>,
) {
    val sheetWidth: Int get() = canvas
    val sheetHeight: Int get() = canvas

    /** Roughly how big one cell lands on that canvas, for a human to sanity-check. */
    val approximateFrameWidth: Int get() = canvas / columns
    val approximateFrameHeight: Int get() = canvas / rows

    companion object {
        /**
         * The only sizes worth asking for.
         *
         * Every provider supports a square; almost none support an arbitrary
         * one. 1024 leaves a 6x7 grid about 170px a cell, which is far more
         * than a sprite needs and costs nothing extra to ask for.
         */
        const val DEFAULT_CANVAS = 1024
        const val SMALL_CANVAS = 512

        /**
         * The default for a playable character: six frames a row, one row per
         * state, including a special separate from a basic attack.
         *
         * Denser than it was. Four frames is the fewest that reads as motion at
         * all, and it showed — a walk looked like a stutter and an attack was
         * two poses. Six is enough for a swing to wind up, land and recover.
         */
        fun detailed(canvas: Int = DEFAULT_CANVAS): SheetLayout = SheetLayout(
            columns = 6,
            rows = 7,
            canvas = canvas,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 6, frameDurationMs = 200),
                AnimationClip(AnimationState.WALK, firstFrame = 6, frameCount = 6, frameDurationMs = 110),
                AnimationClip(AnimationState.ATTACK, firstFrame = 12, frameCount = 6, frameDurationMs = 70, loops = false),
                AnimationClip(AnimationState.SPECIAL, firstFrame = 18, frameCount = 6, frameDurationMs = 80, loops = false),
                AnimationClip(AnimationState.HURT, firstFrame = 24, frameCount = 6, frameDurationMs = 90, loops = false),
                AnimationClip(AnimationState.ROLL, firstFrame = 30, frameCount = 6, frameDurationMs = 60, loops = false),
                AnimationClip(AnimationState.DIE, firstFrame = 36, frameCount = 6, frameDurationMs = 130, loops = false),
            ),
        )

        /** Idle, walk, attack, hurt: one row each. */
        fun standard(canvas: Int = DEFAULT_CANVAS): SheetLayout = SheetLayout(
            columns = 4,
            rows = 4,
            canvas = canvas,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 4, frameDurationMs = 220),
                AnimationClip(AnimationState.WALK, firstFrame = 4, frameCount = 4, frameDurationMs = 120),
                AnimationClip(AnimationState.ATTACK, firstFrame = 8, frameCount = 4, frameDurationMs = 90, loops = false),
                AnimationClip(AnimationState.HURT, firstFrame = 12, frameCount = 2, frameDurationMs = 110, loops = false),
            ),
        )

        /**
         * Six frames of one action and nothing else.
         *
         * The most reliable thing an image model can be asked for, and by a
         * wide margin. A seven-row sheet demands that a figure stay the same
         * size, the same colour and the same character across forty-two cells
         * spanning seven different activities; six cells of one action is a
         * far shorter consistency to hold, and the frames that come back are
         * usable rather than merely present.
         *
         * Three by two rather than a literal strip, because a square canvas is
         * what providers accept, and one row across a square gives each frame a
         * tall thin box a standing figure sits lost inside. Read left to right
         * then down, which is the order a clip already plays in -- so the whole
         * block is one contiguous clip.
         */
        fun action(state: AnimationState, canvas: Int = DEFAULT_CANVAS): SheetLayout = SheetLayout(
            columns = 3,
            rows = 2,
            canvas = canvas,
            clips = listOf(
                AnimationClip(
                    state = state,
                    firstFrame = 0,
                    frameCount = 6,
                    frameDurationMs = state.defaultFrameDurationMs,
                    loops = state !in AnimationState.oneShot,
                ),
            ),
        )

        /**
         * One drawing, asked for as one drawing.
         *
         * What a weak model produces anyway when asked for a sheet. Asking for
         * it deliberately at least gets a composed, centred figure instead of a
         * grid-shaped accident -- and a good still, bobbed and flashed and
         * faded by the renderer, is a usable actor.
         */
        fun pose(canvas: Int = SMALL_CANVAS): SheetLayout = SheetLayout(
            columns = 1,
            rows = 1,
            canvas = canvas,
            clips = listOf(
                AnimationClip(
                    state = AnimationState.IDLE,
                    firstFrame = 0,
                    frameCount = 1,
                    frameDurationMs = AnimationState.IDLE.defaultFrameDurationMs,
                ),
            ),
        )

        /** Two rows, for a monster that only needs to idle and walk. */
        fun simple(canvas: Int = SMALL_CANVAS): SheetLayout = SheetLayout(
            columns = 4,
            rows = 2,
            canvas = canvas,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 4, frameDurationMs = 240),
                AnimationClip(AnimationState.WALK, firstFrame = 4, frameCount = 4, frameDurationMs = 130),
            ),
        )
    }
}

/** The sheet's description plus the pixels it was cut from. */
data class GeneratedSpriteSheet(
    val sheet: SpriteSheet,
    val image: GeneratedImage,
)
