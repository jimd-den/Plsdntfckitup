package com.stratum.tools.microvoxelpreview

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.MicroChunkPos
import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A fixed box of chunks, flattened into an array for fast lookup while tracing. */
class VoxelScene(chunks: Collection<MicroChunk>, val palette: MaterialPalette) {
    val cx0 = chunks.minOf { it.pos.x }; val cy0 = chunks.minOf { it.pos.y }; val cz0 = chunks.minOf { it.pos.z }
    val nx = chunks.maxOf { it.pos.x } - cx0 + 1
    val ny = chunks.maxOf { it.pos.y } - cy0 + 1
    val nz = chunks.maxOf { it.pos.z } - cz0 + 1
    private val grid = arrayOfNulls<MicroChunk>(nx * ny * nz)
    private val empty = BooleanArray(nx * ny * nz) { true }

    val minX = cx0 * S; val minY = cy0 * S; val minZ = cz0 * S
    val maxX = (cx0 + nx) * S; val maxY = (cy0 + ny) * S; val maxZ = (cz0 + nz) * S

    val water = palette.id(M.WATER)
    val lamp = palette.id(M.LAMP)

    init {
        for (c in chunks) {
            val i = ((c.pos.z - cz0) * ny + (c.pos.y - cy0)) * nx + (c.pos.x - cx0)
            grid[i] = c
            empty[i] = c.isEmpty()
        }
    }

    fun chunkIndex(x: Int, y: Int, z: Int): Int {
        val cx = Math.floorDiv(x, S) - cx0; val cy = Math.floorDiv(y, S) - cy0; val cz = Math.floorDiv(z, S) - cz0
        if (cx !in 0 until nx || cy !in 0 until ny || cz !in 0 until nz) return -1
        return (cz * ny + cy) * nx + cx
    }

    fun chunk(i: Int): MicroChunk? = grid[i]
    fun isEmptyChunk(i: Int) = empty[i]

    operator fun get(x: Int, y: Int, z: Int): Short {
        val i = chunkIndex(x, y, z)
        if (i < 0) return MaterialPalette.AIR
        val c = grid[i] ?: return MaterialPalette.AIR
        return c[x - c.pos.originX, y - c.pos.originY, z - c.pos.originZ]
    }

    fun solid(x: Int, y: Int, z: Int): Boolean {
        val m = get(x, y, z)
        return m != MaterialPalette.AIR && m != water
    }

    /** Every lamp voxel, for night lighting. */
    fun lamps(): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (c in grid) {
            if (c == null) continue
            for (bz in 0 until 8) for (by in 0 until 8) for (bx in 0 until 8) {
                if (c.brickUniform(bx, by, bz) != null) continue
                for (z in bz * 8 until bz * 8 + 8) for (y in by * 8 until by * 8 + 8) for (x in bx * 8 until bx * 8 + 8)
                    if (c[x, y, z] == lamp) out += intArrayOf(c.pos.originX + x, c.pos.originY + y, c.pos.originZ + z)
            }
        }
        return out
    }

    companion object {
        const val S = MicroChunk.SIZE
        fun of(chunks: Map<MicroChunkPos, MicroChunk>, palette: MaterialPalette) = VoxelScene(chunks.values, palette)
    }
}

class Hit {
    var t = 0f
    var x = 0; var y = 0; var z = 0
    var axis = 2
    var sign = 1 // the normal is +sign along axis
    var material: Short = 0
}

/** Brick-skipping Amanatides-Woo traversal. Empty chunks and uniform-air bricks are crossed in one step. */
class RayCaster(private val scene: VoxelScene) {

