package com.stratum.tools.artpreview

import com.stratum.engine.model.mask.MaskArt
import com.stratum.engine.model.mask.MaskGenome
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The masks as cartoon art: every preset turning through eight angles, a
 * sheet of random masks front-on, and each preset large.
 *
 *   args: <outDir> [pixels a frame]
 */
object MaskArtPreview {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "docs/screenshots/mask-art" }).apply { mkdirs() }
        val px = args.getOrNull(1)?.toIntOrNull() ?: 200
        val turns = 8
        val presets = MaskGenome.presets
        val fh = (px * MaskArt.ASPECT).toInt()
        val label = 28
        val sheet = BufferedImage(px * turns, (fh + label) * presets.size, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xF4ECDD); g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        val t0 = System.nanoTime()
        var frames = 0
        for ((row, genome) in presets.withIndex()) {
            val y = row * (fh + label)
            g.color = Color(0x2A1E2E); g.drawString(genome.name, 10, y + 20)
            for (i in 0 until turns) {
                val f = MaskArt.draw(genome, (i * 2.0 * Math.PI / turns).toFloat(), px); frames++
                g.drawImage(image(f), i * px, y + label, null)
            }
        }
        val ms = (System.nanoTime() - t0) / 1e6
        g.dispose()
        ImageIO.write(sheet, "png", File(out, "turns-sheet.png"))
        println("wrote turns-sheet.png: $frames frames at ${px}px in ${"%.0f".format(ms)} ms (${"%.1f".format(ms / frames)} ms a frame)")

        // Random masks, front-on and three-quarter.
        val cols = 8; val rows = 3
        val rand = BufferedImage(px * cols, fh * rows, BufferedImage.TYPE_INT_RGB)
        val rg = rand.createGraphics()
        rg.color = Color(0xF4ECDD); rg.fillRect(0, 0, rand.width, rand.height)
        for (i in 0 until cols * rows) {
            val genome = MaskGenome.random(i * 97L + 11)
            rg.drawImage(image(MaskArt.draw(genome, if (i % 2 == 0) 0f else 0.55f, px)), (i % cols) * px, (i / cols) * fh, null)
        }
        rg.dispose()
        ImageIO.write(rand, "png", File(out, "random-sheet.png"))

        // Each preset big, three-quarter view, the way a player first meets it.
        for (genome in presets) {
            val big = MaskArt.draw(genome, 0.45f, 512)
            val img = BufferedImage(big.width, big.height, BufferedImage.TYPE_INT_RGB)
            val bg = img.createGraphics()
            bg.color = Color(0xF4ECDD); bg.fillRect(0, 0, img.width, img.height)
            bg.drawImage(image(big), 0, 0, null); bg.dispose()
            ImageIO.write(img, "png", File(out, "mask-${genome.name.lowercase().replace(' ', '-')}.png"))
        }
        println("wrote random-sheet.png and ${presets.size} large masks")
    }

    private fun image(f: MaskArt.Frame): BufferedImage =
        BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, f.width, f.height, f.argb, 0, f.width) }
}
