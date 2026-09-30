package com.stratum.engine.model.mask

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mask as smooth cartoon art: flat bold colour, a clean dark outline, a
 * soft sheen, glowing eyes -- drawn from its [MaskGenome] at any turn.
 *
 * ## How it turns
 *
 * The art is designed front-on, but every feature is laid on a rounded head
 * rather than on the picture: a point [x] across the face at height [y] sits
 * at an angle round the head, and turning the head by [angle] slides it
 * along the curve (`x = halfWidth(y) sin(u + angle)`), foreshortening it as
 * it goes and fading it out as it rounds the edge. So eyes and marks glide
 * across the face and disappear round the side; the far ear and horn swing
 * behind the head; a crest's combs fan past one another; the halo narrows to
 * an edge; and from behind the mask shows its reverse -- raffia on a carved
 * board -- with the painted front a crescent at its rim. Like a paper
 * cut-out that turns, which is what cartoon characters do.
 *
 * Everything is drawn in logical units ([LEFT], [BOTTOM], [WIDE], [HIGH]);
 * the face is about 1.2 wide and 1.5 tall, with room above for a tall crest
 * and at the sides for ears and horns.
 */
object MaskArt {
    const val LEFT = -1.3f
    const val BOTTOM = -1.4f
    const val WIDE = 2.6f
    const val HIGH = 3.25f

    /** Height over width of a frame. */
    const val ASPECT = HIGH / WIDE

    /** One drawn turn of a mask, and where its eyes are in it (0..1 across and down, NaN when they are out of sight). */
    class Frame(val width: Int, val height: Int, val argb: IntArray, val eyes: FloatArray)

    /**
     * [genome] drawn [angle] radians turned (0 facing out of the picture,
     * positive turning its face to the picture's right), [width] pixels wide.
     */
    fun draw(genome: MaskGenome, angle: Float, width: Int): Frame {
        val height = (width * ASPECT).toInt()
        val c = VectorCanvas(width, height, LEFT, BOTTOM, WIDE, HIGH)
        val eyes = Painter(genome.normalised(), wrap(angle), c).paint()
        return Frame(width, height, c.pixels(), eyes)
    }

    /** [frames] turns evenly round the full circle, frame 0 facing out. */
    fun turns(genome: MaskGenome, frames: Int, width: Int): List<Frame> =
        List(frames) { i -> draw(genome, (i * 2.0 * PI / frames).toFloat(), width) }

    /** How far round the head, each way from the front, the face is painted: past a quarter turn, so a profile is still a face. */
    private const val WRAP = 1.95f
    private const val HALF_PI = (PI / 2).toFloat()

    private fun wrap(a: Float): Float {
        var x = a % (2f * PI.toFloat())
        if (x > PI) x -= 2f * PI.toFloat()
        if (x < -PI) x += 2f * PI.toFloat()
        return x
    }

    /** One drawing: the genome's shapes and colours, projected through the turn. */
    private class Painter(val g: MaskGenome, val a: Float, val c: VectorCanvas) {
        val pal = MaskPalettes[g.palette]
        val face = hex(pal.face); val ink = hex(pal.ink); val crest = hex(pal.crest); val second = hex(pal.second)
        val accent = hex(pal.accent); val ivory = hex(pal.ivory)
        /** The reverse: raffia in the crest's colour, warm enough to read as part of the character, not a hole. */
        val back = lighten(mix(crest, hex(pal.back), 0.35f), 0.12f)
        /** The outline: near-black plum, or the palette's own ink when that is darker. */
        val line = if (luma(ink) < 0.18f) darken(ink, 0.35f) else 0xFF1E1624.toInt()
        val glow = mix(0xFFFFF0B0.toInt(), accent, 0.3f)
        val lineW = 0.075f
        val ca = cos(a); val sa = sin(a)
        /** Heads narrow a little seen side-on; less than a sphere would, so the silhouette holds. */
        val k = 0.8f + 0.2f * abs(ca)

