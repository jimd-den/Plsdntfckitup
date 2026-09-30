package com.stratum.plugins

import com.stratum.core.domain.art.TextureKeys
import com.stratum.core.domain.importing.ImageRegion
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportResult
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ImportedSpriteSheet
import com.stratum.core.domain.importing.ImportedTexture
import com.stratum.core.domain.importing.ProjectImporter
import com.stratum.core.domain.importing.readText
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.plugins.schema.PackJson

/** Installs a `.stratum` plugin: its manifest, its pack and its art. */
class PluginImporter : ProjectImporter {

    override val id: String = "stratum"

    override val displayName: String = "Stratum plugin"

    override fun recognises(source: ImportSource): Boolean =
        source.read(PluginArchive.MANIFEST) != null && source.read(PluginArchive.PACK) != null

    override fun import(source: ImportSource): ImportResult {
        val manifest = ManifestJson.decode(source.readText(PluginArchive.MANIFEST).orEmpty())
        val pack = PackJson.decode(source.readText(PluginArchive.PACK).orEmpty())
        if (pack.id != manifest.id) throw ImportException("pack.json is '${pack.id}' but plugin.json is '${manifest.id}'; they must match")
        val warnings = mutableListOf<String>()
        return ImportResult(
            pack = pack,
            textures = textures(source, warnings),
            spriteSheets = pack.spriteSheets.mapNotNull { sheetArt(source, it, warnings) },
            warnings = warnings,
            manifest = manifest,
        )
    }

    private fun textures(source: ImportSource, warnings: MutableList<String>): List<ImportedTexture> =
        source.paths().filter { it.startsWith(PluginArchive.TEXTURES) }.mapNotNull { path ->
            val size = source.read(path)?.let(PngSize::of)
            if (size == null) null.also { warnings += "'$path' is not a PNG and was left out" }
            else ImportedTexture(TextureKeys.keyFor(path.removePrefix(PluginArchive.TEXTURES)), ImageRegion(path, 0, 0, size.first, size.second))
        }

    /** A sheet's image is its whole grid, so each frame is one cell of it. */
    private fun sheetArt(source: ImportSource, sheet: SpriteSheet, warnings: MutableList<String>): ImportedSpriteSheet? {
        val path = PluginArchive.sheetPath(sheet.id)
        val size = source.read(path)?.let(PngSize::of)
            ?: return null.also { warnings += "Sheet '${sheet.id}' has no image at '$path'; its actors are drawn without art" }
        if (size.first < sheet.columns * sheet.frameWidth || size.second < sheet.rows * sheet.frameHeight) {
            warnings += "Sheet '${sheet.id}' image is ${size.first}x${size.second}, smaller than its ${sheet.columns}x${sheet.rows} grid of ${sheet.frameWidth}x${sheet.frameHeight}"
            return null
        }
        val frames = (0 until sheet.frameCount).map { frame ->
            val rect = sheet.frameRect(frame)
            ImageRegion(path, rect.left, rect.top, rect.width, rect.height)
        }
        return ImportedSpriteSheet(sheet, frames)
    }
}
