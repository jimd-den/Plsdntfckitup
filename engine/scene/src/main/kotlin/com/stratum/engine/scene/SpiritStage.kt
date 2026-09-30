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
class SpiritStage {
    private val emitter = SpiritEmitter()
    private val scratch = FloatArray(6)
    /** A pose reused for a spirit's hands and charms. */
    private val part = SpiritPose()
    /** Hands and a charm for each mask, made the first time that mask carries them. */
    private val parts = java.util.IdentityHashMap<SpiritMesh, Array<SpiritMesh>>()
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

        if (opacity > 0.02f) {
            val body = if (opacity < 0.999f) fading else solid
            emitter.emit(mesh, pose, body, fading = opacity < 0.999f)
            spirit.features?.let { emitter.emitFeatures(it, pose, body, fading = opacity < 0.999f) }
            if (pose.fringeRibbons > 0 && pose.fringePoints > 1) fringe(mesh, pose, body, opacity)
            if (pose.handCount > 0 || pose.charms > 0) parts(mesh, pose, body, opacity < 0.999f)
        }

        // The aura: a soft halo just behind the mask, breathing with its glow.
        val behindX = -sin(pose.yaw) * -s * 0.25f
        val behindY = cos(pose.yaw) * -s * 0.25f
        quad(glows, pose.x + behindX, pose.y + behindY, pose.z, s * (0.85f + 0.5f * flare), aura, (0.12f + 0.3f * glow) * opacity)

        // The eyes' light: a soft bloom over each eye whenever they burn,
        // and a hot star on top when they flare (a cast, a critical).
        val eyes = (glow * 0.5f + pose.eyes).coerceIn(0f, 2f)
        if (eyes > 0.05f && opacity > 0.1f) {
            val e = mesh.eyes
            for (k in 0 until 2) {
                emitter.worldOf(pose, e[k * 3], e[k * 3 + 1] + 0.05f, e[k * 3 + 2], scratch)
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
        const val HOT = 0xFFFFF6E6L
    }
}
