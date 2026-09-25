package com.stratum.plugins

import com.stratum.importer.common.MemoryImportSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportersTest {

    @Test
    fun `the most specific importer wins`() {
        val flame = MemoryImportSource.ofText("g", mapOf("pubspec.yaml" to "name: g\ndependencies:\n  flame: ^1.0\n", "assets/tiles/a.tmj" to """{"type":"map"}"""))
        val tiled = MemoryImportSource.ofText("m", mapOf("a.tmj" to """{"type":"map"}"""))
        val plugin = MemoryImportSource.ofText("p", mapOf("plugin.json" to "{}", "pack.json" to "{}", "pubspec.yaml" to "name: p\ndependencies:\n  flame: ^1.0\n"))

        assertEquals("flame", Importers.standard().importerFor(flame).id)
        assertEquals("tiled", Importers.standard().importerFor(tiled).id)
        assertEquals("stratum", Importers.standard().importerFor(plugin).id)
    }
}
