package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.engine.scene.SceneActor
import com.stratum.engine.scene.SceneBuilder
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.StreamingWorld
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * What placing blocks costs the frames around it, measured the way a player
 * feels it: a microvoxel world at the phone's default tier, frames built in
 * real time at 30 a second, and a wall of 50 blocks laid in quick taps
 * (one every [PLACE_EVERY] frames) straddling a chunk border, then taken back
 * one by one as an undo would.
 *
 * Per frame it records the time to build the scene, the chunks meshed, and
 * how many chunks were drawn from microvoxels. A drop in that last number the
 * frame after an edit is a *pop*: the edited chunk fell back to its textured
 * block mesh while its detail was remade -- a different look, then the old
 * look again a few frames later. That flicker is what reads as jitter.
 *
 * It also renders the frames around one placement, so the pop (or its
 * absence) can be seen, and charts the frame times of every run it has
 * written, so a before and an after run sit on one image.
 *
 *   args: <outDir> <label> [forgeDir]
 */
object PlacementBench {

    private const val FRAME_MS = 1000.0 / 30
    /** Frames between taps: 7.5 blocks a second, a quick tapper or hold-to-repeat. */
    private const val PLACE_EVERY = 4
    private const val PLACEMENTS = 50
    private const val WALL_LENGTH = 12

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "docs/screenshots/build-feel" }).apply { mkdirs() }
        val label = args.getOrElse(1) { "after" }
        val forged = args.getOrNull(2)?.let(::File)?.let { File(it, "house") }?.takeIf { it.isDirectory }
        val rig = Rig(forged)

        // Once to warm the JIT and the microvoxel caches, once to measure.
        rig.run(placements = 20, record = false)
        val frames = rig.run(placements = PLACEMENTS, record = true)
        val csv = File(out, "frames-$label.csv")
        csv.writeText("frame,ms,meshed,detailed,popped,edit\n" + frames.joinToString("\n") { "${it.frame},${"%.3f".format(it.ms)},${it.meshed},${it.detailed},${it.popped},${if (it.edit) 1 else 0}" })
        val report = summary(label, frames) + rig.meshCosts()
        File(out, "numbers-$label.txt").writeText(report)
        println(report)

        rig.sequence(File(out, "sequence-$label.png"), label)
        if (label != "before") rig.feedback(File(out, "feedback.png"))
        chart(out)
    }

    class Sample(val frame: Int, val ms: Double, val meshed: Int, val detailed: Int, val popped: Int, val edit: Boolean)

    /** A world, a camera looking at a patch of it, and a scene builder: what one run edits and draws. */
    private class Rig(forged: File?) {
        val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
            .let { it.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) }
        val config = WorldConfig(seed = 20260928L, simulationRadius = 3)
        val session = com.stratum.engine.world.WorldSession(content, config)
        val generator: MicrovoxelTerrainGenerator = requireNotNull(session.hotTerrain).current
        val director = StyleSheetArtDirector(
            StyleLexicon.interpret("stratum house style", ArtDirection.HOUSE, config.seed).direction,
            BiomeArtKit.deriveAll(IgboContentPack.pack),
        )
        val textures = TextureLibrary().also { lib -> forged?.let { ForgedTextures.loadInto(it, lib) } }
        val block = content.registry.indexOf("igbo:red_earth")

        /** Where the hero stands: open ground a few blocks short of a chunk border, so the wall crosses it. */
        val home: Pair<Int, Int> = run {
            val world = StreamingWorld(content.registry, generator, config)
            world.focusOn(BlockPos(0, 0, 0))
            val x = Math.floorDiv(0, Chunk.SIZE) * Chunk.SIZE + Chunk.SIZE - WALL_LENGTH / 2
            x to 0
        }

        fun world(): StreamingWorld = StreamingWorld(content.registry, generator, config).also { it.focusOn(BlockPos(home.first, home.second, 0)) }

        fun builder() = SceneBuilder(director, textures, biomeAt = { x, y -> generator.biomeAt(x, y) }, settings = RenderSettings.of(QualityTier.MEDIUM), microTerrain = generator)

        fun camera(world: StreamingWorld, distance: Float = 34f): SceneCamera {
            val (x, y) = home
            return SceneCamera(target = Vec3(x + 0.5f, y - 3.5f, world.surfaceAt(x, y - 4) + 1f), aspect = 16f / 9f, distance = distance)
        }

        fun actors(world: StreamingWorld): List<SceneActor> {
            val (x, y) = home
            return listOf(SceneActor(x + 0.5f, y - 3.5f, world.surfaceAt(x, y - 4) + 1f, ActorPresentation("p", ActorRole.PLAYER), 1f, -0.3f, spriteKey = "actor:igbo:dike_ozo"))
        }

        /** The [i]th cell of the wall: [WALL_LENGTH] long along x, rising a course at a time. */
        fun wallCell(world: StreamingWorld, i: Int): BlockPos {
            val x = home.first + i % WALL_LENGTH - WALL_LENGTH / 2
            val y = home.second
            return BlockPos(x, y, world.surfaceAt(x, y) + 1)
        }

        /** What one edit asks of the detail mesher: a whole chunk against the one layer it touched. */
        fun meshCosts(): String {
            val world = world()
            val mesher = com.stratum.engine.scene.MicroDetailMesher(generator)
            val cell = wallCell(world, 0)
            val pos = cell.chunkPos
            val before = com.stratum.engine.scene.BlockSnapshot.of(world, pos)
            val layers = requireNotNull(mesher.meshLayers(before, pos))
            world.setBlock(cell, block)
            val after = com.stratum.engine.scene.BlockSnapshot.of(world, pos)
            val dirty = requireNotNull(after.changedLayers(before, mesher.layers, mesher.blocksPerLayer))
            fun time(n: Int, work: () -> Unit): Double {
                repeat(5) { work() }
                val t = System.nanoTime()
                repeat(n) { work() }
                return (System.nanoTime() - t) / 1e6 / n
            }
            val whole = time(20) { mesher.mesh(after, pos) }
            val patch = time(20) { mesher.meshLayers(after, pos, only = dirty, previous = layers) }
            val scan = time(20) { mesher.meshLayers(after, pos, only = BooleanArray(mesher.layers), previous = layers) }
            val perLayer = (0 until mesher.layers).map { l -> time(10) { mesher.meshLayers(after, pos, only = BooleanArray(mesher.layers) { it == l }, previous = layers) } - scan }
            return ("  detail mesh of the edited chunk: whole %.1f ms, only its %d dirty layer(s) %.1f ms; finding changed blocks %.1f ms, " +
                "each layer %s ms\n").format(whole, dirty.count { it }, patch, scan, perLayer.joinToString(" / ") { "%.1f".format(it) })
        }

        fun revision(world: StreamingWorld) =31 * world.loadedChunks.sumOf { it.revision } + world.residency

        /**
         * Builds frames in real time: a quiet second, [placements] taps laying
         * the wall, a quiet second, the same number of undos, a quiet second.
         */
        fun run(placements: Int, record: Boolean): List<Sample> {
            val world = world()
            val builder = builder()
            val camera = camera(world)
            val actors = actors(world)
            val time = WorldTime(dayFraction = 0.4f, elapsedSeconds = 7f)
            settle(builder) { builder.build(world, camera, actors, time, worldRevision = revision(world)) }
            val placed = ArrayList<BlockPos>()
            val quiet = 30
            val placeStart = quiet
            val undoStart = placeStart + placements * PLACE_EVERY + quiet
            val end = undoStart + placements * PLACE_EVERY + quiet
            val samples = ArrayList<Sample>(end)
            var lastDetailed = -1
            for (f in 0 until end) {
                var edit = false
                if (f >= placeStart && f < undoStart - quiet && (f - placeStart) % PLACE_EVERY == 0) {
                    val cell = wallCell(world, placed.size)
                    if (world.setBlock(cell, block)) { placed += cell; edit = true }
                }
                if (f >= undoStart && f < end - quiet && (f - undoStart) % PLACE_EVERY == 0 && placed.isNotEmpty()) {
                    world.setBlock(placed.removeAt(placed.lastIndex), BlockRegistry.AIR_INDEX)
                    edit = true
                }
                val s = System.nanoTime()
                builder.build(world, camera, actors, time.copy(elapsedSeconds = 7f + f / 30f), worldRevision = revision(world))
                val ms = (System.nanoTime() - s) / 1e6
                val detailed = builder.detailedChunksLastFrame
                val popped = if (lastDetailed >= 0) (lastDetailed - detailed).coerceAtLeast(0) else 0
                lastDetailed = detailed
                samples += Sample(f, ms, builder.chunksMeshedLastFrame, detailed, popped, edit)
                val spare = (FRAME_MS - ms).toLong()
                if (spare > 0) Thread.sleep(spare)
            }
            return if (record) samples else emptyList()
        }

        /**
         * The frames around one tap, drawn: settled, the frame the block goes
         * in, the next few at 30 a second, and settled again.
         */
        fun sequence(file: File, label: String) {
            val world = world()
            val builder = builder()
            val camera = camera(world, distance = 20f)
            val actors = actors(world)
            val start = System.nanoTime()
            // The scene clock follows the wall clock, as the game's does, so a placement's pop plays out in real time.
            fun time() = WorldTime(dayFraction = 0.4f, elapsedSeconds = 7f + (System.nanoTime() - start) / 1e9f)
            // A few courses first, so the tap lands on a wall the eye can find.
            repeat(8) { world.setBlock(wallCell(world, it), block) }
            fun build() = builder.build(world, camera, actors, time(), worldRevision = revision(world))
            val shots = ArrayList<Pair<String, SceneFrame>>()
            shots += "settled, before the tap" to settle(builder, ::build)
            Thread.sleep(1600) // the first eight blocks' pops are over
            shots[0] = shots[0].first to build()
            world.setBlock(wallCell(world, 8), block)
            val t0 = System.nanoTime()
            // The frames are built at 30 a second and kept; drawing them waits until the run is over,
            // so the background meshing gets exactly the time it would on a device.
            for (k in 0 until 4) {
                val wait = (t0 + (k * FRAME_MS * 1e6).toLong() - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                val frame = build()
                shots += "tap +${k} frame${if (k == 1) "" else "s"} (${builder.detailedChunksLastFrame} detailed)" to frame
            }
            shots += "settled after" to settle(builder, ::build)
            val w = 480; val h = 270
            val strip = BufferedImage(w * 3 + 8, (h + 24) * 2 + 34, BufferedImage.TYPE_INT_RGB)
            val g = strip.createGraphics()
            g.color = Color(20, 20, 24); g.fillRect(0, 0, strip.width, strip.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 16); g.color = Color.WHITE
            g.drawString("Placing one block, frame by frame ($label)", 10, 22)
            shots.take(6).forEachIndexed { i, (caption, frame) ->
                val img = SceneRasterizer(w, h, textures).render(frame)
                val x = (i % 3) * (w + 4); val y = 34 + (i / 3) * (h + 24)
                g.drawImage(img, x, y, null)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13); g.color = Color(230, 230, 230)
                g.drawString(caption, x + 6, y + h + 17)
            }
            g.dispose()
            ImageIO.write(strip, "png", file)
            println("wrote ${file.path}")
        }

        /**
         * The placement feedback up close, on a scene clock stepped by hand:
         * a block popping in (with the dust at its foot), then an undo's puff.
         */
        fun feedback(file: File) {
            val world = world()
            val builder = builder()
            // Open, level ground near home, so no roof or wall stands between the camera and the tap.
            val (x, y) = (0..40).asSequence().flatMap { d -> (-d..d).asSequence().map { home.first + it to home.second - 6 - d } }.first { (cx, cy) ->
                val z = world.surfaceAt(cx, cy)
                (-3..3).all { dx -> (-3..3).all { dy -> world.surfaceAt(cx + dx, cy + dy) == z } }
            }
            val focus = BlockPos(x, y, world.surfaceAt(x, y) + 1)
            val camera = SceneCamera(target = Vec3(focus.x + 0.5f, focus.y + 0.5f, focus.z.toFloat()), aspect = 16f / 9f, distance = 12f)
            val actors = listOf(SceneActor(x + 2.5f, y + 1.5f, focus.z.toFloat(), ActorPresentation("p", ActorRole.PLAYER), -1f, -0.3f, spriteKey = "actor:igbo:dike_ozo"))
            var clock = 7f
            fun build() = builder.build(world, camera, actors, WorldTime(dayFraction = 0.4f, elapsedSeconds = clock), worldRevision = revision(world))
            // Two blocks already down beside the one to be tapped, so the new one reads as part of a build.
            world.setBlock(BlockPos(x - 1, y, focus.z), block); world.setBlock(BlockPos(x - 2, y, focus.z), block)
            settle(builder, ::build)
            clock += 2f; build()
            val shots = ArrayList<Pair<String, SceneFrame>>()
            // Each shot waits its moment on the wall clock too, so the edit worker has had just the time it would in game.
            fun shoot(caption: String, base: Float, ms: Int, edited: Long) {
                val wait = ms - (System.nanoTime() - edited) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
                clock = base + ms / 1000f
                shots += caption to build()
            }
            world.setBlock(focus, block)
            val placedAt = System.nanoTime()
            for (ms in listOf(0, 50, 100, 250)) shoot("placed, +$ms ms", 9f, ms, placedAt)
            settle(builder, ::build)
            clock = 11f; build()
            world.setBlock(focus, BlockRegistry.AIR_INDEX)
            val undoneAt = System.nanoTime()
            build() // the frame of the tap, which hands the edit to the worker
            for (ms in listOf(60, 160)) shoot("undone, +$ms ms (dust)", 11f, ms, undoneAt)
            val w = 480; val h = 270
            val strip = BufferedImage(w * 3 + 8, (h + 24) * 2 + 34, BufferedImage.TYPE_INT_RGB)
            val g = strip.createGraphics()
            g.color = Color(20, 20, 24); g.fillRect(0, 0, strip.width, strip.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 16); g.color = Color.WHITE
            g.drawString("Placement feedback: the pop, the dust at its foot, and an undo's puff (at $x,$y)", 10, 22)
            shots.forEachIndexed { i, (caption, frame) ->
                val img = SceneRasterizer(w, h, textures).render(frame)
                val px = (i % 3) * (w + 4); val py = 34 + (i / 3) * (h + 24)
                g.drawImage(img, px, py, null)
                g.font = Font(Font.SANS_SERIF, Font.PLAIN, 13); g.color = Color(230, 230, 230)
                g.drawString(caption, px + 6, py + h + 17)
            }
            g.dispose()
            ImageIO.write(strip, "png", file)
            println("wrote ${file.path}")
        }

        private fun settle(builder: SceneBuilder, build: () -> SceneFrame): SceneFrame {
            var frame = build()
            var waited = 0
            while (builder.detailPending > 0 && waited < 30_000) { Thread.sleep(5); waited += 5; frame = build() }
            return build()
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    /** Worst, 95th percentile and mean frame over the frames that edit and the few after each, plus the pops. */
    fun summary(label: String, frames: List<Sample>): String {
        // The edit frame and the three after it: where meshing, uploading and any pop land.
        val edits = frames.filter { it.edit }.map { it.frame }.toSet()
        val around = frames.filter { s -> (0..3).any { (s.frame - it) in edits } }
        val quiet = frames.filterNot { s -> (0..3).any { (s.frame - it) in edits } }
        val ms = around.map { it.ms }
        return buildString {
            appendLine("Placement bench ($label): ${edits.size} edits (50 placed, then 50 undone), one every $PLACE_EVERY frames at 30 fps, MEDIUM tier, ${Runtime.getRuntime().availableProcessors()} cores")
            appendLine("  frames around edits: worst %.1f ms, p95 %.1f ms, mean %.2f ms (%d frames)".format(ms.maxOrNull() ?: 0.0, percentile(ms, 0.95), ms.average(), ms.size))
            appendLine("  quiet frames:        worst %.1f ms, p95 %.1f ms, mean %.2f ms".format(quiet.maxOfOrNull { it.ms } ?: 0.0, percentile(quiet.map { it.ms }, 0.95), quiet.map { it.ms }.average()))
            appendLine("  chunks block-meshed on the frame: ${frames.sumOf { it.meshed }}")
            appendLine("  detail pops (a detailed chunk drawn as blocks again): ${frames.sumOf { it.popped }} over ${frames.count { it.popped > 0 }} frames")
        }
    }

    /** Frame times of every run written to [out], one line each, the edits marked underneath. */
    fun chart(out: File) {
        val runs = listOf("before", "after").mapNotNull { label ->
            File(out, "frames-$label.csv").takeIf { it.isFile }?.let { f ->
                label to f.readLines().drop(1).map { line -> line.split(',').let { Sample(it[0].toInt(), it[1].toDouble(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5] == "1") } }
            }
        }
        if (runs.isEmpty()) return
        val w = 1100; val h = 260 * runs.size + 40
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 248); g.fillRect(0, 0, w, h)
        val maxMs = maxOf(40.0, runs.maxOf { r -> r.second.maxOf { it.ms } } * 1.1)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15); g.color = Color(30, 30, 30)
        g.drawString("Scene build time per frame while placing 50 blocks, then undoing them (30 fps budget = 33 ms)", 14, 24)
        runs.forEachIndexed { r, (label, frames) ->
            val top = 40 + r * 260; val left = 60; val plotW = w - left - 20; val plotH = 190
            val n = frames.size
            fun x(i: Int) = left + i * plotW / (n - 1).coerceAtLeast(1)
            fun y(ms: Double) = top + plotH - (ms / maxMs * plotH).toInt()
            g.color = Color(225, 225, 222)
            for (t in 0..maxMs.toInt() step 10) { g.drawLine(left, y(t.toDouble()), left + plotW, y(t.toDouble())) }
            g.color = Color(120, 120, 120); g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            for (t in 0..maxMs.toInt() step 10) g.drawString("$t ms", 14, y(t.toDouble()) + 4)
            g.color = Color(210, 80, 60, 110); g.stroke = BasicStroke(1f)
            g.drawLine(left, y(33.3), left + plotW, y(33.3))
            frames.forEach { s ->
                if (s.edit) { g.color = Color(80, 80, 80, 90); g.drawLine(x(s.frame), top + plotH + 2, x(s.frame), top + plotH + 8) }
                if (s.popped > 0) { g.color = Color(214, 40, 40); g.fillOval(x(s.frame) - 3, y(s.ms) - 3, 7, 7) }
            }
            g.color = if (label == "before") Color(196, 90, 30) else Color(30, 110, 190)
            g.stroke = BasicStroke(1.6f)
            for (i in 1 until n) g.drawLine(x(i - 1), y(frames[i - 1].ms), x(i), y(frames[i].ms))
            val ms = frames.filter { s -> frames.any { it.edit && s.frame - it.frame in 0..3 } }.map { it.ms }
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 13); g.color = Color(30, 30, 30)
            g.drawString(
                "$label: worst %.1f ms, p95 %.1f ms around edits; %d detail pops (red dots)".format(ms.maxOrNull() ?: 0.0, percentile(ms, 0.95), frames.sumOf { it.popped }),
                left, top + plotH + 26,
            )
        }
        g.dispose()
        ImageIO.write(img, "png", File(out, "frame-times.png"))
        println("wrote ${File(out, "frame-times.png").path}")
    }
}
