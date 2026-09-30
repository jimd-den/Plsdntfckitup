package com.stratum.core.data.save

import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.WorldSummary
import java.io.File
import java.util.UUID

/**
 * The player's saved worlds, as the menus use them: list, continue, make,
 * rename and delete.
 *
 * Making a world here only names it -- a fresh id and a creation time --
 * because the world itself is made by the session that plays it, which
 * writes the first save as soon as it starts. Names are tidied here so
 * every screen that makes or renames a world gets the same rules.
 */
class WorldLibrary(
    val repository: WorldSaveRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Every saved world, most recently played first. */
    suspend fun list(): List<WorldSummary> = repository.list()

    /** The world played last, whole, or null when there is none: what "Continue" opens. */
    suspend fun latest(): WorldSave? = list().firstNotNullOfOrNull { repository.load(it.id) }

    suspend fun load(id: String): WorldSave? = repository.load(id)

    /**
     * A new world's identity, to hand to the session that will play it. Not
     * written until that session saves, so backing out of a new world before
     * it starts leaves no empty slot behind.
     */
    fun create(name: String, presetName: String = "", heroName: String = "", packIds: List<String> = emptyList()): WorldIdentity =
        WorldIdentity(newId(), tidy(name) ?: DEFAULT_NAME, clock(), presetName, packIds, heroName)

    suspend fun delete(id: String) = repository.delete(id)

    /** Renames a world; a blank name is ignored rather than leaving a slot nobody can read. */
    suspend fun rename(id: String, name: String) {
        tidy(name)?.let { repository.rename(id, it) }
    }

    private fun tidy(name: String): String? = name.trim().replace(WHITESPACE, " ").take(MAX_NAME).ifBlank { null }

    companion object {
        const val DEFAULT_NAME = "New world"
        const val MAX_NAME = 40
        private val WHITESPACE = Regex("\\s+")

        /** The app's library: one folder per world under `<files dir>/worlds`. Pass `context.filesDir`. */
        fun inFiles(filesDir: File): WorldLibrary = WorldLibrary(FileWorldSaveStore(File(filesDir, "worlds")))
    }
}