        val halfW = 0.47f + 0.2f * g.width * when (g.face) { FaceShape.ROUND -> 1.15f; FaceShape.LONG -> 0.8f; else -> 1f }
        val top = 0.74f
        val bottom = when (g.face) {
            FaceShape.OVAL -> -0.8f; FaceShape.LONG -> -0.98f; FaceShape.ROUND -> -0.62f; FaceShape.HEART -> -0.9f; FaceShape.SQUARE_JAW -> -0.76f
        }
        val mid = (top + bottom) / 2f
        val halfH = (top - bottom) / 2f
        /** Where the features sit: the genome's dial raises or lowers the whole face's worth. */
        val lift = (g.features - 0.5f) * 0.22f

        /** Half the face's width at height [y], front-on. */
        fun hw(y: Float): Float {
            val t = ((y - mid) / halfH).coerceIn(-1f, 1f)
            val round = sqrt(max(0f, 1f - t * t))
            val w = when (g.face) {
                FaceShape.SQUARE_JAW -> if (t < 0f) (1f - abs(t).pow(4)).pow(0.25f) else round
                FaceShape.HEART -> if (t < 0f) (1f + t).pow(0.75f) * (0.92f + 0.08f * (1f + t)) else round * (1f + 0.08f * (1f - t))
                else -> round
            }
            return halfW * w
        }

        /** Screen x of the face point front-on at ([x], [y]), and how squarely it faces out (1) or away (<0). */
        fun project(x: Float, y: Float, out: FloatArray) {
            val h = hw(y).coerceAtLeast(1e-3f)
            val u = asin((x / h).coerceIn(-1f, 1f))
            out[0] = h * k * sin(u + a); out[1] = cos(u + a)
        }
        private val tmp = FloatArray(2)

        /** A front-on shape carried onto the turned head. */
        fun onFace(points: FloatArray): FloatArray {
            val out = FloatArray(points.size)
            for (i in 0 until points.size / 2) {
                project(points[i * 2], points[i * 2 + 1], tmp)
                out[i * 2] = tmp[0]; out[i * 2 + 1] = points[i * 2 + 1]
            }
            return out
        }

        /**
         * A shape hung from the face at ([ax], [ay]) -- a beard, a tusk --
         * carried whole: it moves with its anchor and narrows as it turns,
         * rather than bending round a face it hangs below.
         */
        fun hung(points: FloatArray, ax: Float, ay: Float): FloatArray {
            project(ax, ay, tmp)
            val x0 = tmp[0]; val sq = max(0.3f, abs(tmp[1]))
            return FloatArray(points.size) { i -> if (i % 2 == 0) x0 + (points[i] - ax) * sq else points[i] }
        }

        /** How visible a face feature centred at ([x], [y]) is: fades out as it rounds the edge. */
        fun seen(x: Float, y: Float): Float {
            project(x, y, tmp)
            return smooth(0.08f, 0.38f, tmp[1])
        }

        /** Where on the head's rim, at height [y], the angle [u] round from the front lands: screen x and depth. */
        fun rim(u: Float, y: Float, out: FloatArray) {
            val h = hw(y)
            out[0] = h * k * sin(u + a); out[1] = cos(u + a)
        }

        /** Things in front of or behind the head, painted in depth order. */
        private class Layer(val depth: Float, val draw: () -> Unit)

        fun paint(): FloatArray {
            val behind = ArrayList<Layer>(); val front = ArrayList<Layer>()
            fun place(depth: Float, draw: () -> Unit) { (if (depth < 0f) behind else front) += Layer(depth, draw) }
            halo()?.let { behind += Layer(-2f, it) }
            ears(::place)
            crest(::place)
            behind.sortBy { it.depth }; front.sortBy { it.depth }
            behind.forEach { it.draw() }
            head()
            val eyes = features()
            beard()
            front.forEach { it.draw() }
            sheen()
            return eyes
        }

        // ---- the head -------------------------------------------------------

