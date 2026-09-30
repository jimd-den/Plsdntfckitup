package com.stratum.engine.model.mask

import com.stratum.engine.scene.SpiritFace
import com.stratum.engine.scene.SpiritFeatures
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * African mask art: a painted head with decorations as vector pieces that
 * float in front of it and move.
 *
 * The head is a smooth glossy form, sometimes a single oval and sometimes a
 * union of shapes ([Silhouette]): a gourd's two lobes, a dome riding on a
 * face, a wide jaw plate. It is painted like a picture, lighter above, with
 * cool shade toward its edge, a warm blush and brush grain. Paint, uli and
 * scarification go on the head itself and wrap its curve.
 *
 * Everything else is a separate vector piece hung in front of the head:
 * eyes, brows, nose, mouth, crown, what hangs from the chin and what hangs at
 * the sides. Each piece is a [Part] with its own pivot and its own motion.
 * Eyes blink and look about. Brows lift, the mouth talks, horns and feathers
 * sway, beads and raffia swing, a halo turns. A builder can move, turn and
 * scale each piece ([Nudge]). The shapes are the masks' own: leaves,
 * teardrops, crescents, cowries, lozenges, tapering horns. Each one has a
 * crisp ink outline and a glossy sheen.
 *
 * [generate] is a procedural generator over that vocabulary. The same seed
 * always makes the same mask.
 */
object AfricanMaskArt {

    /** A pigment scheme: the head's ground and four colours to paint on it. */
    class Scheme(val name: String, val base: Int, val ink: Int, val a1: Int, val a2: Int, val a3: Int)

    val schemes: List<Scheme> = listOf(
        Scheme("Kaolin", c(0xF1ECE2), c(0x1E1A1A), c(0xB3322B), c(0xD9A23A), c(0x2F3A6B)),
        Scheme("Camwood", c(0x8E2C2F), c(0x1C1212), c(0xF1ECE2), c(0xE3B044), c(0x2B2B2B)),
        Scheme("Ochre", c(0xD99A2B), c(0x1C1212), c(0xF1ECE2), c(0x8E2C2F), c(0x2A5A4A)),
        Scheme("Charcoal", c(0x24211F), c(0xF1ECE2), c(0xD9A23A), c(0xB3322B), c(0xF1ECE2)),
        Scheme("Indigo", c(0x263B6E), c(0xF1ECE2), c(0xE3B044), c(0xF1ECE2), c(0xB3322B)),
        Scheme("Terracotta", c(0xB85A36), c(0x1C1212), c(0xF1ECE2), c(0x2A2420), c(0xE3B044)),
        Scheme("Brass", c(0xC8943C), c(0x24160C), c(0x7A2E1E), c(0xF1ECE2), c(0x2A5A4A)),
        Scheme("Jade", c(0x3E7F6E), c(0x14201C), c(0xF1ECE2), c(0xE3B044), c(0xB3322B)),
    )

    enum class Silhouette(val label: String) {
        OVAL("Oval"), ROUND("Round"), EGG("Egg"), GOURD("Gourd"), SHIELD("Shield"), CROWNED("Crowned dome"), JAW("Jaw plate"), LONG("Long face"),
    }
    enum class Eyes(val label: String) {
        ALMOND("Igbo almond slits"), TUBE("Mgbedike tubes"), BEAN("Dan coffee beans"), CRESCENT("Closed crescents"),
        DIAMOND("Kuba diamonds"), SLIT("Senufo slits"), LEAF("Leaf eyes"), TEAR("Weeping eyes"),
    }
    enum class Brows(val label: String) { NONE("None"), ARCH("Arched bands"), RIDGE("Heavy ridge"), HEART("Fang heart face"), LEAVES("Leaf brows"), DOTS("Dotted brows") }
    enum class Nose(val label: String) { NONE("None"), WEDGE("Long wedge"), BROAD("Broad"), ARROW("Arrow ridge") }
    enum class Mouth(val label: String) { LIPS("Full lips"), TEETH("Bared teeth"), PURSED("Pursed"), TUBE("Projecting"), LINE("Closed line"), DIAMOND("Kuba lozenge") }
    enum class Mark(val label: String) { ICHI("Ichi bars"), CHEEK("Cheek marks"), KELOID("Keloid diamond"), TEMPLE("Temple chevrons"), LOZENGE("Forehead lozenge") }
    enum class Paint(val label: String) { EYE_RINGS("Kaolin eye rings"), HALF("Split face"), BROW_BAND("Brow band"), CHEEK_FIELDS("Cheek fields"), T_ZONE("T-zone") }
    enum class Uli(val label: String) { SPIRALS("Cheek spirals"), ZIGZAG("Zigzag band"), BORDER("Dotted border"), ARCS("Chin arcs"), TRIANGLES("Triangle band") }
    enum class Crown(val label: String) {
        NONE("None"), HORNS("Horns"), COMB("Comb"), TIERS("Tiers"), RAYS("Sun rays"), MOON("Crescent"),
        FEATHERS("Feather fan"), COWRIES("Cowrie arc"), BIRD("Hornbill"), KNOTS("Hair knots"),
    }
    enum class Hanging(val label: String) { NONE("None"), RAFFIA("Raffia fringe"), BEADS("Bead strands"), BEARD("Carved beard"), TASSELS("Tassels"), COWRIES("Cowrie strands") }
    enum class Sides(val label: String) { NONE("None"), DISCS("Ear discs"), LEAVES("Temple leaves"), RINGS("Hoops") }

    /** The pieces a mask is made of, and how each moves. Painted parts go on the head; the rest float in front of it. */
    enum class Part(val label: String, val moves: String, val painted: Boolean) {
        PAINT("Paint", "painted on", true),
        ULI("Uli", "painted on", true),
        MARKS("Marks", "cut in", true),
        HALO("Halo", "turns and breathes", false),
        SIDES("Sides", "swing", false),
        HANGING("Hanging", "swings", false),
        CROWN("Crown", "sways and bobs", false),
        NOSE("Nose", "floats", false),
        BROWS("Brows", "lift", false),
        MOUTH("Mouth", "talks", false),
        EYES("Eyes", "blink and look", false),
    }

    /** A builder's hand on one [Part]: moved by ([dx], [dy]) face units, scaled, turned by [angle] radians. */
    data class Nudge(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val angle: Float = 0f)

    /** Every choice a mask is made of: what the builder edits and the generator rolls. */
    data class Design(
        val name: String,
        val scheme: Int,
        val silhouette: Silhouette,
        val eyes: Eyes,
        val brows: Brows,
        val nose: Nose,
        val mouth: Mouth,
        val marks: List<Mark>,
        val paint: List<Paint>,
        val uli: List<Uli>,
        val crown: Crown,
        val hanging: Hanging,
        val sides: Sides,
        val symmetric: Boolean,
        /** 0 narrow, 1 wide: the head's width. */
        val width: Float,
        /** 0 still, 1 lively: how much the pieces move. */
        val motion: Float = 0.7f,
        val nudges: Map<Part, Nudge> = emptyMap(),
    ) {
        val colours: Scheme get() = schemes[Math.floorMod(scheme, schemes.size)]
    }

