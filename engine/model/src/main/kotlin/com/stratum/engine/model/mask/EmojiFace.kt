package com.stratum.engine.model.mask

import com.stratum.engine.scene.SpiritFace
import com.stratum.engine.scene.SpiritFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mask's live face: Igbo masks as emoji.
 *
 * The head is a 3D mask with its face left bare ([MaskSpiritMesher] with
 * `emoji`), glossy as a vinyl toy; this draws its face onto it as flat
 * shapes, posed by an [Expression]. The look is a sticker emoji's -- dark
 * crescents for closed happy eyes, glossy dot eyes, a wink, plump red lips,
 * rosy cheeks -- and what makes it Igbo is what it is painted with: ichi
 * bars on the brow, cheek stripes, an uli cross and dots, the mask's own
 * palette and eyes (a maiden's calm lids, Mbari's ringed stare).
 */
object EmojiFace {

    /** How one eye looks. */
    enum class Eye {
        /** The mask's own: almond lids resting closed, crescents smiling, rings staring, dots bright. */
        OWN,
        /** A glossy dark dot with a highlight. */
        DOT,
        /** Closed and smiling: an arc bowed up. */
        HAPPY,
        /** Closed in calm or sleep: a lid bowed down, a lash at its corner. */
        LID,
        /** Squeezed shut: a chevron. */
        PINCH,
        /** Wide and ringed, Mbari's stare. */
        RING,
        HEART,
        STAR,
        SPIRAL,
    }

    enum class Mouth {
        /** The mask's own: plump lips, a pursed kiss, or a toothy grin. */
        OWN,
        SMILE,
        GRIN,
        LAUGH,
        OH,
        FROWN,
        WOBBLE,
        TONGUE,
    }

    enum class Brows { NONE, SOFT, ANGRY, SAD, UP }

    /** A feeling, as parts: each eye, the mouth, the brows, and what else shows. */
    data class Expression(
        val left: Eye = Eye.OWN,
        val right: Eye = Eye.OWN,
        val mouth: Mouth = Mouth.OWN,
        val brows: Brows = Brows.NONE,
        val cheeks: Boolean = true,
        val tear: Boolean = false,
        /** Where open eyes look, -1..1 each way. */
        val lookX: Float = 0f,
        val lookY: Float = 0f,
    )

    /** The set every mask can make, by name: what the game plays and the creator offers. */
    val presets: Map<String, Expression> = linkedMapOf(
        "Calm" to Expression(),
        "Smile" to Expression(Eye.HAPPY, Eye.HAPPY, Mouth.SMILE),
        "Wink" to Expression(Eye.DOT, Eye.HAPPY, Mouth.SMILE),
        "Laugh" to Expression(Eye.HAPPY, Eye.HAPPY, Mouth.LAUGH),
        "Cheeky" to Expression(Eye.DOT, Eye.HAPPY, Mouth.TONGUE),
        "Love" to Expression(Eye.HEART, Eye.HEART, Mouth.SMILE),
        "Star" to Expression(Eye.STAR, Eye.STAR, Mouth.GRIN),
        "Surprise" to Expression(Eye.RING, Eye.RING, Mouth.OH, Brows.UP, cheeks = false),
        "Anger" to Expression(Eye.DOT, Eye.DOT, Mouth.FROWN, Brows.ANGRY, cheeks = false),
        "Shout" to Expression(Eye.PINCH, Eye.PINCH, Mouth.GRIN, Brows.ANGRY, cheeks = false),
        "Hurt" to Expression(Eye.PINCH, Eye.PINCH, Mouth.WOBBLE, Brows.SAD),
        "Sad" to Expression(Eye.DOT, Eye.DOT, Mouth.FROWN, Brows.SAD, cheeks = false, tear = true),
        "Dizzy" to Expression(Eye.SPIRAL, Eye.SPIRAL, Mouth.WOBBLE, cheeks = false),
        "Sleep" to Expression(Eye.LID, Eye.LID, Mouth.OWN),
        "Blink" to Expression(Eye.LID, Eye.LID),
    )

