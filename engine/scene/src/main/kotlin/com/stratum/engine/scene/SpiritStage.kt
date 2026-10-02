package com.stratum.engine.scene

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stages floating mask spirits in a frame: the smooth mask itself, and the
 * light it throws around -- aura, afterimage trail, strike ring, crit glints,
 * shield and death shards.
 *
 * ## Why the light is drawn apart from the mask
 *
 * The mask is lit geometry and must sit in the depth buffer, cast a shadow
 * and be hidden by a wall like anything solid. Its light is additive glow
 * that should bleed over whatever is behind it, the way a flare does on film.
 * So the body goes to the opaque actors' batch (or the cut-out batch while it
 * dissolves, whose screen-door fade needs no sorting), and every effect goes
 * to the glow batch the combat theatre already uses. No new material, no new
 * shader: both backends already draw all of it.
 *
 * ## Why it is drawn this way
 *
 * The user asked for Art Deco clarity, so the effects are made of few,
 * deliberate shapes rather than noise: a ring is evenly spaced beads, a
 * trail is a tapering row of discs, a shield is a disc with a crisp rim --
 * the geometry of a sunburst or a radiator grille, in the mask's own palette.
 *
 * Allocation-free: the camera's axes are read into fields once per frame and
 * every quad is written straight into the builders.
 */
