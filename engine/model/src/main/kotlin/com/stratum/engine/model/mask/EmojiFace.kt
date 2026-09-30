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
 * `emoji`); this draws the eyes, brows, lips and marks onto it as flat
 * shapes, posed by an [Expression], so the mask can feel. Not a cartoon face
 * wearing a mask: every emotion is said in the masks' own language --
 *
 * - eyes are **slits, crescents, rings and tubes**, dark with the spirit's
 *   light burning inside, never cartoon eyeballs;
 * - **joy** bends the slits up into glowing crescents and lifts the lips;
 * - **anger** plunges Mgbedike's brow and bares the teeth;
 * - **surprise** opens the eyes into round tubes and the mouth into an O;
 * - **hurt** pinches the eyes into uli chevrons;
 * - **focus** narrows them to bright slits and lights the linework;
 * - a **critical** turns them into Mbari sunbursts;
 * - **death** closes them into the crescents the carvings give the dead.
 *
 * The mask keeps who it is through all of it: its own eye form at rest, its
 * kaolin or ochre face, its uli, ichi and cheek marks, its palette.
 */
object EmojiFace {

    /** A feeling: continuous dials, so the face can move between any two. */
    data class Expression(
        /** How open the eyes are: 0 shut, 1 the mask's own, 1.4 wide. */
        val open: Float = 1f,
        /** How far the eyes bend into joyful crescents. */
        val joy: Float = 0f,
        /** The brow: -1 lifted in sorrow at the middle, 0 at rest, 1 plunged in anger. */
        val brow: Float = 0f,
        /** The lips: -1 turned down, 1 turned up. */
        val smile: Float = 0f,
        /** How far the mouth opens. */
        val jaw: Float = 0f,
        /** A round mouth (surprise) rather than a wide one (a shout). */
        val round: Float = 0f,
        /** How bright the spirit burns in the eyes and lines, over the mask's own. */
        val burn: Float = 0f,
        /** A special pair of eyes, replacing the ordinary ones. */
        val eyes: Eyes = Eyes.OWN,
        /** Where the eyes look, -1..1 each way. */
        val lookX: Float = 0f,
        val lookY: Float = 0f,
    )

    enum class Eyes { OWN, CRESCENT, CHEVRON, SUNBURST, RING, SPIRAL }

    /** The set every mask can make, by name: what the game plays and the creator offers. */
    val presets: Map<String, Expression> = linkedMapOf(
        "Calm" to Expression(),
        "Joy" to Expression(joy = 1f, smile = 0.9f, burn = 0.3f),
        "Laugh" to Expression(joy = 1f, smile = 1f, jaw = 0.7f, burn = 0.4f),
        "Anger" to Expression(open = 0.7f, brow = 1f, smile = -0.4f, jaw = 0.35f, burn = 0.6f),
        "Shout" to Expression(open = 1.1f, brow = 0.8f, jaw = 1f, burn = 1f),
        "Surprise" to Expression(open = 1.35f, brow = -0.3f, jaw = 0.8f, round = 1f, eyes = Eyes.RING),
        "Hurt" to Expression(brow = -0.8f, smile = -0.7f, jaw = 0.3f, eyes = Eyes.CHEVRON),
        "Sorrow" to Expression(open = 0.6f, brow = -1f, smile = -0.8f),
        "Focus" to Expression(open = 0.45f, brow = 0.4f, burn = 1.2f),
        "Radiant" to Expression(smile = 0.6f, jaw = 0.4f, burn = 1.4f, eyes = Eyes.SUNBURST),
        "Dazed" to Expression(smile = -0.2f, jaw = 0.2f, eyes = Eyes.SPIRAL),
        "Rest" to Expression(smile = 0.1f, eyes = Eyes.CRESCENT),
        "Blink" to Expression(open = 0f),
    )

    /** [genome]'s face showing [e], laid on [face]. */
    fun build(genome: MaskGenome, face: SpiritFace, e: Expression): SpiritFeatures = Painter(genome.normalised(), face, e).paint()