        fun silhouette(): FloatArray {
            val n = 48
            val out = FloatArray(n * 4)
            for (i in 0 until n) {
                val y = bottom + (top - bottom) * i / (n - 1f)
                out[i * 2] = hw(y) * k; out[i * 2 + 1] = y
                val j = 2 * n - 1 - i
                out[j * 2] = -hw(y) * k; out[j * 2 + 1] = y
            }
            return out
        }

        fun head() {
            val s = silhouette()
            c.stroke(s, lineW * 2f, line, closed = true)
            c.fill(s, back)
            // The raffia on the back: strands round the back of the head, sliding as it turns.
            if (abs(a) + WRAP > HALF_PI) {
                for (i in 0 until 11) {
                    val u = (WRAP + (i + 0.5) * (2 * PI - 2 * WRAP) / 11).toFloat()
                    rim(u, mid, tmp)
                    if (tmp[1] < 0.05f) continue
                    val pts = FloatArray(24)
                    for (j in 0 until 12) {
                        val y = bottom + 0.06f + (top - bottom - 0.12f) * j / 11f
                        rim(u, y, tmp); pts[j * 2] = tmp[0]; pts[j * 2 + 1] = y
                    }
                    c.stroke(pts, 0.05f, if (i % 3 == 1) second else lighten(back, 0.22f), opacity = smooth(0.05f, 0.4f, tmp[1]))
                }
                // The ties that hold the mask on, knotted at the back of the head.
                rim(PI.toFloat(), mid + 0.1f, tmp)
                if (tmp[1] > 0.1f) {
                    val kx = tmp[0]; val ky = mid + 0.1f; val v = smooth(0.1f, 0.5f, tmp[1])
                    for (sx in listOf(-1f, 1f)) {
                        val bow = Shapes.ellipse(kx + sx * 0.14f * tmp[1], ky + 0.02f, 0.14f * tmp[1] + 0.02f, 0.08f)
                        c.stroke(bow, lineW * 2f, line, closed = true, opacity = v); c.fill(bow, accent, v)
                        val tail = Shapes.taper(kx, ky, kx + sx * 0.12f, ky - 0.2f, kx + sx * 0.18f, ky - 0.42f, 0.09f, 0.04f)
                        c.stroke(tail, lineW * 2f, line, closed = true, opacity = v); c.fill(tail, accent, v)
                    }
                    val knot = Shapes.ellipse(kx, ky, 0.06f, 0.06f)
                    c.stroke(knot, lineW * 2f, line, closed = true, opacity = v); c.fill(knot, lighten(accent, 0.2f), v)
                }
            }
            // The painted front: the part of the face turned out, bounded by where it rounds away.
            val front = frontRegion() ?: return
            c.fill(front, VectorCanvas.Paint { x, y ->
                // Lit from above and a little left; darker toward the rim as the face turns away.
                val h = hw(y).coerceAtLeast(1e-3f) * k
                val depth = sqrt(max(0f, 1f - (x / h).coerceIn(-1f, 1f).let { it * it }))
                val vertical = 1.08f - 0.16f * ((y - bottom) / (top - bottom)).let { 1f - it }
                shade(face, vertical * (0.8f + 0.2f * depth))
            })
            // The terminator: where the paint stops and the board begins, drawn as a line when the face is turned well away.
            if (abs(sa) > 0.15f) {
                val edge = FloatArray(48 * 2)
                for (i in 0 until 48) {
                    val y = bottom + (top - bottom) * i / 47f
                    val h = hw(y) * k
                    edge[i * 2] = if (a >= 0f) h * sin(max(-HALF_PI, a - WRAP)) else h * sin(min(HALF_PI, a + WRAP))
                    edge[i * 2 + 1] = y
                }
                if (abs(a) + WRAP > HALF_PI + 0.05f) c.stroke(edge, 0.05f, line)
            }
        }

