package com.stratum.core.domain.ai

/**
 * A character that exists on disk, as the person thinks of it.
 *
 * The pipeline already stored everything: a reference image, forty-two poses,
 * the guides they were drawn against, the weapon fit. What it had no notion of
 * was a *character* -- the id is derived from the subject line, so the only
 * way back to a set was to retype exactly what it had once been called. Work
 * that costs real money and half an hour was reachable only by remembering a
 * spelling.
 *
 * This is the missing noun. It owns no new storage: it is what the stores
 * already hold, named and counted so it can be listed, reopened and deleted.
 */
data class SavedCharacter(
    val setId: String,
    /** What the person typed, recovered from the id. */
    val name: String,
    /** How many poses are drawn, which is the honest measure of progress. */
    val posesDrawn: Int,
    val hasReference: Boolean,
    /** Set once the poses have been packed into a sheet the game can draw. */
    val sheetId: String? = null,
) {
    /** Whether there is anything here worth coming back to. */
    val isEmpty: Boolean get() = posesDrawn == 0 && !hasReference

    companion object {
        /**
         * The subject line, read back out of the id it was turned into.
         *
         * Lossy on purpose rather than by accident: the id is a slug, so
         * "Bronze Warrior" and "bronze_warrior" are the same character and
         * always were. Recovering a readable name from the slug is honest
         * about that, where storing the original text alongside would invent a
         * distinction the rest of the pipeline does not have.
         */
        fun nameOf(setId: String): String =
            setId.substringAfter(':').replace('_', ' ').trim()
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                .ifBlank { setId }
    }
}
