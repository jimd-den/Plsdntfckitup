package com.stratum.core.domain.ai

/** What a generated model is for, which decides how it is described to the provider. */
enum class ModelSubjectKind(val description: String) {
    PROP("a piece of scenery that stands on the ground, such as a shrine, a barrel, a lantern or a rock"),
    WEAPON("a weapon on its own, laid out so its full length is visible"),
    CREATURE("a creature standing on all its feet in a neutral pose, arms or wings slightly away from its body"),
    STATUE("a carved statue on a plinth"),
    STRUCTURE("a small building or ruin seen whole, closed on every side"),
}

/** One model, described by the player in the forge. */
data class ModelBrief(
    val subject: String,
    val kind: ModelSubjectKind = ModelSubjectKind.PROP,
    /** The world's style in words; blank uses a plain low-poly look. */
    val style: String = "",
    /** A picture picked from the device, for image-to-3D. */
    val reference: ImageReference? = null,
    val modelId: String? = null,
    val textured: Boolean = true,
    val maxTriangles: Int = ModelRequest.DEFAULT_TRIANGLES,
) {
    /** A stable id for what was asked for, used to name the saved asset. */
    fun slug(): String {
        val base = subject.lowercase().replace(NON_ID, "_").trim('_').take(MAX_SLUG)
        return "${kind.name.lowercase()}_${base.ifBlank { "model" }}"
    }

    private companion object {
        val NON_ID = Regex("[^a-z0-9]+")
        const val MAX_SLUG = 32
    }
}

/**
 * Asks for a model and checks that what came back is one.
 *
 * The prompt is built here rather than in an adapter because it is the same
 * for every provider: what a game needs from a mesh — one object, whole,
 * grounded, no scenery — is a fact about the game, not about Meshy or fal.
 *
 * When the configured provider only turns pictures into models and the player
 * gave no picture, the reference is drawn first with [images]. That is not a
 * fallback so much as the standard shape of the pipeline: several of the best
 * mesh models are image-only, and the image models are already configured for
 * the sprite forge.
 */
class GenerateModelUseCase(
    private val models: ModelGenerationPort,
    private val images: ImageModelPort? = null,
) {

    suspend operator fun invoke(
        brief: ModelBrief,
        observer: ModelGenerationObserver = ModelGenerationObserver.None,
    ): Result<GeneratedModel> {
        if (brief.subject.isBlank() && brief.reference == null) {
            return Result.failure(GenerationException("Describe the model, or give it a picture to work from"))
        }
        val capabilities = models.capabilities
        if (!capabilities.textToModel && !capabilities.imageToModel) {
            return Result.failure(GenerationException("The configured 3D provider accepts neither text nor pictures", fatal = true))
        }
        val reference = brief.reference ?: if (!capabilities.textToModel) {
            drawReference(brief, observer).getOrElse { return Result.failure(it) }
        } else {
            null
        }
        val request = ModelRequest(
            prompt = ModelPrompts.modelPrompt(brief),
            reference = reference.takeIf { capabilities.imageToModel },
            modelId = brief.modelId,
            textured = brief.textured,
            maxTriangles = brief.maxTriangles,
            negativePrompt = ModelPrompts.NEGATIVE,
        )
        val generated = models.generateModel(request, observer).getOrElse { return Result.failure(it) }
        val format = ModelFormat.sniff(generated.bytes)
            ?: return Result.failure(
                GenerationException("The provider answered with something that is not a GLB or OBJ model (${generated.bytes.size} bytes)"),
            )
        return Result.success(if (format == generated.format) generated else generated.copy(format = format))
    }

    private suspend fun drawReference(brief: ModelBrief, observer: ModelGenerationObserver): Result<ImageReference> {
        val painter = images ?: return Result.failure(
            GenerationException("This 3D model needs a picture to work from. Pick one, or configure an image model to draw it.", fatal = true),
        )
        val drawn = painter.generateImage(
            ImageRequest(prompt = ModelPrompts.referencePrompt(brief), width = REFERENCE_EDGE, height = REFERENCE_EDGE, requireTransparency = false),
            observer,
        ).getOrElse { return Result.failure(it) }
        if (drawn.bytes.isEmpty()) return Result.failure(GenerationException("The reference picture came back empty"))
        return Result.success(ImageReference(drawn.bytes, drawn.mimeType))
    }

    private companion object {
        const val REFERENCE_EDGE = 1024
    }
}

/**
 * The words sent with a model request.
 *
 * Kept apart from the use case so the contract can be tested line by line:
 * each clause exists because a mesh without it fails in the game in a
 * particular way.
 */
object ModelPrompts {

    /** What every provider that takes a negative prompt is told to avoid. */
    const val NEGATIVE = "ground plane, floor, base platform, scenery, background, multiple objects, text, low quality, broken mesh"

    /**
     * The text prompt.
     *
     * Short, because mesh models are trained on short captions and read a
     * paragraph worse than a sentence. Every clause earns its place:
     * *single object* because a scene cannot be one prop; *whole and closed*
     * because an open shell voxelises to a hollow smear; *standing upright*
     * because the pipeline grounds the lowest point and a model lying on its
     * side becomes a very wide, very short prop.
     */
    fun modelPrompt(brief: ModelBrief): String = buildString {
        append(brief.subject.trim().ifBlank { "the object in the picture" })
        append(". ")
        append("A single game-ready 3D model of ${brief.kind.description}. ")
        append("One object alone, whole and closed, standing upright, centred, nothing else in the scene. ")
        append(brief.style.trim().ifBlank { DEFAULT_STYLE })
        append(" Clear readable colours; ")
        append("simple shapes that read from far above.")
    }

    /**
     * The picture drawn for an image-only mesh model.
     *
     * A three-quarter view on a plain background, because that is what image-
     * to-3D models reconstruct best: a flat-on view loses depth and a busy
     * background gets built into the mesh.
     */
    fun referencePrompt(brief: ModelBrief): String = buildString {
        appendLine("A single object for a 3D game: ${brief.subject.trim()}.")
        appendLine("It is ${brief.kind.description}.")
        appendLine(brief.style.trim().ifBlank { DEFAULT_STYLE })
        appendLine("Seen whole from a three-quarter view, slightly from above, centred, filling most of the image.")
        appendLine("Evenly lit, no strong shadows. Plain flat light grey background, no floor, no scenery.")
        appendLine("One object only. No text, no border, no second view.")
    }

    private const val DEFAULT_STYLE = "Stylised low-poly game art with hand-painted colours."
}
