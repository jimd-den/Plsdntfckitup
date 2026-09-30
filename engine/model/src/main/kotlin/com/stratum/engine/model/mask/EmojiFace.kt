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
 * The head is a glossy oval ([MaskSpiritMesher] with `emoji`); the face is
 * crisp line work floating in front of it, on its own plane, a hand's
 * breadth off the surface. As the head turns the line work slides across
 * the oval rather than wrapping round it, so it reads as drawn in the air
 * over the mask -- the way a spirit's face hovers over its mask -- and it
 * can move on its own: effects drift round the head in the same line.
 *
 * What makes it Igbo is what the lines draw: the maiden's almond lids,
 * Mbari's ringed stare, ichi bars, the uli cross and dots, cheek stripes,
 * in the mask's palette.
 */
object EmojiFace {

    /** How one eye looks. */
    enum class Eye {
        /** The mask's own: almond lids, crescents, rings, tubes. */
        OWN,
        /** A dark dot with a spark in it. */
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

    enum class Mouth { OWN, SMILE, GRIN, LAUGH, OH, FROWN, WOBBLE, TONGUE }

    enum class Brows { NONE, SOFT, ANGRY, SAD, UP }

    /** Line work that floats round the head with a feeling. */
    enum class Effect { NONE, SPARKLES, HEARTS, ANGER, MOTION, SWEAT, SLEEP, SHOCK, SWIRL }

    /** A feeling, as parts: each eye, the mouth, the brows, and what floats round it. */
    data class Expression(
        val left: Eye = Eye.OWN,
        val right: Eye = Eye.OWN,
        val mouth: Mouth = Mouth.OWN,
        val brows: Brows = Brows.NONE,
        val cheeks: Boolean = true,
        val tear: Boolean = false,
        val effect: Effect = Effect.NONE,
        /** Where open eyes look, -1..1 each way. */
        val lookX: Float = 0f,
        val lookY: Float = 0f,
    )

    /** The set every mask can make, by name: what the game plays and the creator offers. */
    val presets: Map<String, Expression> = linkedMapOf(
        "Calm" to Expression(),
        "Smile" to Expression(Eye.HAPPY, Eye.HAPPY, Mouth.SMILE),
        "Wink" to Expression(Eye.DOT, Eye.HAPPY, Mouth.SMILE, effect = Effect.SPARKLES),
        "Laugh" to Expression(Eye.HAPPY, Eye.HAPPY, Mouth.LAUGH, effect = Effect.MOTION),
        "Cheeky" to Expression(Eye.DOT, Eye.HAPPY, Mouth.TONGUE),
        "Love" to Expression(Eye.HEART, Eye.HEART, Mouth.SMILE, effect = Effect.HEARTS),
        "Star" to Expression(Eye.STAR, Eye.STAR, Mouth.GRIN, effect = Effect.SPARKLES),
        "Surprise" to Expression(Eye.RING, Eye.RING, Mouth.OH, Brows.UP, cheeks = false, effect = Effect.SHOCK),
        "Anger" to Expression(Eye.DOT, Eye.DOT, Mouth.FROWN, Brows.ANGRY, cheeks = false, effect = Effect.ANGER),
        "Shout" to Expression(Eye.PINCH, Eye.PINCH, Mouth.GRIN, Brows.ANGRY, cheeks = false, effect = Effect.MOTION),
        "Hurt" to Expression(Eye.PINCH, Eye.PINCH, Mouth.WOBBLE, Brows.SAD, effect = Effect.SWEAT),
        "Sad" to Expression(Eye.DOT, Eye.DOT, Mouth.FROWN, Brows.SAD, cheeks = false, tear = true),
        "Dizzy" to Expression(Eye.SPIRAL, Eye.SPIRAL, Mouth.WOBBLE, cheeks = false, effect = Effect.SWIRL),
        "Sleep" to Expression(Eye.LID, Eye.LID, Mouth.OWN, effect = Effect.SLEEP),
        "Blink" to Expression(Eye.LID, Eye.LID),
    )

    /** How far in front of the oval's nearest point the line work floats, in face units. */
    const val FLOAT = 0.16f

