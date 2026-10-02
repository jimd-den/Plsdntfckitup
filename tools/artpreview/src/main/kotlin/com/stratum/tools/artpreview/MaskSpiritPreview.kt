package com.stratum.tools.artpreview

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.BiomeArtKit
import com.stratum.core.domain.art.SceneLighting
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.engine.model.mask.MaskGenome
import com.stratum.engine.model.mask.MaskPalettes
import com.stratum.engine.model.mask.MaskSpiritMesher
import com.stratum.engine.scene.MaterialKind
import com.stratum.engine.scene.MeshBuilder
import com.stratum.engine.scene.MeshRecycler
import com.stratum.engine.scene.PointLight
import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.SceneFrame
import com.stratum.engine.scene.SpiritInstance
import com.stratum.engine.scene.SpiritMesh
import com.stratum.engine.scene.SpiritStage
import com.stratum.engine.scene.TextureLibrary
import com.stratum.engine.scene.Vec3
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.stratum.engine.model.mask.AfricanMaskArt.Nudge
import com.stratum.engine.model.mask.AfricanMaskArt.Part

/**
 * Mask spirits through the game's own scene pipeline: turntables of every
 * preset, frame strips of their motion, and the hero with a companion
 * mid-fight. Writes into the folder given (the repo keeps them in
 * `docs/screenshots/mask-spirits`).
 */
object MaskSpiritPreview {

