package com.stratum.core.data.sprite

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gets finished art out of the app.
 *
 * A character costs real money and a long run to make, and until now it could
 * only be looked at inside the one screen that drew it. That makes the app a
 * dead end for the work it exists to produce: the sheet cannot go into an
 * engine, the frames cannot be touched up, and nothing can be backed up.
 *
 * Two destinations, because they answer different questions. A copy in
 * Downloads is the one that survives the app being uninstalled and can be
 * found later by a person who has forgotten where it came from. A share
 * intent is the one that reaches the machine the art is actually needed on.
 * Both come from the same file, written once into the cache.
 */
object SpriteExporter {

    /** Where exports land, under Downloads. */
    const val ALBUM = "Stratum"

    /**
     * Writes bytes as a file the rest of the device can see.
     *
     * The cache copy is written first and returned even when the public copy
     * fails. Sharing works from the cache alone, so a device that refuses the
     * media store -- and they do, for reasons that vary by vendor and version
     * -- still lets the art out rather than failing entirely.
     */
    fun exportBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
    ): File? {
        val file = runCatching {
            val dir = File(context.cacheDir, EXPORTS).apply { mkdirs() }
            File(dir, fileName).apply { writeBytes(bytes) }
        }.getOrNull() ?: return null

        publish(context, file, fileName, mimeType)
        return file
    }

    /**
     * Every pose of one character, full size, in one zip.
     *
     * A character is forty-two separate images and handing them over one at a
     * time is forty-two taps and forty-two chances to lose one. They are the
     * originals rather than the packed sheet on purpose: the sheet is the
     * thing the game needs, and these are the thing a person needs to redraw a
     * frame, retouch a hand, or pack the set differently later.
     */
    fun exportPoses(
        context: Context,
        setName: String,
        poses: Map<String, ByteArray>,
    ): File? {
        if (poses.isEmpty()) return null
        val bytes = runCatching {
            val buffer = java.io.ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zip ->
                poses.toSortedMap().forEach { (key, image) ->
                    zip.putNextEntry(ZipEntry("$key.png"))
                    zip.write(image)
                    zip.closeEntry()
                }
            }
            buffer.toByteArray()
        }.getOrNull() ?: return null

        return exportBytes(context, bytes, "${slug(setName)}_poses.zip", "application/zip")
    }

    /**
     * The reference drawing, on its own.
     *
     * Worth its own way out because it is not an animation frame and is not in
     * the sheet, and it is the one image the whole character is an edit of. A
     * person redrawing this character later, or taking it to another tool,
     * wants the T-pose rather than forty action poses — and until now the only
     * thing that could leave the app was the sheet the reference is absent
     * from.
     */
    fun exportReference(context: Context, setName: String, bytes: ByteArray): File? =
        exportBytes(context, bytes, "${slug(setName)}_reference.png", "image/png")

    /**
     * The clip an animation was cut from.
     *
     * The frames are a sampling of this, and a coarse one: a four second clip
     * holds nearly a hundred pictures and a twelve frame row takes ten. Anyone
     * who wants the motion rather than the sheet — to re-cut it at another
     * rate, to look at what the sampling missed, or to take it into an editor
     * that can do better than a fixed grid — needs the clip itself, and it is
     * the most expensive thing the pipeline produces.
     */
    fun exportClip(context: Context, setName: String, key: String, bytes: ByteArray): File? =
        exportBytes(context, bytes, "${slug(setName)}_${slug(key)}.mp4", MIME_VIDEO)

    /** Hands the file to whatever the person picked, via the app's file provider. */
    fun share(context: Context, file: File, mimeType: String, title: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "Share $title")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** A name a file system will keep and a person will recognise. */
    fun slug(name: String): String =
        name.lowercase().replace(NOT_NAME, "_").trim('_').take(MAX_NAME).ifBlank { "sprite" }

    /**
     * The copy in Downloads.
     *
     * Only on Android 10 and later, where the media store takes a write
     * without a storage permission. Below that this would need a runtime
     * permission prompt to write a file the person can already share, which is
     * a poor trade; the cache copy and the share sheet cover it.
     */
    private fun publish(context: Context, file: File, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM",
                )
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { target ->
                context.contentResolver.openOutputStream(target)?.use { out ->
                    file.inputStream().use { source -> source.copyTo(out) }
                }
            }
        }
    }

    /** What a share sheet needs to offer the right apps for a clip. */
    const val MIME_VIDEO = "video/mp4"

    private const val EXPORTS = "exports"
    private const val MAX_NAME = 48
    private val NOT_NAME = Regex("[^a-z0-9]+")
}
