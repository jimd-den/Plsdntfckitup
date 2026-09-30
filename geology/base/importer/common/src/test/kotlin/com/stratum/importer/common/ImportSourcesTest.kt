package com.stratum.importer.common

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.importing.readText
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImportSourcesTest {

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `a zipped folder reads the same as its contents`() {
        val archive = zip("my_game/pubspec.yaml" to "name: x".toByteArray(), "my_game/assets/a.png" to byteArrayOf(1))
        val source = ZipImportSource.read("my_game.zip", archive)

        assertEquals("my_game", source.name)
        assertEquals(listOf("assets/a.png", "pubspec.yaml"), source.paths())
        assertEquals("name: x", source.readText("pubspec.yaml"))
    }

    @Test
    fun `an entry that climbs out of the archive is refused`() {
        val archive = zip("../../evil.sh" to "boom".toByteArray())
        assertFailsWith<ImportException> { ZipImportSource.read("evil.zip", archive) }
    }

    @Test
    fun `an archive that inflates past the cap is refused`() {
        val archive = zip("big.bin" to ByteArray(10_000))
        assertFailsWith<ImportException> { ZipImportSource.read("big.zip", archive, maxTotalBytes = 1_000) }
    }

    @Test
    fun `something that is not a zip says so`() {
        assertFailsWith<ImportException> { ZipImportSource.read("notes.zip", "hello".toByteArray()) }
    }

    @Test
    fun `paths resolve relative to the file that names them`() {
        assertEquals("tilesets/grass.tsx", ProjectPaths.resolve("maps/level.tmx", "../tilesets/grass.tsx"))
        assertEquals("maps/grass.png", ProjectPaths.resolve("maps/level.tmx", "./grass.png"))
        assertEquals("a/b", ProjectPaths.normalize("a\\b"))
        assertFailsWith<ImportException> { ProjectPaths.resolve("level.tmx", "../../x.png") }
    }

    @Test
    fun `memory sources match paths however they are spelled`() {
        val source = MemoryImportSource.ofText("m", mapOf("./a/b.txt" to "hi"))

        assertEquals("hi", source.readText("a/b.txt"))
        assertNull(source.read("a/c.txt"))
    }

    @Test
    fun `names become stable namespaced ids`() {
        assertEquals("forest_tiles_v2", ImportNaming.slug("Forest Tiles (v2)"))
        assertEquals("oak:overworld_12", ImportNaming.id("Oak", "Overworld", "12"))
        assertEquals("Forest Tiles", ImportNaming.displayName("forest_tiles"))
    }

    @Test
    fun `tiled colours parse with or without alpha`() {
        assertEquals(0xFF112233, SeedColors.parse("#112233"))
        assertEquals(0x80112233, SeedColors.parse("#80112233"))
        assertNull(SeedColors.parse("green"))
    }
}
