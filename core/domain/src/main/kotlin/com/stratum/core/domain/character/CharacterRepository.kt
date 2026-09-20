package com.stratum.core.domain.character

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for observing and managing the lifecycle of [Character] aggregates.
 *
 * Provides a single source of truth for all characters on disk, eliminating
 * manual cross-store reconciliation and counter-bumping.
 */
interface CharacterRepository {
    /** Observable stream of all characters currently on disk, newest first. */
    val characters: StateFlow<List<Character>>

    /** Returns the current snapshot of characters on disk. */
    fun all(): List<Character>

    /** Finds a character by its set id, or null if not found. */
    fun find(setId: String): Character?

    /**
     * Atomically deletes a character and all its associated assets:
     * raw poses, reference image, packed sheet, pose guides, and weapon fit.
     */
    fun delete(setId: String)

    /** Forces a reload of character assets from disk and emits the updated state. */
    fun refresh()
}
