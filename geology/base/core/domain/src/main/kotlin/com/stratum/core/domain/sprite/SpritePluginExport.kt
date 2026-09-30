package com.stratum.core.domain.sprite

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.PackOrigin
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.Version

/** A sheet and its image, as the library holds them. */
class SheetArt(val sheet: SpriteSheet, val bytes: ByteArray)

/**
 * Generated sheets, ready to be written as a `.stratum` plugin.
 *
 * [images] is keyed by sheet id, which is what the archive writer names the
 * files by; [skipped] says which sheets could not go and why, so a share that
 * left something out says so rather than arriving one character short.
 */
data class SpritePluginBundle(
    val manifest: PluginManifest,
    val pack: ContentPack,
    val images: Map<String, ByteArray>,
    val skipped: Map<String, String>,
)

/**
 * Packages generated characters so another player can install them.
 *
 * The archive format always had room for sheets — one PNG per sheet under
 * `art/sheets/`, its grid in `pack.json` — and the importer already reads them,
 * but nothing on the device ever wrote one: forty generations of a character
 * could leave the phone as a loose PNG, which arrives on the other side as a
 * picture rather than as something the game can draw.
 *
 * The checks here are the importer's, run before sending rather than after
 * receiving. It drops a sheet whose image is not a PNG or is smaller than its
 * grid, with only a warning at the far end; finding that out here means the
 * sheet can be re-encoded or left out knowingly.
 */
object SpritePluginExport {

    const val DEFAULT_ID = "shared.sprites"

    /**
     * @param toPng re-encodes an image that is not already PNG, or returns
     *   null when it cannot. Generated sheets are kept exactly as the provider
     *   sent them when keying found nothing to clear, and some providers send
     *   JPEG whatever was asked for.
     */
    fun bundle(
        art: List<SheetArt>,
        author: String = "A Stratum player",
        id: String = DEFAULT_ID,
        toPng: (ByteArray) -> ByteArray? = { null },
    ): SpritePluginBundle? {
        val images = LinkedHashMap<String, ByteArray>()
        val sheets = mutableListOf<SpriteSheet>()
        val skipped = LinkedHashMap<String, String>()
        for (item in art.distinctBy { it.sheet.id }) {
            val sheet = item.sheet
            if (sheet.columns <= 0 || sheet.rows <= 0 || sheet.frameWidth <= 0 || sheet.frameHeight <= 0) {
                skipped[sheet.id] = "it has no frames"
                continue
            }
            val png = if (PngHeader.sizeOf(item.bytes) != null) item.bytes else toPng(item.bytes)
            val size = png?.let(PngHeader::sizeOf)
            if (png == null || size == null) {
                skipped[sheet.id] = "its image could not be written as a PNG"
                continue
            }
            if (size.first < sheet.columns * sheet.frameWidth || size.second < sheet.rows * sheet.frameHeight) {
                skipped[sheet.id] = "its image is smaller than its grid"
                continue
            }
            images[sheet.id] = png
            sheets += sheet.copy(origin = SpriteOrigin.IMPORTED)
        }
        if (sheets.isEmpty()) return null

        val name = if (sheets.size == 1) sheets.single().name.ifBlank { "Shared character" } else "Shared characters"
        val manifest = PluginManifest(
            id = id,
            name = name,
            version = Version(1, 0, 0),
            author = author,
            license = "CC-BY-4.0",
            description = "${sheets.size} character sheet${if (sheets.size == 1) "" else "s"} drawn in the sprite forge.",
        )
        val pack = ContentPack(
            id = id,
            name = name,
            author = author,
            description = manifest.description,
            origin = PackOrigin.IMPORTED,
            spriteSheets = sheets,
        )
        return SpritePluginBundle(manifest, pack, images, skipped)
    }
}

/** Reads a PNG's size from its header, without decoding it. */
object PngHeader {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun sizeOf(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < HEADER_END) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
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