    private class Painter(val g: MaskGenome, val face: SpiritFace, val e: Expression) {
        val pal = MaskPalettes[g.palette]
        val ink = hex(pal.ink); val faceC = hex(pal.face); val second = hex(pal.second); val accent = hex(pal.accent)
        val ivory = hex(pal.ivory); val crest = hex(pal.crest)
        /** Where lines are drawn: ink, unless ink would vanish into the face. */
        val line = if (abs(luma(ink) - luma(faceC)) > 0.25f) ink else if (abs(luma(crest) - luma(faceC)) > 0.25f) crest else second
        val lip = if (accent != faceC) accent else second
        /** The spirit's light: warm white tinted by the accent, hotter as it burns. */
        val light = mix(0xFFFFE9A6.toInt(), accent, 0.25f)
        val w = face.plan[0]; val ey = face.plan[1]; val ex = face.plan[2]; val my = face.plan[3]
        val burn = (e.burn).coerceIn(0f, 2f)
        /** Emoji proportions: features bigger and lines bolder than a carving's. */
        val big = 1.3f

        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val glw = ArrayList<Float>(); val idx = ArrayList<Int>()
        private val tmp = FloatArray(6)
        private var layer = 0

        fun paint(): SpiritFeatures {
            marks()
            for (side in listOf(-1f, 1f)) brow(side)
            for (side in listOf(-1f, 1f)) eye(side)
            mouth()
            return SpiritFeatures(pos.toFloatArray(), nrm.toFloatArray(), col.toIntArray(), glw.toFloatArray(), idx.toIntArray())
        }

        // ---- laying shapes on the face -------------------------------------

        /** Fills polygon [pts] (face units) with [color] on the next layer up. */
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

        /** A thick line along [pts], round at the ends. */
        fun stroke(pts: FloatArray, width: Float, color: Int, glow: Float = 0f) {
            val n = pts.size / 2
            if (n < 2) return
            val h = width / 2f
            val left = FloatArray(n * 2); val right = FloatArray(n * 2)
            for (i in 0 until n) {
                val j = min(n - 1, i + 1); val k = max(0, i - 1)
                var dx = pts[j * 2] - pts[k * 2]; var dy = pts[j * 2 + 1] - pts[k * 2 + 1]
                val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f); dx /= l; dy /= l
                left[i * 2] = pts[i * 2] - dy * h; left[i * 2 + 1] = pts[i * 2 + 1] + dx * h
                right[i * 2] = pts[i * 2] + dy * h; right[i * 2 + 1] = pts[i * 2 + 1] - dx * h
            }
            val outline = FloatArray(n * 4)
            for (i in 0 until n) { outline[i * 2] = left[i * 2]; outline[i * 2 + 1] = left[i * 2 + 1] }
            for (i in 0 until n) { val r = n - 1 - i; outline[(n + i) * 2] = right[r * 2]; outline[(n + i) * 2 + 1] = right[r * 2 + 1] }
            fill(outline, color, glow)
            layer--
            fill(Shapes.ellipse(pts[0], pts[1], h, h, 10), color, glow); layer--
            fill(Shapes.ellipse(pts[(n - 1) * 2], pts[(n - 1) * 2 + 1], h, h, 10), color, glow)
        }

        // ---- the features ---------------------------------------------------

        /** Brows: carved arcs running into the nose's bridge, as on the masks; anger plunges them, sorrow lifts them. */
        fun brow(side: Float) {
            val cx = side * ex
            val cy = ey + 0.24f - 0.03f * e.brow
            val inner = e.brow.coerceIn(-1f, 1f) * 0.1f
            val thick = (0.05f + 0.06f * g.brow + 0.025f * max(0f, e.brow)) * big
            val half = 0.2f * (w / 0.66f).coerceIn(0.8f, 1.2f) * big
            val pts = Shapes.quad(cx - side * half, cy - inner, cx, cy + 0.07f - 0.03f * max(0f, e.brow), cx + side * half, cy - 0.02f + inner * 0.3f, 10)
            stroke(pts, thick, line)
        }

