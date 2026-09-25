package com.stratum.plugins

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.plugin.PluginDependency
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.StratumApi
import com.stratum.core.domain.plugin.Version
import com.stratum.core.domain.plugin.VersionRange
import com.stratum.plugins.schema.PackJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class DependencySchema(val id: String, val version: String = "*", val optional: Boolean = false)

@Serializable
internal data class ManifestSchema(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val license: String = "",
    val description: String = "",
    val homepage: String = "",
    val api: Int = StratumApi.LEVEL,
    val dependencies: List<DependencySchema> = emptyList(),
)

/** Reads and writes `plugin.json`. */
object ManifestJson {

    fun encode(manifest: PluginManifest): String = PackJson.json.encodeToString(
        ManifestSchema.serializer(),
        ManifestSchema(
            manifest.id, manifest.name, manifest.version.toString(), manifest.author, manifest.license, manifest.description,
            manifest.homepage, manifest.apiLevel, manifest.dependencies.map { DependencySchema(it.id, it.versions.toString(), it.optional) },
        ),
    )

    /** @throws ImportException naming what is wrong. */
    fun decode(text: String): PluginManifest {
        val schema = try {
            PackJson.json.decodeFromString(ManifestSchema.serializer(), text)
        } catch (failure: SerializationException) {
            throw ImportException("plugin.json: ${failure.message?.lineSequence()?.firstOrNull()}", failure)
        }
        return PluginManifest(
            id = schema.id,
            name = schema.name,
            version = Version.parse(schema.version) ?: throw ImportException("plugin.json: version '${schema.version}' is not like 1.2.0"),
            author = schema.author,
            license = schema.license,
            description = schema.description,
            homepage = schema.homepage,
            apiLevel = schema.api,
            dependencies = schema.dependencies.map { dependency ->
                val range = VersionRange.parse(dependency.version)
                    ?: throw ImportException("plugin.json: dependency '${dependency.id}' version '${dependency.version}' is not a range like ^1.2")
                PluginDependency(dependency.id, range, dependency.optional)
            },
        )
    }
}