        /** The face's painted region at this turn; null when it is out of sight. */
        fun frontRegion(): FloatArray? {
            if (a - WRAP > HALF_PI || a + WRAP < -HALF_PI) return null
            val n = 48
            val out = FloatArray(n * 4)
            for (i in 0 until n) {
                val y = bottom + (top - bottom) * i / (n - 1f)
                val h = hw(y) * k
                val lo = h * sin(max(-HALF_PI, a - WRAP))
                val hi = h * sin(min(HALF_PI, a + WRAP))
                out[i * 2] = hi; out[i * 2 + 1] = y
                val j = 2 * n - 1 - i
                out[j * 2] = lo; out[j * 2 + 1] = y
            }
            return out
        }

        /** A soft gloss high on the left, fixed to the light, not the face. */
        fun sheen() {
            val s = frontRegion() ?: silhouette()
            val hx = -halfW * 0.38f; val hy = top - 0.34f
            c.fill(Shapes.ellipse(hx, hy, halfW * 0.34f, 0.2f), VectorCanvas.Paint { x, y ->
                val inside = insidePolygon(s, x, y)
                if (inside) 0x55FFFFFF else 0
            })
            c.fill(Shapes.ellipse(hx - 0.07f, hy + 0.06f, 0.06f, 0.04f), VectorCanvas.Paint { x, y -> if (insidePolygon(s, x, y)) 0xB0FFFFFF.toInt() else 0 })
        }

        // ---- the face -------------------------------------------------------

        /** Paints eyes, brows, nose, mouth and marks; returns the eyes' positions for the glow. */
        fun features(): FloatArray {
            val eyes = FloatArray(4) { Float.NaN }
            val ey = 0.06f + lift
            val ex = hw(ey) * 0.45f
            for ((side, sx) in listOf(0 to -1f, 1 to 1f)) {
                val cx = sx * ex
                val v = seen(cx, ey)
                if (v <= 0.01f) continue
                eye(cx, ey, sx, v)
                brow(cx, ey + 0.25f + g.brow * 0.03f, sx, v)
                project(cx, ey, tmp)
                if (v > 0.3f) { eyes[side * 2] = (tmp[0] - LEFT) / WIDE; eyes[side * 2 + 1] = (BOTTOM + HIGH - ey) / HIGH }
            }
            nose(ey)
            mouth()
            marks(ey)
            return eyes
        }