        fun eye(side: Float) {
            val cx = side * ex; val cy = ey
            val half = 0.19f * (w / 0.66f).coerceIn(0.8f, 1.25f) * big
            val special = when {
                e.eyes != Eyes.OWN -> e.eyes
                e.open < 0.08f -> Eyes.CRESCENT
                else -> null
            }
            val glowLevel = 0.6f + burn
            when (special) {
                Eyes.CRESCENT -> {
                    // Closed: the line sagging to a curve, the dead's and the sleeper's.
                    stroke(Shapes.quad(cx - half, cy + 0.02f, cx, cy - 0.11f, cx + half, cy + 0.02f, 10), 0.07f, line)
                }
                Eyes.CHEVRON -> {
                    // Pinched shut: an uli chevron pointing in.
                    val tip = cx - side * half * 0.5f
                    stroke(floatArrayOf(cx + side * half * 0.6f, cy + 0.1f, tip, cy, cx + side * half * 0.6f, cy - 0.1f), 0.07f, line)
                }
                Eyes.SUNBURST -> {
                    // The Mbari sun: a burning disc with rays.
                    for (r in 0 until 8) {
                        val a = r * PI.toFloat() / 4f
                        stroke(floatArrayOf(cx + cos(a) * 0.13f, cy + sin(a) * 0.13f, cx + cos(a) * 0.25f, cy + sin(a) * 0.25f), 0.045f, light, 1f + burn)
                    }
                    fill(Shapes.ellipse(cx, cy, 0.11f, 0.11f, 24), line)
                    fill(Shapes.ellipse(cx, cy, 0.078f, 0.078f, 24), light, 1f + burn)
                }
                Eyes.RING -> ringEye(cx, cy, 0.14f * big * e.open.coerceIn(0.8f, 1.4f), glowLevel)
                Eyes.SPIRAL -> {
                    val pts = ArrayList<Float>()
                    for (i in 0..40) {
                        val t = i / 40f; val a = t * 4.5f * PI.toFloat() * side; val r = 0.02f + 0.16f * t
                        pts += cx + cos(a) * r; pts += cy + sin(a) * r
                    }
                    stroke(pts.toFloatArray(), 0.04f, line)
                }
                else -> ownEye(cx, cy, side, half, glowLevel)
            }
        }

        /** The mask's own eye, opened or narrowed, bent by joy, looking where it looks. */
        fun ownEye(cx: Float, cy: Float, side: Float, half: Float, glowLevel: Float) {
            val open = e.open.coerceIn(0f, 1.5f)
            val lx = e.lookX * 0.03f; val ly = e.lookY * 0.02f
            // Joy bends every eye, slit or ring or tube, into a glowing crescent.
            if (e.joy > 0.5f) {
                val arc = Shapes.quad(cx - half, cy - 0.04f, cx, cy + 0.13f + 0.05f * e.joy, cx + half, cy - 0.04f, 12)
                stroke(arc, 0.085f, line)
                stroke(Shapes.quad(cx - half * 0.68f, cy - 0.02f, cx, cy + 0.095f + 0.035f * e.joy, cx + half * 0.68f, cy - 0.02f, 10), 0.032f, light, glowLevel)
                return
            }
            when (g.eyes) {
                EyeForm.ROUND -> { ringEye(cx, cy, 0.12f * big * max(0.4f, open), glowLevel); return }
                EyeForm.TUBULAR -> {
                    val r = 0.13f * big * max(0.4f, open)
                    fill(Shapes.ellipse(cx, cy, r, r, 28), lip)
                    fill(Shapes.ellipse(cx, cy, r * 0.62f, r * 0.62f, 24), line)
                    fill(Shapes.ellipse(cx + lx, cy + ly, r * 0.36f, r * 0.36f, 20), light, glowLevel)
                    return
                }
                else -> Unit
            }
            val restH = (if (g.eyes == EyeForm.CRESCENT) 0.06f else 0.085f) * big
            val h = restH * open
            if (h < 0.015f) { stroke(floatArrayOf(cx - half, cy, cx + half, cy + side * 0.01f), 0.04f, line); return }
            // The outer corner lifts, as the maiden's does.
            val lid = almond(cx, cy, half, h, side)
            fill(lid, line)
            fill(almond(cx + lx, cy - 0.004f + ly, half * 0.78f, max(0.012f, h * 0.45f), side), light, glowLevel)
        }

