package com.stratum.tools.artpreview

import com.stratum.core.domain.motion.MotionBody
import com.stratum.core.domain.motion.MotionProfiles
import com.stratum.core.domain.motion.StrikeStyle
import com.stratum.engine.model.mask.sculpt.MaskCulture
import com.stratum.engine.model.mask.sculpt.MaskSculptor
import com.stratum.engine.model.mask.sculpt.MaskSpec
import com.stratum.engine.model.mask.sculpt.Spirit
import com.stratum.engine.model.mask.sculpt.SpiritSpec
import com.stratum.engine.model.mask.sculpt.Anatomy
import com.stratum.engine.scene.MaskCast
import com.stratum.engine.scene.ShardRig
import com.stratum.engine.scene.ShatteredSpirit
import com.stratum.engine.scene.SpiritInstance
import com.stratum.engine.scene.Vec3
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.max
import kotlin.math.min

/**
 * Masks broken open by their spirits, through the game's own scene
 * pipeline: every tradition, each way of breaking, each core, the masks
 * breathing whole, and the pieces alive in a fight -- dashing, striking,
 * casting, struck and dying. Stills as PNG sheets, motion as looping GIFs.
 *
 *   ./gradlew :tools:artpreview:maskSpiritPreview -Pout=docs/screenshots/spirit-masks -Ponly=spirits
 */
object SpiritMaskShots {
    private const val TILT = 0.35f

