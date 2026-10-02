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
import com.stratum.engine.scene.ShardRole
import com.stratum.engine.scene.ShatteredSpirit
import com.stratum.engine.scene.SpiritShard
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
        // Room for every kind of monster in a big fight, and the hero, without carving any twice.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpiritMesh>?): Boolean = size > 64
    }

    fun cached(spec: MaskSpec, detail: Detail = Detail.GAME): SpiritMesh {
        val key = spec.hashCode().toString() + detail.name
        synchronized(cache) { cache[key]?.let { return it } }
        val mesh = carve(spec, detail)
        synchronized(cache) { cache[key] = mesh }
        return mesh
    }

    fun carve(spec: MaskSpec, detail: Detail = Detail.GAME): SpiritMesh = Carver(spec, detail).run()

    /**
     * [spec] carved, then broken open by its [spirit]: floating pieces round
     * a glowing core, for [com.stratum.engine.scene.SpiritStage] to fly.
     */
    fun shatter(spec: MaskSpec, spirit: SpiritSpec = MaskCulture.spiritOf(spec), detail: Detail = Detail.GAME): ShatteredSpirit = Carver(spec, detail).shatter(spirit)

    /** How a spirit's pieces move, from its temper. */
    fun temperamentOf(spirit: SpiritSpec): com.stratum.engine.scene.SpiritTemperament = SpiritCores.temperament(spirit.temper)

    private val broken = object : LinkedHashMap<String, ShatteredSpirit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShatteredSpirit>?): Boolean = size > 12
    }

    fun cachedShatter(spec: MaskSpec, spirit: SpiritSpec = MaskCulture.spiritOf(spec), detail: Detail = Detail.GAME): ShatteredSpirit {
        val key = spec.hashCode().toString() + "/" + spirit.hashCode() + detail.name
        synchronized(broken) { broken[key]?.let { return it } }
        val made = shatter(spec, spirit, detail)
        synchronized(broken) { broken[key] = made }
        return made
    }

    // Materials: what a surface is, which decides its colour and its light.
    internal const val BROW = 12
    internal const val FACE = 0; internal const val HAIR = 1; internal const val EYE = 2; internal const val MOUTH = 3; internal const val TEETH = 4
    internal const val CREST = 6; internal const val SCAR = 10; internal const val LIP = 11; internal const val PLANK = 13
    internal const val TIER = 20; internal const val STUD = 30; internal const val COWRIE = 31
    internal const val KNOB = 32; internal const val ARCH_BAND = 34
    /** The raw face where the wood split: lit from inside by the spirit's core. */
    internal const val FRACTURE = 40

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

        // Long, narrow faces, as the carvers make them: never wider than about two thirds of their height.
        val hw = 0.19f + 0.11f * s.width * (if (s.outline == Outline.LONG) 0.8f else 1f)
        val hh = (0.36f + 0.16f * s.length) * (when (s.outline) { Outline.ROUND -> 0.88f; Outline.DISC -> 0.8f; else -> 1f })
        val helmet = s.form == Form.HELMET
        // A disc mask is a flat board; everything else has the depth of a head.
        val dy = (if (helmet) hw * 1.05f else hw * 0.72f) * k(Dial.FACE_DEPTH, 0.75f, 1.3f) * (if (s.outline == Outline.DISC) 0.5f else 1f)
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
                // Each narrows below the eyes smoothly from the eye line, so no crease runs round the head there.
                Outline.HEART, Outline.CONCAVE -> if (zn < 0f) 1.04f - 0.54f * a.pow(1.6f) else 1.04f
                Outline.SHIELD -> if (zn < 0f) 1.06f - 0.48f * a.pow(1.5f) else 1.06f
                // Tapering to a small, firm chin; the brow domed above.
                Outline.LONG -> if (zn < 0f) 1f - 0.34f * a.pow(1.7f) else 1f - 0.08f * a * a
                Outline.ROUND -> 1.08f
                Outline.DISC -> 1.12f
                Outline.SQUARE -> if (zn < 0f) 1f - 0.12f * a.pow(1.6f) else 1f
                Outline.OVAL -> if (zn < 0f) 1f - 0.4f * a.pow(1.9f) else 1f - 0.1f * a * a
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
        /** max(0, v), its corner rounded over a short span. */
        fun hinge(v: Float, w: Float): Float { return if (v > w) v else if (v < -w) 0f else (v + w) * (v + w) / (4f * w) }

        fun facePlane(x: Float, z: Float): Float {
            // Only the face is pressed into planes; past its edges the head keeps its own round.
            val xf = min(abs(x), hw * 0.85f)
            val central = (1f - (x / (hw * 1.05f)).let { it * it }).coerceIn(0f, 1f)
            val across = 0.9f * k(Dial.CONVEXITY, 0.35f, 1.9f) * xf * xf
            // Carved in planes: the forehead a plane falling back from the brow line, the chin one falling back below the mouth.
            val brow = browZ + hh * 0.06f
            // Each plane turns into the next over a short bend, so the break reads on the face without cracking round the sides.
            val down = 0.15f * (z - eyeZ).let { it * it } + (0.42f * hinge(z - brow, hh * 0.16f) + 0.18f * hinge(mouthZ - z, hh * 0.1f)) * central
            // Never pressed back past the middle of the head, so crown and chin keep their own round.
            return max(front0 - across - down, dy * 0.2f)
        }

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
            val scored = if (striated) Displaced(shell, 0.006f * q) { x, _, z -> 0.004f * q * vee(z * 24f + 0.5f * sin(x * 9f)) } else shell
            // The adze's marks: shallow scoops, each cut flat across, meeting at sharp little ridges.
            val adze = 0.0007f * k(Dial.TOOL_MARKS, 0f, 3.2f)
            val adzed = if (adze > 0f) Displaced(scored, adze) { x, y, z -> adze * scoop(x, y, z) } else scored
            val head = if (ichiOn) Displaced(adzed, ichiDepth) { x, y, z -> ichiDepth * ichi(x, y, z) } else adzed
            sc.add(head, FACE)
            // Press the front into a face plane, softly, so a rim runs round the face.
            val plane = Custom(-hw * 1.3f, -2f, -hh * 1.2f, hw * 1.3f, 2f, hh * 1.2f) { x, y, z -> y - facePlane(x, z) + (if (ichiOn) ichiDepth * ichi(x, y, z) else 0f) }
            // Rolled into the sides, not bevelled: the face is one convex form, not a board.
            sc.parts += Sculpture.Part(plane, Sculpture.Op.INTERSECT, 0.09f, -1, smooth = true)
            if (!helmet) {
                // A face mask is a board: its back cut flat and hollowed for the wearer.
                sc.keepInside(Custom(-2f, -2f, -2f, 2f, 2f, 2f) { _, y, _ -> -(y + dy * 0.45f) }, -1, 0.03f)
            }
            if (s.outline == Outline.CONCAVE) {
                // The Fang face: a hollow scooped from brow to chin inside the heart.
                sc.cut(Ellipsoid(0f, front0 + hw * 0.18f, -hh * 0.22f, hw * 0.72f, hw * 0.3f, hh * 0.72f), FACE, 0.06f, smooth = true)
            }
        }

        val ichiOn = Scar.ICHI in s.scars
        val ichiDepth = 0.0045f * k(Dial.SCAR_DEPTH, 0.5f, 1.9f)

        /**
         * Ichi: fine parallel flutes over the brow and round onto the temples,
         * spaced evenly round the head's curve so they follow it rather than
         * being ruled straight across it, fading out at their ends. 1 in a cut.
         */
        fun ichi(x: Float, y: Float, z: Float): Float {
            val z0 = browZ + hh * 0.05f; val z1 = browZ + hh * 0.3f
            if (z < z0 || z > z1) return 0f
            val a = atan2(x, y + dy * 0.3f)
            if (abs(a) > ICHI_REACH) return 0f
            val ends = hh * 0.05f
            val fade = min(1f, min((z - z0) / ends, (z1 - z) / ends)) * min(1f, (ICHI_REACH - abs(a)) / 0.2f)
            return (1f - vee(a * ICHI_LINES) / 0.3f).coerceAtLeast(0f) * fade
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
            structure()
            brow()
            ears()
            cheeks()
            eyes()
            nose()
            mouth()
            scars()
        }

        fun sides(f: (Float) -> Unit) { f(-1f); f(1f) }

        /** A ridge (or, cut, a V-groove) through face points (x, z), standing [lift] off the surface, tapering from [r0] to [r1]. */
        fun chain(pts: FloatArray, r0: Float, r1: Float, lift: Float, add: Boolean, material: Int, blend: Float) {
            val n = pts.size / 2
            ridge(pts, FloatArray(n) { i -> r0 + (r1 - r0) * i / (n - 1).coerceAtLeast(1) }, lift, 1f, add, material, blend)
        }

        /** A ridge through face points (x, z) with its own [radii] at each, [aspect] times as wide as it stands. */
        fun ridge(pts: FloatArray, radii: FloatArray, lift: Float, aspect: Float, add: Boolean, material: Int, blend: Float, smooth: Boolean = false) {
            val n = pts.size / 2
            // Kept on the face, however the jaw and outline narrow it.
            fun onFace(x: Float, z: Float): Float { val lim = hw * 0.92f * taper((z / hh).coerceIn(-1f, 1f)); return x.coerceIn(-lim, lim) }
            val xyz = FloatArray(n * 3)
            for (i in 0 until n) {
                val z = pts[i * 2 + 1]; val x = onFace(pts[i * 2], z)
                xyz[i * 3] = x; xyz[i * 3 + 1] = front(x, z) + lift; xyz[i * 3 + 2] = z
            }
            val shape = Ridge(xyz, radii, aspect)
            if (add) sc.add(shape, material, blend, smooth) else sc.cut(shape, material, blend, smooth)
        }

        /** Points (x, z) along a curve through [n] + 1 samples of t from -1 to 1. */
        fun curve(n: Int, f: (Float) -> Pair<Float, Float>): FloatArray = FloatArray((n + 1) * 2) { k ->
            val t = -1f + 2f * (k / 2) / n
            val (x, z) = f(t)
            if (k % 2 == 0) x else z
        }

        /**
         * The face under the features, as a carver roughs it out before any
         * detail: the eyes' orbits sunk so the brow stands over them, a mound
         * for the mouth to sit on, and the chin.
         */
        fun structure() {
            val od = 0.024f * k(Dial.ORBITS, 0f, 2.2f)
            if (od > 0.002f) {
                val tilt = c(Dial.EYE_TILT) * 0.9f
                sides { sd ->
                    val odd = if (sd > 0f) asym else 0f
                    val sz = k(Dial.EYE_SIZE, 0.5f, 1.7f).pow(0.6f) * (1f + 0.25f * odd)
                    val x = sd * ex + sd * odd * hw * 0.06f; val z = eyeZ + odd * hh * 0.07f
                    val y = front(x, z)
                    val rx = min(hw * 0.36f * sz, hw * 0.46f); val rz = hh * 0.15f * sz
                    // A dish sunk into the face: steep under the brow, easing out onto the cheek.
                    sc.cut(Ellipsoid(x, y + 0.07f - od, z + hh * 0.015f, rx, 0.07f, rz, tilt * sd), FACE, 0.05f, smooth = true)
                }
            }
            if (od > 0.002f) {
                // The eye band: one shallow trough across both eyes under the brow, so the brow overhangs them and the
                // cheeks rise out of it, the way the face is roughed out before the eyes are cut.
                val y = front(0f, eyeZ)
                sc.cut(Ellipsoid(0f, y + 0.05f - od * 0.55f, eyeZ - hh * 0.01f, hw * 0.95f, 0.05f, hh * 0.13f), FACE, 0.06f, smooth = true)
            }
            val muzzle = 0.02f * k(Dial.MUZZLE, 0f, 2.2f)
            val mw = hw * 0.44f * k(Dial.MOUTH_SIZE, 0.55f, 1.5f)
            if (muzzle > 0.002f && s.mouth != Mouth.BOX) {
                val mx = -asym * hw * 0.1f
                // The mouth's mound: a rounded volume swelling out of the face, no rim.
                val mr = min(mw * 1.45f, hw * 0.8f * taper(mouthZ / hh))
                sc.add(Ellipsoid(mx, front(mx, mouthZ) - mr * 0.55f + muzzle, mouthZ - hh * 0.03f, mr, mr * 0.6f, hh * 0.2f), FACE, 0.06f, smooth = true)
            }
            // The chin: a small mound, cut clean.
            val zc = -hh * 0.84f
            val cr = hw * 0.22f * taper(zc / hh)
            sc.add(Ellipsoid(0f, front(0f, zc) - cr * 0.6f + 0.01f, zc, cr, cr * 0.65f, hh * 0.08f), FACE, 0.04f, smooth = true)
        }

        fun arc(x0: Float, z0: Float, xm: Float, zm: Float, x1: Float, z1: Float, n: Int = 8): FloatArray = FloatArray((n + 1) * 2) { k ->
            val t = (k / 2) / n.toFloat(); val u = 1 - t
            if (k % 2 == 0) u * u * x0 + 2 * u * t * xm + t * t * x1 else u * u * z0 + 2 * u * t * zm + t * t * z1
        }

        fun brow() {
            val bw = k(Dial.BROW_WEIGHT, 0.45f, 1.9f)
            when (s.brow) {
                Brow.NONE -> Unit
                // The arches spring from the root of the nose, so brow and nose are one T-shaped form, as carved.
                Brow.ARCH -> sides { sd -> chain(arc(sd * hw * 0.03f, browZ - hh * 0.05f, sd * ex * 0.85f, browZ + hh * 0.06f, sd * (ex + hw * 0.38f), browZ - hh * 0.1f), 0.02f * bw, 0.012f * bw, 0.004f * bw, true, BROW, 0.02f) }
                Brow.HEART -> sides { sd ->
                    // The heart: from the temple over the eye to the top of the nose, then down the cheek to the chin.
                    chain(arc(sd * hw * 0.86f, eyeZ - hh * 0.05f, sd * ex * 1.1f, browZ + hh * 0.08f, 0f, browZ - hh * 0.02f), 0.012f * bw, 0.012f * bw, 0.002f, true, FACE, 0.016f)
                    chain(arc(sd * hw * 0.86f, eyeZ - hh * 0.05f, sd * hw * 0.8f, -hh * 0.55f, 0f, -hh * 0.93f), 0.01f * bw, 0.008f * bw, 0.001f, true, FACE, 0.016f)
                }
                // A heavy brow: one ridge sweeping across over both eyes, dipping to the nose's root, flowing into the temples.
                Brow.SHELF -> {
                    val pts = curve(12) { t -> (hw * 0.84f * t) to (browZ + hh * 0.01f - hh * 0.05f * t * t - hh * 0.025f * (1f - abs(t)).pow(6f)) }
                    ridge(pts, FloatArray(13) { i -> val t = -1f + i / 6f; (0.02f + 0.012f * (1f - t * t)) * bw }, 0.006f * bw, 2.2f, true, BROW, 0.035f, smooth = true)
                }
                // A domed forehead: a broad, low swell above the brow, melting into the face round it.
                Brow.BULGE -> sc.add(Ellipsoid(0f, front0 - hw * 0.2f, browZ + hh * 0.22f, hw * 0.72f, hw * 0.24f * bw, hh * 0.26f), FACE, 0.1f, smooth = true)
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
                val x = sd * hw * 0.5f; val z = min(-hh * 0.2f, eyeZ - hh * 0.3f)
                val y = front(x, z)
                if (f > 0f) sc.add(Ellipsoid(x, y - hw * 0.14f + 0.008f + 0.025f * f, z, hw * 0.26f, hw * 0.15f, hh * 0.17f), FACE, 0.07f, smooth = true)
                else {
                    // Sunken cheeks: a flat facet taken off under the cheekbone, one plane, as a knife would.
                    val depth = -0.0065f * f
                    val nx = sd * 0.4f; val ny = 1f; val nz = -0.25f; val nl = sqrt(nx * nx + ny * ny + nz * nz)
                    val px = x; val py = y - depth; val pz = z; val rad = hw * 0.6f
                    sc.cut(Custom(x - rad, y - rad, z - rad, x + rad, y + rad, z + rad) { qx, qy, qz ->
                        val over = ((qx - px) * nx + (qy - py) * ny + (qz - pz) * nz) / nl
                        max(-over, sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py) + (qz - pz) * (qz - pz)) - rad)
                    }, FACE, 0.02f)
                }
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
                        sc.add(Cap(x, y - 0.004f, z + 0.006f, hw * 0.32f * sz, hh * 0.1f * sz, 0.036f * lid, er), FACE, 0.012f)
                        sc.cut(RoundBox(x, y + 0.03f, z, hw * 0.26f * sz, 0.06f + 0.03f * lid, 0.008f * sz, 0.005f, bx[0], bx[1], bx[2]), EYE)
                    }
                    Eyes.BEAN -> {
                        sc.add(Cap(x, y - 0.004f, z, hw * 0.33f * sz, hh * 0.12f * sz, 0.05f * lid, er), FACE, 0.012f)
                        sc.cut(RoundBox(x, y + 0.045f, z, hw * 0.27f * sz, 0.07f + 0.03f * lid, 0.0075f * sz, 0.005f, bx[0], bx[1], bx[2]), EYE)
                    }
                    Eyes.TUBE -> {
                        sc.add(Cylinder(x, y + 0.045f * lid, z, hw * 0.2f * sz, 0.06f * lid, 0.014f), FACE, 0.035f, smooth = true)
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
                    Eyes.DOWNCAST -> {
                        // A heavy lid swelling down over the eye, the opening only a slit curving along its lower edge (as the Pende carve them).
                        sc.add(Cap(x, y - 0.004f, z + hh * 0.01f, hw * 0.36f * sz, hh * 0.15f * sz, 0.055f * lid, er - sd * 0.12f), FACE, 0.014f)
                        val w = hw * 0.27f * sz
                        chain(arc(x - w, z - hh * 0.03f * sz - sd * tilt * w, x, z - hh * 0.085f * sz, x + w, z - hh * 0.03f * sz + sd * tilt * w), 0.009f * sz, 0.009f * sz, 0.003f, false, EYE, 0f)
                    }
                    Eyes.BULGING -> {
                        // A dome standing out of the face, cut across with a slit.
                        val rd = hw * 0.2f * sz * sqrt(lid)
                        sc.add(Sphere(x, y - rd * 0.3f, z, rd), FACE, 0.02f, smooth = true)
                        sc.cut(RoundBox(x, y + rd * 0.7f, z, rd * 0.62f, rd * 0.5f, 0.0075f * sz, 0.003f, bx[0], bx[1], bx[2]), EYE)
                    }
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
            /** A nose as a carver cuts it: a prism, its bridge a sharp ridge, its sides flat planes down to the face, its underside cut square. */
            fun prism(top: Float, tip: Float, halfWidth: Float, tz: Float = tipZ) =
                // Grown out of the face, not set on it: its sides sweep smoothly into the cheeks.
                sc.add(Wedge(0f, y0 - 0.006f, topZ, nx, yt - 0.002f, tz, top, tip, aspect = halfWidth / tip), FACE, 0.028f, smooth = true)
            when (s.nose) {
                Nose.NONE -> Unit
                Nose.LONG -> {
                    prism(0.022f * nb, 0.075f * nb, 0.058f * ns)
                    alae(nx, yt, ns, nb)
                    nostrils(nx, yt + 0.05f * nb, ns)
                }
                Nose.BROAD -> {
                    prism(0.02f * nb, 0.05f * nb, 0.07f * ns, tipZ + hh * 0.03f)
                    sc.add(Cap(nx, yt - 0.004f, tipZ, hw * 0.3f * ns, hh * 0.1f * ns, 0.05f * nb), FACE, 0.012f)
                    nostrils(nx, yt + 0.05f * nb, ns)
                }
                Nose.TRIANGLE -> {
                    prism(0.012f * nb, 0.07f * nb, 0.085f * ns)
                    alae(nx, yt, ns, nb)
                    nostrils(nx, yt + 0.05f * nb, ns)
                }
                Nose.UPTURNED -> {
                    // A short bridge that turns up at the tip, the nostrils open to the front.
                    prism(0.02f * nb, 0.055f * nb, 0.05f * ns)
                    sc.add(Wedge(nx, yt + 0.035f * nb, tipZ - hh * 0.01f, nx, yt + 0.08f * nb, tipZ + hh * 0.07f, 0.03f * nb, 0.012f * nb, 0f, 1f, 0f, 1.3f), FACE, 0.012f, smooth = true)
                    nostrils(nx, yt + 0.045f * nb, ns)
                }
                Nose.BEAK -> {
                    prism(0.02f * nb, 0.09f * nb, 0.042f * ns, tipZ + hh * 0.05f)
                    sc.add(Wedge(nx, yt + 0.06f * nb, tipZ + hh * 0.05f, nx, yt + 0.09f * nb, tipZ - hh * 0.08f, 0.03f * nb, 0.008f, 0f, 1f, 0f, 0.8f), FACE, 0.008f)
                }
            }
        }

        /** The nostrils' wings, either side of the tip, and the groove from the nose down to the lip. */
        fun alae(nx: Float, yt: Float, ns: Float, nb: Float) {
            sides { sd -> sc.add(Cap(nx + sd * 0.036f * ns, yt - 0.004f, tipZ + 0.008f, 0.024f * ns, 0.02f * ns, 0.03f * nb), FACE, 0.016f, smooth = true) }
            if (s.mouth == Mouth.CLOSED || s.mouth == Mouth.PURSED) {
                val mx = -asym * hw * 0.1f
                chain(floatArrayOf(nx, tipZ - 0.02f, (nx + mx) / 2f, (tipZ + mouthZ) / 2f, mx, mouthZ + 0.03f), 0.004f, 0.005f, 0f, false, FACE, 0.002f)
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
                    // Carved lips: the upper drawn in a bow, dipping at the middle; the lower fuller; both tapering into the corners.
                    val upper = curve(12) { t -> (mx + mw * t) to (mouthZ + 0.004f + 0.017f * lf * (1f - t * t) * (1f - 0.4f * exp(-(t / 0.2f) * (t / 0.2f)))) }
                    ridge(upper, FloatArray(13) { i -> val t = -1f + i / 6f; 0.005f + 0.017f * lf * (1f - t * t).pow(0.7f) }, 0f, 1.5f, true, LIP, 0.006f)
                    val lower = curve(12) { t -> (mx + mw * 0.94f * t) to (mouthZ - 0.005f - 0.02f * lf * (1f - t * t)) }
                    ridge(lower, FloatArray(13) { i -> val t = -1f + i / 6f; 0.005f + 0.021f * lf * (1f - t * t).pow(0.6f) }, 0f, 1.7f, true, LIP, 0.006f)
                    sc.cut(RoundBox(mx, y + 0.06f * lf, mouthZ, mw * 0.9f, 0.05f, 0.0035f, 0.002f), MOUTH)
                }
                Mouth.OPEN, Mouth.TEETH -> {
                    // Lips drawn back round an open mouth, cut deep.
                    val oh = hh * 0.075f
                    val upper = curve(12) { t -> (mx + mw * t) to (mouthZ + 0.004f + oh * sqrt(1f - t * t)) }
                    ridge(upper, FloatArray(13) { i -> val t = -1f + i / 6f; 0.008f + 0.014f * lf * sqrt(1f - t * t) }, 0f, 1.4f, true, LIP, 0.006f)
                    val lower = curve(12) { t -> (mx + mw * t) to (mouthZ - 0.004f - oh * sqrt(1f - t * t)) }
                    ridge(lower, FloatArray(13) { i -> val t = -1f + i / 6f; 0.008f + 0.018f * lf * sqrt(1f - t * t) }, 0f, 1.5f, true, LIP, 0.006f)
                    sc.cut(Ellipsoid(mx, y + 0.02f, mouthZ, mw * 0.88f, 0.08f, oh), MOUTH, 0.004f)
                    if (s.mouth == Mouth.TEETH) {
                        // Filed teeth, points meeting in the middle.
                        val n = 6
                        for (row in listOf(1f, -1f)) for (i in 0 until n) {
                            val u = -0.72f + 1.44f * i / (n - 1)
                            val x = mx + mw * u
                            val root = mouthZ + row * (oh * sqrt(1f - u * u) + 0.004f)
                            sc.add(Wedge(x, y + 0.004f, root, x, y + 0.004f, mouthZ + row * 0.003f, mw * 0.085f, 0.0025f, aspect = 0.8f), TEETH)
                        }
                    }
                }
                Mouth.PURSED -> {
                    val ring = curve(14) { t -> val a = PI.toFloat() * t; (mx + mw * 0.42f * cos(a)) to (mouthZ + mw * 0.3f * sin(a)) }
                    ridge(ring, FloatArray(15) { 0.013f * lf }, 0f, 1.2f, true, LIP, 0.006f)
                    sc.cut(Sphere(mx, y + 0.045f * lf, mouthZ, 0.012f), MOUTH)
                }
                Mouth.BOX -> {
                    sc.add(RoundBox(mx, y + 0.03f * lf, mouthZ, mw, 0.045f * lf, hh * 0.08f, 0.02f), LIP, 0.035f, smooth = true)
                    sc.cut(RoundBox(mx, y + 0.07f * lf, mouthZ, mw * 0.78f, 0.05f, hh * 0.028f, 0.006f), MOUTH)
                }
                Mouth.TUBE -> {
                    sc.add(Cylinder(mx, y + 0.035f * lf, mouthZ, mw * 0.55f, 0.04f * lf, 0.01f), LIP, 0.016f)
                    sc.cut(Cylinder(mx, y + 0.07f * lf, mouthZ, mw * 0.3f, 0.06f * lf), MOUTH)
                }
                Mouth.GRIN -> {
                    // A crescent grin stretched wide across the lower face, its corners lifted, a row of teeth along it.
                    val gw = min(mw * 1.4f, hw * 0.8f * taper(mouthZ / hh))
                    fun smile(t: Float) = mouthZ - 0.012f + 0.04f * t * t
                    val upper = curve(14) { t -> (mx + gw * t) to (smile(t) + 0.016f + 0.006f * (1f - t * t)) }
                    ridge(upper, FloatArray(15) { i -> val t = -1f + i / 7f; 0.005f + 0.011f * lf * (1f - t * t).pow(0.6f) }, 0f, 1.5f, true, LIP, 0.006f)
                    val lower = curve(14) { t -> (mx + gw * 0.96f * t) to (smile(t) - 0.02f - 0.012f * (1f - t * t)) }
                    ridge(lower, FloatArray(15) { i -> val t = -1f + i / 7f; 0.005f + 0.014f * lf * (1f - t * t).pow(0.6f) }, 0f, 1.6f, true, LIP, 0.006f)
                    val opening = curve(14) { t -> (mx + gw * 0.95f * t) to smile(t) }
                    ridge(opening, FloatArray(15) { i -> val t = -1f + i / 7f; 0.006f + 0.022f * (1f - t * t).pow(0.5f) }, 0.016f, 1.4f, false, MOUTH, 0.003f)
                    val n = 8
                    for (i in 0 until n) {
                        val t = -0.8f + 1.6f * i / (n - 1)
                        val x = mx + gw * 0.95f * t; val z = smile(t)
                        sc.add(Wedge(x, front(x, z) + 0.004f, z + 0.013f * (1f - 0.4f * t * t), x, front(x, z) + 0.004f, z - 0.004f, gw * 0.06f, 0.002f, aspect = 0.8f), TEETH)
                    }
                }
            }
        }

        /** A keloid as carved: a small raised lozenge with a sharp crest. */
        fun keloid(x: Float, z: Float, r: Float) {
            val y = front(x, z)
            sc.add(Wedge(x - r * 1.2f, y - r * 0.3f, z, x + r * 1.2f, y - r * 0.3f, z, r, r), FACE, 0.003f)
        }

        /**
         * Signs in the manner of nsibidi, the ideographic script of the Cross
         * River region (used by the Ekpe society, the Igbo and their
         * neighbours), incised as a carver cuts them: rings, crossings, ladders
         * and paired arcs. Composed from that stroke vocabulary, not copied
         * glyphs, so they claim no meaning. On the brow, or on the cheeks when
         * ichi already take the brow.
         */
        fun nsibidi(q: Float) {
            val g = 0.0035f * q
            fun ring(cx: Float, cz: Float, rad: Float) = chain(curve(14) { t -> val a = PI.toFloat() * t; (cx + rad * cos(a)) to (cz + rad * sin(a)) }, g, g, 0.001f, false, SCAR, 0f)
            fun bar(x0: Float, z0: Float, x1: Float, z1: Float) = chain(floatArrayOf(x0, z0, x1, z1), g, g, 0.001f, false, SCAR, 0f)
            val u = hh * 0.045f
            val signs = listOf<(Float, Float) -> Unit>(
                { x, z -> ring(x, z, u); bar(x - u * 1.4f, z, x + u * 1.4f, z); bar(x, z - u * 1.4f, x, z + u * 1.4f) },
                { x, z -> bar(x - u * 0.6f, z - u, x - u * 0.6f, z + u); bar(x + u * 0.6f, z - u, x + u * 0.6f, z + u); for (i in -1..1) bar(x - u * 0.6f, z + i * u * 0.6f, x + u * 0.6f, z + i * u * 0.6f) },
                { x, z -> chain(arc(x - u, z + u, x - u * 0.2f, z, x - u, z - u), g, g, 0.001f, false, SCAR, 0f); chain(arc(x + u, z + u, x + u * 0.2f, z, x + u, z - u), g, g, 0.001f, false, SCAR, 0f); bar(x - u * 0.4f, z, x + u * 0.4f, z) },
                { x, z -> ring(x - u * 0.5f, z, u * 0.55f); ring(x + u * 0.5f, z, u * 0.55f) },
            ).shuffled(r)
            if (ichiOn) sides { sd -> signs[if (sd < 0f) 0 else 1](sd * hw * 0.55f, -hh * 0.3f) }
            else for (i in -1..1) signs[i + 1](i * hw * 0.36f, browZ + hh * 0.17f)
        }

        fun scars() {
            val q = k(Dial.SCAR_DEPTH, 0.5f, 1.9f)
            for (m in s.scars) when (m) {
                // Cut into the head itself, so they flow over its curve: see [ichi].
                Scar.ICHI -> Unit
                Scar.NSIBIDI -> nsibidi(q)
                Scar.TEMPLES -> sides { sd -> for (i in 0 until 3) { val z = browZ - hh * 0.02f + i * hh * 0.05f; chain(floatArrayOf(sd * hw * 0.66f, z, sd * hw * 0.86f, z - hh * 0.01f), 0.0045f * q, 0.0045f * q, 0.001f, false, SCAR, 0f) } }
                Scar.CHEEKS -> sides { sd -> for (i in 0 until 3) { val z = -hh * 0.2f - i * hh * 0.06f; chain(floatArrayOf(sd * hw * 0.42f, z, sd * hw * 0.72f, z - hh * 0.05f), 0.005f * q, 0.005f * q, 0.001f, false, SCAR, 0f) } }
                Scar.TEARS -> sides { sd -> chain(floatArrayOf(sd * ex, eyeZ - hh * 0.07f, sd * ex * 1.05f, -hh * 0.3f, sd * ex * 1.1f, -hh * 0.45f), 0.005f * q, 0.004f * q, 0.001f, false, SCAR, 0f) }
                Scar.KELOID_DIAMONDS -> {
                    fun diamond(cx: Float, cz: Float, sz: Float) {
                        for (i in -2..2) for (j in -2..2) if (abs(i) + abs(j) == 2 || (i == 0 && j == 0)) {
                            val x = cx + i * sz; val z = cz + j * sz
                            keloid(x, z, 0.0075f * q)
                        }
                    }
                    diamond(0f, browZ + hh * 0.16f, hh * 0.035f)
                    sides { sd -> diamond(sd * hw * 0.78f, eyeZ + hh * 0.02f, hh * 0.028f) }
                }
                Scar.CHEEK_KELOIDS -> sides { sd -> for (i in 0 until 3) for (j in 0 until 2) { val x = sd * (hw * 0.45f + i * hw * 0.1f); val z = -hh * 0.18f - j * hh * 0.07f; keloid(x, z, 0.007f * q) } }
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
                val swell = shell.d(x, y, z) - 0.0015f * hv
                val above = hairZ(y) - z
                // Pressed back with the face, so the hair never stands out over the brow like a bowl.
                max(max(swell, above), y - facePlane(x, z) - 0.002f)
            }
            val cap = if (cornrows) Displaced(capBase, 0.008f) { x, y, _ -> 0.005f * vee(atan2(x, y + dy * 0.3f) * rows / PI.toFloat()) }
            // Otherwise the hair is a polished mass, combed in a few broad, shallow grooves from brow to crown.
            else Displaced(capBase, 0.004f) { x, y, _ -> 0.0025f * groove(atan2(x, y + dy * 0.3f) * rows * 0.35f / PI.toFloat()) }
            sc.add(cap, HAIR, 0.008f, smooth = true)
            // The hairline: a clean incised line where hair meets face.
            val line = curve(14) { t -> val x = hw * 0.78f * t; x to hairZ(facePlane(x, hh * 0.5f)) }
            ridge(line, FloatArray(15) { 0.004f }, 0.002f, 1.2f, false, HAIR, 0f)
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
                Coiffure.ARCHES -> {
                    // Broad arched crests standing front to back over the head, as on Agbogho Mmuo: the middle one tallest,
                    // studded down its back with knobs, each banded in camwood along its outer rim.
                    val n = s.count.coerceIn(1, 3).let { if (it == 2) 3 else it }
                    for (i in 0 until n) {
                        val off = if (n == 1) 0f else (i - (n - 1) / 2f)
                        val x = off * hw * 0.5f
                        val cy = -dy * 0.35f; val cz = top - hh * 0.2f
                        val ry = max(dy * 1.3f, hw * 1.05f) * (1f - 0.12f * abs(off)) * hv
                        val rz = hh * (0.8f + 0.5f * s.crownSize) * (1f - 0.22f * abs(off)) * hv
                        val band = hw * 0.19f * hv; val depth = hw * 0.07f * hv
                        val segs = 16
                        for (k in 0 until segs) {
                            val a0 = PI.toFloat() * k / segs; val a1 = PI.toFloat() * (k + 1) / segs; val am = (a0 + a1) / 2f
                            val len = sqrt((ry * (cos(a1) - cos(a0))).let { it * it } + (rz * (sin(a1) - sin(a0))).let { it * it }) / 2f + depth * 0.6f
                            // A flat band: wide across the head, thin through the arch, its length along it.
                            val ax = RoundBox.axes(pitch = atan2(rz * cos(am), -ry * sin(am)))
                            sc.add(RoundBox(x, cy + ry * cos(am), cz + rz * sin(am), band, len, depth, depth * 0.5f, ax[0], ax[1], ax[2]), HAIR, 0.01f)
                            val o = 1f + depth * 1.1f / min(ry, rz)
                            sc.add(RoundBox(x, cy + ry * o * cos(am), cz + rz * o * sin(am), band * 0.9f, len, depth * 0.35f, depth * 0.2f, ax[0], ax[1], ax[2]), ARCH_BAND, 0.004f)
                        }
                        if (abs(off) < 0.1f) {
                            val knobs = 9
                            for (k in 0 until knobs) {
                                val a = PI.toFloat() * (0.1f + 0.55f * k / (knobs - 1))
                                val o = 1f + depth * 2.4f / min(ry, rz)
                                sc.add(Sphere(x, cy + ry * o * cos(a), cz + rz * o * sin(a), band * 0.45f), KNOB + k % 2, 0.008f)
                            }
                        }
                    }
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
            extra.group = HORN
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
                    // Seated down over the crown, so the superstructure rests on the head.
                    var z = top - hh * 0.2f
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
                Crown.HORN_ROW -> {
                    // A row of straight horns standing side by side over the brow (the Bamana n'tomo).
                    val n = s.count.coerceIn(2, 6)
                    for (i in 0 until n) {
                        val u = if (n == 1) 0f else (i - (n - 1) / 2f) / ((n - 1) / 2f)
                        val x = u * hw * 0.62f * spread
                        val pts = TubeMesh.curve(
                            x, -dy * 0.02f, top - hh * 0.1f,
                            x * 1.06f, -dy * 0.08f * curl, top + L * 0.4f,
                            x * 1.12f, -dy * 0.16f * curl, top + L * (0.8f - 0.08f * abs(u)),
                        )
                        extra.sweep(pts, detailRings, sides, { t -> hw * 0.085f * girth * (1f - 0.85f * t) * TubeMesh.ridge(t, 6f, 0.08f * ridges) }, ::hornColour)
                    }
                }
                Crown.FRAME_HORNS -> sides { sd ->
                    // Horns rising from the crown and sweeping down round both sides of the face (the Kwele ekuk).
                    val pts = TubeMesh.curve(
                        sd * hw * 0.25f, -dy * 0.05f, top - hh * 0.05f,
                        sd * hw * 0.95f * spread, dy * 0.05f, top + hh * 0.12f * curl,
                        sd * hw * 1.35f * spread, dy * 0.15f, hh * 0.35f,
                        sd * hw * 1.38f * spread, dy * 0.22f, -hh * 0.15f,
                        sd * hw * 1.08f * spread, dy * 0.3f, -hh * 0.55f * curl,
                    )
                    extra.sweep(pts, detailRings * 2, sides, { t -> hw * 0.11f * girth * (1f - 0.7f * t) * TubeMesh.ridge(t, 10f, 0.06f * ridges) }, ::hornColour)
                }
                Crown.BIRD -> {
                    val z = top + hh * 0.12f
                    sc.add(Ellipsoid(0f, -dy * 0.1f, z, hw * 0.2f, hw * 0.34f, hh * 0.13f), CREST, 0.04f)
                    sc.add(Sphere(0f, dy * 0.25f, z + hh * 0.14f, hw * 0.13f), CREST, 0.02f)
                    sc.add(RoundCone(0f, dy * 0.32f, z + hh * 0.14f, 0f, dy * 0.95f, z + hh * 0.02f, hw * 0.075f, 0.006f), CREST, 0.01f)
                }
            }
            extra.group = 0
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
                Adorn.LIP_PLUG -> {
                    // A disc set in the upper lip, pale as ivory or bone.
                    val mx = -asym * hw * 0.1f; val z = mouthZ + 0.022f
                    sc.add(Cylinder(mx, front(mx, z) + 0.01f, z, hw * 0.16f, 0.012f, 0.004f), TEETH, 0.004f)
                }
                Adorn.NECK_RINGS -> {
                    // Rings of the neck, a sign of beauty and plenty (the Mende sowei).
                    for (i in 0 until 3) sc.add(Torus(0f, -dy * 0.15f, -hh * (1.0f + 0.13f * i), hw * (0.8f - 0.06f * i), 0.032f, floatArrayOf(0f, 0f, 1f)), FACE, 0.012f)
                }
            }
            if (s.beard == Beard.RAFFIA) raffia(30, hh * 0.9f, collar = false)
        }

        /** Raffia strands, [count] of them, hanging [length]: round the rim as a collar, or from the chin as a beard. */
        fun raffia(count: Int, length: Float, collar: Boolean) {
            val full = (count * (0.45f + 1.1f * s.raffia)).toInt().coerceAtLeast(6)
            val n = if (detail.cell < 0.01f) full else full / 2
            val straw = if (s.raffiaDye == MaskSpec.NATURAL) STRAW else STRAW.map { mix(Pigments.argb(s.raffiaDye), it, 0.22f) }.toIntArray()
            val dyed = if (s.finish == Finish.POLYCHROME || s.finish == Finish.BLACKENED) intArrayOf(Pigments.argb(s.accent), 0xFF2A1A12.toInt()) else straw
            extra.group = RAFFIA
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
            extra.group = 0
        }

        // ---- colour and assembly ------------------------------------------------------------

        val patina = k(Dial.PATINA, 0.2f, 1.8f)
        val grainK = k(Dial.GRAIN, 0f, 2.6f)
        val wearAt = 0.98f - 0.24f * s.dial(Dial.WEAR)
        val ps = k(Dial.PATTERN_SCALE, 0.5f, 1.9f)

        /**
         * The carved surface coloured, before it is a mesh: everything in face
         * units, the field's vertices first and the swept parts' after, with
         * which primitive and group each swept vertex came from.
         */
        inner class Coloured(
            val positions: FloatArray, val normals: FloatArray, val colours: IntArray, val channels: ByteArray, val glows: IntArray,
            val indices: IntArray, val fieldVertices: Int, val prims: IntArray, val groups: IntArray,
        ) { val vertexCount: Int get() = positions.size / 3 }

        /** Face units to model units: the face stands about 0.64 tall, centred on the eye line's middle. */
        val toModel: Float get() = 0.64f / (2f * hh)

        fun assemble(): SpiritMesh = mesh(colour(SurfaceNets(sc, detail.cell).mesh(), null))

        /** Colours every vertex of [net] and the swept parts; [core] lights the raw faces of a fracture. */
        fun colour(net: SurfaceNets.Result, core: Spirit.Core?): Coloured {
            val nv = net.vertexCount
            val tp = extra.pos.toArray(); val tn = extra.nrm.toArray()
            val total = nv + tp.size / 3
            val positions = FloatArray(total * 3); val normals = FloatArray(total * 3)
            val colours = IntArray(total); val channels = ByteArray(total); val glowColours = IntArray(total)
            net.positions.copyInto(positions); net.normals.copyInto(normals)
            for (i in 0 until nv) {
                val x = net.positions[i * 3]; val y = net.positions[i * 3 + 1]; val z = net.positions[i * 3 + 2]
                val nx = net.normals[i * 3]; val ny = net.normals[i * 3 + 1]; val nz = net.normals[i * 3 + 2]
                val m = net.materials[i]
                // A split face lies inside the unbroken carving, where no light or wear has reached.
                val split = m == FRACTURE
                val cav = if (split) 1f else cavity(x, y, z, nx, ny, nz)
                bendHere = if (split) 0f else bend(x, y, z)
                val ichiCut = m == FACE && ichiOn && ichi(x, y, z) > 0.5f
                var c = surface(if (ichiCut) SCAR else m, x, y, z, ny, cav)
                c = scale(c, (1f - 0.5f * patina * (1f - cav)).coerceAtLeast(0.15f))
                if (m != EYE && m != MOUTH && !split) {
                    // Handled wood: its edges and high points burnished warm and bright, its hollows dark with old oil and dust.
                    val burnish = max(0f, bendHere); val hollow = max(0f, -bendHere)
                    c = mix(c, 0xFFE9CDA2.toInt(), 0.2f * burnish * patina.coerceAtMost(1.4f))
                    c = scale(c, 1f - 0.35f * hollow * patina.coerceAtMost(1.4f))
                }
                colours[i] = c
                when {
                    m == EYE -> { channels[i] = GlowChannel.EYES; glowColours[i] = s.glow }
                    // Embers in the split, not a lamp: the core's light only tints the raw wood.
                    split -> { channels[i] = GlowChannel.CORE; glowColours[i] = mix(c, core?.glow ?: s.glow, 0.45f) }
                    m == SCAR || ichiCut || (m == FACE && uliLine(x, z) > 0.5f && Pattern.ULI in s.patterns) -> { channels[i] = GlowChannel.LINES; glowColours[i] = s.glow }
                    m == STUD -> { channels[i] = GlowChannel.CREST; glowColours[i] = mix(s.glow, 0xFFFFFFFF.toInt(), 0.3f) }
                    else -> glowColours[i] = c
                }
            }
            // The swept parts: their triangles faced outward one by one, since curls can turn a tube inside out.
            val tubeTris = extra.idx.toArray()
            for (t in 0 until tubeTris.size / 3) {
                val a = tubeTris[t * 3] * 3; val b = tubeTris[t * 3 + 1] * 3; val c = tubeTris[t * 3 + 2] * 3
                val ux = tp[b] - tp[a]; val uy = tp[b + 1] - tp[a + 1]; val uz = tp[b + 2] - tp[a + 2]
                val vx = tp[c] - tp[a]; val vy = tp[c + 1] - tp[a + 1]; val vz = tp[c + 2] - tp[a + 2]
                val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
                val sx = tn[a] + tn[b] + tn[c]; val sy = tn[a + 1] + tn[b + 1] + tn[c + 1]; val sz = tn[a + 2] + tn[b + 2] + tn[c + 2]
                if (fx * sx + fy * sy + fz * sz < 0f) { val tmp = tubeTris[t * 3 + 1]; tubeTris[t * 3 + 1] = tubeTris[t * 3 + 2]; tubeTris[t * 3 + 2] = tmp }
            }
            val tc = extra.col.toArray(); val tg = extra.glow.toArray()
            tp.copyInto(positions, nv * 3); tn.copyInto(normals, nv * 3)
            for (j in 0 until tp.size / 3) { colours[nv + j] = tc[j]; channels[nv + j] = tg[j].toByte(); glowColours[nv + j] = tc[j] }
            val indices = IntArray(net.indices.size + tubeTris.size)
            net.indices.copyInto(indices)
            for (t in tubeTris.indices) indices[net.indices.size + t] = tubeTris[t] + nv
            val prims = IntArray(total) { -1 }; val groups = IntArray(total)
            val ep = extra.prims.toArray(); val eg = extra.groups.toArray()
            for (j in ep.indices) { prims[nv + j] = ep[j]; groups[nv + j] = eg[j] }
            return Coloured(positions, normals, colours, channels, glowColours, indices, nv, prims, groups)
        }

        /** The eyes in model units, left then right. */
        fun eyesInModel(): FloatArray {
            val k = toModel
            val eyes = FloatArray(6)
            eyeCentres.take(2).forEachIndexed { e, p -> eyes[e * 3] = p[0] * k; eyes[e * 3 + 1] = (p[1] + 0.01f) * k; eyes[e * 3 + 2] = p[2] * k }
            return eyes
        }

        fun straw(): IntArray = when {
            s.raffiaDye != MaskSpec.NATURAL -> intArrayOf(mix(Pigments.argb(s.raffiaDye), STRAW[0], 0.22f), mix(Pigments.argb(s.raffiaDye), STRAW[2], 0.3f))
            s.finish == Finish.POLYCHROME -> intArrayOf(STRAW[0], Pigments.argb(s.accent))
            else -> intArrayOf(STRAW[0], STRAW[2])
        }

        /** The whole coloured carving as one mesh, in model units. */
        fun mesh(c: Coloured): SpiritMesh {
            val k = toModel
            val positions = FloatArray(c.positions.size) { c.positions[it] * k }
            return SpiritMesh(
                positions, c.normals, c.colours, c.channels, c.glows, c.indices, eyesInModel(),
                auraColor = s.glow, auraSecond = Pigments.argb(s.accent), height = 1f, faceColor = finishColour(0f, 0f, 0f),
                fringeColors = straw(), charmColor = 0xFFF3EAD6.toInt(),
            )
        }

        // ---- breaking it open ---------------------------------------------------------------

        /**
         * The mask broken open by [spirit]: carved whole, then cracked along the
         * fracture's cells in the same meshing pass (the cracks are one more cut
         * in the field), coloured as one piece so its paint and wear run on
         * unbroken across the breaks, and split into floating pieces.
         */
        fun shatter(spirit: SpiritSpec): ShatteredSpirit {
            sculptHead(); sculptFace(); sculptCoiffure(); sculptCrown(); sculptAdornments()
            val cells = FractureCells.of(spirit, hw, hh, eyeZ, browZ, mouthZ, crowned = s.crown != Crown.NONE || s.coiffure in TALL_HAIR, cell = detail.cell)
            sc.keepInside(cells.crack, FRACTURE)
            val net = SurfaceNets(sc, detail.cell).mesh()
            sc.parts.removeAt(sc.parts.size - 1)
            return breakApart(colour(net, spirit.core), cells, spirit)
        }

        /**
         * Splits the coloured carving into its pieces: every field vertex goes
         * to its cell; every swept part goes whole, horns each to a piece of
         * their own and the raffia to one hanging shroud. Each piece is built
         * about its centre, given its place to float to and its drift, and the
         * relics and the core are made to go with them.
         */
        fun breakApart(c: Coloured, cells: FractureCells, sp: SpiritSpec): ShatteredSpirit {
            val k = toModel
            val nv = c.vertexCount
            val owner = IntArray(nv)
            var next = cells.n
            val extraRoles = ArrayList<ShardRole>()
            val hornOf = HashMap<Int, Int>(); val primOf = HashMap<Int, Int>(); var shroud = -1
            for (i in 0 until nv) {
                val x = c.positions[i * 3]; val y = c.positions[i * 3 + 1]; val z = c.positions[i * 3 + 2]
                owner[i] = if (i < c.fieldVertices) cells.cellAt(x, y, z) else when (c.groups[i]) {
                    HORN -> hornOf.getOrPut(c.prims[i]) { extraRoles += ShardRole.RELIC; next++ }
                    RAFFIA -> { if (shroud < 0) { shroud = next++; extraRoles += ShardRole.SHROUD }; shroud }
                    else -> primOf.getOrPut(c.prims[i]) { cells.cellAt(x, y, z) }
                }
            }
            fun roleOf(o: Int) = if (o < cells.n) cells.roles[o] else extraRoles[o - cells.n]
            val tris = Array(next) { SurfaceNets.IntBuffer() }
            for (t in 0 until c.indices.size / 3) tris[owner[c.indices[t * 3]]] += t
            val temperament = SpiritCores.temperament(sp.temper)
            val r = Random(sp.seed * 977 + 5)
            // The core sits behind the nose, in the middle of the head.
            val coreX = 0f; val coreY = front0 * 0.1f * k; val coreZ = (eyeZ - hh * 0.2f) * k
            // At no drift the mask stays whole, its cracks hairlines of light, and only breathes.
            val reach = 0.002f + 0.12f * sp.drift
            val shards = ArrayList<SpiritShard>(); val shardOf = IntArray(next) { -1 }
            val pivots = ArrayList<FloatArray>()
            val map = IntArray(nv)
            for (o in 0 until next) {
                val list = tris[o]
                if (list.size < MIN_TRIANGLES) continue
                val role = roleOf(o)
                java.util.Arrays.fill(map, -1)
                var count = 0
                var cx = 0f; var cy = 0f; var cz = 0f
                for (q in 0 until list.size) for (v in 0..2) {
                    val i = c.indices[list.data[q] * 3 + v]
                    if (map[i] < 0) { map[i] = count++; cx += c.positions[i * 3]; cy += c.positions[i * 3 + 1]; cz += c.positions[i * 3 + 2] }
                }
                cx = cx / count * k; cy = cy / count * k; cz = cz / count * k
                val pos = FloatArray(count * 3); val nrm = FloatArray(count * 3); val col = IntArray(count); val ch = ByteArray(count); val gl = IntArray(count)
                // Embers are charred, or bronze where the fire caught the brass.
                val ember = role == ShardRole.EMBER
                val char = if (o % 2 == 0) 0xFF1E1612.toInt() else MaskSculptor.mix(Pigments.argb(Pigments.BRASS), 0xFF5A3A1E.toInt(), 0.35f)
                for (i in 0 until nv) {
                    val j = map[i]; if (j < 0) continue
                    pos[j * 3] = c.positions[i * 3] * k - cx; pos[j * 3 + 1] = c.positions[i * 3 + 1] * k - cy; pos[j * 3 + 2] = c.positions[i * 3 + 2] * k - cz
                    nrm[j * 3] = c.normals[i * 3]; nrm[j * 3 + 1] = c.normals[i * 3 + 1]; nrm[j * 3 + 2] = c.normals[i * 3 + 2]
                    col[j] = if (ember && c.channels[i] != GlowChannel.CORE) MaskSculptor.mix(c.colours[i], char, 0.6f) else c.colours[i]
                    ch[j] = c.channels[i]; gl[j] = c.glows[i]
                }
                val idx = IntArray(list.size * 3)
                for (q in 0 until list.size) for (v in 0..2) idx[q * 3 + v] = map[c.indices[list.data[q] * 3 + v]]
                val mesh = SpiritMesh(pos, nrm, col, ch, gl, idx, FloatArray(6), auraColor = sp.core.glow, auraSecond = sp.core.second, faceColor = finish())
                // Its place: out from the core, mostly across the face, by a share of the drift its part takes.
                var dx = cx - coreX; var dy = (cy - coreY) * 0.25f; var dz = cz - coreZ
                val l = sqrt(dx * dx + dy * dy + dz * dz)
                if (l < 1e-3f) { dx = 0f; dy = 1f; dz = 0f } else { dx /= l; dy /= l; dz /= l }
                val share = when (role) {
                    ShardRole.FACE -> 0.25f; ShardRole.BRIDGE -> 0.5f; ShardRole.HALF_LEFT, ShardRole.HALF_RIGHT -> 1.5f
                    ShardRole.CROWN -> 1.3f; ShardRole.EMBER -> 1.6f; ShardRole.SHROUD -> 0.4f; ShardRole.RELIC -> 1.1f
                    ShardRole.JAW -> 1.1f; else -> 1f
                }
                var rx = dx * reach * share; var ry = dy * reach * share; var rz = dz * reach * share
                when (role) {
                    ShardRole.HALF_LEFT, ShardRole.HALF_RIGHT -> { rx = (if (cx < coreX) -1f else 1f) * reach * share; ry = 0f; rz = 0f }
                    ShardRole.CREST, ShardRole.CROWN -> rz += 0.035f + 0.03f * sp.drift
                    ShardRole.JAW -> rz -= 0.012f
                    ShardRole.FACE, ShardRole.BRIDGE -> ry += 0.006f
                    ShardRole.SHROUD -> rz -= 0.01f
                    else -> Unit
                }
                val bob = (if (ember) 1.8f else if (role == ShardRole.SHROUD) 1.4f else 1f) * 0.6f
                val mass = when (role) { ShardRole.RELIC -> 1.4f; ShardRole.CROWN -> 1.2f; ShardRole.EMBER -> 0.6f; ShardRole.SHROUD -> 0.8f; ShardRole.FACE -> 1.3f; else -> 1f }
                shards += SpiritShard(
                    mesh, role, cx, cy, cz, rx, ry, rz, phase = r.nextFloat() * 6.2832f,
                    bobX = (0.003f + 0.005f * r.nextFloat()) * bob, bobY = (0.002f + 0.003f * r.nextFloat()) * bob, bobZ = (0.004f + 0.006f * r.nextFloat()) * bob, mass = mass,
                )
                shardOf[o] = shards.size - 1
                pivots += floatArrayOf(cx, cy, cz)
            }
            // The relics: a ring round the head at the cheekbones, the halo alone behind it.
            val kinds = sp.relics.sortedBy { it.ordinal }
            val ring = kinds.filter { it != Spirit.Relic.HALO }.flatMap { kind -> List(if (kind == Spirit.Relic.MIRROR || kind == Spirit.Relic.COWRIE) 3 else 2) { kind } }.take(8)
            val ringR = hw * 1.45f * k; val ringZ = (eyeZ - hh * 0.25f) * k
            ring.forEachIndexed { i, kind ->
                val a = 2f * PI.toFloat() * (i + 0.5f) / ring.size + PI.toFloat() / 2f
                val t = SpiritCores.relic(kind, 0.03f, s, sp.core)
                val mesh = turned(SpiritCores.mesh(t, sp.core.glow, sp.core.second), a)
                val px = cos(a) * ringR; val py = coreY + sin(a) * ringR * 0.8f; val pz = ringZ + 0.025f * sin(i * 2.1f)
                shards += SpiritShard(mesh, ShardRole.ORBIT, px, py, pz, 0f, 0f, 0f, r.nextFloat() * 6.2832f, 0.002f, 0.002f, 0.006f, mass = 1.6f)
            }
            if (Spirit.Relic.HALO in sp.relics) {
                val mesh = SpiritCores.mesh(SpiritCores.relic(Spirit.Relic.HALO, 0.03f * (hw / 0.3f) * 3.2f, s, sp.core), sp.core.glow, sp.core.second)
                shards += SpiritShard(mesh, ShardRole.RELIC, 0f, -dy * 1.25f * k, (browZ + hh * 0.1f) * k, 0f, -0.01f, 0f, 0f, 0.001f, 0.001f, 0.004f, mass = 2f)
            }
            // The eyes ride the pieces they were cut in.
            val eyeShards = IntArray(2) { -1 }; val eyes = FloatArray(6)
            eyeCentres.take(2).forEachIndexed { e, p ->
                val o = cells.cellAt(p[0], p[1], p[2]); val si = if (o < shardOf.size) shardOf[o] else -1
                eyeShards[e] = si
                if (si >= 0) { val pv = shards[si]; eyes[e * 3] = p[0] * k - pv.pivotX; eyes[e * 3 + 1] = (p[1] + 0.01f) * k - pv.pivotY; eyes[e * 3 + 2] = p[2] * k - pv.pivotZ }
            }
            val core = SpiritCores.mesh(
                SpiritCores.core(sp.core, 0.065f, sp.seed, detail.cell < 0.01f), sp.core.glow, sp.core.second,
                eyes = eyesInModel().also { for (e in 0..1) { it[e * 3] -= coreX; it[e * 3 + 1] -= coreY; it[e * 3 + 2] -= coreZ } },
                face = finish(), fringe = straw(),
            )
            return ShatteredSpirit(
                core, coreX, coreY, coreZ, shards, temperament, eyeShards, eyes,
                arcs = sp.core == Spirit.Core.THUNDER || sp.temper == Spirit.Temper.STORM, name = s.name,
            )
        }

        /** A mesh turned [a] about z. */
        fun turned(m: SpiritMesh, a: Float): SpiritMesh {
            val c = cos(a); val sn = sin(a)
            val p = m.positions.copyOf(); val n = m.normals.copyOf()
            for (i in 0 until m.vertexCount) {
                val x = p[i * 3]; val y = p[i * 3 + 1]; p[i * 3] = x * c - y * sn; p[i * 3 + 1] = x * sn + y * c
                val nx = n[i * 3]; val ny = n[i * 3 + 1]; n[i * 3] = nx * c - ny * sn; n[i * 3 + 1] = nx * sn + ny * c
            }
            return SpiritMesh(p, n, m.colors, m.channels, m.glowColors, m.indices, m.eyes, m.auraColor, m.auraSecond, m.height, m.faceColor, m.fringeColors, m.charmColor)
        }

        fun finish(): Int = finishColour(0f, 0f, 0f)

        /** How the surface bends at the vertex being coloured: see [bend]. */
        var bendHere = 0f

        /** How the surface bends here: toward 1 over a ridge or an edge, toward -1 in a hollow or a cut, about 0 on the open face. */
        fun bend(x: Float, y: Float, z: Float): Float {
            val h = detail.cell * 1.6f
            val sum = sc.d(x + h, y, z) + sc.d(x - h, y, z) + sc.d(x, y + h, z) + sc.d(x, y - h, z) + sc.d(x, y, z + h) + sc.d(x, y, z - h) - 6f * sc.d(x, y, z)
            return (sum / h * 1.5f - 0.12f).coerceIn(-1f, 1f)
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
            // Grain runs down the block, wandering, never ruled.
            val grain = 1f + grainK * (0.045f * sin(x * 70f + 11f * noise(x * 2.5f, z * 0.7f, y * 2.5f)) + 0.06f * (noise(x * 30f, z * 2.5f, y * 30f) - 0.5f))
            return when {
                m == EYE -> 0xFF1A0806.toInt()
                m == MOUTH -> 0xFF160A08.toInt()
                m == TEETH -> 0xFFEDE4D2.toInt()
                m == COWRIE -> 0xFFF3EAD6.toInt()
                m == STUD -> if (s.finish == Finish.BRASS) 0xFFE8C070.toInt() else Pigments.argb(Pigments.BRASS)
                m == HAIR -> mix(Pigments.argb(s.hair), wood, 0.12f)
                m in TIER until TIER + 4 -> {
                    val band = listOf(s.accent, s.accent2, Pigments.JADE, Pigments.OCHRE)[(m - TIER) % 4]
                    val c = Pigments.argb(band)
                    if (((atan2(y, x) * 8f / PI.toFloat()).toInt() + (m - TIER)) % 2 == 0) c else mix(c, 0xFF1A120E.toInt(), 0.35f)
                }
                m == PLANK -> plankPaint(x, z)
                m == KNOB -> mix(Pigments.argb(Pigments.OCHRE), Pigments.argb(Pigments.KAOLIN), 0.35f)
                m == KNOB + 1 -> mix(Pigments.argb(s.accent2), Pigments.argb(Pigments.OCHRE), 0.3f)
                m == ARCH_BAND -> scale(Pigments.argb(if (s.accent2 == s.hair) Pigments.CAMWOOD else s.accent2), grain)
                m == FRACTURE -> {
                    // Split wood: the raw heart of the block, unweathered, streaked with its grain and scorched by what broke it.
                    val heart = mix(wood, 0xFF8A5A34.toInt(), 0.5f)
                    scale(heart, 0.6f + 0.25f * (grain - 1f) * 3f + 0.1f * sin(x * 140f + 6f * noise(x * 8f, z * 2f, y * 8f)))
                }
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
            // Small chips, not blotches: paint knocked off in flakes.
            val wear = noise(x * 32f + 3f, z * 32f, y * 32f) + 0.2f * noise(x * 90f, z * 90f, y * 90f) - 0.15f
            var c = when (s.finish) {
                Finish.KAOLIN -> {
                    // Chipped most where it stands proud.
                    // Chipped only on edges and high points; the open face keeps its chalk, greyed in the hollows.
                    if (bendHere > 0.25f && wear > wearAt + 0.05f - 0.2f * bendHere) scale(wood, grain) else mix(Pigments.argb(Pigments.KAOLIN), 0xFF6E5A4A.toInt(), 0.04f + 0.22f * (1f - cav))
                }
                Finish.BRASS -> {
                    val green = ((1f - cav) * 1.4f + 0.3f * noise(x * 20f, z * 20f, y * 20f) - 0.2f).coerceIn(0f, 1f)
                    mix(Pigments.argb(Pigments.BRASS), 0xFF3F7A62.toInt(), green * 0.7f)
                }
                Finish.BLACKENED, Finish.POLYCHROME -> scale(if (bendHere > 0.25f && wear > wearAt + 0.04f) mix(finishColour(x, y, z), wood, 0.55f) else finishColour(x, y, z), grain)
                else -> scale(finishColour(x, y, z), grain)
            }
            if (m == LIP && (s.finish == Finish.KAOLIN || s.finish == Finish.POLYCHROME)) c = mix(Pigments.argb(s.accent2), c, 0.35f)
            // The brows painted dark over a whitened or painted face, a bold line as on the dance masks.
            if (m == BROW && (s.finish == Finish.KAOLIN || s.finish == Finish.POLYCHROME || s.finish == Finish.CAMWOOD)) c = mix(Pigments.argb(s.hair), c, 0.12f)
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

    /** A fine incised line at every whole number: 1 in the cut, 0 on the land between. */
    internal fun groove(t: Float): Float = (1f - vee(t) / 0.22f).coerceAtLeast(0f)

    /** A sharp triangle wave, 0 at whole numbers and 1 halfway: V-cut grooves and ridges. */
    internal fun vee(t: Float): Float = abs(2f * (t - floor(t + 0.5f)))

    /**
     * An adze's scoops over the head: 1 at the heart of each shallow scoop,
     * falling to 0 at the sharp ridge where it meets the next. Scoops sit on a
     * jittered grid wrapped round the head, about [ADZE] across.
     */
    internal fun scoop(x: Float, y: Float, z: Float): Float {
        // Strokes run with the grain, down the block: longer than they are wide.
        val u = atan2(x, y) * 0.3f / ADZE; val v = z / (ADZE * 1.8f)
        val iu = floor(u).toInt(); val iv = floor(v).toInt()
        var best = 9f
        for (a in -1..1) for (b in -1..1) {
            val cu = iu + a + 0.5f + 0.7f * (hash(iu + a, iv + b, 7) - 0.5f)
            val cv = iv + b + 0.5f + 0.7f * (hash(iu + a, iv + b, 13) - 0.5f)
            val d = (u - cu) * (u - cu) + (v - cv) * (v - cv)
            if (d < best) best = d
        }
        // Nearly flat across the middle, turning up only near the ridge: a blade's stroke, not a hammer's dent.
        return (1f - (best / 0.5f).pow(2f)).coerceIn(0f, 1f)
    }

    private const val ADZE = 0.058f

    /** A piece smaller than this is a sliver of a crack, not worth flying. */
    private const val MIN_TRIANGLES = 12

    /** Groups of swept parts that break away as pieces of their own. */
    internal const val HORN = 1; internal const val RAFFIA = 2

    private val TALL_HAIR = setOf(Coiffure.COMBS, Coiffure.CREST, Coiffure.TRIPLE_CREST, Coiffure.LOBES, Coiffure.KNOTS, Coiffure.TOPKNOT, Coiffure.ARCHES)

    /** Ichi run this far round the head either side of the middle, radians, this many flutes a radian. */
    private const val ICHI_REACH = 1.05f
    private const val ICHI_LINES = 7.5f

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
