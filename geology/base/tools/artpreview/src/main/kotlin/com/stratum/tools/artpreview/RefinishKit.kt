package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.AssetKind
import com.stratum.core.domain.art.ForgePlanner
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.engine.scene.forge.AssetForge
import java.io.File

/**
 * Re-runs the forge's clean-up on the originals it already paid for.
 *
 * ForgeKit keeps every raw image the model returned; when post-processing
 * changes — a better seam, a better key — this rebuilds a kit's finished files
 * of one kind from the newest raw for each key, at no cost and with the same
 * paintings. Usage: `refinishKit --args="house GROUND_MAP"`.
 */
object RefinishKit {

    private object NoModel : com.stratum.core.domain.ai.ImageModelPort {
        override suspend fun generateImage(
            request: com.stratum.core.domain.ai.ImageRequest,
            observer: com.stratum.core.domain.ai.GenerationObserver,
        ): Result<com.stratum.core.domain.ai.GeneratedImage> = error("refinishing never calls the model")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val name = args.getOrNull(0) ?: "house"
        val kind = AssetKind.valueOf(args.getOrNull(1) ?: AssetKind.GROUND_MAP.name)
        val out = File("content/igbo/src/main/resources/forge/$name")
        val raw = File("tools/artpreview/build/forge-raw/$name")
        val keys = ForgePlanner.plan(StyleLexicon.interpret("stratum house style", ArtDirection.HOUSE).direction, IgboContentPack.pack, emptySet())
            .filter { it.kind == kind }.map { it.key }
        // No model is called: finishing is pure.
        val forge = AssetForge(images = NoModel, codec = AwtCodec)
        keys.forEach { key ->
            val stem = ForgedTextures.fileNameFor(key).removeSuffix(".png")
            val newest = raw.listFiles { f -> f.name.startsWith("$stem-") }?.maxByOrNull { it.lastModified() }
            if (newest == null) { println("  skip $key: no original kept"); return@forEach }
            val decoded = AwtCodec.decode(newest.readBytes()) ?: run { println("  skip $key: undecodable"); return@forEach }
            val finished = forge.finish(kind, decoded)
            File(out, "$stem.png").writeBytes(AwtCodec.encodePng(finished))
            println("  ok   $key ${finished.width}x${finished.height} from ${newest.name}")
        }
    }
}