        fun eye(cx: Float, cy: Float, sx: Float, v: Float) {
            val w = 0.25f + 0.07f * g.width; val h = 0.17f
            when (g.eyes) {
                EyeForm.ALMOND -> {
                    val lid = almond(cx, cy, w, h, sx)
                    c.fill(onFace(lid), line, v)
                    c.fill(onFace(almond(cx, cy - 0.005f, w * 0.72f, h * 0.55f, sx)), glow, v)
                }
                EyeForm.CRESCENT -> {
                    val arc = Shapes.quad(cx - w, cy + 0.02f, cx, cy - h * 1.4f, cx + w, cy + 0.02f)
                    c.stroke(onFace(arc), 0.1f, line, opacity = v)
                    c.stroke(onFace(Shapes.quad(cx - w * 0.6f, cy - 0.02f, cx, cy - h * 0.8f, cx + w * 0.6f, cy - 0.02f)), 0.025f, glow, opacity = v * 0.9f)
                }
                EyeForm.ROUND -> {
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.2f, 0.2f)), line, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.15f, 0.15f)), second, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.095f, 0.095f)), line, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.065f, 0.065f)), glow, v)
                }
                EyeForm.TUBULAR -> {
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.21f, 0.19f)), line, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy, 0.16f, 0.145f)), second, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy - 0.01f, 0.1f, 0.09f)), line, v)
                    c.fill(onFace(Shapes.ellipse(cx, cy - 0.01f, 0.065f, 0.058f)), glow, v)
                }
            }
        }

        /** An almond eye, its outer corner lifted. */
        fun almond(cx: Float, cy: Float, w: Float, h: Float, sx: Float): FloatArray {
            val lift = h * 0.35f
            val ix = cx - sx * w; val ox = cx + sx * w
            val upper = Shapes.quad(ix, cy, cx, cy + h * 1.6f, ox, cy + lift)
            val lower = Shapes.quad(ox, cy + lift, cx, cy - h * 1.2f, ix, cy)
            return upper + lower.copyOfRange(2, lower.size - 2)
        }

        fun brow(cx: Float, cy: Float, sx: Float, v: Float) {
            val w = 0.22f
            val thick = 0.045f + 0.08f * g.brow
            // Heavy brows frown inward, light ones arch.
            val inner = if (g.brow > 0.6f) -0.06f else 0.0f
            val arc = Shapes.quad(cx - sx * w, cy + inner, cx, cy + 0.07f - g.brow * 0.03f, cx + sx * w, cy - 0.02f)
            c.stroke(onFace(arc), thick, line, opacity = v)
        }

        fun nose(ey: Float) {
            val v = seen(0f, ey - 0.2f)
            if (v <= 0.01f) return
            val tone = darken(face, 0.14f)
            val shape = when (g.nose) {
                NoseForm.LONG_STRAIGHT -> Shapes.roundRect(-0.045f, ey - 0.34f, 0.045f, ey - 0.05f, 0.04f)
                NoseForm.BROAD -> Shapes.ellipse(0f, ey - 0.28f, 0.12f, 0.08f)
                NoseForm.ARCHED -> floatArrayOf(0f, ey - 0.06f, 0.08f, ey - 0.32f, -0.08f, ey - 0.32f)
            }
            c.fill(onFace(shape), tone, v * 0.9f)
        }

        fun mouth() {
            val my = -0.44f + lift * 0.6f
            val v = seen(0f, my)
            if (v <= 0.01f) return
            when (g.mouth) {
                MouthForm.CLOSED_SMILE -> {
                    c.stroke(onFace(Shapes.quad(-0.2f, my + 0.04f, 0f, my - 0.13f, 0.2f, my + 0.04f)), 0.08f, line, opacity = v)
                    // Rosy cheeks: the one thing every cheerful face has.
                    for (sx in listOf(-1f, 1f)) {
                        val cx = sx * hw(my + 0.15f) * 0.62f
                        c.fill(onFace(Shapes.ellipse(cx, my + 0.15f, 0.13f, 0.08f)), second, seen(cx, my + 0.15f) * 0.5f)
                    }
                }
                MouthForm.OPEN_TEETH -> {
                    val box = Shapes.roundRect(-0.22f, my - 0.1f, 0.22f, my + 0.08f, 0.08f)
                    c.fill(onFace(box), line, v)
                    c.fill(onFace(Shapes.roundRect(-0.17f, my - 0.02f, 0.17f, my + 0.05f, 0.03f)), ivory, v)
                    for (i in -2..2) c.stroke(onFace(floatArrayOf(i * 0.065f, my - 0.02f, i * 0.065f, my + 0.05f)), 0.018f, line, opacity = v)
                }
                MouthForm.PURSED -> {
                    c.fill(onFace(Shapes.ellipse(0f, my, 0.1f, 0.07f)), line, v)
                    c.fill(onFace(Shapes.ellipse(0f, my + 0.005f, 0.07f, 0.045f)), second, v)
                }
            }
            if (g.tusks) {
                for (sx in listOf(-1f, 1f)) {
                    val bx = sx * 0.2f
                    val tv = seen(bx, my)
                    if (tv <= 0.01f) continue
                    val tusk = Shapes.taper(bx, my, bx + sx * 0.28f, my - 0.2f, bx + sx * 0.14f, my - 0.52f, 0.1f, 0.01f)
                    val placed = hung(tusk, bx, my)
                    c.stroke(placed, lineW * 2f, line, closed = true, opacity = tv)
                    c.fill(placed, ivory, tv)
                }
            }
        }

        /** Ichi cuts on the brow, cheek marks, and uli dots: the drawn-on ornament. */
        fun marks(ey: Float) {
            val sides = if (g.symmetric) listOf(-1f, 1f) else listOf(-1f)
            for (i in 0 until g.ichi) {
                val y = top - 0.14f - i * 0.06f
                val w = hw(y) * 0.45f
                val v = seen(0f, y)
                if (v > 0.01f) c.stroke(onFace(floatArrayOf(-w, y, w, y)), 0.028f, line, opacity = v * 0.85f)
            }
            for (sx in sides) for (i in 0 until g.cheekMarks) {
                val x = sx * (hw(ey - 0.2f) * 0.6f) + sx * i * 0.055f
                val y = ey - 0.2f
                val v = seen(x, y)
                if (v > 0.01f) c.stroke(onFace(floatArrayOf(x - sx * 0.03f, y + 0.07f, x + sx * 0.03f, y - 0.07f)), 0.03f, accent, opacity = v)
            }
            // Uli: a row of dots over the brow, and a chevron on the chin, as the ornament dial asks.
            val dots = (g.ornament * 9).toInt()
            for (i in 0 until dots) {
                val t = if (dots == 1) 0.5f else i / (dots - 1f)
                if (!g.symmetric && t > 0.5f) continue
                val y = top - 0.07f - 0.05f * sin(t * PI.toFloat())
                val x = (t * 2f - 1f) * hw(y) * 0.7f
                val v = seen(x, y)
                if (v > 0.01f) c.fill(onFace(Shapes.ellipse(x, y, 0.028f, 0.028f)), if (i % 2 == 0) accent else line, v)
            }
            if (g.ornament > 0.55f) {
                val y = bottom + 0.2f
                val v = seen(0f, y)
                if (v > 0.01f) c.stroke(onFace(floatArrayOf(-0.13f, y + 0.07f, 0f, y - 0.02f, 0.13f, y + 0.07f)), 0.035f, accent, opacity = v)
            }
        }

        fun beard() {
            if (g.beard == BeardForm.NONE) return
            val y = bottom + 0.06f
            val v = seen(0f, y)
            if (v <= 0.01f) return
            val w = max(0.22f, hw(bottom + 0.2f) * 0.8f)
            val shape = when (g.beard) {
                BeardForm.STRIPED -> Shapes.roundRect(-w, y - 0.5f, w, y + 0.08f, 0.14f)
                else -> Shapes.taper(0f, y + 0.04f, 0f, y - 0.28f, 0f, y - 0.58f, 0.52f, 0.1f)
            }
            val placed = hung(shape, 0f, y)
            c.stroke(placed, lineW * 2f, line, closed = true, opacity = v)
            c.fill(placed, crest, v)
            if (g.beard == BeardForm.STRIPED) for (i in 1..3) {
                val sy = y - i * 0.13f
                c.stroke(hung(floatArrayOf(-w * 0.9f, sy, w * 0.9f, sy), 0f, y), 0.045f, second, opacity = v)
            }
        }

        // ---- what rises and spreads around it ------------------------------

        /** The sun disc behind the head, narrowing to an edge as the face turns. */
        fun halo(): (() -> Unit)? {
            if (g.crest != CrestForm.DISC) return null
            return {
                val r = halfW * (1.45f + 0.35f * g.crestHeight)
                val sq = max(0.08f, abs(ca))
                val cy = mid + 0.12f
                val cx = sa * 0.06f
                val outer = Shapes.ellipse(cx, cy, r * sq, r, 64)
                c.stroke(outer, lineW * 2f, line, closed = true)
                c.fill(outer, crest)
                c.fill(Shapes.ellipse(cx, cy, r * 0.84f * sq, r * 0.84f, 64), accent)
                c.fill(Shapes.ellipse(cx, cy, r * 0.7f * sq, r * 0.7f, 64), second)
                for (i in 0 until 16) {
                    val t = i * 2.0 * PI / 16
                    c.fill(Shapes.ellipse(cx + (r * 0.92f * cos(t)).toFloat() * sq, cy + (r * 0.92f * sin(t)).toFloat(), 0.04f * sq + 0.01f, 0.04f), line)
                }
            }
        }

        fun ears(place: (Float, () -> Unit) -> Unit) {
            if (g.ears == EarForm.NONE) return
            val y = 0.02f + lift * 0.5f
            for (side in listOf(-1f, 1f)) {
                val u = side * (PI / 2).toFloat()
                rim(u, y, tmp)
                val depth = tmp[1]
                val big = g.ears == EarForm.ELEPHANT
                val rx = if (big) 0.42f else 0.12f; val ry = if (big) 0.6f else 0.17f
                // The ear sits out from the rim, its flat side toward the front.
                val out = sin(u + a)
                val cx = (hw(y) * k + rx * 0.72f) * out
                val sq = max(0.22f, abs(ca))
                place(depth) {
                    val e = Shapes.ellipse(cx, y, rx * sq, ry, 48)
                    c.stroke(e, lineW * 2f, line, closed = true)
                    c.fill(e, if (depth >= 0f) crest else darken(crest, 0.2f))
                    if (big) c.fill(Shapes.ellipse(cx + out * rx * 0.1f * sq, y, rx * 0.66f * sq, ry * 0.72f, 48), if (depth >= 0f) second else darken(second, 0.25f))
                }
            }
        }

        fun crest(place: (Float, () -> Unit) -> Unit) {
            val h = 0.35f + 0.8f * g.crestHeight
            when (g.crest) {
                CrestForm.NONE, CrestForm.DISC -> Unit
                CrestForm.COMBS, CrestForm.PLUMES -> {
                    val n = g.crestCount.coerceIn(1, 7)
                    for (i in 0 until n) {
                        val t = if (n == 1) 0f else i / (n - 1f) * 2f - 1f
                        val u = t * 0.95f
                        val y = top - 0.08f
                        rim(u, y, tmp)
                        val x = tmp[0]; val depth = tmp[1]
                        if (depth < -0.2f && abs(u) < 0.01f && n > 2) continue
                        // A blade faces front: it narrows as it turns; the outer ones lean out.
                        val sq = max(0.25f, abs(cos(u + a)))
                        val lean = sin(u + a) * 0.28f
                        val bladeH = h * (1f - 0.22f * abs(t))
                        val colour = if (i % 2 == 0) crest else second
                        place(depth * 0.5f + 0.001f * i) {
                            val shape = if (g.crest == CrestForm.COMBS) comb(x, y, 0.16f * sq, bladeH, lean) else plume(x, y, 0.17f * sq, bladeH * 1.1f, lean)
                            c.stroke(shape, lineW * 2f, line, closed = true)
                            c.fill(shape, if (depth >= 0f) colour else darken(colour, 0.2f))
                            if (g.crest == CrestForm.COMBS) c.fill(comb(x + lean * 0.3f, y + bladeH * 0.55f, 0.07f * sq, bladeH * 0.25f, lean * 0.3f), accent, 0.9f)
                        }
                    }
                }
                CrestForm.TIERS -> {
                    val n = g.crestCount.coerceIn(2, 5)
                    val tierH = h / n
                    place(0.5f) {
                        for (i in 0 until n) {
                            val y0 = top - 0.12f + i * tierH
                            val w = hw(top - 0.12f) * k * (0.95f - 0.6f * i / n)
                            val bar = Shapes.roundRect(-w, y0, w, y0 + tierH * 1.05f, tierH * 0.45f)
                            val colour = listOf(crest, second, accent, face)[i % 4]
                            c.stroke(bar, lineW * 2f, line, closed = true)
                            c.fill(bar, colour)
                            // Beads round each tier, so the tier is seen to turn.
                            for (j in 0 until 8) {
                                val u = (j * PI / 4).toFloat()
                                val bx = w * sin(u + a); val d = cos(u + a)
                                if (d < 0.15f) continue
                                c.fill(Shapes.ellipse(bx, y0 + tierH * 0.52f, 0.045f * d + 0.01f, 0.045f), if (colour == line) face else line, smooth(0.15f, 0.4f, d))
                            }
                        }
                    }
                }
                CrestForm.HORNS -> {
                    for (side in listOf(-1f, 1f)) {
                        val u = side * 0.9f
                        val y = top - 0.12f
                        rim(u, y, tmp)
                        val bx = tmp[0]; val depth = tmp[1]
                        // The horn sweeps outward from the head: outward reads less and less as the head turns side-on.
                        val outward = sin(u + a) / abs(sin(u)).coerceAtLeast(0.3f)
                        val reach = 0.62f * outward
                        place(depth) {
                            val horn = Shapes.taper(bx, y, bx + reach * 1.1f, y + h * 0.55f, bx + reach * 0.45f, y + h * 1.05f, 0.26f, 0.03f, 20)
                            c.stroke(horn, lineW * 2f, line, closed = true)
                            c.fill(horn, if (depth >= 0f) crest else darken(crest, 0.2f))
                            for (r in 1..3) {
                                val t = r / 4f
                                val spine = Shapes.quad(bx, y, bx + reach * 1.1f, y + h * 0.55f, bx + reach * 0.45f, y + h * 1.05f, 4)
                                val px = quadAt(spine, t, 0); val py = quadAt(spine, t, 1)
                                c.fill(Shapes.ellipse(px, py, 0.1f * (1f - t) + 0.02f, 0.035f), second, if (depth >= 0f) 1f else 0.7f)
                            }
                        }
                    }
                }
            }
        }

        fun comb(x: Float, y: Float, w: Float, h: Float, lean: Float): FloatArray {
            val body = Shapes.roundRect(-w, 0f, w, h, w)
            return FloatArray(body.size) { i -> if (i % 2 == 0) x + body[i] + lean * body[i + 1] else y + body[i] }
        }

        fun plume(x: Float, y: Float, w: Float, h: Float, lean: Float): FloatArray =
            Shapes.taper(x, y, x + lean * h * 0.6f - w * 0.3f, y + h * 0.6f, x + lean * h, y + h, w * 1.6f, 0.02f, 16)

        fun quadAt(spine: FloatArray, t: Float, axis: Int): Float {
            val n = spine.size / 2 - 1
            val i = (t * n).toInt().coerceIn(0, n)
            return spine[i * 2 + axis]
        }
    }

    // ---- colour ------------------------------------------------------------

    private fun hex(s: String): Int = (0xFF000000.toInt()) or s.removePrefix("#").toInt(16)
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun luma(c: Int) = (0.299f * ch(c, 16) + 0.587f * ch(c, 8) + 0.114f * ch(c, 0)) / 255f
    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF000000.toInt()) or ((r.toInt().coerceIn(0, 255)) shl 16) or ((g.toInt().coerceIn(0, 255)) shl 8) or b.toInt().coerceIn(0, 255)
    private fun mix(a: Int, b: Int, t: Float) = rgb(ch(a, 16) + (ch(b, 16) - ch(a, 16)) * t, ch(a, 8) + (ch(b, 8) - ch(a, 8)) * t, ch(a, 0) + (ch(b, 0) - ch(a, 0)) * t)
    private fun darken(c: Int, t: Float) = mix(c, 0xFF000000.toInt(), t)
    private fun lighten(c: Int, t: Float) = mix(c, 0xFFFFFFFF.toInt(), t)
    private fun shade(c: Int, k: Float) = if (k >= 1f) mix(c, 0xFFFFFFFF.toInt(), (k - 1f).coerceAtMost(1f)) else mix(c, 0xFF000000.toInt(), (1f - k).coerceAtMost(1f))
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    private fun insidePolygon(p: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = p.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = p[i * 2 + 1]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (p[j * 2] - p[i * 2]) * (y - yi) / (yj - yi) + p[i * 2]) inside = !inside
            j = i
        }
        return inside
    }
}