class SpiritStage(
    /** Draw emoji heads with a white sticker edge. */
    var sticker: Boolean = true,
) {
    private val emitter = SpiritEmitter()
    private val scratch = FloatArray(6)
    /** A pose reused for a spirit's hands and charms. */
    private val part = SpiritPose()
    /** Hands and a charm for each mask, made the first time that mask carries them. */
    private val parts = java.util.IdentityHashMap<SpiritMesh, Array<SpiritMesh>>()
    /** Rigs holding broken masks at rest, for spirits nobody animates. */
    private val still = java.util.IdentityHashMap<ShatteredSpirit, ShardRig>()
    private var tx = 0f; private var ty = 0f; private var tz = 1f
    private var rx = 0f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 0f; private var uz = 0f

    fun draw(
        spirits: List<SpiritInstance>,
        camera: SceneCamera,
        solid: MeshBuilder,
        fading: MeshBuilder,
        glows: MeshBuilder,
        lights: MutableList<PointLight>,
    ) {
        val right = camera.right; val up = camera.up
        rx = right.x; ry = right.y; rz = right.z
        ux = up.x; uy = up.y; uz = up.z
        val ex = camera.eye.x - camera.target.x; val ey = camera.eye.y - camera.target.y; val ez = camera.eye.z - camera.target.z
        val el = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-4f)
        tx = ex / el; ty = ey / el; tz = ez / el
        for (i in spirits.indices) spirit(spirits[i], solid, fading, glows, lights)
    }

    private fun spirit(spirit: SpiritInstance, solid: MeshBuilder, fading: MeshBuilder, glows: MeshBuilder, lights: MutableList<PointLight>) {
        val mesh = spirit.mesh
        val pose = spirit.pose
        if (!pose.x.isFinite() || !pose.y.isFinite() || !pose.z.isFinite() || !(pose.scale > 0f)) return
        val opacity = pose.opacity.coerceIn(0f, 1f)
        val s = pose.scale
        val aura = mesh.auraColor.toLong() and 0xFFFFFFFFL
        val second = mesh.auraSecond.toLong() and 0xFFFFFFFFL
        val glow = pose.glow.coerceIn(0f, 1.5f)
        val flare = pose.flare.coerceIn(0f, 1f)
        val eyeLight = aura
        val broken = spirit.shattered
        val rig = if (broken == null) null else spirit.rig?.takeIf { it.spirit === broken } ?: still.getOrPut(broken) { ShardRig(broken) }

        if (opacity > 0.02f) {
            val body = if (opacity < 0.999f) fading else solid
            // An emoji head wears the white die-cut edge of a sticker.
            if (mesh.face != null && opacity > 0.9f && sticker && broken == null) emitter.emitShell(mesh, pose, body, -tx, -ty, -tz, STICKER_WIDTH, STICKER_PUSH, STICKER_WHITE)
            if (broken != null && rig != null) pieces(broken, rig, pose, body, opacity < 0.999f)
            else emitter.emit(mesh, pose, body, fading = opacity < 0.999f)
            if (broken == null) spirit.features?.let { emitter.emitFeatures(it, pose, body, fading = opacity < 0.999f) }
            // A broken mask carries its raffia as a piece of its own, its shroud.
            if (pose.fringeRibbons > 0 && pose.fringePoints > 1 && broken == null) fringe(mesh, pose, body, opacity)
            if (pose.handCount > 0 || pose.charms > 0) parts(mesh, pose, body, opacity < 0.999f)
        }

        if (broken != null && rig != null) light(broken, rig, pose, glows, lights, opacity)

        // The aura: a soft halo just behind the mask, breathing with its glow.
        val behindX = -sin(pose.yaw) * -s * 0.25f
        val behindY = cos(pose.yaw) * -s * 0.25f
        quad(glows, pose.x + behindX, pose.y + behindY, pose.z, s * (0.85f + 0.5f * flare), aura, (0.12f + 0.3f * glow) * opacity)

        // The eyes' light: a soft bloom over each eye whenever they burn,
        // and a hot star on top when they flare (a cast, a critical).
        val eyes = if (rig != null) ((glow * 0.5f + pose.eyes) * rig.eyeLight + 0.4f * rig.eyeLight).coerceIn(0f, 2f) else (glow * 0.5f + pose.eyes).coerceIn(0f, 2f)
        if (eyes > 0.05f && opacity > 0.1f) {
            val e = mesh.eyes
            for (k in 0 until 2) {
                val holder = broken?.eyeShards?.get(k) ?: -1
                if (broken != null && rig != null) {
                    // A broken mask's eyes ride the pieces they were carved in.
                    if (holder < 0) continue
                    val o = holder * 3
                    emitter.worldOf(pose, rig.locals[holder], broken.eyes[k * 3], broken.eyes[k * 3 + 1] + 0.05f, broken.eyes[k * 3 + 2], rig.lag[o], rig.lag[o + 1], rig.lag[o + 2], scratch)
                    // The glint: a hot point that looks about inside the eye and locks on as it strikes.
                    emitter.worldOf(pose, rig.locals[holder], broken.eyes[k * 3] + rig.gazeX, broken.eyes[k * 3 + 1] + 0.03f, broken.eyes[k * 3 + 2] + rig.gazeZ, rig.lag[o], rig.lag[o + 1], rig.lag[o + 2], scratch, 3)
                    quad(glows, scratch[3], scratch[4], scratch[5], s * (0.022f + 0.02f * rig.surge), HOT, rig.eyeLight * 1.3f * opacity)
                } else emitter.worldOf(pose, e[k * 3], e[k * 3 + 1] + 0.05f, e[k * 3 + 2], scratch)
                val strength = eyes * opacity
                quad(glows, scratch[0], scratch[1], scratch[2], s * (0.1f + 0.08f * strength), eyeLight, strength * 0.9f)
                if (eyes > 0.6f) quad(glows, scratch[0], scratch[1], scratch[2], s * (0.05f + 0.12f * (eyes - 0.6f)), HOT, (eyes - 0.6f) * 2.2f * opacity)
            }
        }

        // The afterimage trail: a row of discs along where it has just been,
        // shrinking and cooling from the mask's colour to its second.
        if (flare > 0.05f && pose.trailCount > 1) {
            val n = pose.trailCount
            for (k in 1 until n) {
                val share = k / n.toFloat()
                val o = k * 3
                val colour = if (share < 0.5f) aura else second
                quad(glows, pose.trail[o], pose.trail[o + 1], pose.trail[o + 2], s * (0.55f - 0.35f * share), colour, flare * (1f - share) * 0.9f)
            }
        }

        // The strike ring: evenly spaced beads round the mask, opening out as
        // the strike lands, a sunburst in the mask's palette.
        if (flare > 0.05f) {
            val r = s * (0.55f + 0.9f * flare)
            for (k in 0 until RING_BEADS) {
                val a = k * TAU / RING_BEADS + pose.yaw
                val ox = rx * cos(a) * r + ux * sin(a) * r
                val oy = ry * cos(a) * r + uy * sin(a) * r
                val oz = rz * cos(a) * r + uz * sin(a) * r
                val colour = if (k % 2 == 0) aura else second
                quad(glows, pose.x + ox, pose.y + oy, pose.z + oz, s * 0.09f, colour, (1f - flare) * 1.8f * opacity)
            }
            lights += PointLight(pose.x, pose.y, pose.z, aura, flare * FLARE_LIGHT, 3.5f)
        }

        // The shield: a disc of light with a beaded rim, stood between the
        // guarded body and the blow, flickering as it takes it.
        val shield = pose.shield.coerceIn(0f, 1f)
        if (shield > 0.02f) {
            val fx = pose.shieldFacingX; val fy = pose.shieldFacingY
            val len = sqrt(fx * fx + fy * fy).coerceAtLeast(1e-4f)
            val cx = pose.shieldX + fx / len * 0.55f
            val cy = pose.shieldY + fy / len * 0.55f
            val cz = pose.shieldZ
            val r = 0.75f
            quad(glows, cx, cy, cz, r * 1.1f, aura, shield * 0.35f)
            for (k in 0 until SHIELD_BEADS) {
                val a = k * TAU / SHIELD_BEADS
                val ox = rx * cos(a) * r + ux * sin(a) * r
                val oy = ry * cos(a) * r + uy * sin(a) * r
                val oz = rz * cos(a) * r + uz * sin(a) * r
                quad(glows, cx + ox, cy + oy, cz + oz, 0.1f, if (k % 3 == 0) HOT else aura, shield * 1.4f)
            }
        }

        // A crack of light where a blow lands: a six-rayed star, opening out.
        val crack = pose.crack.coerceIn(0f, 1f)
        if (crack > 0.02f) {
            val open = 1f - crack
            for (ray in 0 until 6) {
                val a = ray * TAU / 6 + 0.26f
                for (k in 1..3) {
                    val r = s * (0.12f + 0.5f * open) * k / 3f
                    val ox = rx * cos(a) * r + ux * sin(a) * r
                    val oy = ry * cos(a) * r + uy * sin(a) * r
                    val oz = rz * cos(a) * r + uz * sin(a) * r
                    quad(glows, pose.x + ox + tx * s * 0.3f, pose.y + oy + ty * s * 0.3f, pose.z + oz + tz * s * 0.3f, s * 0.05f * (4 - k) / 2f, if (k == 1) HOT else aura, crack * 2f)
                }
            }
        }

        // Death: shards of light thrown out and up as the mask dissolves.
        val shatter = pose.shatter.coerceIn(0f, 1f)
        if (shatter > 0.01f) {
            for (k in 0 until SHARDS) {
                val a = k * TAU / SHARDS + 0.4f
                val out = s * (0.3f + 1.4f * shatter) * (0.7f + 0.3f * ((k * 7) % 5) / 4f)
                val lift = s * 1.6f * shatter * shatter * (0.5f + 0.5f * ((k * 3) % 4) / 3f)
                val ox = rx * cos(a) * out + ux * sin(a) * out
                val oy = ry * cos(a) * out + uy * sin(a) * out
                val oz = rz * cos(a) * out + uz * sin(a) * out + lift
                val fade = 1f - shatter
                quad(glows, pose.x + ox, pose.y + oy, pose.z + oz, s * 0.12f * (0.5f + fade), if (k % 2 == 0) aura else HOT, fade * 1.6f)
            }
        }
    }

    /**
     * A broken mask's solid parts: the core, burning, and every piece where
     * its spring has carried it. The core's light reaches the raw faces of
     * the cracks through the CORE channel.
     */
    private fun pieces(broken: ShatteredSpirit, rig: ShardRig, pose: SpiritPose, out: MeshBuilder, fading: Boolean) {
        val saved = pose.core; val savedEyes = pose.eyes
        pose.core = saved + rig.coreGlow()
        // The carved eyes themselves burn with the breath and go dark for a blink.
        pose.eyes = savedEyes * rig.eyeLight + rig.eyeLight - 0.5f
        emitter.emit(broken.core, pose, out, fading, rig.coreLocal, 0f, 0f, 0f)
        // The pieces' split faces only smoulder with it: a tint, not a lamp.
        pose.core = saved + SPLIT_GLOW * (rig.coreGlow() - 0.6f)
        for (i in broken.shards.indices) {
            val o = i * 3
            emitter.emit(broken.shards[i].mesh, pose, out, fading, rig.locals[i], rig.lag[o], rig.lag[o + 1], rig.lag[o + 2])
        }
        pose.core = saved; pose.eyes = savedEyes
    }

    /**
     * The light of a broken mask: a halo round the core, threads of beads
     * tethering every piece to it (brighter as they are flung out), and for
     * a storm spirit, arcs leaping from the core to its pieces as it strikes.
     */
    private fun light(broken: ShatteredSpirit, rig: ShardRig, pose: SpiritPose, glows: MeshBuilder, lights: MutableList<PointLight>, opacity: Float) {
        val s = pose.scale
        val glow = rig.coreGlow()
        val colour = broken.core.auraColor.toLong() and 0xFFFFFFFFL
        val second = broken.core.auraSecond.toLong() and 0xFFFFFFFFL
        emitter.worldOf(pose, rig.coreLocal, 0f, 0f, 0f, 0f, 0f, 0f, scratch)
        val cx = scratch[0]; val cy = scratch[1]; val cz = scratch[2]
        quad(glows, cx, cy, cz, s * (0.2f + 0.14f * rig.surge), colour, (0.35f + 0.45f * glow) * opacity)
        quad(glows, cx, cy, cz, s * (0.06f + 0.05f * rig.surge), HOT, glow * 0.9f * opacity)
        if (glow > 0.7f) lights += PointLight(cx, cy, cz, colour, (glow - 0.5f) * CORE_LIGHT, 2.5f)
        val tether = (0.16f + 0.6f * rig.surge) * opacity * (1f - rig.scatter)
        val n = broken.shards.size
        for (i in 0 until n) {
            val o = i * 3
            emitter.worldOf(pose, rig.locals[i], 0f, 0f, 0f, rig.lag[o], rig.lag[o + 1], rig.lag[o + 2], scratch)
            val px = scratch[0]; val py = scratch[1]; val pz = scratch[2]
            if (tether > 0.02f) for (k in 1..TETHER_BEADS) {
                val f = k / (TETHER_BEADS + 1f)
                quad(glows, cx + (px - cx) * f, cy + (py - cy) * f, cz + (pz - cz) * f, s * 0.011f * (1.3f - f * 0.6f), if (k % 2 == 0) second else colour, tether * (1f - 0.5f * f))
            }
            // A storm spirit's arcs: a jagged run of sparks to a few of its pieces, flickering as it surges.
            if (broken.arcs && rig.surge > 0.15f && ((rig.time * 14f).toInt() + i * 5) % 3 == 0) {
                val strength = (rig.surge - 0.15f) * 1.8f * opacity
                for (k in 1..ARC_SPARKS) {
                    val f = k / (ARC_SPARKS + 1f)
                    val zig = (if (k % 2 == 0) 1f else -1f) * s * 0.025f * kotlin.math.sin(rig.time * 40f + i + k)
                    quad(glows, cx + (px - cx) * f + rx * zig, cy + (py - cy) * f + ry * zig, cz + (pz - cz) * f + uz * zig, s * 0.016f, if (k % 2 == 0) HOT else colour, strength)
                }
            }
        }
    }

    /**
     * The raffia fringe: each ribbon a strip turned to the camera, tapering
     * to its tip, in the mask's banded fringe colours. Lit like the mask, so
     * it sits in the same light.
     */
    private fun fringe(mesh: SpiritMesh, pose: SpiritPose, out: MeshBuilder, opacity: Float) {
        val f = pose.fringe
        val n = pose.fringePoints
        val colours = mesh.fringeColors
        val width = pose.scale * 0.055f
        for (r in 0 until pose.fringeRibbons) {
            val colour = colours[r % colours.size].toLong() and 0xFFFFFFFFL
            val o = r * n * 3
            var prevL = -1; var prevR = -1
            for (i in 0 until n) {
                val p = o + i * 3
                if (!f[p].isFinite() || !f[p + 1].isFinite() || !f[p + 2].isFinite()) { prevL = -1; continue }
                // Across the strip: the camera's right, less whatever of it runs along the ribbon.
                val q = if (i + 1 < n) p + 3 else p - 3
                var dx = f[q] - f[p]; var dy = f[q + 1] - f[p + 1]; var dz = f[q + 2] - f[p + 2]
                val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-5f)
                dx /= dl; dy /= dl; dz /= dl
                val along = rx * dx + ry * dy + rz * dz
                var sx = rx - dx * along; var sy = ry - dy * along; var sz = rz - dz * along
                val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-5f)
                val w = width * (1f - 0.55f * i / (n - 1f))
                sx = sx / sl * w; sy = sy / sl * w; sz = sz / sl * w
                val l = out.vertex(f[p] - sx, f[p + 1] - sy, f[p + 2] - sz, tx, ty, tz, colour, opacity, 0f, 0f, Vertex.ACTOR, 0.08f)
                val rr = out.vertex(f[p] + sx, f[p + 1] + sy, f[p + 2] + sz, tx, ty, tz, colour, opacity, 0f, 0f, Vertex.ACTOR, 0.08f)
                if (prevL >= 0) out.quad(prevL, prevR, rr, l)
                prevL = l; prevR = rr
            }
        }
    }

    /** Floating hands and orbiting charms, each a small mesh posed off the mask's own frame. */
    private fun parts(mesh: SpiritMesh, pose: SpiritPose, out: MeshBuilder, fading: Boolean) {
        val set = parts.getOrPut(mesh) {
            arrayOf(SpiritParts.hand(mesh.faceColor, mesh.auraColor), SpiritParts.charm(mesh.charmColor, mesh.auraColor))
        }
        part.copyPartFrom(pose)
        part.glow = (0.35f + pose.handGlow).coerceIn(0f, 1.5f)
        part.crest = pose.handGlow
        for (side in 0 until pose.handCount.coerceAtMost(2)) {
            val h = pose.hands
            emitter.worldOf(pose, h[side * 3], h[side * 3 + 1], h[side * 3 + 2], scratch)
            part.x = scratch[0]; part.y = scratch[1]; part.z = scratch[2]
            part.roll = pose.roll + if (side == 0) -0.35f else 0.35f
            emitter.emit(set[0], part, out, fading)
        }
        val n = pose.charms.coerceAtMost(8)
        for (k in 0 until n) {
            val a = pose.charmSpin + k * TAU / n
            val lx = cos(a) * 0.62f; val ly = sin(a) * 0.62f; val lz = sin(a * 2f + k) * 0.08f - 0.05f
            // Orbit about the vertical through the mask, not tilted with it: charms circle like a halo.
            part.x = pose.x + (lx * cos(pose.yaw) - ly * sin(pose.yaw)) * pose.scale
            part.y = pose.y + (lx * sin(pose.yaw) + ly * cos(pose.yaw)) * pose.scale
            part.z = pose.z + lz * pose.scale
            part.yaw = pose.yaw + a - 1.5708f
            part.roll = 0f; part.pitch = 0f
            part.lines = 0.5f + pose.flare
            emitter.emit(set[1], part, out, fading)
        }
    }

    /** A soft additive disc facing the camera, written without allocating. */
    private fun quad(out: MeshBuilder, x: Float, y: Float, z: Float, radius: Float, color: Long, opacity: Float) {
        if (opacity <= 0.003f || radius <= 0f) return
        val ax = rx * radius; val ay = ry * radius; val az = rz * radius
        val bx = ux * radius; val by = uy * radius; val bz = uz * radius
        val c = color or 0xFF000000L
        val a = out.vertex(x - ax - bx, y - ay - by, z - az - bz, 0f, 0f, 1f, c, opacity, -1f, -1f, Vertex.DISC)
        val b = out.vertex(x + ax - bx, y + ay - by, z + az - bz, 0f, 0f, 1f, c, opacity, 1f, -1f, Vertex.DISC)
        val d = out.vertex(x + ax + bx, y + ay + by, z + az + bz, 0f, 0f, 1f, c, opacity, 1f, 1f, Vertex.DISC)
        val e = out.vertex(x - ax + bx, y - ay + by, z - az + bz, 0f, 0f, 1f, c, opacity, -1f, 1f, Vertex.DISC)
        out.quad(a, b, d, e)
    }

    private companion object {
        const val TAU = 6.2831855f
        const val RING_BEADS = 16
        const val SHIELD_BEADS = 18
        const val SHARDS = 12
        const val FLARE_LIGHT = 1.6f
        const val CORE_LIGHT = 1.2f
        /** How much of the core's light reaches the split faces of the pieces. */
        const val SPLIT_GLOW = 0.35f
        const val TETHER_BEADS = 4
        const val ARC_SPARKS = 6
        const val HOT = 0xFFFFF6E6L
        /** The sticker edge: how far the white shell swells past the head, how far it sits behind it, and its white. */
        const val STICKER_WIDTH = 0.07f
        const val STICKER_PUSH = 0.35f
        const val STICKER_WHITE = 0xFFFFFCF6.toInt()
    }
}
