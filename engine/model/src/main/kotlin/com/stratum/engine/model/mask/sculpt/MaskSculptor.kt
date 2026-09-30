package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.model.mask.sculpt.Anatomy.Adorn
import com.stratum.engine.model.mask.sculpt.Anatomy.Beard
import com.stratum.engine.model.mask.sculpt.Anatomy.Brow
import com.stratum.engine.model.mask.sculpt.Anatomy.Coiffure
import com.stratum.engine.model.mask.sculpt.Anatomy.Dial
import com.stratum.engine.model.mask.sculpt.Anatomy.Crown
import com.stratum.engine.model.mask.sculpt.Anatomy.Ears
import com.stratum.engine.model.mask.sculpt.Anatomy.Eyes
import com.stratum.engine.model.mask.sculpt.Anatomy.Finish
import com.stratum.engine.model.mask.sculpt.Anatomy.Form
import com.stratum.engine.model.mask.sculpt.Anatomy.Mouth
import com.stratum.engine.model.mask.sculpt.Anatomy.Nose
import com.stratum.engine.model.mask.sculpt.Anatomy.Outline
import com.stratum.engine.model.mask.sculpt.Anatomy.Pattern
import com.stratum.engine.model.mask.sculpt.Anatomy.Scar
import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Carves a [MaskSpec] into a mask the game can fly: a [SpiritMesh].
 *
 * The face is a signed distance sculpture. A shell in the tradition's
 * outline has its front flattened into a face plane. Forms are added and
 * blended in (brows, lids, nose, lips, ears, keloids, coiffure, crests) and
 * cut away (eye holes, mouths, nostrils, ichi and striations). It is meshed
 * by surface nets at the detail asked for.
 *
 * Horns, raffia and bead strands are swept or placed as their own geometry,
 * cleaner than a field for long, thin forms.
 *
 * Every vertex is then coloured from what it is and where it is:
 * - the finish (oiled, blackened, kaolin-whitened and worn back to the wood,
 *   camwood-rubbed, painted, cast brass going green);
 * - the tradition's painted patterns;
 * - wood grain running down the mask;
 * - darkening in its cavities.
 *
 * Eye holes glow with the spirit's eye light, and carved lines breathe with
 * its line glow.
 */
object MaskSculptor {

    /** How finely a mask is carved: a grid pitch, in face units. */
    enum class Detail(val cell: Float) { SHOWCASE(0.0055f), HIGH(0.008f), GAME(0.013f), FAR(0.02f) }

