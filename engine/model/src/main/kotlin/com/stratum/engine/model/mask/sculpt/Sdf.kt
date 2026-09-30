package com.stratum.engine.model.mask.sculpt

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Signed distance shapes: negative inside, positive outside, about the
 * distance to the surface. A mask is carved from them the way a carver
 * works a block: forms added and blended in, forms cut away.
 *
 * Every shape carries its bounds, so a point far from a feature never pays
 * to evaluate it: a sculpture of a hundred parts costs about what the few
 * parts near each point cost.
 */
abstract class Shape {
    /** Bounds, in model units. */
    var x0 = 0f; var y0 = 0f; var z0 = 0f; var x1 = 0f; var y1 = 0f; var z1 = 0f

    abstract fun d(x: Float, y: Float, z: Float): Float

    protected fun bound(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float) {
        x0 = cx - rx; x1 = cx + rx; y0 = cy - ry; y1 = cy + ry; z0 = cz - rz; z1 = cz + rz
    }

    /** A lower bound on the distance to this shape: the distance to its box. */
    fun boxDistance(x: Float, y: Float, z: Float): Float {
        val dx = max(max(x0 - x, x - x1), 0f); val dy = max(max(y0 - y, y - y1), 0f); val dz = max(max(z0 - z, z - z1), 0f)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

class Sphere(val cx: Float, val cy: Float, val cz: Float, val r: Float) : Shape() {
    init { bound(cx, cy, cz, r, r, r) }
    override fun d(x: Float, y: Float, z: Float): Float {
        val dx = x - cx; val dy = y - cy; val dz = z - cz
        return sqrt(dx * dx + dy * dy + dz * dz) - r
    }
}

/** An ellipsoid, by the usual close approximation to its distance. */
class Ellipsoid(val cx: Float, val cy: Float, val cz: Float, val rx: Float, val ry: Float, val rz: Float) : Shape() {
    init { bound(cx, cy, cz, rx, ry, rz) }
    override fun d(x: Float, y: Float, z: Float): Float {
        val px = (x - cx) / rx; val py = (y - cy) / ry; val pz = (z - cz) / rz
        val k0 = sqrt(px * px + py * py + pz * pz)
        val qx = px / rx; val qy = py / ry; val qz = pz / rz
        val k1 = sqrt(qx * qx + qy * qy + qz * qz)
        return if (k1 < 1e-9f) -min(rx, min(ry, rz)) else k0 * (k0 - 1f) / k1
    }
}

/** A capsule tapering from radius [ra] at a to [rb] at b: a horn's segment, a nose, a braid. */
class RoundCone(
    val ax: Float, val ay: Float, val az: Float, val bx: Float, val by: Float, val bz: Float, val ra: Float, val rb: Float,
) : Shape() {
    private val bax = bx - ax; private val bay = by - ay; private val baz = bz - az
    private val l2 = bax * bax + bay * bay + baz * baz
    private val rr = ra - rb
    private val a2 = l2 - rr * rr
    private val il2 = 1f / max(l2, 1e-12f)

    init {
        val r = max(ra, rb)
        x0 = min(ax, bx) - r; x1 = max(ax, bx) + r; y0 = min(ay, by) - r; y1 = max(ay, by) + r; z0 = min(az, bz) - r; z1 = max(az, bz) + r
    }

    override fun d(x: Float, y: Float, z: Float): Float {
        // After Inigo Quilez's exact round cone.
        val pax = x - ax; val pay = y - ay; val paz = z - az
        val yv = pax * bax + pay * bay + paz * baz
        val zv = yv - l2
        val cx = pax * l2 - bax * yv; val cy = pay * l2 - bay * yv; val cz = paz * l2 - baz * yv
        val x2 = cx * cx + cy * cy + cz * cz
        val y2 = yv * yv * l2
        val z2 = zv * zv * l2
        val k = Math.signum(rr) * rr * rr * x2
        if (Math.signum(zv) * a2 * z2 > k) return sqrt(x2 + z2) * il2 - rb
        if (Math.signum(yv) * a2 * y2 < k) return sqrt(x2 + y2) * il2 - ra
        return (sqrt(x2 * a2 * il2) + yv * rr) * il2 - ra
    }
}

/** A box with rounded edges, turned into any frame by its axes (unit vectors). */
class RoundBox(
    val cx: Float, val cy: Float, val cz: Float, val hx: Float, val hy: Float, val hz: Float, val round: Float,
    private val ux: FloatArray = floatArrayOf(1f, 0f, 0f), private val uy: FloatArray = floatArrayOf(0f, 1f, 0f), private val uz: FloatArray = floatArrayOf(0f, 0f, 1f),
) : Shape() {
    init {
        val ex = abs(ux[0]) * hx + abs(uy[0]) * hy + abs(uz[0]) * hz + round
        val ey = abs(ux[1]) * hx + abs(uy[1]) * hy + abs(uz[1]) * hz + round
        val ez = abs(ux[2]) * hx + abs(uy[2]) * hy + abs(uz[2]) * hz + round
        bound(cx, cy, cz, ex, ey, ez)
    }

    override fun d(x: Float, y: Float, z: Float): Float {
        val px = x - cx; val py = y - cy; val pz = z - cz
        val lx = abs(px * ux[0] + py * ux[1] + pz * ux[2]) - hx
        val ly = abs(px * uy[0] + py * uy[1] + pz * uy[2]) - hy
        val lz = abs(px * uz[0] + py * uz[1] + pz * uz[2]) - hz
        val ox = max(lx, 0f); val oy = max(ly, 0f); val oz = max(lz, 0f)
        return sqrt(ox * ox + oy * oy + oz * oz) + min(max(lx, max(ly, lz)), 0f) - round
    }

    companion object {
        /** Axes turned by [yaw] about z, then [pitch] about the new x, then [roll] about the new y. */
        fun axes(yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f): Array<FloatArray> {
            val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
            val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
            val cr = kotlin.math.cos(roll); val sr = kotlin.math.sin(roll)
            // Columns of R = Rz(yaw) * Rx(pitch) * Ry(roll).
            val m = arrayOf(
                floatArrayOf(cy * cr - sy * sp * sr, -sy * cp, cy * sr + sy * sp * cr),
                floatArrayOf(sy * cr + cy * sp * sr, cy * cp, sy * sr - cy * sp * cr),
                floatArrayOf(-cp * sr, sp, cp * cr),
            )
            return arrayOf(floatArrayOf(m[0][0], m[1][0], m[2][0]), floatArrayOf(m[0][1], m[1][1], m[2][1]), floatArrayOf(m[0][2], m[1][2], m[2][2]))
        }
    }
}

/** A capped cylinder along y: a tube eye, a mouth spout, a drum tier (turned by [RoundBox.axes] if not). */
class Cylinder(val cx: Float, val cy: Float, val cz: Float, val r: Float, val halfLength: Float, val round: Float = 0f, val alongZ: Boolean = false) : Shape() {
    init { if (alongZ) bound(cx, cy, cz, r + round, r + round, halfLength + round) else bound(cx, cy, cz, r + round, halfLength + round, r + round) }
    override fun d(x: Float, y: Float, z: Float): Float {
        val px = x - cx; val py = y - cy; val pz = z - cz
        val radial = if (alongZ) sqrt(px * px + py * py) else sqrt(px * px + pz * pz)
        val axial = if (alongZ) abs(pz) else abs(py)
        val dx = radial - r + round; val dy = axial - halfLength + round
        return min(max(dx, dy), 0f) + sqrt(max(dx, 0f) * max(dx, 0f) + max(dy, 0f) * max(dy, 0f)) - round
    }
}

/** A ring lying in a plane through its centre with normal [n]: an earring, a coil, a tier's rim. */
class Torus(val cx: Float, val cy: Float, val cz: Float, val major: Float, val minor: Float, n: FloatArray) : Shape() {
    private val nx: Float; private val ny: Float; private val nz: Float
    init {
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]); nx = n[0] / l; ny = n[1] / l; nz = n[2] / l
        val e = major + minor; bound(cx, cy, cz, e, e, e)
    }
    override fun d(x: Float, y: Float, z: Float): Float {
        val px = x - cx; val py = y - cy; val pz = z - cz
        val h = px * nx + py * ny + pz * nz
        val qx = px - h * nx; val qy = py - h * ny; val qz = pz - h * nz
        val q = sqrt(qx * qx + qy * qy + qz * qz) - major
        return sqrt(q * q + h * h) - minor
    }
}

