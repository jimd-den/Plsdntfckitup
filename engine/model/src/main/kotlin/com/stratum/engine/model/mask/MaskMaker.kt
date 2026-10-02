package com.stratum.engine.model.mask

import com.stratum.engine.model.mask.EmojiMask.Look
import com.stratum.engine.scene.SpiritMesh
import java.math.BigInteger
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The mask maker's rules over [EmojiMask]: which traits a roll can keep,
 * rolling again and browsing variations, how many masks the choices make,
 * a short share code, and a mask made into something the game can carry.
 */
object MaskMaker {

    /** What a roll can keep: a locked trait survives [reroll]; [variations] change one or two unlocked ones. */
    enum class Trait(val label: String) {
        SHAPE("Face"), FINISH("Wood & finish"), COLOURS("Colours"), COIFFURE("Coiffure"), CREST("Crest"), EYES("Eyes"), BROWS("Brows"),
        NOSE("Nose"), MOUTH("Mouth"), EARS("Ears"), MARKS("Marks"), PAINT("Paint"), ADORNMENT("Adornment"),
    }

    /** [look] rolled again from [seed], keeping every trait in [locked] and its name. */
    fun reroll(look: Look, seed: Long, locked: Set<Trait> = emptySet()): Look {
        val f = EmojiMask.generate(seed, look.name)
        fun <T> keep(t: Trait, mine: T, fresh: T): T = if (t in locked) mine else fresh
        return f.copy(
            shape = keep(Trait.SHAPE, look.shape, f.shape), width = keep(Trait.SHAPE, look.width, f.width),
            finish = keep(Trait.FINISH, look.finish, f.finish), wood = keep(Trait.FINISH, look.wood, f.wood),
            ground = keep(Trait.COLOURS, look.ground, if (Trait.FINISH in locked && look.finish == EmojiMask.Finish.CARVED) look.wood else f.ground),
            hair = keep(Trait.COLOURS, look.hair, f.hair), accent = keep(Trait.COLOURS, look.accent, f.accent),
            accent2 = keep(Trait.COLOURS, look.accent2, f.accent2), crestColour = keep(Trait.COLOURS, look.crestColour, f.crestColour),
            hairline = keep(Trait.COIFFURE, look.hairline, f.hairline), braids = keep(Trait.COIFFURE, look.braids, f.braids),
            cowries = keep(Trait.COIFFURE, look.cowries, f.cowries),
            crest = keep(Trait.CREST, look.crest, f.crest), crestCount = keep(Trait.CREST, look.crestCount, f.crestCount),
            crestSize = keep(Trait.CREST, look.crestSize, f.crestSize),
            eyes = keep(Trait.EYES, look.eyes, f.eyes), brows = keep(Trait.BROWS, look.brows, f.brows), nose = keep(Trait.NOSE, look.nose, f.nose),
            mouth = keep(Trait.MOUTH, look.mouth, f.mouth), ears = keep(Trait.EARS, look.ears, f.ears),
            marks = keep(Trait.MARKS, look.marks, f.marks), paint = keep(Trait.PAINT, look.paint, f.paint),
            earrings = keep(Trait.ADORNMENT, look.earrings, f.earrings), beard = keep(Trait.ADORNMENT, look.beard, f.beard),
        ).let(::normalised)
    }

    /** [count] masks like [look]: each changes one or two of its traits not in [locked], so browsing never loses it. */
    fun variations(look: Look, seed: Long, count: Int, locked: Set<Trait> = emptySet()): List<Look> {
        val open = Trait.entries.filter { it !in locked }
        if (open.isEmpty()) return List(count) { look }
        return List(count) { i ->
            val r = Random(seed * 1_000_003L + i)
            val change = open.shuffled(r).take(1 + r.nextInt(2)).toSet()
            reroll(look, seed * 7919L + i * 104_729L + 1, Trait.entries.toSet() - change)
        }
    }

    /** How many different masks the choices make, before any slider -- the sliders multiply it past counting. */
    fun choices(): BigInteger {
        fun b(n: Int) = BigInteger.valueOf(n.toLong())
        val pigments = b(EmojiMask.PIGMENT_COUNT)
        return listOf(
            b(EmojiMask.FaceShape.entries.size), b(EmojiMask.Finish.entries.size), pigments, pigments.pow(5),
            b(EmojiMask.Hairline.entries.size), b(4), b(EmojiMask.Crest.entries.size), b(6), b(EmojiMask.EyeStyle.entries.size),
            b(EmojiMask.BrowStyle.entries.size), b(EmojiMask.NoseStyle.entries.size), b(EmojiMask.MouthStyle.entries.size),
            b(EmojiMask.Ears.entries.size), b(1 shl EmojiMask.Mark.entries.size), b(1 shl EmojiMask.Paint.entries.size), b(4),
        ).reduce(BigInteger::multiply)
    }

