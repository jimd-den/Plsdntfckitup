package com.stratum.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportedPackRepository
import com.stratum.core.domain.world.TerrainRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One imported pack, as the library lists it. */
data class ImportedPackSummary(
    val id: String,
    val name: String,
    val version: String,
    val maps: Int,
    val characters: Int,
    val classes: Int,
    /** Whether loading it replaces the world with its own level. */
    val replacesWorld: Boolean,
) {
    companion object {
        fun of(pack: ContentPack) = ImportedPackSummary(
            id = pack.id,
            name = pack.name,
            version = pack.version,
            maps = pack.maps.size,
            characters = pack.spriteSheets.size,
            classes = pack.heroClasses.size,
            replacesWorld = pack.terrain?.generatorId == TerrainRecipe.TILE_MAP,
        )
    }
}

/** How the last import went, for the player to read. */
sealed interface ImportStatus {
    data object Idle : ImportStatus
    data object Working : ImportStatus
    data class Imported(val packName: String, val importerName: String, val warnings: List<String>) : ImportStatus
    data class Failed(val reason: String) : ImportStatus
}

data class LibraryUiState(
    val packs: List<ImportedPackSummary> = emptyList(),
    val status: ImportStatus = ImportStatus.Idle,
)

/** Imports projects and lists what has been imported. Knows only the repository port. */
class LibraryViewModel(private val repository: ImportedPackRepository) : ViewModel() {

    private val status = MutableStateFlow<ImportStatus>(ImportStatus.Idle)

    val state: StateFlow<LibraryUiState> = combine(repository.packs, status) { packs, status ->
        LibraryUiState(packs.map(ImportedPackSummary::of), status)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun importArchive(name: String, bytes: ByteArray) {
        if (status.value == ImportStatus.Working) return
        status.value = ImportStatus.Working
        viewModelScope.launch {
            status.value = try {
                val outcome = repository.import(name, bytes)
                ImportStatus.Imported(outcome.pack.name, outcome.importerName, outcome.warnings)
            } catch (failure: ImportException) {
                ImportStatus.Failed(failure.message ?: "That file could not be imported")
            }
        }
    }

    /** A file that could not even be read, reported the same way as one that could not be imported. */
    fun reportUnreadable(reason: String) {
        status.value = ImportStatus.Failed(reason)
    }

    fun delete(packId: String) {
        viewModelScope.launch { repository.delete(packId) }
    }

    fun dismissStatus() {
        status.value = ImportStatus.Idle
    }

    companion object {
        fun factory(repository: ImportedPackRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
        }
    }
}
