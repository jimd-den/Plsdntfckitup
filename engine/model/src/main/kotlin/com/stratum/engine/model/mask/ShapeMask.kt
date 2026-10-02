package com.stratum.engine.model.mask

import com.stratum.engine.model.mask.EmojiMask.EyeKind
import com.stratum.engine.model.mask.EmojiMask.Face
import com.stratum.engine.model.mask.EmojiMask.MouthKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Masks built from shapes: one mask made of several clean flat shapes laid
 * together, the way the carvers compose a face from planes. An oval with a
 * dome over it and a plate across the jaw; a shield split in two colours;
 * a heart with triangles on the cheeks; a disc crest behind; a raffia
 * fringe below.
 *
 * Nothing is sculpted. Every piece is a flat shape with a gentle wash, a
 * crisp edge and a soft shadow under it, so the stack of layers reads, and
 * each piece stays its own shape to change. The whole stack turns like a
 * paper puppet: shapes lie on a shallow dome, so they slide and narrow as
 * the mask turns.
 *
 * Feelings come from [EmojiMask.Face]. The same twenty expressions drive the
 * eye shapes, brows and mouth shapes, so every shape mask makes all of them.
 */
object ShapeMask {

    enum class Base(val label: String) { OVAL("Oval"), CIRCLE("Circle"), SHIELD("Shield"), HEART("Heart"), DIAMOND("Diamond"), PLANK("Plank"), WEDGE("Wedge"), EGG("Egg") }
    enum class Addon(val label: String) { DOME("Dome"), CHIN("Chin point"), JAW("Jaw plate"), BROW_PLATE("Brow plate"), WINGS("Side wings"), RIM("Rim") }
    enum class Field(val label: String) { SPLIT("Split"), HALVES("Upper and lower"), BAND("Brow band"), CHEEKS("Cheek triangles"), FOREHEAD("Forehead triangle"), CHIN("Chin block"), QUARTERS("Quarters"), STRIPES("Stripes") }
    enum class Pattern(val label: String) { NONE("Plain"), DOTS("Dots"), CHEVRONS("Chevrons"), LINES("Hatching"), CHECKER("Checker") }
    enum class EyeShape(val label: String) { ALMOND("Almond"), CIRCLE("Circle"), CRESCENT("Crescent"), DIAMOND("Diamond"), SLIT("Slit"), TRIANGLE("Triangle") }
    enum class Surround(val label: String) { NONE("None"), RING("Ring"), DIAMOND("Diamond"), LEAF("Leaf"), SQUARE("Square") }
    enum class Nose(val label: String) { TRIANGLE("Triangle"), BAR("Bar"), DIAMOND("Diamond"), NONE("None") }
    enum class Mouth(val label: String) { RECT("Box"), CIRCLE("Circle"), DIAMOND("Diamond"), CRESCENT("Crescent"), LIPS("Lips") }
    enum class Brows(val label: String) { ARC("Arc"), TRIANGLE("Triangle"), BAR("Bar"), NONE("None") }
    enum class Crest(val label: String) { NONE("None"), FAN("Fan"), DISC("Sun disc"), RAYS("Rays"), HORNS("Horns"), BALLS("Balls"), BLADE("Blade"), ZIGZAG("Zigzag crown") }
    enum class Hang(val label: String) { NONE("None"), BEARD("Beard"), FRINGE("Raffia"), DISCS("Ear discs"), TASSELS("Tassels") }

    /** The shapes a mask is made of. Colours are [AfricanMaskArt.PIGMENTS] indices: ground, ink, then three colours. */
    data class Look(
        val name: String = "Mask",
        val base: Base = Base.OVAL,
        /** 0 narrow .. 1 wide. */
        val width: Float = 0.5f,
        val addons: Set<Addon> = emptySet(),
        val fields: List<Field> = emptyList(),
        val pattern: Pattern = Pattern.NONE,
        val eyes: EyeShape = EyeShape.ALMOND,
        val surround: Surround = Surround.NONE,
        val nose: Nose = Nose.TRIANGLE,
        val mouth: Mouth = Mouth.CRESCENT,
        val brows: Brows = Brows.ARC,
        val crest: Crest = Crest.NONE,
        val crestCount: Int = 5,
        val hang: Hang = Hang.NONE,
        val colours: List<Int> = listOf(EmojiMask.KAOLIN, EmojiMask.LAMPBLACK, EmojiMask.VERMILION, EmojiMask.OCHRE, EmojiMask.LAPIS),
        /** Crisp ink edges round every shape. */
        val outline: Boolean = true,
    )

    /** The layers of a mask, to draw some of them: an exploded view shows how one is built. */
    enum class Layer { BEHIND, BASE, FIELDS, PLATES, FEATURES, FRONT }