        /** The Mbari stare: ringed like a target, the centre burning. */
        fun ringEye(cx: Float, cy: Float, r: Float, glowLevel: Float) {
            fill(Shapes.ellipse(cx, cy, r, r, 28), line)
            fill(Shapes.ellipse(cx, cy, r * 0.76f, r * 0.76f, 28), if (second != faceC) second else ivory)
            fill(Shapes.ellipse(cx, cy, r * 0.5f, r * 0.5f, 24), line)
            fill(Shapes.ellipse(cx + e.lookX * 0.02f, cy + e.lookY * 0.02f, r * 0.3f, r * 0.3f, 20), light, glowLevel)
        }

        fun almond(cx: Float, cy: Float, half: Float, h: Float, side: Float): FloatArray {
            val lift = h * 0.35f
            val ix = cx - side * half; val ox = cx + side * half
            val upper = Shapes.quad(ix, cy, cx, cy + h * 1.7f, ox, cy + lift, 12)
            val lower = Shapes.quad(ox, cy + lift, cx, cy - h * 1.3f, ix, cy, 12)
            val out = upper + lower.copyOfRange(2, lower.size - 2)
            // Wind it one way, whichever side it is on.
            return if (side < 0f) reverse(out) else out
        }

        /** The lips: carved in the lip colour, parted by the jaw; teeth where the mask has them or a shout bares them. */
        fun mouth() {
            val jaw = e.jaw.coerceIn(0f, 1.2f)
            val smile = e.smile.coerceIn(-1f, 1f)
            val my = my + 0.02f
            val wide = when (g.mouth) { MouthForm.PURSED -> 0.12f; MouthForm.OPEN_TEETH -> 0.24f; else -> 0.19f } * (1f - 0.45f * e.round) * big
            val corner = smile * 0.07f
            if (jaw < 0.12f && g.mouth != MouthForm.OPEN_TEETH) {
                if (g.mouth == MouthForm.PURSED && abs(smile) < 0.3f) {
                    fill(Shapes.ellipse(0f, my, 0.12f, 0.085f, 24), lip)
                    fill(Shapes.ellipse(0f, my, 0.04f, 0.028f, 12), line)
                    return
                }
                // Closed lips: a full shape in the lip colour, parted by a line that follows the smile.
                val upper = Shapes.quad(-wide, my + corner, 0f, my + 0.09f, wide, my + corner, 12)
                val lower = Shapes.quad(wide, my + corner, 0f, my - 0.1f - 0.03f * max(0f, smile), -wide, my + corner, 12)
                fill(upper + lower.copyOfRange(2, lower.size - 2), lip)
                stroke(Shapes.quad(-wide * 0.95f, my + corner, 0f, my - corner * 0.8f, wide * 0.95f, my + corner, 12), 0.04f, line)
                return
            }
            // Open: lips round a dark mouth; a shout is wide, surprise is round.
            val open = max(jaw, if (g.mouth == MouthForm.OPEN_TEETH) 0.35f else 0f)
            val top = my + 0.04f + corner * 0.5f
            val bottom = my - 0.06f - 0.24f * open
            val half = wide * (1f + 0.25f * open * (1f - e.round))
            val outer = if (e.round > 0.5f) Shapes.ellipse(0f, (top + bottom) / 2f, half * 0.75f + 0.03f, (top - bottom) / 2f + 0.03f, 28)
            else roundedMouth(half + 0.03f, top + 0.03f, bottom - 0.03f, corner)
            fill(outer, lip)
            val inner = if (e.round > 0.5f) Shapes.ellipse(0f, (top + bottom) / 2f, half * 0.75f, (top - bottom) / 2f, 28)
            else roundedMouth(half, top, bottom, corner)
            fill(inner, line)
            // Teeth: ivory blocks under the upper lip, as Mgbedike bares them.
            if (g.mouth == MouthForm.OPEN_TEETH || e.brow > 0.5f) {
                val n = 5
                val tw = half * 1.6f / n
                for (i in 0 until n) {
                    val x0 = -half * 0.8f + i * tw
                    fill(Shapes.roundRect(x0 + 0.006f, top - 0.055f, x0 + tw - 0.006f, top - 0.005f, 0.012f), ivory)
                }
            }
            // A glow deep in the mouth when the spirit burns: a shout is light pouring out.
            if (burn > 0.5f && open > 0.4f) fill(Shapes.ellipse(0f, (top + bottom) / 2f - 0.01f, half * 0.4f, (top - bottom) * 0.22f, 20), light, burn)
        }

