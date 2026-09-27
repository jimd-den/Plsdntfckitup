package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.ForgePlanner
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.engine.scene.forge.AssetForge
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Forges an asset kit for one style and writes it into the pack's resources.
 *
 *   forgeKit <kit-name> "<style prompt>" [biome ids, comma separated]
 *
 * Each image's prompt is written next to it in `kit.txt`, so anyone looking at
 * a texture can see exactly what was asked for — which is the first thing you
 * need when an asset comes back wrong and the last thing a generation log
 * usually keeps.
 */
object ForgeKit {

    @JvmStatic
    fun main(args: Array<String>) {
        val name = args.getOrNull(0) ?: "house"
        val prompt = args.getOrNull(1) ?: "stratum house style"
        val biomes = args.getOrNull(2)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
            ?: setOf("igbo:sacred_grove")
        val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
        require(key.isNotBlank()) { "Set OPENROUTER_API_KEY to forge a kit" }

        val direction = StyleLexicon.interpret(prompt, ArtDirection.HOUSE).direction
        val out = File("content/igbo/src/main/resources/forge/$name").also { it.mkdirs() }
        // Originals stay out of the repository; they are for debugging a bad
        // asset, not for shipping.
        val raw = File("tools/artpreview/build/forge-raw/$name").also { it.mkdirs() }
        val planned = ForgePlanner.plan(direction, IgboContentPack.pack, biomes)
        // Already forged means already paid for: only what is missing is asked for.
        val orders = planned.filterNot { File(out, ForgedTextures.fileNameFor(it.key)).exists() }
        println("forging ${orders.size} of ${planned.size} assets for '$name' ($prompt) into $out")

        val forge = AssetForge(OpenRouterImages(key), AwtCodec)
        val results = runBlocking {
            forge.forge(
                orders,
                concurrency = 4,
                onRaw = { order, bytes -> File(raw, ForgedTextures.fileNameFor(order.key).removeSuffix(".png") + "-${System.nanoTime()}.webp").writeBytes(bytes) },
            ) { asset ->
                if (asset.texture != null) {
                    File(out, ForgedTextures.fileNameFor(asset.order.key)).writeBytes(AwtCodec.encodePng(asset.texture!!))
                    println("  ok   ${asset.order.key} ${asset.texture!!.width}x${asset.texture!!.height}")
                } else {
                    println("  FAIL ${asset.order.key}: ${asset.failure}")
                }
            }
        }
        val log = File(out, "kit.txt")
        log.appendText(
            buildString {
                if (!log.exists() || log.length() == 0L) {
                    appendLine("# Append-only generation log: every order and the prompt it was sent with, in")
                    appendLine("# order. A FAILED entry followed by a later entry for the same key was retried.")
                    appendLine("# The files in this folder are the final kit; index.txt lists them.")
                    appendLine()
                    appendLine("style: $prompt")
                }
                appendLine("model: meta/muse-image")
                appendLine()
                results.forEach { appendLine("${it.order.key} [${it.order.kind}]${if (it.succeeded) "" else " FAILED: ${it.failure}"}\n  ${it.order.prompt}\n") }
            },
        )
        // Resources inside an APK cannot be listed, so the kit says what is in it.
        File(out, "index.txt").writeText(
            out.listFiles { f -> f.extension == "png" }.orEmpty().map { it.name }.sorted().joinToString("\n"),
        )
        println("${results.count { it.succeeded }}/${results.size} forged")
    }
}