    /** [genome]'s face showing [e], floating in front of [face]. */
    fun build(genome: MaskGenome, face: SpiritFace, e: Expression): SpiritFeatures = Painter(genome.normalised(), face, e).paint()

    private class Painter(val g: MaskGenome, val face: SpiritFace, val e: Expression) {
        val pal = MaskPalettes[g.palette]
        val faceC = hex(pal.face); val second = hex(pal.second); val accent = hex(pal.accent); val crest = hex(pal.crest)
        val light = luma(faceC) > 0.45f
        /** The line: near-black on a light oval, kaolin on a near-black one. */
        val ink = when {
            light -> 0xFF231517.toInt()
            luma(faceC) < 0.2f -> 0xFFF4EEE2.toInt()
            else -> 0xFF1A0F10.toInt()
        }
        /** Marks: the palette's strongest contrast to the face. */
        val mark = listOf(hex(pal.ink), crest, second, accent).maxBy { abs(luma(it) - luma(faceC)) }
        val lip = if (hueRed(faceC)) 0xFFF0C8B8.toInt() else 0xFFC4212E.toInt()
        val blush = 0xFFF06E80.toInt()
        val gold = 0xFFF0B53A.toInt()
        val white = 0xFFFFFBF4.toInt()
        val w = face.plan[0]; val ey = face.plan[1] - 0.02f; val ex = face.plan[2] * 1.05f; val my = face.plan[3] + 0.06f
        val size = if (g.flower) 0.62f else 1.15f
        /** The line's weight: fine and even, like a pen, not a brush. */
        val pen = 0.034f * size
        /** The floating plane: a hand's breadth in front of the oval's crown of the face. */
        val plane = (face.height(0f, 0f).takeIf { it.isFinite() } ?: 0.3f) + FLOAT

        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val glw = ArrayList<Float>(); val idx = ArrayList<Int>()
        private var layer = 0

        fun paint(): SpiritFeatures {
            marks()
            if (e.cheeks) cheeks()
            brows()
            eye(-1f, e.left); eye(1f, e.right)
            if (g.shades) shades()
            if (e.tear) tear(ex * 1.05f, ey - 0.22f)
            mouth()
            effect()
            return SpiritFeatures(pos.toFloatArray(), nrm.toFloatArray(), col.toIntArray(), glw.toFloatArray(), idx.toIntArray())
        }

        // ---- the floating plane ------------------------------------------------

        /** Fills polygon [pts] (face units) on the plane, a hair in front of what is drawn before it. */
        fun fill(pts: FloatArray, color: Int, glow: Float = 0f) {
            val tris = Triangulate.polygon(pts)
            if (tris.isEmpty()) return
            val y = (plane + LAYER_STEP * layer++) * face.scale
            val first = pos.size / 3
            for (i in 0 until pts.size / 2) {
                pos += -pts[i * 2] * face.scale; pos += y; pos += (pts[i * 2 + 1] - face.midV) * face.scale
                nrm += 0f; nrm += 1f; nrm += 0f
                col += color; glw += glow
            }
            for (t in tris) idx += first + t
        }

        /** A pen line along [pts], round at the ends and joins. */
        fun line(pts: FloatArray, color: Int = ink, width: Float = pen, glow: Float = 0.25f, closed: Boolean = false) {
            val n = pts.size / 2
            if (n < 2) return
            val h = width / 2f
            val segs = if (closed) n else n - 1
            val start = layer
            for (i in 0 until segs) {
                val j = (i + 1) % n
                val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[j * 2]; val by = pts[j * 2 + 1]
                var dx = bx - ax; var dy = by - ay
                val l = sqrt(dx * dx + dy * dy)
                if (l < 1e-6f) continue
                dx = dx / l * h; dy = dy / l * h
                layer = start
                fill(floatArrayOf(ax - dy, ay + dx, ax + dy, ay - dx, bx + dy, by - dx, bx - dy, by + dx), color, glow)
            }
            for (i in 0 until n) { layer = start; fill(Shapes.ellipse(pts[i * 2], pts[i * 2 + 1], h, h, 8), color, glow) }
            layer = start + 1
        }