    internal val director by lazy { StyleSheetArtDirector(ArtDirection.HOUSE, BiomeArtKit.deriveAll(IgboContentPack.pack)) }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "build/mask-spirits").also { it.mkdirs() }
        val only = args.getOrNull(1)
        if (only == null || only == "all" || only == "turntable") turntables(out)
        if (only == null || only == "all" || only == "strips") MaskSpiritShots.strips(out)
        if (only == null || only == "all" || only == "world") MaskSpiritShots.fight(out)
        if (only == null || only == "all" || only == "emoji") emoji(out)
        if (only == null || only == "all" || only == "stickers") stickers(out)
        if (only == null || only == "all" || only == "builder") builder(out)
        if (only == null || only == "all" || only == "vector") vectorMasks(out)
        if (only == null || only == "all" || only == "emojimask") emojiMasks(out)
        if (only == null || only == "all" || only == "shapemask") shapeMasks(out)
        if (only == "sculpt") sculptedMasks(out)
        if (only == "sculpt-stills") sculptedMasks(out, animations = false, quick = true)
        if (only == "carver") carver(out)
        if (only == "wood") wood(out)
        // spirits, spirits-quick, or one section: spirits-quick:fractures
        if (only != null && only.startsWith("spirits")) SpiritMaskShots.all(out, quick = only.startsWith("spirits-quick"), only = only.substringAfter(':', "").ifEmpty { null })
    }

    private fun turntables(out: File) {
        val cards = ArrayList<BufferedImage>()
        MaskGenome.presets.forEachIndexed { i, g ->
            val started = System.currentTimeMillis()
            val mesh = MaskSpiritMesher.build(g)
            val built = System.currentTimeMillis() - started
            val views = listOf(-0.9f, -0.45f, 0f, 0.45f, 0.9f).map { yaw ->
                studio(mesh, 300, 360) { it.yaw = yaw; it.glow = 0.55f; it.eyes = 0.2f }
            }
            val strip = strip(views, "${g.name} · ${MaskPalettes[g.palette].name} · ${mesh.triangleCount} triangles")
            ImageIO.write(strip, "png", File(out, "turntable-%02d-%s.png".format(i + 1, slug(g.name))))
            cards += views[1]
            var front = 0; var back = 0
            for (t in 0 until mesh.triangleCount) {
                val a = mesh.indices[t * 3]
                val ny = mesh.normals[a * 3 + 1]
                if (ny > 0.3f) front++ else if (ny < -0.3f) back++
            }
            println("${g.name}: ${mesh.triangleCount} tris (front $front, back $back, walls ${mesh.triangleCount - front - back}), ${mesh.vertexCount} verts, built in ${built}ms")
        }
        ImageIO.write(sheet(cards, 5), "png", File(out, "presets-sheet.png"))
    }

    /**
     * Igbo masks as emoji: every preset's bare 3D head with each expression
     * drawn on, a sheet per mask and one sheet of all of them feeling the
     * same things, plus a turn of one expression to show it rides the face.
     */
    private fun emoji(out: File) {
        val feelings = listOf("Calm", "Smile", "Wink", "Laugh", "Cheeky", "Love", "Star", "Surprise", "Anger", "Hurt", "Sad", "Sleep")
        val grid = ArrayList<BufferedImage>()
        val pick = listOf("Calm", "Smile", "Wink", "Laugh", "Love", "Surprise")
        MaskGenome.presets.forEachIndexed { i, g ->
            val mesh = MaskSpiritMesher.build(g, emoji = true)
            val face = mesh.face ?: error("${g.name} has no emoji face")
            val t0 = System.nanoTime()
            val views = feelings.map { f ->
                val features = com.stratum.engine.model.mask.EmojiFace.build(g, face, com.stratum.engine.model.mask.EmojiFace.presets.getValue(f))
                f to studio(mesh, 240, 290, features, sunny = true) { it.yaw = 0.22f; it.glow = 0.1f; it.eyes = 0f }
            }
            val ms = (System.nanoTime() - t0) / 1e6 / feelings.size
            ImageIO.write(sheet(views.map { (f, img) -> caption(img, f) }, 6), "png", File(out, "emoji-%02d-%s.png".format(i + 1, slug(g.name))))
            views.filter { it.first in pick }.forEach { grid += caption(it.second, "${g.name} · ${it.first}") }
            println("${g.name}: emoji head ${mesh.triangleCount} tris, a face drawn in ${"%.1f".format(ms)} ms incl. render")
        }
        ImageIO.write(sheet(grid, pick.size), "png", File(out, "emoji-sheet.png"))
        // One feeling, turned: the drawn face rides the 3D head.
        val g = MaskGenome.presets[1]
        val mesh = MaskSpiritMesher.build(g, emoji = true)
        val features = com.stratum.engine.model.mask.EmojiFace.build(g, mesh.face!!, com.stratum.engine.model.mask.EmojiFace.presets.getValue("Wink"))
        val turn = listOf(-1.0f, -0.5f, 0f, 0.5f, 1.0f).map { yaw -> studio(mesh, 260, 320, features, sunny = true) { it.yaw = yaw; it.glow = 0.1f; it.eyes = 0f } }
        ImageIO.write(strip(turn, "${g.name} · Wink, turning: a drawn face on a 3D mask"), "png", File(out, "emoji-turn.png"))
    }

    /**
     * African mask art as vector layers hung on a plain oval: a sheet of
     * generated masks, one turning, and one animating (raffia swaying, crown
     * bobbing, a blink).
     */
    /**
     * Igbo masks as emoji: the carvers' masks smiling, one mask through
     * twenty feelings, another through the same, a turn, and fresh rolls.
     */
    // ---- sculpted masks -----------------------------------------------------------------------

    /**
     * The sculpted masks: every tradition carved and seen from the game's
     * isometric camera, close portraits, a turn, fresh carvings, a war party
     * on the ground, and the masks alive -- hovering, striking, spinning,
     * casting, reeling and falling -- driven by the game's own motion.
     */
    private fun sculptedMasks(out: File, animations: Boolean = true, quick: Boolean = false) {
        val culture = com.stratum.engine.model.mask.sculpt.MaskCulture
        val sculptor = com.stratum.engine.model.mask.sculpt.MaskSculptor
        val detail = if (quick) com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.HIGH else com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.SHOWCASE
        val masks = culture.traditions.map { tr ->
            val spec = culture.generate(tr, 11L)
            val t0 = System.nanoTime()
            val mesh = sculptor.carve(spec, detail)
            println("${tr.name} (${tr.people}): ${mesh.triangleCount} triangles, carved in ${(System.nanoTime() - t0) / 1_000_000} ms")
            Triple(tr, spec, mesh)
        }
        // The gallery, from the game's camera.
        val cards = masks.map { (tr, _, mesh) -> val (tz, dist) = framing(mesh, 4.4f); labelled(isoScene(listOf(single(mesh, 0f, 0f, 0.35f)), 320, 380, distance = dist, target = Vec3(0f, 0f, tz)), "${tr.name} · ${tr.people}") }
        ImageIO.write(sheet(cards, 4), "png", File(out, "sculpt-traditions.png"))
        // Close portraits, three-quarter, low.
        val portraits = masks.take(8).map { (tr, _, mesh) -> val (tz, dist) = framing(mesh, 3.2f); labelled(isoScene(listOf(single(mesh, 0f, 0f, 0.5f)), 360, 440, distance = dist, pitch = 14f, target = Vec3(0f, 0f, tz)), tr.name) }
        ImageIO.write(sheet(portraits, 4), "png", File(out, "sculpt-portraits.png"))
        // One mask turning.
        val hero = masks.first { it.first.id == "mgbedike" }.third
        val turn = listOf(-1.2f, -0.6f, 0f, 0.6f, 1.2f, 2.2f).map { yaw -> isoScene(listOf(single(hero, 0f, 0f, yaw)), 280, 340, distance = 3.9f, pitch = 20f) }
        ImageIO.write(strip(turn, "Mgbedike turning: carved horns, tube eyes lit from inside, bared teeth, raffia"), "png", File(out, "sculpt-turn.png"))
        // Fresh carvings: each tradition's grammar rolled again, and two mixed.
        val rolls = (0 until 12).map { i ->
            val tr = culture.traditions[(i * 5) % culture.traditions.size]
            val spec = if (i % 4 == 3) culture.blend(tr, culture.traditions[(i * 3 + 1) % culture.traditions.size], 100L + i) else culture.generate(tr, 100L + i)
            val mesh = sculptor.carve(spec, com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.HIGH)
            val (tz, dist) = framing(mesh, 4.4f)
            labelled(isoScene(listOf(single(mesh, 0f, 0f, 0.4f)), 300, 360, distance = dist, target = Vec3(0f, 0f, tz)), "${spec.name} #${i + 1}")
        }
        ImageIO.write(sheet(rolls, 4), "png", File(out, "sculpt-rolls.png"))
        // A war party on the ground, as the game's camera sees a fight.
        val party = masks.filter { it.first.id in setOf("mgbedike", "agbogho_mmuo", "ikenga", "ijele", "okoroshi", "ogbodo_enyi", "songye", "bwa") }
        val spots = listOf(0f to 0f, -2.2f to 1.2f, 2.1f to 1.0f, -1.1f to 3.0f, 1.3f to 3.2f, -3.3f to -1.2f, 3.4f to -1.0f, 0.2f to -2.6f)
        val scene = party.mapIndexed { i, (_, _, mesh) -> single(mesh, spots[i].first, spots[i].second, 0.3f + i * 0.2f) }
        ImageIO.write(labelled(isoScene(scene, 1280, 800, distance = 16f, target = Vec3(0f, 0.5f, 1f)), "A war party of spirits, from the game's camera"), "png", File(out, "sculpt-party.png"))
        // Alive.
        if (animations) animate(out, masks)
    }

    private var TILT = 0.35f

    /** Where to aim and how far back to stand so the whole mask, crown and all, fills the card. */
    private fun framing(mesh: SpiritMesh, base: Float): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) { val z = mesh.positions[i * 3 + 2]; lo = kotlin.math.min(lo, z); hi = kotlin.math.max(hi, z) }
        val k = 1.5f
        val mid = 1.1f + (lo + hi) / 2f * k
        val tall = (hi - lo) * k
        return mid to (base * kotlin.math.max(1f, tall / 1.2f))
    }

    /** One mask standing at (x, y) facing [yaw], hovering a block up. */
    private fun single(mesh: SpiritMesh, x: Float, y: Float, yaw: Float): SpiritInstance = SpiritInstance(mesh).also {
        it.pose.x = x; it.pose.y = y; it.pose.z = 1.1f; it.pose.yaw = yaw; it.pose.scale = 1.5f; it.pose.glow = 0.35f; it.pose.eyes = 0.3f; it.pose.lines = 0.2f
        // Tipped back toward the camera above, as a floating mask presents its face in an isometric view.
        it.pose.pitch = TILT
    }

    /**
     * Masks over a stretch of dark earth, from above at the game's own
     * elevation (or [pitch]), lit by a low sun with shadows.
     */
    internal fun isoScene(
        spirits: List<SpiritInstance>, width: Int, height: Int, distance: Float, pitch: Float = com.stratum.core.domain.ai.IsometricCamera.SCENE_ELEVATION_DEGREES.toFloat(),
        target: Vec3 = Vec3(0f, 0f, 1.15f),
    ): BufferedImage {
        val camera = SceneCamera(target = target, pitch = pitch, yaw = 270f, distance = distance, fovY = 30f, aspect = width.toFloat() / height, near = 0.5f, far = 60f)
        val base = director.lightingFor(null, WorldTime(dayFraction = 0.36f))
        val sx = 0.5f; val sy = 0.35f; val sz = 0.8f
        val l = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        val lighting = base.copy(
            sunX = sx / l, sunY = sy / l, sunZ = sz / l, fogStart = 100f, fogEnd = 200f, fogFloor = -100f,
            skyTop = 0xFF1A1512, skyBottom = 0xFF2A211B, vignette = 0.35f, shadowStrength = 0.7f,
            sunIntensity = base.sunIntensity * 1.35f, rimStrength = base.rimStrength * 0.6f,
        )
        val solid = MeshBuilder(MaterialKind.OPAQUE); val fading = MeshBuilder(MaterialKind.CUTOUT); val glows = MeshBuilder(MaterialKind.GLOW)
        // The ground: packed laterite, darker toward the edge.
        val n = 24; val span = 14f
        val grid = Array(n + 1) { i -> IntArray(n + 1) { j ->
            val x = -span + 2 * span * i / n; val y = -span + 2 * span * j / n
            val fall = (1f - kotlin.math.sqrt(x * x + y * y) / (span * 1.1f)).coerceIn(0f, 1f)
            val grain = ((kotlin.math.sin(x * 3.1f) * kotlin.math.cos(y * 2.7f) + 1f) * 0.04f)
            val r = (34 + 38 * fall + 160 * grain).toInt().coerceIn(0, 255); val g = (24 + 22 * fall + 110 * grain).toInt().coerceIn(0, 255); val b = (20 + 12 * fall).toInt()
            solid.vertex(x, y, 0f, 0f, 0f, 1f, ((r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()), 1f, 0f, 0f, com.stratum.engine.scene.Vertex.ACTOR)
        } }
        for (i in 0 until n) for (j in 0 until n) solid.quad(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
        val lights = ArrayList<PointLight>()
        SpiritStage(sticker = false).draw(spirits, camera, solid, fading, glows, lights)
        val recycler = MeshRecycler()
        val frame = SceneFrame(
            camera = camera, lighting = lighting, lights = lights,
            shadowViewProjection = sunMatrix(lighting, target, distance * 0.6f + 2f),
            terrain = emptyList(), actors = solid.build(recycler), cutout = fading.build(recycler),
            decals = MeshBuilder(MaterialKind.DECAL).build(recycler), glows = glows.build(recycler),
        )
        return SceneRasterizer(width, height, TextureLibrary(), supersample = 3, shadowSize = 2048).render(frame)
    }

    /** Close portraits of the traditions, to judge the carving itself: in play's detail and the finest. */
    private fun wood(out: File) {
        val cu = com.stratum.engine.model.mask.sculpt.MaskCulture
        val sculptor = com.stratum.engine.model.mask.sculpt.MaskSculptor
        for ((name, detail) in listOf("game" to com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.GAME, "high" to com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.HIGH)) {
            val cards = listOf("agbogho_mmuo", "mgbedike", "okoroshi", "ikenga", "punu", "dan", "songye", "chokwe").map { id ->
                val t = cu.tradition(id)
                val t0 = System.nanoTime()
                val mesh = sculptor.carve(cu.generate(t, 1L), detail)
                println("  ${t.name} ($name): ${mesh.triangleCount} triangles in ${(System.nanoTime() - t0) / 1_000_000} ms")
                val px = com.stratum.engine.model.mask.sculpt.MaskPortrait.render(mesh, 330, 400, yaw = 0.45f)
                val img = BufferedImage(330, 400, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, 330, 400, px, 0, 330) }
                labelled(img, t.name)
            }
            ImageIO.write(sheet(cards, 4), "png", File(out, "wood-$name.png"))
        }
        // Close-ups at the finest detail, turned to catch the light across the cuts.
        val close = listOf("okoroshi" to 0.5f, "chokwe" to -0.6f, "okoroshi" to 1.35f).mapNotNull { (id, yaw) ->
            cu.traditions.firstOrNull { it.id == id || it.name.lowercase().replace(' ', '_') == id }?.let { t ->
                val mesh = sculptor.carve(cu.generate(t, 1L), com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.SHOWCASE)
                val px = com.stratum.engine.model.mask.sculpt.MaskPortrait.render(mesh, 640, 760, yaw = yaw, pitch = 0.1f)
                labelled(BufferedImage(640, 760, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, 640, 760, px, 0, 640) }, "${t.name}, close")
            }
        }
        if (close.isNotEmpty()) ImageIO.write(sheet(close, close.size), "png", File(out, "wood-close.png"))
    }

    /** Every dial of the carver at both ends, and a wall of random designs. */
    private fun carver(out: File) {
        val mc = com.stratum.engine.model.mask.sculpt.MaskCarver
        val sculptor = com.stratum.engine.model.mask.sculpt.MaskSculptor
        val portrait = com.stratum.engine.model.mask.sculpt.MaskPortrait
        fun pic(spec: com.stratum.engine.model.mask.sculpt.MaskSpec, label: String, size: Int = 220, detail: com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail = com.stratum.engine.model.mask.sculpt.MaskSculptor.Detail.GAME, yaw: Float = 0.35f): BufferedImage {
            val mesh = sculptor.carve(spec, detail)
            val px = portrait.render(mesh, size, (size * 1.2f).toInt(), yaw = yaw)
            val img = BufferedImage(size, (size * 1.2f).toInt(), BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, img.width, img.height, px, 0, img.width)
            return labelled(img, label)
        }
        println("Designs from named choices alone: ${mc.designs()}  (with every slider step: ${mc.designs(sliders = true).toString().length} digits)")
        // Each dial: low, as carved, high, on masks that show it.
        val showcase = mapOf(
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.HORNS to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("ikenga"), 3L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.HAIR to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("punu"), 3L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.RAFFIA to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("mgbedike"), 3L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.MARKS to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("kuba"), 5L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.EYES to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("dan"), 4L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.BROW_EARS to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("ogbodo_enyi"), 3L),
            com.stratum.engine.model.mask.sculpt.Anatomy.Part.SURFACE to com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("mgbedike"), 8L),
        )
        // Dials of depth read best from the side.
        val sideways = setOf("FACE_DEPTH", "CONVEXITY", "NOSE_BRIDGE", "LIP_FULLNESS", "EYE_DEPTH")
        val base = com.stratum.engine.model.mask.sculpt.MaskCulture.generate(com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("okoroshi"), 2L).copy(dials = emptyMap())
        val rows = com.stratum.engine.model.mask.sculpt.Anatomy.Dial.entries.map { d ->
            val spec = (showcase[d.part] ?: base).copy(dials = emptyMap())
            listOf(0f, d.default, 1f).map { v -> pic(spec.withDial(d, v), "${d.label} ${"%.1f".format(v)}", 180, yaw = if (d.name in sideways) 1.05f else 0.35f) }
        }
        rows.chunked(11).forEachIndexed { i, chunk -> ImageIO.write(sheet(chunk.flatten(), 6), "png", File(out, "carver-dials-${i + 1}.png")) }
        val wall = (1..30).map { k -> val spec = mc.roll(k * 7717L); pic(spec, spec.name, 200) }
        ImageIO.write(sheet(wall, 6), "png", File(out, "carver-designs.png"))
        // One mask, part by part carved afresh.
        val hero = mc.roll(99L, com.stratum.engine.model.mask.sculpt.MaskCulture.tradition("agbogho_mmuo"))
        val parts = listOf(hero to "As rolled") + com.stratum.engine.model.mask.sculpt.Anatomy.Part.entries.map { p -> mc.reroll(hero, p, 4242L + p.ordinal) to "New ${p.label.lowercase()}" }
        ImageIO.write(sheet(parts.map { (s, l) -> pic(s, l, 200) }, 6), "png", File(out, "carver-parts.png"))
        val code = mc.encode(hero)
        println("Share code (${code.length} chars): $code")
    }

    internal fun labelled(img: BufferedImage, label: String): BufferedImage {
        val out = BufferedImage(img.width, img.height + 28, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(0x16, 0x12, 0x10); g.fillRect(0, 0, out.width, out.height)
        g.drawImage(img, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xE8, 0xDC, 0xC8); g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        g.drawString(label, 10, img.height + 19)
        g.dispose()
        return out
    }

    /**
     * The masks alive, through the game's own motion: each clip runs a mask's
     * body through the motion bank at 30 frames a second and takes frames.
     */
    private fun animate(out: File, masks: List<Triple<com.stratum.engine.model.mask.sculpt.Tradition, com.stratum.engine.model.mask.sculpt.MaskSpec, SpiritMesh>>) {
        fun meshOf(id: String) = masks.first { it.first.id == id }
        class Clip(val id: String, val title: String, val length: Float, val frames: Int, val act: (com.stratum.core.domain.motion.MotionBody, Float, Float) -> Unit)
        val clips = listOf(
            Clip("agbogho_mmuo", "Agbogho Mmuo at rest: hovering, breathing, the eyes' light pulsing", 3.2f, 7) { _, _, _ -> },
            Clip("mgbedike", "Mgbedike strikes: wind-up, lunge, recoil, raffia whipping", 1.2f, 7) { b, t, p -> if (p < 0.4f && t >= 0.4f) b.strike(1.2f, com.stratum.core.domain.motion.StrikeStyle.LUNGE) },
            Clip("okoroshi", "Okoroshi whirls: a spinning strike, fringe flying out", 1.4f, 7) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.strike(1f, com.stratum.core.domain.motion.StrikeStyle.SPIN) },
            Clip("ikenga", "Ikenga butts: rears back and drives the horns down", 1.3f, 7) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.strike(1.2f, com.stratum.core.domain.motion.StrikeStyle.HEADBUTT) },
            Clip("ijele", "Ijele calls on its power: rises and flares", 1.6f, 7) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.cast() },
            Clip("ogbodo_enyi", "Ogbodo Enyi is struck: it reels and shudders", 1.2f, 7) { b, t, p -> if (p < 0.25f && t >= 0.25f) { b.hit(2f, 0f, 1.4f); b.crit() } },
            Clip("songye", "Kifwebe falls: struck down, it drops and fades", 1.6f, 7) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.die() },
        )
        for ((c, clip) in clips.withIndex()) {
            val (tr, _, mesh) = meshOf(clip.id)
            val profile = com.stratum.core.domain.motion.MotionProfiles.resolve(tr.motion)
            val cast = com.stratum.engine.scene.MaskCast()
            val dt = 1f / 30f
            val shots = ArrayList<BufferedImage>()
            val every = (clip.length / dt / clip.frames).toInt().coerceAtLeast(1)
            var t = 0f; var prev = -1f; var step = 0
            // Settle first, so the hover and fringe are in their stride.
            repeat(45) { cast.begin(); cast.track("m", mesh, profile, 0f, 0f, 0f, 0.34f, 0.94f, spawning = false); cast.advance(dt) }
            while (t < clip.length) {
                cast.begin()
                val body = cast.track("m", mesh, profile, 0f, 0f, 0f, 0.34f, 0.94f, spawning = false)
                if (body != null) { body.aim(1.1f, 3f, 1f); clip.act(body, t, prev) }
                cast.advance(dt)
                // Tipped toward the camera above, as the stills are.
                cast.spirits.forEach { it.pose.pitch += TILT }
                if (step % every == 0 && shots.size < clip.frames) shots += isoScene(cast.spirits.toList(), 240, 300, distance = 5.4f, target = Vec3(0f, 0f, 1.25f))
                prev = t; t += dt; step++
            }
            ImageIO.write(strip(shots, clip.title), "png", File(out, "sculpt-anim-${c + 1}-${clip.id}.png"))
        }
    }

    /** Masks built from flat shapes: the set, how one is built layer by layer, its feelings, a turn, rolls. */
    private fun shapeMasks(out: File) {
        val sm = com.stratum.engine.model.mask.ShapeMask
        val em = com.stratum.engine.model.mask.EmojiMask
        fun card(px: IntArray, size: Int, label: String): BufferedImage {
            val img = BufferedImage(size, size + 30, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = Color(0xFA, 0xF7, 0xF1); g.fillRect(0, 0, size, size + 30)
            g.drawImage(BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, size, size, px, 0, size) }, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x3A, 0x30, 0x2C); g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            g.drawString(label, (size - g.fontMetrics.stringWidth(label)) / 2, size + 20)
            g.dispose()
            return img
        }
        val smile = em.expressions.getValue("Smile")
        ImageIO.write(sheet(sm.presets.map { card(sm.image(it, smile, 280, 0.12f, 0.4f), 280, it.name) }, 4), "png", File(out, "shape-masks.png"))
        val L = com.stratum.engine.model.mask.ShapeMask.Layer.entries
        for ((i, look) in listOf(sm.presets[3], sm.presets[1]).withIndex()) {
            val steps = listOf("The shapes behind" to 1, "+ the face" to 2, "+ its fields" to 3, "+ plates and crest" to 4, "+ eyes, nose, mouth" to 5, "+ what hangs" to 6)
            val built = steps.map { (label, n) -> card(sm.image(look, smile, 240, 0.15f, 0.4f, L.take(n).toSet()), 240, label) }
            ImageIO.write(sheet(built, 6), "png", File(out, "shape-built-${i + 1}.png"))
        }
        for ((i, look) in listOf(sm.presets[0], sm.presets[3]).withIndex()) {
            ImageIO.write(sheet(em.expressions.map { (name, face) -> card(sm.image(look, face, 240, 0.1f, 0.4f), 240, name) }, 5), "png", File(out, "shape-expressions-${i + 1}.png"))
        }
        val turn = listOf(-0.7f, -0.4f, -0.15f, 0f, 0.15f, 0.4f, 0.7f).map { card(sm.image(sm.presets[4], em.expressions.getValue("Grin"), 240, it, 0.4f), 240, "") }
        ImageIO.write(strip(turn, "Horned Moon turning: every shape slides on a shallow dome, like a paper puppet"), "png", File(out, "shape-turn.png"))
        val feelings = em.expressions.keys.toList()
        val rolls = (1..12).map { k -> val look = sm.generate(k * 7919L, "Roll $k"); val fe = feelings[(k * 7) % feelings.size]; card(sm.image(look, em.expressions.getValue(fe), 280, 0.12f, 0.4f), 280, "${look.base.label} · $fe") }
        ImageIO.write(sheet(rolls, 6), "png", File(out, "shape-rolls.png"))
    }

    private fun emojiMasks(out: File) {
        val em = com.stratum.engine.model.mask.EmojiMask
        fun card(look: com.stratum.engine.model.mask.EmojiMask.Look, face: com.stratum.engine.model.mask.EmojiMask.Face, label: String, size: Int = 280, turn: Float = 0.12f, time: Float = 0.4f): BufferedImage {
            val t0 = System.nanoTime()
            val px = em.image(look, face, size, turn, time)
            val ms = (System.nanoTime() - t0) / 1e6
            if (label.isNotEmpty()) println("  $label drawn in ${"%.0f".format(ms)} ms")
            val img = BufferedImage(size, size + 30, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.paint = java.awt.GradientPaint(0f, 0f, Color(0x24, 0x1C, 0x19), 0f, size + 30f, Color(0x0C, 0x09, 0x08)); g.fillRect(0, 0, size, size + 30)
            val art = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, size, size, px, 0, size) }
            g.drawImage(art, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0xE8, 0xDC, 0xC8); g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
            g.drawString(label, (size - g.fontMetrics.stringWidth(label)) / 2, size + 20)
            g.dispose()
            return img
        }
        val smile = em.expressions.getValue("Smile")
        ImageIO.write(sheet(em.presets.map { card(it, smile, it.name) }, 6), "png", File(out, "emoji-masks.png"))
        for ((i, look) in listOf(em.presets[0], em.presets[2]).withIndex()) {
            val cards = em.expressions.map { (name, face) -> card(look, face, em.igboNames[name]?.let { "$it · $name" } ?: name, 240) }
            ImageIO.write(sheet(cards, 5), "png", File(out, "emoji-expressions-${i + 1}.png"))
        }
        val turn = listOf(-0.75f, -0.45f, -0.2f, 0f, 0.2f, 0.45f, 0.75f).map { card(em.presets[0], em.expressions.getValue("Grin"), "", 240, it) }
        ImageIO.write(strip(turn, "${em.presets[0].name} turning: the face wraps the head, the nose and crest stand off it"), "png", File(out, "emoji-turn.png"))
        val a = em.expressions.getValue("Serene"); val b = em.expressions.getValue("Laugh"); val c = em.expressions.getValue("Love")
        val frames = (0..7).map { k -> val x = k / 7f; val face = if (x < 0.5f) em.blend(a, b, x * 2f) else em.blend(b, c, x * 2f - 1f); card(em.presets[3], face, "", 220, 0.1f, k * 0.15f) }
        ImageIO.write(strip(frames, "${em.presets[3].name} blending: serene, laughing, in love"), "png", File(out, "emoji-blend.png"))
        val feelings = em.expressions.keys.toList()
        val rolls = (1..12).map { i -> val look = em.generate(i * 104729L, "Roll $i"); card(look, em.expressions.getValue(feelings[(i * 7) % feelings.size]), "${look.crest.label} · ${feelings[(i * 7) % feelings.size]}") }
        ImageIO.write(sheet(rolls, 6), "png", File(out, "emoji-rolls.png"))
    }

    private fun vectorMasks(out: File) {
        val art = com.stratum.engine.model.mask.AfricanMaskArt
        val designs = (1..18).map { art.generate(it * 7919L, "Mask $it") }
        val cards = designs.map { d ->
            val mesh = art.head(d)
            val f = art.features(d, mesh.face!!, time = 0.6f)
            caption(studio(mesh, 280, 360, f, sticker = false, shadows = 0.2f) { it.yaw = 0.18f; it.glow = 0.5f; it.eyes = 0.3f; it.scale = 1.3f },
                "${d.colours.name} · ${d.silhouette.label}")
        }
        ImageIO.write(sheet(cards, 6), "png", File(out, "vector-masks.png"))
        // The liveliest one: the most pieces that move.
        val d = designs.maxBy { x ->
            (if (x.crown != com.stratum.engine.model.mask.AfricanMaskArt.Crown.NONE) 2 else 0) +
                (if (x.hanging != com.stratum.engine.model.mask.AfricanMaskArt.Hanging.NONE) 2 else 0) +
                (if (x.sides != com.stratum.engine.model.mask.AfricanMaskArt.Sides.NONE) 1 else 0) + x.motion +
                (if (x.colours.name in setOf("Kaolin", "Ochre", "Brass", "Jade")) 3 else 0)
        }
        val mesh = art.head(d)
        val turn = listOf(-1.0f, -0.5f, 0f, 0.5f, 1.0f).map { yaw -> studio(mesh, 260, 340, art.features(d, mesh.face!!, 0.6f), sticker = false, shadows = 0.2f) { it.yaw = yaw; it.glow = 0.5f; it.scale = 1.3f } }
        ImageIO.write(strip(turn, "${d.name} turning: a painted head, the pieces floating in front of it"), "png", File(out, "vector-turn.png"))
        val frames = listOf(0.2f, 0.75f, 1.3f, 1.85f, 2.4f, 2.95f).map { tt -> studio(mesh, 230, 310, art.features(d, mesh.face!!, tt), sticker = false, shadows = 0.2f) { it.yaw = 0.15f; it.glow = 0.5f; it.scale = 1.3f } }
        ImageIO.write(strip(frames, "${d.name} animating: every piece moves on its own"), "png", File(out, "vector-anim.png"))
        // The pieces are parts: pulled apart, then re-placed by hand.
        val exploded = d.copy(nudges = mapOf(
            Part.CROWN to Nudge(0f, 0.45f, 1f, 0f), Part.HANGING to Nudge(0f, -0.4f, 1f, 0f), Part.EYES to Nudge(0f, 0.08f, 1f, 0f),
            Part.MOUTH to Nudge(0f, -0.14f, 1f, 0f), Part.BROWS to Nudge(0f, 0.2f, 1f, 0f), Part.SIDES to Nudge(0f, 0f, 1.25f, 0f), Part.NOSE to Nudge(0f, -0.05f, 1f, 0f),
        ))
        val edited = d.copy(nudges = mapOf(
            Part.CROWN to Nudge(0.05f, 0f, 0.8f, -0.25f), Part.EYES to Nudge(0f, 0f, 1.3f, 0f), Part.MOUTH to Nudge(0f, 0.05f, 1.4f, 0f),
            Part.BROWS to Nudge(0f, 0.04f, 1f, 0.12f),
        ))
        val views = listOf(d to "As generated", exploded to "Pulled apart", edited to "Re-placed by hand").map { (x, label) ->
            caption(studio(mesh, 320, 400, art.features(x, mesh.face!!, 0.6f), sticker = false, shadows = 0.2f) { it.yaw = 0.25f; it.glow = 0.5f; it.scale = 1.15f }, label)
        }
        ImageIO.write(sheet(views, 3), "png", File(out, "vector-parts.png"))
        designs.forEach { println("${it.name}: ${it.colours.name}, ${it.silhouette.label}, ${it.eyes.label}, ${it.brows.label}, ${it.mouth.label}, marks ${it.marks.map { m -> m.label }}, paint ${it.paint.map { p -> p.label }}, uli ${it.uli.map { u -> u.label }}, ${it.crown.label}, ${it.hanging.label}, ${it.sides.label}") }
    }

    /**
     * The mask builder's examples: oval spirit heads carrying nothing but
     * African mask art, each from the builder's dials alone; a sheet of all
     * of them, a turn of a few, and a sheet of fresh rolls, one per tradition.
     */
    private fun builder(out: File) {
        val examples = com.stratum.engine.model.mask.AfricanMaskBuilder.examples
        val cards = examples.map { g ->
            val mesh = MaskSpiritMesher.build(g)
            caption(studio(mesh, 280, 330) { it.yaw = 0.3f; it.glow = 0.55f; it.eyes = 0.25f }, g.name)
        }
        ImageIO.write(sheet(cards, 6), "png", File(out, "builder-examples.png"))
        for (g in examples.take(3)) {
            val mesh = MaskSpiritMesher.build(g)
            val views = listOf(-1.0f, -0.5f, 0f, 0.5f, 1.0f).map { yaw -> studio(mesh, 260, 310) { it.yaw = yaw; it.glow = 0.55f; it.eyes = 0.25f } }
            ImageIO.write(strip(views, "${g.name} · turning"), "png", File(out, "builder-turn-${slug(g.name)}.png"))
        }
        val rolls = com.stratum.engine.model.mask.MaskTradition.entries.flatMap { t ->
            (1..2).map { k -> com.stratum.engine.model.mask.AfricanMaskBuilder.roll(t.ordinal * 101L + k * 7L, t).copy(name = t.label) }
        }
        ImageIO.write(sheet(rolls.map { g -> caption(studio(MaskSpiritMesher.build(g), 240, 290) { it.yaw = 0.3f; it.glow = 0.55f; it.eyes = 0.25f }, "Rolled · ${g.name}") }, 7), "png", File(out, "builder-rolls.png"))
        println("wrote builder examples")
    }

    /** The Igbo emoji set as a sticker sheet, each in its own feeling, and each one showing every feeling. */
    private fun stickers(out: File) {
        val cards = com.stratum.engine.model.mask.IgboEmoji.set.map { e ->
            val mesh = MaskSpiritMesher.build(e.genome, emoji = true)
            val features = com.stratum.engine.model.mask.EmojiFace.build(e.genome, mesh.face!!, com.stratum.engine.model.mask.EmojiFace.presets.getValue(e.feeling))
            caption(studio(mesh, 260, 300, features, sunny = true) { it.yaw = 0.12f; it.glow = 0.1f; it.eyes = 0f }, e.name)
        }
        ImageIO.write(sheet(cards, 6), "png", File(out, "igbo-emoji-set.png"))
        val feelings = listOf("Calm", "Smile", "Wink", "Laugh", "Cheeky", "Love", "Star", "Surprise", "Anger", "Hurt", "Sad", "Sleep")
        for (e in com.stratum.engine.model.mask.IgboEmoji.set.take(4)) {
            val mesh = MaskSpiritMesher.build(e.genome, emoji = true)
            val views = feelings.map { f ->
                caption(studio(mesh, 220, 260, com.stratum.engine.model.mask.EmojiFace.build(e.genome, mesh.face!!, com.stratum.engine.model.mask.EmojiFace.presets.getValue(f)), sunny = true) { it.yaw = 0.12f; it.glow = 0.1f }, f)
            }
            ImageIO.write(sheet(views, 6), "png", File(out, "igbo-emoji-${slug(e.name)}.png"))
        }
        // A turn: the line work floats in front of the oval and slides across it.
        val e = com.stratum.engine.model.mask.IgboEmoji.set[1]
        val mesh = MaskSpiritMesher.build(e.genome, emoji = true)
        val wink = com.stratum.engine.model.mask.EmojiFace.build(e.genome, mesh.face!!, com.stratum.engine.model.mask.EmojiFace.presets.getValue("Love"))
        val turn = listOf(-1.1f, -0.55f, 0f, 0.55f, 1.1f).map { yaw -> studio(mesh, 260, 300, wink, sunny = true) { it.yaw = yaw; it.glow = 0.1f } }
        ImageIO.write(strip(turn, "${e.name} · Love, turning: line work floating over the oval"), "png", File(out, "igbo-emoji-turn.png"))
        println("wrote igbo-emoji-set.png")
    }

    private fun caption(img: BufferedImage, text: String): BufferedImage {
        val out = BufferedImage(img.width, img.height + 26, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color(0xF1, 0xEA, 0xDC); g.fillRect(0, 0, out.width, out.height)
        g.drawImage(img, 0, 0, null)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1A, 0x17, 0x1B); g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        g.drawString(text, 10, out.height - 8)
        g.dispose()
        return out
    }

    /** A mask alone in a studio: a Deco backdrop, a key light, the pose set by [setup]. */
    fun studio(
        mesh: SpiritMesh, width: Int, height: Int, features: com.stratum.engine.scene.SpiritFeatures? = null,
        sunny: Boolean = false,
        sticker: Boolean = true,
        shadows: Float = -1f,
        setup: (com.stratum.engine.scene.SpiritPose) -> Unit,
    ): BufferedImage {
        val spirit = SpiritInstance(mesh)
        spirit.features = features
        spirit.pose.scale = 2.1f
        setup(spirit.pose)
        val camera = SceneCamera(target = Vec3(0f, 0f, 0f), pitch = 8f, yaw = 270f, distance = 6f, fovY = 26f, aspect = width.toFloat() / height, near = 1f, far = 30f)
        val lighting = (if (sunny) sunnyLight() else studioLight()).let { if (shadows >= 0f) it.copy(shadowStrength = shadows) else it }
        val solid = MeshBuilder(MaterialKind.OPAQUE); val fading = MeshBuilder(MaterialKind.CUTOUT); val glows = MeshBuilder(MaterialKind.GLOW)
        val lights = ArrayList<PointLight>()
        SpiritStage(sticker = sticker).draw(listOf(spirit), camera, solid, fading, glows, lights)
        val recycler = MeshRecycler()
        val frame = SceneFrame(
            camera = camera, lighting = lighting, lights = lights,
            shadowViewProjection = sunMatrix(lighting, Vec3(0f, 0f, 0f), 3f),
            terrain = emptyList(), actors = solid.build(recycler), cutout = fading.build(recycler),
            decals = MeshBuilder(MaterialKind.DECAL).build(recycler), glows = glows.build(recycler),
        )
        return SceneRasterizer(width, height, TextureLibrary(), supersample = 3, shadowSize = 1024).render(frame)
    }

    /** The sun's orthographic camera over a small stage, as the scene builder frames a large one. */
    fun sunMatrix(lighting: SceneLighting, target: Vec3, extent: Float): FloatArray {
        val sun = Vec3(lighting.sunX, lighting.sunY, lighting.sunZ).normalized()
        val m = com.stratum.engine.scene.Mat4
        val view = m.lookAt(target + sun * 20f, target, if (kotlin.math.abs(sun.z) > 0.95f) Vec3(0f, 1f, 0f) else Vec3.UP)
        return m.multiply(m.orthographic(-extent, extent, -extent, extent, 1f, 40f), view)
    }

    /** A bright village afternoon: warm sun, a pale blue sky, soft light everywhere -- the sticker sheet's. */
    private fun sunnyLight(): SceneLighting {
        val base = director.lightingFor(null, WorldTime(dayFraction = 0.4f))
        val sx = 0.35f; val sy = 0.8f; val sz = 0.7f
        val l = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        return base.copy(
            sunX = sx / l, sunY = sy / l, sunZ = sz / l,
            fogStart = 100f, fogEnd = 200f, fogFloor = -100f,
            skyTop = 0xFF9FD6EE, skyBottom = 0xFFF6E7C8, vignette = 0.1f,
            sunColor = 0xFFFFF1DC, skyAmbient = 0xFFDCEBF5, groundAmbient = 0xFFF2D9BE,
            ambientIntensity = base.ambientIntensity * 1.35f, sunIntensity = base.sunIntensity * 1.1f,
            shadowStrength = 0.4f, saturation = base.saturation * 1.1f,
        )
    }

    private fun studioLight(): SceneLighting {
        val base = director.lightingFor(null, WorldTime(dayFraction = 0.3f))
        // Key light from the upper left and in front, like a fashion plate's.
        val sx = 0.45f; val sy = 0.75f; val sz = 0.6f
        val l = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        return base.copy(
            sunX = sx / l, sunY = sy / l, sunZ = sz / l,
            fogStart = 100f, fogEnd = 200f, fogFloor = -100f,
            skyTop = 0xFF1E2140, skyBottom = 0xFF33264A, vignette = 0.25f,
            shadowStrength = 0.55f, sunIntensity = base.sunIntensity * 1.25f, rimStrength = base.rimStrength * 1.6f + 0.2f,
        )
    }

    fun strip(views: List<BufferedImage>, caption: String): BufferedImage {
        val w = views.sumOf { it.width }; val h = views.maxOf { it.height } + 34
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0xF1, 0xEA, 0xDC); g.fillRect(0, 0, w, h)
        var x = 0
        views.forEach { g.drawImage(it, x, 0, null); x += it.width }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1A, 0x17, 0x1B); g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString(caption, 12, h - 11)
        g.dispose()
        return img
    }

    fun sheet(cards: List<BufferedImage>, columns: Int): BufferedImage {
        val cw = cards.maxOf { it.width }; val ch = cards.maxOf { it.height }
        val rows = (cards.size + columns - 1) / columns
        val img = BufferedImage(cw * columns, ch * rows, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        cards.forEachIndexed { i, c -> g.drawImage(c, (i % columns) * cw, (i / columns) * ch, null) }
        g.dispose()
        return img
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
