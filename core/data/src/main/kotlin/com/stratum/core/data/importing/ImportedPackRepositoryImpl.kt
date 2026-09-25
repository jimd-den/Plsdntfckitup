package com.stratum.core.data.importing

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportOutcome
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.importing.ImportedPackRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** [ImportedPackRepository] over archives in app storage. */
class ImportedPackRepositoryImpl(
    private val store: ImportedPackStore,
    private val importProject: ImportProjectUseCase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ImportedPackRepository {

    private val _packs = MutableStateFlow(emptyList<ContentPack>())
    override val packs: StateFlow<List<ContentPack>> = _packs.asStateFlow()

    override suspend fun refresh() = withContext(io) {
        _packs.value = store.loadAll()
    }

    override suspend fun import(name: String, bytes: ByteArray): ImportOutcome = withContext(io) {
        val outcome = importProject(store.open(name, bytes))
        store.saveArchive(outcome.pack.id, name, bytes)
        _packs.value = _packs.value.filterNot { it.id == outcome.pack.id } + outcome.pack
        outcome
    }

    override suspend fun delete(packId: String) = withContext(io) {
        store.delete(packId)
        _packs.value = _packs.value.filterNot { it.id == packId }
    }
}