    fun trace(ox0: Float, oy0: Float, oz0: Float, dx: Float, dy: Float, dz: Float, maxT: Float, skipWater: Boolean, hit: Hit): Boolean {
        val s = scene
        val ix0 = if (dx != 0f) 1f / dx else Float.POSITIVE_INFINITY
        val iy0 = if (dy != 0f) 1f / dy else Float.POSITIVE_INFINITY
        val iz0 = if (dz != 0f) 1f / dz else Float.POSITIVE_INFINITY
        // Clip to the scene box.
        var tEnter = 0f; var tExit = maxT; var axis = 2
        run {
            val a0 = (s.minX - ox0) * ix0; val a1 = (s.maxX - ox0) * ix0
            val b0 = (s.minY - oy0) * iy0; val b1 = (s.maxY - oy0) * iy0
            val c0 = (s.minZ - oz0) * iz0; val c1 = (s.maxZ - oz0) * iz0
            val nx = min(a0, a1); val ny = min(b0, b1); val nz = min(c0, c1)
            tEnter = max(0f, max(nx, max(ny, nz)))
            axis = if (nx >= ny && nx >= nz) 0 else if (ny >= nz) 1 else 2
            tExit = min(maxT, min(max(a0, a1), min(max(b0, b1), max(c0, c1))))
        }
        if (dx.isNaN() || tEnter >= tExit) return false
        // Rebase onto the entry point: far-away origins (ortho cameras, long rays)
        // otherwise lose the precision the epsilon steps below rely on.
        val base = tEnter
        val ox = ox0 + dx * base; val oy = oy0 + dy * base; val oz = oz0 + dz * base
        var t = 0f
        val tEnd = tExit - base
        var guard = 0
        while (t < tEnd && guard++ < 20000) {
            val te = t + 1e-3f
            val px = ox + dx * te; val py = oy + dy * te; val pz = oz + dz * te
            var x = floor(px).toInt(); var y = floor(py).toInt(); var z = floor(pz).toInt()
            val ci = s.chunkIndex(x, y, z)
            if (ci < 0) return false
            val chunk = s.chunk(ci)
            if (chunk == null || s.isEmptyChunk(ci)) {
                val bx = Math.floorDiv(x, 64) * 64; val by = Math.floorDiv(y, 64) * 64; val bz = Math.floorDiv(z, 64) * 64
                val r = exitBox(ox, oy, oz, ix0, iy0, iz0, bx, by, bz, 64)
                t = r; axis = lastAxis; continue
            }
            val lx = x - chunk.pos.originX; val ly = y - chunk.pos.originY; val lz = z - chunk.pos.originZ
            val uniform = chunk.brickUniform(lx shr 3, ly shr 3, lz shr 3)
            if (uniform == MaterialPalette.AIR || (skipWater && uniform == s.water)) {
                val r = exitBox(ox, oy, oz, ix0, iy0, iz0, x and 7.inv(), y and 7.inv(), z and 7.inv(), 8)
                t = r; axis = lastAxis; continue
            }
            if (uniform != null) return record(hit, base + t, x, y, z, axis, dx, dy, dz, uniform)
            // Voxel DDA inside a detailed brick.
            val bx0 = x and 7.inv(); val by0 = y and 7.inv(); val bz0 = z and 7.inv()
            val sx = if (dx > 0) 1 else -1; val sy = if (dy > 0) 1 else -1; val sz = if (dz > 0) 1 else -1
            var tmx = if (dx != 0f) ((if (dx > 0) x + 1 else x) - ox) * ix0 else Float.POSITIVE_INFINITY
            var tmy = if (dy != 0f) ((if (dy > 0) y + 1 else y) - oy) * iy0 else Float.POSITIVE_INFINITY
            var tmz = if (dz != 0f) ((if (dz > 0) z + 1 else z) - oz) * iz0 else Float.POSITIVE_INFINITY
            val tdx = abs(ix0); val tdy = abs(iy0); val tdz = abs(iz0)
            while (true) {
                val m = chunk[x - chunk.pos.originX, y - chunk.pos.originY, z - chunk.pos.originZ]
                if (m != MaterialPalette.AIR && !(skipWater && m == s.water)) return record(hit, base + t, x, y, z, axis, dx, dy, dz, m)
                if (tmx < tmy && tmx < tmz) { t = tmx; tmx += tdx; x += sx; axis = 0 }
                else if (tmy < tmz) { t = tmy; tmy += tdy; y += sy; axis = 1 }
                else { t = tmz; tmz += tdz; z += sz; axis = 2 }
                if (t >= tEnd) return false
                if (x - bx0 !in 0..7 || y - by0 !in 0..7 || z - bz0 !in 0..7) break
            }
        }
        return false
    }

