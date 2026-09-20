package com.stratum.core.domain.ai

/**
 * The reference drawing every frame of a character is edited out of.
 *
 * A T-pose, which is never a frame anyone plays and is exactly right for this.
 * Its job is not to look good, it is to be *legible*: arms straight out means
 * nothing is occluded — both hands, both sleeves, the weapon, the silhouette of
 * the armour are all visible and unambiguous. An image editor asked to change
 * the pose can only preserve what it can see, and a character anchored on a
 * three-quarter action pose loses whatever that pose was hiding the first time
 * the arms move.
 *
 * Asked for large, because everything downstream is a reduction. A 1024 pixel
 * figure downscales to a crisp 64 pixel sprite; a 128 pixel figure upscales to
 * mush, and no amount of editing afterwards puts the detail back.
 */
data class BasePoseRequest(
    val subject: String,
    val styleDirection: String = "",
    val canvas: Int = DEFAULT_CANVAS,
    val modelId: String? = null,
) {
    companion object {
        /**
         * Large enough that every later step is a downscale.
         *
         * This is the one number in the pipeline that cannot be raised later. A
         * pose set generated small is small forever, and the cost of asking for
         * 1024 instead of 512 is paid once per character rather than once per
         * frame.
         */
        const val DEFAULT_CANVAS = 1024
    }
}

/** One animation frame, asked for as an edit of the reference rather than a new drawing. */
data class PoseFrameRequest(
    /** The reference pose. The same one for every frame, never the previous frame. */
    val reference: ImageReference,
    val step: PoseStep,
    /**
     * A stick figure of the pose, when there is one.
     *
     * Optional because a set generated before the guides existed must still be
     * resumable, and because a provider that only accepts one input image can
     * still be given the character. The prose instruction is kept either way —
     * it is what a model falls back on when it does not follow the drawing, and
     * the two agree because both are generated from the same skeleton.
     */
    val guide: ImageReference? = null,
    val styleDirection: String = "",
    val canvas: Int = BasePoseRequest.DEFAULT_CANVAS,
    val modelId: String? = null,
)

/**
 * Draws the reference pose a character is built from.
 *
 * Separate from [GenerateSpriteSheetUseCase] because it is asking for something
 * categorically different. That one asks for a grid of frames and fights the
 * model's tendency to draw one picture; this one *wants* one picture, and every
 * instruction in the sheet prompt about cells and grids would actively harm it.
 */