    /** A mask from [seed]: the same seed, the same mask. */
    fun generate(seed: Long, name: String = "Mask $seed"): Design {
        val r = Random(seed * 0x2545F4914F6CDD1DL + 0x1234567L)
        fun <T> one(xs: List<T>): T = xs[r.nextInt(xs.size)]
        fun <T> some(xs: List<T>, most: Int): List<T> = xs.shuffled(r).take(r.nextInt(0, most + 1))
        val eyes = one(Eyes.entries)
        return Design(
            name = name,
            scheme = r.nextInt(schemes.size),
            // Half the masks keep the plain oval; the rest are built from more than one shape.
            silhouette = one(List(4) { Silhouette.OVAL } + Silhouette.entries),
            eyes = eyes,
            // Tube eyes want a heavy brow over them, and a mouth that answers them.
            brows = if (eyes == Eyes.TUBE) one(listOf(Brows.RIDGE, Brows.ARCH)) else one(Brows.entries),
            nose = one(listOf(Nose.WEDGE, Nose.WEDGE, Nose.BROAD, Nose.ARROW, Nose.NONE)),
            mouth = if (eyes == Eyes.TUBE) one(listOf(Mouth.TEETH, Mouth.TUBE)) else one(Mouth.entries),
            marks = some(Mark.entries, 2),
            paint = some(Paint.entries, 2),
            uli = some(Uli.entries, 2),
            crown = one(Crown.entries),
            hanging = one(listOf(Hanging.NONE, Hanging.RAFFIA, Hanging.RAFFIA, Hanging.BEADS, Hanging.BEARD, Hanging.TASSELS, Hanging.COWRIES)),
            sides = one(listOf(Sides.NONE, Sides.NONE, Sides.DISCS, Sides.LEAVES, Sides.RINGS)),
            symmetric = r.nextFloat() > 0.15f,
            width = 0.2f + r.nextFloat() * 0.6f,
            motion = 0.5f + r.nextFloat() * 0.5f,
        )
    }

    /** The plain oval this design hangs on: an emoji head with nothing on it, in the scheme's ground colour. */
    fun oval(d: Design): MaskGenome = MaskGenome(
        name = d.name, width = d.width, crest = CrestForm.NONE, ears = EarForm.NONE, ornament = 0f, ichi = 0, cheekMarks = 0,
        palette = MaskPalettes.KAOLIN_INK,
    )

    /** How deep the head is, front to back, at its widest: a mask's shallow dome, not a ball. */
    const val HEAD_DEPTH = 0.5f
    /** Face units to model units. */
    const val HEAD_SCALE = 0.5f
    /** How much shallower the back is than the front. */
    private const val BACK = 0.6f

    /**
     * A head's outline: its half width at each height, from a soft union of
     * lobes on the centre line. Each lobe is centred at height cy, with half
     * width rx (times the design's width) and half height ry. Its exponent
     * sets the shape: 2 is an ellipse, higher is squarer.
     */
    class Profile(private val lobes: List<FloatArray>, val w: Float) {
        val vMin: Float = lobes.minOf { it[0] - it[2] }
        val vMax: Float = lobes.maxOf { it[0] + it[2] }
        val widest: Float

        init {
            var m = 0f
            for (i in 0..200) m = max(m, hw(vMin + (vMax - vMin) * i / 200f))
            widest = m
        }

        /** Half the head's width at height [v]. */
        fun hw(v: Float): Float {
            var sum = 0f
            for (l in lobes) {
                val q = abs((v - l[0]) / l[2])
                if (q >= 1f) continue
                sum += (l[1] * w * (1f - q.pow(l[3])).pow(1f / l[3])).pow(SOFT)
            }
            return sum.pow(1f / SOFT)
        }

        /** How far the head stands forward at height [v]: narrower parts are shallower. */
        fun depth(v: Float): Float = HEAD_DEPTH * (hw(v) / widest).coerceIn(0f, 1f).pow(0.5f)

        /** How far the front surface stands forward at ([u], [v]); 0 at and beyond the outline. */
        fun front(u: Float, v: Float): Float {
            val h = hw(v)
            if (h <= 1e-5f) return 0f
            val q = 1f - (u / h) * (u / h)
            return if (q <= 0f) 0f else depth(v) * sqrt(q)
        }

        /** The head's surface point at height [v], [ph] round from the front, in model units, into [out]. */
        fun point(v: Float, ph: Float, scale: Float, out: FloatArray) {
            val c = cos(ph)
            out[0] = -hw(v) * sin(ph) * scale
            out[1] = depth(v) * c * (if (c < 0f) BACK else 1f) * scale
            out[2] = v * scale
        }

        companion object {
            /** How softly lobes merge: lower rounds the necks between them. */
            private const val SOFT = 6f

            private fun lobe(cy: Float, rx: Float, ry: Float, e: Float = 2f) = floatArrayOf(cy, rx, ry, e)

            fun of(d: Design): Profile = Profile(
                when (d.silhouette) {
                    Silhouette.OVAL -> listOf(lobe(0f, 1f, 1f))
                    Silhouette.ROUND -> listOf(lobe(0.02f, 1.12f, 0.95f, 2.3f))
                    Silhouette.EGG -> listOf(lobe(0.14f, 1.02f, 0.88f), lobe(-0.36f, 0.7f, 0.64f))
                    Silhouette.GOURD -> listOf(lobe(0.28f, 1f, 0.74f), lobe(-0.64f, 0.64f, 0.38f))
                    Silhouette.SHIELD -> listOf(lobe(0.12f, 0.98f, 0.86f, 3.4f), lobe(-0.46f, 0.6f, 0.54f))
                    Silhouette.CROWNED -> listOf(lobe(-0.05f, 1f, 0.95f), lobe(0.9f, 0.46f, 0.32f))
                    Silhouette.JAW -> listOf(lobe(0.14f, 0.94f, 0.86f), lobe(-0.62f, 1.06f, 0.36f, 2.8f))
                    Silhouette.LONG -> listOf(lobe(0f, 0.8f, 1.12f))
                },
                0.66f + 0.2f * d.width,
            )
        }
    }