        fun circle(cx: Float, cy: Float, r: Float, segments: Int = 28): FloatArray = Shapes.ellipse(cx, cy, r, r, segments)

        // ---- eyes -------------------------------------------------------------

        fun eye(side: Float, look: Eye) {
            if (g.shades) return
            val cx = side * ex; val cy = ey
            val r = 0.11f * size
            val kind = if (look != Eye.OWN) look else when (g.eyes) {
                EyeForm.ALMOND -> Eye.OWN
                EyeForm.CRESCENT -> Eye.HAPPY
                EyeForm.ROUND -> Eye.RING
                EyeForm.TUBULAR -> Eye.RING
            }
            when (kind) {
                Eye.OWN -> {
                    // The maiden's almond: an outlined lid, its outer corner lifted, a pupil looking out of it.
                    line(almond(cx, cy, r * 1.35f, r * 0.62f, side), closed = true)
                    fill(circle(cx + e.lookX * 0.025f, cy - r * 0.05f + e.lookY * 0.02f, r * 0.38f, 20), ink, 0.3f)
                    fill(circle(cx - r * 0.12f + e.lookX * 0.025f, cy + r * 0.1f, r * 0.12f, 10), white, 0.6f)
                }
                Eye.DOT -> {
                    fill(Shapes.ellipse(cx + e.lookX * 0.025f, cy + e.lookY * 0.02f, r * 0.55f, r * 0.72f, 24), ink, 0.3f)
                    fill(circle(cx - r * 0.15f, cy + r * 0.25f, r * 0.17f, 12), white, 0.6f)
                }
                Eye.HAPPY -> line(Shapes.quad(cx - r * 1.2f, cy - r * 0.35f, cx, cy + r * 1.3f, cx + r * 1.2f, cy - r * 0.35f, 14), width = pen * 1.25f)
                Eye.LID -> {
                    line(Shapes.quad(cx - r * 1.25f, cy + r * 0.2f, cx, cy - r * 0.95f, cx + r * 1.25f, cy + r * 0.2f, 14), width = pen * 1.2f)
                    val ox = cx + side * r * 1.25f
                    line(floatArrayOf(ox, cy + r * 0.2f, ox + side * r * 0.35f, cy + r * 0.55f))
                    line(floatArrayOf(ox - side * r * 0.4f, cy - r * 0.2f, ox - side * r * 0.3f, cy - r * 0.6f))
                }
                Eye.PINCH -> {
                    val tip = cx - side * r * 0.55f
                    line(floatArrayOf(cx + side * r * 0.9f, cy + r * 0.8f, tip, cy, cx + side * r * 0.9f, cy - r * 0.8f), width = pen * 1.25f)
                }
                Eye.RING -> {
                    // Mbari's stare: rings within rings, the centre a dark pupil.
                    val rr = r * 1.3f
                    line(circle(cx, cy, rr, 36), closed = true)
                    line(circle(cx, cy, rr * 0.68f, 32), color = gold, closed = true)
                    fill(circle(cx + e.lookX * 0.03f, cy + e.lookY * 0.03f, rr * 0.36f, 20), ink, 0.3f)
                    fill(circle(cx - rr * 0.1f, cy + rr * 0.12f, rr * 0.1f, 10), white, 0.6f)
                }
                Eye.HEART -> line(heart(cx, cy, r * 1.2f), color = 0xFFE0303D.toInt(), width = pen * 1.3f, glow = 0.6f, closed = true)
                Eye.STAR -> line(star(cx, cy, r * 1.35f, r * 0.58f), color = gold, width = pen * 1.2f, glow = 0.9f, closed = true)
                Eye.SPIRAL -> {
                    val pts = ArrayList<Float>()
                    for (i in 0..48) {
                        val t = i / 48f; val a = t * 4.5f * PI.toFloat() * side; val rad = 0.012f + r * 1.2f * t
                        pts += cx + cos(a) * rad; pts += cy + sin(a) * rad
                    }
                    line(pts.toFloatArray())
                }
            }
        }

