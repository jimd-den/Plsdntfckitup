package com.stratum.core.data.sprite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.core.domain.sprite.AnimationClip
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteFacing
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stores generated sprite sheets on the device.
 *
 * A sheet is two things: pixels and a description of how to cut them. They are
 * written side by side under one id so a half-written pair cannot be loaded --
 * the metadata is written last, so an image with no metadata is simply ignored
 * and regenerated rather than crashing a world load.
 */
class SpriteLibrary(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val bitmaps = HashMap<String, Bitmap?>()

    /** Whether a decoded sheet has anything drawn on it. Cached beside the pixels. */
    private val drawn = HashMap<String, Boolean>()

    /** Sheets on disk, newest first. */
    fun all(): List<SpriteSheet> =
        root.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { readMetadata(it) }

    fun save(sheet: SpriteSheet, imageBytes: ByteArray) {
        val slug = slugFor(sheet.id)
        File(root, "$slug$IMAGE_SUFFIX").writeBytes(imageBytes)
        // Metadata last: an image without it is ignored, which is recoverable.
        // Metadata without an image would be a sheet that cannot be drawn.
        File(root, "$slug$METADATA_SUFFIX").writeText(json.encodeToString(sheet.toDto()))
        bitmaps.remove(sheet.id)
        drawn.remove(sheet.id)
    }

    fun delete(sheetId: String) {
        val slug = slugFor(sheetId)
        File(root, "$slug$METADATA_SUFFIX").delete()
        File(root, "$slug$IMAGE_SUFFIX").delete()
        bitmaps.remove(sheetId)
        drawn.remove(sheetId)
    }

    /**
     * The decoded sheet, or null when it is missing or unreadable.
     *
     * Cached, including the null: a sheet whose file is corrupt should be
     * attempted once, not on every frame.
     */
    fun bitmapFor(sheetId: String): Bitmap? = bitmaps.getOrPut(sheetId) {
        val file = File(root, "${slugFor(sheetId)}$IMAGE_SUFFIX")
        if (!file.isFile) return@getOrPut null
        runCatching {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // Sprite art must not be smoothed on load; the whole point is
                // hard pixel edges.
                inScaled = false
            })
        }.getOrNull()
    }

    /**
     * The stored file, byte for byte.
     *
     * The frame mapper needs the original rather than a decoded bitmap: it
     * copies the art into a project of its own so that re-cutting a sheet never
     * touches the only copy of the image it was cut from.
     */
    fun bytesFor(sheetId: String): ByteArray? {
        val file = File(root, "${slugFor(sheetId)}$IMAGE_SUFFIX")
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    /**
     * The sheet's pixels, or null when there is nothing on them to draw.
     *
     * A blank sheet is not a theoretical case: a generation that came back
     * empty, or one keyed too hard by an older build, is stored like any other
     * and then resolves ahead of the art that does work. Answering null lets
     * the caller move on to the next candidate instead of drawing nothing and
     * leaving the player to wonder why their character has no skin.
     */
    fun drawableBitmapFor(sheetId: String): Bitmap? {
        val bitmap = bitmapFor(sheetId) ?: return null
        val hasArt = drawn.getOrPut(sheetId) { anythingDrawnOn(bitmap) }
        return if (hasArt) bitmap else null
    }

    /**
     * Samples a lattice rather than every pixel. A sheet is a megapixel and
     * this is asked on every resolver rebuild; the question is only whether
     * *anything* is there, which a few thousand samples settle.
     */
    private fun anythingDrawnOn(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / SAMPLES_ACROSS).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLES_ACROSS).coerceAtLeast(1)
        var sampled = 0
        var opaque = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                sampled++
                if ((bitmap.getPixel(x, y) ushr 24) and 0xFF > 0) opaque++
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && opaque > sampled * MIN_DRAWN
    }

    fun clearCache() {
        bitmaps.clear()
        drawn.clear()
    }

    private fun readMetadata(file: File): SpriteSheet? = runCatching {
        json.decodeFromString(SheetDto.serializer(), file.readText()).toDomain()
    }.getOrNull()

    /** Ids are namespaced with a colon, which is not safe in a file name. */
    private fun slugFor(sheetId: String): String = sheetId.replace(NON_FILE_SAFE, "_")

    private companion object {
        const val DIRECTORY = "sprite_sheets"
        const val IMAGE_SUFFIX = ".png"
        const val METADATA_SUFFIX = ".json"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
        /** Samples per axis when asking whether a sheet has any art on it. */
        const val SAMPLES_ACROSS = 64
        /** Below this share of samples drawn on, there is no sprite there. */
        const val MIN_DRAWN = 0.005f
    }
}

// The domain model is not serializable on purpose: persistence is this module's
// concern, and a stored format that tracks the domain type would break every
// saved sheet the moment the domain changed.
@Serializable
private data class SheetDto(
    val id: String,
    val name: String,
    val columns: Int,
    val rows: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val origin: String,
    val clips: List<ClipDto>,
    // Defaulted, so every sheet written before hand-mapped atlases existed
    // keeps reading as the mirrored sheet it was.
    val mirrorsFacings: Boolean = true,
    /**
     * Which row block each facing reads from, by facing name.
     *
     * Written because it was not. A sheet with a second block of rows for the
     * away angle carries the whole of that art in the image and nothing in
     * the file to say how to reach it, so the character turned around until
     * the app was restarted and then never again -- the rows were still
     * there, unreferenced, and nothing reported a problem.
     *
     * Empty by default, which is every sheet that has only one angle.
     */
    val facingRows: Map<String, Int> = emptyMap(),
)

@Serializable
private data class ClipDto(
    val state: String,
    val firstFrame: Int,
    val frameCount: Int,
    val frameDurationMs: Int,
    val loops: Boolean,
    /** Null for every sheet written before clips could be borrowed. */
    val standsInFor: String? = null,
)

private fun SpriteSheet.toDto() = SheetDto(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
    origin = origin.name,
    mirrorsFacings = mirrorsFacings,
    facingRows = facingRows.mapKeys { (facing, _) -> facing.name },
    clips = clips.map {
        ClipDto(
            state = it.state.name,
            firstFrame = it.firstFrame,
            frameCount = it.frameCount,
            frameDurationMs = it.frameDurationMs,
            loops = it.loops,
            standsInFor = it.standsInFor?.name,
        )
    },
)

private fun SheetDto.toDomain() = SpriteSheet(
    id = id,
    name = name,
    columns = columns,
    rows = rows,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
    origin = runCatching { SpriteOrigin.valueOf(origin) }.getOrDefault(SpriteOrigin.IMPORTED),
    mirrorsFacings = mirrorsFacings,
    // An unreadable facing name is dropped rather than failing the sheet: the
    // worst case is a character that stops turning, where refusing the whole
    // file would be a character that cannot be drawn at all.
    facingRows = facingRows.mapNotNull { (name, row) ->
        runCatching { SpriteFacing.valueOf(name) }.getOrNull()?.let { it to row }
    }.toMap(),
    clips = clips.mapNotNull { dto ->
        val state = runCatching { AnimationState.valueOf(dto.state) }.getOrNull()
            ?: return@mapNotNull null
        runCatching {
            AnimationClip(
                state = state,
                firstFrame = dto.firstFrame,
                frameCount = dto.frameCount,
                frameDurationMs = dto.frameDurationMs,
                loops = dto.loops,
                standsInFor = dto.standsInFor
                    ?.let { name -> runCatching { AnimationState.valueOf(name) }.getOrNull() },
            )
        }.getOrNull()
    },
)