    private var lastAxis = 2

    private fun exitBox(ox: Float, oy: Float, oz: Float, ix: Float, iy: Float, iz: Float, bx: Int, by: Int, bz: Int, size: Int): Float {
        val tx = if (ix > 0) (bx + size - ox) * ix else if (ix < 0) (bx - ox) * ix else Float.POSITIVE_INFINITY
        val ty = if (iy > 0) (by + size - oy) * iy else if (iy < 0) (by - oy) * iy else Float.POSITIVE_INFINITY
        val tz = if (iz > 0) (bz + size - oz) * iz else if (iz < 0) (bz - oz) * iz else Float.POSITIVE_INFINITY
        return if (tx <= ty && tx <= tz) { lastAxis = 0; tx } else if (ty <= tz) { lastAxis = 1; ty } else { lastAxis = 2; tz }
    }

    private fun record(hit: Hit, t: Float, x: Int, y: Int, z: Int, axis: Int, dx: Float, dy: Float, dz: Float, m: Short): Boolean {
        hit.t = t; hit.x = x; hit.y = y; hit.z = z; hit.axis = axis; hit.material = m
        val d = when (axis) { 0 -> dx; 1 -> dy; else -> dz }
        hit.sign = if (d > 0) -1 else 1
        return true
    }
}

/** A camera that turns a pixel into a ray. */
interface Camera {
    /** [u], [v] in -1..1, v up. Writes origin and direction into [out] (6 floats). */
    fun ray(u: Float, v: Float, out: FloatArray)

    /** Distance along a ray before fog starts: an ortho ray begins far behind the scene. */
    val fogBias: Float get() = 0f
}

class PerspectiveCamera(
    private val px: Float, private val py: Float, private val pz: Float,
    tx: Float, ty: Float, tz: Float, fovDeg: Float, private val aspect: Float,
) : Camera {
    private val f: FloatArray = norm(floatArrayOf(tx - px, ty - py, tz - pz))
    private val r: FloatArray = norm(cross(f, floatArrayOf(0f, 0f, 1f)))
    private val up: FloatArray = cross(r, f)
    private val k = kotlin.math.tan(fovDeg * PI.toFloat() / 360f)

    override fun ray(u: Float, v: Float, out: FloatArray) {
        val a = u * k * aspect; val b = v * k
        val d = norm(floatArrayOf(f[0] + r[0] * a + up[0] * b, f[1] + r[1] * a + up[1] * b, f[2] + r[2] * a + up[2] * b))
        out[0] = px; out[1] = py; out[2] = pz; out[3] = d[0]; out[4] = d[1]; out[5] = d[2]
    }
}

/** Orthographic: isometric dioramas and top-down maps. */
class OrthoCamera(
    private val cx: Float, private val cy: Float, private val cz: Float,
    yawDeg: Float, pitchDeg: Float, private val halfWidth: Float, private val aspect: Float,
) : Camera {
    private val f: FloatArray
    private val r: FloatArray
    private val up: FloatArray
    private val back = 3000f
    override val fogBias: Float get() = back - 300f

    init {
        val yaw = yawDeg * PI.toFloat() / 180f; val pitch = pitchDeg * PI.toFloat() / 180f
        f = norm(floatArrayOf(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), -sin(pitch)))
        r = norm(cross(f, if (pitchDeg > 89.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, 1f)))
        up = cross(r, f)
    }

    override fun ray(u: Float, v: Float, out: FloatArray) {
        val a = u * halfWidth; val b = v * halfWidth / aspect
        out[0] = cx + r[0] * a + up[0] * b - f[0] * back
        out[1] = cy + r[1] * a + up[1] * b - f[1] * back
        out[2] = cz + r[2] * a + up[2] * b - f[2] * back
        out[3] = f[0]; out[4] = f[1]; out[5] = f[2]
    }
}