class GenerateBasePoseUseCase(
    private val imageModel: ImageModelPort,
) {

    suspend operator fun invoke(
        request: BasePoseRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<GeneratedImage> {
        val generated = imageModel.generateImage(
            ImageRequest(
                prompt = buildPrompt(request),
                modelId = request.modelId,
                width = request.canvas,
                height = request.canvas,
                requireTransparency = true,
            ),
            observer,
        ).getOrElse { return Result.failure(it) }

        if (generated.bytes.isEmpty()) {
            return Result.failure(GenerationException("The model returned an empty image"))
        }
        return Result.success(generated)
    }

    private fun buildPrompt(request: BasePoseRequest): String = buildString {
        appendLine("A character reference sheet: one figure, drawn once, in a T-pose.")
        appendLine()
        appendLine("The character: ${request.subject}.")
        appendLine(request.styleDirection.ifBlank { DEFAULT_STYLE })
        appendLine()
        appendLine("Pose, exactly:")
        appendLine("- Standing upright, both feet flat on the ground and slightly apart.")
        appendLine("- Both arms straight out to the sides at shoulder height, palms down.")
        // Empty hands, because the weapon is drawn separately and attached at
        // the hand. A reference holding a sword bakes that sword into every
        // pose edited from it, and then the character cannot put it down.
        appendLine("- Both hands empty and open. No weapon, no shield, no tool, nothing held.")
        // The whole reason for a T-pose. Said as a requirement rather than left
        // to the model's idea of what a reference sheet is.
        appendLine("- Nothing overlapping anything else: both hands, both arms, both legs")
        appendLine("  and any weapon or equipment fully visible and separated.")
        appendLine("- Full body, head to feet, nothing cropped at any edge.")
        appendLine("- Centred, filling most of the canvas, feet near the bottom.")
        appendLine("- Neutral expression. No action, no motion.")
        appendLine("- One figure only. No turnaround, no second view, no variations.")
        appendLine("- No grid, no panel, no frame, no border, no caption, no text, no labels.")
        appendLine()
        appendLine(IsometricCamera.clause)
        appendLine()
        appendLine(BACKGROUND)
    }

    private companion object {
        const val DEFAULT_STYLE =
            "High detail 2D game character art, clean flat colours, bold readable silhouette, " +
                "strong dark outline."
    }
}

/**
 * Draws one animation frame by editing the reference pose.
 *
 * This is the step that makes AI-generated animation work at all. Asking a
 * model for eight poses of a bronze warrior in eight separate calls returns
 * eight different warriors — different helmet, different palette, different
 * build — and no prompt engineering fixes it, because there is nothing tying
 * the calls together. Handing it the warrior and changing only the pose ties
 * them together with the one thing a model cannot misremember: the pixels.
 *
 * Every frame is edited from the *reference*, never from the previous frame.
 * Chaining would compound: each generation drifts a little, and by frame eight
 * the accumulated drift is a different character again. A star, not a chain.
 */
class GeneratePoseFrameUseCase(
    private val imageModel: ImageModelPort,
) {

    suspend operator fun invoke(
        request: PoseFrameRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<GeneratedImage> {
        val generated = imageModel.generateImage(
            ImageRequest(
                prompt = buildPrompt(request),
                modelId = request.modelId,
                width = request.canvas,
                height = request.canvas,
                requireTransparency = true,
                // Character first, guide second, and the prompt names them in
                // that order. An image editor handed two pictures with no word
                // about which is which will cheerfully redraw the stick figure.
                references = listOfNotNull(request.reference, request.guide),
            ),
            observer,
        ).getOrElse { return Result.failure(it) }

        if (generated.bytes.isEmpty()) {
            return Result.failure(
                GenerationException("The model returned an empty image for ${request.step.key}"),
            )
        }
        return Result.success(generated)
    }

    /**
     * Almost all of this prompt is about what must *not* change.
     *
     * An image editor given a pose instruction will happily take the
     * opportunity to improve the character while it is there — better armour, a
     * nicer palette, a more interesting camera. Every one of those is fatal
     * here, because the frames are going to be played in sequence and anything
     * that differs between them reads as flicker rather than as an improvement.
     */
    private fun buildPrompt(request: PoseFrameRequest): String = buildString {
        if (request.guide != null) {
            appendLine("Two images are attached.")
            appendLine("IMAGE 1 is the character. IMAGE 2 is a stick figure diagram of a pose.")
            appendLine()
            appendLine("Redraw the character from IMAGE 1 standing in the pose drawn in IMAGE 2.")
            appendLine()
            // The failure this is guarding against is not subtle: a model given
            // a line drawing and no explanation will sometimes return the line
            // drawing, tidied up.
            appendLine("IMAGE 2 is a diagram, not art. Do not draw a stick figure. Do not copy")
            appendLine("its lines, its white background or its black dots. Use it only to place")
            appendLine("the character's head, arms, hands, legs and feet.")
            appendLine()
            appendLine("Match IMAGE 2 exactly: the same limb angles, the same bend at every")
            appendLine("elbow and knee, the same lean of the body, the same height off the")
            appendLine("ground. The large dot marks the hand that holds a weapon; close that")
            appendLine("hand into a grip.")
            appendLine()
            appendLine("In words, the pose is: ${request.step.instruction}.")
        } else {
            appendLine("Redraw the character in the attached image in a new pose.")
            appendLine()
            appendLine("New pose: ${request.step.instruction}.")
        }
        appendLine()
        // Its own paragraph, in capitals. Asked politely as one bullet among
        // nine -- "the same camera: viewed from the same angle" -- every model
        // tested turned the character to a profile view, because a pose
        // described in terms of legs and arms reads as a request for the angle
        // those read best from. Frames that alternate between angles are not an
        // animation, they are a flicker, so this is the one worth shouting.
        appendLine(IsometricCamera.holdClause)
        // After the camera clause and in its own paragraph, because the two
        // are easy to read as contradicting each other: one says the view does
        // not change, the other says the subject is seen from behind. Stated
        // as a fact about the body and placed second, it reads as what it is
        // -- the character has turned, the viewer has not.
        if (request.step.view.turnClause.isNotBlank()) {
            appendLine()
            appendLine(request.step.view.turnClause)
        }
        appendLine()
        appendLine("Keep identical to the character in IMAGE 1:")
        appendLine("- The same character. Same face, same build, same proportions.")
        appendLine("- The same colours, exactly. Same palette, same shading, same outline.")
        appendLine("- The same equipment, armour and clothing, unchanged in every detail.")
        // Carried things need naming separately from worn things. A weapon
        // hanging at the hip in the reference simply disappeared when the pose
        // changed: the model reads a held object as part of the pose rather
        // than as part of the character, and drops it along with the old pose.
        appendLine("- Everything the character wears or carries on their body.")
        // The reference has empty hands and every pose must keep them empty:
        // the weapon is a separate drawing attached at the hand, so a sword
        // invented here would be a second sword clipping through the real one.
        appendLine("- Empty hands. The character holds nothing. Do not add a weapon, a shield,")
        appendLine("  a tool or any held object, even if the pose is an attack. Draw the hands")
        appendLine("  gripping as though holding something, but draw nothing in them.")
        appendLine("- The same art style and the same line weight.")
        appendLine("- The same scale: the figure occupies the same height on the canvas.")
        appendLine()
        appendLine("Change only the pose of the body. Do not redesign, upgrade, restyle or")
        appendLine("reinterpret the character. Do not add effects, motion blur, speed lines,")
        appendLine("dust, sparks or a ground shadow. Do not add anything that was not already")
        appendLine("on the character.")
        appendLine()
        appendLine("Framing: one figure, full body, head to feet, nothing cropped at any edge,")
        appendLine("centred, feet near the bottom of the canvas.")
        if (request.styleDirection.isNotBlank()) {
            appendLine()
            appendLine(request.styleDirection)
        }
        appendLine()
        appendLine(BACKGROUND)
    }
}

/**
 * What to do about the background, said the same way everywhere.
 *
 * Flat chroma green rather than a plea for alpha. Image models answer a request
 * for transparency by *drawing* the grey checkerboard that represents it in an
 * image editor at least as often as they return a real alpha channel, and a
 * drawn checkerboard is unrecoverable. A flat colour is unambiguous to produce
 * and trivial to key out afterwards, which is a far better trade than a coin
 * flip on whether the sprite arrives wearing a chessboard.
 */
private const val BACKGROUND =
    "Background: a single flat solid chroma green (#00FF00) filling the entire background, " +
        "edge to edge. No scenery, no gradient, no texture, no vignette, no ground, no " +
        "shadow cast onto the background. Do NOT draw the grey and white checkerboard " +
        "pattern that image editors use to show transparency. The green must not appear " +
        "anywhere on the character itself."
