package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.ShardRole
import com.stratum.engine.scene.SpiritMesh
import com.stratum.engine.scene.SpiritTemperament
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Where a mask breaks: cells round anchors placed on the face's own
 * landmarks (each brow, each cheek, the jaw, the crest), so it splits along
 * the lines a carver's forms already draw rather than at random.
 *
 * The cells are Voronoi cells under an anisotropic metric that counts
 * distance across the face more than distance down it, so the walls between
 * them stand upright: wood splits along its grain, which runs down the
 * block. Every wall is a plane through the whole thickness of the mask.
 *
 * Space is warped before the cells are found, by one warp shared by all of
 * them (so they still tile without gaps or overlaps): a long, sharp zigzag
 * down the grain for the splits, a short ragged one across it, where wood
 * tears rather than cleaves.
 *
 * [crack] is the gap between the cells as a shape: kept outside it, the
 * carving falls into pieces in the same meshing pass that carves it.
 */
internal class FractureCells(
    val ax: FloatArray, val az: FloatArray, val roles: Array<ShardRole>,
    private val warp: Float, private val halfGap: Float, private val phase: Float,
) {
    val n = ax.size
    /** 1 / (2 |W (a_j - a_i)|): turns a difference of metric distances into a distance to the wall between two cells. */
    private val inv = FloatArray(n * n)

    init {
        require(n >= 2 && az.size == n && roles.size == n)
        for (i in 0 until n) for (j in 0 until n) if (i != j) {
            val dx = WX * (ax[j] - ax[i]); val dz = WZ * (az[j] - az[i])
            inv[i * n + j] = 1f / (2f * sqrt(dx * dx + dz * dz)).coerceAtLeast(1e-5f)
        }
    }

    private fun qx(x: Float, y: Float, z: Float): Float =
        x + warp * (1.3f * (MaskSculptor.vee(z * 4.6f + phase) - 0.5f) + 0.8f * (MaskSculptor.noise(z * 11f + phase, x * 3f, y * 3f) - 0.5f))

    private fun qz(x: Float, y: Float, z: Float): Float =
        z + warp * 0.75f * (1.3f * (MaskSculptor.vee(x * 13f + phase * 1.7f) - 0.5f) + 0.9f * (MaskSculptor.noise(x * 31f, z * 4f + phase, y * 5f) - 0.5f))

    private fun metric(i: Int, x: Float, z: Float): Float { val dx = x - ax[i]; val dz = z - az[i]; return WX * dx * dx + WZ * dz * dz }

    /** Which cell (x, y, z) is in, face units. */
    fun cellAt(x: Float, y: Float, z: Float): Int {
        val px = qx(x, y, z); val pz = qz(x, y, z)
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0 until n) { val d = metric(i, px, pz); if (d < bd) { bd = d; best = i } }
        return best
    }

    /** How far inside its cell a point is: the distance to the nearest wall. */
    fun wall(x: Float, y: Float, z: Float): Float {
        val px = qx(x, y, z); val pz = qz(x, y, z)
        var best = 0; var bd = Float.MAX_VALUE
        for (i in 0 until n) { val d = metric(i, px, pz); if (d < bd) { bd = d; best = i } }
        var w = Float.MAX_VALUE
        for (j in 0 until n) if (j != best) { val d = (metric(j, px, pz) - bd) * inv[best * n + j]; if (d < w) w = d }
        return w
    }

    /** The gaps between the cells: kept outside, the carving breaks along them. */
    val crack: Shape = Custom(-9f, -9f, -9f, 9f, 9f, 9f) { x, y, z -> halfGap - wall(x, y, z) }

    companion object {
        /** Across the face counts for more than down it: the walls stand with the grain. */
        const val WX = 1.4f
        const val WZ = 0.7f

        /**
         * The cells for [spirit]'s fracture on a face [hw] wide and [hh] tall
         * (half sizes), its eye, brow and mouth lines where the carving put
         * them. [crowned]: the mask carries something tall on its head.
         */
        fun of(spirit: SpiritSpec, hw: Float, hh: Float, eyeZ: Float, browZ: Float, mouthZ: Float, crowned: Boolean, cell: Float): FractureCells {
            val r = Random(spirit.seed * 13 + spirit.fracture.ordinal * 7919L)
            val ez = eyeZ / hh; val bz = browZ / hh; val mz = mouthZ / hh
            val p = spirit.pieces
            val xs = ArrayList<Float>(); val zs = ArrayList<Float>(); val rs = ArrayList<ShardRole>()
            fun at(x: Float, z: Float, role: ShardRole, jitter: Float = 0.06f) {
                xs += (x + (r.nextFloat() - 0.5f) * 2f * jitter) * hw; zs += (z + (r.nextFloat() - 0.5f) * 2f * jitter) * hh; rs += role
            }
            when (spirit.fracture) {
                Spirit.Fracture.SUSPENDED_FACETS -> {
                    at(-0.55f, bz + 0.15f, ShardRole.BROW_LEFT); at(0.55f, bz + 0.15f, ShardRole.BROW_RIGHT)
                    at(-0.62f, mz + 0.15f, ShardRole.CHEEK_LEFT); at(0.62f, mz + 0.15f, ShardRole.CHEEK_RIGHT)
                    at(0f, -1.02f, ShardRole.JAW)
                    if (crowned || p > 0.35f) at(0f, 1.3f, ShardRole.CREST)
                    if (p > 0.55f) at(0f, ez - 0.12f, ShardRole.BRIDGE, 0.02f)
                }
                Spirit.Fracture.SPLIT_VISAGE -> {
                    at(-0.6f, 0f, ShardRole.HALF_LEFT, 0.02f); at(0.6f, 0f, ShardRole.HALF_RIGHT, 0.02f)
                    if (crowned && p > 0.5f) at(0f, 1.75f, ShardRole.CREST)
                }
                Spirit.Fracture.SHATTERED_CROWN -> {
                    at(0f, -0.35f, ShardRole.FACE, 0f)
                    val m = 3 + (p * 3.99f).toInt()
                    for (i in 0 until m) at(-1.1f + 2.2f * (i + 0.5f) / m, 1.3f + 0.3f * r.nextFloat(), ShardRole.CROWN, 0.08f)
                }
                Spirit.Fracture.FLOATING_QUADRANTS -> {
                    at(-0.6f, ez + 0.4f, ShardRole.BROW_LEFT, 0.03f); at(0.6f, ez + 0.4f, ShardRole.BROW_RIGHT, 0.03f)
                    at(-0.6f, ez - 0.6f, ShardRole.CHEEK_LEFT, 0.03f); at(0.6f, ez - 0.6f, ShardRole.CHEEK_RIGHT, 0.03f)
                    if (crowned) at(0f, 1.6f, ShardRole.CREST)
                }
                Spirit.Fracture.DRIFTING_JAW -> {
                    at(0f, mz + 0.6f, ShardRole.FACE, 0.02f); at(0f, mz - 0.6f, ShardRole.JAW, 0.02f)
                    if (crowned && p > 0.4f) at(0f, 1.8f, ShardRole.CREST)
                }
                Spirit.Fracture.DISSOLVED_CHIN -> {
                    at(0f, mz + 0.5f, ShardRole.FACE, 0f)
                    // Embers packed into the jaw itself, so every one takes a piece of it.
                    val m = 7 + (p * 5.99f).toInt()
                    for (i in 0 until m) at((r.nextFloat() - 0.5f) * 0.9f, mz - 0.15f - (0.95f + mz - 0.15f).coerceAtLeast(0.2f) * r.nextFloat(), ShardRole.EMBER, 0f)
                }
            }
            val warp = 0.004f + 0.022f * spirit.jag
            return FractureCells(xs.toFloatArray(), zs.toFloatArray(), rs.toTypedArray(), warp, max(0.011f, 1.35f * cell), r.nextFloat() * 10f)
        }
    }
}