internal fun norm(v: FloatArray): FloatArray {
    val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
}

internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

/** Lighting for one picture. */
data class Lighting(
    val sunAzimuth: Float = 225f,
    val sunElevation: Float = 24f,
    val sun: FloatArray = floatArrayOf(3.2f, 2.5f, 1.7f),
    val skyZenith: FloatArray = floatArrayOf(0.20f, 0.36f, 0.72f),
    val skyHorizon: FloatArray = floatArrayOf(0.92f, 0.70f, 0.52f),
    val ambient: Float = 0.55f,
    val fogDensity: Float = 0.0007f,
    /** Fog starts this far from the camera, so the foreground keeps its contrast. */
    val fogStart: Float = 200f,
    val night: Boolean = false,
    val exposure: Float = 1.0f,
) {
    companion object {
        val GOLDEN = Lighting()
        val DAY = Lighting(
            sunAzimuth = 140f, sunElevation = 52f, sun = floatArrayOf(2.9f, 2.8f, 2.55f),
            skyZenith = floatArrayOf(0.22f, 0.45f, 0.85f), skyHorizon = floatArrayOf(0.62f, 0.76f, 0.92f), fogDensity = 0.0005f,
        )
        val NIGHT = Lighting(
            sunAzimuth = 60f, sunElevation = 38f, sun = floatArrayOf(0.12f, 0.16f, 0.28f),
            skyZenith = floatArrayOf(0.01f, 0.015f, 0.05f), skyHorizon = floatArrayOf(0.06f, 0.06f, 0.12f),
            ambient = 0.35f, fogDensity = 0.0012f, night = true, exposure = 1.6f,
        )
    }
}

/**
 * A small offline renderer: primary ray, sun shadow ray, per-pixel voxel
 * corner occlusion plus a few short hemisphere rays, fresnel water with
 * refraction depth tint, glossy reflections on glass and metal, aerial
 * perspective, and -- at night -- unshadowed street-lamp point lights and bloom.
 *
 * It is a review tool, not the game renderer: it exists so a generator change
 * can be judged by eye on any machine in seconds.
 */
class Renderer(private val scene: VoxelScene, private val light: Lighting, private val samples: Int = 1) {
    private val palette = scene.palette
    private val sunDir: FloatArray = run {
        val a = light.sunAzimuth * PI.toFloat() / 180f; val e = light.sunElevation * PI.toFloat() / 180f
        floatArrayOf(cos(e) * cos(a), cos(e) * sin(a), sin(e))
    }
    private val linear = Array(palette.all.size) { i ->
        val c = palette[i.toShort()].color
        floatArrayOf(srgb((c shr 16) and 255), srgb((c shr 8) and 255), srgb(c and 255))
    }
    private val litGlass = palette.id(M.GLASS_LIT)
    private val glass = palette.id(M.GLASS)
    private val lamps: LampGrid? = if (light.night) LampGrid(scene.lamps()) else null

    @Volatile private var fogBias = 0f

    fun render(camera: Camera, width: Int, height: Int): BufferedImage {
        fogBias = camera.fogBias
        val hdr = FloatArray(width * height * 3)
        val glow = FloatArray(width * height * 3)
        IntStream.range(0, height).parallel().forEach { py ->
            val caster = RayCaster(scene)
            val hit = Hit(); val ray = FloatArray(6); val acc = FloatArray(3); val em = FloatArray(3)
            val px3 = FloatArray(3); val em3 = FloatArray(3)
            for (px in 0 until width) {
                acc.fill(0f); em.fill(0f)
                val n = samples
                for (sy in 0 until n) for (sx in 0 until n) {
                    val u = ((px + (sx + 0.5f) / n) / width) * 2f - 1f
                    val v = 1f - ((py + (sy + 0.5f) / n) / height) * 2f
                    camera.ray(u, v, ray)
                    px3.fill(0f); em3.fill(0f)
                    shade(caster, hit, ray, px3, em3, depth = 0, pixelSeed = py * 7919 + px * 104729 + sy * 31 + sx)
                    for (k in 0..2) { acc[k] += px3[k]; em[k] += em3[k] }
                }
                val inv = 1f / (n * n)
                val i = (py * width + px) * 3
                for (k in 0..2) { hdr[i + k] = acc[k] * inv; glow[i + k] = em[k] * inv }
            }
        }
        if (light.night) bloom(hdr, glow, width, height)
        return tonemap(hdr, width, height)
    }

