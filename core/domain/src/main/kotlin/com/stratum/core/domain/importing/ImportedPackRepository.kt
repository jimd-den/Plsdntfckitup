package com.stratum.core.domain.importing

import com.stratum.core.domain.content.ContentPack
import kotlinx.coroutines.flow.StateFlow

/**
 * The projects a player has imported, kept between runs.
 *
 * The screen that imports and the shell that assembles content both hold this
 * port, never the storage behind it.
 */
interface ImportedPackRepository {
    /** Imported packs, in the order they load. */
    val packs: StateFlow<List<ContentPack>>

    /** Re-reads what was imported in earlier runs. Empty until called once. */
    suspend fun refresh()

    /**
     * Imports an archive the player picked and keeps it.
     *
     * @throws ImportException when it cannot become a pack.
     */
    suspend fun import(name: String, bytes: ByteArray): ImportOutcome

    suspend fun delete(packId: String)
}
