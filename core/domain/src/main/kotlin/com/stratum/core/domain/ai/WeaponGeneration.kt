package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.WeaponKind

/** One weapon, drawn on its own so any character can pick it up. */
data class WeaponRequest(
    val subject: String,
    val kind: WeaponKind,
    val styleDirection: String = "",
    val canvas: Int = BasePoseRequest.DEFAULT_CANVAS,
    val modelId: String? = null,
) {
    fun slug(): String {
        val base = subject.lowercase().replace(NON_ID, "_").trim('_').take(MAX_SLUG)
        return "${kind.name.lowercase()}_${base.ifBlank { "weapon" }}"
    }

    private companion object {
        val NON_ID = Regex("[^a-z0-9]+")
        const val MAX_SLUG = 24
    }
}

/**
 * Draws a weapon nobody owns.
 *
 * The reason this is separate from the character at all: a sword drawn into a
 * character belongs to that character forever. It cannot be dropped, cannot be
 * swapped for a better one, and has to be drawn again for every actor that
 * carries one — which, in a game whose whole loop is picking up better loot, is
 * exactly backwards. Drawing it once on its own means one sword serves every
 * character, and a character can change what they are holding without being
 * redrawn.
 *
 * It also fixes a failure measured in the pose pipeline. A weapon held in the
 * reference pose simply disappeared when the pose changed: an image editor
 * reads a held object as part of the pose, and drops it along with the old one.
 * A weapon that was never in the reference cannot be lost from it.
 */
class GenerateWeaponUseCase(
    private val imageModel: ImageModelPort,
) {

    suspend operator fun invoke(
        request: WeaponRequest,
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

    /**
     * Two contracts, and both exist so the renderer can do arithmetic on the
     * result.
     *
     * **Upright, tip at the top, grip at the bottom.** Every rotation in the
     * swing rig is measured from "pointing straight up", so the drawing has to
     * start there. A sword that arrives lying diagonally is not a sword at a
     * different angle, it is a sword whose every frame is off by however much
     * the model felt like.
     *
     * **Flat on, not at the isometric camera.** This is the one piece of art in
     * the game deliberately not drawn at the game's camera angle, and it is
     * worth saying why: the weapon is going to be *rotated* through a swing, so
     * it has to be drawn as a flat shape that survives rotation. A blade drawn
     * receding into the isometric distance is foreshortened for one angle only,
     * and looks wrong at all the others it gets turned to. Characters are drawn
     * at the camera because they never rotate; weapons rotate constantly.
     */
    private fun buildPrompt(request: WeaponRequest): String = buildString {
        appendLine("A single game weapon icon: one object, drawn alone.")
        appendLine()
        appendLine("The weapon: ${request.subject}.")
        appendLine("It is ${request.kind.description}.")
        appendLine(request.styleDirection.ifBlank { DEFAULT_STYLE })
        appendLine()
        appendLine("Orientation, exactly:")
        appendLine("- Standing straight up and down, vertical, perfectly upright.")
        appendLine("- The business end — the blade, point or head — at the TOP of the image.")
        appendLine("- The handle, grip or pommel at the BOTTOM of the image.")
        appendLine("- Seen flat from the side, its full shape and length visible. Not angled")
        appendLine("  away from the viewer, not foreshortened, not in perspective.")
        appendLine("- Centred left to right, filling most of the height of the image.")
        appendLine()
        appendLine("Nothing else in the image:")
        appendLine("- No hand, no arm, no character, no person holding it.")
        appendLine("- No stand, no rack, no table, no ground, no scabbard.")
        appendLine("- One weapon only. No variations, no second view, no parts laid out.")
        appendLine("- No grid, no panel, no frame, no border, no caption, no text, no labels.")
        appendLine()
        // The light has to agree with the characters even though the angle does
        // not, or a bright sword reads as pasted onto a dim body.
        appendLine("Lighting: lit from above and slightly to the left, the same as a character")
        appendLine("standing under an overhead light. Even, readable, no dramatic rim light.")
        appendLine()
        appendLine(WEAPON_BACKGROUND)
    }

    private companion object {
        const val DEFAULT_STYLE =
            "High detail 2D game item art, clean flat colours, bold readable silhouette, " +
                "strong dark outline."
    }
}

/**
 * The same chroma contract the characters use.
 *
 * Repeated rather than shared with the pose prompts because the sentence about
 * the colour not appearing on the subject has to name a weapon, not a
 * character — and a green-handled axe keyed to nothing is a funnier bug to
 * write about than to debug.
 */
private const val WEAPON_BACKGROUND =
    "Background: a single flat solid chroma green (#00FF00) filling the entire background, " +
        "edge to edge. No scenery, no gradient, no texture, no vignette, no ground, no " +
        "shadow cast onto the background. Do NOT draw the grey and white checkerboard " +
        "pattern that image editors use to show transparency. The green must not appear " +
        "anywhere on the weapon itself."
