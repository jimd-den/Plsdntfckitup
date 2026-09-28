package com.stratum.core.domain.session

/**
 * What a menu needs to know about a saved world without loading it: enough to
 * draw its card and to say "Continue" with a name, a hero and a level.
 */
data class WorldSummary(
    val id: String, val name: String,
    val heroName: String, val heroClassId: String, val level: Int,
    val presetName: String, val seed: Long,
    val playSeconds: Long, val lastPlayedAt: Long, val createdAt: Long,
)

/** Where saved worlds are kept. The menus only ever list, rename and delete. */
interface WorldSaveRepository {
    suspend fun list(): List<WorldSummary>          // newest played first
    suspend fun load(id: String): WorldSave?
    suspend fun save(save: WorldSave)
    suspend fun delete(id: String)
    suspend fun rename(id: String, name: String)
}

/**
 * A whole saved world. Only its summary is modelled here; the world-save
 * workstream owns the full shape and replaces this class with it.
 */
class WorldSave(val summary: WorldSummary)