        fun almond(cx: Float, cy: Float, half: Float, h: Float, side: Float): FloatArray {
            val lift = h * 0.4f
            val ix = cx - side * half; val ox = cx + side * half
            val upper = Shapes.quad(ix, cy, cx, cy + h * 1.7f, ox, cy + lift, 14)
            val lower = Shapes.quad(ox, cy + lift, cx, cy - h * 1.2f, ix, cy, 14)
            return upper + lower.copyOfRange(2, lower.size - 2)
        }

        fun heart(cx: Float, cy: Float, r: Float): FloatArray {
            val out = ArrayList<Float>()
            for (i in 0 until 44) {
                val t = i / 44f * 2f * PI.toFloat()
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
                val cx = s * ex; val cy = ey + 0.24f * size
                val half = 0.12f * size
                val (inner, outer) = when (e.brows) {
                    Brows.ANGRY -> -0.07f to 0.04f
                    Brows.SAD -> 0.06f to -0.04f
                    Brows.UP -> 0.05f to 0.05f
                    else -> 0f to 0f
                }
                line(Shapes.quad(cx - s * half, cy + inner, cx, cy + 0.03f + (inner + outer) / 2f, cx + s * half, cy + outer, 10), width = pen * 1.2f)
            }
        }

        /** Blush as three short strokes, the way a pen draws it. */
        fun cheeks() {
            for (s in listOf(-1f, 1f)) for (i in 0 until 3) {
                val x = s * (ex * 1.15f) + (i - 1) * 0.045f
                val y = ey - 0.24f
                line(floatArrayOf(x + 0.02f, y + 0.03f, x - 0.02f, y - 0.03f), color = blush, width = pen * 0.85f, glow = 0.4f)
            }
        }

        fun shades() {
            val r = 0.16f * size
            for (s in listOf(-1f, 1f)) {
                val cx = s * ex
                val lens = Shapes.roundRect(cx - r * 1.15f, ey - r * 0.8f, cx + r * 1.15f, ey + r * 0.75f, r * 0.45f)
                fill(lens, 0xFF18161C.toInt())
                line(floatArrayOf(cx - r * 0.7f, ey + r * 0.35f, cx - r * 0.95f, ey - r * 0.1f), color = white, width = pen * 0.7f, glow = 0.5f)
            }
            line(floatArrayOf(-ex + r * 1.1f, ey + r * 0.35f, 0f, ey + r * 0.5f, ex - r * 1.1f, ey + r * 0.35f), color = 0xFF18161C.toInt(), width = pen * 1.2f)
        }

        fun tear(x: Float, y: Float) {
            val drop = ArrayList<Float>()
            for (i in 0 until 26) {
                val t = i / 26f * 2f * PI.toFloat()
                val px = sin(t) * 0.042f * (1f - 0.55f * max(0f, cos(t)))
                val py = -cos(t) * 0.058f + if (cos(t) > 0.6f) (cos(t) - 0.6f) * 0.22f else 0f
                drop += x + px; drop += y + py
            }
            line(drop.toFloatArray(), color = 0xFF4FA8E0.toInt(), glow = 0.5f, closed = true)
        }

        // ---- mouth -------------------------------------------------------------

