package com.stratum.core.domain.plugin

import com.stratum.core.domain.content.ContentPack

/**
 * What a plugin says about itself: who made it, what it needs, and which
 * version of the engine it was written for.
 *
 * The content itself is an ordinary [ContentPack]. A plugin is a pack with a
 * name tag and a list of the other plugins it builds on -- which is what lets
 * a pen-and-paper conversion depend on a monster pack someone else made, and
 * lets the loader say so plainly when that pack is missing.
 */
data class PluginManifest(
    /** Stable and namespaced like any other id, e.g. `nri.chronicles`. */
    val id: String,
    val name: String,
    val version: Version,
    val author: String = "",
    /** An SPDX identifier such as `CC-BY-4.0` or `MIT`, so others know what they may do with it. */
    val license: String = "",
    val description: String = "",
    val homepage: String = "",
    /** The [StratumApi.LEVEL] the plugin was written against. */
    val apiLevel: Int = StratumApi.LEVEL,
    val dependencies: List<PluginDependency> = emptyList(),
) {
    companion object {
        /** A manifest for content that came without one, such as an imported Flame game. */
        fun of(pack: ContentPack): PluginManifest = PluginManifest(
            id = pack.id,
            name = pack.name,
            version = Version.parse(pack.version) ?: Version(1, 0, 0),
            author = pack.author,
            description = pack.description,
        )
    }
}

data class PluginDependency(val id: String, val versions: VersionRange = VersionRange.ANY, val optional: Boolean = false)

/**
 * The plugin API this build offers.
 *
 * Raised when something a plugin can say changes meaning or goes away -- not
 * when something is added, since additions are optional fields an older
 * plugin simply does not use. A plugin written for a newer level than this
 * build is refused with a message saying to update, rather than loaded and
 * silently misread.
 */
object StratumApi {
    const val LEVEL = 1
}