        fun roundedMouth(half: Float, top: Float, bottom: Float, corner: Float): FloatArray {
            val upper = Shapes.quad(-half, top - 0.02f + corner, 0f, top + 0.02f, half, top - 0.02f + corner, 12)
            val lower = Shapes.quad(half, top - 0.02f + corner, half * 0.9f, bottom, 0f, bottom, 8)
            val lower2 = Shapes.quad(0f, bottom, -half * 0.9f, bottom, -half, top - 0.02f + corner, 8)
            return upper + lower.copyOfRange(2, lower.size) + lower2.copyOfRange(2, lower2.size - 2)
        }

        /** Uli, ichi and cheek marks: the mask's drawn ornament, lit by the spirit when it burns. */
        fun marks() {
            val lit = if (burn > 0.9f) 0.8f else 0f
            val sides = if (g.symmetric) listOf(-1f, 1f) else listOf(1f)
            for (i in 0 until g.ichi) {
                val y = ey + 0.34f + i * 0.07f
                val half = min(w * 0.55f, 0.32f)
                if (y > 0.8f) break
                stroke(floatArrayOf(-half, y, half, y), 0.034f, line, lit)
            }
            for (s in sides) for (i in 0 until g.cheekMarks) {
                val x = s * (ex + 0.02f * i + 0.02f)
                val y = ey - 0.25f - i * 0.02f
                stroke(floatArrayOf(x - s * 0.04f + i * s * 0.06f, y + 0.09f, x + s * 0.02f + i * s * 0.06f, y - 0.08f), 0.036f, lip, lit)
            }
            // Uli: dots arched over the brow and a chevron on the chin, as the ornament dial asks.
            val dots = (g.ornament * 9).toInt()
            for (i in 0 until dots) {
                val t = if (dots == 1) 0.5f else i / (dots - 1f)
                if (!g.symmetric && t < 0.5f) continue
                val x = (t * 2f - 1f) * min(w * 0.62f, 0.4f)
                val y = ey + 0.5f + 0.08f * sin(t * PI.toFloat())
                if (y > 0.85f) continue
                fill(Shapes.ellipse(x, y, 0.036f, 0.036f, 12), if (i % 2 == 0) lip else line, lit)
            }
            if (g.ornament > 0.55f) stroke(floatArrayOf(-0.12f, my - 0.24f, 0f, my - 0.31f, 0.12f, my - 0.24f), 0.03f, line, lit)
            // Warm cheeks when it smiles.
            if (e.smile > 0.4f || e.joy > 0.5f) for (s in listOf(-1f, 1f)) fill(Shapes.ellipse(s * ex * 1.1f, ey - 0.26f, 0.1f, 0.06f, 20), mix(faceC, lip, 0.45f))
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

    private fun reverse(p: FloatArray): FloatArray { val n = p.size / 2; return FloatArray(p.size) { i -> p[(n - 1 - i / 2) * 2 + i % 2] } }
    private fun hex(s: String): Int = (0xFF000000.toInt()) or s.removePrefix("#").toInt(16)
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun luma(c: Int) = (0.299f * ch(c, 16) + 0.587f * ch(c, 8) + 0.114f * ch(c, 0)) / 255f
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
