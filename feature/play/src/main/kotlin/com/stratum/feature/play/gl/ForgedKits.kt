package com.stratum.feature.play.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.TextureKeys
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.TextureLibrary
import java.io.File

/**
 * The asset kits that ship inside content packs, and which one a style uses.
 *
 * Kits live in a pack's resources under `forge/<kit>/`, with an `index.txt`
 * naming their files, because an APK's resources cannot be listed. File names
 * encode texture keys (`~` for `:`, `__` for `/`), the same convention the
 * forge writes and the preview tool reads.
 */
object ForgedKits {

    const val HOUSE = "house"

    /**
     * The kit that best fits a style.
     *
     * Chosen from the traits the lexicon matched, not from the raw words, so a
     * player who typed "grim" gets the dark kit. A style with no kit of its own
     * borrows the house kit: painted surfaces under a different light, fog and
     * grade already read as a different place, and the forge can fill the gap.
     */
    fun kitFor(direction: ArtDirection, available: (String) -> Boolean = ::exists): String {
        val traits = direction.id.removePrefix("prompt:").split('+').toSet()
        val preferred = when {
            "kawaii" in traits -> "kawaii"
            "inked" in traits || "chiaroscuro" in traits -> "hades"
            "dark" in traits || "volcanic" in traits -> "dark"
            else -> HOUSE
        }
        return preferred.takeIf(available) ?: HOUSE
    }

    fun exists(kit: String): Boolean = loader().getResource("forge/$kit/index.txt") != null

    /** Prefix naming a kit the player forged on this device, stored in app files. */
    const val LOCAL = "file:"

    /**
     * Decodes a kit into a fresh library, in index order, so layers are stable,
     * then lays each overlay folder on top -- imported packs' textures, which
     * name their own blocks and so never collide with the kit's.
     */
    fun load(kit: String, overlays: List<File> = emptyList()): TextureLibrary {
        val library = if (kit.startsWith(LOCAL)) loadDirectory(File(kit.removePrefix(LOCAL))) else loadResources(kit)
        overlays.forEach { directory -> library.aliasMissingSides(putFiles(library, directory)) }
        return library
    }

    private fun loadResources(kit: String): TextureLibrary {
        val library = TextureLibrary()
        val index = loader().getResourceAsStream("forge/$kit/index.txt")?.bufferedReader()?.readLines().orEmpty()
        index.map(String::trim).filter { it.endsWith(".png") }.forEach { name ->
            loader().getResourceAsStream("forge/$kit/$name")?.use(BitmapFactory::decodeStream)?.let { put(library, name, it) }
        }
        return library
    }

    /**
     * A kit forged on the device: the shipped house kit underneath, and every
     * file the player forged on top, so a partly forged style is still whole.
     */
    fun loadDirectory(directory: File): TextureLibrary = loadResources(HOUSE).also { putFiles(it, directory) }

    /** Returns the keys it loaded. */
    private fun putFiles(library: TextureLibrary, directory: File): List<String> =
        directory.listFiles { f -> f.extension == "png" }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { put(library, file.name, it) }
        }

    private fun put(library: TextureLibrary, fileName: String, bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val key = keyFor(fileName)
        library.put(key, Texture(bitmap.width, bitmap.height, pixels))
        bitmap.recycle()
        return key
    }

    fun fileNameFor(key: String): String = TextureKeys.fileNameFor(key)

    fun keyFor(fileName: String): String = TextureKeys.keyFor(fileName)

    private fun loader(): ClassLoader = ForgedKits::class.java.classLoader!!
}