    /** [genome]'s face showing [e], laid on [face]. */
    fun build(genome: MaskGenome, face: SpiritFace, e: Expression): SpiritFeatures = Painter(genome.normalised(), face, e).paint()

    private class Painter(val g: MaskGenome, val face: SpiritFace, val e: Expression) {
        val pal = MaskPalettes[g.palette]
        val faceC = hex(pal.face); val second = hex(pal.second); val accent = hex(pal.accent); val crest = hex(pal.crest)
        val light = luma(faceC) > 0.45f
        /** Eyes and lines: near-black brown on a light face, deep brown-black on a dark one too -- emoji ink. */
        val ink = when {
            light -> 0xFF2B1A1C.toInt()
            // On a near-black face (Okoroshi Ojo) the face is drawn in kaolin, as those masks are.
            luma(faceC) < 0.2f -> 0xFFF4EEE2.toInt()
            else -> 0xFF1A0F10.toInt()
        }
        /** Marks: the palette's strongest contrast to the face. */
        val mark = listOf(hex(pal.ink), crest, second, accent).maxBy { abs(luma(it) - luma(faceC)) }
        val lip = if (hueRed(faceC)) 0xFF6E1219.toInt() else 0xFFB3202C.toInt()
        val cheek = mix(faceC, 0xFFF2878F.toInt(), if (light) 0.55f else 0.4f)
        val gold = 0xFFE9B23C.toInt()
        val white = 0xFFFFFBF4.toInt()
        val w = face.plan[0]; val ey = face.plan[1] - 0.02f; val ex = face.plan[2] * 1.05f; val my = face.plan[3] + 0.06f
        val size = if (g.flower) 0.62f else (w / 0.8f).coerceIn(0.85f, 1.15f) * 1.3f

        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val glw = ArrayList<Float>(); val idx = ArrayList<Int>()
        private val tmp = FloatArray(6)
        private var layer = 0

        fun paint(): SpiritFeatures {
            marks()
            if (e.cheeks) for (s in listOf(-1f, 1f)) fill(Shapes.ellipse(s * ex * 1.18f, ey - 0.23f * size, 0.1f * size, 0.062f * size, 24), cheek)
            brows()
            eye(-1f, e.left); eye(1f, e.right)
            if (g.shades) shades()
            if (e.tear) tear()
            mouth()
            return SpiritFeatures(pos.toFloatArray(), nrm.toFloatArray(), col.toIntArray(), glw.toFloatArray(), idx.toIntArray())
        }

        // ---- laying shapes on the face -------------------------------------

        fun fill(pts: FloatArray, color: Int, glow: Float = 0f) {
            val tris = Triangulate.polygon(pts)
            if (tris.isEmpty()) return
            val lift = LIFT0 + LIFT_STEP * layer++
            val first = pos.size / 3
            val n = pts.size / 2
            val placed = BooleanArray(n)
            for (i in 0 until n) {
                placed[i] = face.surface(pts[i * 2], pts[i * 2 + 1], lift, tmp)
                pos += tmp[0]; pos += tmp[1]; pos += tmp[2]; nrm += tmp[3]; nrm += tmp[4]; nrm += tmp[5]
                col += color; glw += glow
            }
            for (t in tris.indices step 3) {
                val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
                if (placed[a] && placed[b] && placed[c]) { idx += first + a; idx += first + b; idx += first + c }
            }
        }