    private fun shade(caster: RayCaster, hit: Hit, ray: FloatArray, out: FloatArray, emit: FloatArray, depth: Int, pixelSeed: Int) {
        val ox = ray[0]; val oy = ray[1]; val oz = ray[2]; val dx = ray[3]; val dy = ray[4]; val dz = ray[5]
        if (!caster.trace(ox, oy, oz, dx, dy, dz, 6000f, skipWater = false, hit = hit)) {
            sky(dx, dy, dz, out); return
        }
        val t = hit.t
        val hx = ox + dx * t; val hy = oy + dy * t; val hz = oz + dz * t
        val nx = if (hit.axis == 0) hit.sign.toFloat() else 0f
        val ny = if (hit.axis == 1) hit.sign.toFloat() else 0f
        val nz = if (hit.axis == 2) hit.sign.toFloat() else 0f
        val mat = palette[hit.material]
        val m = hit.material

        if (m == scene.water) {
            water(caster, hit, ray, hx, hy, hz, out, emit, depth, pixelSeed)
        } else {
            surface(caster, hit, hx, hy, hz, nx, ny, nz, out, emit, pixelSeed)
            val gloss = if (m == litGlass && !light.night) palette[glass].gloss else mat.gloss
            if (gloss > 0.3f && depth == 0) {
                val dn = dx * nx + dy * ny + dz * nz
                val r = floatArrayOf(hx + nx * 0.01f, hy + ny * 0.01f, hz + nz * 0.01f, dx - 2 * dn * nx, dy - 2 * dn * ny, dz - 2 * dn * nz)
                val rc = FloatArray(3); val re = FloatArray(3)
                shade(caster, Hit(), r, rc, re, depth + 1, pixelSeed)
                val fres = (0.08f + 0.92f * (1f + dn).coerceIn(0f, 1f).pow(5)) * gloss
                for (k in 0..2) out[k] = out[k] * (1f - fres) + rc[k] * fres
            }
        }
        fog(out, dx, dy, dz, t)
    }

    private fun surface(caster: RayCaster, hit: Hit, hx: Float, hy: Float, hz: Float, nx: Float, ny: Float, nz: Float, out: FloatArray, emit: FloatArray, pixelSeed: Int) {
        val m = hit.material
        val mat = palette[m]
        val base = if (m == litGlass && !light.night) linear[glass.toInt()] else linear[m.toInt()]
        // Per-voxel tone jitter and a faint bevel at voxel edges: the microvoxel look.
        val j = 1f + (hash3(hit.x, hit.y, hit.z) - 0.5f) * 2f * mat.jitter
        val (fu, fv) = faceUV(hit.axis, hx, hy, hz)
        val edge = min(min(fu, 1f - fu), min(fv, 1f - fv))
        val bevel = if (edge < 0.07f) 0.9f else 1f
        val ao = cornerAO(hit, fu, fv) * hemisphereAO(caster, hit, hx, hy, hz, nx, ny, nz, pixelSeed)

        val ndl = nx * sunDir[0] + ny * sunDir[1] + nz * sunDir[2]
        var direct = 0f
        if (ndl > 0f) {
            val sh = Hit()
            val lit = !caster.trace(hx + nx * 0.02f, hy + ny * 0.02f, hz + nz * 0.02f, sunDir[0], sunDir[1], sunDir[2], 900f, false, sh)
            if (lit) direct = ndl
        }
        // Sky light from above, bounce light from the ground below.
        val skyW = 0.5f + 0.5f * nz
        for (k in 0..2) {
            val ambient = light.ambient * (light.skyZenith[k] * skyW * 1.6f + light.skyHorizon[k] * (1f - skyW) * 0.5f + 0.06f) * ao
            out[k] = base[k] * j * bevel * (light.sun[k] * direct + ambient)
        }
        if (light.night) {
            lamps?.illuminate(hx, hy, hz, nx, ny, nz, out, base)
            if (mat.emission > 0f) for (k in 0..2) {
                val e = base[k] * mat.emission * (0.8f + 0.4f * hash3(hit.x / 3, hit.y / 3, hit.z / 3))
                out[k] += e; emit[k] += max(0f, e - 0.35f)
            }
        } else if (m == scene.lamp) for (k in 0..2) out[k] += base[k] * 0.6f
    }