/**
 * What burns inside a broken mask, and what circles it: small meshes of
 * swept tubes and balls, every vertex lit by the core's light.
 */
internal object SpiritCores {

    /** The temperament a tradition's temper names. */
    fun temperament(t: Spirit.Temper): SpiritTemperament = when (t) {
        Spirit.Temper.AUSTERE -> SpiritTemperament.AUSTERE
        Spirit.Temper.STORM -> SpiritTemperament.STORM
        Spirit.Temper.MONUMENTAL -> SpiritTemperament.MONUMENTAL
        Spirit.Temper.BREATHING -> SpiritTemperament.BREATHING
        Spirit.Temper.RESTLESS -> SpiritTemperament.RESTLESS
    }

    private val CORE = GlowChannel.CORE.toInt()

    /** A circle of [n] + 1 points (closed) of radius [r] about the origin, in the plane with normal turned [tiltX] about x and [tiltZ] about z. */
    private fun circle(r: Float, n: Int, tiltX: Float, tiltZ: Float): FloatArray {
        val out = FloatArray((n + 1) * 3)
        for (i in 0..n) {
            val a = 2f * PI.toFloat() * i / n
            val x = cos(a) * r; val y0 = sin(a) * r
            val y = y0 * cos(tiltX); val z = y0 * sin(tiltX)
            out[i * 3] = x * cos(tiltZ) - y * sin(tiltZ); out[i * 3 + 1] = x * sin(tiltZ) + y * cos(tiltZ); out[i * 3 + 2] = z
        }
        return out
    }

