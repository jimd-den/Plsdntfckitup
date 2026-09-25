package com.stratum.plugins

import com.stratum.core.domain.art.TextureKeys
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.plugins.schema.PackJson
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The `.stratum` plugin file: a zip anyone can open and read.
 *
 * ```
 * plugin.json               who made it, its version, license and dependencies
 * pack.json                 the content: blocks, classes, monsters, maps, checks...
 * art/textures/<key>.png    textures, named by texture key (see TextureKeys)
 * art/sheets/<sheet>.png    one grid image per sprite sheet in pack.json
 * ```
 *
 * Plain files rather than a binary format, so a plugin can be written with a
 * text editor and an image editor, diffed in git, and forked.
 */
object PluginArchive {

    const val EXTENSION = "stratum"
    const val MANIFEST = "plugin.json"
    const val PACK = "pack.json"
    const val TEXTURES = "art/textures/"
    const val SHEETS = "art/sheets/"

    fun texturePath(key: String): String = TEXTURES + TextureKeys.fileNameFor(key)

    fun sheetPath(sheetId: String): String = SHEETS + TextureKeys.fileNameFor(sheetId)

    /**
     * Writes a plugin.
     *
     * @param textures PNG bytes by texture key.
     * @param sheets PNG bytes by sprite sheet id; each must be the sheet's full grid.
     */
    fun write(
        manifest: PluginManifest,
        pack: ContentPack,
        textures: Map<String, ByteArray> = emptyMap(),
        sheets: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        require(pack.id == manifest.id) { "The pack's id '${pack.id}' must be the plugin's id '${manifest.id}'" }
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.put(MANIFEST, ManifestJson.encode(manifest).toByteArray(Charsets.UTF_8))
                zip.put(PACK, PackJson.encode(pack).toByteArray(Charsets.UTF_8))
                textures.toSortedMap().forEach { (key, bytes) -> zip.put(texturePath(key), bytes) }
                sheets.toSortedMap().forEach { (id, bytes) -> zip.put(sheetPath(id), bytes) }
            }
        }.toByteArray()
    }

    private fun ZipOutputStream.put(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }
}

/** Reads a PNG's size from its header, so art can be described without decoding it. */
internal object PngSize {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** Width and height, or null for anything that is not a PNG. */
    fun of(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < HEADER_END || !bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) return null
        if (String(bytes, CHUNK_TYPE, 4, Charsets.US_ASCII) != "IHDR") return null
        return int(bytes, WIDTH) to int(bytes, HEIGHT)
    }

    private fun int(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private const val CHUNK_TYPE = 12
    private const val WIDTH = 16
    private const val HEIGHT = 20
    private const val HEADER_END = 24
}