    val presets: List<Look> = listOf(
        Look("Sun Maiden", Base.OVAL, 0.45f, setOf(Addon.DOME), listOf(Field.BAND), Pattern.DOTS, EyeShape.ALMOND, Surround.NONE, Nose.BAR, Mouth.LIPS, Brows.ARC, Crest.DISC, 7, Hang.NONE,
            listOf(EmojiMask.KAOLIN, EmojiMask.LAMPBLACK, EmojiMask.VERMILION, EmojiMask.GOLD, EmojiMask.LAPIS)),
        Look("Split Shield", Base.SHIELD, 0.6f, setOf(Addon.RIM), listOf(Field.SPLIT), Pattern.NONE, EyeShape.SLIT, Surround.DIAMOND, Nose.TRIANGLE, Mouth.RECT, Brows.BAR, Crest.FAN, 5, Hang.FRINGE,
            listOf(EmojiMask.KAOLIN, EmojiMask.LAMPBLACK, EmojiMask.INDIGO, EmojiMask.GOLD, EmojiMask.VERMILION)),
        Look("Heart of Fang", Base.HEART, 0.55f, setOf(Addon.CHIN), listOf(Field.CHEEKS), Pattern.NONE, EyeShape.CRESCENT, Surround.NONE, Nose.BAR, Mouth.DIAMOND, Brows.NONE, Crest.BLADE, 1, Hang.NONE,
            listOf(EmojiMask.BONE, EmojiMask.UMBER, EmojiMask.CAMWOOD, EmojiMask.CHARCOAL, EmojiMask.OCHRE)),
        Look("Kuba Plank", Base.PLANK, 0.5f, setOf(Addon.JAW, Addon.BROW_PLATE), listOf(Field.HALVES), Pattern.CHECKER, EyeShape.DIAMOND, Surround.RING, Nose.DIAMOND, Mouth.RECT, Brows.NONE, Crest.ZIGZAG, 6, Hang.TASSELS,
            listOf(EmojiMask.OCHRE, EmojiMask.LAMPBLACK, EmojiMask.KAOLIN, EmojiMask.CAMWOOD, EmojiMask.MALACHITE)),
        Look("Horned Moon", Base.CIRCLE, 0.6f, setOf(Addon.WINGS), listOf(Field.FOREHEAD), Pattern.NONE, EyeShape.CIRCLE, Surround.RING, Nose.TRIANGLE, Mouth.CIRCLE, Brows.TRIANGLE, Crest.HORNS, 2, Hang.DISCS,
            listOf(EmojiMask.TERRACOTTA, EmojiMask.LAMPBLACK, EmojiMask.KAOLIN, EmojiMask.BRASS, EmojiMask.LAPIS)),
        Look("Night Rays", Base.EGG, 0.4f, emptySet(), listOf(Field.QUARTERS), Pattern.LINES, EyeShape.TRIANGLE, Surround.NONE, Nose.BAR, Mouth.CRESCENT, Brows.BAR, Crest.RAYS, 14, Hang.BEARD,
            listOf(EmojiMask.CHARCOAL, EmojiMask.KAOLIN, EmojiMask.VERMILION, EmojiMask.GOLD, EmojiMask.KAOLIN)),
        Look("Diamond Seer", Base.DIAMOND, 0.6f, setOf(Addon.DOME, Addon.CHIN), listOf(Field.STRIPES), Pattern.CHEVRONS, EyeShape.ALMOND, Surround.LEAF, Nose.TRIANGLE, Mouth.LIPS, Brows.ARC, Crest.BALLS, 3, Hang.NONE,
            listOf(EmojiMask.INDIGO, EmojiMask.KAOLIN, EmojiMask.GOLD, EmojiMask.CORAL, EmojiMask.KAOLIN)),
        Look("Wedge Warrior", Base.WEDGE, 0.7f, setOf(Addon.JAW), listOf(Field.BAND, Field.CHIN), Pattern.NONE, EyeShape.SLIT, Surround.SQUARE, Nose.BAR, Mouth.RECT, Brows.TRIANGLE, Crest.FAN, 7, Hang.FRINGE,
            listOf(EmojiMask.CAMWOOD, EmojiMask.LAMPBLACK, EmojiMask.KAOLIN, EmojiMask.GOLD, EmojiMask.CHARCOAL)),
    )

    /** A mask from [seed]: the same seed, the same mask. */
    fun generate(seed: Long, name: String = "Mask"): Look {
        val r = Random(seed * 0x9E3779B97F4A7C15uL.toLong() + 3)
        fun <T> one(xs: List<T>): T = xs[r.nextInt(xs.size)]
        fun <T> some(xs: List<T>, most: Int): List<T> = xs.shuffled(r).take(r.nextInt(0, most + 1))
        val ground = one(listOf(EmojiMask.KAOLIN, EmojiMask.KAOLIN, EmojiMask.BONE, EmojiMask.OCHRE, EmojiMask.CAMWOOD, EmojiMask.TERRACOTTA, EmojiMask.CHARCOAL, EmojiMask.INDIGO, EmojiMask.UMBER, EmojiMask.BRASS, EmojiMask.JADE))
        val light = luma(EmojiMask.pigment(ground)) > 0.45f
        val ink = if (light) one(listOf(EmojiMask.LAMPBLACK, EmojiMask.CHARCOAL, EmojiMask.UMBER)) else one(listOf(EmojiMask.KAOLIN, EmojiMask.BONE))
        val rest = AfricanMaskArt.PIGMENTS.indices.filter { it != ground && it != ink }.shuffled(r).take(3)
        val fields = some(Field.entries, 2)
        return Look(
            name = name,
            base = one(Base.entries),
            width = 0.2f + r.nextFloat() * 0.6f,
            addons = some(Addon.entries, 2).toSet(),
            fields = fields,
            pattern = if (fields.isEmpty()) Pattern.NONE else one(Pattern.entries),
            eyes = one(EyeShape.entries),
            surround = one(listOf(Surround.NONE, Surround.NONE) + Surround.entries),
            nose = one(Nose.entries),
            mouth = one(Mouth.entries),
            brows = one(Brows.entries),
            crest = one(Crest.entries),
            crestCount = 1 + r.nextInt(9),
            hang = one(listOf(Hang.NONE, Hang.NONE) + Hang.entries),
            colours = listOf(ground, ink) + rest,
            outline = r.nextFloat() < 0.8f,
        )
    }

    const val HALF = EmojiMask.HALF
    const val BOTTOM = EmojiMask.BOTTOM

    fun image(look: Look, face: Face, size: Int, turn: Float = 0f, time: Float = 0f, layers: Set<Layer> = Layer.entries.toSet(), blink: Boolean = false): IntArray {
        val canvas = VectorCanvas(size, size, -HALF, BOTTOM, HALF * 2, HALF * 2)
        draw(canvas, look, face, turn, time, layers, blink)
        return canvas.pixels()
    }

    fun draw(canvas: VectorCanvas, look: Look, face: Face, turn: Float = 0f, time: Float = 0f, layers: Set<Layer> = Layer.entries.toSet(), blink: Boolean = false) =
        Stack(canvas, look, face, turn.coerceIn(-1f, 1f), time, layers, blink).draw()

    private class Stack(val cv: VectorCanvas, val look: Look, val f: Face, val yaw: Float, val t: Float, val layers: Set<Layer>, blink: Boolean) {
        val col = look.colours.map(EmojiMask::pigment).let { if (it.size >= 5) it else it + List(5 - it.size) { EmojiMask.pigment(EmojiMask.KAOLIN) } }
        val ground = col[0]; val ink = col[1]; val c1 = col[2]; val c2 = col[3]; val c3 = col[4]
        val W = (0.68f + 0.22f * look.width) * when (look.base) { Base.CIRCLE -> 1.12f; Base.PLANK -> 0.82f; Base.WEDGE -> 1.05f; else -> 1f }
        val H = when (look.base) { Base.CIRCLE -> W * 1.12f; Base.PLANK -> 1.12f; else -> 1f }
        val headY = -0.18f + 0.02f * sin(t * 1.7f)
        val squash = f.bounce * 0.045f * sin(t * 9f)
        val roll = f.tilt + 0.025f * sin(t * 0.9f)
        val cr = cos(roll); val sr = sin(roll)
        val cy = cos(yaw); val sy = sin(yaw)
        val blinkK = if (blink && ((t + 0.7f) % 3.4f) < 0.13f) 0.05f else 1f
        val dark = luma(ground) < 0.35f
        val lineInk = ink