    /** The core, about [radius] across, centred on the origin. */
    fun core(core: Spirit.Core, radius: Float, seed: Long, fine: Boolean): TubeMesh {
        val t = TubeMesh()
        val r = Random(seed * 31 + core.ordinal)
        val g = core.glow; val g2 = core.second
        val d = if (fine) 8 else 5
        when (core) {
            Spirit.Core.THUNDER -> {
                t.ball(0f, 0f, 0f, radius * 0.42f, g2, CORE, d)
                // Bolts forking out, zigzagging, thinning to nothing.
                for (b in 0 until 7) {
                    val a = 2f * PI.toFloat() * (b + 0.3f * r.nextFloat()) / 7f; val up = (r.nextFloat() - 0.5f) * 1.2f
                    val n = 5; val pts = FloatArray(n * 3)
                    for (k in 0 until n) {
                        val f = k / (n - 1f) * radius * 1.7f
                        val zig = if (k in 1 until n - 1) (r.nextFloat() - 0.5f) * radius * 0.5f else 0f
                        pts[k * 3] = cos(a) * f - sin(a) * zig; pts[k * 3 + 1] = (r.nextFloat() - 0.5f) * radius * 0.4f; pts[k * 3 + 2] = sin(a) * f * 0.8f + up * f * 0.3f + cos(a) * zig
                    }
                    t.sweep(pts, 12, 4, { u -> radius * 0.07f * (1f - 0.85f * u) }, { u -> TubeMesh.lerp(g2, g, u) }, CORE)
                }
            }
            Spirit.Core.SOLAR -> {
                t.ball(0f, 0f, 0f, radius * 0.6f, g, CORE, d)
                // A sunburst of rays in the face's plane, as a Deco sun is drawn.
                for (k in 0 until 14) {
                    val a = 2f * PI.toFloat() * k / 14f; val len = if (k % 2 == 0) 0.75f else 0.5f
                    t.sweep(floatArrayOf(cos(a) * radius * 0.55f, 0f, sin(a) * radius * 0.55f, cos(a) * radius * (0.55f + len), 0f, sin(a) * radius * (0.55f + len)), 4, 5, { u -> radius * 0.12f * (1f - 0.9f * u) }, { u -> TubeMesh.lerp(g, g2, u) }, CORE)
                }
            }
            Spirit.Core.LEOPARD -> {
                // A void with a leopard's rosettes burning in it.
                t.ball(0f, 0f, 0f, radius * 0.72f, 0xFF150C1E.toInt(), 0, d)
                for (k in 0 until 11) {
                    val u = r.nextFloat() * 2f - 1f; val a = r.nextFloat() * 2f * PI.toFloat(); val s = sqrt(1f - u * u)
                    val cx = s * cos(a) * radius * 0.74f; val cy = s * sin(a) * radius * 0.74f + radius * 0.1f; val cz = u * radius * 0.74f
                    t.ball(cx, cy, cz, radius * 0.1f, if (k % 3 == 0) g else g2, CORE, 4)
                }
            }
            Spirit.Core.MARSH -> {
                t.ball(0f, 0f, 0f, radius * 0.38f, g2, CORE, d)
                // A flame twisting upward.
                val n = 16; val pts = FloatArray(n * 3)
                for (k in 0 until n) { val f = k / (n - 1f); val a = f * 3f * PI.toFloat(); pts[k * 3] = cos(a) * radius * 0.35f * (1f - f); pts[k * 3 + 1] = sin(a) * radius * 0.35f * (1f - f); pts[k * 3 + 2] = -radius * 0.3f + f * radius * 1.7f }
                t.sweep(pts, 24, 6, { u -> radius * 0.32f * (1f - u) + 0.002f }, { u -> TubeMesh.lerp(g, g2, u) }, CORE)
            }
            Spirit.Core.FIREFLY -> {
                // A swarm: motes in a loose cloud, denser at the heart.
                for (k in 0 until 28) {
                    val u = r.nextFloat() * 2f - 1f; val a = r.nextFloat() * 2f * PI.toFloat(); val s = sqrt(1f - u * u); val rr = radius * (0.2f + 0.9f * r.nextFloat())
                    t.ball(s * cos(a) * rr, s * sin(a) * rr * 0.6f, u * rr, radius * (0.05f + 0.04f * r.nextFloat()), if (k % 2 == 0) g else g2, CORE, 3)
                }
            }
            Spirit.Core.MOON -> {
                t.ball(0f, 0f, 0f, radius * 0.58f, g, CORE, d)
                t.sweep(circle(radius * 0.95f, 24, 1.2f, 0.3f), 48, 5, { radius * 0.035f }, { g2 }, CORE, capEnd = false)
            }
            Spirit.Core.CAMWOOD -> {
                t.ball(0f, 0f, 0f, radius * 0.55f, g, CORE, d)
                for (k in 0 until 7) {
                    val a = 2f * PI.toFloat() * k / 7f
                    t.ellipsoid(cos(a) * radius * 0.85f, 0f, sin(a) * radius * 0.85f, radius * 0.09f, radius * 0.09f, radius * 0.16f, g2, CORE, 4)
                }
            }
            Spirit.Core.INDIGO -> {
                t.ball(0f, 0f, 0f, radius * 0.3f, g2, CORE, d)
                for (k in 0 until 3) t.sweep(circle(radius * (0.6f + 0.15f * k), 24, 0.9f + 0.7f * k, 0.8f * k), 48, 4, { radius * 0.04f }, { if (k == 1) g2 else g }, CORE, capEnd = false)
            }
        }
        return t
    }

