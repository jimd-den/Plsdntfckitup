package com.stratum.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** A file the player picked, read into memory. */
internal class PickedArchive(val name: String, val bytes: ByteArray) {

    companion object {
        /** Larger than any sensible 2D game; stops a mistaken pick from exhausting memory. */
        const val MAX_BYTES = 128L * 1024 * 1024

        /** @throws IllegalArgumentException with a message for the player. */
        fun read(resolver: ContentResolver, uri: Uri): PickedArchive {
            val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "import.zip"
            val stream = resolver.openInputStream(uri) ?: throw IllegalArgumentException("'$name' could not be opened")
            val bytes = stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(out.size() + read <= MAX_BYTES) { "'$name' is larger than ${MAX_BYTES / (1024 * 1024)} MB" }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            return PickedArchive(name, bytes)
        }

        private fun displayName(resolver: ContentResolver, uri: Uri): String? =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

        private const val BUFFER = 64 * 1024
    }
}
