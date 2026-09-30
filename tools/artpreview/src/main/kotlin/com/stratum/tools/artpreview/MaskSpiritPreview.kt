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
        val feelings = listOf("Calm", "Joy", "Laugh", "Anger", "Shout", "Surprise", "Hurt", "Sorrow", "Focus", "Radiant", "Dazed", "Rest")
        val grid = ArrayList<BufferedImage>()
        val pick = listOf("Joy", "Anger", "Surprise", "Hurt", "Radiant", "Rest")
        MaskGenome.presets.forEachIndexed { i, g ->
            val mesh = MaskSpiritMesher.build(g, emoji = true)
            val face = mesh.face ?: error("${g.name} has no emoji face")
            val t0 = System.nanoTime()
            val views = feelings.map { f ->
                val features = com.stratum.engine.model.mask.EmojiFace.build(g, face, com.stratum.engine.model.mask.EmojiFace.presets.getValue(f))
                f to studio(mesh, 240, 290, features) { it.yaw = 0.22f; it.glow = 0.5f; it.eyes = 0.3f }
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
        val features = com.stratum.engine.model.mask.EmojiFace.build(g, mesh.face!!, com.stratum.engine.model.mask.EmojiFace.presets.getValue("Joy"))
        val turn = listOf(-1.0f, -0.5f, 0f, 0.5f, 1.0f).map { yaw -> studio(mesh, 260, 320, features) { it.yaw = yaw; it.glow = 0.5f; it.eyes = 0.3f } }
        ImageIO.write(strip(turn, "${g.name} · Joy, turning: a drawn face on a 3D mask"), "png", File(out, "emoji-turn.png"))
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
        setup: (com.stratum.engine.scene.SpiritPose) -> Unit,
    ): BufferedImage {
        val spirit = SpiritInstance(mesh)
        spirit.features = features
        spirit.pose.scale = 2.1f
        setup(spirit.pose)
        val camera = SceneCamera(target = Vec3(0f, 0f, 0f), pitch = 8f, yaw = 270f, distance = 6f, fovY = 26f, aspect = width.toFloat() / height, near = 1f, far = 30f)
        val lighting = studioLight()
        val solid = MeshBuilder(MaterialKind.OPAQUE); val fading = MeshBuilder(MaterialKind.CUTOUT); val glows = MeshBuilder(MaterialKind.GLOW)
        val lights = ArrayList<PointLight>()
        SpiritStage().draw(listOf(spirit), camera, solid, fading, glows, lights)
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
