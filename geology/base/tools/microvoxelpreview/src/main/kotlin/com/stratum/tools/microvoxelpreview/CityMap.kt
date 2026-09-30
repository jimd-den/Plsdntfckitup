package com.stratum.tools.microvoxelpreview

import com.stratum.engine.microvoxel.gen.ArchitectureRegistry
import com.stratum.engine.microvoxel.gen.BuildingsStage
import com.stratum.engine.microvoxel.gen.CityPlanStage
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.LotUse
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.Rect
import com.stratum.engine.microvoxel.gen.RoadKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the generator's services -- terrain, water, the city plan -- as a
 * map, without generating a single voxel. The same data a minimap or a
 * fast-travel screen would read.
 */
object CityMap {

    fun render(gen: MicroGenerator, x0: Int, y0: Int, span: Int, size: Int): BufferedImage {
        val surface = gen.fields.require(Fields.SURFACE)
        val sea = gen.fields.require(Fields.SEA_LEVEL)
        val scale = span.toFloat() / size
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        IntStream.range(0, size).parallel().forEach { py ->
            for (px in 0 until size) {
                val wx = (x0 + px * scale).toInt(); val wy = (y0 + (size - 1 - py) * scale).toInt()
                val h = surface.heightAt(wx, wy)
                val hx = surface.heightAt(wx + 4, wy) - surface.heightAt(wx - 4, wy)
                val hy = surface.heightAt(wx, wy + 4) - surface.heightAt(wx, wy - 4)
                val shade = ((-hx * 0.6f + hy * 0.6f) / 8f + 1f).coerceIn(0.55f, 1.35f)
                val rgb = if (h < sea) {
                    val d = ((sea - h) / 60f).coerceIn(0f, 1f)
                    mix(0x6FA8C8, 0x1E4E78, d)
                } else {
                    val e = ((h - sea) / 320f).coerceIn(0f, 1f)
                    val land = when {
                        e < 0.02f -> 0xD9C995
                        e < 0.45f -> mix(0x86A860, 0x5E7F46, e / 0.45f)
                        e < 0.75f -> mix(0x7E7A68, 0x9A968C, (e - 0.45f) / 0.3f)
                        else -> mix(0xB8B8B8, 0xF4F6FA, (e - 0.75f) / 0.25f)
                    }
                    scaleRgb(land, shade)
                }
                img.setRGB(px, py, rgb)
            }
        }
        val city = gen.fields.get(CityPlanStage.KEY) ?: return img
        val styles = gen.fields.get(ArchitectureRegistry.KEY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun fill(r: Rect, c: Int) {
            val a = (r.x0 - x0) / scale; val b = (r.x1 + 1 - x0) / scale
            val top = size - (r.y1 + 1 - y0) / scale; val bottom = size - (r.y0 - y0) / scale
            g.color = Color(c)
            g.fillRect(a.toInt(), top.toInt(), max(1, (b - a).toInt()), max(1, (bottom - top).toInt()))
        }
        val area = Rect(x0, y0, x0 + span, y0 + span)
        val regions = city.regionsTouching(area)
        for (region in regions) for (lot in region.lots) {
            if (lot.use == LotUse.PARK) { fill(lot.rect, 0x6FA457); continue }
            fill(lot.rect, 0xCBC3B0)
            val style = styles?.get(lot.style) ?: continue
            val b = BuildingsStage.resolve(lot, style, surface)
            val base = when (lot.style) { "tower" -> 0x4F6A86; "terrace" -> 0xA4533B; else -> 0xE0D2B4 }
            fill(b.footprint, scaleRgb(base, 1.15f - min(0.5f, b.floors * 0.025f)))
        }
        for (region in regions) for (road in region.roads) fill(road.rect, 0xE9E6DD)
        for (region in regions) for (road in region.roads) {
            val sw = road.kind.sidewalk
            val inner = if (road.alongX) Rect(road.rect.x0, road.rect.y0 + sw, road.rect.x1, road.rect.y1 - sw) else Rect(road.rect.x0 + sw, road.rect.y0, road.rect.x1 - sw, road.rect.y1)
            fill(inner, if (road.kind == RoadKind.ARTERIAL) 0x3A3B40 else 0x55575C)
        }
        // Region grid, faintly, so the planner's unit of work is visible.
        g.color = Color(255, 255, 255, 40)
        g.stroke = BasicStroke(1f)
        val r = city.regionSize
        var gx = Math.floorDiv(x0, r) * r
        while (gx <= x0 + span) { val p = ((gx - x0) / scale).toInt(); g.drawLine(p, 0, p, size); gx += r }
        var gy = Math.floorDiv(y0, r) * r
        while (gy <= y0 + span) { val p = size - ((gy - y0) / scale).toInt(); g.drawLine(0, p, size, p); gy += r }
        label(g, "City map  -  ${span / 4} x ${span / 4} blocks  -  regions of ${r / 4} blocks", 12, 22)
        g.dispose()
        return img
    }

    fun label(g: java.awt.Graphics2D, text: String, x: Int, y: Int) {
        try {
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
            g.color = Color(0, 0, 0, 150)
            g.drawString(text, x + 1, y + 1)
            g.color = Color(255, 255, 255)
            g.drawString(text, x, y)
        } catch (e: Throwable) {
            // Headless JDKs without fonts cannot draw text; the picture is still worth having.
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - tt) + ((b shr s) and 255) * tt).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun scaleRgb(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 255) * k).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
