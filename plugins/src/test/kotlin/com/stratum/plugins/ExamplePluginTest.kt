package com.stratum.plugins

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginResolver
import com.stratum.core.domain.tabletop.Attribute
import com.stratum.importer.common.DirectoryImportSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The example in the repository is documentation; this keeps it true. */
class ExamplePluginTest {

    private val folder = File("../examples/plugins/nri-chronicles")

    @Test
    fun `the example plugin installs, loads on the built-in pack, and resolves`() {
        val result = PluginImporter().import(DirectoryImportSource(folder))
        val manifest = result.manifestOrDerived

        assertEquals("nri.chronicles", manifest.id)
        assertEquals("CC-BY-4.0", manifest.license)
        assertEquals("Ọfọ & Bronze: Chronicles of Nri", manifest.name, "UTF-8 names survive")
        assertTrue(result.warnings.isEmpty(), "warnings: ${result.warnings}")

        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack, result.pack))
        assertEquals(4, content.checks.count { it.id.startsWith("nri:") })
        assertEquals(Attribute.INSIGHT, content.check("nri:afa_divination")!!.attribute)

        val resolution = PluginResolver.resolve(listOf(manifest), listOf(manifest.id), builtIn = listOf(PluginManifest.of(IgboContentPack.pack)))
        assertEquals(listOf(manifest.id), resolution.loadOrder)
    }
}