        // ---- the paper-puppet turn ------------------------------------------------

        /** How far a face point stands forward: a shallow dome, so shapes slide as the mask turns. */
        fun dome(u: Float, v: Float): Float = 0.55f * sqrt(max(0f, 1f - (u / (W * 1.15f)).let { it * it } - (v / (H * 1.3f)).let { it * it }))

        fun map(uv: FloatArray, lift: Float = 0f): FloatArray {
            val out = FloatArray(uv.size)
            for (i in 0 until uv.size / 2) {
                val u = uv[i * 2]; val v = uv[i * 2 + 1]
                val x = u * cy + (dome(u, v) + lift) * sy
                val px = x * cr - v * sr; val py = x * sr + v * cr
                out[i * 2] = px * (1f + squash); out[i * 2 + 1] = py * (1f - squash) + headY
            }
            return out
        }

        fun at(u: Float, v: Float, lift: Float = 0f): FloatArray = map(floatArrayOf(u, v), lift)

        // ---- painting a shape --------------------------------------------------------

        fun bounds(p: FloatArray): FloatArray {
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until p.size / 2) { x0 = min(x0, p[i * 2]); x1 = max(x1, p[i * 2]); y0 = min(y0, p[i * 2 + 1]); y1 = max(y1, p[i * 2 + 1]) }
            return floatArrayOf(x0, y0, x1, y1)
        }

        /** A gentle wash: a touch lighter at the top of the shape, a touch deeper at its foot. */
        fun wash(p: FloatArray, colour: Int): VectorCanvas.Paint {
            val b = bounds(p)
            val top = mix(colour, WHITE, 0.12f); val foot = mix(colour, DEEP, 0.12f)
            return VectorCanvas.Paint { _, y -> mix(foot, top, ((y - b[1]) / (b[3] - b[1] + 1e-4f)).coerceIn(0f, 1f)) }
        }

        /** One flat piece: its soft shadow on what is under it, its washed fill, its crisp edge. */
        fun piece(uv: FloatArray, colour: Int, lift: Float = 0f, shadow: Boolean = true, edge: Boolean = look.outline) {
            val p = map(uv, lift)
            if (shadow) cv.fill(Shapes.offset(p, 0.028f, -0.04f), (0x38 shl 24) or 0x140A0C)
            cv.fill(p, wash(p, colour))
            if (edge) cv.stroke(p, 0.024f, mix(colour, DEEP, 0.7f), closed = true)
        }

        fun line(uv: FloatArray, colour: Int, width: Float, lift: Float = 0f, closed: Boolean = false) = cv.stroke(map(uv, lift), width, colour, closed)