    /**
     * The head: a smooth glossy form in the design's silhouette, painted
     * like a picture, with its paint, uli and marks laid on its curve. Its
     * front surface is kept ([SpiritFace]) for the floating pieces to hang
     * in front of. Face units: v up (chin about -1, crown about 1), u across.
     */
    fun head(d: Design, rings: Int = 44, segments: Int = 48): SpiritMesh {
        val p = Profile.of(d)
        val scale = HEAD_SCALE
        val s = d.colours
        val mid = (p.vMax + p.vMin) / 2f; val half = (p.vMax - p.vMin) / 2f
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>()
        val a = FloatArray(3); val b = FloatArray(3); val e = FloatArray(3)
        // Painted light: warm and pale above and in front, a cool violet shade toward the edge.
        val light = mix(s.base, c(0xFFF4E0), 0.32f)
        val shade = mix(s.base, c(0x2A1E3A), 0.5f)
        val blushC = mix(s.base, c(0xE0604A), 0.6f)
        val ex = p.hw(0.1f) * 0.44f
        val ev = half * 2e-3f
        for (i in 0..rings) {
            val v = mid + half * cos(PI.toFloat() * i / rings)
            val vv = v.coerceIn(p.vMin + 3 * ev, p.vMax - 3 * ev)
            for (j in 0..segments) {
                val ph = 2f * PI.toFloat() * j / segments
                p.point(v, ph, scale, a); pos += a[0]; pos += a[1]; pos += a[2]
                // The normal from the surface's own tangents, so every silhouette shades smoothly.
                p.point(vv + ev, ph, scale, b); p.point(vv - ev, ph, scale, e)
                val dvx = b[0] - e[0]; val dvy = b[1] - e[1]; val dvz = b[2] - e[2]
                p.point(vv, ph + 1e-3f, scale, b); p.point(vv, ph - 1e-3f, scale, e)
                val dpx = b[0] - e[0]; val dpy = b[1] - e[1]; val dpz = b[2] - e[2]
                val nx = dpy * dvz - dpz * dvy; val ny = dpz * dvx - dpx * dvz; val nz = dpx * dvy - dpy * dvx
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l > 1e-12f) { nrm += nx / l; nrm += ny / l; nrm += nz / l } else { nrm += 0f; nrm += 0f; nrm += if (v > mid) 1f else -1f }
                val lift = (v - p.vMin) / (p.vMax - p.vMin)
                val facing = max(0f, cos(ph))
                var cc = mix(s.base, light, 0.3f * lift * facing)
                cc = mix(cc, shade, 0.45f * (1f - facing).pow(1.6f))
                val u = p.hw(v) * sin(ph)
                val blush = exp(-(((abs(u) - ex) / 0.17f).pow(2) + ((v + 0.22f) / 0.15f).pow(2))) * facing
                cc = mix(cc, blushC, 0.22f * blush)
                // Brush grain: faint strokes across the form.
                val brush = 0.05f * sin(v * 27f + 2.5f * sin(ph * 3f + v * 5f))
                cc = mix(cc, if (brush > 0f) light else shade, abs(brush))
                col += cc
            }
        }
        val idx = ArrayList<Int>()
        val row = segments + 1
        for (i in 0 until rings) for (j in 0 until segments) {
            val q = i * row + j; val q1 = q + 1; val q2 = q + row; val q3 = q2 + 1
            idx += q; idx += q2; idx += q1; idx += q1; idx += q2; idx += q3
        }
        val step = 0.02f
        val wide = p.widest
        val nu = (2 * wide / step).toInt() + 2; val nv = ((p.vMax - p.vMin) / step).toInt() + 2
        val front = FloatArray(nu * nv) { k ->
            val u = -wide + (k % nu) * step; val v = p.vMin + (k / nu) * step
            if (abs(u) < p.hw(v)) p.front(u, v) else Float.NaN
        }
        val face = SpiritFace(wide, p.vMin, step, nu, nv, front, scale, 0f, floatArrayOf(wide, 0.1f, ex, -0.55f))
        // The painted layers, wrapped onto the head.
        val skin = Painter(d, face, 0f, 0f, p, skin = true).skin()
        val first = pos.size / 3
        skin.positions.forEach { pos += it }; skin.normals.forEach { nrm += it }; skin.colors.forEach { col += it }
        skin.indices.forEach { idx += first + it }
        val n = pos.size / 3
        val eyeFront = p.front(ex, 0.1f)
        val eyes = floatArrayOf(-ex * scale, eyeFront * scale, 0.1f * scale, ex * scale, eyeFront * scale, 0.1f * scale)
        val colors = col.toIntArray()
        return SpiritMesh(
            pos.toFloatArray(), nrm.toFloatArray(), colors, ByteArray(n), colors.copyOf(), idx.toIntArray(),
            eyes, auraColor = s.a2, auraSecond = s.a1, faceColor = s.base,
        ).also { it.face = face }
    }

    /**
     * The floating pieces of [d] in front of [face] at [time] seconds, each
     * moving on its own. Marks and eyes burn with [burn].
     */
    fun features(d: Design, face: SpiritFace, time: Float = 0f, burn: Float = 0.4f): SpiritFeatures =
        Painter(d, face, time, burn, Profile.of(d), skin = false).floating()

    private class Painter(val d: Design, val face: SpiritFace, val t: Float, val burn: Float, val prof: Profile, val skin: Boolean) {
        val s = d.colours
        val w = prof.w
        val ey = 0.1f; val ex = prof.hw(ey) * 0.44f; val my = -0.55f
        val top = prof.vMax; val chin = prof.vMin
        val front = prof.front(0f, 0f) + 0.08f
        val m = d.motion.coerceIn(0f, 1f)
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val glw = ArrayList<Float>(); val idx = ArrayList<Int>()
        var depth = 0f
        var layer = 0
        // The current placement: x' = xa x + xb y + xtx, y' = xc x + xd y + xty.
        var xa = 1f; var xb = 0f; var xc = 0f; var xd = 1f; var xtx = 0f; var xty = 0f
        val glowC = mix(c(0xFFE7A0), s.a2, 0.25f)
        val seed = (d.name.hashCode() and 1023) / 1023f * 6.283f

        // ---- the clock -------------------------------------------------------------
        /** A quick blink every few seconds. */
        val open = if (((t + seed) % 3.7f) < 0.14f) 0.12f else 1f
        /** The glints drift as if looking about. */
        val look = sin(t * 0.63f + seed) * 0.022f * m
        /** Now and then the brows jump. */
        val browLift = 0.05f * m * max(0f, sin(t * 0.9f + seed)).pow(6)
        /** Bursts of talk. */
        val talk = 1f + 0.5f * m * max(0f, sin(t * 4.7f)) * max(0f, sin(t * 0.8f + seed))
        val sway = sin(t * 2.3f) * (0.6f + 0.8f * m)

        fun skin(): SpiritFeatures {
            part(Part.PAINT, 0f, 0f, 0f) { paintFields() }
            part(Part.ULI, 0.004f, 0f, 0f) { uli() }
            part(Part.MARKS, 0.008f, 0f, 0.3f) { marks() }
            return out()
        }

        fun floating(): SpiritFeatures {
            if (d.crown == Crown.RAYS) part(Part.HALO, -0.35f - front, 0f, (top + chin) / 2f) { halo() }
            part(Part.SIDES, 0.02f, 0f, 0f) { sides() }
            part(Part.HANGING, 0.03f, 0f, chin) { hanging() }
            part(Part.CROWN, 0.04f, 0f, top) { crown() }
            part(Part.NOSE, 0.05f, 0f, -0.1f) { nose() }
            part(Part.BROWS, 0.06f, 0f, ey + 0.2f) { brows() }
            part(Part.MOUTH, 0.07f, 0f, my) { mouth() }
            part(Part.EYES, 0.08f, 0f, ey) { eyes() }
            return out()
        }

        fun out() = SpiritFeatures(pos.toFloatArray(), nrm.toFloatArray(), col.toIntArray(), glw.toFloatArray(), idx.toIntArray())

        /** One [Part]: its layer, the builder's nudge about its pivot ([px], [py]), and, afloat, its own slow hover. */
        inline fun part(p: Part, at: Float, px: Float, py: Float, draw: () -> Unit) {
            depth = if (skin) at else front + at
            layer = 0
            xa = 1f; xb = 0f; xc = 0f; xd = 1f; xtx = 0f; xty = 0f
            val n = d.nudges[p]
            val hover = if (skin) 0f else sin(t * 1.3f + p.ordinal * 1.7f) * 0.02f * m
            moved(px, py, n?.angle ?: 0f, n?.scale ?: 1f, n?.scale ?: 1f, n?.dx ?: 0f, (n?.dy ?: 0f) + hover) { draw() }
        }

        /** Draws [draw] turned by [angle] and scaled about ([px], [py]), then moved by ([dx], [dy]). */
        inline fun moved(px: Float, py: Float, angle: Float = 0f, sx: Float = 1f, sy: Float = sx, dx: Float = 0f, dy: Float = 0f, draw: () -> Unit) {
            val oa = xa; val ob = xb; val oc = xc; val od = xd; val otx = xtx; val oty = xty
            val ca = cos(angle); val sa = sin(angle)
            val la = ca * sx; val lb = -sa * sy; val lc = sa * sx; val ld = ca * sy
            val ltx = px + dx - (la * px + lb * py); val lty = py + dy - (lc * px + ld * py)
            xa = oa * la + ob * lc; xb = oa * lb + ob * ld; xc = oc * la + od * lc; xd = oc * lb + od * ld
            xtx = oa * ltx + ob * lty + otx; xty = oc * ltx + od * lty + oty
            draw()
            xa = oa; xb = ob; xc = oc; xd = od; xtx = otx; xty = oty
        }

        /** Runs [n] repeats of a motif on the same layers, so a row of beads doesn't stack up in depth. */
        inline fun each(n: Int, body: (Int) -> Unit) {
            val base = layer; var high = base
            for (i in 0 until n) { layer = base; body(i); high = max(high, layer) }
            layer = high
        }

        // ---- output -----------------------------------------------------------------

        fun fillRaw(pts: FloatArray, color: Int, glow: Float = 0f) {
            val tris = EmojiFace.Triangulate.polygon(pts)
            if (tris.isEmpty()) return
            val n = pts.size / 2
            val tx = FloatArray(n); val ty = FloatArray(n)
            for (i in 0 until n) {
                val x = pts[i * 2]; val y = pts[i * 2 + 1]
                tx[i] = xa * x + xb * y + xtx; ty[i] = xc * x + xd * y + xty
            }
            if (!skin) {
                val yy = (depth + LAYER * layer++) * face.scale
                val first = pos.size / 3
                for (i in 0 until n) {
                    pos += -tx[i] * face.scale; pos += yy; pos += (ty[i] - face.midV) * face.scale
                    nrm += 0f; nrm += 1f; nrm += 0f
                    col += color; glw += glow
                }
                for (k in tris) idx += first + k
                return
            }
            val lift = SKIN_LIFT + depth + SKIN_LAYER * layer++
            var k = 0
            while (k < tris.size) {
                val i0 = tris[k]; val i1 = tris[k + 1]; val i2 = tris[k + 2]
                tri(tx[i0], ty[i0], tx[i1], ty[i1], tx[i2], ty[i2], lift, color, glow, 0)
                k += 3
            }
        }

        /** A painted triangle, split until it can follow the head's curve. */
        fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, lift: Float, color: Int, glow: Float, level: Int) {
            val ab = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val bc = (cx - bx) * (cx - bx) + (cy - by) * (cy - by)
            val ca = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy)
            val longest = max(ab, max(bc, ca))
            if (longest > SKIN_EDGE * SKIN_EDGE && level < 10) {
                when (longest) {
                    ab -> { val mx = (ax + bx) / 2f; val mv = (ay + by) / 2f; tri(ax, ay, mx, mv, cx, cy, lift, color, glow, level + 1); tri(mx, mv, bx, by, cx, cy, lift, color, glow, level + 1) }
                    bc -> { val mx = (bx + cx) / 2f; val mv = (by + cy) / 2f; tri(ax, ay, bx, by, mx, mv, lift, color, glow, level + 1); tri(ax, ay, mx, mv, cx, cy, lift, color, glow, level + 1) }
                    else -> { val mx = (cx + ax) / 2f; val mv = (cy + ay) / 2f; tri(ax, ay, bx, by, mx, mv, lift, color, glow, level + 1); tri(mx, mv, bx, by, cx, cy, lift, color, glow, level + 1) }
                }
                return
            }
            val first = pos.size / 3
            skinVertex(ax, ay, lift, color, glow); skinVertex(bx, by, lift, color, glow); skinVertex(cx, cy, lift, color, glow)
            idx += first; idx += first + 1; idx += first + 2
        }

        fun skinVertex(u: Float, v: Float, lift: Float, color: Int, glow: Float) {
            val h = prof.front(u, v)
            val e = 0.01f
            val du = ((prof.front(u + e, v) - prof.front(u - e, v)) / (2 * e)).coerceIn(-4f, 4f)
            val dv = ((prof.front(u, v + e) - prof.front(u, v - e)) / (2 * e)).coerceIn(-4f, 4f)
            val l = sqrt(du * du + 1f + dv * dv)
            pos += -u * face.scale; pos += (h + lift) * face.scale; pos += (v - face.midV) * face.scale
            nrm += du / l; nrm += 1f / l; nrm += -dv / l
            col += color; glw += glow
        }

        // ---- drawing ------------------------------------------------------------------

        fun line(pts: FloatArray, color: Int, width: Float = 0.035f, glow: Float = 0f, closed: Boolean = false) {
            val n = pts.size / 2
            if (n < 2) return
            val h = width / 2f
            val start = layer
            val segs = if (closed) n else n - 1
            for (i in 0 until segs) {
                val j = (i + 1) % n
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[j * 2]; val by = pts[j * 2 + 1]
                var dx = bx - ax; var dy = by - ay
                val l = sqrt(dx * dx + dy * dy)
                if (l < 1e-6f) continue
                dx = dx / l * h; dy = dy / l * h
                layer = start
                fillRaw(floatArrayOf(ax - dy, ay + dx, ax + dy, ay - dx, bx + dy, by - dx, bx - dy, by + dx), color, glow)
            }
            for (i in 0 until n) { layer = start; fillRaw(Shapes.ellipse(pts[i * 2], pts[i * 2 + 1], h, h, 8), color, glow) }
            layer = start + 1
        }

        /** The ink an outline is drawn in: the shape's own colour, deepened almost to black. */
        fun edge(color: Int) = mix(color, DEEP, 0.8f)

        /** A flat shape with a crisp ink outline. */
        fun piece(pts: FloatArray, color: Int, glow: Float = 0f) {
            line(pts, edge(color), OUTLINE, closed = true)
            fillRaw(pts, color, glow)
        }

        /** A rounded piece, outlined and glossy: a paler sheen toward the light and a glint. Keep it convex. */
        fun gem(pts: FloatArray, color: Int, glow: Float = 0f) {
            piece(pts, color, glow)
            var cx = 0f; var cy = 0f
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            val n = pts.size / 2
            for (i in 0 until n) {
                val x = pts[i * 2]; val y = pts[i * 2 + 1]
                cx += x; cy += y; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            cx /= n; cy /= n
            val bw = x1 - x0; val bh = y1 - y0
            fillRaw(FloatArray(pts.size) { k -> if (k % 2 == 0) cx + (pts[k] - cx) * 0.6f - bw * 0.08f else cy + (pts[k] - cy) * 0.55f + bh * 0.14f }, mix(color, WHITE, 0.26f), glow)
            val r = min(bw, bh) * 0.12f
            fillRaw(Shapes.ellipse(cx - bw * 0.2f, cy + bh * 0.24f, r, r * 0.8f, 10), mix(color, WHITE, 0.8f), glow)
        }

        /** A tapering horn, tusk or strand, outlined, with a glossy streak along it. */
        fun horn(ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float, w0: Float, w1: Float, color: Int) {
            piece(Shapes.taper(ax, ay, cx, cy, bx, by, w0, w1, 18), color)
            val ex = ax + (bx - ax) * 0.7f; val ey = ay + (by - ay) * 0.7f
            fillRaw(Shapes.taper(ax - 0.012f, ay + 0.012f, cx - 0.012f, cy + 0.012f, ex, ey, w0 * 0.28f, 0.004f, 14), mix(color, WHITE, 0.35f))
        }

        fun sides(draw: (Float) -> Unit) { draw(-1f); if (d.symmetric) draw(1f) }
        fun both(draw: (Float) -> Unit) { draw(-1f); draw(1f) }

        fun hw(v: Float) = prof.hw(v)

        // ---- motifs, in local shapes -----------------------------------------------------

        fun rotated(pts: FloatArray, px: Float, py: Float, a: Float): FloatArray {
            val c = cos(a); val sn = sin(a)
            return FloatArray(pts.size) { k ->
                val i = k - k % 2
                val x = pts[i] - px; val y = pts[i + 1] - py
                if (k % 2 == 0) px + x * c - y * sn else py + x * sn + y * c
            }
        }

        /** A leaf from its stem at ([bx], [by]), [len] long and [wid] wide at the belly, pointing up turned by [angle]. */
        fun leaf(bx: Float, by: Float, len: Float, wid: Float, angle: Float): FloatArray {
            val right = Shapes.quad(0f, 0f, wid, len * 0.45f, 0f, len, 10)
            val left = Shapes.quad(0f, len, -wid, len * 0.45f, 0f, 0f, 10)
            return rotated(Shapes.offset(right + left.copyOfRange(2, left.size - 2), bx, by), bx, by, angle)
        }

        /** A teardrop about ([cx], [cy]), its point up turned by [angle]. */
        fun teardrop(cx: Float, cy: Float, r: Float, angle: Float = 0f): FloatArray {
            val n = 24
            val out = FloatArray(n * 2)
            for (i in 0 until n) {
                val th = 2f * PI.toFloat() * i / n
                out[i * 2] = cx + r * sin(th) * sin(th / 2f); out[i * 2 + 1] = cy + r * 1.4f * cos(th)
            }
            return rotated(out, cx, cy, angle)
        }

        /** A crescent about ([cx], [cy]), horns up turned by [angle]. */
        fun crescent(cx: Float, cy: Float, r: Float, thick: Float, angle: Float = 0f): FloatArray {
            val f = kotlin.math.asin((thick / 2f / r).coerceIn(-1f, 1f))
            val n = 18
            val out = ArrayList<Float>()
            for (i in 0..n) { val a = PI.toFloat() - f + (PI.toFloat() + 2 * f) * i / n; out += cx + r * cos(a); out += cy + r * sin(a) }
            for (i in 1 until n) { val a = 2 * PI.toFloat() - f - (PI.toFloat() - 2 * f) * i / n; out += cx + r * cos(a); out += cy + thick + r * sin(a) }
            return rotated(out.toFloatArray(), cx, cy, angle)
        }

        fun star(cx: Float, cy: Float, r0: Float, r1: Float, points: Int, a0: Float = 0f): FloatArray =
            FloatArray(points * 4) { k ->
                val i = k / 2; val a = a0 + PI.toFloat() / 2f + i * PI.toFloat() / points; val r = if (i % 2 == 0) r1 else r0
                if (k % 2 == 0) cx + cos(a) * r else cy + sin(a) * r
            }

        fun lozenge(cx: Float, cy: Float, hx: Float, hy: Float) = floatArrayOf(cx, cy + hy, cx + hx, cy, cx, cy - hy, cx - hx, cy)

        /** A cowrie shell: a glossy oval with its toothed slit. */
        fun cowrie(cx: Float, cy: Float, rx: Float, ry: Float, angle: Float = 0f) {
            val shell = rotated(Shapes.ellipse(cx, cy, rx, ry, 18), cx, cy, angle)
            gem(shell, mix(c(0xF4ECDA), s.a2, 0.12f))
            line(rotated(floatArrayOf(cx, cy - ry * 0.7f, cx, cy + ry * 0.7f), cx, cy, angle), c(0x3A2A20), rx * 0.3f)
            for (k in -1..1) line(rotated(floatArrayOf(cx - rx * 0.28f, cy + k * ry * 0.3f, cx + rx * 0.28f, cy + k * ry * 0.3f), cx, cy, angle), c(0x3A2A20), rx * 0.12f)
        }

        // ---- painted on the head -----------------------------------------------------

        fun paintFields() {
            for (p in d.paint) when (p) {
                Paint.EYE_RINGS -> both { sx -> fillRaw(Shapes.ellipse(sx * ex, ey, 0.22f, 0.17f, 30), s.a1) }
                Paint.HALF -> {
                    val pts = ArrayList<Float>()
                    val v0 = chin + 0.02f; val v1 = top - 0.02f
                    for (i in 0..48) { val v = v0 + (v1 - v0) * i / 48f; pts += -hw(v) * 0.985f; pts += v }
                    pts += 0f; pts += v1; pts += 0f; pts += v0
                    fillRaw(pts.toFloatArray(), s.a3)
                }
                Paint.BROW_BAND -> {
                    val pts = ArrayList<Float>()
                    for (i in 0..24) { val v = 0.34f + 0.2f * i / 24f; pts += hw(v) * 0.985f; pts += v }
                    for (i in 24 downTo 0) { val v = 0.34f + 0.2f * i / 24f; pts += -hw(v) * 0.985f; pts += v }
                    fillRaw(pts.toFloatArray(), s.a2)
                }
                Paint.CHEEK_FIELDS -> sides { sx -> fillRaw(floatArrayOf(sx * ex * 0.6f, ey - 0.22f, sx * hw(-0.25f) * 0.95f, -0.25f, sx * ex * 1.2f, -0.62f), s.a1) }
                Paint.T_ZONE -> {
                    fillRaw(Shapes.roundRect(-hw(0.45f) * 0.9f, 0.36f, hw(0.45f) * 0.9f, 0.5f, 0.05f), s.a2)
                    fillRaw(Shapes.roundRect(-0.07f, -0.35f, 0.07f, 0.4f, 0.05f), s.a2)
                }
            }
        }

        fun uli() {
            for (u in d.uli) when (u) {
                Uli.SPIRALS -> sides { sx ->
                    val pts = ArrayList<Float>()
                    for (i in 0..40) { val k = i / 40f; val a = k * 4f * PI.toFloat() * sx; val r = 0.01f + 0.09f * k; pts += sx * (ex + 0.06f) + cos(a) * r; pts += -0.26f + sin(a) * r }
                    line(pts.toFloatArray(), s.ink, 0.022f, burn * 0.6f)
                }
                Uli.ZIGZAG -> {
                    val pts = ArrayList<Float>()
                    val half = hw(0.6f) * 0.85f
                    for (i in 0..12) { pts += -half + 2 * half * i / 12f; pts += 0.6f + if (i % 2 == 0) 0.04f else -0.04f }
                    line(pts.toFloatArray(), s.ink, 0.026f, burn * 0.6f)
                }
                Uli.BORDER -> each(17) { k ->
                    val v = chin + 0.14f + (top - chin - 0.28f) * k / 16f
                    both { sx -> fillRaw(Shapes.ellipse(sx * hw(v) * 0.86f, v, 0.022f, 0.022f, 8), if (k % 2 == 0) s.ink else s.a2, burn * 0.5f) }
                }
                Uli.ARCS -> for (k in 0 until 3) line(Shapes.quad(-0.22f + k * 0.04f, my - 0.14f - k * 0.05f, 0f, my - 0.26f - k * 0.05f, 0.22f - k * 0.04f, my - 0.14f - k * 0.05f, 10), s.ink, 0.022f, burn * 0.6f)
                Uli.TRIANGLES -> {
                    val half = hw(0.5f) * 0.8f
                    val n = 7
                    each(n) { i ->
                        val x0 = -half + 2 * half * i / n; val x1 = x0 + 2 * half / n
                        fillRaw(floatArrayOf(x0, 0.46f, x1, 0.46f, (x0 + x1) / 2f, 0.56f), if (i % 2 == 0) s.ink else s.a1)
                    }
                }
            }
        }

        fun marks() {
            for (mk in d.marks) when (mk) {
                Mark.ICHI -> for (i in 0 until 4) { val x = (i - 1.5f) * 0.07f; line(floatArrayOf(x, 0.66f, x, 0.52f), s.ink, 0.028f, burn * 0.7f) }
                Mark.CHEEK -> sides { sx -> for (i in 0 until 3) line(floatArrayOf(sx * (ex + 0.04f), -0.16f - i * 0.06f, sx * (ex + 0.2f), -0.14f - i * 0.06f), s.ink, 0.026f, burn * 0.7f) }
                Mark.KELOID -> each(13) { k ->
                    val i = listOf(0, -1, 0, 1, -2, -1, 0, 1, 2, -1, 0, 1, 0)[k]; val j = listOf(-2, -1, -1, -1, 0, 0, 0, 0, 0, 1, 1, 1, 2)[k]
                    fillRaw(Shapes.ellipse(i * 0.045f, 0.56f + j * 0.045f, 0.018f, 0.018f, 8), s.ink, burn * 0.5f)
                }
                Mark.TEMPLE -> sides { sx -> for (i in 0 until 2) line(floatArrayOf(sx * (hw(0.3f) - 0.2f), 0.4f - i * 0.08f, sx * (hw(0.3f) - 0.08f), 0.34f - i * 0.08f, sx * (hw(0.3f) - 0.2f), 0.28f - i * 0.08f), s.ink, 0.024f, burn * 0.7f) }
                Mark.LOZENGE -> { fillRaw(lozenge(0f, 0.6f, 0.07f, 0.12f), s.a1); line(lozenge(0f, 0.6f, 0.07f, 0.12f), s.ink, 0.02f, closed = true) }
            }
        }

        // ---- floating: the face ---------------------------------------------------------

        fun brows() {
            // The pair jumps together; each brow also tips up at its outer end.
            moved(0f, ey + 0.2f, dy = browLift) {
                when (d.brows) {
                    Brows.NONE -> Unit
                    Brows.ARCH -> both { sx -> moved(sx * ex, ey + 0.2f, angle = -sx * browLift * 2f) { piece(Shapes.taper(sx * (ex - 0.22f), ey + 0.15f, sx * ex, ey + 0.32f, sx * (ex + 0.22f), ey + 0.16f, 0.07f, 0.02f, 14), s.ink) } }
                    Brows.RIDGE -> both { sx ->
                        moved(sx * ex, ey + 0.2f, angle = -sx * browLift * 2f) {
                            val upper = Shapes.quad(sx * (ex - 0.25f), ey + 0.16f, sx * ex, ey + 0.35f, sx * (ex + 0.25f), ey + 0.12f, 12)
                            val lower = Shapes.quad(sx * (ex + 0.25f), ey + 0.12f, sx * ex, ey + 0.24f, sx * (ex - 0.25f), ey + 0.16f, 12)
                            piece(upper + lower.copyOfRange(2, lower.size - 2), mix(s.ink, s.a3, 0.2f))
                        }
                    }
                    Brows.HEART -> both { sx -> line(Shapes.quad(0f, -0.18f, sx * (ex + 0.3f), ey + 0.1f, sx * 0.03f, ey + 0.32f, 16), s.ink, 0.045f) }
                    Brows.LEAVES -> both { sx -> moved(sx * ex, ey + 0.22f, angle = -sx * browLift * 2f) {
                        val lf = leaf(sx * (ex - 0.2f), ey + 0.18f, 0.42f, 0.06f, -sx * 1.32f)
                        gem(lf, s.a1)
                        line(floatArrayOf(sx * (ex - 0.17f), ey + 0.2f, sx * (ex + 0.14f), ey + 0.25f), edge(s.a1), 0.012f)
                    } }
                    Brows.DOTS -> both { sx -> moved(sx * ex, ey + 0.22f, angle = -sx * browLift * 2f) {
                        each(5) { k -> val a = (k - 2) * 0.35f; gem(Shapes.ellipse(sx * ex + sin(a) * 0.2f, ey + 0.1f + cos(a) * 0.14f, 0.035f, 0.035f, 14), if (k % 2 == 0) s.a2 else s.a1) }
                    } }
                }
            }
        }

        fun nose() {
            when (d.nose) {
                Nose.NONE -> Unit
                Nose.WEDGE -> piece(floatArrayOf(0f, ey + 0.1f, 0.03f, ey - 0.02f, 0.085f, -0.24f, 0f, -0.29f, -0.085f, -0.24f, -0.03f, ey - 0.02f), mix(s.base, s.ink, 0.35f))
                Nose.BROAD -> {
                    piece(floatArrayOf(0f, 0f, 0.07f, -0.16f, 0.13f, -0.27f, 0f, -0.3f, -0.13f, -0.27f, -0.07f, -0.16f), mix(s.base, s.ink, 0.3f))
                    both { sx -> fillRaw(Shapes.ellipse(sx * 0.055f, -0.26f, 0.025f, 0.015f, 10), s.ink) }
                }
                Nose.ARROW -> {
                    piece(floatArrayOf(-0.025f, 0.55f, 0.025f, 0.55f, 0.025f, -0.12f, 0.1f, -0.12f, 0f, -0.3f, -0.1f, -0.12f, -0.025f, -0.12f), s.a1)
                    gem(lozenge(0f, 0.6f, 0.05f, 0.07f), s.a2)
                }
            }
        }

        fun eyes() {
            both { sx ->
                val cx = sx * ex; val cy = ey
                // A blink squashes the eye on its line; the glint looks about.
                moved(cx, cy, sx = 1f, sy = open) {
                    when (d.eyes) {
                        Eyes.ALMOND -> {
                            piece(almond(cx, cy, 0.21f, 0.095f, sx), s.a1)
                            fillRaw(almond(cx, cy, 0.16f, 0.07f, sx), s.ink)
                            fillRaw(almond(cx + look, cy, 0.1f, 0.022f, sx), glowC, 0.6f + burn)
                        }
                        Eyes.TUBE -> {
                            gem(Shapes.ellipse(cx, cy, 0.15f, 0.15f, 30), s.a1)
                            fillRaw(Shapes.ellipse(cx, cy, 0.095f, 0.095f, 24), s.ink)
                            fillRaw(Shapes.ellipse(cx + look, cy, 0.05f, 0.05f, 16), glowC, 0.7f + burn)
                        }
                        Eyes.BEAN -> {
                            gem(Shapes.ellipse(cx, cy, 0.16f, 0.09f, 28), mix(s.base, s.a2, 0.5f))
                            line(floatArrayOf(cx - 0.13f, cy, cx + 0.13f, cy), s.ink, 0.03f)
                            line(floatArrayOf(cx - 0.07f + look, cy, cx + 0.07f + look, cy), glowC, 0.014f, 0.8f + burn)
                        }
                        Eyes.CRESCENT -> piece(crescent(cx, cy + 0.06f, 0.15f, 0.07f), s.ink, burn * 0.4f)
                        Eyes.DIAMOND -> {
                            piece(lozenge(cx, cy, 0.17f, 0.12f), s.a2)
                            fillRaw(lozenge(cx, cy, 0.12f, 0.075f), s.ink)
                            fillRaw(Shapes.ellipse(cx + look, cy, 0.035f, 0.035f, 12), glowC, 0.7f + burn)
                        }
                        Eyes.SLIT -> {
                            piece(Shapes.roundRect(cx - 0.18f, cy - 0.05f, cx + 0.18f, cy + 0.05f, 0.05f), s.a1)
                            fillRaw(Shapes.roundRect(cx - 0.15f, cy - 0.025f, cx + 0.15f, cy + 0.029f, 0.02f), s.ink)
                            fillRaw(Shapes.roundRect(cx - 0.09f + look, cy - 0.008f, cx + 0.09f + look, cy + 0.008f, 0.008f), glowC, 0.7f + burn)
                        }
                        Eyes.LEAF -> {
                            gem(leaf(cx - sx * 0.19f, cy - 0.03f, 0.4f, 0.1f, -sx * 1.45f), s.a1)
                            fillRaw(leaf(cx - sx * 0.12f, cy - 0.01f, 0.25f, 0.045f, -sx * 1.45f), s.ink)
                            fillRaw(Shapes.ellipse(cx + look, cy + 0.005f, 0.025f, 0.02f, 10), glowC, 0.7f + burn)
                        }
                        Eyes.TEAR -> {
                            piece(almond(cx, cy, 0.17f, 0.075f, sx), s.ink)
                            fillRaw(almond(cx + look, cy, 0.1f, 0.022f, sx), glowC, 0.6f + burn)
                        }
                    }
                }
                // Tears fall, and fall again.
                if (d.eyes == Eyes.TEAR) {
                    val fall = ((t * 0.45f + (sx + 1f) * 0.3f) % 1f)
                    gem(teardrop(cx + sx * 0.05f, cy - 0.16f - fall * 0.26f * (0.3f + m), 0.04f), s.a2, 0.2f + burn * 0.3f)
                }
            }
        }

        fun almond(cx: Float, cy: Float, half: Float, h: Float, side: Float): FloatArray {
            val ix = cx - side * half; val ox = cx + side * half
            val upper = Shapes.quad(ix, cy, cx, cy + h * 1.7f, ox, cy + h * 0.4f, 12)
            val lower = Shapes.quad(ox, cy + h * 0.4f, cx, cy - h * 1.2f, ix, cy, 12)
            return upper + lower.copyOfRange(2, lower.size - 2)
        }

        fun mouth() {
            moved(0f, my, sx = 1f, sy = talk) {
                when (d.mouth) {
                    Mouth.LIPS -> {
                        val lip = if (s.base == s.a1) s.a2 else s.a1
                        val up = floatArrayOf(-0.14f, my, -0.065f, my + 0.055f, 0f, my + 0.028f, 0.065f, my + 0.055f, 0.14f, my)
                        val low = Shapes.quad(0.14f, my, 0f, my - 0.11f, -0.14f, my, 10)
                        gem(up + low.copyOfRange(2, low.size - 2), lip)
                        line(floatArrayOf(-0.12f, my, 0.12f, my), edge(lip), 0.018f)
                    }
                    Mouth.TEETH -> {
                        piece(Shapes.roundRect(-0.18f, my - 0.1f, 0.18f, my + 0.055f, 0.055f), s.ink)
                        each(5) { i -> fillRaw(Shapes.roundRect(-0.145f + i * 0.059f, my - 0.01f, -0.145f + i * 0.059f + 0.046f, my + 0.036f, 0.012f), c(0xF4ECDA)) }
                    }
                    Mouth.PURSED -> { gem(Shapes.ellipse(0f, my, 0.085f, 0.065f, 22), s.a1); fillRaw(Shapes.ellipse(0f, my, 0.03f, 0.02f, 10), s.ink) }
                    Mouth.TUBE -> { gem(Shapes.ellipse(0f, my, 0.11f, 0.11f, 26), s.a2); fillRaw(Shapes.ellipse(0f, my, 0.058f, 0.058f, 20), s.ink) }
                    Mouth.LINE -> line(Shapes.quad(-0.13f, my + 0.01f, 0f, my - 0.03f, 0.13f, my + 0.01f, 10), s.ink, 0.032f)
                    Mouth.DIAMOND -> { piece(lozenge(0f, my, 0.16f, 0.08f), s.a1); fillRaw(lozenge(0f, my, 0.1f, 0.035f), s.ink) }
                }
            }
        }

        // ---- floating: what crowns it, what hangs from it ------------------------------------

        fun halo() {
            val cy = (top + chin) / 2f; val ry = (top - chin) / 2f * 1.04f; val rx = prof.widest * 1.04f
            val n = 16
            val spin = t * 0.25f * m
            val breathe = 1f + 0.08f * sin(t * 1.4f) * m
            each(n) { i ->
                val a = i * 2f * PI.toFloat() / n + spin
                val bx = cos(a) * rx; val by = cy + sin(a) * ry
                val len = (0.26f + 0.1f * (i % 2)) * breathe
                gem(leaf(bx, by, len, 0.07f, a - PI.toFloat() / 2f), if (i % 2 == 0) s.a2 else s.a1, 0.25f)
            }
        }

        fun crown() {
            val rock = sin(t * 1.7f) * 0.1f * m
            val bob = sin(t * 1.6f) * 0.04f * m
            when (d.crown) {
                Crown.NONE, Crown.RAYS -> Unit
                Crown.HORNS -> both { sx ->
                    val bx = sx * hw(top - 0.22f) * 0.7f; val by = top - 0.22f
                    moved(bx, by, angle = sx * rock) {
                        horn(bx, by, sx * w * 1.3f, top + 0.15f, sx * w * 0.8f, top + 0.62f, 0.17f, 0.02f, s.a3)
                        for (k in 1..3) { val kk = k / 4.5f; gem(Shapes.ellipse(bx + (sx * w * 1.05f - bx) * kk * 1.2f, by + 0.1f + 0.36f * kk, 0.05f * (1 - kk) + 0.022f, 0.022f, 12), s.a2) }
                    }
                }
                Crown.COMB -> moved(0f, top - 0.1f, dy = bob) {
                    for (i in -2..2) {
                        val a = i * 0.3f + sin(t * 2.2f + i) * 0.04f * m
                        val h = 0.52f - abs(i) * 0.07f
                        piece(rotated(Shapes.roundRect(-0.06f, top - 0.12f, 0.06f, top - 0.12f + h, 0.06f), 0f, top - 0.12f, a), if (i % 2 == 0) s.a3 else s.a2)
                        gem(Shapes.ellipse(-sin(a) * (h - 0.06f), top - 0.12f + cos(a) * (h - 0.06f), 0.035f, 0.035f, 12), s.a1)
                    }
                }
                Crown.TIERS -> for (k in 0 until 3) {
                    // Each tier bobs a beat after the one below it, like a stack on a spring.
                    val lift = sin(t * 1.6f - k * 0.6f) * 0.025f * m * (k + 1)
                    val half = w * (0.72f - 0.17f * k); val y0 = top - 0.1f + k * 0.2f + lift
                    piece(Shapes.roundRect(-half, y0, half, y0 + 0.17f, 0.08f), listOf(s.a1, s.a2, s.a3)[k])
                    each(5) { j -> gem(Shapes.ellipse(-half * 0.7f + j * half * 0.35f, y0 + 0.085f, 0.028f, 0.028f, 10), s.ink) }
                }
                Crown.MOON -> moved(0f, top + 0.2f, angle = rock, dy = bob) {
                    gem(Shapes.ellipse(0f, top + 0.02f, 0.06f, 0.06f, 14), s.a1)
                    piece(crescent(0f, top + 0.22f, 0.32f, 0.16f), s.a2)
                    gem(star(0f, top + 0.4f, 0.03f, 0.08f, 5, t * 0.8f * m), s.a1, 0.3f)
                }
                Crown.FEATHERS -> {
                    val n = 7
                    for (i in 0 until n) {
                        val base = (i - (n - 1) / 2f) * 0.28f
                        val a = base + sin(t * 2.6f + i * 0.9f) * 0.12f * m
                        val bx = sin(base) * 0.18f; val by = top - 0.12f
                        val len = 0.62f - abs(i - (n - 1) / 2f) * 0.06f
                        val lf = leaf(bx, by, len, 0.09f, a)
                        gem(lf, if (i % 2 == 0) s.a3 else s.a1)
                        line(rotated(floatArrayOf(bx, by + 0.04f, bx, by + len * 0.85f), bx, by, a), edge(if (i % 2 == 0) s.a3 else s.a1), 0.012f)
                    }
                    gem(Shapes.ellipse(0f, top - 0.1f, 0.12f, 0.07f, 20), s.a2)
                }
                Crown.COWRIES -> each(7) { i ->
                    val a = PI.toFloat() * (0.18f + 0.64f * i / 6f)
                    val v = top - 0.1f - (1f - sin(a)) * 0.5f
                    val u = cos(a) * hw(v) * 1.02f
                    cowrie(u, v + 0.06f + sin(t * 2f + i * 0.8f) * 0.012f * m, 0.06f, 0.085f, a - PI.toFloat() / 2f + sin(t * 1.8f + i) * 0.1f * m)
                }
                Crown.BIRD -> {
                    // A Senufo hornbill perched on top: body, long beak, crest. It nods now and then.
                    val nod = max(0f, sin(t * 1.1f + seed)).pow(8) * 0.35f * m
                    val by = top + 0.04f
                    moved(0f, by, dy = bob) {
                        piece(floatArrayOf(-0.05f, by - 0.02f, 0.05f, by - 0.02f, 0.03f, by + 0.12f, -0.03f, by + 0.12f), s.a3)
                        gem(Shapes.ellipse(0f, by + 0.26f, 0.2f, 0.14f, 24), s.a1)
                        moved(0.12f, by + 0.32f, angle = -nod) {
                            gem(Shapes.ellipse(0.14f, by + 0.38f, 0.08f, 0.08f, 18), s.a1)
                            horn(0.2f, by + 0.38f, 0.42f, by + 0.36f, 0.5f, by + 0.1f, 0.07f, 0.01f, s.a2)
                            fillRaw(Shapes.ellipse(0.15f, by + 0.4f, 0.018f, 0.018f, 8), s.ink)
                            gem(leaf(0.1f, by + 0.44f, 0.16f, 0.035f, 0.6f), s.a3)
                        }
                        gem(leaf(-0.16f, by + 0.24f, 0.26f, 0.06f, 1.9f + sin(t * 3f) * 0.08f * m), s.a3)
                    }
                }
                Crown.KNOTS -> for (k in -1..1) {
                    val lift = sin(t * 1.9f - abs(k) * 0.7f) * 0.03f * m
                    val kx = k * 0.3f; val ky = top + 0.12f - abs(k) * 0.12f + lift
                    line(floatArrayOf(kx * 0.6f, top - 0.12f, kx, ky), mix(s.ink, s.a3, 0.3f), 0.05f)
                    gem(Shapes.ellipse(kx, ky + 0.06f, 0.12f, 0.12f, 26), if (k == 0) s.a3 else s.a1)
                    gem(Shapes.ellipse(kx, ky + 0.06f, 0.045f, 0.045f, 12), s.a2)
                }
            }
        }

        /** How far down the head reaches at [x] across: where something hung at [x] starts. */
        fun bottomAt(x: Float): Float {
            var v = chin
            while (v < 0f && hw(v) < abs(x)) v += 0.01f
            return v + 0.03f
        }

        fun hanging() {
            when (d.hanging) {
                Hanging.NONE -> Unit
                Hanging.RAFFIA -> {
                    val n = 13
                    val span = min(0.46f, prof.hw(chin + 0.12f) * 0.95f)
                    each(n) { i ->
                        val x0 = -span + 2 * span * i / (n - 1)
                        val y0 = bottomAt(x0) + 0.02f
                        val len = 0.46f + 0.08f * ((i * 7) % 3)
                        val swing = sway * (0.06f + 0.02f * (i % 3))
                        val tint = when (i % 3) { 0 -> s.a2; 1 -> mix(s.a2, s.a3, 0.45f); else -> mix(s.a2, WHITE, 0.3f) }
                        piece(Shapes.taper(x0, y0, x0 + swing * 0.5f, y0 - len * 0.5f, x0 + swing, y0 - len, 0.055f, 0.014f, 10), tint)
                    }
                    piece(Shapes.roundRect(-span - 0.04f, chin + 0.02f, span + 0.04f, chin + 0.1f, 0.04f), s.a3)
                }
                Hanging.BEADS -> both { sx ->
                    for (k in 0 until 3) {
                        val x = sx * (hw(-0.3f) * 0.82f + k * 0.055f); val y0 = -0.3f - k * 0.02f
                        moved(x, y0, angle = sway * 0.12f * (1f + k * 0.3f)) {
                            line(floatArrayOf(x, y0, x, y0 - 0.56f), edge(s.a2), 0.01f)
                            each(7) { b ->
                                val y = y0 - 0.04f - b * 0.08f
                                if (b % 3 == 1) gem(Shapes.roundRect(x - 0.022f, y - 0.034f, x + 0.022f, y + 0.034f, 0.02f), s.a3)
                                else gem(Shapes.ellipse(x, y, 0.028f, 0.028f, 12), if ((b + k) % 2 == 0) s.a2 else s.a1)
                            }
                        }
                    }
                }
                Hanging.BEARD -> moved(0f, chin + 0.08f, angle = sin(t * 1.9f) * 0.06f * m) {
                    piece(Shapes.taper(0f, chin + 0.08f, 0f, chin - 0.12f, 0f, chin - 0.42f, 0.46f, 0.1f, 14), s.a3)
                    for (k in 1..3) piece(floatArrayOf(-0.16f + k * 0.03f, chin - 0.02f - k * 0.1f, 0f, chin - 0.07f - k * 0.1f, 0.16f - k * 0.03f, chin - 0.02f - k * 0.1f, 0f, chin - 0.04f - k * 0.1f), s.a2)
                }
                Hanging.TASSELS -> {
                    val n = 5
                    for (i in 0 until n) {
                        val x = (i - (n - 1) / 2f) * 0.13f
                        val y0 = bottomAt(x)
                        val len = 0.18f + 0.1f * (1f - abs(i - (n - 1) / 2f) / 2f)
                        moved(x, y0, angle = sin(t * 2.1f + i * 0.7f) * 0.2f * m) {
                            line(floatArrayOf(x, y0, x, y0 - len), edge(s.a2), 0.014f)
                            gem(teardrop(x, y0 - len - 0.07f, 0.055f), if (i % 2 == 0) s.a1 else s.a2)
                        }
                    }
                }
                Hanging.COWRIES -> {
                    val n = 4
                    for (i in 0 until n) {
                        val x = (i - (n - 1) / 2f) * 0.15f
                        val y0 = bottomAt(x)
                        moved(x, y0, angle = sin(t * 1.8f + i * 0.9f) * 0.16f * m) {
                            line(floatArrayOf(x, y0, x, y0 - 0.42f), edge(s.a2), 0.012f)
                            each(3) { k -> cowrie(x, y0 - 0.1f - k * 0.14f, 0.045f, 0.062f) }
                        }
                    }
                }
            }
        }

        fun sides() {
            when (d.sides) {
                Sides.NONE -> Unit
                Sides.DISCS -> both { sx ->
                    val cx = sx * (hw(-0.05f) + 0.1f); val cy = -0.05f
                    moved(cx, cy + 0.12f, angle = sx * sway * 0.08f) {
                        gem(Shapes.ellipse(cx, cy, 0.13f, 0.13f, 28), s.a2)
                        fillRaw(Shapes.ellipse(cx, cy, 0.065f, 0.065f, 20), s.ink)
                        each(6) { k -> val a = k * PI.toFloat() / 3f + t * 0.8f * m; fillRaw(Shapes.ellipse(cx + cos(a) * 0.1f, cy + sin(a) * 0.1f, 0.014f, 0.014f, 8), s.a1) }
                    }
                }
                Sides.LEAVES -> both { sx ->
                    val bx = sx * hw(0.35f) * 0.92f; val by = 0.35f
                    each(3) { k ->
                        val a = -sx * (0.75f + k * 0.4f) + sin(t * 3f + k + sx) * 0.14f * m
                        gem(leaf(bx, by, 0.36f - k * 0.05f, 0.075f, a), if (k % 2 == 0) s.a1 else s.a3)
                    }
                }
                Sides.RINGS -> both { sx ->
                    val cx = sx * (hw(-0.1f) + 0.02f); val topY = -0.14f
                    moved(cx, topY, angle = sx * sway * 0.2f) {
                        line(Shapes.ellipse(cx, topY - 0.11f, 0.1f, 0.11f, 28), edge(s.a2), 0.05f, closed = true)
                        line(Shapes.ellipse(cx, topY - 0.11f, 0.1f, 0.11f, 28), s.a2, 0.028f, closed = true)
                        gem(Shapes.ellipse(cx, topY - 0.24f, 0.035f, 0.035f, 12), s.a1)
                    }
                }
            }
        }
    }

    /** Depth between the layers of a floating piece. */
    private const val LAYER = 0.0025f
    /** How far paint stands off the head, and between its layers. */
    private const val SKIN_LIFT = 0.008f
    private const val SKIN_LAYER = 0.0015f
    /** The longest a painted triangle's edge may be before it is split to follow the curve. */
    private const val SKIN_EDGE = 0.07f
    private const val OUTLINE = 0.026f
    private val DEEP = c(0x160E12)
    private val WHITE = c(0xFFFFFF)

    private fun c(rgb: Int) = (0xFF shl 24) or rgb
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
