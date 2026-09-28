package com.stratum.app.world

import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.WorldSummary

/**
 * Worlds kept in memory for as long as the process lives.
 *
 * What the menus are previewed and tested against, and what the app runs on
 * until a store that writes to disk is plugged into `AppGraph`. Summaries are
 * kept apart from the saves so a rename touches only the name a menu shows,
 * and so this class never has to build a [WorldSave] itself: it only needs
 * [summaryOf] to read one.
 */
class InMemoryWorldSaveRepository(
    summaries: List<WorldSummary> = emptyList(),
    private val summaryOf: (WorldSave) -> WorldSummary = { it.summary },
) : WorldSaveRepository {

    private val summaries = summaries.associateBy { it.id }.toMutableMap()
    private val saves = HashMap<String, WorldSave>()

    override suspend fun list(): List<WorldSummary> = synchronized(this) {
        summaries.values.sortedByDescending { it.lastPlayedAt }
    }

    override suspend fun load(id: String): WorldSave? = synchronized(this) { saves[id] }

    override suspend fun save(save: WorldSave) {
        val summary = summaryOf(save)
        synchronized(this) {
            saves[summary.id] = save
            summaries[summary.id] = summary
        }
    }

    override suspend fun delete(id: String) {
        synchronized(this) {
            saves.remove(id)
            summaries.remove(id)
        }
    }

    override suspend fun rename(id: String, name: String) {
        synchronized(this) {
            summaries[id]?.let { summaries[id] = it.copy(name = name) }
        }
    }
}