    fun all(out: File, quick: Boolean = false, only: String? = null) {
        out.mkdirs()
        val detail = if (quick) MaskSculptor.Detail.GAME else MaskSculptor.Detail.HIGH
        val t0 = System.nanoTime()
        val maiden = maiden()
        if (only == null || only == "traditions") traditions(out, detail)
        if (only == null || only == "fractures") fractures(out, maiden, detail)
        if (only == null || only == "cores") cores(out, detail)
        if (only == null || only == "breathing") breathing(out, maiden, detail)
        if (only == null || only == "alive") alive(out, detail)
        if (only == null || only == "party") party(out, detail)
        println("spirit masks written in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /** An Agbogho Mmuo like the museum pieces: kaolin-white, slit-eyed, crowned with arched crests. */
    private fun maiden(): MaskSpec {
        val t = MaskCulture.tradition("agbogho_mmuo")
        val seed = (1L..400L).first { val s = MaskCulture.generate(t, it); s.coiffure == Anatomy.Coiffure.ARCHES && s.finish == Anatomy.Finish.KAOLIN && s.eyes == Anatomy.Eyes.SLIT && s.mouth != Anatomy.Mouth.CLOSED }
        // Dressed as the reference piece: dark surrounds to the eyes, camwood on the crests' rims.
        return MaskCulture.generate(t, seed).copy(
            patterns = setOf(Anatomy.Pattern.EYE_RINGS), accent = com.stratum.engine.model.mask.sculpt.Pigments.UMBER, accent2 = com.stratum.engine.model.mask.sculpt.Pigments.CAMWOOD,
        )
    }

    private fun shatter(spec: MaskSpec, spirit: SpiritSpec, detail: MaskSculptor.Detail): ShatteredSpirit {
        val t0 = System.nanoTime()
        val sp = MaskSculptor.shatter(spec, spirit, detail)
        println("  ${spec.name}: ${spirit.fracture.label}, ${spirit.core.label} -> ${sp.shards.size} pieces, ${sp.triangleCount} triangles in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return sp
    }

    /** One spirit hovering at (x, y) facing [yaw], its pieces settled into their drift for [time] seconds. */
    private fun still(sp: ShatteredSpirit, x: Float, y: Float, yaw: Float, time: Float = 1.3f): SpiritInstance = SpiritInstance(sp.core).also {
        it.shattered = sp
        it.pose.x = x; it.pose.y = y; it.pose.z = 1.1f; it.pose.yaw = yaw; it.pose.scale = 1.5f
        it.pose.glow = 0.35f; it.pose.eyes = 0.3f; it.pose.lines = 0.2f; it.pose.pitch = TILT
        val rig = ShardRig(sp)
        var t = 0f
        while (t < time) { rig.update(it.pose, 1f / 30f); t += 1f / 30f }
        it.rig = rig
    }

    /** How high and how far to stand to frame a whole broken mask, relics and all. */
    private fun framing(sp: ShatteredSpirit, base: Float): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (sh in sp.shards) {
            val m = sh.mesh
            for (i in 0 until m.vertexCount) { val z = m.positions[i * 3 + 2] + sh.pivotZ + sh.restZ; lo = min(lo, z); hi = max(hi, z) }
        }
        val k = 1.5f
        return (1.1f + (lo + hi) / 2f * k) to (base * max(1f, (hi - lo) * k / 1.2f))
    }

    private fun card(sp: ShatteredSpirit, label: String, w: Int = 320, h: Int = 380, base: Float = 4.4f, yaw: Float = 0.35f, pitch: Float? = null): BufferedImage {
        val (tz, dist) = framing(sp, base)
        val img = if (pitch != null) MaskSpiritPreview.isoScene(listOf(still(sp, 0f, 0f, yaw)), w, h, distance = dist, pitch = pitch, target = Vec3(0f, 0f, tz))
        else MaskSpiritPreview.isoScene(listOf(still(sp, 0f, 0f, yaw)), w, h, distance = dist, target = Vec3(0f, 0f, tz))
        return MaskSpiritPreview.labelled(img, label)
    }

    /** Every tradition, carved and broken as its own spirit grammar rolls it. */
    private fun traditions(out: File, detail: MaskSculptor.Detail) {
        println("traditions")
        val cards = MaskCulture.traditions.map { t ->
            val spec = MaskCulture.generate(t, 11L)
            val spirit = MaskCulture.spirit(t, 11L)
            card(shatter(spec, spirit, detail), "${t.name} · ${spirit.fracture.label}")
        }
        ImageIO.write(MaskSpiritPreview.sheet(cards, 5), "png", File(out, "spirit-traditions.png"))
    }

    /** One maiden broken six ways. */
    private fun fractures(out: File, maiden: MaskSpec, detail: MaskSculptor.Detail) {
        println("fractures")
        val base = MaskCulture.spiritOf(maiden)
        val cards = Spirit.Fracture.entries.map { f ->
            card(shatter(maiden, base.copy(fracture = f, drift = 0.6f, pieces = 0.7f), detail), f.label, 340, 420, base = 3.6f, pitch = 16f)
        }
        ImageIO.write(MaskSpiritPreview.sheet(cards, 3), "png", File(out, "spirit-fractures.png"))
    }

    /** What burns inside: one fierce mask, split, with each of the eight cores. */
    private fun cores(out: File, detail: MaskSculptor.Detail) {
        println("cores")
        val t = MaskCulture.tradition("mgbedike")
        val spec = MaskCulture.generate(t, 4L)
        val base = MaskCulture.spirit(t, 4L)
        val cards = Spirit.Core.entries.map { c ->
            card(shatter(spec, base.copy(core = c, fracture = Spirit.Fracture.SPLIT_VISAGE, drift = 0.85f), detail), c.label, 300, 340, base = 3.4f, yaw = 0.15f, pitch = 14f)
        }
        ImageIO.write(MaskSpiritPreview.sheet(cards, 4), "png", File(out, "spirit-cores.png"))
    }

    /**
     * The masks whole, only breathing: no drift, the cracks hairlines of
     * light, the jaw parting and the brows and crest lifting with each
     * breath, the eyes' light swelling with it, blinking and glancing about.
     */
    private fun breathing(out: File, maiden: MaskSpec, detail: MaskSculptor.Detail) {
        println("breathing")
        val masks = listOf(
            "maiden" to (maiden to MaskCulture.spiritOf(maiden).copy(drift = 0f, fracture = Spirit.Fracture.SUSPENDED_FACETS, pieces = 0.8f)),
            "mgbedike" to MaskCulture.tradition("mgbedike").let { MaskCulture.generate(it, 4L) to MaskCulture.spirit(it, 4L).copy(drift = 0.05f, fracture = Spirit.Fracture.DRIFTING_JAW) },
            "okoroshi" to MaskCulture.tradition("okoroshi").let { MaskCulture.generate(it, 6L) to MaskCulture.spirit(it, 6L).copy(drift = 0.1f, fracture = Spirit.Fracture.SUSPENDED_FACETS, pieces = 0.9f) },
        )
        val firsts = ArrayList<List<BufferedImage>>()
        for ((name, pair) in masks) {
            val (spec, spirit) = pair
            val sp = shatter(spec, spirit, detail)
            val tr = MaskCulture.tradition(spec.tradition)
            val period = 1f / com.stratum.engine.model.mask.sculpt.MaskSculptor.temperamentOf(spirit).breathRate
            val frames = clip(sp, tr.motion, length = period, frames = 36, w = 300, h = 380, distance = 3.4f, pitch = 14f) { _, _, _ -> }
            gif(frames, File(out, "spirit-breathe-$name.gif"), (period * 1000 / frames.size).toInt())
            firsts += frames
        }
        // A strip of the maiden's breath, for reading without a player.
        val m = firsts.first()
        ImageIO.write(MaskSpiritPreview.strip(listOf(0, 6, 12, 18, 24, 30).map { m[it] }, "Agbogho Mmuo breathing whole: the jaw parts, brows and crests lift, the eyes swell, blink and glance"), "png", File(out, "spirit-breathe-strip.png"))
    }

    /** Broken spirits in a fight, through the game's own motion. */
    private fun alive(out: File, detail: MaskSculptor.Detail) {
        println("alive")
        class Clip(val id: String, val seed: Long, val spirit: (SpiritSpec) -> SpiritSpec, val title: String, val length: Float, val wide: Boolean = false, val act: (MotionBody, Float, Float) -> Unit)
        val clips = listOf(
            Clip("mgbedike", 4L, { it.copy(fracture = Spirit.Fracture.SUSPENDED_FACETS, drift = 0.6f) }, "Mgbedike dashes and stops: the pieces drag behind, then snap back into the face", 1.6f, wide = true) { _, _, _ -> },
            Clip("songye", 3L, { it.copy(fracture = Spirit.Fracture.FLOATING_QUADRANTS, drift = 0.6f) }, "Kifwebe strikes: storm crackle, the quarters flare out, arcs leap", 1.2f) { b, t, p -> if (p < 0.3f && t >= 0.3f) b.strike(1.2f, StrikeStyle.LUNGE) },
            Clip("ijele", 5L, { it.copy(drift = 0.6f, relics = setOf(Spirit.Relic.MIRROR, Spirit.Relic.BRASS_BELL)) }, "Ijele casts: the crown rises on its heavy springs, the relics swing round", 1.6f) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.cast() },
            Clip("fang", 2L, { it.copy(fracture = Spirit.Fracture.SPLIT_VISAGE, drift = 0.5f) }, "Ngil is struck: the halves jolt apart and settle, austere", 1.2f) { b, t, p -> if (p < 0.25f && t >= 0.25f) { b.hit(2f, 0f, 1.4f); b.crit() } },
            Clip("chokwe", 7L, { it.copy(fracture = Spirit.Fracture.DISSOLVED_CHIN, drift = 0.6f) }, "Pwo falls: the pieces are thrown apart and the core goes out", 1.6f) { b, t, p -> if (p < 0.2f && t >= 0.2f) b.die() },
        )
        for ((c, clip) in clips.withIndex()) {
            val tr = MaskCulture.tradition(clip.id)
            val spec = MaskCulture.generate(tr, clip.seed)
            val sp = shatter(spec, clip.spirit(MaskCulture.spirit(tr, clip.seed)), detail)
            val dash: ((Float) -> Float)? = if (clip.wide) { t -> if (t < 0.15f) -0.9f else if (t < 0.5f) -0.9f + 1.8f * ((t - 0.15f) / 0.35f) else 0.9f } else null
            val all = clip(sp, tr.motion, clip.length, frames = 30, w = if (clip.wide) 460 else 300, h = 360, distance = if (clip.wide) 4.8f else 3.9f, dash = dash, act = clip.act)
            gif(all, File(out, "spirit-anim-${c + 1}-${clip.id}.gif"), (clip.length * 1000 / all.size).toInt())
            val picks = (0 until 7).map { all[(it * (all.size - 1)) / 6] }
            ImageIO.write(MaskSpiritPreview.strip(picks, clip.title), "png", File(out, "spirit-anim-${c + 1}-${clip.id}.png"))
        }
    }

    /**
     * Runs a broken spirit through the motion bank at 30 frames a second for
     * [length] seconds and keeps [frames] evenly spaced pictures. [dash] moves
     * it across the stage over time; [act] fires its moves.
     */
    private fun clip(
        sp: ShatteredSpirit, motion: String, length: Float, frames: Int, w: Int, h: Int, distance: Float, pitch: Float? = null,
        dash: ((Float) -> Float)? = null, act: (MotionBody, Float, Float) -> Unit,
    ): List<BufferedImage> {
        val profile = MotionProfiles.resolve(motion)
        val cast = MaskCast()
        val dt = 1f / 30f
        val x0 = dash?.invoke(0f) ?: 0f
        repeat(45) { cast.begin(); cast.track("m", sp, profile, x0, 0f, 0f, 0.34f, 0.94f, spawning = false); cast.advance(dt) }
        val shots = ArrayList<BufferedImage>()
        val steps = (length / dt).toInt().coerceAtLeast(frames)
        var prev = -1f
        for (step in 0 until steps) {
            val t = step * dt
            cast.begin()
            val x = dash?.invoke(t) ?: 0f
            val body = cast.track("m", sp, profile, x, 0f, 0f, 0.34f, 0.94f, spawning = false)
            if (body != null) { body.aim(x + 1.1f, 3f, 1f); act(body, t, prev) }
            cast.advance(dt)
            cast.spirits.forEach { it.pose.pitch += TILT }
            if (step * frames / steps != (step - 1) * frames / steps || step == 0) {
                if (shots.size < frames) shots += if (pitch != null) MaskSpiritPreview.isoScene(cast.spirits.toList(), w, h, distance = distance, pitch = pitch, target = Vec3(0f, 0f, 1.25f))
                else MaskSpiritPreview.isoScene(cast.spirits.toList(), w, h, distance = distance, target = Vec3(0f, 0f, 1.25f))
            }
            prev = t
        }
        return shots
    }

    /** A war party of broken spirits on the ground, from the game's camera. */
    private fun party(out: File, detail: MaskSculptor.Detail) {
        println("party")
        val ids = listOf("mgbedike", "agbogho_mmuo", "ikenga", "ijele", "okoroshi", "songye", "gelede", "bugle")
        val spots = listOf(0f to 0f, -2.2f to 1.2f, 2.1f to 1.0f, -1.1f to 3.0f, 1.3f to 3.2f, -3.3f to -1.2f, 3.4f to -1.0f, 0.2f to -2.6f)
        val scene = ids.mapIndexed { i, id ->
            val t = MaskCulture.tradition(id)
            still(shatter(MaskCulture.generate(t, 11L), MaskCulture.spirit(t, 11L), MaskSculptor.Detail.GAME), spots[i].first, spots[i].second, 0.3f + i * 0.2f, time = 0.7f + i * 0.4f)
        }
        ImageIO.write(MaskSpiritPreview.labelled(MaskSpiritPreview.isoScene(scene, 1280, 800, distance = 16f, target = Vec3(0f, 0.5f, 1f)), "A war party of broken spirits, from the game's camera"), "png", File(out, "spirit-party.png"))
    }

    /** Writes [frames] as a looping GIF, [delayMs] a frame. */
    fun gif(frames: List<BufferedImage>, file: File, delayMs: Int) {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        file.delete()
        ImageIO.createImageOutputStream(file).use { ios ->
            writer.output = ios
            writer.prepareWriteSequence(null)
            for ((i, f) in frames.withIndex()) {
                val param = writer.defaultWriteParam
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(f), param)
                val format = meta.nativeMetadataFormatName
                val root = meta.getAsTree(format) as IIOMetadataNode
                fun node(name: String): IIOMetadataNode {
                    for (k in 0 until root.length) if (root.item(k).nodeName == name) return root.item(k) as IIOMetadataNode
                    return IIOMetadataNode(name).also { root.appendChild(it) }
                }
                node("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none"); setAttribute("userInputFlag", "FALSE"); setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", (delayMs / 10).coerceAtLeast(2).toString()); setAttribute("transparentColorIndex", "0")
                }
                if (i == 0) node("ApplicationExtensions").appendChild(IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0"); userObject = byteArrayOf(1, 0, 0)
                })
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(f, null, meta), param)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }
}