    /** One relic, its centre at the origin, facing out along +x (it is turned to face out from the head). */
    fun relic(kind: Spirit.Relic, size: Float, spec: MaskSpec, core: Spirit.Core): TubeMesh {
        val t = TubeMesh()
        val brass = Pigments.argb(Pigments.BRASS)
        val patinated = MaskSculptor.mix(brass, 0xFF3F7A62.toInt(), 0.3f)
        when (kind) {
            Spirit.Relic.BRONZE_PLAQUE -> {
                // A cast plaque, its rim raised and a boss at its heart, gone green in its hollows.
                t.box(0f, 0f, 0f, size * 0.12f, size * 0.62f, size * 0.85f, patinated)
                t.box(size * 0.08f, 0f, 0f, size * 0.06f, size * 0.5f, size * 0.72f, MaskSculptor.mix(brass, 0xFF2B5A48.toInt(), 0.45f))
                t.ball(size * 0.16f, 0f, 0f, size * 0.22f, brass, GlowChannel.CREST.toInt(), 5)
            }
            Spirit.Relic.BRASS_BELL -> {
                // A bell flaring from its loop, its clapper below.
                t.sweep(floatArrayOf(0f, 0f, size * 0.7f, 0f, 0f, size * 0.1f, 0f, 0f, -size * 0.5f), 10, 10, { u -> size * (0.12f + 0.45f * u * u) }, { brass }, 0, capEnd = false)
                t.ball(0f, 0f, -size * 0.62f, size * 0.15f, MaskSculptor.mix(brass, 0xFF000000.toInt(), 0.3f), 0, 4)
            }
            Spirit.Relic.HALO -> t.sweep(circle(size * 1.6f, 28, PI.toFloat() / 2f, PI.toFloat() / 2f), 56, 5, { size * 0.08f }, { core.glow }, GlowChannel.CORE.toInt(), capEnd = false)
            Spirit.Relic.COWRIE -> {
                t.ellipsoid(0f, 0f, 0f, size * 0.35f, size * 0.42f, size * 0.62f, 0xFFF3EAD6.toInt(), 0, 6)
                t.box(size * 0.33f, 0f, 0f, size * 0.04f, size * 0.05f, size * 0.42f, 0xFF3A2A20.toInt())
            }
            Spirit.Relic.KANAGA -> {
                // The double-barred cross, its bars' ends turned up above and down below.
                val wood = Pigments.argb(if (spec.finish == Anatomy.Finish.KAOLIN) Pigments.KAOLIN else spec.wood)
                t.box(0f, 0f, 0f, size * 0.07f, size * 0.07f, size * 0.95f, wood)
                for ((z, dir) in listOf(size * 0.45f to 1f, -size * 0.25f to -1f)) {
                    t.box(0f, 0f, z, size * 0.07f, size * 0.75f, size * 0.07f, wood)
                    for (sd in listOf(-1f, 1f)) t.box(0f, sd * size * 0.75f, z + dir * size * 0.18f, size * 0.07f, size * 0.07f, size * 0.2f, Pigments.argb(spec.accent))
                }
            }
            Spirit.Relic.MIRROR -> {
                // A trade mirror set in a frame, as Ijele carries them: it catches the core's light.
                t.sweep(floatArrayOf(-size * 0.06f, 0f, 0f, size * 0.06f, 0f, 0f), 2, 16, { size * 0.6f }, { 0xFFE6F0FF.toInt() }, GlowChannel.CREST.toInt())
                t.sweep(circle(size * 0.62f, 20, PI.toFloat() / 2f, PI.toFloat() / 2f), 40, 5, { size * 0.08f }, { brass }, 0, capEnd = false)
            }
        }
        return t
    }

