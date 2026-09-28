package com.stratum.app.world

import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.WorldSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The saved worlds as the menus see them: a list that stays current, and the
 * three things a menu does to a world -- continue, rename, delete.
 *
 * The seam between the menus and whatever stores worlds. The menus never
 * touch a [WorldSaveRepository] directly, so pointing the app at a different
 * store is one constructor argument in `AppGraph` and nothing else moves.
 */
class WorldLibrary(
    private val repository: WorldSaveRepository,
    private val scope: CoroutineScope,
) {
    private val summaries = MutableStateFlow<List<WorldSummary>>(emptyList())

    /** Newest played first, as the repository lists them. */
    val worlds: StateFlow<List<WorldSummary>> = summaries.asStateFlow()

    /** Reads the list again: after leaving a world, which is when it was last saved. */
    fun refresh() {
        scope.launch { summaries.value = repository.list() }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            repository.rename(id, trimmed)
            summaries.value = repository.list()
        }
    }

    fun delete(id: String) {
        scope.launch {
            repository.delete(id)
            summaries.value = repository.list()
        }
    }

    /** The whole saved world, for the play session that resumes it. */
    suspend fun load(id: String): WorldSave? = repository.load(id)

    /** Stores a world, for the play session that saves it, and refreshes the list. */
    suspend fun save(save: WorldSave) {
        repository.save(save)
        summaries.value = repository.list()
    }
}