    private fun water(caster: RayCaster, hit: Hit, ray: FloatArray, hx: Float, hy: Float, hz: Float, out: FloatArray, emit: FloatArray, depth: Int, pixelSeed: Int) {
        val dx = ray[3]; val dy = ray[4]; val dz = ray[5]
        // Gentle normal ripple so water is not a mirror-flat floor.
        val wx = (vnoise(hx * 0.15f, hy * 0.15f) - 0.5f) * 0.25f
        val wy = (vnoise(hx * 0.15f + 40f, hy * 0.15f) - 0.5f) * 0.25f
        val n = norm(floatArrayOf(wx, wy, 1f))
        val dn = dx * n[0] + dy * n[1] + dz * n[2]
        val refl = FloatArray(3)
        if (depth == 0) {
            val r = floatArrayOf(hx, hy, hz + 0.02f, dx - 2 * dn * n[0], dy - 2 * dn * n[1], abs(dz - 2 * dn * n[2]))
            shade(caster, Hit(), r, refl, FloatArray(3), depth + 1, pixelSeed)
        } else sky(dx, dy, -dz, refl)
        // Through the water to the bed, tinted by the distance travelled.
        val bed = FloatArray(3)
        val under = Hit()
        val deep = if (caster.trace(hx, hy, hz - 0.01f, dx, dy, dz, 400f, skipWater = true, hit = under)) {
            val bhx = hx + dx * under.t; val bhy = hy + dy * under.t; val bhz = hz + dz * under.t
            surface(caster, under, bhx, bhy, bhz, if (under.axis == 0) under.sign.toFloat() else 0f, if (under.axis == 1) under.sign.toFloat() else 0f, if (under.axis == 2) under.sign.toFloat() else 0f, bed, FloatArray(3), pixelSeed)
            under.t
        } else 400f
        val absorb = floatArrayOf(0.09f, 0.035f, 0.02f)
        val wc = linear[scene.water.toInt()]
        for (k in 0..2) {
            val tr = exp(-absorb[k] * deep)
            bed[k] = bed[k] * tr + wc[k] * (1f - tr) * (light.ambient * 0.9f + light.sun[k] * 0.12f)
        }
        val fres = min(0.6f, 0.03f + 0.97f * (1f + dn).coerceIn(0f, 1f).pow(5))
        for (k in 0..2) out[k] = bed[k] * (1f - fres) + refl[k] * fres * (0.55f + 0.45f * wc[k] / wc[2])
        // Sun glint.
        val hv = norm(floatArrayOf(sunDir[0] - dx, sunDir[1] - dy, sunDir[2] - dz))
        val spec = (hv[0] * n[0] + hv[1] * n[1] + hv[2] * n[2]).coerceAtLeast(0f).pow(180) * 6f
        for (k in 0..2) out[k] += light.sun[k] * spec
    }

