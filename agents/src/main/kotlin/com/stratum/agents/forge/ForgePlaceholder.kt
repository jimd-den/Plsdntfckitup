package com.stratum.agents.forge

/**
 * The card shown the moment a request is made, before the model has said a
 * word: a name taken from the prompt and the kind that was asked for.
 *
 * It exists so asking is instant. The player sees their thing appear, named in
 * their own words, and watches it fill in; the model's reply replaces it
 * rather than arriving out of nowhere after a wait.
 */
object ForgePlaceholder {

    const val SECTION = "pending"

    fun of(kind: ForgeKind, prompt: String): ForgeCard = ForgeCard(
        section = SECTION,
        id = "",
        glyph = kind.glyph,
        title = nameFrom(prompt).ifBlank { "Untitled ${kind.label.lowercase()}" },
        subtitle = "${kind.label} · being written",
        lines = emptyList(),
        text = prompt.trim(),
    )

    /**
     * A working title from a prompt: its opening words, less the articles and
     * filler a person types before the noun, capitalised like a name.
     * "A heavy bronze cleaver cast for executions" becomes "Heavy Bronze Cleaver".
     */
    fun nameFrom(prompt: String): String {
        val words = prompt.trim().split(WHITESPACE).map { it.trim(*PUNCTUATION) }.filter { it.isNotEmpty() }
        val start = words.indexOfFirst { it.lowercase() !in LEADING }.takeIf { it >= 0 } ?: return ""
        val title = words.drop(start).takeWhile { it.lowercase() !in BREAKS }.take(MAX_WORDS)
            .ifEmpty { words.drop(start).take(1) }
        return title.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = charArrayOf(',', '.', ';', ':', '!', '?', '"', '\'', '(', ')')
    private const val MAX_WORDS = 3
    private val LEADING = setOf("a", "an", "the", "some", "make", "write", "forge", "create", "me", "about")

    /** Words that end the name and begin the description: "a cleaver *cast for* executions". */
    private val BREAKS = setOf(
        "that", "which", "who", "whose", "with", "for", "from", "of", "to", "in", "on", "by", "and", "but",
        "cast", "made", "worn", "woven", "forged", "about", "where", "when", "because",
    )
}
