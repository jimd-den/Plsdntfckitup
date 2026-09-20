package com.stratum.core.domain.ai

/**
 * The capability of asking a model for a moving clip.
 *
 * Separate from [ImageModelPort] rather than a flag on it, because the two
 * answer differently shaped questions. An image model is handed a pose and
 * returns that pose. A video model is handed the ends of a movement and returns
 * the movement, and what comes back is bytes in a container that has to be
 * decoded before a single frame exists — which is Android's job, not the
 * domain's, exactly as raw image bytes already are.
 *
 * Why bother, when the still pipeline works: consistency between frames is the
 * one thing separate generations cannot give you. Six stills of the same
 * character under six guides come back as the same character six times *nearly*
 * — measured on real output, one frame of a walk arrived wearing a cape the
 * other five did not have. Frames cut out of one clip cannot disagree with each
 * other like that, because they were never generated apart.
 *
 * What it cannot give you is the pose. A clip takes no per-frame stick figure,
 * so a whole animation generated this way is a plausible walk rather than the
 * authored one, its camera drifts, and its last frame does not hand back to its
 * first. That is why [firstFrame] and [lastFrame] are here: conditioned on two
 * stills the pipeline already knows how to draw under a guide, a clip fills the
 * gap between two authored poses instead of replacing them.
 */
interface VideoModelPort {
    /**
     * [observer] reports what was sent and what came back, for the same reason
     * the image port takes one: a model that rejects a request is only
     * debuggable if the request can be read afterwards.
     */
    suspend fun generateClip(
        request: ClipRequest,
        observer: GenerationObserver = GenerationObserver.None,
    ): Result<GeneratedClip>
}

/** A short clip, asked for as the movement between two drawings. */
data class ClipRequest(
    val prompt: String,
    val modelId: String? = null,
    /**
     * The drawing the clip starts on.
     *
     * The whole value of this path. Without it the model invents a character
     * and a pose; with it the clip begins on a frame the pipeline drew under a
     * stick figure guide, so the movement starts from the authored pose rather
     * than near it.
     */
    val firstFrame: ImageReference? = null,
    /**
     * The drawing the clip ends on, where the provider supports it.
     *
     * Optional because not every model takes an end frame, and one that does
     * not is still useful — it just means the far end of the segment drifts and
     * the next authored keyframe has to be trusted to pull it back.
     */
    val lastFrame: ImageReference? = null,
    /** How long a clip to ask for. Short: this spans one authored beat, not an animation. */
    val seconds: Float = DEFAULT_SECONDS,
    val width: Int = 1024,
    val height: Int = 1024,
) {
    companion object {
        /**
         * Long enough to move, short enough not to invent.
         *
         * A segment between two authored poses is a fraction of a second of
         * animation. Asking for four seconds of it does not buy smoother
         * in-betweens, it buys three seconds of the model deciding what happens
         * next — which is the drift this path exists to avoid.
         */
        const val DEFAULT_SECONDS = 1f
    }
}

/**
 * A clip as it came back, undecoded.
 *
 * Bytes and a container type, because the domain cannot decode video any more
 * than it can decode a PNG. [durationMillis] is what a [ClipCut]'s fraction is
 * multiplied by, and it is nullable because a provider that does not report it
 * leaves the decoder to read it off the container.
 *
 * @see com.stratum.core.domain.sprite.ClipSampling
 */
data class GeneratedClip(
    val bytes: ByteArray,
    val mimeType: String,
    val durationMillis: Long? = null,
) {
    // ByteArray compares by identity, which would make two copies of the same
    // clip unequal and quietly break every test that checks what came back.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedClip) return false
        return mimeType == other.mimeType &&
            durationMillis == other.durationMillis &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (durationMillis?.hashCode() ?: 0)
        return result
    }
}
