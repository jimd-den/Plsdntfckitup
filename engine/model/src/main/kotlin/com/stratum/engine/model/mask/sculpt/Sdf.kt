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
internal const val SQRT_HALF = 0.70710677f

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

/** An ellipsoid, by the usual close approximation to its distance, tilted by [roll] in the face's plane (x toward z). */
class Ellipsoid(val cx: Float, val cy: Float, val cz: Float, val rx: Float, val ry: Float, val rz: Float, roll: Float = 0f) : Shape() {
    private val cr = kotlin.math.cos(roll); private val sr = kotlin.math.sin(roll)
    init { if (roll == 0f) bound(cx, cy, cz, rx, ry, rz) else max(rx, rz).let { bound(cx, cy, cz, it, ry, it) } }
    override fun d(x: Float, y: Float, z: Float): Float {
        val ux = x - cx; val uz = z - cz
        val px = (ux * cr + uz * sr) / rx; val py = (y - cy) / ry; val pz = (uz * cr - ux * sr) / rz
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

/**
 * A carver's wedge: a bar from a to b whose cross-section is a diamond, [ra]
 * across at a narrowing (or widening) to [rb] at b, turned so one corner faces
 * [up], its width [aspect] times its height. Added, it stands as a
 * knife-edged ridge -- a brow, a nose's bridge; cut, it is a V-shaped incision.
 */
class Wedge(
    val ax: Float, val ay: Float, val az: Float, val bx: Float, val by: Float, val bz: Float, val ra: Float, val rb: Float,
    upX: Float = 0f, upY: Float = 1f, upZ: Float = 0f, val aspect: Float = 1f,
) : Shape() {
    private val norm = 1f / sqrt(1f + 1f / (aspect * aspect))
    private val len: Float
    private val tx: Float; private val ty: Float; private val tz: Float
    private val ux: Float; private val uy: Float; private val uz: Float
    private val vx: Float; private val vy: Float; private val vz: Float

    init {
        var dx = bx - ax; var dy = by - ay; var dz = bz - az
        len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        dx /= len; dy /= len; dz /= len
        tx = dx; ty = dy; tz = dz
        // The corner that faces out, square to the bar.
        val k = upX * tx + upY * ty + upZ * tz
        var px = upX - k * tx; var py = upY - k * ty; var pz = upZ - k * tz
        var pl = sqrt(px * px + py * py + pz * pz)
        if (pl < 1e-5f) { px = 0f; py = 0f; pz = 1f; pl = 1f }
        ux = px / pl; uy = py / pl; uz = pz / pl
        vx = ty * uz - tz * uy; vy = tz * ux - tx * uz; vz = tx * uy - ty * ux
        val r = max(ra, rb) * max(1f, aspect)
        x0 = min(ax, bx) - r; x1 = max(ax, bx) + r; y0 = min(ay, by) - r; y1 = max(ay, by) + r; z0 = min(az, bz) - r; z1 = max(az, bz) + r
    }

    override fun d(x: Float, y: Float, z: Float): Float {
        val px = x - ax; val py = y - ay; val pz = z - az
        val along = px * tx + py * ty + pz * tz
        val t = (along / len).coerceIn(0f, 1f)
        val r = ra + (rb - ra) * t
        val u = abs(px * ux + py * uy + pz * uz); val v = abs(px * vx + py * vy + pz * vz)
        val side = (u + v / aspect - r) * norm
        val ends = max(-along, along - len)
        return max(side, ends)
    }
}

/**
 * A [Wedge] swept along a polyline ([points], x y z triples), [radii] at each
 * point, its joints mitred so the knife edge runs unbroken round every turn:
 * a brow ridge, a heart line, an incised mark.
 */
class Ridge(private val points: FloatArray, private val radii: FloatArray, val aspect: Float = 1f, upX: Float = 0f, upY: Float = 1f, upZ: Float = 0f) : Shape() {
    private val n = points.size / 3
    private val segs = n - 1
    private val norm = 1f / sqrt(1f + 1f / (aspect * aspect))
    // Per segment: unit tangent, length, the out and side axes; per joint: the mitre plane's normal.
    private val t = FloatArray(segs * 3); private val len = FloatArray(segs)
    private val u = FloatArray(segs * 3); private val v = FloatArray(segs * 3)
    private val m = FloatArray(n * 3)

    init {
        require(n >= 2)
        for (i in 0 until segs) {
            var dx = points[i * 3 + 3] - points[i * 3]; var dy = points[i * 3 + 4] - points[i * 3 + 1]; var dz = points[i * 3 + 5] - points[i * 3 + 2]
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
            dx /= l; dy /= l; dz /= l
            t[i * 3] = dx; t[i * 3 + 1] = dy; t[i * 3 + 2] = dz; len[i] = l
            val k = upX * dx + upY * dy + upZ * dz
            var px = upX - k * dx; var py = upY - k * dy; var pz = upZ - k * dz
            var pl = sqrt(px * px + py * py + pz * pz)
            if (pl < 1e-5f) { px = 0f; py = 0f; pz = 1f; pl = 1f }
            px /= pl; py /= pl; pz /= pl
            u[i * 3] = px; u[i * 3 + 1] = py; u[i * 3 + 2] = pz
            v[i * 3] = dy * pz - dz * py; v[i * 3 + 1] = dz * px - dx * pz; v[i * 3 + 2] = dx * py - dy * px
        }
        for (j in 0 until n) {
            val a = (j - 1).coerceAtLeast(0); val b = j.coerceAtMost(segs - 1)
            var mx = t[a * 3] + t[b * 3]; var my = t[a * 3 + 1] + t[b * 3 + 1]; var mz = t[a * 3 + 2] + t[b * 3 + 2]
            val l = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-6f)
            mx /= l; my /= l; mz /= l
            m[j * 3] = mx; m[j * 3 + 1] = my; m[j * 3 + 2] = mz
        }
        val r = (radii.maxOrNull() ?: 0f) * max(1f, aspect)
        x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE; z0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE; z1 = -Float.MAX_VALUE
        for (j in 0 until n) {
            x0 = min(x0, points[j * 3] - r); x1 = max(x1, points[j * 3] + r)
            y0 = min(y0, points[j * 3 + 1] - r); y1 = max(y1, points[j * 3 + 1] + r)
            z0 = min(z0, points[j * 3 + 2] - r); z1 = max(z1, points[j * 3 + 2] + r)
        }
    }

    override fun d(x: Float, y: Float, z: Float): Float {
        // The segment whose mitred slab holds the point measures it, so neighbours meet without a seam;
        // outside every slab (past an end, or round the outside of a turn), the nearest capped segment does.
        var inSlab = Float.MAX_VALUE; var capped = Float.MAX_VALUE
        for (i in 0 until segs) {
            val ax = points[i * 3]; val ay = points[i * 3 + 1]; val az = points[i * 3 + 2]
            val px = x - ax; val py = y - ay; val pz = z - az
            val along = px * t[i * 3] + py * t[i * 3 + 1] + pz * t[i * 3 + 2]
            val f = (along / len[i]).coerceIn(0f, 1f)
            val r = radii[i] + (radii[i + 1] - radii[i]) * f
            val uu = abs(px * u[i * 3] + py * u[i * 3 + 1] + pz * u[i * 3 + 2])
            val vv = abs(px * v[i * 3] + py * v[i * 3 + 1] + pz * v[i * 3 + 2])
            val side = (uu + vv / aspect - r) * norm
            val c0 = -(px * m[i * 3] + py * m[i * 3 + 1] + pz * m[i * 3 + 2])
            val bx = x - points[i * 3 + 3]; val by = y - points[i * 3 + 4]; val bz = z - points[i * 3 + 5]
            val c1 = bx * m[(i + 1) * 3] + by * m[(i + 1) * 3 + 1] + bz * m[(i + 1) * 3 + 2]
            if (c0 <= 0f && c1 <= 0f) { if (side < inSlab) inSlab = side }
            else { val d = max(side, max(if (i == 0) c0 else c0 * 0.5f, if (i == segs - 1) c1 else c1 * 0.5f)); if (d < capped) capped = d }
        }
        return min(inSlab, capped)
    }
}

/**
 * A carved boss: domed [bulge] high over an oval [rx] by [rz] facing +y, its
 * rim cut sharp, turned by [roll] in the face's plane. Lids, lips, keloids and
 * cheek pads, as a carver leaves them.
 */
class Cap(val cx: Float, val cy: Float, val cz: Float, val rx: Float, val rz: Float, val bulge: Float, roll: Float = 0f, val depth: Float = 0.03f) : Shape() {
    private val cr = kotlin.math.cos(roll); private val sr = kotlin.math.sin(roll)
    init { val r = max(rx, rz); bound(cx, cy + (bulge - depth) / 2f, cz, r, (bulge + depth) / 2f + 0.002f, r) }
    override fun d(x: Float, y: Float, z: Float): Float {
        val ux = x - cx; val uz = z - cz
        val qx = (ux * cr + uz * sr) / rx; val qz = (uz * cr - ux * sr) / rz
        val q2 = qx * qx + qz * qz
        val top = (y - cy) - bulge * (1f - q2)
        val rim = (sqrt(q2) - 1f) * min(rx, rz)
        val bottom = (cy - depth) - y
        return max(max(top * 0.8f, rim), bottom)
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

    // Joins are chamfered, not melted: where two forms meet, a flat 45-degree
    // facet as a chisel leaves it, [Part.blend] wide, instead of a clay fillet.

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
                    if (e < d) mat = p.material
                    d = if (p.blend > 0f) min(min(d, e), (d + e - p.blend * CHAMFER) * SQRT_HALF) else min(d, e)
                }
                Op.CUT -> {
                    if (s.boxDistance(x, y, z) - p.blend > -d) continue
                    val e = -s.d(x, y, z)
                    // A cut: its lip bevelled as a knife takes it.
                    if (e > d) mat = p.material
                    d = if (p.blend > 0f) max(max(d, e), (d + e + p.blend * CHAMFER) * SQRT_HALF) else max(d, e)
                }
                Op.INTERSECT -> {
                    val e = s.d(x, y, z)
                    if (e > d && p.material >= 0) mat = p.material
                    d = if (p.blend > 0f) max(max(d, e), (d + e + p.blend * CHAMFER) * SQRT_HALF) else max(d, e)
                }
            }
        }
        if (material != null) material[0] = mat
        return d
    }

    companion object {
        const val FAR = 1e3f
        /** How much of a part's blend width its chamfer takes. */
        const val CHAMFER = 0.6f
    }
}
