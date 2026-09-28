package com.stratum.app.world

import com.stratum.core.data.save.WorldLibrary
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The saved worlds as the menus see them: a list that stays current, and the
 * things a menu does to a world -- continue, make, rename, delete.
 *
 * The menus' only way to the world store. The [WorldLibrary] underneath
 * answers suspending calls; this keeps its answer as state a screen can
 * collect, and refreshes it after every change so no screen has to.
 */
class SavedWorlds(
    val library: WorldLibrary,
    private val scope: CoroutineScope,
) {
    private val summaries = MutableStateFlow<List<WorldSummary>>(emptyList())

    /** Newest played first. */
    val worlds: StateFlow<List<WorldSummary>> = summaries.asStateFlow()

    /** Reads the list again: on arriving at a menu, and after leaving a world, which is when it was last saved. */
    fun refresh() {
        scope.launch { summaries.value = library.list() }
    }

    fun rename(id: String, name: String) {
        scope.launch {
            library.rename(id, name)
            summaries.value = library.list()
        }
    }

    fun delete(id: String) {
        scope.launch {
            library.delete(id)
            summaries.value = library.list()
        }
    }

    /** The whole saved world, for the play session that resumes it. */
    suspend fun load(id: String): WorldSave? = library.load(id)

    /** A new world's identity; nothing is written until its session first saves. */
    fun create(name: String, presetName: String, heroName: String, packIds: List<String>): WorldIdentity =
        library.create(name = name, presetName = presetName, heroName = heroName, packIds = packIds)
}
