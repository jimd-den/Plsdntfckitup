package com.stratum.engine.model.mask

import com.stratum.core.domain.micro.MicroModel
import com.stratum.engine.model.MicroModelRenderer
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Pictures of masks for people to judge: a straight-on elevation (how the
 * design reads as a poster) beside the studio's own isometric preview (how it
 * stands as an object). Used by [MaskShotsTest] to write the docs' screenshots.
 */
internal object MaskShots {
    private const val PAPER = 0xFFEFE8DA.toInt()

    /** The model seen straight from the front (-Y), a voxel as [k] pixels, edges picked out by a raking light. */
    fun front(m: MicroModel, k: Int): BufferedImage {
        val img = BufferedImage(m.sizeX * k, m.sizeZ * k, BufferedImage.TYPE_INT_ARGB)
        val depth = IntArray(m.sizeX * m.sizeZ) { -1 }
        val colour = IntArray(m.sizeX * m.sizeZ)
        for (z in 0 until m.sizeZ) for (x in 0 until m.sizeX) {
            for (y in 0 until m.sizeY) {
                val v = m.cells[m.index(x, y, z)]
                if (v != 0) { depth[z * m.sizeX + x] = m.sizeY - y; colour[z * m.sizeX + x] = MicroModelRenderer.defaultColour(m.palette[v - 1]); break }
            }
        }
        for (z in 0 until m.sizeZ) for (x in 0 until m.sizeX) {
            val d = depth[z * m.sizeX + x]
            val argb = if (d < 0) PAPER else {
                // Light from the upper left: a column standing prouder than its upper-left neighbour catches it.
                val nx = (x - 1).coerceAtLeast(0); val nz = (z + 1).coerceAtMost(m.sizeZ - 1)
                val nd = depth[nz * m.sizeX + nx].coerceAtLeast(0)
                val lit = (1f + 0.07f * (d - nd).coerceIn(-3, 3)).coerceIn(0.75f, 1.2f)
                shade(colour[z * m.sizeX + x], lit)
            }
            for (py in 0 until k) for (px in 0 until k) img.setRGB(x * k + px, (m.sizeZ - 1 - z) * k + py, argb)
        }
        return img
    }

    /** The studio preview, turned so the mask's face is toward the viewer. */
    fun iso(m: MicroModel, size: Int): BufferedImage {
        val px = MicroModelRenderer.render(m, size, turn = 1, background = PAPER)
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, size, size, px, 0, size) }
    }

    /** Front and iso side by side with the name under them: one card. */
    fun card(m: MicroModel, caption: String, h: Int = 360): BufferedImage {
        val k = (h - 20) / m.sizeZ
        val f = front(m, k.coerceAtLeast(1))
        val i = iso(m, h)
        val w = f.width + i.width + 40
        val out = BufferedImage(w, h + 40, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(PAPER, true); g.fillRect(0, 0, out.width, out.height)
        g.drawImage(f, 10, (h - f.height) / 2, null)
        g.drawImage(i, f.width + 30, 0, null)
        g.color = Color(0x1A171B)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.drawString(caption, 10, h + 26)
        g.dispose()
        return out
    }

    /** Cards in a grid. */
    fun sheet(cards: List<BufferedImage>, columns: Int): BufferedImage {
        val cw = cards.maxOf { it.width }; val chh = cards.maxOf { it.height }
        val rows = (cards.size + columns - 1) / columns
        val out = BufferedImage(cw * columns, chh * rows, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.color = Color(PAPER, true); g.fillRect(0, 0, out.width, out.height)
        cards.forEachIndexed { n, c -> g.drawImage(c, (n % columns) * cw + (cw - c.width) / 2, (n / columns) * chh, null) }
        g.dispose()
        return out
    }

    fun write(img: BufferedImage, file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }

    private fun shade(argb: Int, k: Float): Int {
        fun c(s: Int) = (((argb shr s) and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
}
