package com.stratum.core.domain.importing

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.plugin.PluginManifest

/**
 * Writes an import's art where the platform keeps it.
 *
 * Implemented per platform: Android crops bitmaps into app storage, the JVM
 * tools write PNGs. Each call gets the whole source so an adapter can decode
 * each image file once however many regions it cuts from it.
 */
interface ImportedAssetWriter {
    fun writeTextures(packId: String, source: ImportSource, textures: List<ImportedTexture>)

    fun writeSpriteSheets(packId: String, source: ImportSource, sheets: List<ImportedSpriteSheet>)
}

/** The imported pack, and what the player should be told about it. */
data class ImportOutcome(
    val pack: ContentPack,
    val importerName: String,
    val warnings: List<String>,
    val manifest: PluginManifest = PluginManifest.of(pack),
)

/**
 * Imports whatever the player picked: finds the importer that recognises it,
 * runs it, and hands the art to the platform.
 */
class ImportProjectUseCase(
    private val registry: ImporterRegistry,
    private val assets: ImportedAssetWriter,
) {
    operator fun invoke(source: ImportSource): ImportOutcome {
        val importer = registry.importerFor(source)
        val result = importer.import(source)
        writeAssets(result, source)
        return ImportOutcome(result.pack, importer.displayName, result.warnings, result.manifestOrDerived)
    }

    private fun writeAssets(result: ImportResult, source: ImportSource) {
        if (result.textures.isNotEmpty()) assets.writeTextures(result.pack.id, source, result.textures)
        if (result.spriteSheets.isNotEmpty()) assets.writeSpriteSheets(result.pack.id, source, result.spriteSheets)
    }
}

/**
 * The importers this build knows, asked in order.
 *
 * Open by construction, like the terrain registry: a new format is one more
 * [ProjectImporter], and nothing that imports needs to change.
 */
class ImporterRegistry(importers: List<ProjectImporter> = emptyList()) {

    private val importers = importers.toMutableList()

    val all: List<ProjectImporter> get() = importers

    fun register(importer: ProjectImporter): ImporterRegistry = apply {
        require(importers.none { it.id == importer.id }) { "An importer with id '${importer.id}' is already registered" }
        importers += importer
    }

    /** The first importer that recognises [source]. */
    fun importerFor(source: ImportSource): ProjectImporter =
        importers.firstOrNull { it.recognises(source) }
            ?: throw ImportException(
                "Nothing here is a project this build can import. " +
                    "Supported: ${importers.joinToString { it.displayName }}.",
            )
}
