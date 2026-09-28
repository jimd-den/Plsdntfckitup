package com.stratum.core.data.save

import com.stratum.core.data.sprite.AtomicFiles
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.session.WorldSaveRepository
import com.stratum.core.domain.session.WorldSummary
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Worlds as one folder each under [root]:
 *
 * - `summary.json`, the menu's row, small enough that listing every world
 *   reads nothing else;
 * - `world.json`, the state, pointing at
 * - `chunks-N.bin`, the changed chunks (see [ChunkBlob]).
 *
 * Every file is written beside itself and renamed into place, and the
 * chunk file is written under a new name each save before the state that
 * points at it, so a phone that dies mid-save keeps the last good world
 * whole rather than new chunks under old state. A world that will not read
 * is skipped and logged: one corrupt save must not lock a player out of
 * every other world.
 */
class FileWorldSaveStore(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Told about every save that would not read. Defaults to the platform log. */
    private val onCorrupt: (File, Exception) -> Unit = { file, error -> log.log(Level.WARNING, "Skipping unreadable world save $file", error) },
) : WorldSaveRepository {

    /** One write at a time: an autosave and a rename landing together must not interleave their files. */
    private val writing = Mutex()

    override suspend fun list(): List<WorldSummary> = withContext(io) {
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { folder -> readOrSkip(File(folder, SUMMARY)) { WorldSaveJson.decodeSummary(it.readText()) } }
            .sortedWith(compareByDescending<WorldSummary> { it.lastPlayedAt }.thenBy { it.id })
    }

    override suspend fun load(id: String): WorldSave? = withContext(io) {
        val folder = folderFor(id) ?: return@withContext null
        readOrSkip(File(folder, STATE)) { file ->
            val state = WorldSaveJson.decodeState(file.readText())
            val chunks = state.chunkFile?.let { name -> ChunkBlob.decode(File(folder, name).readBytes()) }.orEmpty()
            state.toDomain(chunks)
        }
    }

    override suspend fun save(save: WorldSave) = withContext(io) {
        writing.withLock {
            val folder = requireNotNull(folderFor(save.id)) { "'${save.id}' cannot name a world folder" }.apply { mkdirs() }
            val chunkFile = if (save.chunks.isEmpty()) null else "$CHUNK_PREFIX${nextGeneration(folder)}$CHUNK_SUFFIX"
            chunkFile?.let { AtomicFiles.write(File(folder, it), ChunkBlob.encode(save.chunks)) }
            AtomicFiles.write(File(folder, STATE), WorldSaveJson.encodeState(save, chunkFile).toByteArray())
            AtomicFiles.write(File(folder, SUMMARY), WorldSaveJson.encodeSummary(save.summary()).toByteArray())
            // Only now is the previous chunk file unreferenced.
            chunkFiles(folder).filter { it.name != chunkFile }.forEach { it.delete() }
        }
    }

    override suspend fun delete(id: String) {
        withContext(io) { writing.withLock { folderFor(id)?.deleteRecursively() } }
    }

    /** Renames the world in its state and its summary; the chunks are not touched. A world that will not read is left as it is. */
    override suspend fun rename(id: String, name: String) {
        withContext(io) {
            writing.withLock {
                val folder = folderFor(id) ?: return@withLock
                val state = readOrSkip(File(folder, STATE)) { WorldSaveJson.decodeState(it.readText()) } ?: return@withLock
                val summary = readOrSkip(File(folder, SUMMARY)) { WorldSaveJson.decodeSummary(it.readText()) }
                AtomicFiles.write(File(folder, STATE), WorldSaveJson.encodeState(state.copy(name = name)).toByteArray())
                summary?.let { AtomicFiles.write(File(folder, SUMMARY), WorldSaveJson.encodeSummary(it.copy(name = name)).toByteArray()) }
            }
        }
    }

    private fun <T> readOrSkip(file: File, read: (File) -> T): T? {
        if (!file.isFile) return null
        return try {
            read(file)
        } catch (error: IllegalArgumentException) {
            // Malformed JSON, and values the domain refuses, both land here.
            onCorrupt(file, error)
            null
        } catch (error: IOException) {
            onCorrupt(file, error)
            null
        }
    }

    private fun chunkFiles(folder: File): List<File> =
        folder.listFiles { file -> file.name.startsWith(CHUNK_PREFIX) && file.name.endsWith(CHUNK_SUFFIX) }.orEmpty().toList()

    private fun nextGeneration(folder: File): Long =
        (chunkFiles(folder).mapNotNull { it.name.removePrefix(CHUNK_PREFIX).removeSuffix(CHUNK_SUFFIX).toLongOrNull() }.maxOrNull() ?: 0L) + 1

    /**
     * Ids are generated, but only the safe characters ever reach the file
     * system, and never a name made only of dots: `..` would put a delete
     * one folder above every world.
     */
    private fun folderFor(id: String): File? = id.replace(UNSAFE, "_").takeIf { name -> name.any { it != '.' } }?.let { File(root, it) }

    private companion object {
        const val SUMMARY = "summary.json"
        const val STATE = "world.json"
        const val CHUNK_PREFIX = "chunks-"
        const val CHUNK_SUFFIX = ".bin"
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
        val log: Logger = Logger.getLogger(FileWorldSaveStore::class.java.name)
    }
}
