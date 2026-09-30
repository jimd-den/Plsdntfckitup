package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.plugin.PluginManifest
import com.stratum.core.domain.plugin.PluginResolver
import com.stratum.importer.common.DirectoryImportSource
import com.stratum.plugins.PluginArchive
import com.stratum.plugins.PluginImporter
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks a plugin folder the way the game will, then packs it into a
 * `.stratum` file: the files read, the content assembles on top of the
 * built-in pack, and every dependency resolves. A plugin that would not load
 * is not packed.
 *
 *   ./gradlew :tools:artpreview:packPlugin --args="path/to/plugin build/my.stratum"
 */
object PackPlugin {

    @JvmStatic
    fun main(args: Array<String>) {
        val folder = File(args.getOrNull(0) ?: fail("Usage: packPlugin <plugin folder> [output.stratum]"))
        val output = File(args.getOrNull(1) ?: "build/${folder.name}.${PluginArchive.EXTENSION}")
        val source = DirectoryImportSource(folder)
        val result = runCatching { PluginImporter().import(source) }.getOrElse { fail("Not a valid plugin: ${it.message}") }
        val manifest = result.manifestOrDerived

        runCatching { ContentPackAssembler().assemble(listOf(IgboContentPack.pack, result.pack)) }
            .onFailure { fail("The content does not load: ${it.message}") }
        val resolution = PluginResolver.resolve(listOf(manifest), listOf(manifest.id), builtIn = listOf(PluginManifest.of(IgboContentPack.pack)))
        resolution.problems.forEach { println("note: ${it.message} (install it alongside this plugin)") }

        output.parentFile?.mkdirs()
        output.writeBytes(PluginArchive.zip(source.paths().associateWith { source.read(it)!! }))
        println("${manifest.name} ${manifest.version} by ${manifest.author.ifEmpty { "unknown" }} (${manifest.license.ifEmpty { "no license" }})")
        println("  ${result.pack.heroClasses.size} classes, ${result.pack.enemies.size} enemies, ${result.pack.checks.size} checks, ${result.pack.maps.size} maps, ${result.textures.size} textures")
        result.warnings.forEach { println("  warning: $it") }
        println("wrote ${output.absolutePath}")
    }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
