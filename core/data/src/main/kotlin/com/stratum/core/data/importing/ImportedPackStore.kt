package com.stratum.core.data.importing

import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.importing.ImporterRegistry
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginOrder
import com.stratum.importer.common.ZipImportSource
import java.io.File

/**
 * Keeps installed plugins -- and every other imported project -- between runs,
 * with the player's load order.
 *
 * What is kept is the archive the player picked, not a serialised pack: the
 * importers are deterministic and quick, so re-reading the archive on launch
 * gives back exactly the same pack, and there is no second format to keep in
 * step with every field a pack can have. Art is cut once, at import, and
 * lives beside it.
 */
class ImportedPackStore(
    private val root: File,
    private val importers: ImporterRegistry,
) {
    private val archives: File get() = File(root, ARCHIVES).apply { mkdirs() }
    private val orderFile: File get() = File(root, ORDER)

    /**
     * Reads an archive the player picked. Named exactly as [loadAll] will
     * name it later, because an importer may take ids from the name, and the
     * pack must come back with the ids it had.
     */
    fun open(name: String, bytes: ByteArray): ImportSource = ZipImportSource.read(archiveName(name), bytes)

    /** Keeps the archive a pack was imported from, replacing any earlier import of it. */
    fun saveArchive(packId: String, name: String, bytes: ByteArray) {
        archivesOf(packId).forEach(File::delete)
        File(archives, "${fileSafe(packId)}$SEPARATOR${archiveName(name)}").writeBytes(bytes)
    }

    /** Every installed plugin, re-read from its archive. One that no longer imports is skipped, not fatal. */
    fun loadAll(): List<Pair<PluginManifest, ContentPack>> = archiveFiles().mapNotNull { file ->
        runCatching {
            val source = ZipImportSource.read(file.name.substringAfter(SEPARATOR), file.readBytes())
            importers.importerFor(source).import(source).let { it.manifestOrDerived to it.pack }
        }.getOrNull()
    }

    fun loadOrder(): PluginOrder = orderFile.takeIf(File::isFile)?.readText()?.let(PluginOrder::decode) ?: PluginOrder()

    fun saveOrder(order: PluginOrder) {
        root.mkdirs()
        orderFile.writeText(order.encode())
    }

    fun delete(packId: String) {
        archivesOf(packId).forEach(File::delete)
        packDirectory(packId).deleteRecursively()
    }

    fun textureDirectory(packId: String): File = File(packDirectory(packId), TEXTURES)

    /** Texture folders of every imported pack, for the 3D view to lay over its kit. */
    fun textureDirectories(): List<File> =
        File(root, PACKS).listFiles().orEmpty().map { File(it, TEXTURES) }.filter(File::isDirectory).sortedBy { it.path }

    private fun packDirectory(packId: String) = File(File(root, PACKS), fileSafe(packId))

    private fun archiveFiles(): List<File> = archives.listFiles { f -> f.extension == "zip" }.orEmpty().sortedBy { it.name }

    private fun archivesOf(packId: String) = archiveFiles().filter { it.name.startsWith(fileSafe(packId) + SEPARATOR) }

    private fun archiveName(name: String) = fileSafe(name.removeSuffix(".zip")) + ".zip"

    private fun fileSafe(text: String) = text.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private companion object {
        const val ARCHIVES = "archives"
        const val ORDER = "order.txt"
        const val PACKS = "packs"
        const val TEXTURES = "textures"
        const val SEPARATOR = "--"
    }
}