    /** Minecraft-style corner occlusion, interpolated across the face so creases are soft. */
    private fun cornerAO(hit: Hit, fu: Float, fv: Float): Float {
        val a = hit.axis; val s = hit.sign
        val bx = hit.x + if (a == 0) s else 0; val by = hit.y + if (a == 1) s else 0; val bz = hit.z + if (a == 2) s else 0
        fun occ(du: Int, dv: Int): Int {
            val x = bx + when (a) { 0 -> 0; else -> du }
            val y = by + when (a) { 0 -> du; 1 -> 0; else -> dv }
            val z = bz + when (a) { 2 -> 0; else -> dv }
            return if (scene.solid(x, y, z)) 1 else 0
        }
        fun vertex(su: Int, sv: Int): Float {
            val s1 = occ(su, 0); val s2 = occ(0, sv); val c = occ(su, sv)
            return if (s1 == 1 && s2 == 1) 0f else (3 - s1 - s2 - c) / 3f
        }
        val v00 = vertex(-1, -1); val v10 = vertex(1, -1); val v01 = vertex(-1, 1); val v11 = vertex(1, 1)
        val ao = (v00 * (1 - fu) + v10 * fu) * (1 - fv) + (v01 * (1 - fu) + v11 * fu) * fv
        return 0.35f + 0.65f * ao
    }