        fun mouth() {
            val kind = if (e.mouth != Mouth.OWN) e.mouth else when (g.mouth) {
                MouthForm.OPEN_TEETH -> Mouth.GRIN
                else -> Mouth.OWN
            }
            val y = my
            val half = 0.15f * size
            when (kind) {
                Mouth.OWN -> if (g.mouth == MouthForm.PURSED) line(circle(0f, y, 0.045f * size, 20), color = lip, closed = true) else lips(y, half)
                Mouth.SMILE -> line(Shapes.quad(-half, y + 0.03f, 0f, y - 0.11f, half, y + 0.03f, 14), width = pen * 1.2f)
                Mouth.FROWN -> line(Shapes.quad(-half * 0.8f, y - 0.04f, 0f, y + 0.07f, half * 0.8f, y - 0.04f, 14), width = pen * 1.2f)
                Mouth.WOBBLE -> {
                    val pts = FloatArray(30)
                    for (i in 0 until 15) { val t = i / 14f; pts[i * 2] = -half + 2 * half * t; pts[i * 2 + 1] = y + sin(t * 3f * PI.toFloat()) * 0.028f }
                    line(pts, width = pen * 1.1f)
                }
                Mouth.OH -> line(Shapes.ellipse(0f, y - 0.02f, 0.06f * size, 0.08f * size, 28), closed = true, width = pen * 1.2f)
                Mouth.GRIN, Mouth.LAUGH, Mouth.TONGUE -> {
                    val depth = (if (kind == Mouth.LAUGH) 0.19f else 0.13f) * size
                    val top = y + 0.035f
                    val shape = open(half * 1.05f, top, top - depth)
                    fill(shape, 0xFF4A1016.toInt(), 0.2f)
                    line(shape, closed = true, width = pen * 1.1f)
                    if (kind == Mouth.GRIN) line(floatArrayOf(-half * 0.85f, top - depth * 0.35f, half * 0.85f, top - depth * 0.35f), color = white, width = pen * 0.8f, glow = 0.4f)
                    else line(Shapes.quad(-half * 0.45f, top - depth * 0.72f, 0f, top - depth * 0.45f, half * 0.45f, top - depth * 0.72f, 8), color = 0xFFF08A95.toInt(), width = pen * 1.4f, glow = 0.4f)
                    if (kind == Mouth.TONGUE) line(Shapes.quad(-half * 0.25f, top - depth, 0f, top - depth - 0.1f, half * 0.25f, top - depth, 8), color = 0xFFF08A95.toInt(), width = pen * 1.6f, glow = 0.4f)
                }
            }
        }

        fun open(half: Float, top: Float, bottom: Float): FloatArray {
            val out = ArrayList<Float>()
            val upper = Shapes.quad(-half, top, 0f, top - 0.02f, half, top, 8)
            for (v in upper) out += v
            val lower = Shapes.quad(half, top, half * 0.9f, bottom - (top - bottom) * 0.35f, -half, top, 16)
            for (i in 2 until lower.size - 2) out += lower[i]
            return out.toFloatArray()
        }

        /** Lips in line: the bow of the upper lip and the curve of the lower, in camwood red. */
        fun lips(y: Float, half: Float) {
            val hw = half * 0.75f
            line(floatArrayOf(-hw, y, -hw * 0.5f, y + 0.045f, 0f, y + 0.022f, hw * 0.5f, y + 0.045f, hw, y), color = lip, width = pen * 1.1f, glow = 0.35f)
            line(Shapes.quad(-hw, y, 0f, y - 0.1f, hw, y, 12), color = lip, width = pen * 1.1f, glow = 0.35f)
            line(floatArrayOf(-hw * 0.9f, y, hw * 0.9f, y), color = lip, width = pen * 0.8f, glow = 0.35f)
        }

        // ---- Igbo marks ---------------------------------------------------------

        fun marks() {
            val sides = if (g.symmetric) listOf(-1f, 1f) else listOf(1f)
            val brow = if (g.hair != HairStyle.NONE || g.hat) ey + 0.3f else ey + 0.42f
            if (g.ichi > 0) {
                val n = g.ichi.coerceAtMost(4)
                for (i in 0 until n) {
                    val x = (i - (n - 1) / 2f) * 0.07f
                    line(floatArrayOf(x, brow + 0.08f, x, brow - 0.06f), color = mark)
                }
            } else if (g.ornament > 0.45f) {
                val r = 0.065f
                line(floatArrayOf(-r, brow + r, r, brow - r), color = mark)
                line(floatArrayOf(-r, brow - r, r, brow + r), color = mark)
            }
            for (s in sides) for (i in 0 until g.cheekMarks) {
                val y = ey - 0.2f - i * 0.06f
                line(floatArrayOf(s * (ex + 0.08f), y, s * (ex + 0.24f), y + 0.012f), color = mark)
            }
            val dots = (g.ornament * 7).toInt()
            if (dots > 1 && g.ichi == 0) for (i in 0 until dots) {
                val t = i / (dots - 1f)
                if (!g.symmetric && t < 0.5f) continue
                val x = (t * 2f - 1f) * 0.24f
                val y = brow + 0.16f - 0.05f * (1f - (2f * t - 1f).let { it * it })
                fill(circle(x, y, 0.022f, 10), if (i % 2 == 0) gold else mark, 0.4f)
            }
        }

