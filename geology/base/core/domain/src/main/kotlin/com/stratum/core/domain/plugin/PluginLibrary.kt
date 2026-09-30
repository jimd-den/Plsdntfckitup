package com.stratum.core.domain.plugin

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportOutcome
import kotlinx.coroutines.flow.StateFlow

/** A plugin on this device: what it says about itself, what it contains, and whether the player wants it. */
data class InstalledPlugin(val manifest: PluginManifest, val pack: ContentPack, val enabled: Boolean)

/**
 * Every installed plugin in the player's order, and what the resolver made
 * of them: which load, in what order, and why any do not.
 */
data class PluginLibrary(
    val installed: List<InstalledPlugin> = emptyList(),
    val resolution: PluginResolution = PluginResolution(emptyList(), emptyList()),
) {
    /** The packs to assemble, in load order. Only plugins that resolved. */
    val activePacks: List<ContentPack>
        get() = resolution.loadOrder.mapNotNull { id -> installed.firstOrNull { it.manifest.id == id }?.pack }

    fun problemFor(pluginId: String): PluginProblem? = resolution.problems.firstOrNull { it.pluginId == pluginId }

    fun isActive(pluginId: String): Boolean = pluginId in resolution.loadOrder
}

/**
 * The player's plugin order and which are switched on.
 *
 * Order matters because later plugins override earlier ones, and it is the
 * player's to decide; the resolver only breaks it to load a dependency first.
 * Kept as plain lines -- `+id` enabled, `-id` disabled -- so the file is
 * readable and survives a plugin being removed behind the app's back.
 */
data class PluginOrder(val entries: List<Entry> = emptyList()) {

    data class Entry(val id: String, val enabled: Boolean)

    val ids: List<String> get() = entries.map { it.id }

    val enabledIds: List<String> get() = entries.filter { it.enabled }.map { it.id }

    fun isEnabled(id: String): Boolean = entries.firstOrNull { it.id == id }?.enabled ?: false

    /** Drops plugins no longer installed; appends new ones, switched on, at the end, where they win. */
    fun reconciledWith(installedIds: List<String>): PluginOrder {
        val kept = entries.filter { it.id in installedIds }
        val added = installedIds.filter { id -> kept.none { it.id == id } }.map { Entry(it, enabled = true) }
        return PluginOrder(kept + added)
    }

    fun withEnabled(id: String, enabled: Boolean): PluginOrder =
        PluginOrder(entries.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Moves a plugin [by] places; negative is earlier. Clamped to the ends. */
    fun moved(id: String, by: Int): PluginOrder {
        val from = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return this
        val to = (from + by).coerceIn(0, entries.lastIndex)
        return PluginOrder(entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun encode(): String = entries.joinToString("\n") { (if (it.enabled) "+" else "-") + it.id }

    companion object {
        fun decode(text: String): PluginOrder = PluginOrder(
            text.lines().map(String::trim).filter { it.length > 1 && it[0] in "+-" }
                .map { Entry(it.substring(1), enabled = it[0] == '+') }
                .distinctBy { it.id },
        )
    }
}

/**
 * The plugins on this device, kept between runs.
 *
 * The screen that manages them and the shell that assembles content hold this
 * port, never the storage behind it.
 */
interface PluginRepository {
    val library: StateFlow<PluginLibrary>

    /** Re-reads what was installed in earlier runs. Empty until called once. */
    suspend fun refresh()

    /**
     * Installs anything the importers understand: a `.stratum` plugin, a
     * Flame game, Tiled maps. A new plugin is switched on and loads last.
     *
     * @throws com.stratum.core.domain.importing.ImportException when it cannot become a pack.
     */
    suspend fun install(name: String, bytes: ByteArray): ImportOutcome

    suspend fun uninstall(pluginId: String)

    suspend fun setEnabled(pluginId: String, enabled: Boolean)

    /** Moves a plugin earlier (negative) or later in the load order. */
    suspend fun move(pluginId: String, by: Int)
}
