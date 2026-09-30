package com.stratum.importer.common

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.ImportSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A project held in memory. What tests build, and what an archive becomes once read. */
class MemoryImportSource(
    override val name: String,
    files: Map<String, ByteArray>,
) : ImportSource {

    private val files: Map<String, ByteArray> = files.mapKeys { (path, _) -> ProjectPaths.normalize(path) }

    override fun paths(): List<String> = files.keys.sorted()

    override fun read(path: String): ByteArray? = files[ProjectPaths.normalize(path)]

    companion object {
        /** Convenience for text fixtures. */
        fun ofText(name: String, files: Map<String, String>) =
            MemoryImportSource(name, files.mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8) })
    }
}

/** A project in a folder on disk, read lazily. */
class DirectoryImportSource(private val root: File) : ImportSource {

    init {
        require(root.isDirectory) { "'$root' is not a folder" }
    }

    override val name: String = root.name

    override fun paths(): List<String> =
        root.walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    override fun read(path: String): ByteArray? {
        val file = File(root, ProjectPaths.normalize(path))
        val inside = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        return if (inside && file.isFile) file.readBytes() else null
    }
}

/**
 * Reads a zip archive into memory, refusing archives that would not fit.
 *
 * A player picks an archive from anywhere, so it is treated as hostile: the
 * total it may expand to is capped, which is what stops a small file that
 * inflates to gigabytes from taking the app down.
 */
object ZipImportSource {

    const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024
    const val MAX_ENTRIES = 20_000

    fun read(name: String, archive: ByteArray, maxTotalBytes: Long = MAX_TOTAL_BYTES): ImportSource {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                if (files.size >= MAX_ENTRIES) throw ImportException("'$name' holds more than $MAX_ENTRIES files")
                val bytes = readCapped(zip, maxTotalBytes - total, name)
                total += bytes.size
                files[entry.name] = bytes
            }
        }
        if (files.isEmpty()) throw ImportException("'$name' is empty or is not a zip archive")
        return MemoryImportSource(name.substringBeforeLast('.'), stripSharedRoot(files))
    }

    private fun readCapped(zip: ZipInputStream, budget: Long, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > budget) throw ImportException("'$name' expands to more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB")
            out.write(buffer, 0, read)
        }
    }

    /**
     * Zipping a folder usually wraps everything in that folder's name. Taken
     * off, so `my_game/pubspec.yaml` and `pubspec.yaml` import the same way.
     */
    internal fun stripSharedRoot(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val normalized = files.mapKeys { (path, _) -> ProjectPaths.normalize(path) }
        val roots = normalized.keys.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
        val shared = roots.singleOrNull()?.takeIf { it.isNotEmpty() } ?: return normalized
        return normalized.mapKeys { (path, _) -> path.removePrefix("$shared/") }
    }

    private const val BUFFER = 16 * 1024
}