    private val cache = object : LinkedHashMap<String, SpiritMesh>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpiritMesh>?): Boolean = size > 24
    }

    fun cached(spec: MaskSpec, detail: Detail = Detail.GAME): SpiritMesh {
        val key = spec.hashCode().toString() + detail.name
        synchronized(cache) { cache[key]?.let { return it } }
        val mesh = carve(spec, detail)
        synchronized(cache) { cache[key] = mesh }
        return mesh
    }

    fun carve(spec: MaskSpec, detail: Detail = Detail.GAME): SpiritMesh = Carver(spec, detail).run()

    // Materials: what a surface is, which decides its colour and its light.
    internal const val FACE = 0; internal const val HAIR = 1; internal const val EYE = 2; internal const val MOUTH = 3; internal const val TEETH = 4
    internal const val CREST = 6; internal const val SCAR = 10; internal const val LIP = 11; internal const val PLANK = 13
    internal const val TIER = 20; internal const val STUD = 30; internal const val COWRIE = 31

    private class Carver(val s: MaskSpec, val detail: Detail) {
        val r = Random(s.seed * 31 + 7)
        val sc = Sculpture()
        val extra = TubeMesh()

        // ---- proportions --------------------------------------------------------------
        /** A dial as a factor: [lo] at 0, 1 where the tradition carves it, [hi] at 1. */
        fun k(d: Dial, lo: Float, hi: Float): Float {
            val v = s.dial(d); val m = d.default
            return if (v <= m) lo + (1f - lo) * (if (m > 0f) v / m else 1f) else 1f + (hi - 1f) * (v - m) / (1f - m)
        }

        /** A dial as an offset from where the tradition carves it, -0.5..0.5. */
        fun c(d: Dial): Float = s.dial(d) - d.default

        val asym = s.dial(Dial.ASYMMETRY)

        val hw = 0.22f + 0.12f * s.width * (if (s.outline == Outline.LONG) 0.8f else 1f)
        val hh = (0.36f + 0.16f * s.length) * (if (s.outline == Outline.ROUND) 0.88f else 1f)
        val helmet = s.form == Form.HELMET
        val dy = (if (helmet) hw * 1.05f else hw * 0.72f) * k(Dial.FACE_DEPTH, 0.75f, 1.3f)
        val eyeZ = hh * (0.08f - 0.24f * c(Dial.FOREHEAD))
        val browZ = eyeZ + hh * 0.2f
        val tipZ = eyeZ - hh * 0.36f * k(Dial.NOSE_LENGTH, 0.65f, 1.35f)
        val mouthZ = min(-hh * (0.56f - 0.3f * c(Dial.MOUTH_HEIGHT)), tipZ - hh * 0.12f)
        val ex = hw * 0.42f * k(Dial.EYE_SPACING, 0.7f, 1.3f)
        val jawK = k(Dial.JAW, 1.35f, 0.35f)

        /** The outline's width at height zn (-1 chin .. 1 crown), the jaw narrowed or squared by its dial. */
        fun taper(zn: Float): Float {
            val t = outlineTaper(zn)
            if (zn >= 0f) return t
            val a = -zn
            return (1f - (1f - t) * jawK - (jawK - 1f) * 0.18f * a * a).coerceIn(0.32f, 1.2f)
        }

        fun outlineTaper(zn: Float): Float {
            val a = abs(zn)
            return when (s.outline) {
                Outline.HEART, Outline.CONCAVE -> if (zn < 0f) 1f - 0.5f * a.pow(1.25f) else 1.04f
                Outline.SHIELD -> if (zn < 0f) 1f - 0.42f * a.pow(1.1f) else 1.06f
                Outline.LONG -> if (zn < 0f) 1f - 0.2f * a else 1f
                Outline.ROUND -> 1.08f
                Outline.SQUARE -> if (zn < 0f) 1f - 0.12f * a else 1f
                Outline.OVAL -> 1f
            }
        }

        /** The bare head: the tradition's outline, its front pressed flat into a face plane. */
        val shell: Shape = run {
            val square = s.outline == Outline.SQUARE
            val base = Custom(-hw * 1.2f, -dy, -hh, hw * 1.2f, dy, hh) { x, y, z ->
                val t = taper((z / hh).coerceIn(-1f, 1f))
                val xw = x / t
                if (square) {
                    // A super-ellipsoid: flat cheeks and a squared jaw.
                    val qx = abs(xw) / hw; val qy = abs(y) / dy; val qz = abs(z) / hh
                    val p = 4f
                    val k = (qx.pow(p) + qy.pow(2.2f) + qz.pow(p)).pow(1f / p)
                    (k - 1f) * min(hw, hh) * 0.9f * min(1f, t)
                } else ellipsoid(xw, y, z, hw, dy, hh) * min(1f, t)
            }
            base
        }

        val front0 = if (helmet) dy * 0.78f else dy * 0.62f

        /** The face plane: the front pressed flat, a little convex across, falling back at brow and chin. */
        fun facePlane(x: Float, z: Float): Float = front0 - 0.9f * k(Dial.CONVEXITY, 0.35f, 1.9f) * x * x - 0.25f * (z - eyeZ).let { it * it } * (if (z > browZ) 1.6f else 1f)

        fun run(): SpiritMesh {
            sculptHead()
            sculptFace()
            sculptCoiffure()
            sculptCrown()
            sculptAdornments()
            return assemble()
        }

        // ---- the head ------------------------------------------------------------------

        fun sculptHead() {
            val striated = Scar.STRIATIONS in s.scars
            val q = k(Dial.SCAR_DEPTH, 0.5f, 1.9f)
            val head = if (striated) Displaced(shell, 0.006f * q) { x, _, z -> 0.004f * q * (0.5f + 0.5f * sin(z * 150f + 3f * sin(x * 9f))) } else shell
            sc.add(head, FACE)
            // Press the front into a face plane, softly, so a rim runs round the face.
            val plane = Custom(-hw * 1.3f, -2f, -hh * 1.2f, hw * 1.3f, 2f, hh * 1.2f) { x, y, z -> y - facePlane(x, z) }
            sc.keepInside(plane, -1, 0.035f)
            if (!helmet) {
                // A face mask is a board: its back cut flat and hollowed for the wearer.
                sc.keepInside(Custom(-2f, -2f, -2f, 2f, 2f, 2f) { _, y, _ -> -(y + dy * 0.45f) }, -1, 0.03f)
            }
            if (s.outline == Outline.CONCAVE) {
                // The Fang face: a hollow scooped from brow to chin inside the heart.
                sc.cut(Ellipsoid(0f, front0 + hw * 0.18f, -hh * 0.22f, hw * 0.72f, hw * 0.3f, hh * 0.72f), FACE, 0.05f)
            }
        }

        // ---- finding the surface --------------------------------------------------------

        /** How far forward the carved face stands at (x, z): a march in from the front. */
        fun front(x: Float, z: Float): Float {
            var y = dy * 1.6f
            repeat(60) {
                val d = sc.d(x, y, z)
                if (d < 0.0006f) return y
                y -= max(d, 0.0015f)
                if (y < -dy * 2f) return front0
            }
            return y
        }

        // ---- the face -------------------------------------------------------------------

        fun sculptFace() {
            brow()
            ears()
            cheeks()
            eyes()
            nose()
            mouth()
            scars()
        }

        fun sides(f: (Float) -> Unit) { f(-1f); f(1f) }

        /** A chain of tapered segments through face points (x, z), standing [lift] off the surface. */
        fun chain(pts: FloatArray, r0: Float, r1: Float, lift: Float, add: Boolean, material: Int, blend: Float) {
            val n = pts.size / 2
            // Kept on the face, however the jaw and outline narrow it.
            fun onFace(x: Float, z: Float): Float { val lim = hw * 0.92f * taper((z / hh).coerceIn(-1f, 1f)); return x.coerceIn(-lim, lim) }
            var px = onFace(pts[0], pts[1]); var pz = pts[1]; var py = front(px, pz) + lift
            for (i in 1 until n) {
                val z = pts[i * 2 + 1]; val x = onFace(pts[i * 2], z); val y = front(x, z) + lift
                val ra = r0 + (r1 - r0) * (i - 1) / (n - 1); val rb = r0 + (r1 - r0) * i / (n - 1)
                val shape = RoundCone(px, py, pz, x, y, z, ra, rb)
                if (add) sc.add(shape, material, blend) else sc.cut(shape, material, blend)
                px = x; py = y; pz = z
            }
        }

        fun arc(x0: Float, z0: Float, xm: Float, zm: Float, x1: Float, z1: Float, n: Int = 8): FloatArray = FloatArray((n + 1) * 2) { k ->
            val t = (k / 2) / n.toFloat(); val u = 1 - t
            if (k % 2 == 0) u * u * x0 + 2 * u * t * xm + t * t * x1 else u * u * z0 + 2 * u * t * zm + t * t * z1
        }

        fun brow() {
            val bw = k(Dial.BROW_WEIGHT, 0.45f, 1.9f)
            when (s.brow) {
                Brow.NONE -> Unit
                Brow.ARCH -> sides { sd -> chain(arc(sd * (ex - hw * 0.28f), browZ - hh * 0.06f, sd * ex, browZ + hh * 0.04f, sd * (ex + hw * 0.36f), browZ - hh * 0.09f), 0.022f * bw, 0.014f * bw, 0.004f * bw, true, FACE, 0.022f) }
                Brow.HEART -> sides { sd ->
                    // The heart: from the temple over the eye to the top of the nose, then down the cheek to the chin.
                    chain(arc(sd * hw * 0.86f, eyeZ - hh * 0.05f, sd * ex * 1.1f, browZ + hh * 0.08f, 0f, browZ - hh * 0.02f), 0.012f * bw, 0.012f * bw, 0.002f, true, FACE, 0.016f)
                    chain(arc(sd * hw * 0.86f, eyeZ - hh * 0.05f, sd * hw * 0.8f, -hh * 0.55f, 0f, -hh * 0.93f), 0.01f * bw, 0.008f * bw, 0.001f, true, FACE, 0.016f)
                }
                Brow.SHELF -> {
                    val y = front(0f, browZ)
                    sc.add(RoundBox(0f, y - 0.004f, browZ, hw * 0.82f, 0.034f * bw, hh * 0.06f * bw, 0.02f), FACE, 0.028f)
                }
                Brow.BULGE -> sc.add(Ellipsoid(0f, front0 - hw * 0.12f, hh * 0.45f, hw * 0.82f, hw * 0.3f * bw, hh * 0.36f), FACE, 0.07f)
            }
        }

        fun ears() {
            val es = k(Dial.EAR_SIZE, 0.55f, 1.6f)
            when (s.ears) {
                Ears.NONE -> Unit
                Ears.SMALL -> sides { sd ->
                    sc.add(Ellipsoid(sd * hw * 0.98f, -dy * 0.05f, eyeZ - hh * 0.08f, hw * 0.09f * es, dy * 0.16f * es, hh * 0.14f * es), FACE, 0.02f)
                    sc.cut(Ellipsoid(sd * hw * (0.98f + 0.05f * es), -dy * 0.02f, eyeZ - hh * 0.08f, hw * 0.05f * es, dy * 0.08f * es, hh * 0.08f * es), SCAR, 0.01f)
                }
                Ears.ELEPHANT -> sides { sd ->
                    val cx = sd * (hw + hw * 0.6f * es)
                    sc.add(Ellipsoid(cx, -dy * 0.15f, eyeZ - hh * 0.05f, hw * 0.62f * es, 0.035f, hh * 0.62f * es), FACE, 0.05f)
                    sc.cut(Ellipsoid(cx, -dy * 0.15f + 0.03f, eyeZ - hh * 0.05f, hw * 0.48f * es, 0.02f, hh * 0.48f * es), FACE, 0.02f)
                }
            }
        }

        val eyeCentres = ArrayList<FloatArray>()

        /** Full cheeks swell out beside the nose; sunken ones are hollowed. */
        fun cheeks() {
            val f = c(Dial.CHEEKS) * 2f
            if (abs(f) < 0.05f) return
            sides { sd ->
                val x = sd * hw * 0.52f; val z = -hh * 0.14f
                val y = front(x, z)
                if (f > 0f) sc.add(Ellipsoid(x, y - 0.01f, z, hw * 0.26f, 0.02f + 0.035f * f, hh * 0.16f), FACE, 0.05f)
                else sc.cut(Ellipsoid(x, y + 0.012f, z - hh * 0.04f, hw * 0.22f, 0.012f - 0.03f * f, hh * 0.15f), FACE, 0.05f)
            }
        }

        fun eyes() {
            val lid = k(Dial.EYE_DEPTH, 0.4f, 1.9f)
            // Positive tilt lifts the outer corners.
            val tilt = c(Dial.EYE_TILT) * 0.9f
            sides { sd ->
                // An asymmetric face: one eye larger, higher and further out.
                val odd = if (sd > 0f) asym else 0f
                val sz = k(Dial.EYE_SIZE, 0.5f, 1.7f) * (1f + 0.35f * odd)
                val x = sd * ex + sd * odd * hw * 0.06f; val z = eyeZ + odd * hh * 0.07f
                val y = front(x, z)
                val er = tilt * sd
                val bx = RoundBox.axes(roll = -er)
                eyeCentres += floatArrayOf(x, y, z)
                when (s.eyes) {
                    Eyes.SLIT -> {
                        sc.add(Ellipsoid(x, y + 0.004f * lid, z + 0.006f, hw * 0.32f * sz, 0.04f * lid, hh * 0.1f * sz, er), FACE, 0.018f)
                        sc.cut(RoundBox(x, y + 0.03f, z, hw * 0.26f * sz, 0.06f + 0.03f * lid, 0.008f * sz, 0.005f, bx[0], bx[1], bx[2]), EYE)
                    }
                    Eyes.BEAN -> {
                        sc.add(Ellipsoid(x, y + 0.008f * lid, z, hw * 0.33f * sz, 0.05f * lid, hh * 0.12f * sz, er), FACE, 0.016f)
                        sc.cut(RoundBox(x, y + 0.045f, z, hw * 0.27f * sz, 0.07f + 0.03f * lid, 0.0075f * sz, 0.005f, bx[0], bx[1], bx[2]), EYE)
                    }
                    Eyes.TUBE -> {
                        sc.add(Cylinder(x, y + 0.045f * lid, z, hw * 0.2f * sz, 0.06f * lid, 0.014f), FACE, 0.02f)
                        sc.cut(Cylinder(x, y + 0.09f * lid, z, hw * 0.12f * sz, 0.1f * lid + 0.02f), EYE)
                    }
                    Eyes.ROUND -> sc.cut(Cylinder(x, y + 0.02f, z, hw * 0.17f * sz, 0.05f, 0.006f), EYE, 0.008f)
                    Eyes.RINGED -> {
                        sc.add(Torus(x, y + 0.004f, z, hw * 0.17f * sz, 0.012f * lid, floatArrayOf(0f, 1f, 0f)), FACE, 0.01f)
                        sc.cut(Cylinder(x, y + 0.02f, z, hw * 0.11f * sz, 0.05f, 0.005f), EYE, 0.004f)
                    }
                    Eyes.CRESCENT -> {
                        val w = hw * 0.2f * sz
                        chain(arc(x - w, z + 0.004f - sd * tilt * w, x, z - hh * 0.05f * sz, x + w, z + 0.004f + sd * tilt * w), 0.011f * sz, 0.011f * sz, 0.004f, false, EYE, 0f)
                    }
                    Eyes.DIAMOND -> {
                        val ax = RoundBox.axes(roll = (PI / 4).toFloat() - er)
                        sc.cut(RoundBox(x, y + 0.02f, z, hw * 0.11f * sz, 0.05f, hw * 0.11f * sz, 0.004f, ax[0], ax[1], ax[2]), EYE)
                    }
                    Eyes.TROUGH -> sc.cut(RoundBox(x, y + 0.02f, z - hh * 0.02f, hw * 0.12f * sz, 0.06f, hh * 0.13f * sz, 0.01f, bx[0], bx[1], bx[2]), EYE, 0.008f)
                }
            }
        }

        fun nose() {
            val ns = k(Dial.NOSE_SIZE, 0.6f, 1.55f)
            val nb = k(Dial.NOSE_BRIDGE, 0.45f, 1.7f)
            // Asymmetry bends the tip aside.
            val nx = asym * hw * 0.08f
            val topZ = browZ - hh * 0.04f
            val y0 = front(0f, topZ); val yt = front(nx, tipZ)
            when (s.nose) {
                Nose.NONE -> Unit
                Nose.LONG -> {
                    sc.add(RoundCone(0f, y0 - 0.004f, topZ, nx, yt + 0.075f * nb, tipZ, 0.024f * ns, 0.04f * ns), FACE, 0.026f)
                    nostrils(nx, yt + 0.06f * nb, ns)
                }
                Nose.BROAD -> {
                    sc.add(RoundCone(0f, y0 - 0.004f, topZ, nx, yt + 0.05f * nb, tipZ + hh * 0.03f, 0.026f * ns, 0.045f * ns), FACE, 0.028f)
                    sc.add(Ellipsoid(nx, yt + 0.045f * nb, tipZ, hw * 0.3f * ns, 0.06f * nb, hh * 0.1f * ns), FACE, 0.028f)
                    nostrils(nx, yt + 0.07f * nb, ns)
                }
                Nose.TRIANGLE -> {
                    sc.add(RoundCone(0f, y0 - 0.004f, topZ, nx, yt + 0.07f * nb, tipZ, 0.016f * ns, 0.058f * ns), FACE, 0.024f)
                    nostrils(nx, yt + 0.06f * nb, ns)
                }
                Nose.BEAK -> {
                    sc.add(RoundCone(0f, y0, topZ, nx, yt + 0.09f * nb, tipZ + hh * 0.05f, 0.02f * ns, 0.014f * ns), FACE, 0.02f)
                    sc.add(RoundCone(nx, yt + 0.09f * nb, tipZ + hh * 0.05f, nx, yt + 0.12f * nb, tipZ - hh * 0.08f, 0.014f * ns, 0.004f), FACE, 0.01f)
                }
            }
        }

        fun nostrils(x: Float, y: Float, ns: Float) = sides { sd -> sc.cut(Sphere(x + sd * 0.026f * ns, y, tipZ - 0.018f, 0.013f * ns), MOUTH, 0.005f) }

        fun mouth() {
            // Asymmetry pulls the mouth aside.
            val mx = -asym * hw * 0.1f
            val y = front(mx, mouthZ)
            // Never wider than the face is at the mouth.
            val mw = min(hw * (if (s.mouth == Mouth.BOX) 0.5f else 0.44f) * k(Dial.MOUTH_SIZE, 0.55f, 1.5f), hw * 0.62f * taper(mouthZ / hh))
            val lf = k(Dial.LIP_FULLNESS, 0.45f, 1.8f)
            when (s.mouth) {
                Mouth.CLOSED -> {
                    sc.add(Ellipsoid(mx, y + 0.022f * lf, mouthZ + 0.02f, mw, 0.045f * lf, 0.022f * lf), LIP, 0.016f)
                    sc.add(Ellipsoid(mx, y + 0.022f * lf, mouthZ - 0.022f, mw * 0.92f, 0.048f * lf, 0.026f * lf), LIP, 0.016f)
                    sc.cut(RoundBox(mx, y + 0.06f * lf, mouthZ, mw * 0.88f, 0.04f, 0.004f, 0.003f), MOUTH)
                }
                Mouth.OPEN, Mouth.TEETH -> {
                    sc.add(Ellipsoid(mx, y + 0.035f * lf, mouthZ, mw * 1.15f, 0.065f * lf, hh * 0.13f), LIP, 0.024f)
                    sc.cut(Ellipsoid(mx, y + 0.085f * lf, mouthZ, mw * 0.85f, 0.07f, hh * 0.07f), MOUTH, 0.005f)
                    if (s.mouth == Mouth.TEETH) {
                        val n = 6
                        for (row in listOf(1f, -1f)) for (i in 0 until n) {
                            val x = mx - mw * 0.7f + mw * 1.4f * i / (n - 1)
                            sc.add(RoundBox(x, y + 0.055f * lf, mouthZ + row * hh * 0.036f, mw * 0.09f, 0.016f, hh * 0.028f, 0.005f), TEETH)
                        }
                    }
                }
                Mouth.PURSED -> {
                    sc.add(Ellipsoid(mx, y + 0.012f * lf, mouthZ, mw * 0.55f, 0.035f * lf, hh * 0.06f), LIP, 0.014f)
                    sc.cut(Sphere(mx, y + 0.045f * lf, mouthZ, 0.012f), MOUTH)
                }
                Mouth.BOX -> {
                    sc.add(RoundBox(mx, y + 0.03f * lf, mouthZ, mw, 0.045f * lf, hh * 0.08f, 0.012f), LIP, 0.02f)
                    sc.cut(RoundBox(mx, y + 0.07f * lf, mouthZ, mw * 0.78f, 0.05f, hh * 0.028f, 0.006f), MOUTH)
                }
                Mouth.TUBE -> {
                    sc.add(Cylinder(mx, y + 0.035f * lf, mouthZ, mw * 0.55f, 0.04f * lf, 0.01f), LIP, 0.016f)
                    sc.cut(Cylinder(mx, y + 0.07f * lf, mouthZ, mw * 0.3f, 0.06f * lf), MOUTH)
                }
            }
        }

        fun scars() {
            val q = k(Dial.SCAR_DEPTH, 0.5f, 1.9f)
            for (m in s.scars) when (m) {
                Scar.ICHI -> for (k in -3..3) {
                    val x = k * hw * 0.09f
                    chain(floatArrayOf(x, browZ + hh * 0.06f, x, browZ + hh * 0.25f), 0.0045f * q, 0.0045f * q, 0.001f, false, SCAR, 0f)
                }
                Scar.TEMPLES -> sides { sd -> for (i in 0 until 3) { val z = browZ - hh * 0.02f + i * hh * 0.05f; chain(floatArrayOf(sd * hw * 0.66f, z, sd * hw * 0.86f, z - hh * 0.01f), 0.0045f * q, 0.0045f * q, 0.001f, false, SCAR, 0f) } }
                Scar.CHEEKS -> sides { sd -> for (i in 0 until 3) { val z = -hh * 0.2f - i * hh * 0.06f; chain(floatArrayOf(sd * hw * 0.42f, z, sd * hw * 0.72f, z - hh * 0.05f), 0.005f * q, 0.005f * q, 0.001f, false, SCAR, 0f) } }
                Scar.TEARS -> sides { sd -> chain(floatArrayOf(sd * ex, eyeZ - hh * 0.07f, sd * ex * 1.05f, -hh * 0.3f, sd * ex * 1.1f, -hh * 0.45f), 0.005f * q, 0.004f * q, 0.001f, false, SCAR, 0f) }
                Scar.KELOID_DIAMONDS -> {
                    fun diamond(cx: Float, cz: Float, sz: Float) {
                        for (i in -2..2) for (j in -2..2) if (abs(i) + abs(j) == 2 || (i == 0 && j == 0)) {
                            val x = cx + i * sz; val z = cz + j * sz
                            sc.add(Sphere(x, front(x, z), z, 0.0075f * q), FACE, 0.004f * q)
                        }
                    }
                    diamond(0f, browZ + hh * 0.16f, hh * 0.035f)
                    sides { sd -> diamond(sd * hw * 0.78f, eyeZ + hh * 0.02f, hh * 0.028f) }
                }
                Scar.CHEEK_KELOIDS -> sides { sd -> for (i in 0 until 3) for (j in 0 until 2) { val x = sd * (hw * 0.45f + i * hw * 0.1f); val z = -hh * 0.18f - j * hh * 0.07f; sc.add(Sphere(x, front(x, z), z, 0.007f * q), FACE, 0.004f * q) } }
                Scar.CENTRE_RIDGE -> chain(floatArrayOf(0f, hh * 0.72f, 0f, browZ + hh * 0.2f, 0f, browZ), 0.009f * q, 0.007f * q, 0f, true, FACE, 0.012f)
                Scar.STRIATIONS -> Unit
            }
        }

        // ---- the coiffure --------------------------------------------------------------

        /** Where the hair begins, at depth y: high on the brow, lower round the back. */
        fun hairZ(y: Float): Float = hh * (0.52f + 0.36f * c(Dial.HAIRLINE)) - 0.55f * max(0f, front0 * 0.5f - y)

        val top: Float get() = hh * 0.98f

        fun sculptCoiffure() {
            if (s.coiffure == Coiffure.NONE) return
            val hv = k(Dial.HAIR_VOLUME, 0.55f, 1.6f)
            val rows = 16f * k(Dial.HAIR_TEXTURE, 0.4f, 2f)
            // The cap of hair: the head swollen a little where the hair grows.
            val cornrows = s.coiffure == Coiffure.CORNROWS || s.coiffure == Coiffure.LOBES || s.coiffure == Coiffure.CREST
            val capBase = Custom(-hw * 1.4f, -dy * 1.3f, hairZ(-dy * 1.3f) - 0.05f, hw * 1.4f, dy * 1.3f, hh * 1.2f) { x, y, z ->
                val swell = shell.d(x, y, z) - 0.014f * hv
                val above = hairZ(y) - z
                max(swell, above)
            }
            val cap = if (cornrows) Displaced(capBase, 0.008f) { x, y, _ -> 0.005f * abs(sin(atan2(x, y + dy * 0.3f) * rows)) } else capBase
            sc.add(cap, HAIR, 0.01f)
            when (s.coiffure) {
                Coiffure.CREST -> sc.add(RoundBox(0f, -dy * 0.05f, top + hh * 0.08f * hv, hw * 0.1f * hv, dy * 0.75f, hh * 0.16f * hv, hw * 0.08f * hv), HAIR, 0.04f)
                Coiffure.TRIPLE_CREST -> for (k in -1..1) sc.add(RoundBox(k * hw * 0.42f, -dy * 0.05f, top + hh * (0.06f - 0.03f * abs(k)) * hv, hw * 0.08f * hv, dy * 0.7f, hh * (0.15f - 0.04f * abs(k)) * hv, hw * 0.07f * hv), HAIR, 0.035f)
                Coiffure.LOBES -> {
                    sc.add(Ellipsoid(0f, -dy * 0.1f, top + hh * 0.1f * hv, hw * 0.34f * hv, dy * 0.85f, hh * 0.3f * hv), HAIR, 0.05f)
                    sides { sd -> sc.add(Ellipsoid(sd * hw * 0.82f, -dy * 0.15f, hh * 0.55f, hw * 0.28f * hv, dy * 0.7f, hh * 0.24f * hv), HAIR, 0.045f) }
                    // The parting between the lobes.
                    sides { sd -> sc.cut(RoundBox(sd * hw * 0.42f, -dy * 0.1f, top, 0.006f, dy, hh * 0.25f, 0.004f), SCAR, 0.006f) }
                }
                Coiffure.KNOTS -> {
                    val n = s.count.coerceIn(1, 5)
                    for (i in 0 until n) {
                        val a = if (n == 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f) * 0.9f
                        val x = sin(a) * hw * 0.6f; val z = top + hh * 0.12f - abs(a) * hh * 0.12f
                        sc.add(Sphere(x, -dy * 0.05f, z, hw * 0.22f * hv), HAIR, 0.04f)
                    }
                }
                Coiffure.COMBS -> {
                    val n = s.count.coerceIn(2, 6)
                    for (i in 0 until n) {
                        val k = i - (n - 1) / 2f
                        val a = -k * 0.42f
                        val h = hh * (0.42f + 0.25f * s.crownSize) * (1f - 0.08f * abs(k)) * hv
                        val bx = k * hw * 0.3f; val bz = top - hh * 0.1f
                        val ax = RoundBox.axes(roll = a)
                        sc.add(RoundBox(bx + sin(-a) * h * 0.5f, -dy * 0.05f, bz + cos(a) * h * 0.5f, hw * 0.15f, 0.02f, h * 0.5f, hw * 0.12f, ax[0], ax[1], ax[2]), HAIR, 0.012f)
                        sc.add(Sphere(bx + sin(-a) * h, -dy * 0.05f, bz + cos(a) * h, hw * 0.07f), STUD, 0.01f)
                    }
                }
                Coiffure.TOPKNOT -> {
                    sc.add(RoundCone(0f, -dy * 0.1f, top - hh * 0.05f, 0f, -dy * 0.15f, top + hh * 0.2f * hv, hw * 0.12f * hv, hw * 0.08f * hv), HAIR, 0.03f)
                    sc.add(Sphere(0f, -dy * 0.15f, top + hh * 0.26f * hv, hw * 0.16f * hv), HAIR, 0.02f)
                }
                Coiffure.CAP, Coiffure.CORNROWS, Coiffure.NONE -> Unit
            }
        }

        // ---- horns and superstructures ------------------------------------------------------

        fun hornColour(t: Float): Int {
            val base = Pigments.argb(s.hornColour)
            val root = mix(base, 0xFF1A120E.toInt(), 0.55f)
            val tip = mix(base, 0xFFF6EEDC.toInt(), 0.35f)
            return TubeMesh.lerp(root, tip, t.pow(0.8f))
        }

        fun sculptCrown() {
            val L = 0.3f + 0.45f * s.crownSize
            val detailRings = if (detail.cell < 0.01f) 48 else 24
            val sides = if (detail.cell < 0.01f) 18 else 10
            val curl = k(Dial.HORN_CURL, 0.3f, 1.9f)
            val spread = k(Dial.HORN_SPREAD, 0.65f, 1.5f)
            val girth = k(Dial.HORN_GIRTH, 0.55f, 1.7f)
            val ridges = k(Dial.HORN_RIDGES, 0f, 2.6f)
            when (s.crown) {
                Crown.NONE -> Unit
                Crown.ANTELOPE -> sides { sd ->
                    val pts = TubeMesh.curve(
                        sd * hw * 0.3f * spread, -dy * 0.1f, top - hh * 0.12f,
                        sd * hw * 0.42f * spread, -dy * 0.3f * curl, top + L * 0.35f,
                        sd * hw * 0.5f * spread, -dy * 0.75f * curl, top + L * (0.7f - 0.08f * (curl - 1f)),
                        sd * hw * 0.44f * spread, -dy * 1.3f * curl, top + L * (0.92f - 0.2f * (curl - 1f)),
                    )
                    extra.sweep(pts, detailRings, sides, { t -> hw * 0.2f * girth * (1f - 0.93f * t) * TubeMesh.ridge(t * 0.55f, 9f, 0.12f * ridges) }, ::hornColour)
                }
                Crown.BUFFALO -> sides { sd ->
                    val pts = TubeMesh.curve(
                        sd * hw * 0.7f, -dy * 0.05f, hh * 0.62f,
                        sd * (hw + L * 0.35f * spread), -dy * 0.15f, hh * 0.72f,
                        sd * (hw + L * 0.7f * spread), -dy * 0.2f, hh * (0.72f + 0.23f * curl),
                        sd * (hw + L * (0.78f - 0.12f * (curl - 1f)) * spread), -dy * 0.1f, hh * (0.72f + 0.58f * curl),
                    )
                    extra.sweep(pts, detailRings, sides, { t -> hw * 0.26f * girth * (1f - 0.9f * t.pow(0.8f)) * TubeMesh.ridge(t * 0.5f, 7f, 0.05f * ridges) }, ::hornColour)
                }
                Crown.RAM -> sides { sd ->
                    // A ram's horn: out from the temple, curling back and down in a tightening spiral, ridged all along.
                    val n = 28
                    val pts = FloatArray(n * 3)
                    val cx = sd * (hw + hw * 0.25f * spread); val cy = -dy * 0.25f; val cz = hh * 0.45f
                    for (i in 0 until n) {
                        val t = i / (n - 1f)
                        val a = PI.toFloat() * (0.5f + 1.7f * curl.coerceAtMost(1.5f) * t)
                        val rad = hh * (0.36f - 0.2f * t) * (0.8f + 0.4f * s.crownSize)
                        pts[i * 3] = cx + sd * hw * 0.35f * spread * t
                        pts[i * 3 + 1] = cy + cos(a) * rad
                        pts[i * 3 + 2] = cz + sin(a) * rad
                    }
                    extra.sweep(pts, detailRings * 2, sides, { t -> hw * 0.2f * girth * (1f - 0.75f * t) * TubeMesh.ridge(t, 22f, 0.1f * ridges) }, ::hornColour)
                }
                Crown.TIERS -> {
                    val n = s.count.coerceIn(2, 4)
                    var z = top - hh * 0.05f
                    for (k in 0 until n) {
                        val rad = hw * (1.05f - 0.22f * k); val h = hh * (0.12f + 0.06f * s.crownSize)
                        sc.add(Cylinder(0f, -dy * 0.1f, z + h, rad, h, 0.02f, alongZ = true), TIER + k, 0.02f)
                        val studs = 10 - k * 2
                        for (j in 0 until studs) {
                            val a = PI.toFloat() * (0.1f + 0.8f * j / (studs - 1))
                            sc.add(Sphere(cos(a) * rad, -dy * 0.1f + sin(a) * rad, z + h, 0.012f + 0.004f * s.crownSize), STUD, 0.004f)
                        }
                        z += h * 2f
                    }
                    sc.add(RoundCone(0f, -dy * 0.1f, z, 0f, -dy * 0.1f, z + hh * 0.25f, hw * 0.28f, 0.01f), TIER, 0.02f)
                }
                Crown.PLANK -> {
                    val h = hh * (1.1f + 1.4f * s.crownSize)
                    sc.add(RoundBox(0f, -dy * 0.05f, top + h * 0.5f, hw * 0.42f, 0.014f, h * 0.5f, 0.01f), PLANK, 0.03f)
                    // The crescent hook at the top of the board.
                    val zt = top + h
                    for (k in 0 until 8) {
                        val a0 = PI.toFloat() * k / 8f; val a1 = PI.toFloat() * (k + 1) / 8f
                        sc.add(RoundCone(cos(a0) * hw * 0.34f, -dy * 0.05f, zt + sin(a0) * hw * 0.34f, cos(a1) * hw * 0.34f, -dy * 0.05f, zt + sin(a1) * hw * 0.34f, 0.016f, 0.016f), PLANK, 0.01f)
                    }
                }
                Crown.KANAGA -> {
                    val h = hh * (1.2f + 1.2f * s.crownSize)
                    val z0 = top - hh * 0.05f; val w = hw * 1.5f
                    sc.add(RoundBox(0f, -dy * 0.05f, z0 + h * 0.5f, 0.018f, 0.015f, h * 0.5f, 0.006f), CREST, 0.02f)
                    val upper = z0 + h * 0.78f; val lower = z0 + h * 0.4f
                    sc.add(RoundBox(0f, -dy * 0.05f, upper, w, 0.015f, 0.016f, 0.006f), CREST, 0.01f)
                    sc.add(RoundBox(0f, -dy * 0.05f, lower, w * 0.9f, 0.015f, 0.016f, 0.006f), CREST, 0.01f)
                    sides { sd ->
                        sc.add(RoundBox(sd * w, -dy * 0.05f, upper + hh * 0.12f, 0.016f, 0.015f, hh * 0.12f, 0.006f), CREST, 0.01f)
                        sc.add(RoundBox(sd * w * 0.9f, -dy * 0.05f, lower - hh * 0.12f, 0.016f, 0.015f, hh * 0.12f, 0.006f), CREST, 0.01f)
                    }
                }
                Crown.BIRD -> {
                    val z = top + hh * 0.12f
                    sc.add(Ellipsoid(0f, -dy * 0.1f, z, hw * 0.2f, hw * 0.34f, hh * 0.13f), CREST, 0.04f)
                    sc.add(Sphere(0f, dy * 0.25f, z + hh * 0.14f, hw * 0.13f), CREST, 0.02f)
                    sc.add(RoundCone(0f, dy * 0.32f, z + hh * 0.14f, 0f, dy * 0.95f, z + hh * 0.02f, hw * 0.075f, 0.006f), CREST, 0.01f)
                }
            }
            when (s.beard) {
                Beard.NONE, Beard.RAFFIA -> Unit
                Beard.CARVED -> {
                    val y = front(0f, -hh * 0.85f)
                    val beard = RoundCone(0f, y - 0.03f, -hh * 0.82f, 0f, y - 0.02f, -hh * 1.35f, hw * 0.26f, hw * 0.05f)
                    sc.add(Displaced(beard, 0.006f) { x, _, z -> 0.004f * abs(sin(x * 120f + z * 20f)) }, HAIR, 0.03f)
                }
            }
        }

        // ---- what is fastened to it -------------------------------------------------------------

        fun sculptAdornments() {
            val beadColours = intArrayOf(Pigments.argb(s.beads), Pigments.argb(Pigments.KAOLIN), Pigments.argb(s.accent), Pigments.argb(s.beads) .let { mix(it, 0xFF000000.toInt(), 0.35f) })
            for (a in s.adorn) when (a) {
                Adorn.COWRIES -> {
                    val n = 11
                    for (i in 0 until n) {
                        val x = -hw * 0.8f + hw * 1.6f * i / (n - 1)
                        val z = hairZ(front0) - 0.006f - 0.08f * x * x
                        val y = front(x, z)
                        sc.add(Ellipsoid(x, y + 0.004f, z, 0.016f, 0.012f, 0.011f), COWRIE, 0.003f)
                        sc.cut(RoundBox(x, y + 0.016f, z, 0.012f, 0.006f, 0.0018f, 0.001f), MOUTH)
                    }
                }
                Adorn.BEADS -> sides { sd ->
                    for (strand in 0 until 3) {
                        val x0 = sd * (hw * 0.95f + strand * 0.012f); val y0 = -dy * 0.1f + strand * 0.02f
                        for (b in 0 until 9) extra.ball(x0, y0, eyeZ - b * 0.028f, 0.012f, beadColours[(b + strand) % beadColours.size], 0, if (detail.cell < 0.01f) 6 else 4)
                    }
                }
                Adorn.EARRINGS -> sides { sd ->
                    sc.add(Torus(sd * hw * 1.02f, 0f, eyeZ - hh * 0.3f, hw * 0.12f, 0.008f, floatArrayOf(1f, 0f, 0f)), STUD, 0.002f)
                }
                Adorn.BRASS_STUDS -> sides { sd ->
                    for (i in 0 until 4) {
                        val x = sd * hw * (0.25f + 0.17f * i); val z = browZ + hh * 0.1f - i * hh * 0.02f
                        sc.add(Sphere(x, front(x, z), z, 0.01f), STUD, 0.003f)
                    }
                }
                Adorn.RAFFIA_COLLAR -> raffia(46, hh * 0.85f, collar = true)
            }
            if (s.beard == Beard.RAFFIA) raffia(30, hh * 0.9f, collar = false)
        }

        /** Raffia strands, [count] of them, hanging [length]: round the rim as a collar, or from the chin as a beard. */
        fun raffia(count: Int, length: Float, collar: Boolean) {
            val full = (count * (0.45f + 1.1f * s.raffia)).toInt().coerceAtLeast(6)
            val n = if (detail.cell < 0.01f) full else full / 2
            val straw = if (s.raffiaDye == MaskSpec.NATURAL) STRAW else STRAW.map { mix(Pigments.argb(s.raffiaDye), it, 0.22f) }.toIntArray()
            val dyed = if (s.finish == Finish.POLYCHROME || s.finish == Finish.BLACKENED) intArrayOf(Pigments.argb(s.accent), 0xFF2A1A12.toInt()) else straw
            for (i in 0 until n) {
                val a = if (collar) PI.toFloat() * (-0.15f + 1.3f * i / (n - 1)) else PI.toFloat() * (0.2f + 0.6f * i / (n - 1))
                val ax = cos(a); val ay = sin(a)
                val (x0, y0, z0) = if (collar) Triple(ax * hw * 0.98f, -ay * dy * 0.6f + dy * 0.1f, -hh * 0.65f - 0.02f * sin(a * 3f))
                else Triple(ax * hw * 0.45f, front(ax * hw * 0.45f, -hh * 0.9f) - 0.02f, -hh * 0.88f)
                val len = length * k(Dial.RAFFIA_LENGTH, 0.45f, 1.9f) * (0.85f + 0.3f * r.nextFloat())
                val out = if (collar) 0.12f else 0.04f
                val pts = TubeMesh.curve(
                    x0, y0, z0,
                    x0 + ax * out * 0.5f, y0 + (if (collar) -ay * out * 0.3f else 0.02f), z0 - len * 0.45f,
                    x0 + ax * out, y0 + (if (collar) -ay * out * 0.5f else 0.03f), z0 - len,
                )
                val c = if (i % 5 == 0) dyed[i % dyed.size] else straw[i % straw.size]
                extra.sweep(pts, 6, 3, { t -> 0.007f * (1f - 0.6f * t) }, { t -> TubeMesh.lerp(c, mix(c, 0xFF1A120A.toInt(), 0.4f), t * 0.6f) })
            }
        }

        // ---- colour and assembly ------------------------------------------------------------

        val patina = k(Dial.PATINA, 0.2f, 1.8f)
        val grainK = k(Dial.GRAIN, 0f, 2.6f)
        val wearAt = 0.98f - 0.24f * s.dial(Dial.WEAR)
        val ps = k(Dial.PATTERN_SCALE, 0.5f, 1.9f)

        fun assemble(): SpiritMesh {
            val net = SurfaceNets(sc, detail.cell).mesh()
            val nv = net.vertexCount
            val colours = IntArray(nv); val channels = ByteArray(nv); val glowColours = IntArray(nv)
            for (i in 0 until nv) {
                val x = net.positions[i * 3]; val y = net.positions[i * 3 + 1]; val z = net.positions[i * 3 + 2]
                val nx = net.normals[i * 3]; val ny = net.normals[i * 3 + 1]; val nz = net.normals[i * 3 + 2]
                val m = net.materials[i]
                val cav = cavity(x, y, z, nx, ny, nz)
                var c = surface(m, x, y, z, ny, cav)
                c = scale(c, (1f - 0.5f * patina * (1f - cav)).coerceAtLeast(0.15f))
                colours[i] = c
                when {
                    m == EYE -> { channels[i] = GlowChannel.EYES; glowColours[i] = s.glow }
                    m == SCAR || (m == FACE && uliLine(x, z) > 0.5f && Pattern.ULI in s.patterns) -> { channels[i] = GlowChannel.LINES; glowColours[i] = s.glow }
                    m == STUD -> { channels[i] = GlowChannel.CREST; glowColours[i] = mix(s.glow, 0xFFFFFFFF.toInt(), 0.3f) }
                    else -> glowColours[i] = c
                }
            }
            // The swept parts: their triangles faced outward one by one, since curls can turn a tube inside out.
            val tubeTris = extra.idx.toArray()
            val tp = extra.pos.toArray(); val tn = extra.nrm.toArray()
            for (t in 0 until tubeTris.size / 3) {
                val a = tubeTris[t * 3] * 3; val b = tubeTris[t * 3 + 1] * 3; val c = tubeTris[t * 3 + 2] * 3
                val ux = tp[b] - tp[a]; val uy = tp[b + 1] - tp[a + 1]; val uz = tp[b + 2] - tp[a + 2]
                val vx = tp[c] - tp[a]; val vy = tp[c + 1] - tp[a + 1]; val vz = tp[c + 2] - tp[a + 2]
                val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
                val sx = tn[a] + tn[b] + tn[c]; val sy = tn[a + 1] + tn[b + 1] + tn[c + 1]; val sz = tn[a + 2] + tn[b + 2] + tn[c + 2]
                if (fx * sx + fy * sy + fz * sz < 0f) { val tmp = tubeTris[t * 3 + 1]; tubeTris[t * 3 + 1] = tubeTris[t * 3 + 2]; tubeTris[t * 3 + 2] = tmp }
            }
            val tc = extra.col.toArray(); val tg = extra.glow.toArray()
            // Into model units: the face stands about 0.64 tall, centred on the eye line's middle.
            val k = 0.64f / (2f * hh)
            val total = nv + tp.size / 3
            val positions = FloatArray(total * 3); val normals = FloatArray(total * 3)
            val allColours = IntArray(total); val allChannels = ByteArray(total); val allGlow = IntArray(total)
            for (i in 0 until nv) {
                positions[i * 3] = net.positions[i * 3] * k; positions[i * 3 + 1] = net.positions[i * 3 + 1] * k; positions[i * 3 + 2] = net.positions[i * 3 + 2] * k
                normals[i * 3] = net.normals[i * 3]; normals[i * 3 + 1] = net.normals[i * 3 + 1]; normals[i * 3 + 2] = net.normals[i * 3 + 2]
                allColours[i] = colours[i]; allChannels[i] = channels[i]; allGlow[i] = glowColours[i]
            }
            for (j in 0 until tp.size / 3) {
                val i = nv + j
                positions[i * 3] = tp[j * 3] * k; positions[i * 3 + 1] = tp[j * 3 + 1] * k; positions[i * 3 + 2] = tp[j * 3 + 2] * k
                normals[i * 3] = tn[j * 3]; normals[i * 3 + 1] = tn[j * 3 + 1]; normals[i * 3 + 2] = tn[j * 3 + 2]
                allColours[i] = tc[j]; allChannels[i] = tg[j].toByte(); allGlow[i] = tc[j]
            }
            val indices = IntArray(net.indices.size + tubeTris.size)
            net.indices.copyInto(indices)
            for (t in tubeTris.indices) indices[net.indices.size + t] = tubeTris[t] + nv
            val eyes = FloatArray(6)
            eyeCentres.take(2).forEachIndexed { e, p -> eyes[e * 3] = p[0] * k; eyes[e * 3 + 1] = (p[1] + 0.01f) * k; eyes[e * 3 + 2] = p[2] * k }
            val straw = when {
                s.raffiaDye != MaskSpec.NATURAL -> intArrayOf(mix(Pigments.argb(s.raffiaDye), STRAW[0], 0.22f), mix(Pigments.argb(s.raffiaDye), STRAW[2], 0.3f))
                s.finish == Finish.POLYCHROME -> intArrayOf(STRAW[0], Pigments.argb(s.accent))
                else -> intArrayOf(STRAW[0], STRAW[2])
            }
            return SpiritMesh(
                positions, normals, allColours, allChannels, allGlow, indices, eyes,
                auraColor = s.glow, auraSecond = Pigments.argb(s.accent), height = 1f, faceColor = finishColour(0f, 0f, 0f),
                fringeColors = straw, charmColor = 0xFFF3EAD6.toInt(),
            )
        }

        /** How open the surface is here: 1 in the open, less in cuts, creases and under overhangs. */
        fun cavity(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Float {
            var occ = 0f
            for ((dist, w) in CAVITY) {
                val d = sc.d(x + nx * dist, y + ny * dist, z + nz * dist)
                occ += w * ((dist - d) / dist).coerceIn(0f, 1f)
            }
            return (1f - occ).coerceIn(0f, 1f)
        }

        fun finishColour(x: Float, y: Float, z: Float): Int {
            val wood = Pigments.argb(s.wood)
            return when (s.finish) {
                Finish.RAW -> wood
                Finish.BLACKENED -> mix(wood, 0xFF120C0A.toInt(), 0.72f)
                Finish.KAOLIN -> Pigments.argb(Pigments.KAOLIN)
                Finish.CAMWOOD -> mix(Pigments.argb(Pigments.CAMWOOD), wood, 0.25f)
                Finish.POLYCHROME -> wood
                Finish.BRASS -> Pigments.argb(Pigments.BRASS)
            }
        }

        fun surface(m: Int, x: Float, y: Float, z: Float, ny: Float, cav: Float): Int {
            val wood = Pigments.argb(s.wood)
            val grain = 1f + grainK * (-0.1f + 0.1f * sin(x * 140f + 7f * noise(x * 3f, z * 1.2f, y * 3f)) + 0.05f * (noise(x * 40f, z * 4f, y * 40f) - 0.5f))
            return when {
                m == EYE -> 0xFF1A0806.toInt()
                m == MOUTH -> 0xFF160A08.toInt()
                m == TEETH -> 0xFFEDE4D2.toInt()
                m == COWRIE -> 0xFFF3EAD6.toInt()
                m == STUD -> if (s.finish == Finish.BRASS) 0xFFE8C070.toInt() else Pigments.argb(Pigments.BRASS)
                m == HAIR -> scale(mix(Pigments.argb(s.hair), wood, 0.12f), 0.9f + 0.1f * sin(z * 260f + x * 30f))
                m in TIER until TIER + 4 -> {
                    val band = listOf(s.accent, s.accent2, Pigments.JADE, Pigments.OCHRE)[(m - TIER) % 4]
                    val c = Pigments.argb(band)
                    if (((atan2(y, x) * 8f / PI.toFloat()).toInt() + (m - TIER)) % 2 == 0) c else mix(c, 0xFF1A120E.toInt(), 0.35f)
                }
                m == PLANK -> plankPaint(x, z)
                m == CREST -> scale(finishColour(x, y, z), grain)
                m == SCAR -> {
                    // A cut shows the wood under any paint, darker in its depth.
                    val c = if (s.finish == Finish.KAOLIN || Pattern.STRIATED in s.patterns) Pigments.argb(Pigments.KAOLIN) else mix(wood, 0xFF140A08.toInt(), 0.45f)
                    c
                }
                else -> faceColour(m, x, y, z, ny, cav, grain)
            }
        }

        fun faceColour(m: Int, x: Float, y: Float, z: Float, ny: Float, cav: Float, grain: Float): Int {
            val wood = Pigments.argb(s.wood)
            // Handling wears the high points and edges back toward the bare wood.
            val wear = noise(x * 8f + 3f, z * 8f, y * 8f) + 0.15f * noise(x * 40f, z * 40f, y * 40f)
            var c = when (s.finish) {
                Finish.KAOLIN -> {
                    if (wear > wearAt) scale(wood, grain) else mix(Pigments.argb(Pigments.KAOLIN), wood, 0.08f + 0.1f * (1f - cav))
                }
                Finish.BRASS -> {
                    val green = ((1f - cav) * 1.4f + 0.3f * noise(x * 20f, z * 20f, y * 20f) - 0.2f).coerceIn(0f, 1f)
                    mix(Pigments.argb(Pigments.BRASS), 0xFF3F7A62.toInt(), green * 0.7f)
                }
                Finish.BLACKENED, Finish.POLYCHROME -> scale(if (wear > wearAt + 0.04f) mix(finishColour(x, y, z), wood, 0.55f) else finishColour(x, y, z), grain)
                else -> scale(finishColour(x, y, z), grain)
            }
            if (m == LIP && (s.finish == Finish.KAOLIN || s.finish == Finish.POLYCHROME)) c = mix(Pigments.argb(s.accent2), c, 0.35f)
            if (ny > 0.15f && y > front0 * 0.3f) c = paint(c, x, z)
            return c
        }

        /** The tradition's painted patterns, over the face's front. */
        fun paint(base: Int, x: Float, z: Float): Int {
            var c = base
            val a1 = Pigments.argb(s.accent); val a2 = Pigments.argb(s.accent2)
            for (p in s.patterns) when (p) {
                Pattern.EYE_RINGS -> for (e in eyeCentres) { val d = sqrt((x - e[0]).let { it * it } + (z - e[2]).let { it * it }); if (d < hw * 0.3f * ps) c = a1 }
                Pattern.SPLIT -> if (x < 0f) c = mix(a2, c, 0.1f)
                Pattern.BANDS -> if (abs(z - browZ - hh * 0.1f) < hh * 0.05f * ps || abs(z + hh * 0.2f) < hh * 0.035f * ps) c = a1
                Pattern.CHECKER -> if (z < -hh * 0.3f && (floor(x / (0.03f * ps)) + floor(z / (0.03f * ps))).toInt() % 2 == 0) c = a1
                Pattern.DOTS -> { val g = 0.028f * ps; val fx = (x / g) - floor(x / g) - 0.5f; val fz = (z / g) - floor(z / g) - 0.5f; if (z > browZ && fx * fx + fz * fz < 0.07f) c = a1 }
                Pattern.TRIANGLES -> { val g = 0.05f * ps; val row = floor(z / g); val fx = (x / g) - floor(x / g); val fz = (z / g) - row; if (fz < 1f - abs(fx * 2f - 1f) && row.toInt() % 2 == 0 && z < eyeZ - hh * 0.1f) c = if (fx < 0.5f) a1 else a2 }
                Pattern.CONCENTRIC -> for (e in eyeCentres) { val d = sqrt((x - e[0]).let { it * it } + (z - e[2]).let { it * it }); if (d < hw * 0.36f * ps && (d / (0.018f * ps)).toInt() % 2 == 0) c = a1 }
                Pattern.STRIATED -> { if (sin(z * 150f / ps + 3f * sin(x * 9f)) < -0.2f) c = mix(Pigments.argb(Pigments.KAOLIN), c, 0.15f) else c = mix(c, 0xFF1A0E0A.toInt(), 0.3f) }
                Pattern.ULI -> if (uliLine(x, z) > 0.5f) c = mix(Pigments.argb(s.hair), c, 0.15f)
            }
            return c
        }

        /** Uli: fine black spirals on the cheeks and a line of curls along the brow. */
        fun uliLine(x: Float, z: Float): Float {
            for (sd in listOf(-1f, 1f)) {
                val cx = sd * hw * 0.55f; val cz = -hh * 0.22f
                val dx = x - cx; val dz = z - cz
                val rr = sqrt(dx * dx + dz * dz)
                if (rr < hw * 0.22f * ps) {
                    val th = atan2(dz, dx * sd)
                    val phase = (rr / (0.014f * ps) - th / (2f * PI.toFloat())) % 1f
                    if (abs(phase - 0.5f) < 0.12f) return 1f
                }
            }
            if (abs(z - (browZ + hh * 0.2f + 0.012f * sin(x * 60f))) < 0.0035f && abs(x) < hw * 0.6f) return 1f
            return 0f
        }

        /** A Bwa board: checkers, triangles and target circles in the tradition's colours. */
        fun plankPaint(x: Float, z: Float): Int {
            val a1 = Pigments.argb(s.accent); val a2 = Pigments.argb(s.accent2); val dark = 0xFF1A120E.toInt()
            val band = floor((z - top) / (hw * 0.4f)).toInt()
            return when (Math.floorMod(band, 3)) {
                0 -> if ((floor(x / (0.028f * ps)) + floor(z / (0.028f * ps))).toInt() % 2 == 0) a1 else dark
                1 -> { val cz = top + (band + 0.5f) * hw * 0.4f; val d = sqrt(x * x + (z - cz).let { it * it }); if ((d / (0.016f * ps)).toInt() % 2 == 0) a2 else a1 }
                else -> { val fx = (x / (0.05f * ps)) - floor(x / (0.05f * ps)); val fz = ((z - top) / (hw * 0.4f)) - band; if (fz < 1f - abs(fx * 2f - 1f)) dark else a1 }
            }
        }
    }

    // ---- shared helpers ------------------------------------------------------------------

    private val STRAW = intArrayOf(0xFFD9B26A.toInt(), 0xFFB8894A.toInt(), 0xFF8C5A2B.toInt(), 0xFFE2C686.toInt())

    private val CAVITY = listOf(0.012f to 0.45f, 0.035f to 0.35f, 0.08f to 0.2f)

    internal fun ellipsoid(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float): Float {
        val px = x / rx; val py = y / ry; val pz = z / rz
        val k0 = sqrt(px * px + py * py + pz * pz)
        val qx = px / rx; val qy = py / ry; val qz = pz / rz
        val k1 = sqrt(qx * qx + qy * qy + qz * qz)
        return if (k1 < 1e-9f) -min(rx, min(ry, rz)) else k0 * (k0 - 1f) / k1
    }

    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 668265263 + z * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /** Smooth 3D value noise, 0..1. */
    internal fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val fx = x - xi; val fy = y - yi; val fz = z - zi
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy); val uz = fz * fz * (3 - 2 * fz)
        fun l(a: Float, b: Float, t: Float) = a + (b - a) * t
        return l(
            l(l(hash(xi, yi, zi), hash(xi + 1, yi, zi), ux), l(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), ux), uy),
            l(l(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), ux), l(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), ux), uy),
            uz,
        )
    }

    internal fun mix(a: Int, b: Int, t: Float): Int = TubeMesh.lerp(a, b, t)

    internal fun scale(argb: Int, k: Float): Int {
        fun m(s: Int) = (((argb shr s) and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    @Suppress("unused") private fun smooth(x: Float) = 1f / (1f + exp(-x))
}
