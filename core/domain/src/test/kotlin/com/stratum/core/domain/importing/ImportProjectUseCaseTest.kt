package com.stratum.core.domain.importing

import com.stratum.core.domain.content.ContentPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportProjectUseCaseTest {

    private class Files(override val name: String, private val files: Set<String>) : ImportSource {
        override fun paths() = files.toList()
        override fun read(path: String): ByteArray? = if (path in files) ByteArray(0) else null
    }

    private class ByExtension(override val id: String, private val extension: String) : ProjectImporter {
        override val displayName = "$id files"
        override fun recognises(source: ImportSource) = source.paths().any { it.endsWith(extension) }
        override fun import(source: ImportSource) = ImportResult(
            pack = ContentPack(id = id, name = source.name, author = "test"),
            textures = listOf(ImportedTexture("$id:block/top", ImageRegion("tiles.png", 0, 0, 16, 16))),
            warnings = listOf("from $id"),
        )
    }

    private class RecordingWriter : ImportedAssetWriter {
        val textures = mutableListOf<ImportedTexture>()
        var sheetCalls = 0
        override fun writeTextures(packId: String, source: ImportSource, textures: List<ImportedTexture>) {
            this.textures += textures
        }
        override fun writeSpriteSheets(packId: String, source: ImportSource, sheets: List<ImportedSpriteSheet>) {
            sheetCalls++
        }
    }

    private val registry = ImporterRegistry()
        .register(ByExtension("flame", ".yaml"))
        .register(ByExtension("tiled", ".tmx"))

    @Test
    fun `the first importer that recognises the project runs`() {
        val writer = RecordingWriter()
        val outcome = ImportProjectUseCase(registry, writer)(Files("game", setOf("pubspec.yaml", "level.tmx")))

        assertEquals("flame", outcome.pack.id)
        assertEquals("flame files", outcome.importerName)
        assertEquals(listOf("from flame"), outcome.warnings)
    }

    @Test
    fun `art is handed to the platform, and nothing is written that was not imported`() {
        val writer = RecordingWriter()
        ImportProjectUseCase(registry, writer)(Files("maps", setOf("level.tmx")))

        assertEquals(listOf("tiled:block/top"), writer.textures.map { it.key })
        assertEquals(0, writer.sheetCalls)
    }

    @Test
    fun `a project nothing recognises names what is supported`() {
        val failure = assertFailsWith<ImportException> {
            ImportProjectUseCase(registry, RecordingWriter())(Files("notes", setOf("readme.txt")))
        }
        assertTrue("flame files" in failure.message.orEmpty() && "tiled files" in failure.message.orEmpty())
    }

    @Test
    fun `two importers cannot share an id`() {
        assertFailsWith<IllegalArgumentException> { registry.register(ByExtension("tiled", ".tmj")) }
    }
}
