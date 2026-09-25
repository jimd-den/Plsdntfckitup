package com.stratum.importer.flame

import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.readText
import com.stratum.importer.common.ImportNaming
import com.stratum.importer.common.ProjectPaths

/**
 * Where a Flame game keeps things, by Flame's own defaults: images under
 * `assets/images/`, which is the prefix `images.load('player.png')` reads
 * from, and Dart under `lib/`.
 */
internal class FlameAssets(private val source: ImportSource) {

    private val paths = source.paths().toSet()

    /** The project path an image reference in Dart names, trying Flame's prefixes in order. */
    fun imagePath(reference: String): String? =
        listOf("assets/images/$reference", "assets/$reference", reference)
            .map(ProjectPaths::normalize)
            .firstOrNull(paths::contains)

    fun dartFiles(): List<String> = paths.filter { it.startsWith("lib/") && it.endsWith(".dart") }.sorted()

    fun atlasFiles(): List<String> = paths
        .filter { it.startsWith("assets/") && ProjectPaths.extensionOf(it) == "json" }
        .filter { path -> source.readText(path)?.let(AtlasJsonFormat::recognises) == true }
        .sorted()
}

/** The characters a Flame game animates, each as one sheet. */
internal class FlameCharacters(private val source: ImportSource, private val namespace: String) {

    private val assets = FlameAssets(source)

    data class Found(val sheets: List<AssembledSheet>, val warnings: List<String>)

    fun collect(): Found {
        val warnings = mutableListOf<String>()
        val byCharacter = (fromAtlases() + fromDart(warnings))
            .groupBy({ ImportNaming.slug(it.first) }, { it.second })
            .mapValues { (_, lists) -> lists.flatten() }
        val sheets = byCharacter.mapNotNull { (character, animations) ->
            SheetAssembler.assemble(ImportNaming.id(namespace, character), ImportNaming.displayName(character), animations)
        }
        return Found(sheets, warnings + sheets.flatMap { it.warnings })
    }

    private fun fromAtlases(): List<Pair<String, List<SourceAnimation>>> =
        assets.atlasFiles().flatMap { path -> AtlasJsonFormat.read(source.readText(path).orEmpty(), path).toList() }

    /** One Dart file is one component, so its animations are one character. */
    private fun fromDart(warnings: MutableList<String>): List<Pair<String, List<SourceAnimation>>> {
        val scanner = DartAnimationScanner(assets::imagePath)
        return assets.dartFiles().mapNotNull { path ->
            val scan = scanner.scan(source.readText(path).orEmpty(), path)
            warnings += scan.warnings
            scan.animations.takeIf(List<SourceAnimation>::isNotEmpty)?.let { ProjectPaths.baseNameOf(path) to it }
        }
    }
}
