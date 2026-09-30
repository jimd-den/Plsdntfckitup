package com.stratum.tools.microvoxelpreview

import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroWorld
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.StageSpec
import com.stratum.engine.microvoxel.geo.GeoAtlas
import com.stratum.engine.microvoxel.geo.Provinces
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The geology, seen: a map of the provinces across a continent-sized
 * stretch of world, and a diorama of each province on its own.
 *
 *   args: <outDir> [seed] [fast] [province ids or "map" to limit what is drawn]
 *
 * Uses the block world's proportions (sea at 12 blocks, peaks under 40), so
 * what it shows is what the game builds.
 */
object GeoPreview {

    private const val SEA = 48
    private const val CEILING = 160

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "build/geo-preview" }).apply { mkdirs() }
        val seed = args.getOrNull(1)?.toLongOrNull() ?: 20260928L
        val fast = args.contains("fast")
        val only = args.drop(2).filter { it != "fast" }.toSet()
        fun want(name: String) = only.isEmpty() || name in only

        if (want("map")) {
            val gen = generator(seed, "africa")
            val (colour, provinces) = maps(gen, span = 32000, size = if (fast) 700 else 1400)
            ImageIO.write(colour, "png", File(out, "geo-map.png"))
            ImageIO.write(provinces, "png", File(out, "geo-provinces.png"))
            log("wrote the maps")
        }
        val w = if (fast) 640 else 1100; val h = if (fast) 400 else 680
        for (p in Provinces.all(seed)) {
            if (!want(p.id)) continue
            val t = System.currentTimeMillis()
            val gen = generator(seed, p.id, vegetation = true)
            val (x, y) = viewpoint(gen)
            val world = MicroWorld(gen)
            val scene = sceneAround(world, x, y, if (fast) 6 else 7)
            val ground = world.surfaceAt(x, y).toFloat()
            val cam = OrthoCamera(x.toFloat(), y.toFloat(), max(ground, SEA.toFloat()), 45f, 36f, if (fast) 300f else 340f, w.toFloat() / h)
            val img = Renderer(scene, Lighting.GOLDEN, if (fast) 1 else 2).render(cam, w, h)
            caption(img, p.name, p.places)
            ImageIO.write(img, "png", File(out, "province-${p.id}.png"))
            log("${p.id}: ${System.currentTimeMillis() - t} ms at $x,$y")
        }
    }

    fun generator(seed: Long, geology: String, vegetation: Boolean = false): MicroGenerator {
        val specs = mutableListOf(StageSpec("micro:terrain", mapOf("geology" to geology, "seaLevel" to "$SEA", "maxHeight" to "$CEILING", "spawnRise" to "0")))
        if (vegetation) {
            specs += StageSpec("micro:features")
            specs += StageSpec("micro:groundcover")
            specs += StageSpec("micro:trees", mapOf("style" to "tropical"))
        }
        return MicroWorldgen.build(seed, specs)
    }

    /** The most varied dry spot near the origin: where the landform shows itself best. */
    private fun viewpoint(gen: MicroGenerator): Pair<Int, Int> {
        val s = gen.fields.require(Fields.SURFACE)
        var best = 0 to 0; var score = -1f
        for (gy in -8..8) for (gx in -8..8) {
            val x = gx * 220; val y = gy * 220
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var wet = 0
            for (k in 0 until 25) {
                val v = s.heightAt(x + (k % 5 - 2) * 60, y + (k / 5 - 2) * 60)
                lo = min(lo, v); hi = max(hi, v); if (v < SEA) wet++
            }
            val sc = (hi - lo) - wet * 6f
            if (sc > score) { score = sc; best = x to y }
        }
        return best
    }

    private fun sceneAround(world: MicroWorld, x: Int, y: Int, radius: Int): VoxelScene {
        val c = MicroChunkPos.containing(x, y, 0)
        val positions = ArrayList<MicroChunkPos>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            for (pz in world.verticalRange(c.x + dx, c.y + dy)) positions += MicroChunkPos(c.x + dx, c.y + dy, pz)
        }
        world.preload(positions)
        return VoxelScene(positions.mapNotNull { world.peek(it) }, world.palette)
    }

    /** A true-colour relief map and a province map of the same ground. */
    private fun maps(gen: MicroGenerator, span: Int, size: Int): Pair<BufferedImage, BufferedImage> {
        val atlas = gen.fields.require(Fields.GEOLOGY) as GeoAtlas
        val palette = gen.palette
        val step = span / size
        val x0 = -span / 2; val y0 = -span / 2
        val heights = FloatArray(size * size)
        val provinces = IntArray(size * size)
        val col = GeoAtlas.Column()
        for (py in 0 until size) for (px in 0 until size) {
            atlas.column(x0 + px * step, y0 + py * step, col)
            heights[py * size + px] = col.height; provinces[py * size + px] = col.province
        }
        val colour = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val map = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val hues = atlas.provinces.indices.map { Color.HSBtoRGB(it / atlas.provinces.size.toFloat(), 0.55f, 0.9f) and 0xFFFFFF }
        for (py in 0 until size) for (px in 0 until size) {
            val i = py * size + px
            val h = heights[i]
            val e = heights[py * size + min(px + 1, size - 1)]; val n = heights[max(py - 1, 0) * size + px]
            val shade = (1f + ((h - e) + (n - h)) * 0.05f).coerceIn(0.55f, 1.35f)
            val p = atlas.resolved[provinces[i]]
            val base = if (h < SEA) 0x2F6FA0 else palette[p.ground].color
            colour.setRGB(px, py, scale(base, if (h < SEA) 1f - (SEA - h) / 120f else shade))
            map.setRGB(px, py, if (h < SEA) 0x1E3A55 else scale(hues[provinces[i]], shade))
        }
        legend(map, atlas.provinces.map { it.name }, hues)
        return colour to map
    }

    private fun legend(img: BufferedImage, names: List<String>, hues: List<Int>) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        val rowH = 17
        g.color = Color(0, 0, 0, 170); g.fillRect(8, 8, 230, names.size * rowH + 12)
        names.forEachIndexed { i, name ->
            g.color = Color(hues[i]); g.fillRect(16, 16 + i * rowH, 12, 12)
            g.color = Color.WHITE; g.drawString(name, 34, 27 + i * rowH)
        }
        g.dispose()
    }

    private fun caption(img: BufferedImage, title: String, places: String) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0, 0, 0, 150); g.fillRect(0, img.height - 46, img.width, 46)
        g.color = Color.WHITE; g.font = Font(Font.SANS_SERIF, Font.BOLD, 17); g.drawString(title, 12, img.height - 26)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12); g.drawString(places.take(150), 12, img.height - 9)
        g.stroke = BasicStroke(1f)
        g.dispose()
    }

    private fun scale(rgb: Int, k: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * k).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun log(s: String) = println("[geoPreview] $s")

    @Suppress("unused") private fun dist(a: Int, b: Int) = sqrt((a * a + b * b).toFloat())
}