/** A shape with a field added to its distance, within [amplitude]: grooves, striations, a bark of carving. */
class Displaced(val base: Shape, val amplitude: Float, val field: (Float, Float, Float) -> Float) : Shape() {
    init { x0 = base.x0 - amplitude; x1 = base.x1 + amplitude; y0 = base.y0 - amplitude; y1 = base.y1 + amplitude; z0 = base.z0 - amplitude; z1 = base.z1 + amplitude }
    override fun d(x: Float, y: Float, z: Float): Float {
        val b = base.d(x, y, z)
        return if (abs(b) > amplitude * 3f) b else b + field(x, y, z)
    }
}

/** Any distance function, with the bounds it lives in. */
class Custom(bx0: Float, by0: Float, bz0: Float, bx1: Float, by1: Float, bz1: Float, val f: (Float, Float, Float) -> Float) : Shape() {
    init { x0 = bx0; y0 = by0; z0 = bz0; x1 = bx1; y1 = by1; z1 = bz1 }
    override fun d(x: Float, y: Float, z: Float): Float = f(x, y, z)
}

/**
 * A sculpture: parts laid down in order, each added (smoothly blended by its
 * [Part.blend]) or cut away, each with the material its surface shows.
 */
class Sculpture {
    enum class Op { ADD, CUT, INTERSECT }