    /** A big count as words a player reads at a glance: "4.2 trillion". */
    fun spoken(n: BigInteger): String {
        val names = listOf("thousand", "million", "billion", "trillion", "quadrillion", "quintillion", "sextillion", "septillion", "octillion", "nonillion", "decillion")
        val digits = n.toString().length
        if (digits <= 3) return n.toString()
        val group = ((digits - 1) / 3).coerceAtMost(names.size)
        val scaled = n.toBigDecimal().movePointLeft(group * 3).toDouble()
        return "${"%.1f".format(scaled).removeSuffix(".0")} ${names[group - 1]}"
    }

    // ---- sharing ----------------------------------------------------------------------

    const val PREFIX = "im1:"
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val STEPS = 35

    fun isCode(code: String?): Boolean = code != null && code.trim().startsWith(PREFIX)

    /** Sliders on the steps a code holds, so a mask and its code are the same mask. */
    fun normalised(look: Look): Look = look.copy(width = q(look.width), crestSize = q(look.crestSize), crestCount = look.crestCount.coerceIn(1, 9))

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * STEPS).roundToInt() / STEPS.toFloat()

    fun encode(look: Look): String {
        val b = StringBuilder(PREFIX)
        fun put(n: Int) { b.append(DIGITS[n.coerceIn(0, 35)]) }
        fun unit(v: Float) = put((v.coerceIn(0f, 1f) * STEPS).roundToInt())
        fun flag(v: Boolean) = put(if (v) 1 else 0)
        fun bits(n: Int) { put(n / 36); put(n % 36) }
        put(look.shape.ordinal); unit(look.width); put(look.finish.ordinal); put(look.wood); put(look.ground); put(look.hair)
        put(look.accent); put(look.accent2); put(look.crestColour); put(look.hairline.ordinal); flag(look.braids); flag(look.cowries)
        put(look.crest.ordinal); put(look.crestCount); unit(look.crestSize); put(look.eyes.ordinal); put(look.brows.ordinal)
        put(look.nose.ordinal); put(look.mouth.ordinal); put(look.ears.ordinal)
        bits(look.marks.fold(0) { acc, m -> acc or (1 shl m.ordinal) }); bits(look.paint.fold(0) { acc, p -> acc or (1 shl p.ordinal) })
        flag(look.earrings); flag(look.beard)
        if (look.name.isNotBlank()) b.append('~').append(look.name.replace('~', '-').take(40))
        return b.toString()
    }

    /** The mask in [code], or null when it isn't one (or is damaged). */
    fun decode(code: String, fallbackName: String = "Shared mask"): Look? = runCatching {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return null
        val body = text.removePrefix(PREFIX)
        val main = body.substringBefore('~')
        val name = body.substringAfter('~', "").ifBlank { fallbackName }
        var i = 0
        fun next(): Int { val v = DIGITS.indexOf(main[i++]); require(v >= 0); return v }
        fun unit() = next() / STEPS.toFloat()
        fun <E : Enum<E>> one(all: List<E>) = all[next().coerceAtMost(all.size - 1)]
        fun pig() = next().coerceAtMost(EmojiMask.PIGMENT_COUNT - 1)
        fun bits() = next() * 36 + next()
        val shape = one(EmojiMask.FaceShape.entries); val width = unit(); val finish = one(EmojiMask.Finish.entries)
        val wood = pig(); val ground = pig(); val hair = pig(); val accent = pig(); val accent2 = pig(); val crestColour = pig()
        val hairline = one(EmojiMask.Hairline.entries); val braids = next() != 0; val cowries = next() != 0
        val crest = one(EmojiMask.Crest.entries); val crestCount = next().coerceIn(1, 9); val crestSize = unit()
        val eyes = one(EmojiMask.EyeStyle.entries); val brows = one(EmojiMask.BrowStyle.entries); val nose = one(EmojiMask.NoseStyle.entries)
        val mouth = one(EmojiMask.MouthStyle.entries); val ears = one(EmojiMask.Ears.entries)
        val marks = bits().let { m -> EmojiMask.Mark.entries.filter { m and (1 shl it.ordinal) != 0 }.toSet() }
        val paint = bits().let { m -> EmojiMask.Paint.entries.filter { m and (1 shl it.ordinal) != 0 }.toSet() }
        val earrings = next() != 0; val beard = next() != 0
        Look(
            name = name, shape = shape, width = width, ground = ground, hair = hair, accent = accent, accent2 = accent2, crestColour = crestColour,
            hairline = hairline, braids = braids, cowries = cowries, crest = crest, crestCount = crestCount, crestSize = crestSize, eyes = eyes,
            brows = brows, nose = nose, mouth = mouth, ears = ears, marks = marks, paint = paint, earrings = earrings, beard = beard,
            finish = finish, wood = wood,
        )
    }.getOrNull()

    // ---- into the game ------------------------------------------------------------------

    /**
     * [look] feeling [face] as a mesh the game's spirits can wear: the
     * painted mask on a gently curved card, one coloured cell per pixel of a
     * [resolution]-pixel painting, clear pixels left out, and a plain wooden
     * back. It stands a model unit tall, facing +y, like every spirit mesh.
     */
    fun card(look: Look, face: EmojiMask.Face, resolution: Int = 72): SpiritMesh {
        val n = resolution
        val px = EmojiMask.image(look, face, n)
        val span = EmojiMask.HALF * 2f
        // The painting spans 3.4 units; a head and crest fill about 2.4 of them, which is to stand one model unit.
        val k = 1f / 2.4f
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val col = ArrayList<Int>(); val idx = ArrayList<Int>()
        val back = EmojiMask.pigment(look.wood).let { (it and 0xFF000000.toInt()) or ((it and 0xFEFEFE) shr 1) }
        val grid = IntArray((n + 1) * (n + 1)) { -1 }
        fun alpha(x: Int, y: Int) = if (x in 0 until n && y in 0 until n) (px[y * n + x] ushr 24) else 0
        fun vertex(gx: Int, gy: Int): Int {
            val key = gy * (n + 1) + gx
            if (grid[key] >= 0) return grid[key]
            val lx = -EmojiMask.HALF + span * gx / n; val ly = EmojiMask.BOTTOM + span * (n - gy) / n
            val x = -lx * k; val z = (ly + 0.25f) * k
            val y = 0.12f * cos(x * 2.6f)
            pos += x; pos += y; pos += z
            nrm += 0f; nrm += 1f; nrm += 0f
            // A vertex takes the colour of the most solid pixel it touches.
            var best = 0; var c = 0
            for (dy in -1..0) for (dx in -1..0) { val a = alpha(gx + dx, gy + dy); if (a > best) { best = a; c = px[(gy + dy) * n + gx + dx] } }
            col += c or 0xFF000000.toInt()
            grid[key] = pos.size / 3 - 1
            return grid[key]
        }
        for (y in 0 until n) for (x in 0 until n) {
            if (alpha(x, y) < 128) continue
            val a = vertex(x, y); val b = vertex(x + 1, y); val c = vertex(x, y + 1); val d = vertex(x + 1, y + 1)
            idx += a; idx += c; idx += b; idx += b; idx += c; idx += d
        }
        // The back: the same cells, turned away, in plain dark wood.
        val front = pos.size / 3
        for (i in 0 until front) { pos += pos[i * 3]; pos += pos[i * 3 + 1] - 0.02f; pos += pos[i * 3 + 2]; nrm += 0f; nrm += -1f; nrm += 0f; col += back }
        val frontTris = idx.size
        for (t in 0 until frontTris step 3) { idx += idx[t] + front; idx += idx[t + 2] + front; idx += idx[t + 1] + front }
        val count = pos.size / 3
        val colors = col.toIntArray()
        return SpiritMesh(
            pos.toFloatArray(), nrm.toFloatArray(), colors, ByteArray(count), colors.copyOf(), idx.toIntArray(),
            floatArrayOf(-0.16f, 0.14f, -0.02f, 0.16f, 0.14f, -0.02f),
            auraColor = EmojiMask.pigment(look.accent), auraSecond = EmojiMask.pigment(look.ground), faceColor = EmojiMask.pigment(look.ground),
        )
    }
}
