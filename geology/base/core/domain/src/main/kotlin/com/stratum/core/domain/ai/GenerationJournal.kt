package com.stratum.core.domain.ai

/**
 * What a generation is doing right now.
 *
 * Reported rather than inferred, because the only honest progress bar is one
 * the thing doing the work fills in. An image model can sit on a request for
 * three minutes; a spinner that says nothing for three minutes is
 * indistinguishable from a hang.
 */
enum class GenerationStage(val label: String, val fraction: Float) {
    PREPARING("Writing the prompt", 0.08f),
    SENDING("Sending to the provider", 0.2f),
    WAITING("Waiting for the model", 0.45f),
    READING("Reading the reply", 0.7f),
    DECODING("Decoding the image", 0.82f),
    MEASURING("Measuring the sheet", 0.9f),
    SAVING("Saving", 0.96f),
    DONE("Done", 1f),
    FAILED("Failed", 1f),
}

/**
 * One call to a provider, kept so a failure can be read rather than guessed at.
 *
 * Holds the request body as it was actually sent and the response as it came
 * back. When a model rejects a sprite sheet the useful information is almost
 * always in the provider's own words, and summarising it into "the request was
 * rejected" throws away the only thing that would have explained why.
 *
 * The API key is never part of this: it travels in a header, and [redactedHeaders]
 * is what gets recorded.
 */
data class GenerationAttempt(
    val id: String,
    /** What the player asked for, e.g. "Sprite sheet: ancestral warrior". */
    val label: String,
    val endpoint: String,
    val model: String,
    val requestBody: String,
    val redactedHeaders: Map<String, String> = emptyMap(),
    /** Null when the call never reached a response at all. */
    val status: Int? = null,
    val responseBody: String? = null,
    /** Set when the attempt failed, in the clearest words available. */
    val failure: String? = null,
    val durationMillis: Long = 0,
) {
    val succeeded: Boolean get() = failure == null && status != null && status in 200..299

    /** A one-line summary for a list row. */
    val summary: String
        get() = when {
            succeeded -> "$model · ${status} · ${durationMillis}ms"
            status != null -> "$model · HTTP $status"
            else -> "$model · no reply"
        }
}

/**
 * The last few provider calls, newest first.
 *
 * Bounded: this exists to debug the call you just made, not to be an audit log,
 * and an unbounded buffer of base64 image payloads would be a memory leak with a
 * user interface.
 */
class GenerationJournal(private val capacity: Int = DEFAULT_CAPACITY) {

    private val attempts = ArrayDeque<GenerationAttempt>()

    val recent: List<GenerationAttempt> get() = attempts.toList()

    val latest: GenerationAttempt? get() = attempts.firstOrNull()

    fun record(attempt: GenerationAttempt) {
        attempts.addFirst(attempt)
        while (attempts.size > capacity) attempts.removeLast()
    }

    fun clear() = attempts.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 12
    }
}

/** Where an adapter reports what it is doing and what it sent. */
interface GenerationObserver {
    fun onStage(stage: GenerationStage) {}
    fun onAttempt(attempt: GenerationAttempt) {}

    companion object {
        /** For callers that do not care; every port takes this by default. */
        val None = object : GenerationObserver {}
    }
}
