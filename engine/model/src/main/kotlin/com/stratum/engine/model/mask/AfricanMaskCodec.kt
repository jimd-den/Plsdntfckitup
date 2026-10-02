package com.stratum.engine.model.mask

import com.stratum.engine.model.mask.AfricanMaskArt.Design
import com.stratum.engine.model.mask.AfricanMaskArt.Dial
import com.stratum.engine.model.mask.AfricanMaskArt.Nudge
import com.stratum.engine.model.mask.AfricanMaskArt.Part
import kotlin.math.roundToInt

/**
 * A mask as a short code a player can copy, paste and send: every choice,
 * colour, dial and hand-placed piece, in one base-36 character each.
 *
 *   am1:<choices>[.<part><dx><dy><scale><angle>...][~<name>]
 *
 * The code is also what the hero wears ([PlayerLoadout.heroMask]): [isCode]
 * tells it from an older sculpted mask's genome code.
 */
object AfricanMaskCodec {
    const val PREFIX = "am1:"

    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val SCHEME = 's'
    private const val OWN = 'p'

    fun isCode(code: String?): Boolean = code != null && code.trim().startsWith(PREFIX)

    fun encode(d: Design): String {
        val b = StringBuilder(PREFIX)
        fun put(n: Int) { b.append(DIGITS[n.coerceIn(0, 35)]) }
        fun unit(v: Float) = put((v.coerceIn(0f, 1f) * AfricanMaskArt.STEPS).roundToInt())
        fun bits(xs: List<Enum<*>>) = put(xs.fold(0) { acc, e -> acc or (1 shl e.ordinal) })
        val own = d.pigments?.takeIf { it.size == 5 }
        if (own == null) { b.append(SCHEME); put(d.scheme) } else { b.append(OWN); own.forEach { put(it) } }
        put(d.silhouette.ordinal); put(d.eyes.ordinal); put(d.brows.ordinal); put(d.nose.ordinal); put(d.mouth.ordinal)
        bits(d.marks); bits(d.paint); bits(d.uli)
        put(d.crown.ordinal); put(d.hanging.ordinal); put(d.sides.ordinal)
        put(if (d.symmetric) 1 else 0)
        unit(d.width); unit(d.motion)
        Dial.entries.forEach { unit(d.dial(it)) }
        // Hand-placed pieces: moves of up to half a face either way, sizes 0.4..2, turns up to a half turn.
        for ((part, n) in d.nudges.toSortedMap()) {
            if (n == Nudge()) continue
            b.append('.'); put(part.ordinal)
            unit((n.dx + 0.5f)); unit((n.dy + 0.5f)); unit((n.scale - 0.4f) / 1.6f); unit(n.angle / Math.PI.toFloat() + 0.5f)
        }
        if (d.name.isNotBlank()) b.append('~').append(d.name.replace('~', '-').take(40))
        return b.toString()
    }

    /** The mask in [code], or null when it isn't one (or is damaged). */
    fun decode(code: String, fallbackName: String = "Shared mask"): Design? = runCatching {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return null
        val body = text.removePrefix(PREFIX)
        val name = body.substringAfter('~', "").ifBlank { fallbackName }
        val parts = body.substringBefore('~').split('.')
        val main = parts[0]
        var i = 0
        fun next(): Int { val v = DIGITS.indexOf(main[i++]); require(v >= 0) { "not a code character" }; return v }
        fun unit() = next() / AfricanMaskArt.STEPS.toFloat()
        fun <E : Enum<E>> one(all: List<E>) = all[next().coerceAtMost(all.size - 1)]
        fun <E : Enum<E>> set(all: List<E>): List<E> { val m = next(); return all.filter { m and (1 shl it.ordinal) != 0 } }
        val kind = main[i++]
        var scheme = 0; var pigments: List<Int>? = null
        when (kind) {
            SCHEME -> scheme = next()
            OWN -> pigments = List(5) { next().coerceAtMost(AfricanMaskArt.PIGMENTS.size - 1) }
            else -> return null
        }
        val silhouette = one(AfricanMaskArt.Silhouette.entries)
        val eyes = one(AfricanMaskArt.Eyes.entries); val brows = one(AfricanMaskArt.Brows.entries)
        val nose = one(AfricanMaskArt.Nose.entries); val mouth = one(AfricanMaskArt.Mouth.entries)
        val marks = set(AfricanMaskArt.Mark.entries); val paint = set(AfricanMaskArt.Paint.entries); val uli = set(AfricanMaskArt.Uli.entries)
        val crown = one(AfricanMaskArt.Crown.entries); val hanging = one(AfricanMaskArt.Hanging.entries); val sides = one(AfricanMaskArt.Sides.entries)
        val symmetric = next() != 0
        val width = unit(); val motion = unit()
        val dials = Dial.entries.associateWith { unit() }.filterValues { it != AfricanMaskArt.quantise(0.5f) }
        val nudges = parts.drop(1).filter { it.length == 5 }.associate { p ->
            fun at(k: Int) = DIGITS.indexOf(p[k]).coerceAtLeast(0) / AfricanMaskArt.STEPS.toFloat()
            Part.entries[DIGITS.indexOf(p[0]).coerceIn(0, Part.entries.size - 1)] to
                Nudge(dx = at(1) - 0.5f, dy = at(2) - 0.5f, scale = 0.4f + at(3) * 1.6f, angle = (at(4) - 0.5f) * Math.PI.toFloat())
        }
        Design(
            name = name, scheme = scheme, silhouette = silhouette, eyes = eyes, brows = brows, nose = nose, mouth = mouth,
            marks = marks, paint = paint, uli = uli, crown = crown, hanging = hanging, sides = sides, symmetric = symmetric,
            width = width, motion = motion, nudges = nudges, pigments = pigments, dials = dials,
        )
    }.getOrNull()

    /** [d] as its code would give it back: dials and nudges on the steps a code holds. */
    fun normalised(d: Design): Design = decode(encode(d), d.name) ?: d
}
