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
     * The drawing the clip ends on.
     *
     * Checked rather than hoped for: the catalogue reports first and last frame
     * control on every one of these models, so a clip can be pinned at both
     * ends by stills the pipeline drew under a guide.
     *
     * Which is what makes a looping animation possible at all. Set both ends to
     * the *same* drawing and the clip is a movement that returns to where it
     * started — the walk closing its own loop, rather than ending near enough
     * to its first frame and hitching once per stride. Nothing else on this
     * path can produce that.
     *
     * Still optional, because a provider that drops it leaves the far end to
     * drift and the next authored keyframe to pull it back, which is worse but
     * not useless.
     */
    val lastFrame: ImageReference? = null,
    /**
     * How long a clip to ask for, in whole seconds.
     *
     * Not a free number: providers publish a set of durations they accept and
     * reject anything else, and the floor is four seconds. That is the single
     * fact that decides how this path is worth using.
     *
     * A segment between two authored poses is a fraction of a second of
     * animation, and four seconds is the least that can be bought. So paying
     * for a clip per beat to take one frame out of each is the *expensive*
     * shape, not the cheap one — it buys eighty-odd frames and throws away all
     * but one. A clip spanning a whole animation, cut into as many frames as
     * the row wants, buys the same four seconds and uses them.
     */
    val seconds: Int = MIN_SECONDS,
    /** One of the provider's published sizes. Square, because a sprite cell is. */
    val width: Int = DEFAULT_EDGE,
    val height: Int = DEFAULT_EDGE,
    /**
     * Sound, which a sprite sheet has no use for and is charged for.
     *
     * Off is not a tidiness preference. Measured against the published rates
     * for the model this was built for, generating without audio is half the
     * price of generating with it, for output that is discarded either way.
     */
    val generateAudio: Boolean = false,
) {
    /** Clamped to what providers accept, so a bad number fails here rather than mid-run. */
    val durationSeconds: Int get() = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    companion object {
        /**
         * The shortest clip any of these providers sells.
         *
         * Checked against the catalogue rather than assumed: every video model
         * listing a duration set starts at four. Asking for one second is
         * rejected, not rounded up.
         */
        const val MIN_SECONDS = 4

        /** The longest the cheapest model accepts; others go further. */
        const val MAX_SECONDS = 12

        /**
         * Square and modest.
         *
         * The frames are cut down to a sprite cell a couple of hundred pixels
         * tall, so resolution above this is paid for and then thrown away —
         * and video is priced by the pixel far more steeply than stills are.
         */
        const val DEFAULT_EDGE = 720
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
