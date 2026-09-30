package com.stratum.core.data.sprite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.stratum.core.domain.sprite.SpriteOrigin
import com.stratum.core.domain.sprite.WeaponKind
import com.stratum.core.domain.sprite.WeaponSprite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Weapons on the device, kept apart from the characters that swing them.
 *
 * A separate store rather than a corner of [SpriteLibrary], because a weapon is
 * a different kind of thing: it has no clips, no animation and no facing, and
 * it does have a grip — the one number that decides whether it looks held or
 * looks driven through the palm. Sharing a format with sprite sheets would mean
 * every sheet carrying a grip it does not have, and every weapon carrying a
 * grid it does not have.
 */
class WeaponLibrary(context: Context) {

    private val root: File = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val bitmaps = HashMap<String, Bitmap?>()

    /** Weapons on disk, newest first. */
    fun all(): List<WeaponSprite> =
        root.listFiles { file -> file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { read(it) }

    fun find(weaponId: String): WeaponSprite? =
        read(File(root, "${slugFor(weaponId)}$METADATA_SUFFIX"))

    fun save(weapon: WeaponSprite, imageBytes: ByteArray) {
        val slug = slugFor(weapon.id)
        File(root, "$slug$IMAGE_SUFFIX").writeBytes(imageBytes)
        // Metadata last, like the sheet library: an image with no metadata is
        // ignored, which is recoverable; metadata with no image is a weapon
        // that cannot be drawn.
        File(root, "$slug$METADATA_SUFFIX").writeText(json.encodeToString(weapon.toDto()))
        bitmaps.remove(weapon.id)
    }

    fun delete(weaponId: String) {
        val slug = slugFor(weaponId)
        File(root, "$slug$METADATA_SUFFIX").delete()
        File(root, "$slug$IMAGE_SUFFIX").delete()
        bitmaps.remove(weaponId)
    }

    /** Cached including the null: a corrupt file is attempted once, not per frame. */
    fun bitmapFor(weaponId: String): Bitmap? = bitmaps.getOrPut(weaponId) {
        val file = File(root, "${slugFor(weaponId)}$IMAGE_SUFFIX")
        if (!file.isFile) return@getOrPut null
        runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            )
        }.getOrNull()
    }

    fun clearCache() = bitmaps.clear()

    private fun read(file: File): WeaponSprite? = runCatching {
        json.decodeFromString(WeaponDto.serializer(), file.readText()).toDomain()
    }.getOrNull()

    private fun slugFor(weaponId: String): String = weaponId.replace(NON_FILE_SAFE, "_")

    private companion object {
        const val DIRECTORY = "weapons"
        const val IMAGE_SUFFIX = ".png"
        const val METADATA_SUFFIX = ".json"
        val NON_FILE_SAFE = Regex("[^A-Za-z0-9._-]")
    }
}

@Serializable
private data class WeaponDto(
    val id: String,
    val name: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val gripX: Float,
    val gripY: Float,
    val origin: String,
)

private fun WeaponSprite.toDto() = WeaponDto(
    id = id,
    name = name,
    kind = kind.name,
    width = width,
    height = height,
    gripX = gripX,
    gripY = gripY,
    origin = origin.name,
)

private fun WeaponDto.toDomain(): WeaponSprite {
    val weaponKind = runCatching { WeaponKind.valueOf(kind) }.getOrDefault(WeaponKind.SWORD)
    return WeaponSprite(
        id = id,
        name = name,
        kind = weaponKind,
        width = width,
        height = height,
        gripX = gripX,
        gripY = gripY,
        origin = runCatching { SpriteOrigin.valueOf(origin) }.getOrDefault(SpriteOrigin.IMPORTED),
    )
}