    /** A tube mesh as a spirit's mesh, built about its own origin, its glows its own colours. */
    fun mesh(t: TubeMesh, aura: Int, second: Int, eyes: FloatArray = FloatArray(6), face: Int = aura, fringe: IntArray? = null): SpiritMesh {
        val p = t.pos.toArray(); val n = t.nrm.toArray(); val c = t.col.toArray(); val g = t.glow.toArray(); val idx = t.idx.toArray()
        // Faced outward, triangle by triangle, as the sculptor does its swept parts.
        for (k in 0 until idx.size / 3) {
            val a = idx[k * 3] * 3; val b = idx[k * 3 + 1] * 3; val e = idx[k * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[e] - p[a]; val vy = p[e + 1] - p[a + 1]; val vz = p[e + 2] - p[a + 2]
            val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
            if (fx * (n[a] + n[b] + n[e]) + fy * (n[a + 1] + n[b + 1] + n[e + 1]) + fz * (n[a + 2] + n[b + 2] + n[e + 2]) < 0f) { val tmp = idx[k * 3 + 1]; idx[k * 3 + 1] = idx[k * 3 + 2]; idx[k * 3 + 2] = tmp }
        }
        val ch = ByteArray(g.size) { g[it].toByte() }
        return if (fringe != null) SpiritMesh(p, n, c, ch, c.copyOf(), idx, eyes, auraColor = aura, auraSecond = second, faceColor = face, fringeColors = fringe)
        else SpiritMesh(p, n, c, ch, c.copyOf(), idx, eyes, auraColor = aura, auraSecond = second, faceColor = face)
    }
}