        // ---- effects: line work floating round the head --------------------------

        fun effect() {
            val top = 1.05f; val side = w * 1.25f
            when (e.effect) {
                Effect.NONE -> Unit
                Effect.SPARKLES -> for ((x, y, r) in listOf(Triple(side, 0.62f, 0.09f), Triple(-side * 0.95f, 0.85f, 0.06f), Triple(side * 0.8f, 1.02f, 0.05f))) sparkle(x, y, r)
                Effect.HEARTS -> for ((x, y, r) in listOf(Triple(side, 0.7f, 0.1f), Triple(side * 0.72f, 1.05f, 0.065f), Triple(-side, 0.9f, 0.07f)))
                    line(heart(x, y, r), color = 0xFFE0303D.toInt(), glow = 0.6f, closed = true)
                Effect.ANGER -> {
                    // The comic vein: four hooked strokes at the temple.
                    val cx = side * 0.85f; val cy = 0.82f; val r = 0.1f
                    for (q in 0 until 4) {
                        val a = q * PI.toFloat() / 2f + PI.toFloat() / 4f
                        val px = cx + cos(a) * r * 0.55f; val py = cy + sin(a) * r * 0.55f
                        line(floatArrayOf(px + cos(a + 0.9f) * r * 0.5f, py + sin(a + 0.9f) * r * 0.5f, px, py, px + cos(a - 0.9f) * r * 0.5f, py + sin(a - 0.9f) * r * 0.5f), color = 0xFFD8263A.toInt(), width = pen * 1.3f, glow = 0.7f)
                    }
                }
                Effect.MOTION -> for (s in listOf(-1f, 1f)) for (i in 0 until 3) {
                    val r = side + 0.08f + i * 0.09f
                    val a0 = 0.35f; val a1 = 0.9f
                    val pts = FloatArray(20)
                    for (k in 0 until 10) { val a = a0 + (a1 - a0) * k / 9f; pts[k * 2] = s * cos(a) * r; pts[k * 2 + 1] = 0.05f + sin(a) * r * 0.8f - 0.3f }
                    line(pts, color = ink, width = pen * (1f - 0.2f * i))
                }
                Effect.SWEAT -> tear(side * 0.9f, 0.75f)
                Effect.SLEEP -> for ((i, z) in listOf(0.1f, 0.075f, 0.055f).withIndex()) {
                    val x = side * 0.8f + i * 0.14f; val y = 0.75f + i * 0.2f
                    line(floatArrayOf(x - z, y + z, x + z, y + z, x - z, y - z, x + z, y - z), color = ink)
                }
                Effect.SHOCK -> for (k in 0 until 5) {
                    val a = PI.toFloat() * (0.2f + 0.15f * k)
                    line(floatArrayOf(cos(a) * (side + 0.05f), top * 0.5f + sin(a) * 0.9f, cos(a) * (side + 0.2f), top * 0.5f + sin(a) * 1.05f), color = ink, width = pen * 1.2f)
                }
                Effect.SWIRL -> for (k in 0 until 3) sparkle(cos(k * 2.1f) * side * 0.9f, top + sin(k * 2.1f) * 0.12f, 0.06f)
            }
        }

        /** A four-point sparkle, drawn in two strokes and a dot. */
        fun sparkle(x: Float, y: Float, r: Float) {
            line(Shapes.quad(x, y + r, x + r * 0.15f, y + r * 0.15f, x + r, y, 6) + Shapes.quad(x + r, y, x + r * 0.15f, y - r * 0.15f, x, y - r, 6) +
                Shapes.quad(x, y - r, x - r * 0.15f, y - r * 0.15f, x - r, y, 6) + Shapes.quad(x - r, y, x - r * 0.15f, y + r * 0.15f, x, y + r, 6), color = gold, glow = 0.9f, closed = true)
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

    /** How far each drawn layer sits in front of the last, so overlapping lines never fight. */
    private const val LAYER_STEP = 0.004f

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
