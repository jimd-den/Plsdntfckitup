package com.stratum.core.data.importing

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportOutcome
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.plugin.InstalledPlugin
import com.stratum.core.domain.plugin.PluginLibrary
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginOrder
import com.stratum.core.domain.plugin.PluginRepository
import com.stratum.core.domain.plugin.PluginResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PluginRepository] over archives in app storage. Every change is saved and
 * then resolved again, so what the shell assembles is always what the
 * resolver allowed.
 */
class PluginRepositoryImpl(
    private val store: ImportedPackStore,
    private val installProject: ImportProjectUseCase,
    /** What the build ships and always loads first; plugins may depend on it. */
    private val builtIn: List<PluginManifest> = emptyList(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PluginRepository {

    private val _library = MutableStateFlow(PluginLibrary())
    override val library: StateFlow<PluginLibrary> = _library.asStateFlow()

    /** One change at a time: installs, reorders and toggles all rewrite the same order file. */
    private val lock = Mutex()
    private var installed: List<Pair<PluginManifest, ContentPack>> = emptyList()
    private var order = PluginOrder()

    override suspend fun refresh() = change {
        installed = store.loadAll()
        order = store.loadOrder()
    }

    override suspend fun install(name: String, bytes: ByteArray): ImportOutcome {
        lateinit var outcome: ImportOutcome
        change {
            outcome = installProject(store.open(name, bytes))
            store.saveArchive(outcome.pack.id, name, bytes)
            installed = installed.filterNot { it.first.id == outcome.manifest.id } + (outcome.manifest to outcome.pack)
        }
        return outcome
    }

    override suspend fun uninstall(pluginId: String) = change {
        store.delete(pluginId)
        installed = installed.filterNot { it.first.id == pluginId }
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) = change { order = order.withEnabled(pluginId, enabled) }

    override suspend fun move(pluginId: String, by: Int) = change { order = order.moved(pluginId, by) }

    /** Applies [edit], keeps the order in step with what is installed, saves it, and resolves again. */
    private suspend fun change(edit: suspend () -> Unit) = withContext(io) {
        lock.withLock {
            edit()
            order = order.reconciledWith(installed.map { it.first.id })
            store.saveOrder(order)
            publish()
        }
    }

    private fun publish() {
        val byId = installed.associateBy { it.first.id }
        val plugins = order.ids.mapNotNull { id -> byId[id]?.let { (manifest, pack) -> InstalledPlugin(manifest, pack, order.isEnabled(id)) } }
        _library.value = PluginLibrary(plugins, PluginResolver.resolve(plugins.map { it.manifest }, order.enabledIds, builtIn))
    }
}