        fun stroke(pts: FloatArray, width: Float, color: Int, glow: Float = 0f) {
            val n = pts.size / 2
            if (n < 2) return
            val h = width / 2f
            val outline = FloatArray(n * 4)
            for (i in 0 until n) {
                val j = min(n - 1, i + 1); val k = max(0, i - 1)
                var dx = pts[j * 2] - pts[k * 2]; var dy = pts[j * 2 + 1] - pts[k * 2 + 1]
                val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f); dx /= l; dy /= l
                outline[i * 2] = pts[i * 2] - dy * h; outline[i * 2 + 1] = pts[i * 2 + 1] + dx * h
                val r = 2 * n - 1 - i
                outline[r * 2] = pts[i * 2] + dy * h; outline[r * 2 + 1] = pts[i * 2 + 1] - dx * h
            }
            fill(outline, color, glow); layer--
            fill(Shapes.ellipse(pts[0], pts[1], h, h, 10), color, glow); layer--
            fill(Shapes.ellipse(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], h, h, 10), color, glow)
        }

        // ---- eyes -------------------------------------------------------------

        fun eye(side: Float, look: Eye) {
            val cx = side * ex; val cy = ey
            val r = 0.11f * size
            val own = look == Eye.OWN
            val kind = if (!own) look else when (g.eyes) {
                EyeForm.ALMOND -> Eye.LID
                EyeForm.CRESCENT -> Eye.HAPPY
                EyeForm.ROUND -> Eye.DOT
                EyeForm.TUBULAR -> Eye.RING
            }
            when (kind) {
                Eye.DOT -> dot(cx + e.lookX * 0.025f, cy + e.lookY * 0.02f, r * 0.72f, r)
                Eye.HAPPY -> stroke(Shapes.quad(cx - r * 1.25f, cy - r * 0.35f, cx, cy + r * 1.35f, cx + r * 1.25f, cy - r * 0.35f, 12), 0.075f * size, ink)
                Eye.LID -> {
                    // The maiden's resting lid: bowed down, a small lash lifting at the outer corner.
                    val lid = Shapes.quad(cx - r * 1.3f, cy + r * 0.2f, cx, cy - r * 1.0f, cx + r * 1.3f, cy + r * 0.2f, 12)
                    stroke(lid, 0.07f * size, ink)
                    val ox = cx + side * r * 1.3f
                    stroke(floatArrayOf(ox, cy + r * 0.2f, ox + side * r * 0.35f, cy + r * 0.55f), 0.045f * size, ink)
                }
                Eye.PINCH -> {
                    val tip = cx - side * r * 0.55f
                    stroke(floatArrayOf(cx + side * r * 0.9f, cy + r * 0.8f, tip, cy, cx + side * r * 0.9f, cy - r * 0.8f), 0.075f * size, ink)
                }
                Eye.RING -> {
                    // Mbari's stare: a ring of gold round a glossy dark eye.
                    val rr = r * 1.45f
                    fill(Shapes.ellipse(cx, cy, rr, rr, 32), ink)
                    fill(Shapes.ellipse(cx, cy, rr * 0.84f, rr * 0.84f, 32), gold)
                    fill(Shapes.ellipse(cx, cy, rr * 0.62f, rr * 0.62f, 32), white)
                    dot(cx + e.lookX * 0.03f, cy + e.lookY * 0.03f, rr * 0.42f, rr * 0.42f)
                }
                Eye.HEART -> fill(heart(cx, cy, r * 1.25f), 0xFFE0303D.toInt(), 0.3f)
                Eye.STAR -> {
                    fill(star(cx, cy, r * 1.4f, r * 0.62f), gold, 0.8f)
                    fill(Shapes.ellipse(cx - r * 0.3f, cy + r * 0.35f, r * 0.2f, r * 0.14f, 12), white, 0.5f)
                }
                Eye.SPIRAL -> {
                    val pts = ArrayList<Float>()
                    for (i in 0..44) {
                        val t = i / 44f; val a = t * 4.5f * PI.toFloat() * side; val rad = 0.015f + r * 1.25f * t
                        pts += cx + cos(a) * rad; pts += cy + sin(a) * rad
                    }
                    stroke(pts.toFloatArray(), 0.045f * size, ink)
                }
                Eye.OWN -> Unit
            }
        }

        /** A glossy dark eye: the dot, and its highlight catching the light. */
        fun dot(cx: Float, cy: Float, rx: Float, ry: Float) {
            fill(Shapes.ellipse(cx, cy, rx, ry, 28), ink)
            fill(Shapes.ellipse(cx - rx * 0.3f, cy + ry * 0.38f, rx * 0.34f, ry * 0.28f, 16), white, 0.4f)
            fill(Shapes.ellipse(cx + rx * 0.32f, cy - ry * 0.35f, rx * 0.14f, ry * 0.12f, 10), white, 0.4f)
        }

        fun heart(cx: Float, cy: Float, r: Float): FloatArray {
            val out = ArrayList<Float>()
            for (i in 0 until 40) {
                val t = i / 40f * 2f * PI.toFloat()
                val x = 16f * sin(t).let { it * it * it }
                val y = 13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)
                out += cx + x / 17f * r; out += cy + y / 17f * r
            }
            return out.toFloatArray()
        }

        fun star(cx: Float, cy: Float, outer: Float, inner: Float): FloatArray {
            val out = FloatArray(20)
            for (i in 0 until 10) {
                val a = PI.toFloat() / 2f + i * PI.toFloat() / 5f
                val r = if (i % 2 == 0) outer else inner
                out[i * 2] = cx + cos(a) * r; out[i * 2 + 1] = cy + sin(a) * r
            }
            return out
        }

        fun brows() {
            if (e.brows == Brows.NONE) return
            for (s in listOf(-1f, 1f)) {
                val cx = s * ex; val cy = ey + 0.23f * size
                val half = 0.12f * size
                val (inner, outer) = when (e.brows) {
                    Brows.ANGRY -> -0.07f to 0.04f
                    Brows.SAD -> 0.06f to -0.04f
                    Brows.UP -> 0.05f to 0.05f
                    else -> 0f to 0f
                }
                stroke(Shapes.quad(cx - s * half, cy + inner, cx, cy + 0.03f + (inner + outer) / 2f, cx + s * half, cy + outer, 8), 0.06f * size, ink)
            }
        }

        /** Dark glasses: two rounded lenses and a bridge, a stripe of light across each. */
        fun shades() {
            val lens = 0xFF18161C.toInt()
            val r = 0.17f * size
            for (s in listOf(-1f, 1f)) {
                val cx = s * ex
                fill(Shapes.roundRect(cx - r * 1.15f, ey - r * 0.8f, cx + r * 1.15f, ey + r * 0.75f, r * 0.45f), lens)
                fill(floatArrayOf(cx - r * 0.8f, ey + r * 0.45f, cx - r * 0.45f, ey + r * 0.45f, cx - r * 0.95f, ey - r * 0.4f, cx - r * 1.05f, ey - r * 0.1f), 0xFF6A6A78.toInt(), 0.2f)
            }
            stroke(floatArrayOf(-ex + r * 1.1f, ey + r * 0.35f, 0f, ey + r * 0.5f, ex - r * 1.1f, ey + r * 0.35f), 0.04f * size, lens)
        }

        fun tear() {
            val x = ex * 1.05f; val y = ey - 0.2f
            val drop = ArrayList<Float>()
            for (i in 0 until 24) {
                val t = i / 24f * 2f * PI.toFloat()
                val rx = 0.045f; val ry = 0.06f
                val px = sin(t) * rx * (1f - 0.5f * max(0f, cos(t)))
                val py = -cos(t) * ry
                drop += x + px; drop += y + py + if (cos(t) > 0.7f) (cos(t) - 0.7f) * 0.2f else 0f
            }
            fill(drop.toFloatArray(), 0xFF6FC3F0.toInt(), 0.3f)
        }

        // ---- mouth -------------------------------------------------------------

        fun mouth() {
            val kind = if (e.mouth != Mouth.OWN) e.mouth else when (g.mouth) {
                MouthForm.CLOSED_SMILE -> Mouth.OWN
                MouthForm.PURSED -> Mouth.OWN
                MouthForm.OPEN_TEETH -> Mouth.GRIN
            }
            val y = my
            val half = 0.15f * size
            when (kind) {
                Mouth.OWN -> if (g.mouth == MouthForm.PURSED) kiss(y) else lips(y, half)
                Mouth.SMILE -> stroke(Shapes.quad(-half, y + 0.03f, 0f, y - 0.1f, half, y + 0.03f, 12), 0.06f * size, ink)
                Mouth.FROWN -> stroke(Shapes.quad(-half * 0.8f, y - 0.04f, 0f, y + 0.06f, half * 0.8f, y - 0.04f, 12), 0.06f * size, ink)
                Mouth.WOBBLE -> {
                    val pts = FloatArray(26)
                    for (i in 0 until 13) { val t = i / 12f; pts[i * 2] = -half + 2 * half * t; pts[i * 2 + 1] = y + sin(t * 3f * PI.toFloat()) * 0.025f }
                    stroke(pts, 0.055f * size, ink)
                }
                Mouth.OH -> {
                    fill(Shapes.ellipse(0f, y - 0.02f, 0.07f * size, 0.09f * size, 28), ink)
                    fill(Shapes.ellipse(0f, y - 0.05f, 0.045f * size, 0.035f * size, 20), 0xFFC9434E.toInt())
                }
                Mouth.GRIN, Mouth.LAUGH, Mouth.TONGUE -> {
                    val depth = if (kind == Mouth.LAUGH) 0.2f else 0.14f
                    val open = open(half * 1.05f, y + 0.035f, y + 0.035f - depth * size)
                    fill(open, 0xFF4A1016.toInt())
                    if (kind == Mouth.GRIN || g.mouth == MouthForm.OPEN_TEETH) {
                        fill(Shapes.roundRect(-half * 0.82f, y - 0.015f, half * 0.82f, y + 0.03f, 0.02f), white)
                    }
                    if (kind != Mouth.GRIN) fill(Shapes.ellipse(0f, y + 0.035f - depth * size * 0.72f, half * 0.5f, depth * size * 0.28f, 20), 0xFFE8707B.toInt())
                    if (kind == Mouth.TONGUE) fill(Shapes.roundRect(-half * 0.3f, y - depth * size - 0.07f, half * 0.3f, y - depth * size * 0.4f, 0.05f), 0xFFE8707B.toInt())
                }
            }
        }

        /** An open mouth: flat along the top, round below, like a D on its side. */
        fun open(half: Float, top: Float, bottom: Float): FloatArray {
            val out = ArrayList<Float>()
            val upper = Shapes.quad(-half, top, 0f, top - 0.02f, half, top, 8)
            for (i in upper.indices) out += upper[i]
            val lower = Shapes.quad(half, top, half * 0.9f, bottom - (top - bottom) * 0.35f, -half, top, 16)
            for (i in 2 until lower.size - 2) out += lower[i]
            return out.toFloatArray()
        }

        /** Plump lips: a bowed upper lip, a full lower, a highlight on it, as the maiden's are painted with camwood. */
        fun lips(y: Float, half: Float) {
            val hw = half * 0.8f
            val upper = ArrayList<Float>()
            upper += -hw; upper += y
            upper += -hw * 0.5f; upper += y + 0.045f
            upper += 0f; upper += y + 0.02f
            upper += hw * 0.5f; upper += y + 0.045f
            upper += hw; upper += y
            val lower = Shapes.quad(hw, y, 0f, y - 0.11f, -hw, y, 12)
            val shape = upper.toFloatArray() + lower.copyOfRange(2, lower.size - 2)
            fill(shape, lip)
            stroke(floatArrayOf(-hw * 0.95f, y, 0f, y - 0.008f, hw * 0.95f, y), 0.018f, darken(lip, 0.45f))
            fill(Shapes.ellipse(-hw * 0.25f, y - 0.035f, hw * 0.25f, 0.012f, 12), mix(lip, white, 0.55f), 0.3f)
        }

        fun kiss(y: Float) {
            fill(Shapes.ellipse(0f, y, 0.07f * size, 0.055f * size, 24), lip)
            fill(Shapes.ellipse(0f, y, 0.025f * size, 0.02f * size, 12), darken(lip, 0.5f))
        }

        // ---- Igbo marks ---------------------------------------------------------

        /** Ichi bars on the brow, stripes on the cheeks, an uli cross or a crown of dots: what makes the emoji Igbo. */
        fun marks() {
            val sides = if (g.symmetric) listOf(-1f, 1f) else listOf(1f)
            // Under hair or a hat the brow is low: the marks sit on skin, not hair.
            val brow = if (g.hair != HairStyle.NONE || g.hat) ey + 0.3f else ey + 0.42f * size
            if (g.ichi > 0) {
                // Ichi: short upright bars, side by side, high on the brow.
                val n = g.ichi.coerceAtMost(4)
                for (i in 0 until n) {
                    val x = (i - (n - 1) / 2f) * 0.07f
                    stroke(floatArrayOf(x, brow + 0.08f, x, brow - 0.06f), 0.034f, mark)
                }
            } else if (g.ornament > 0.45f) {
                // The uli cross: two strokes, the brow's one jewel.
                val r = 0.07f
                stroke(floatArrayOf(-r, brow + r, r, brow - r), 0.034f, mark)
                stroke(floatArrayOf(-r, brow - r, r, brow + r), 0.034f, mark)
            }
            for (s in sides) for (i in 0 until g.cheekMarks) {
                // Cheek stripes: short parallel bars under the eye, toward the ear.
                val y = ey - 0.2f - i * 0.065f
                val x0 = s * (ex + 0.06f); val x1 = s * (ex + 0.22f)
                stroke(floatArrayOf(x0, y, x1, y + 0.012f), 0.03f, mark)
            }
            val dots = (g.ornament * 7).toInt()
            if (dots > 1 && g.ichi == 0) for (i in 0 until dots) {
                val t = i / (dots - 1f)
                val x = (t * 2f - 1f) * 0.24f
                val y = brow + 0.16f - 0.05f * (1f - (2f * t - 1f).let { it * it })
                if (!g.symmetric && t < 0.5f) continue
                fill(Shapes.ellipse(x, y, 0.024f, 0.024f, 10), if (i % 2 == 0) gold else mark)
            }
        }
    }

    /** Ear-clipping triangulation of a simple polygon, either winding. */
    object Triangulate {
        fun polygon(p: FloatArray): IntArray {
            val n = p.size / 2
            if (n < 3) return IntArray(0)
            var area = 0f
            for (i in 0 until n) { val j = (i + 1) % n; area += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1] }
            val ccw = area > 0f
            val v = ArrayList<Int>(n).apply { for (i in 0 until n) add(if (ccw) i else n - 1 - i) }
            val out = ArrayList<Int>((n - 2) * 3)
            var guard = 0
            while (v.size > 3 && guard++ < n * n) {
                var clipped = false
                for (k in v.indices) {
                    val a = v[(k + v.size - 1) % v.size]; val b = v[k]; val c = v[(k + 1) % v.size]
                    if (cross(p, a, b, c) <= 1e-9f) continue
                    var inside = false
                    for (m in v) if (m != a && m != b && m != c && inTriangle(p, m, a, b, c)) { inside = true; break }
                    if (inside) continue
                    out += a; out += b; out += c
                    v.removeAt(k); clipped = true; break
                }
                if (!clipped) break
            }
            if (v.size == 3) { out += v[0]; out += v[1]; out += v[2] }
            return out.toIntArray()
        }

        private fun cross(p: FloatArray, a: Int, b: Int, c: Int) =
            (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])

        private fun inTriangle(p: FloatArray, m: Int, a: Int, b: Int, c: Int): Boolean {
            val d1 = cross(p, a, b, m); val d2 = cross(p, b, c, m); val d3 = cross(p, c, a, m)
            return d1 >= 0f && d2 >= 0f && d3 >= 0f
        }
    }

    private const val LIFT0 = 0.012f
    private const val LIFT_STEP = 0.006f

    private fun hex(s: String): Int = (0xFF000000.toInt()) or s.removePrefix("#").toInt(16)
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun luma(c: Int) = (0.299f * ch(c, 16) + 0.587f * ch(c, 8) + 0.114f * ch(c, 0)) / 255f
    private fun darken(c: Int, t: Float) = mix(c, 0xFF000000.toInt(), t)
    /** True for a reddish face, where red lips would vanish. */
    private fun hueRed(c: Int) = ch(c, 16) > ch(c, 8) + 50 && ch(c, 16) > ch(c, 0) + 50
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
