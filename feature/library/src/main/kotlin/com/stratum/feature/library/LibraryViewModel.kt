package com.stratum.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.plugin.InstalledPlugin
import com.stratum.core.domain.plugin.PluginLibrary
import com.stratum.core.domain.plugin.PluginRepository
import com.stratum.core.domain.world.TerrainRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One installed plugin, as the library lists it. */
data class PluginSummary(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val enabled: Boolean,
    /** Enabled and resolved: its content is in the game. */
    val active: Boolean,
    /** Why an enabled plugin is not loading, if it is not. */
    val problem: String?,
    val maps: Int,
    val characters: Int,
    val classes: Int,
    val checks: Int,
    /** Whether loading it replaces the world with its own level. */
    val replacesWorld: Boolean,
    val isFirst: Boolean,
    val isLast: Boolean,
) {
    companion object {
        fun of(plugin: InstalledPlugin, library: PluginLibrary, index: Int, count: Int): PluginSummary {
            val pack = plugin.pack
            return PluginSummary(
                id = plugin.manifest.id,
                name = plugin.manifest.name,
                version = plugin.manifest.version.toString(),
                author = plugin.manifest.author,
                license = plugin.manifest.license,
                enabled = plugin.enabled,
                active = library.isActive(plugin.manifest.id),
                problem = library.problemFor(plugin.manifest.id)?.message,
                maps = pack.maps.size,
                characters = pack.spriteSheets.size,
                classes = pack.heroClasses.size,
                checks = pack.checks.size,
                replacesWorld = pack.terrain?.generatorId == TerrainRecipe.TILE_MAP,
                isFirst = index == 0,
                isLast = index == count - 1,
            )
        }
    }
}

/** How the last install went, for the player to read. */
sealed interface ImportStatus {
    data object Idle : ImportStatus
    data object Working : ImportStatus
    data class Imported(val packName: String, val importerName: String, val warnings: List<String>) : ImportStatus
    data class Failed(val reason: String) : ImportStatus
}

data class LibraryUiState(
    val plugins: List<PluginSummary> = emptyList(),
    val status: ImportStatus = ImportStatus.Idle,
)

/** Installs, orders and switches plugins. Knows only the repository port. */
class LibraryViewModel(private val repository: PluginRepository) : ViewModel() {

    private val status = MutableStateFlow<ImportStatus>(ImportStatus.Idle)

    val state: StateFlow<LibraryUiState> = combine(repository.library, status) { library, status ->
        val count = library.installed.size
        LibraryUiState(library.installed.mapIndexed { index, plugin -> PluginSummary.of(plugin, library, index, count) }, status)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun install(name: String, bytes: ByteArray) {
        if (status.value == ImportStatus.Working) return
        status.value = ImportStatus.Working
        viewModelScope.launch {
            status.value = try {
                val outcome = repository.install(name, bytes)
                ImportStatus.Imported(outcome.manifest.name, outcome.importerName, outcome.warnings)
            } catch (failure: ImportException) {
                ImportStatus.Failed(failure.message ?: "That file could not be imported")
            }
        }
    }

    /** A file that could not even be read, reported the same way as one that could not be installed. */
    fun reportUnreadable(reason: String) {
        status.value = ImportStatus.Failed(reason)
    }

    fun setEnabled(pluginId: String, enabled: Boolean) = launch { repository.setEnabled(pluginId, enabled) }

    fun moveEarlier(pluginId: String) = launch { repository.move(pluginId, -1) }

    fun moveLater(pluginId: String) = launch { repository.move(pluginId, 1) }

    fun uninstall(pluginId: String) = launch { repository.uninstall(pluginId) }

    fun dismissStatus() {
        status.value = ImportStatus.Idle
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun factory(repository: PluginRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
        }
    }
}
