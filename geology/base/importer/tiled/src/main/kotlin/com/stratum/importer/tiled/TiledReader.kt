package com.stratum.importer.tiled

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.readText
import com.stratum.importer.common.ProjectPaths

/**
 * Reads Tiled maps and tilesets out of a project, whichever format each file
 * was saved in. A `.tmx` map may name a `.tsj` tileset and the other way
 * round; each file is read by its own extension.
 */
class TiledReader(private val source: ImportSource) {

    private val tilesets = HashMap<String, TiledTileset>()

    /** Maps read, and one warning for each that could not be. */
    data class MapsRead(val maps: List<TiledMap>, val warnings: List<String>)

    /**
     * Reads every map in [paths], skipping any that fail. A project with one
     * infinite or broken map still has the others, and the player is told
     * which was left out and why.
     */
    fun readMaps(paths: List<String>): MapsRead {
        val results = paths.map { path -> path to runCatching { readMap(path) } }
        return MapsRead(
            maps = results.mapNotNull { (_, result) -> result.getOrNull() },
            warnings = results.mapNotNull { (path, result) ->
                result.exceptionOrNull()?.let { "Map '$path' was left out: ${it.message}" }
            },
        )
    }

    fun readMap(path: String): TiledMap {
        val text = textOf(path)
        return when (ProjectPaths.extensionOf(path)) {
            in XML_MAPS -> TmxFormat.readMap(text, path, ::readTileset)
            in JSON_MAPS -> TmjFormat.readMap(text, path, ::readTileset)
            else -> throw ImportException("'$path' is not a Tiled map")
        }
    }

    /** Read once per path, however many maps share the tileset. */
    fun readTileset(path: String): TiledTileset = tilesets.getOrPut(path) {
        val text = textOf(path)
        when (ProjectPaths.extensionOf(path)) {
            "tsx" -> TmxFormat.readTileset(text, path)
            "tsj", "json" -> TmjFormat.readTileset(text, path)
            else -> throw ImportException("'$path' is not a Tiled tileset")
        }
    }

    private fun textOf(path: String): String =
        source.readText(path) ?: throw ImportException("'$path' is missing from '${source.name}'")

    companion object {
        private val XML_MAPS = setOf("tmx")
        private val JSON_MAPS = setOf("tmj", "json")

        /** Map files in a project, by extension alone; a `.json` is checked by [looksLikeJsonMap]. */
        fun mapPathsIn(source: ImportSource): List<String> = source.paths().filter { path ->
            when (ProjectPaths.extensionOf(path)) {
                "tmx", "tmj" -> true
                "json" -> looksLikeJsonMap(source, path)
                else -> false
            }
        }

        /** A `.json` could be anything; a Tiled map says so in its `type`. */
        private fun looksLikeJsonMap(source: ImportSource, path: String): Boolean =
            source.readText(path)?.let { Regex("\"type\"\\s*:\\s*\"map\"").containsMatchIn(it) } ?: false
    }
}
