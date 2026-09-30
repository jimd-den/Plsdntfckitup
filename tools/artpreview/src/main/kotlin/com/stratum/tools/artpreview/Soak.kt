package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.WorldSession
import com.stratum.importer.common.DirectoryImportSource
import com.stratum.plugins.PluginImporter
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plays the shipped world for a few minutes the way the phone does -- built-in
 * pack plus the bundled microvoxel plugin, the hero walking, frames built,
 * autosaves written -- and reports time, memory and anything thrown.
 *
 *   args: [seconds] [tier]
 */
object Soak {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.getOrNull(0)?.toIntOrNull() ?: 150
        val tier = args.getOrNull(1)?.let { QualityTier.valueOf(it) } ?: QualityTier.MEDIUM
        val plugin = PluginImporter().import(DirectoryImportSource(File("examples/plugins/microvoxel-realms"))).pack
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack, plugin))
        val config = WorldConfig(seed = 1234567L, simulationRadius = RenderSettings.of(tier).streamingRadius, seaLevel = 12, surfaceVariation = 4, caveDensity = 0.44f)
        val session = WorldSession(content, config)
        val director = StyleSheetArtDirector(StyleLexicon.interpret("house", ArtDirection.HOUSE, 1L).direction)
        val builder = SceneBuilder(director, TextureLibrary(), biomeAt = { x, y -> session.biomeAt(x, y) }, settings = RenderSettings.of(tier), microTerrain = session.microTerrain)
        val identity = WorldIdentity(id = "soak", name = "Soak", createdAt = 0L, heroName = "Soak")
        val rt = Runtime.getRuntime()
        val dt = 1f / 30f
        var worstFrame = 0.0; var peak = 0L
        val start = System.nanoTime()
        var heading = 0f
        var last = session.player.position
        for (frame in 0 until seconds * 30) {
            val t = frame * dt
            // Wander, turning away whenever something stops the hero, so new ground keeps streaming in.
            if (frame % 15 == 0) {
                val p = session.player.position
                if (kotlin.math.abs(p.x - last.x) + kotlin.math.abs(p.y - last.y) < 0.5f) heading += 1.9f
                last = p
            }
            session.setMoveInput(cos(heading), sin(heading))
            val s = System.nanoTime()
            session.tick(dt)
            val p = session.player.position
            val camera = SceneCamera(target = Vec3(p.x, p.y, p.z), aspect = 9f / 19.5f)
            builder.build(session.world, camera, emptyList(), WorldTime(dayFraction = 0.4f), worldRevision = frame)
            val ms = (System.nanoTime() - s) / 1e6
            // Real time, as on the phone: the background meshing gets the same wall clock it would there.
            val spare = (dt * 1000 - ms).toLong()
            if (spare > 0) Thread.sleep(spare)
            if (frame > 60 && ms > worstFrame) worstFrame = ms
            if (frame % 30 == 0) peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
            if (frame % (60 * 30) == 60 * 30 - 1) {
                val save = session.worldSave(identity, savedAt = System.currentTimeMillis())
                println("[soak] t=${"%.0f".format(t)}s saved: ${save.chunks.size} changed chunks, ${save.consumedMarkers.size} markers")
            }
            if (frame % (10 * 30) == 0) { System.gc(); println("[soak] live after GC ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB") }
            if (frame % (10 * 30) == 0) println("[soak] t=${"%.0f".format(t)}s at ${"%.0f,%.0f".format(p.x, p.y)} heap ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB, worst frame ${"%.0f".format(worstFrame)} ms, detail pending ${builder.detailPending}")
        }
        for ((thread, stack) in Thread.getAllStackTraces()) if (thread.name.startsWith("micro-detail")) {
            println("[soak] ${thread.name} ${thread.state}")
            stack.take(14).forEach { println("        at $it") }
        }
        println("[soak] done: ${seconds}s played in ${(System.nanoTime() - start) / 1_000_000_000}s, peak heap ${peak / 1_000_000} MB of ${rt.maxMemory() / 1_000_000} MB, worst frame ${"%.0f".format(worstFrame)} ms")
    }
}