        fun soft(x: Float, y: Float, rw: Float, rh: Float, rgb: Int, alpha: Float) {
            if (alpha <= 0.003f) return
            cv.fill(Shapes.ellipse(x, y, rw, rh, 28), VectorCanvas.Paint { px, py ->
                val r = sqrt(((px - x) / rw).let { it * it } + ((py - y) / rh).let { it * it }).coerceIn(0f, 1f)
                (((alpha * (1f - r * r * (3f - 2f * r))) * 255f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)
            })
        }

        fun sides(draw: (Float) -> Unit) { draw(-1f); draw(1f) }

        // ---- shapes ----------------------------------------------------------------------

        fun baseShape(): FloatArray = when (look.base) {
            Base.OVAL -> Shapes.ellipse(0f, 0f, W, H, 64)
            Base.CIRCLE -> Shapes.ellipse(0f, -0.05f, W, W, 64)
            Base.EGG -> FloatArray(128) { k -> val a = (k / 2) * 2f * PI.toFloat() / 64f; val s = sin(a); if (k % 2 == 0) cos(a) * W * (if (s < 0f) 1f + 0.22f * s else 1f) else s * H }
            Base.SHIELD -> {
                val top = FloatArray(40) { k -> val a = (k / 2) * PI.toFloat() / 19f; if (k % 2 == 0) cos(a) * W else 0.1f + sin(a).coerceAtLeast(0f).pow(0.6f) * (H - 0.1f) }
                top + Shapes.quad(-W, 0.1f, -W * 0.8f, -0.75f, 0f, -H - 0.05f, 12).copyOfRange(2, 26) + Shapes.quad(0f, -H - 0.05f, W * 0.8f, -0.75f, W, 0.1f, 12).copyOfRange(2, 24)
            }
            Base.HEART -> FloatArray(96) { k -> val a = (k / 2) * 2f * PI.toFloat() / 48f; if (k % 2 == 0) W * 1.02f * sin(a).let { it * it * it } else H * (13f * cos(a) - 5f * cos(2 * a) - 2f * cos(3 * a) - cos(4 * a)) / 15.5f + 0.1f }
            Base.DIAMOND -> roundPoly(floatArrayOf(0f, H * 1.05f, W * 1.05f, 0f, 0f, -H * 1.05f, -W * 1.05f, 0f), 0.22f)
            Base.PLANK -> Shapes.roundRect(-W, -H, W, H, 0.34f, 10)
            Base.WEDGE -> roundPoly(floatArrayOf(-W, H * 0.8f, W, H * 0.8f, W * 0.62f, -0.55f, 0f, -H * 1.05f, -W * 0.62f, -0.55f), 0.2f)
        }

        /** [p]'s corners rounded by [r]: each corner replaced by a small arc. */
        fun roundPoly(p: FloatArray, r: Float): FloatArray {
            val n = p.size / 2
            val out = ArrayList<Float>()
            for (i in 0 until n) {
                val px = p[i * 2]; val py = p[i * 2 + 1]
                val ax = p[((i + n - 1) % n) * 2]; val ay = p[((i + n - 1) % n) * 2 + 1]
                val bx = p[((i + 1) % n) * 2]; val by = p[((i + 1) % n) * 2 + 1]
                val la = sqrt((ax - px) * (ax - px) + (ay - py) * (ay - py)); val lb = sqrt((bx - px) * (bx - px) + (by - py) * (by - py))
                val k = min(r, min(la, lb) * 0.45f)
                val q = Shapes.quad(px + (ax - px) / la * k, py + (ay - py) / la * k, px, py, px + (bx - px) / lb * k, py + (by - py) / lb * k, 6)
                q.forEach { out += it }
            }
            return out.toFloatArray()
        }

        fun top(): Float = bounds(baseShape())[3]
        fun chin(): Float = bounds(baseShape())[1]

        // ---- the stack ----------------------------------------------------------------------

        fun draw() {
            val base = baseShape()
            if (Layer.BEHIND in layers) {
                crest(behind = true)
                if (Addon.WINGS in look.addons) sides { sd -> piece(floatArrayOf(sd * W * 0.8f, 0.3f, sd * (W + 0.5f), 0.55f + 0.03f * sin(t * 2f), sd * (W + 0.35f), -0.25f, sd * W * 0.8f, -0.35f), c3, -0.2f) }
                if (look.hang == Hang.DISCS || look.hang == Hang.TASSELS) hang(front = false)
                if (Addon.RIM in look.addons) piece(FloatArray(base.size) { base[it] * 1.13f }, if (dark) c1 else ink, -0.15f)
                if (Addon.DOME in look.addons) piece(Shapes.ellipse(0f, top() - 0.05f, W * 0.6f, W * 0.52f, 48), c1, -0.08f)
                if (Addon.CHIN in look.addons) piece(floatArrayOf(-W * 0.5f, chin() + 0.35f, W * 0.5f, chin() + 0.35f, 0f, chin() - 0.42f), c2, -0.08f)
            }
            if (Layer.BASE in layers) piece(base, ground)
            if (Layer.FIELDS in layers) cv.clipped(map(base)) { fields() }
            if (Layer.PLATES in layers) {
                if (Addon.JAW in look.addons) piece(Shapes.roundRect(-W * 0.92f, chin() - 0.04f, W * 0.92f, -0.5f, 0.16f, 8), c1, 0.03f)
                if (Addon.BROW_PLATE in look.addons) piece(Shapes.ellipse(0f, 0.34f, W * 0.72f, 0.36f, 48).let { e -> FloatArray(50) { k -> val i = k / 2; val a = i * PI.toFloat() / 24f; if (k % 2 == 0) cos(a) * W * 0.72f else 0.34f + sin(a) * 0.36f } }, c2, 0.03f)
                crest(behind = false)
            }
            if (Layer.FEATURES in layers) {
                blush()
                sides { sd -> surround(sd) }
                nose()
                sides { sd -> eye(sd) }
                sides { sd -> brow(sd) }
                mouth()
            }
            if (Layer.FRONT in layers) {
                if (look.hang == Hang.BEARD || look.hang == Hang.FRINGE) hang(front = true)
                effects()
            }
        }

        // ---- fields and pattern -----------------------------------------------------------

        fun fieldShape(fd: Field): List<FloatArray> {
            val big = 2f
            return when (fd) {
                Field.SPLIT -> listOf(floatArrayOf(-big, -big, 0f, -big, 0f, big, -big, big))
                Field.HALVES -> listOf(floatArrayOf(-big, -big, big, -big, big, -0.18f, -big, -0.18f))
                Field.BAND -> listOf(floatArrayOf(-big, 0.17f, big, 0.17f, big, 0.4f, -big, 0.4f))
                Field.CHEEKS -> listOf(-1f, 1f).map { sd -> floatArrayOf(sd * 0.16f, -0.1f, sd * (W + 0.1f), -0.1f, sd * 0.4f, -0.62f) }
                Field.FOREHEAD -> listOf(floatArrayOf(-0.42f, big, 0.42f, big, 0f, 0.2f))
                Field.CHIN -> listOf(floatArrayOf(-big, -big, big, -big, big, -0.74f, -big, -0.74f))
                Field.QUARTERS -> listOf(floatArrayOf(-big, 0f, 0f, 0f, 0f, big, -big, big), floatArrayOf(0f, -big, big, -big, big, 0f, 0f, 0f))
                Field.STRIPES -> listOf(-1f, 1f).map { sd -> floatArrayOf(sd * 0.56f, -big, sd * 0.76f, -big, sd * 0.76f, big, sd * 0.56f, big) }
            }
        }

        fun fields() {
            look.fields.forEachIndexed { i, fd ->
                val colour = listOf(c1, c2, c3)[i % 3]
                for (s in fieldShape(fd)) {
                    piece(s, colour, 0.005f, shadow = false, edge = false)
                    if (i == 0 && look.pattern != Pattern.NONE) cv.clipped(map(s, 0.005f)) { pattern(if (luma(colour) > 0.5f) ink else mix(colour, WHITE, 0.55f)) }
                }
            }
        }

        fun pattern(colour: Int) {
            val step = 0.13f
            var v = -1.4f; var row = 0
            while (v < 1.4f) {
                var u = -1.3f + (if (row % 2 == 0) 0f else step / 2f)
                while (u < 1.3f) {
                    when (look.pattern) {
                        Pattern.DOTS -> cv.fill(map(Shapes.ellipse(u, v, 0.025f, 0.025f, 10), 0.01f), colour)
                        Pattern.CHEVRONS -> line(floatArrayOf(u - 0.05f, v + 0.03f, u, v - 0.02f, u + 0.05f, v + 0.03f), colour, 0.02f, 0.01f)
                        Pattern.LINES -> line(floatArrayOf(u - 0.05f, v - 0.05f, u + 0.05f, v + 0.05f), colour, 0.018f, 0.01f)
                        Pattern.CHECKER -> if ((row + ((u + 2f) / step).toInt()) % 2 == 0) cv.fill(map(floatArrayOf(u - 0.05f, v - 0.05f, u + 0.05f, v - 0.05f, u + 0.05f, v + 0.05f, u - 0.05f, v + 0.05f), 0.01f), colour)
                        Pattern.NONE -> Unit
                    }
                    u += step
                }
                v += step; row++
            }
        }

        // ---- the face ----------------------------------------------------------------------

        val eu = 0.36f * (W / 0.8f).coerceIn(0.85f, 1.15f)
        val ev = 0.04f
        val mv = -0.5f

        fun blush() {
            val amount = (f.blush + f.anger * 0.3f).coerceIn(0f, 1.2f)
            if (amount > 0f) sides { sd -> val p = at(sd * eu * 1.35f, -0.26f, 0.02f); soft(p[0], p[1], 0.2f * (0.5f + 0.5f * cos(yaw)), 0.12f, c(0xE8584A), 0.5f * amount) }
        }

        fun surround(sd: Float) {
            val cu = sd * eu
            when (look.surround) {
                Surround.NONE -> Unit
                Surround.RING -> piece(Shapes.ellipse(cu, ev, 0.22f, 0.2f, 40), c3, 0.02f)
                Surround.DIAMOND -> piece(floatArrayOf(cu, ev + 0.22f, cu + 0.28f, ev, cu, ev - 0.2f, cu - 0.28f, ev), c3, 0.02f)
                Surround.LEAF -> piece(Shapes.quad(cu - sd * 0.3f, ev - 0.02f, cu, ev + 0.32f, cu + sd * 0.3f, ev + 0.06f, 12) + Shapes.quad(cu + sd * 0.3f, ev + 0.06f, cu, ev - 0.24f, cu - sd * 0.3f, ev - 0.02f, 12).copyOfRange(2, 24), c3, 0.02f)
                Surround.SQUARE -> piece(Shapes.roundRect(cu - 0.23f, ev - 0.18f, cu + 0.23f, ev + 0.18f, 0.06f, 5), c3, 0.02f)
            }
        }

        fun eye(sd: Float) {
            val cu = sd * eu; val v = ev
            val eyeInk = if (look.surround != Surround.NONE && luma(c3) < 0.35f) c(0xF1E8DA) else if (dark && look.surround == Surround.NONE) c(0xF1E8DA) else c(0x1E1412)
            when (f.eyeKind) {
                EyeKind.HEART -> {
                    val s = 0.24f * (1f + 0.08f * sin(t * 9f))
                    piece(FloatArray(80) { k -> val a = (k / 2) * 2f * PI.toFloat() / 40f; if (k % 2 == 0) cu + s * sin(a).pow(3) else v + s * (13f * cos(a) - 5f * cos(2 * a) - 2f * cos(3 * a) - cos(4 * a)) / 17f + s * 0.1f }, c(0xE8283C), 0.06f)
                    return
                }
                EyeKind.STAR -> { piece(star(cu, v, 0.1f, 0.24f, 5, t * 0.6f), c(0xFFC21A), 0.06f); return }
                EyeKind.X -> { val d = 0.11f; line(floatArrayOf(cu - d, v + d, cu + d, v - d), eyeInk, 0.06f, 0.05f); line(floatArrayOf(cu - d, v - d, cu + d, v + d), eyeInk, 0.06f, 0.05f); return }
                EyeKind.SPIRAL -> {
                    val pts = FloatArray(90) { k -> val i = k / 2; val kk = i / 44f; val a = kk * 5.2f * PI.toFloat() * sd + t * 5f * sd; val r = 0.01f + 0.13f * kk; if (k % 2 == 0) cu + cos(a) * r else v + sin(a) * r }
                    line(pts, eyeInk, 0.036f, 0.05f); return
                }
                EyeKind.NORMAL -> Unit
            }
            val open = (if (sd < 0) f.eyeOpenL else f.eyeOpenR) * blinkK
            val smile = f.eyeSmile.coerceIn(0f, 1f)
            if (open < 0.12f) {
                if (smile > 0.45f) line(Shapes.quad(cu - 0.16f, v - 0.04f, cu, v + 0.15f, cu + 0.16f, v - 0.04f, 14), eyeInk, 0.06f, 0.05f)
                else line(Shapes.quad(cu - 0.17f, v + 0.02f, cu, v - 0.09f, cu + 0.17f, v + 0.02f, 14), eyeInk, 0.05f, 0.05f)
                return
            }
            val o = open.coerceAtMost(1.4f)
            val ew = 0.17f; val eh = 0.1f
            val shape = when (look.eyes) {
                EyeShape.ALMOND -> {
                    val up = v + 2f * eh * o * (1f + 0.2f * smile); val low = v + 2f * eh * o * (-(1f - smile) + 0.8f * smile)
                    Shapes.quad(cu - sd * ew, v, cu, up, cu + sd * ew, v + 0.03f, 16) + Shapes.quad(cu + sd * ew, v + 0.03f, cu, low, cu - sd * ew, v, 16).copyOfRange(2, 32)
                }
                EyeShape.CIRCLE -> Shapes.ellipse(cu, v, 0.12f, 0.12f * min(1.2f, o * 1.4f) * (1f - 0.45f * smile), 32)
                EyeShape.CRESCENT -> crescent(cu, v, 0.15f, 0.05f + 0.08f * o, smile > 0.3f || f.mouthSmile > 0.3f)
                EyeShape.DIAMOND -> floatArrayOf(cu, v + 0.13f * o * (1f - 0.4f * smile), cu + 0.18f, v, cu, v - 0.13f * o * (1f - 0.7f * smile), cu - 0.18f, v)
                EyeShape.SLIT -> Shapes.roundRect(cu - 0.19f, v - 0.04f * o, cu + 0.19f, v + 0.04f * o + 0.01f, 0.03f, 5)
                EyeShape.TRIANGLE -> floatArrayOf(cu - 0.17f, v + 0.08f * o, cu + 0.17f, v + 0.08f * o, cu + sd * 0.02f, v - 0.14f * o * (1f - 0.5f * smile))
            }
            piece(shape, eyeInk, 0.05f)
            // A glint that looks about: the emoji's spark of life.
            if (luma(eyeInk) < 0.4f) {
                val g = at(cu - 0.04f + f.lookX * 0.05f, v + 0.03f * o + f.lookY * 0.03f, 0.06f)
                cv.fill(Shapes.ellipse(g[0], g[1], 0.034f * (0.5f + 0.5f * cos(yaw)), 0.034f, 14), WHITE)
            }
            if (f.tears > 0.15f) cv.fill(map(Shapes.ellipse(cu, v - 0.07f, 0.13f, 0.035f, 18), 0.06f), c(0x7FC8FF), 0.7f)
        }

        fun brow(sd: Float) {
            val cu = sd * eu
            val raise = f.browRaise + (if (sd > 0) f.browAsym else 0f)
            val y = ev + 0.24f + raise * 0.07f
            val tilt = f.browTilt
            val browInk = if (dark) c1 else ink
            val ax = cu - sd * 0.17f; val ay = y - tilt * 0.07f
            val bx = cu + sd * 0.2f; val by = y + tilt * 0.04f - 0.02f
            when (look.brows) {
                Brows.NONE -> Unit
                Brows.ARC -> line(Shapes.quad(ax, ay, cu, y + 0.08f, bx, by, 12), browInk, 0.055f, 0.04f)
                Brows.BAR -> piece(thick(ax, ay, bx, by, 0.07f), browInk, 0.04f)
                Brows.TRIANGLE -> piece(floatArrayOf(ax, ay - 0.03f, bx, by - 0.03f, (ax + bx) / 2f - sd * 0.03f, y + 0.12f - tilt * 0.02f), browInk, 0.04f)
            }
        }

        fun nose() {
            val nc = if (dark) mix(ground, WHITE, 0.2f) else mix(ground, DEEP, 0.18f)
            when (look.nose) {
                Nose.TRIANGLE -> piece(floatArrayOf(0f, 0.02f, 0.085f, -0.28f, -0.085f, -0.28f), nc, 0.1f)
                Nose.BAR -> piece(Shapes.roundRect(-0.04f, -0.3f, 0.04f, 0.2f, 0.04f, 5), nc, 0.1f)
                Nose.DIAMOND -> piece(floatArrayOf(0f, 0.05f, 0.08f, -0.13f, 0f, -0.3f, -0.08f, -0.13f), c2, 0.1f)
                Nose.NONE -> Unit
            }
        }

        fun mouth() {
            val mInk = if (dark) c(0x2A0E10) else c(0x2A0E10)
            val lipCol = c1
            val hw = 0.13f + 0.12f * f.mouthWide
            val cu = f.mouthSide * 0.05f
            val smile = f.mouthSmile
            var open = f.mouthOpen
            if (f.mouthKind == MouthKind.O) {
                piece(Shapes.ellipse(cu, mv - 0.02f, 0.07f + 0.06f * open, 0.08f + 0.09f * open, 28), mInk, 0.04f)
                if (f.tongue > 0.6f) piece(Shapes.roundRect(cu - 0.06f, mv - 0.34f, cu + 0.06f, mv - 0.1f, 0.06f, 6), TONGUE, 0.05f)
                return
            }
            if (f.mouthKind == MouthKind.KISS) {
                piece(Shapes.ellipse(cu, mv, 0.06f, 0.05f, 20), lipCol, 0.04f)
                piece(Shapes.ellipse(cu, mv, 0.022f, 0.015f, 10), mInk, 0.05f, shadow = false, edge = false)
                return
            }
            val lineCol = if (dark) c(0xF1E8DA) else mInk
            when (look.mouth) {
                Mouth.CRESCENT -> {
                    if (open < 0.08f && f.teeth < 0.5f) { line(Shapes.quad(cu - hw, mv + smile * 0.06f, cu, mv - smile * 0.12f, cu + hw, mv + smile * 0.06f, 14), lineCol, 0.05f, 0.04f); tongueOut(cu); return }
                    open = max(open, 0.15f)
                    val shape = crescent(cu, mv + 0.04f, hw, 0.05f + open * 0.28f, smile >= 0f)
                    piece(shape, mInk, 0.04f)
                    teethAndTongue(shape, cu, mv, hw, open)
                }
                Mouth.RECT -> {
                    open = max(open, if (f.teeth > 0.5f) 0.18f else 0.06f)
                    val lift = smile * 0.06f
                    val shape = floatArrayOf(cu - hw, mv + 0.03f + lift, cu + hw, mv + 0.03f + lift, cu + hw * 0.9f, mv - open * 0.3f, cu - hw * 0.9f, mv - open * 0.3f)
                    piece(shape, mInk, 0.04f)
                    teethAndTongue(shape, cu, mv, hw, open)
                }
                Mouth.CIRCLE -> {
                    val r = 0.06f + 0.05f * f.mouthWide + open * 0.08f
                    val shape = Shapes.ellipse(cu, mv - 0.02f, r * (1f + 0.3f * max(0f, smile)), r * (0.6f + open), 28)
                    piece(shape, mInk, 0.04f)
                    teethAndTongue(shape, cu, mv, hw, open)
                }
                Mouth.DIAMOND -> {
                    val h = 0.05f + open * 0.2f
                    val shape = floatArrayOf(cu, mv + h * (1f - 0.5f * smile), cu + hw, mv + smile * 0.05f, cu, mv - h * (1f + 0.5f * max(0f, smile)), cu - hw, mv + smile * 0.05f)
                    piece(shape, if (open > 0.1f) mInk else lipCol, 0.04f)
                    if (open > 0.1f) teethAndTongue(shape, cu, mv, hw, open)
                }
                Mouth.LIPS -> {
                    val y = mv + smile * 0.05f
                    val gap = open * 0.2f
                    if (gap > 0.02f) { val inside = Shapes.ellipse(cu, mv - gap * 0.5f, hw * 0.85f, gap, 24); piece(inside, mInk, 0.035f); teethAndTongue(inside, cu, mv, hw, open) }
                    piece(Shapes.quad(cu - hw, y, cu - hw * 0.4f, mv + 0.11f, cu, mv + 0.04f, 8) + Shapes.quad(cu, mv + 0.04f, cu + hw * 0.4f, mv + 0.11f, cu + hw, y, 8).copyOfRange(2, 18) + floatArrayOf(cu, mv + 0.005f), lipCol, 0.045f)
                    piece(Shapes.quad(cu + hw, y, cu, mv - gap - 0.17f, cu - hw, y, 14), lipCol, 0.045f)
                }
            }
            tongueOut(cu)
        }

        fun teethAndTongue(shape: FloatArray, cu: Float, mv: Float, hw: Float, open: Float) {
            val m = map(shape, 0.04f)
            cv.clipped(m) {
                if (f.teeth > 0f) {
                    val b = bounds(shape)
                    val n = 5
                    for (i in 0 until n) {
                        val x0 = b[0] + (b[2] - b[0]) * i / n + 0.008f; val x1 = b[0] + (b[2] - b[0]) * (i + 1) / n - 0.008f
                        cv.fill(map(floatArrayOf(x0, b[3] - 0.07f, x1, b[3] - 0.07f, x1, b[3] + 0.05f, x0, b[3] + 0.05f), 0.045f), WHITE, min(1f, f.teeth))
                    }
                }
                if (f.tongue > 0.2f) { val b = bounds(shape); cv.fill(map(Shapes.ellipse(cu, b[1] + 0.02f, hw * 0.55f, 0.07f + 0.03f * open, 20), 0.045f), TONGUE) }
            }
        }

        fun tongueOut(cu: Float) {
            if (f.tongue <= 0.6f) return
            val len = 0.2f + 0.02f * sin(t * 4f)
            piece(Shapes.roundRect(cu - 0.07f, mv - 0.04f - len, cu + 0.07f, mv - 0.02f, 0.07f, 6), TONGUE, 0.05f)
            line(floatArrayOf(cu, mv - 0.06f, cu, mv - len * 0.7f), mix(TONGUE, DEEP, 0.45f), 0.014f, 0.06f)
        }

        // ---- the crest and what hangs -------------------------------------------------------------

        fun crest(behind: Boolean) {
            val tp = top()
            val n = look.crestCount.coerceIn(1, 16)
            when (look.crest) {
                Crest.NONE -> Unit
                Crest.FAN -> if (behind) {
                    val m = n.coerceIn(3, 9)
                    for (i in 0 until m) {
                        val k = i - (m - 1) / 2f
                        val a = k * 0.26f + sin(t * 2f + i) * 0.03f
                        val len = 0.62f - abs(k) * 0.05f
                        val bx = 0f; val by = tp - 0.25f
                        val tipX = bx + sin(a) * len * 1.2f; val tipY = by + cos(a) * len * 1.2f
                        val nx = cos(a) * 0.1f; val ny = -sin(a) * 0.1f
                        piece(floatArrayOf(bx - nx, by - ny, bx + nx, by + ny, tipX, tipY), listOf(c1, c2, c3)[i % 3], -0.12f)
                    }
                }
                Crest.DISC -> if (behind) {
                    val r = W * 1.05f; val ccy = tp - 0.2f
                    piece(Shapes.ellipse(0f, ccy, r, r * 0.9f, 60), c2, -0.2f)
                    piece(Shapes.ellipse(0f, ccy, r * 0.8f, r * 0.72f, 60), c1, -0.19f, shadow = false)
                    for (i in 0 until n.coerceIn(5, 16)) {
                        val a = i * 2f * PI.toFloat() / n.coerceIn(5, 16) + t * 0.2f
                        piece(Shapes.ellipse(cos(a) * r * 0.9f, ccy + sin(a) * r * 0.81f, 0.045f, 0.045f, 12), c3, -0.18f, shadow = false)
                    }
                }
                Crest.RAYS -> if (behind) {
                    val m = n.coerceIn(8, 18)
                    for (i in 0 until m) {
                        val a = PI.toFloat() * (0.05f + 0.9f * i / (m - 1)) + sin(t * 1.3f) * 0.02f
                        val r0 = W * 0.85f; val r1 = W * 0.85f + 0.45f + 0.12f * (i % 2)
                        val dx = cos(a); val dy = sin(a); val wv = 0.07f
                        piece(floatArrayOf(dx * r0 - dy * wv, 0.1f + dy * r0 + dx * wv, dx * r1, 0.1f + dy * r1 * 1.05f, dx * r0 + dy * wv, 0.1f + dy * r0 - dx * wv), if (i % 2 == 0) c2 else c1, -0.15f)
                    }
                }
                Crest.HORNS -> if (behind) sides { sd ->
                    val sw = sin(t * 1.7f) * 0.03f
                    val outer = Shapes.quad(sd * W * 0.35f, tp - 0.3f, sd * (W + 0.55f), tp + 0.1f, sd * (W + 0.1f) + sw, tp + 0.72f, 14)
                    val inner = Shapes.quad(sd * (W + 0.1f) + sw, tp + 0.72f, sd * (W + 0.25f), tp + 0.05f, sd * W * 0.7f, tp - 0.35f, 14)
                    piece(outer + inner.copyOfRange(2, inner.size), c3, -0.1f)
                }
                Crest.BALLS -> if (behind) {
                    val m = n.coerceIn(1, 5)
                    for (i in 0 until m) {
                        val k = i - (m - 1) / 2f
                        val x = k * 0.32f; val y = tp + 0.22f - abs(k) * 0.08f + 0.02f * sin(t * 2f + i)
                        line(floatArrayOf(x * 0.5f, tp - 0.15f, x, y), ink, 0.05f, -0.1f)
                        piece(Shapes.ellipse(x, y + 0.08f, 0.14f, 0.14f, 32), listOf(c1, c2, c3)[i % 3], -0.1f)
                        piece(Shapes.ellipse(x, y + 0.08f, 0.06f, 0.06f, 20), ink, -0.09f, shadow = false)
                    }
                }
                Crest.BLADE -> if (behind) {
                    val h = 0.8f
                    piece(Shapes.roundRect(-0.14f, tp - 0.3f, 0.14f, tp + h, 0.14f, 8), c2, -0.1f)
                    var y = tp; var i = 0
                    while (y < tp + h - 0.12f) { piece(floatArrayOf(-0.1f, y, 0.1f, y, 0f, y + 0.12f), if (i % 2 == 0) c1 else c3, -0.09f, shadow = false); y += 0.16f; i++ }
                }
                Crest.ZIGZAG -> if (!behind) {
                    val m = n.coerceIn(4, 9)
                    val half = W * 0.85f
                    val y0 = tp - 0.28f
                    val band = FloatArray((m * 2 + 3) * 2)
                    var o = 0
                    band[o++] = -half; band[o++] = y0
                    for (i in 0..m * 2) { band[o++] = -half + 2 * half * i / (m * 2); band[o++] = if (i % 2 == 1) y0 + 0.3f else y0 + 0.08f }
                    band[o++] = half; band[o++] = y0
                    piece(band.copyOf(o), c2, 0.04f)
                }
            }
        }

        fun hang(front: Boolean) {
            val cb = chin()
            when (look.hang) {
                Hang.NONE -> Unit
                Hang.BEARD -> {
                    val sw = sin(t * 1.9f) * 0.03f
                    piece(floatArrayOf(-W * 0.42f, cb + 0.3f, W * 0.42f, cb + 0.3f, sw, cb - 0.5f), c2, 0.03f)
                    for (k in 1..3) line(floatArrayOf(-W * 0.35f + k * 0.07f, cb + 0.2f - k * 0.13f, sw * k / 3f, cb + 0.1f - k * 0.13f - 0.06f, W * 0.35f - k * 0.07f, cb + 0.2f - k * 0.13f), c1, 0.03f, 0.04f)
                }
                Hang.FRINGE -> {
                    val m = 11
                    for (i in 0 until m) {
                        val x = (i - (m - 1) / 2f) * (W * 1.2f / m)
                        val sw = sin(t * 2.3f + i * 0.4f) * 0.06f
                        line(floatArrayOf(x, cb + 0.08f, x + sw * 0.5f, cb - 0.2f, x + sw, cb - 0.45f - 0.06f * (i % 3)), listOf(c1, c2, c3)[i % 3], 0.045f, 0.02f)
                    }
                    piece(Shapes.roundRect(-W * 0.62f, cb + 0.02f, W * 0.62f, cb + 0.14f, 0.05f, 5), ink, 0.03f)
                }
                Hang.DISCS -> sides { sd ->
                    piece(Shapes.ellipse(sd * (W + 0.1f), -0.05f, 0.16f, 0.16f, 32), c2, -0.1f)
                    piece(Shapes.ellipse(sd * (W + 0.1f), -0.05f, 0.07f, 0.07f, 20), ink, -0.09f, shadow = false)
                }
                Hang.TASSELS -> sides { sd ->
                    val sw = sin(t * 2.4f + sd) * 0.05f
                    val x = sd * (W + 0.02f)
                    line(floatArrayOf(x, 0f, x + sw, -0.4f), ink, 0.025f, -0.1f)
                    piece(Shapes.ellipse(x + sw, -0.48f, 0.08f, 0.11f, 24), c3, -0.1f)
                }
            }
        }

        // ---- effects, as flat shapes -------------------------------------------------------

        fun effects() {
            if (f.tears > 0.6f) sides { sd ->
                val a = at(sd * eu, ev - 0.08f, 0.06f)
                val s = Shapes.taper(a[0], a[1], a[0] + sd * 0.3f, a[1] - 0.05f, a[0] + sd * 0.45f, chin() + headY - 0.2f, 0.07f, 0.16f * f.tears, 16)
                cv.fill(s, c(0x5AB4F5), 0.9f); cv.stroke(s, 0.02f, c(0x2E6AB0), closed = true)
            } else if (f.tears > 0f) sides { sd ->
                val ph = ((t * 0.5f) + (sd + 1f) * 0.25f) % 1f
                val p = at(sd * (eu + 0.1f), ev - 0.12f - ph * 0.5f * f.tears, 0.06f)
                drop(p[0], p[1], 0.06f)
            }
            if (f.sweat > 0f) { val p = at(0.62f, 0.45f, 0.06f); drop(p[0] + 0.08f, p[1], 0.09f * f.sweat) }
            if (f.zzz > 0f) for (k in 0 until 3) {
                val ph = (t * 0.35f + k / 3f) % 1f
                val s = (0.06f + 0.08f * ph) * f.zzz
                val x = 0.75f + 0.45f * ph; val y = headY + 0.6f + 0.7f * ph
                val z = floatArrayOf(x - s, y + s, x + s, y + s, x - s, y - s, x + s, y - s)
                cv.stroke(z, s * 0.55f, c(0x2A3A7A), opacity = 1f - ph)
                cv.stroke(z, s * 0.3f, c(0xBFD8FF), opacity = 1f - ph)
            }
            if (f.hearts > 0f) for (k in 0 until (if (f.hearts > 0.6f) 3 else 1)) {
                val a = t * 0.9f + k * 2.1f + 0.6f
                val x = cos(a) * 1.2f; val y = headY + 0.15f + sin(a) * 0.6f; val s = 0.12f * f.hearts
                val h = FloatArray(80) { q -> val tt = (q / 2) * 2f * PI.toFloat() / 40f; if (q % 2 == 0) x + s * sin(tt).pow(3) else y + s * (13f * cos(tt) - 5f * cos(2 * tt) - 2f * cos(3 * tt) - cos(4 * tt)) / 17f }
                cv.fill(h, c(0xE8283C)); cv.stroke(h, 0.02f, c(0x8A1020), closed = true)
            }
            if (f.anger > 0f) {
                val cx = 0.62f; val cy2 = headY + 0.78f; val s = 0.1f * f.anger
                for (q in 0 until 4) { val a = q * PI.toFloat() / 2f + PI.toFloat() / 4f; val ox = cx + cos(a) * s; val oy = cy2 + sin(a) * s
                    cv.stroke(Shapes.quad(ox + cos(a + 1.2f) * s * 0.6f, oy + sin(a + 1.2f) * s * 0.6f, ox - cos(a) * s * 0.35f, oy - sin(a) * s * 0.35f, ox + cos(a - 1.2f) * s * 0.6f, oy + sin(a - 1.2f) * s * 0.6f, 8), s * 0.32f, c(0xE0242E)) }
            }
            if (f.sparkle > 0f) listOf(-1.15f to 0.55f, 1.1f to 0.8f, 1.0f to -0.55f, -1.05f to -0.35f).forEachIndexed { i, (x, y) ->
                val tw = (0.55f + 0.45f * sin(t * 4f + i * 1.7f)) * f.sparkle
                cv.fill(star(x, headY + y, 0.02f * tw, 0.12f * tw, 4, 0f), c(0xFFD84A))
            }
        }

        fun drop(x: Float, y: Float, s: Float) {
            val pts = FloatArray(56) { k -> val th = (k / 2) * 2f * PI.toFloat() / 28f; if (k % 2 == 0) x + s * sin(th) * sin(th / 2f) else y + s * 1.4f * cos(th) }
            cv.fill(pts, c(0x5AB4F5)); cv.stroke(pts, 0.018f, c(0x2E6AB0), closed = true)
        }

        // ---- shape helpers ------------------------------------------------------------------

        fun crescent(cx: Float, cyy: Float, r: Float, thick: Float, up: Boolean): FloatArray {
            val n = 16
            val out = ArrayList<Float>()
            val s = if (up) 1f else -1f
            for (i in 0..n) { val a = PI.toFloat() * i / n; out += cx - cos(a) * r; out += cyy - s * sin(a) * (r * 0.55f + thick) }
            for (i in n - 1 downTo 1) { val a = PI.toFloat() * i / n; out += cx - cos(a) * r * 0.92f; out += cyy - s * sin(a) * r * 0.3f }
            return out.toFloatArray()
        }

        fun star(cx: Float, cyy: Float, r0: Float, r1: Float, points: Int, spin: Float): FloatArray = FloatArray(points * 4) { k ->
            val i = k / 2; val a = spin + PI.toFloat() / 2f + i * PI.toFloat() / points; val r = if (i % 2 == 0) r1 else r0
            if (k % 2 == 0) cx + cos(a) * r else cyy + sin(a) * r
        }

        fun thick(ax: Float, ay: Float, bx: Float, by: Float, w: Float): FloatArray {
            var dx = bx - ax; var dy = by - ay; val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f); dx = dx / l * w / 2f; dy = dy / l * w / 2f
            return floatArrayOf(ax - dy, ay + dx, bx - dy, by + dx, bx + dy, by - dx, ax + dy, ay - dx)
        }
    }

    private val WHITE = c(0xFFFFFF)
    private val DEEP = c(0x140A0C)
    private val TONGUE = c(0xF0607A)

    private fun c(rgb: Int) = (0xFF shl 24) or rgb
    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun luma(argb: Int) = (0.299f * ch(argb, 16) + 0.587f * ch(argb, 8) + 0.114f * ch(argb, 0)) / 255f
    private fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
