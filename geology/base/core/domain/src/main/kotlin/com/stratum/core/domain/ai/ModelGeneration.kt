package com.stratum.core.domain.ai

/**
 * The capability of asking a model for a 3D model.
 *
 * Shaped like [VideoModelPort] rather than [ImageModelPort], because every
 * provider that sells meshes sells them as a job: the request returns an id at
 * once, the mesh takes one to five minutes, and it is fetched from a URL when
 * it is done. The port hides the submit, the polling and the download behind
 * one suspend function, and reports the waiting through [ModelGenerationObserver]
 * so a screen can show a real progress bar instead of a spinner that looks
 * exactly like a hang.
 *
 * What comes back is bytes and a format, never a parsed mesh: parsing a GLB is
 * pure-Kotlin work that belongs in `:engine:model`, where it can be tested
 * without a network, and an adapter should only move bytes.
 */
interface ModelGenerationPort {
    /**
     * What the configured provider can do with a request.
     *
     * Asked before generating rather than discovered by failing, because the
     * answer changes what a caller sends: an image-only model given a bare
     * prompt is not an error to report but a reference to draw first.
     */
    val capabilities: ModelCapabilities get() = ModelCapabilities()

    suspend fun generateModel(
        request: ModelRequest,
        observer: ModelGenerationObserver = ModelGenerationObserver.None,
    ): Result<GeneratedModel>
}

/** Which kinds of request a provider accepts. */
data class ModelCapabilities(
    val textToModel: Boolean = true,
    val imageToModel: Boolean = true,
    val formats: Set<ModelFormat> = setOf(ModelFormat.GLB),
)

/**
 * One model, asked for by description, by picture, or by both.
 *
 * [maxTriangles] is a request, not a guarantee: providers that take a target
 * polycount are sent it, and the model pipeline caps what arrives anyway,
 * because a phone draws hundreds of props and a single million-triangle statue
 * would cost more than the whole terrain.
 */
data class ModelRequest(
    val prompt: String,
    /** A picture of the thing, for image-to-3D. Wins over [prompt] where a provider takes only one. */
    val reference: ImageReference? = null,
    /** Null lets the adapter use whichever model the player configured. */
    val modelId: String? = null,
    val format: ModelFormat = ModelFormat.GLB,
    /** False asks for bare geometry, which is faster and cheaper where a provider charges for texturing. */
    val textured: Boolean = true,
    val maxTriangles: Int = DEFAULT_TRIANGLES,
    val negativePrompt: String = "",
) {
    companion object {
        /** Enough for a readable statue at the size a prop is drawn, and cheap on a phone. */
        const val DEFAULT_TRIANGLES = 20_000
    }
}

/** The container a model arrives in. */
enum class ModelFormat(val mimeType: String, val extension: String) {
    /** Binary glTF 2.0: one file with the geometry, materials and textures inside. */
    GLB("model/gltf-binary", "glb"),

    /** Wavefront OBJ: geometry only, accepted because some endpoints offer nothing else. */
    OBJ("model/obj", "obj"),
    ;

    companion object {
        /**
         * Which format [bytes] are, read from the bytes rather than a header.
         *
         * A provider that answers a 200 with an HTML error page, or with a zip
         * when a GLB was asked for, would otherwise be saved as a model and
         * fail far from the call that caused it.
         */
        fun sniff(bytes: ByteArray): ModelFormat? = when {
            bytes.size >= 12 && bytes[0] == 'g'.code.toByte() && bytes[1] == 'l'.code.toByte() &&
                bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte() -> GLB
            looksLikeObj(bytes) -> OBJ
            else -> null
        }

        private fun looksLikeObj(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, OBJ_SNIFF_BYTES), Charsets.US_ASCII)
            return head.lineSequence().map(String::trim).any { it.startsWith("v ") } &&
                head.none { it.code == 0 }
        }

        private const val OBJ_SNIFF_BYTES = 4096
    }
}

/** A model as it came back, unparsed. */
data class GeneratedModel(
    val bytes: ByteArray,
    val format: ModelFormat,
    /** The provider's id for the job, kept so a result can be traced back to its request. */
    val jobId: String? = null,
) {
    // ByteArray compares by identity, which would make two copies of the same
    // model unequal and quietly break every test that checks what came back.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedModel) return false
        return format == other.format && jobId == other.jobId && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + (jobId?.hashCode() ?: 0)
        return result
    }
}

/** Where a model job has got to. */
enum class ModelJobPhase(val label: String) {
    UPLOADING("Sending the reference"),
    QUEUED("Waiting in the provider's queue"),
    RUNNING("Building the model"),
    TEXTURING("Painting the model"),
    DOWNLOADING("Downloading the model"),
}

/**
 * A progress report from a running job.
 *
 * [fraction] is null when the provider says only that it is working; several
 * do, and a bar that invents a number is worse than one that says it does not
 * know.
 */
data class ModelJobProgress(
    val phase: ModelJobPhase,
    val fraction: Float? = null,
    val detail: String = "",
)

/**
 * A [GenerationObserver] that also hears how far a job has got.
 *
 * An extension of the shared observer rather than a second callback, so the
 * generation journal and stage reporting work exactly as they do for images
 * and clips, and a caller that does not care about progress passes nothing.
 */
interface ModelGenerationObserver : GenerationObserver {
    fun onProgress(progress: ModelJobProgress) {}

    companion object {
        val None = object : ModelGenerationObserver {}

        /** Wraps a plain observer, for callers that already have one. */
        fun of(observer: GenerationObserver, onProgress: (ModelJobProgress) -> Unit = {}): ModelGenerationObserver =
            object : ModelGenerationObserver {
                override fun onStage(stage: GenerationStage) = observer.onStage(stage)
                override fun onAttempt(attempt: GenerationAttempt) = observer.onAttempt(attempt)
                override fun onProgress(progress: ModelJobProgress) = onProgress(progress)
            }
    }
}