    class Part(val shape: Shape, val op: Op, val blend: Float, val material: Int)

    val parts = ArrayList<Part>()

    fun add(shape: Shape, material: Int, blend: Float = 0f) { parts += Part(shape, Op.ADD, blend, material) }
    fun cut(shape: Shape, material: Int, blend: Float = 0f) { parts += Part(shape, Op.CUT, blend, material) }
    fun keepInside(shape: Shape, material: Int, blend: Float = 0f) { parts += Part(shape, Op.INTERSECT, blend, material) }

    /** Bounds of everything added. */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in parts) if (p.op == Op.ADD) {
            b[0] = min(b[0], p.shape.x0); b[1] = min(b[1], p.shape.y0); b[2] = min(b[2], p.shape.z0)
            b[3] = max(b[3], p.shape.x1); b[4] = max(b[4], p.shape.y1); b[5] = max(b[5], p.shape.z1)
        }
        return b
    }

    fun d(x: Float, y: Float, z: Float): Float = eval(x, y, z, null)

    /** The distance at a point; with [material], also which part's surface is nearest there. */
    fun eval(x: Float, y: Float, z: Float, material: IntArray?): Float {
        var d = FAR
        var mat = -1
        for (p in parts) {
            val s = p.shape
            when (p.op) {
                Op.ADD -> {
                    if (s.boxDistance(x, y, z) - p.blend > d) continue
                    val e = s.d(x, y, z)
                    if (p.blend > 0f) {
                        // Smooth minimum: the new form grows out of what is there.
                        val h = (0.5f + 0.5f * (d - e) / p.blend).coerceIn(0f, 1f)
                        val nd = d + (e - d) * h - p.blend * h * (1f - h)
                        if (h > 0.5f) mat = p.material
                        d = nd
                    } else if (e < d) { d = e; mat = p.material }
                }
                Op.CUT -> {
                    if (s.boxDistance(x, y, z) - p.blend > -d) continue
                    val e = -s.d(x, y, z)
                    if (p.blend > 0f) {
                        // Smooth subtraction: a cut with softened lips.
                        val h = (0.5f - 0.5f * (d - e) / p.blend).coerceIn(0f, 1f)
                        val nd = d + (e - d) * h + p.blend * h * (1f - h)
                        if (h > 0.5f) mat = p.material
                        d = nd
                    } else if (e > d) { d = e; mat = p.material }
                }
                Op.INTERSECT -> {
                    val e = s.d(x, y, z)
                    if (p.blend > 0f) {
                        val h = (0.5f - 0.5f * (d - e) / p.blend).coerceIn(0f, 1f)
                        d = d + (e - d) * h + p.blend * h * (1f - h)
                        if (h > 0.5f && p.material >= 0) mat = p.material
                    } else if (e > d) { d = e; if (p.material >= 0) mat = p.material }
                }
            }
        }
        if (material != null) material[0] = mat
        return d
    }

    companion object { const val FAR = 1e3f }
}