    /** A few short cosine-weighted rays: occlusion at the scale of alleys and eaves, not just creases. */
    private fun hemisphereAO(caster: RayCaster, hit: Hit, hx: Float, hy: Float, hz: Float, nx: Float, ny: Float, nz: Float, pixelSeed: Int): Float {
        val rays = 4
        var open = 0
        val h = Hit()
        val tangent = if (abs(nz) > 0.5f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
        val n = floatArrayOf(nx, ny, nz)
        val b1 = norm(cross(n, tangent)); val b2 = cross(n, b1)
        for (i in 0 until rays) {
            val r1 = (hash3(pixelSeed, i, 17) + i) / rays
            val r2 = hash3(pixelSeed, i, 29)
            val phi = 2f * PI.toFloat() * r2
            val rr = sqrt(r1)
            val lx = rr * cos(phi); val ly = rr * sin(phi); val lz = sqrt(max(0f, 1f - r1))
            val d0 = b1[0] * lx + b2[0] * ly + n[0] * lz
            val d1 = b1[1] * lx + b2[1] * ly + n[1] * lz
            val d2 = b1[2] * lx + b2[2] * ly + n[2] * lz
            if (!caster.trace(hx + nx * 0.02f, hy + ny * 0.02f, hz + nz * 0.02f, d0, d1, d2, 22f, true, h)) open++
        }
        return 0.45f + 0.55f * open / rays
    }

    private fun faceUV(axis: Int, x: Float, y: Float, z: Float): Pair<Float, Float> = when (axis) {
        0 -> Pair(frac(y), frac(z))
        1 -> Pair(frac(x), frac(z))
        else -> Pair(frac(x), frac(y))
    }

    private fun sky(dx: Float, dy: Float, dz: Float, out: FloatArray) {
        val h = dz.coerceIn(-1f, 1f)
        val t = (h.coerceAtLeast(0f)).pow(0.45f)
        for (k in 0..2) out[k] = light.skyHorizon[k] * (1f - t) + light.skyZenith[k] * t
        // Below the horizon is land beyond the generated box: a hazy distant ground, not an empty void.
        if (h < 0f) {
            val g = (-h * 6f).coerceIn(0f, 1f)
            for (k in 0..2) out[k] = out[k] * (1f - g * 0.55f) + DISTANT_GROUND[k] * light.ambient * 1.4f * g * 0.55f
        }
        val sd = (dx * sunDir[0] + dy * sunDir[1] + dz * sunDir[2]).coerceAtLeast(0f)
        if (!light.night) {
            for (k in 0..2) out[k] += light.sun[k] * (sd.pow(900) * 30f + sd.pow(12) * 0.25f)
        } else {
            // A moon and a scatter of stars.
            for (k in 0..2) out[k] += 0.9f * sd.pow(4000) * 40f
            if (dz > 0.1f) {
                val sx = floor(dx * 900).toInt(); val sy = floor(dy * 900).toInt(); val sz = floor(dz * 900).toInt()
                if (hash3(sx, sy, sz) > 0.9985f) for (k in 0..2) out[k] += 0.6f
            }
        }
    }

    private fun fog(out: FloatArray, dx: Float, dy: Float, dz: Float, t: Float) {
        val f = 1f - exp(-max(0f, t - fogBias - light.fogStart) * light.fogDensity)
        if (f <= 0f) return
        val sky = FloatArray(3)
        sky(dx, dy, max(dz, 0.02f) * 0.3f, sky)
        for (k in 0..2) out[k] = out[k] * (1f - f) + sky[k] * f * 0.9f
    }

    private fun bloom(hdr: FloatArray, glow: FloatArray, w: Int, h: Int) {
        // Two separable box blurs approximate a gaussian; cheap and plenty for a glow.
        var src = glow
        repeat(2) {
            src = blur(src, w, h, 9, true)
            src = blur(src, w, h, 9, false)
        }
        for (i in hdr.indices) hdr[i] += src[i] * 0.45f
    }

    private fun blur(src: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean): FloatArray {
        val dst = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        IntStream.range(0, if (horizontal) h else w).parallel().forEach { line ->
            val len = if (horizontal) w else h
            for (k in 0..2) {
                var sum = 0f
                fun idx(p: Int): Int { val q = p.coerceIn(0, len - 1); return (if (horizontal) line * w + q else q * w + line) * 3 + k }
                for (p in -r..r) sum += src[idx(p)]
                for (p in 0 until len) {
                    dst[idx(p)] = sum * norm
                    sum += src[idx(p + r + 1)] - src[idx(p - r)]
                }
            }
        }
        return dst
    }

    private fun tonemap(hdr: FloatArray, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            // Gentle vignette.
            val vx = x.toFloat() / w - 0.5f; val vy = y.toFloat() / h - 0.5f
            val vig = 1f - (vx * vx + vy * vy) * 0.45f
            var rgb = 0
            for (k in 0..2) {
                val c = hdr[i + k] * light.exposure * vig
                val a = (c * (2.51f * c + 0.03f)) / (c * (2.43f * c + 0.59f) + 0.14f) // ACES fit
                val g = a.coerceIn(0f, 1f).pow(1f / 2.2f)
                rgb = (rgb shl 8) or (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            img.setRGB(x, y, rgb)
        }
        return img
    }

    private fun srgb(c: Int): Float = (c / 255f).pow(2.2f)
    private fun frac(v: Float) = v - floor(v)

    private fun vnoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val a = hash3(xi, yi, 0); val b = hash3(xi + 1, yi, 0); val c = hash3(xi, yi + 1, 0); val d = hash3(xi + 1, yi + 1, 0)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    /** Street lamps as unshadowed point lights, bucketed so each point only asks the few nearby. */
    private class LampGrid(lamps: List<IntArray>) {
        private val cells = HashMap<Long, MutableList<IntArray>>()
        init { for (l in lamps) cells.getOrPut(key(l[0] shr 5, l[1] shr 5)) { ArrayList() } += l }
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        fun illuminate(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, out: FloatArray, base: FloatArray) {
            val cx = floor(x).toInt() shr 5; val cy = floor(y).toInt() shr 5
            for (gy in cy - 1..cy + 1) for (gx in cx - 1..cx + 1) {
                val list = cells[key(gx, gy)] ?: continue
                for (l in list) {
                    val lx = l[0] + 0.5f - x; val ly = l[1] + 0.5f - y; val lz = l[2] - 0.5f - z
                    val d2 = lx * lx + ly * ly + lz * lz
                    if (d2 > 48f * 48f) continue
                    val d = sqrt(d2)
                    val ndl = (nx * lx + ny * ly + nz * lz) / d
                    if (ndl <= 0f) continue
                    val fall = 260f / (d2 + 30f) * ndl
                    out[0] += base[0] * fall * 1.0f; out[1] += base[1] * fall * 0.78f; out[2] += base[2] * fall * 0.5f
                }
            }
        }
    }

    companion object {
        private val DISTANT_GROUND = floatArrayOf(0.16f, 0.2f, 0.12f)

        fun hash3(x: Int, y: Int, z: Int): Float {
            var h = x * 374761393 + y * 668265263 + z * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }
    }
}
