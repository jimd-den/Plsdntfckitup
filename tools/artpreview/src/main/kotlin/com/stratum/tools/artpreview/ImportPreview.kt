package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.importing.ImportOutcome
import com.stratum.core.domain.importing.ImportProjectUseCase
import com.stratum.core.domain.importing.ImportSource
import com.stratum.core.domain.world.BiomeSource
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import com.stratum.importer.common.DirectoryImportSource
import com.stratum.importer.common.ZipImportSource
import com.stratum.importer.flame.Importers
import java.io.File
import javax.imageio.ImageIO

/**
 * Imports a project the way the app does, prints what came of it, and draws
 * the level from the player's spawn -- on the imported pack layered over the
 * built-in one, exactly as a player would load it.
 */
object ImportPreview {

    private const val WIDTH = 1280
    private const val HEIGHT = 720

    @JvmStatic
    fun main(args: Array<String>) {
        val input = File(args.firstOrNull() ?: error("Usage: importPreview <project folder or .zip> [output folder]"))
        val out = File(args.getOrNull(1) ?: "build/import-preview").apply { mkdirs() }
        val writer = JvmImportedAssetWriter(out)
        val outcome = ImportProjectUseCase(Importers.standard(), writer)(open(input))
        println(report(outcome))
        val pack = playingOn(outcome.pack, args.getOrNull(2)) ?: return
        val file = File(out, "${pack.id}/${pack.terrain?.options?.get(TerrainRecipe.MAP_OPTION)?.substringAfter(':')}.png")
        ImageIO.write(render(pack, writer.textureDirectory(pack.id)), "png", file)
        println("wrote ${file.absolutePath}")
    }

    /** The pack set to play on the map whose id contains [wanted], or on its own first map. */
    private fun playingOn(pack: ContentPack, wanted: String?): ContentPack? {
        val map = pack.maps.firstOrNull { wanted != null && wanted in it.id } ?: pack.maps.firstOrNull() ?: return null
        return pack.copy(terrain = TerrainRecipe.tileMap(map.id))
    }

    private fun open(input: File): ImportSource =
        if (input.isDirectory) DirectoryImportSource(input) else ZipImportSource.read(input.name, input.readBytes())

    fun report(outcome: ImportOutcome): String = buildString {
        val pack = outcome.pack
        appendLine("${pack.name} ${pack.version} (${outcome.importerName}, id '${pack.id}')")
        appendLine("  blocks ${pack.blocks.size}, maps ${pack.maps.size}, sheets ${pack.spriteSheets.size}, classes ${pack.heroClasses.size}")
        pack.maps.forEach { map ->
            appendLine("  map '${map.id}' ${map.width}x${map.height}: ${map.layers.joinToString { "${it.name}@${it.elevation}+${it.thickness}" }}")
            appendLine("    markers: ${map.markers.groupingBy { it.kind }.eachCount()}")
        }
        pack.spriteSheets.forEach { sheet ->
            appendLine("  sheet '${sheet.id}' ${sheet.columns}x${sheet.rows} of ${sheet.frameWidth}x${sheet.frameHeight}: ${sheet.clips.joinToString { "${it.state}(${it.frameCount})" }}")
        }
        outcome.warnings.forEach { appendLine("  warning: $it") }
    }

    fun render(pack: ContentPack, textures: File): java.awt.image.BufferedImage {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack, pack))
        val config = WorldConfig(seed = 1L, simulationRadius = 3)
        val generator = StratumTerrain.create(content.terrainContext(config))
        val world = StreamingWorld(content.registry, generator, config)
        world.focusOn(BlockPos(0, 0, 0))

        val library = TextureLibrary().also { ForgedTextures.loadInto(textures, it); it.aliasMissingSides() }
        val director = StyleSheetArtDirector(ArtDirection.HOUSE, BiomeArtKit.deriveAll(pack))
        val builder = SceneBuilder(director, library, biomeAt = { x, y -> (generator as? BiomeSource)?.biomeAt(x, y) })
        val stand = world.surfaceAt(0, 0) + 1f
        val camera = SceneCamera(target = Vec3(0.5f, 0.5f, stand), aspect = WIDTH.toFloat() / HEIGHT)
        val player = SceneActor(0.5f, 0.5f, stand, ActorPresentation("p", ActorRole.PLAYER))
        val frame = builder.build(world, camera, listOf(player), WorldTime(dayFraction = 0.42f))
        return SceneRasterizer(WIDTH, HEIGHT, library).render(frame)
    }
}
