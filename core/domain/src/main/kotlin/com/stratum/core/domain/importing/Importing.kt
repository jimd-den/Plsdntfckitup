package com.stratum.core.domain.importing

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.sprite.SpriteSheet

/**
 * A project to import, seen as a read-only tree of files.
 *
 * Deliberately smaller than a file system: a folder on disk, a zip a player
 * picked, and an in-memory fixture in a test all fit behind it, and an
 * importer never learns which one it was given.
 */
interface ImportSource {
    /** Shown to the player, such as the folder or archive name. */
    val name: String

    /** Every file, as `/`-separated paths relative to the project root. */
    fun paths(): List<String>

    /** The file's bytes, or null when there is no such file. */
    fun read(path: String): ByteArray?
}

/** Reads a file as UTF-8 text, or null when there is no such file. */
fun ImportSource.readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)

/**
 * Turns one kind of project into a content pack.
 *
 * Importers are the only place a foreign format is understood. What comes out
 * is ordinary pack data, so everything downstream -- assembly, validation,
 * world generation, rendering -- treats an imported game exactly like the
 * built-in one.
 */
interface ProjectImporter {
    /** Stable, such as `flame`. */
    val id: String

    /** Shown to the player, such as `Flame game`. */
    val displayName: String

    /** Whether [source] looks like something this importer understands. Must be cheap. */
    fun recognises(source: ImportSource): Boolean

    /** @throws ImportException when the project cannot become a playable pack. */
    fun import(source: ImportSource): ImportResult
}

/**
 * What an import produced.
 *
 * Image data is described rather than carried: [textures] and [spriteSheets]
 * say which region of which file each asset comes from, and the platform does
 * the decoding and cutting. That keeps importers pure Kotlin and testable
 * without a bitmap.
 */
data class ImportResult(
    val pack: ContentPack,
    val textures: List<ImportedTexture> = emptyList(),
    val spriteSheets: List<ImportedSpriteSheet> = emptyList(),
    /**
     * Everything the importer understood but could not honour. Reported rather
     * than dropped, because a map that silently lost its lighting layer looks
     * like an engine bug to the person who made it.
     */
    val warnings: List<String> = emptyList(),
)

/** A rectangle of pixels in one of the project's image files. */
data class ImageRegion(
    val imagePath: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(x >= 0 && y >= 0) { "Region of '$imagePath' starts off the image at $x,$y" }
        require(width > 0 && height > 0) { "Region of '$imagePath' is ${width}x$height" }
    }
}

/** A texture the renderer will look up by [key], such as `tiled:grass/top`. */
data class ImportedTexture(val key: String, val region: ImageRegion)

/**
 * A sprite sheet and where each of its frames comes from.
 *
 * [frames] is in the sheet's grid order. Source art is often packed tightly
 * or spread across files, and the engine's sheets are a regular grid, so the
 * platform lays each region into its grid cell when it writes the sheet.
 */
data class ImportedSpriteSheet(val sheet: SpriteSheet, val frames: List<ImageRegion>) {
    init {
        require(frames.size == sheet.frameCount) {
            "Sheet '${sheet.id}' has ${sheet.frameCount} cells but ${frames.size} frames"
        }
    }
}

/** An import that cannot produce a playable pack. The message is for the player. */
class ImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
