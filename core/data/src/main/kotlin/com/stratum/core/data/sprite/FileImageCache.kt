package com.stratum.core.data.sprite

import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.ImageCache
import java.io.File

/**
 * Generated images kept against the request that produced them, on disk.
 *
 * Bounded by total size, oldest first, because this is a safety net for work
 * already paid for rather than an archive: the poses themselves are kept by
 * [PoseLibrary], and this only has to outlive the gap between an answer
 * arriving and it being stored. The size and type are kept in the file name,
 * so an entry is one file and never half of a pair.
 */
class FileImageCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ImageCache {

    init {
        root.mkdirs()
    }

    override fun get(key: String): GeneratedImage? {
        val file = root.listFiles { f -> f.name.startsWith("$key$SEPARATOR") }?.firstOrNull() ?: return null
        val parts = file.name.removePrefix("$key$SEPARATOR").split(SEPARATOR, limit = 3)
        if (parts.size != 3) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        val mime = parts[2].replace('~', '/')
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // Touched so a frequently reused entry is the last to be evicted.
        file.setLastModified(System.currentTimeMillis())
        return GeneratedImage(bytes, mime, width, height)
    }

    override fun put(key: String, image: GeneratedImage) {
        if (!SAFE_KEY.matches(key)) return
        val mime = image.mimeType.replace('/', '~').replace(UNSAFE, "_")
        AtomicFiles.write(File(root, "$key$SEPARATOR${image.width}$SEPARATOR${image.height}$SEPARATOR$mime"), image.bytes)
        trim()
    }

    private fun trim() {
        val files = root.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        /** Enough for two full characters of 1024 pixel poses. */
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        const val SEPARATOR = "_"
        val SAFE_KEY = Regex("[0-9a-f]{16,128}")
        val UNSAFE = Regex("[^A-Za-z0-9~.+-]")
    }
}
