package com.stratum.core.domain.plugin

/** Why a plugin was not loaded, for the player to read. */
sealed interface PluginProblem {
    val pluginId: String
    val message: String

    data class MissingDependency(override val pluginId: String, val dependency: PluginDependency) : PluginProblem {
        override val message = "'$pluginId' needs '${dependency.id}' ${dependency.versions}, which is not installed or not enabled"
    }

    data class WrongVersion(override val pluginId: String, val dependency: PluginDependency, val found: Version) : PluginProblem {
        override val message = "'$pluginId' needs '${dependency.id}' ${dependency.versions}, but $found is installed"
    }

    data class NewerApi(override val pluginId: String, val wanted: Int) : PluginProblem {
        override val message = "'$pluginId' needs a newer Stratum (plugin API $wanted; this build has ${StratumApi.LEVEL})"
    }

    data class DependsOnRefused(override val pluginId: String, val dependencyId: String) : PluginProblem {
        override val message = "'$pluginId' was left out because '$dependencyId', which it needs, was left out"
    }

    data class Cycle(override val pluginId: String, val through: List<String>) : PluginProblem {
        override val message = "'$pluginId' depends on itself through ${through.joinToString(" -> ")}"
    }
}

/** Which plugins load, in what order, and why any did not. */
data class PluginResolution(val loadOrder: List<String>, val problems: List<PluginProblem>)

/**
 * Decides what loads and in what order.
 *
 * The player's order is kept wherever it can be, because later plugins
 * override earlier ones and that is the player's call. It is broken only to
 * put a dependency before the plugin that needs it. A plugin that cannot load
 * is left out with a reason, and so is anything that depended on it -- one
 * broken mod never stops the rest of the game.
 */
object PluginResolver {

    /**
     * @param builtIn what this build ships and always loads first, such as its
     *   own content pack; a plugin may depend on it like any other.
     */
    fun resolve(installed: List<PluginManifest>, enabledInOrder: List<String>, builtIn: List<PluginManifest> = emptyList()): PluginResolution {
        val byId = installed.associateBy { it.id }
        val enabled = enabledInOrder.filter { it in byId }.distinct()
        val shipped = builtIn.associateBy { it.id }
        val problems = mutableListOf<PluginProblem>()
        enabled.forEach { id -> problems += problemsOf(byId.getValue(id), byId, enabled.toSet(), shipped) }
        val placement = Placement(byId, enabled.toSet(), problems)
        enabled.forEach { placement.place(it) }
        return PluginResolution(placement.order, problems)
    }

    /** What stops a plugin loading on its own account: its API level and its dependencies. */
    private fun problemsOf(
        manifest: PluginManifest,
        byId: Map<String, PluginManifest>,
        enabled: Set<String>,
        shipped: Map<String, PluginManifest>,
    ): List<PluginProblem> {
        if (manifest.apiLevel > StratumApi.LEVEL) return listOf(PluginProblem.NewerApi(manifest.id, manifest.apiLevel))
        return manifest.dependencies.filterNot { it.optional }.mapNotNull { dependency ->
            val found = byId[dependency.id]?.takeIf { dependency.id in enabled } ?: shipped[dependency.id]
            when {
                found == null -> PluginProblem.MissingDependency(manifest.id, dependency)
                found.version !in dependency.versions -> PluginProblem.WrongVersion(manifest.id, dependency, found.version)
                else -> null
            }
        }
    }

    /** Orders plugins depth first, so each comes after everything it builds on. */
    private class Placement(
        private val byId: Map<String, PluginManifest>,
        private val enabled: Set<String>,
        private val problems: MutableList<PluginProblem>,
    ) {
        val order = mutableListOf<String>()
        private val refused: MutableSet<String> = problems.mapTo(mutableSetOf()) { it.pluginId }

        /** Returns whether [id] made it into the load order. */
        fun place(id: String, trail: List<String> = emptyList()): Boolean {
            if (id in order) return true
            if (id in refused) return false
            if (id in trail) return refuse(id, PluginProblem.Cycle(id, trail.dropWhile { it != id } + id))
            // Optional dependencies are ordered first when present, so this plugin can override them.
            byId.getValue(id).dependencies.filter { it.id in enabled }.forEach { dependency ->
                if (!place(dependency.id, trail + id) && !dependency.optional) {
                    return refuse(id, PluginProblem.DependsOnRefused(id, dependency.id))
                }
            }
            order += id
            return true
        }

        private fun refuse(id: String, problem: PluginProblem): Boolean {
            if (refused.add(id)) problems += problem
            return false
        }
    }
}
