package com.stratum.core.domain.creation

import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.content.ContentPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A world that can be played the moment it is described: the packs already
 * loaded, the rules already chosen, and a look read from the same sentence by
 * the [StyleLexicon], which needs no model and cannot fail.
 *
 * The crew's own pack for the prompt is written in the background and joins
 * the world later; this is what stands in until it does, and what is played if
 * it never arrives.
 */
data class InstantWorld(
    val name: String,
    val prompt: String,
    /** Handed to play as the world's style; the lexicon reads it there. */
    val stylePrompt: String,
    /** What the lexicon made of it, in its own words, to show before play. */
    val styleSummary: String,
    /** The words it recognised, so a player sees which of theirs shaped the look. */
    val recognised: List<String>,
) {
    companion object {
        /** Reads [prompt] into a world at once. [name] defaults to the prompt's first words. */
        fun from(prompt: String, name: String = ""): InstantWorld {
            val trimmed = prompt.trim()
            val reading = StyleLexicon.interpret(trimmed)
            return InstantWorld(
                name = name.trim().ifBlank { nameFrom(trimmed) },
                prompt = trimmed,
                stylePrompt = trimmed,
                styleSummary = reading.direction.summary,
                recognised = reading.matched.map { it.id },
            )
        }

        private fun nameFrom(prompt: String): String =
            prompt.split(Regex("[\\s,;.]+")).filter { it.isNotBlank() }.take(3).joinToString(" ")
                .replaceFirstChar { it.uppercase() }.ifBlank { "New world" }
    }
}

/** What happened to a finished pack when it arrived. */
enum class PackDelivery {
    /** Nothing was being played: it was installed and the next world is built with it. */
    INSTALLED,

    /** A world was running: it waits, and joins the next time a world is entered. */
    HELD,
}

/**
 * Where the packs background jobs finish wait for a safe moment to join the game.
 *
 * A pack is never swapped in under a running session: the world would be
 * rebuilt around the player mid-step. One that arrives while a world is being
 * played is held and handed over when play ends; one that arrives before play
 * starts is handed over at once, so the world is built with it.
 */
class PackInbox {

    private val _held = MutableStateFlow<List<ContentPack>>(emptyList())
    val held: StateFlow<List<ContentPack>> = _held.asStateFlow()

    /** Delivers [pack] through [install] now, or holds it while [sessionRunning]. */
    fun arrive(pack: ContentPack, sessionRunning: Boolean, install: (ContentPack) -> Unit): PackDelivery {
        if (!sessionRunning) {
            install(pack)
            return PackDelivery.INSTALLED
        }
        // A newer draft of the same pack replaces the one still waiting.
        _held.update { waiting -> waiting.filterNot { it.id == pack.id } + pack }
        return PackDelivery.HELD
    }

    /** Hands over everything held, once play has ended. */
    fun release(install: (ContentPack) -> Unit) {
        _held.getAndUpdate { emptyList() }.forEach(install)
    }
}
