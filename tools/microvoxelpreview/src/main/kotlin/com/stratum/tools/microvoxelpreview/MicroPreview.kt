package com.stratum.tools.microvoxelpreview

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.microvoxel.MicroWorld
import com.stratum.engine.microvoxel.gen.CityPlanStage
import com.stratum.engine.microvoxel.gen.CityRegion
import com.stratum.engine.microvoxel.gen.Fields
import com.stratum.engine.microvoxel.gen.LotUse
import com.stratum.engine.microvoxel.gen.MicroGenerator
import com.stratum.engine.microvoxel.gen.MicroWorldgen
import com.stratum.engine.microvoxel.gen.RoadKind
import com.stratum.engine.microvoxel.mesh.BinaryGreedyMesher
import com.stratum.engine.microvoxel.mesh.Lod
import com.stratum.engine.microvoxel.mesh.QualityProfile
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Renders the microvoxel generators to PNGs.
 *
 *   args: <outDir> [seed] [fast]
 *
 * Each shot finds its own location from the generator's services (an urban
 * region for the city, a high-relief spot for the wilds), so any seed gives
 * a representative set.
 */
object MicroPreview {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "build/microvoxel-preview" }).apply { mkdirs() }
        val seed = args.getOrNull(1)?.toLongOrNull() ?: 20260928L
        val fast = args.contains("fast")
        val w = if (fast) 640 else 1280
        val h = if (fast) 360 else 720
        val ss = if (fast) 1 else 2
        val only = args.drop(2).filter { it != "fast" }.toSet()
        fun want(name: String) = only.isEmpty() || only.any { name.startsWith(it) }
        val t0 = System.currentTimeMillis()

        val cityGen = MicroWorldgen.preset(MicroWorldgen.CITY, seed)
        val region = densestRegion(cityGen)
        val cx = (region.bounds.x0 + region.bounds.x1) / 2
        val cy = (region.bounds.y0 + region.bounds.y1) / 2
        val plateau = region.plateau
        log("seed $seed: downtown region (${region.rx}, ${region.ry}) at ($cx, $cy), plateau ${plateau.toInt()}, ${region.lots.size} lots, ${region.roads.size} roads")

        if (want("01")) {
            val world = MicroWorld(cityGen)
            val scene = sceneAround(world, cx, cy, 11)
            val cam = PerspectiveCamera(cx - 250f, cy - 320f, plateau + 250f, cx + 40f, cy + 40f, plateau, 48f, w.toFloat() / h)
            save(Renderer(scene, Lighting.GOLDEN, ss).render(cam, w, h), out, "01_city_golden_hour.png")
        }
        if (want("02")) {
            val world = MicroWorld(cityGen)
            val street = region.roads.filter { it.kind == RoadKind.STREET }.maxByOrNull { max(it.rect.width, it.rect.depth) }
                ?: region.roads.first()
            val r = street.rect
            val (sx, sy, tx, ty) = if (street.alongX) listOf(r.x0 + 6, (r.y0 + r.y1) / 2 - 5, r.x1, (r.y0 + r.y1) / 2)
            else listOf((r.x0 + r.x1) / 2 - 5, r.y0 + 6, (r.x0 + r.x1) / 2, r.y1)
            val scene = sceneAround(world, (sx + tx) / 2, (sy + ty) / 2, 6)
            val ground = world.surfaceAt(sx, sy)
            val cam = PerspectiveCamera(sx + 0.5f, sy + 0.5f, ground + 8f, tx.toFloat(), ty.toFloat(), ground + 16f, 62f, w.toFloat() / h)
            save(Renderer(scene, Lighting.GOLDEN, ss).render(cam, w, h), out, "02_street_level.png")
        }
        if (want("03")) {
            val wild = MicroWorldgen.preset(MicroWorldgen.WILDS, seed + 7)
            val (vx, vy, top) = scenicSpot(wild)
            val world = MicroWorld(wild)
            val scene = sceneAround(world, vx, vy, 11)
            val sea = wild.fields.require(Fields.SEA_LEVEL)
            val cam = PerspectiveCamera(vx - 330f, vy - 260f, max(top, sea + 60f) + 120f, vx + 30f, vy + 20f, sea + 20f, 50f, w.toFloat() / h)
            save(Renderer(scene, Lighting.DAY, ss).render(cam, w, h), out, "03_wilds_valley.png")
        }
        if (want("04") || want("05")) {
            val world = MicroWorld(cityGen)
            val scene = sceneAround(world, cx, cy, 6)
            val cam = OrthoCamera(cx.toFloat(), cy.toFloat(), plateau + 40f, 45f, 35f, 300f, w.toFloat() / h)
            if (want("04")) save(Renderer(scene, Lighting.DAY, ss).render(cam, w, h), out, "04_city_diorama.png")
            if (want("05")) save(Renderer(scene, Lighting.NIGHT, ss).render(cam, w, h), out, "05_city_night.png")
        }
        if (want("06")) {
            // Editing: dig a crater in an open park (nothing roofs over it) and build a stone watchtower beside it.
            val (ex, ey) = region.lots.filter { it.use == LotUse.PARK }.map { (it.rect.x0 + it.rect.x1) / 2 to (it.rect.y0 + it.rect.y1) / 2 }
                .ifEmpty { region.roads.map { (it.rect.x0 + it.rect.x1) / 2 to (it.rect.y0 + it.rect.y1) / 2 } }
                .minByOrNull { (x, y) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) } ?: (cx to cy)
            val world = MicroWorld(cityGen)
            val scene0 = sceneAround(world, ex, ey, 4)
            val cam = OrthoCamera(ex + 12f, ey - 4f, plateau + 20f, 45f, 38f, 90f, 1f)
            val before = Renderer(scene0, Lighting.GOLDEN, ss).render(cam, h, h)
            val ground = world.surfaceAt(ex, ey)
            val dug = world.sphere(ex, ey, ground, 22f)
            val stone = world.palette.id(M.STONE)
            val lamp = world.palette.id(M.LAMP)
            val tx = ex + 26; val ty = ey - 18
            val tg = world.surfaceAt(tx, ty)
            var built = world.box(tx - 6, ty - 6, tg + 1, tx + 6, ty + 6, tg + 44, stone)
            for (k in -6..6 step 3) {
                built += world.box(tx + k, ty - 6, tg + 45, tx + k, ty - 6, tg + 47, stone) + world.box(tx + k, ty + 6, tg + 45, tx + k, ty + 6, tg + 47, stone)
                built += world.box(tx - 6, ty + k, tg + 45, tx - 6, ty + k, tg + 47, stone) + world.box(tx + 6, ty + k, tg + 45, tx + 6, ty + k, tg + 47, stone)
            }
            built += world.box(tx, ty, tg + 45, tx, ty, tg + 45, lamp)
            val scene1 = sceneAround(world, ex, ey, 4)
            val after = Renderer(scene1, Lighting.GOLDEN, ss).render(cam, h, h)
            val edits = world.exportEdits().values.sumOf { it.size }
            log("edit demo: dug $dug voxels at ground $ground, built $built; save file holds $edits edited voxels")
            save(sideBySide(before, after, "Generated", "After edits: $dug dug, $built placed"), out, "06_edit_before_after.png")
        }
        if (want("07")) {
            val world = MicroWorld(cityGen)
            val scene = sceneAround(world, cx - 200, cy - 200, 4)
            val blocky = VoxelScene(world.loadedChunks.map { upsample(Lod.downsample(it, 4, world.palette), it.pos) }, world.palette)
            val cam = OrthoCamera(cx - 200f, cy - 200f, plateau + 20f, 45f, 35f, 170f, 1f)
            val a = Renderer(scene, Lighting.GOLDEN, ss).render(cam, h, h)
            val b = Renderer(blocky, Lighting.GOLDEN, ss).render(cam, h, h)
            save(sideBySide(a, b, "Microvoxels (1/4 block)", "Same world at block resolution (LOD 4)"), out, "07_micro_vs_block.png")
        }
        if (want("08")) {
            val span = 2048
            save(CityMap.render(cityGen, cx - span / 2, cy - span / 2, span, 1024), out, "08_city_map.png")
            val mixed = MicroWorldgen.preset(MicroWorldgen.MIXED, seed)
            save(CityMap.render(mixed, cx - 4096, cy - 4096, 8192, 1024), out, "09_region_map.png")
        }
        if (want("10")) stats(cityGen, cx, cy, File(out, "10_budget.txt"))
        log("done in ${(System.currentTimeMillis() - t0) / 1000}s -> ${out.absolutePath}")
    }

    /** The most built-up region near the origin, so the city shots always have a city in them. */
    private fun densestRegion(gen: MicroGenerator): CityRegion {
        val city = gen.fields.require(CityPlanStage.KEY)
        var best: CityRegion? = null
        for (ry in -6..6) for (rx in -6..6) {
            val r = city.region(rx, ry)
            if (r.urban && (best == null || r.density > best.density)) best = r
        }
        return best ?: error("No urban region within 6 regions of the origin; try another seed")
    }

    /** A place with mountains and water in view. */
    private fun scenicSpot(gen: MicroGenerator): Triple<Int, Int, Float> {
        val s = gen.fields.require(Fields.SURFACE)
        val sea = gen.fields.require(Fields.SEA_LEVEL)
        var best = Triple(0, 0, 0f); var score = -1f
        for (gy in -12..12) for (gx in -12..12) {
            val x = gx * 300; val y = gy * 300
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (k in 0 until 9) {
                val h = s.heightAt(x + (k % 3 - 1) * 180, y + (k / 3 - 1) * 180)
                lo = minOf(lo, h); hi = maxOf(hi, h)
            }
            val water = if (lo < sea && hi > sea + 40) 150f else 0f
            val sc = (hi - lo) + water
            if (sc > score) { score = sc; best = Triple(x, y, hi) }
        }
        return best
    }

    /** Generates a square of chunk columns, full height, and wraps them for tracing. */
    private fun sceneAround(world: MicroWorld, x: Int, y: Int, radius: Int): VoxelScene {
        val c = MicroChunkPos.containing(x, y, 0)
        val positions = ArrayList<MicroChunkPos>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val px = c.x + dx; val py = c.y + dy
            for (pz in world.verticalRange(px, py)) positions += MicroChunkPos(px, py, pz)
        }
        val t = System.currentTimeMillis()
        world.preload(positions)
        log("generated ${positions.size} chunks in ${System.currentTimeMillis() - t} ms")
        return VoxelScene(positions.mapNotNull { world.peek(it) }, world.palette)
    }

    private fun upsample(grid: com.stratum.engine.microvoxel.mesh.CoarseGrid, pos: MicroChunkPos): MicroChunk {
        val f = MicroChunk.SIZE / grid.size
        val chunk = MicroChunk(pos)
        for (z in 0 until grid.size) for (y in 0 until grid.size) for (x in 0 until grid.size) {
            val m = grid[x, y, z]
            if (m != 0.toShort()) chunk.fill(x * f, y * f, z * f, x * f + f - 1, y * f + f - 1, z * f + f - 1, m)
        }
        chunk.compact()
        return chunk
    }

    /** Numbers behind the low-spec claims: generation time, memory, and quads per LOD. */
    private fun stats(gen: MicroGenerator, x: Int, y: Int, file: File) {
        val world = MicroWorld(gen, QualityProfile.LOW)
        val c = MicroChunkPos.containing(x, y, 0)
        val cols = (-2..2).flatMap { dy -> (-2..2).map { dx -> c.x + dx to c.y + dy } }
        val positions = cols.flatMap { (px, py) -> world.verticalRange(px, py).map { MicroChunkPos(px, py, it) } }
        val t0 = System.nanoTime()
        positions.forEach { world.chunk(it) }
        val genMs = (System.nanoTime() - t0) / 1e6 / positions.size
        val chunks = positions.map { world.chunk(it) }
        val nonEmpty = chunks.filter { !it.isEmpty() }
        val bytes = chunks.sumOf { it.approximateBytes().toLong() }
        val mesher = BinaryGreedyMesher(world.palette)
        val sb = StringBuilder()
        sb.appendLine("Microvoxel budget report (seed ${gen.seed}, downtown, ${cols.size} columns, single thread)")
        sb.appendLine("chunks: ${positions.size} (${nonEmpty.size} non-empty), 64^3 microvoxels = 16^3 blocks each")
        sb.appendLine("generation: %.1f ms per chunk average".format(genMs))
        sb.appendLine("memory: %.1f MB for all chunks (%.1f KB per non-empty chunk; a flat array would be 512 KB)".format(bytes / 1e6, bytes / 1024.0 / max(1, nonEmpty.size)))
        for (f in listOf(1, 2, 4)) {
            var quads = 0L; var area = 0L
            val t = System.nanoTime()
            for (ch in nonEmpty) {
                val grid = if (f == 1) ch else Lod.downsample(ch, f, world.palette)
                val mesh = mesher.mesh(grid)
                quads += mesh.count; area += mesh.area()
            }
            val ms = (System.nanoTime() - t) / 1e6 / max(1, nonEmpty.size)
            sb.appendLine("LOD x$f: %,d quads (%,d faces before merging, %.1fx reduction), %.2f ms mesh per chunk, %.1f MB of 8-byte instances"
                .format(quads, area, area.toDouble() / max(1L, quads), ms, quads * 8 / 1e6))
        }
        file.writeText(sb.toString())
        log(sb.toString())
    }

    private fun sideBySide(a: BufferedImage, b: BufferedImage, la: String, lb: String): BufferedImage {
        val img = BufferedImage(a.width + b.width + 8, max(a.height, b.height), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(24, 24, 28); g.fillRect(0, 0, img.width, img.height)
        g.drawImage(a, 0, 0, null); g.drawImage(b, a.width + 8, 0, null)
        CityMap.label(g, la, 12, 24); CityMap.label(g, lb, a.width + 20, 24)
        g.dispose()
        return img
    }

    private fun save(img: BufferedImage, dir: File, name: String) {
        ImageIO.write(img, "png", File(dir, name))
        log("wrote $name")
    }

    private fun log(s: String) = println("[microPreview] $s")
}
