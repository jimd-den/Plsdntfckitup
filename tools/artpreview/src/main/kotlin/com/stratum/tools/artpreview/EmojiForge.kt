package com.stratum.tools.artpreview

import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.ai.ImageRequest
import com.stratum.engine.model.mask.EmojiSheetForge
import com.stratum.engine.model.mask.IgboEmoji
import com.stratum.engine.model.mask.MaskArt
import com.stratum.engine.model.mask.MaskGenome
import com.stratum.engine.scene.Texture
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Forges Igbo emoji characters as sprite sheets.
 *
 *   emojiForge <outDir> [names...]
 *
 * With OPENROUTER_API_KEY set, each character is drawn by the image model
 * ([EmojiSheetForge]). Without one, an offline stand-in draws a deliberately
 * untidy magenta sheet from the procedural mask art -- sizes that wander,
 * cells off centre, specks -- so the cutting half of the pipeline can be
 * run and checked with no key; its pictures are not what a model draws.
 *
 * Writes per character: the raw sheet, the cut sheet (`<name>-sheet.png`,
 * transparent, 4x4), its clips as strips, the prompt, and a before/after card.
 */
object EmojiForge {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "build/emoji-forge" }).apply { mkdirs() }
        val names = args.drop(1)
        val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
        val offline = key.isBlank()
        val chosen = IgboEmoji.set.filter { names.isEmpty() || it.name in names || it.name.lowercase().replace(' ', '-') in names }
        println(if (offline) "no OPENROUTER_API_KEY: using the offline stand-in, which exercises the cutting only" else "forging with the image model")
        for (e in chosen) {
            val model: ImageModelPort = if (offline) StandIn(e.genome) else OpenRouterImages(key)
            val slug = e.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            var raw: ByteArray? = null
            val forge = EmojiSheetForge(Recording(model) { raw = it }, AwtCodec)
            val t = System.nanoTime()
            val sheet = runBlocking { forge.forge(e.genome) }.getOrElse { println("  FAIL ${e.name}: ${it.message}"); null } ?: continue
            val ms = (System.nanoTime() - t) / 1e6
            raw?.let { File(out, "$slug-raw.png").writeBytes(it) }
            File(out, "$slug-sheet.png").writeBytes(AwtCodec.encodePng(sheet.image))
            File(out, "$slug-prompt.txt").writeText(sheet.prompt)
            val bad = sheet.defects.withIndex().filter { it.value != null }
            println("  ${e.name}: ${sheet.frames.size} frames, clips ${sheet.sprite.clips.joinToString { "${it.state}x${it.frameCount}" }}, " +
                "${bad.size} bad${if (bad.isEmpty()) "" else " (" + bad.joinToString { "${EmojiSheetForge.CELLS[it.index].name}: ${it.value}" } + ")"}, ${"%.0f".format(ms)} ms")
            raw?.let { ImageIO.write(card(ImageIO.read(it.inputStream()), sheet, offline), "png", File(out, "$slug-card.png")) }
        }
    }

    /** Keeps the raw bytes the model returned, for the before/after card. */
    private class Recording(val inner: ImageModelPort, val keep: (ByteArray) -> Unit) : ImageModelPort {
        override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> =
            inner.generateImage(request, observer).onSuccess { keep(it.bytes) }
    }

    /**
     * The offline stand-in: a 4x4 magenta sheet of the character from the
     * procedural art, turned a little cell to cell, drawn at wandering sizes
     * and offsets with a stray speck or two -- the untidiness a model hands
     * back, so the cutter has something real to do.
     */
    private class StandIn(val genome: MaskGenome) : ImageModelPort {
        override suspend fun generateImage(request: ImageRequest, observer: GenerationObserver): Result<GeneratedImage> {
            val size = request.width
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(0xFF00FF); g.fillRect(0, 0, size, size)
            val cell = size / EmojiSheetForge.COLUMNS
            val r = Random(genome.hashCode())
            for (i in 0 until 16) {
                val angle = listOf(0f, 0.2f, -0.2f, 0.4f)[i % 4] + (if (i / 4 == 1) 0.3f else 0f)
                val scale = 0.62f + r.nextFloat() * 0.22f
                val w = (cell * scale).toInt()
                val f = MaskArt.draw(genome, angle, w)
                val art = BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, f.width, f.height, f.argb, 0, f.width) }
                val h = (w * f.height / f.width).coerceAtMost((cell * 0.95f).toInt())
                val ww = w * h / (w * f.height / f.width)
                val x = (i % 4) * cell + (cell - ww) / 2 + r.nextInt(-cell / 12, cell / 12)
                val y = (i / 4) * cell + (cell - h) / 2 + r.nextInt(-cell / 14, cell / 14)
                g.drawImage(art, x, y, ww, h, null)
                if (r.nextFloat() < 0.4f) { g.color = Color(0x2B1A1C); g.fillOval((i % 4) * cell + r.nextInt(cell - 8), (i / 4) * cell + r.nextInt(cell - 8), 5, 5) }
            }
            g.dispose()
            val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
            return Result.success(GeneratedImage(bytes = bytes, mimeType = "image/png", width = size, height = size))
        }
    }

    /** Raw on the left, the cut sheet on a checker on the right. */
    private fun card(raw: BufferedImage, sheet: EmojiSheetForge.Sheet, offline: Boolean): BufferedImage {
        val side = 640
        val out = BufferedImage(side * 2 + 30, side + 60, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF1EADC); g.fillRect(0, 0, out.width, out.height)
        g.drawImage(raw, 10, 10, side, side, null)
        val cut = image(sheet.image)
        for (y in 0 until side step 16) for (x in 0 until side step 16) {
            g.color = if ((x / 16 + y / 16) % 2 == 0) Color(0xE4E0E8) else Color(0xFAFAFC); g.fillRect(side + 20 + x, 10 + y, 16, 16)
        }
        g.drawImage(cut, side + 20, 10, side, side, null)
        g.color = Color(0x1A171B); g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString("${if (offline) "Offline stand-in" else "Image model"}: raw sheet on magenta", 12, side + 38)
        g.drawString("Cut: keyed, specks cleared, every frame the same size and centre", side + 22, side + 38)
        g.dispose()
        return out
    }

    private fun image(t: Texture): BufferedImage = BufferedImage(t.width, t.height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, t.width, t.height, t.argb, 0, t.width) }
}
