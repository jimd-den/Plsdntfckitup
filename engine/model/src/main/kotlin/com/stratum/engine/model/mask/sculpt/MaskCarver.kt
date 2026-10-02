package com.stratum.engine.model.mask.sculpt

import com.stratum.engine.model.mask.AfricanMaskArt
import com.stratum.engine.model.mask.sculpt.Anatomy.Part
import java.math.BigInteger
import java.util.Base64
import kotlin.random.Random

/**
 * Everything a player can set on a carved mask, and the tools to set it.
 *
 * [controls] names every choice in a [MaskSpec]: its form and proportions, each
 * feature, the marks and paint, the coiffure, the horns and crest, the raffia
 * and beads, the wood, finish, pigments and eye light, and every
 * [Anatomy.Dial]. An editor only has to show the catalogue; nothing on the
 * mask is out of reach.
 *
 * On top of it are the maker's tools:
 * - [roll] and [variations], which keep what is locked;
 * - [encode] and [decode], a share code that carries the whole mask;
 * - [designs], which counts how many different masks there are.
 */
object MaskCarver {

    /** One thing that can be set on a mask. */
    sealed class Control(val id: String, val label: String, val part: Part) {
        /** How many distinct settings it has: a slider counts the steps a share code keeps. */
        abstract val settings: BigInteger

        /** One of several named options. */
        class Pick(id: String, label: String, part: Part, val options: List<String>, val get: (MaskSpec) -> Int, val set: (MaskSpec, Int) -> MaskSpec) : Control(id, label, part) {
            override val settings: BigInteger get() = BigInteger.valueOf(options.size.toLong())
        }

        /** Any combination of named marks: scars, patterns, adornments. */
        class Flags(id: String, label: String, part: Part, val options: List<String>, val get: (MaskSpec) -> Set<Int>, val set: (MaskSpec, Set<Int>) -> MaskSpec) : Control(id, label, part) {
            override val settings: BigInteger get() = BigInteger.TWO.pow(options.size)
        }

        /** A colour from a palette: pigments, or eye light. */
        class Colour(id: String, label: String, part: Part, val options: List<Pair<String, Int>>, val get: (MaskSpec) -> Int, val set: (MaskSpec, Int) -> MaskSpec) : Control(id, label, part) {
            override val settings: BigInteger get() = BigInteger.valueOf(options.size.toLong())
        }

        /** A continuous proportion, 0..1. */
        class Slider(id: String, label: String, part: Part, val get: (MaskSpec) -> Float, val set: (MaskSpec, Float) -> MaskSpec) : Control(id, label, part) {
            override val settings: BigInteger get() = BigInteger.valueOf(STEPS.toLong())
        }

        /** Copies this control's setting from [from] onto [to]. */
        fun carry(from: MaskSpec, to: MaskSpec): MaskSpec = when (this) {
            is Pick -> set(to, get(from))
            is Flags -> set(to, get(from))
            is Colour -> set(to, get(from))
            is Slider -> set(to, get(from))
        }
    }

    /** The eye lights a mask can burn with. Any ARGB travels in a code; these are the named ones. */
    val GLOWS: List<Pair<String, Int>> = listOf(
        "Ember" to 0xFFFFA23A.toInt(), "Blood" to 0xFFFF4A26.toInt(), "Moon" to 0xFFBFE4FF.toInt(), "Gold" to 0xFFFFD36A.toInt(),
        "Ghost green" to 0xFF8CFFB0.toInt(), "Violet" to 0xFFC08CFF.toInt(), "White flame" to 0xFFFFF6E8.toInt(), "Blue fire" to 0xFF5AB4FF.toInt(),
    )

    private val PIGMENTS: List<Pair<String, Int>> = AfricanMaskArt.PIGMENTS.map { it.name to it.rgb }
    private val DYES: List<Pair<String, Int>> = listOf("Natural straw" to 0xFFD9B26A.toInt()) + PIGMENTS

    /** Steps a slider keeps in a share code. */
    const val STEPS = 256

    private inline fun <reified E : Enum<E>> pick(id: String, label: String, part: Part, entries: List<E>, crossinline name: (E) -> String, crossinline get: (MaskSpec) -> E, crossinline set: (MaskSpec, E) -> MaskSpec) =
        Control.Pick(id, label, part, entries.map { name(it) }, { s -> get(s).ordinal }, { s, i -> set(s, entries[i.coerceIn(0, entries.size - 1)]) })

    private inline fun <reified E : Enum<E>> flags(id: String, label: String, part: Part, entries: List<E>, crossinline name: (E) -> String, crossinline get: (MaskSpec) -> Set<E>, crossinline set: (MaskSpec, Set<E>) -> MaskSpec) =
        Control.Flags(id, label, part, entries.map { name(it) }, { s -> get(s).map { it.ordinal }.toSet() }, { s, xs -> set(s, xs.filter { it in entries.indices }.map { entries[it] }.toSet()) })

    private fun pigment(id: String, label: String, part: Part, get: (MaskSpec) -> Int, set: (MaskSpec, Int) -> MaskSpec) =
        Control.Colour(id, label, part, PIGMENTS, get, { s, i -> set(s, i.coerceIn(0, PIGMENTS.size - 1)) })

    /** Every setting of a mask, grouped by the part it shapes. */
    val controls: List<Control> = buildList {
        add(pick("form", "Worn as", Part.FACE, Anatomy.Form.entries, { it.label }, { it.form }, { s, v -> s.copy(form = v) }))
        add(pick("outline", "Outline", Part.FACE, Anatomy.Outline.entries, { it.label }, { it.outline }, { s, v -> s.copy(outline = v) }))
        add(Control.Slider("width", "Width", Part.FACE, { it.width }, { s, v -> s.copy(width = v.coerceIn(0f, 1f)) }))
        add(Control.Slider("length", "Length", Part.FACE, { it.length }, { s, v -> s.copy(length = v.coerceIn(0f, 1f)) }))
        add(pick("eyes", "Eyes", Part.EYES, Anatomy.Eyes.entries, { it.label }, { it.eyes }, { s, v -> s.copy(eyes = v) }))
        add(Control.Colour("glow", "Eye light", Part.EYES, GLOWS, { s -> GLOWS.indexOfFirst { it.second == s.glow }.coerceAtLeast(0) }, { s, i -> s.copy(glow = GLOWS[i.coerceIn(0, GLOWS.size - 1)].second) }))
        add(pick("nose", "Nose", Part.NOSE, Anatomy.Nose.entries, { it.label }, { it.nose }, { s, v -> s.copy(nose = v) }))
        add(pick("mouth", "Mouth", Part.MOUTH, Anatomy.Mouth.entries, { it.label }, { it.mouth }, { s, v -> s.copy(mouth = v) }))
        add(pick("brow", "Brow", Part.BROW_EARS, Anatomy.Brow.entries, { it.label }, { it.brow }, { s, v -> s.copy(brow = v) }))
        add(pick("ears", "Ears", Part.BROW_EARS, Anatomy.Ears.entries, { it.label }, { it.ears }, { s, v -> s.copy(ears = v) }))
        add(flags("scars", "Carved marks", Part.MARKS, Anatomy.Scar.entries, { it.label }, { it.scars }, { s, v -> s.copy(scars = v) }))
        add(flags("patterns", "Painted patterns", Part.MARKS, Anatomy.Pattern.entries, { it.label }, { it.patterns }, { s, v -> s.copy(patterns = v) }))
        add(pigment("accent", "Paint", Part.MARKS, { it.accent }, { s, v -> s.copy(accent = v) }))
        add(pigment("accent2", "Second paint", Part.MARKS, { it.accent2 }, { s, v -> s.copy(accent2 = v) }))
        add(pick("coiffure", "Coiffure", Part.HAIR, Anatomy.Coiffure.entries, { it.label }, { it.coiffure }, { s, v -> s.copy(coiffure = v) }))
        add(pigment("hair", "Hair colour", Part.HAIR, { it.hair }, { s, v -> s.copy(hair = v) }))
        add(pick("crown", "Horns & crest", Part.HORNS, Anatomy.Crown.entries, { it.label }, { it.crown }, { s, v -> s.copy(crown = v) }))
        add(Control.Slider("crownSize", "Height", Part.HORNS, { it.crownSize }, { s, v -> s.copy(crownSize = v.coerceIn(0f, 1f)) }))
        add(Control.Pick("count", "How many", Part.HORNS, (1..6).map { "$it" }, { s -> (s.count - 1).coerceIn(0, 5) }, { s, i -> s.copy(count = i.coerceIn(0, 5) + 1) }))
        add(pigment("hornColour", "Horn colour", Part.HORNS, { it.hornColour }, { s, v -> s.copy(hornColour = v) }))
        add(pick("beard", "Beard", Part.RAFFIA, Anatomy.Beard.entries, { it.label }, { it.beard }, { s, v -> s.copy(beard = v) }))
        add(Control.Slider("raffia", "Fullness", Part.RAFFIA, { it.raffia }, { s, v -> s.copy(raffia = v.coerceIn(0f, 1f)) }))
        add(Control.Colour("raffiaDye", "Dye", Part.RAFFIA, DYES, { s -> s.raffiaDye + 1 }, { s, i -> s.copy(raffiaDye = i.coerceIn(0, DYES.size - 1) - 1) }))
        add(flags("adorn", "Fastened on", Part.ADORN, Anatomy.Adorn.entries, { it.label }, { it.adorn }, { s, v -> s.copy(adorn = v) }))
        add(pigment("beads", "Beads", Part.ADORN, { it.beads }, { s, v -> s.copy(beads = v) }))
        add(pick("finish", "Finish", Part.SURFACE, Anatomy.Finish.entries, { it.label }, { it.finish }, { s, v -> s.copy(finish = v) }))
        add(pigment("wood", "Wood", Part.SURFACE, { it.wood }, { s, v -> s.copy(wood = v) }))
        for (d in Anatomy.Dial.entries) add(Control.Slider("dial." + d.name.lowercase(), d.label, d.part, { it.dial(d) }, { s, v -> s.withDial(d, v) }))
    }

    fun control(id: String): Control? = controls.firstOrNull { it.id == id }

    fun controls(part: Part): List<Control> = controls.filter { it.part == part }

    /**
     * How many different masks can be made. [sliders] false counts only the
     * named choices, the part and mark and colour pickers; true also counts
     * every step of every proportion a share code keeps.
     */
    fun designs(sliders: Boolean = false): BigInteger =
        controls.filter { sliders || it !is Control.Slider }.fold(BigInteger.ONE) { n, c -> n * c.settings }

    // ---- making --------------------------------------------------------------------------------

    /**
     * A new mask from [seed]: of [tradition], or any, keeping every [locked]
     * part as it is on [from].
     */
    fun roll(seed: Long, tradition: Tradition? = null, locked: Set<Part> = emptySet(), from: MaskSpec? = null, name: String? = null): MaskSpec {
        val r = Random(seed)
        val t = tradition ?: MaskCulture.traditions[r.nextInt(MaskCulture.traditions.size)]
        var spec = if (tradition == null && r.nextFloat() < 0.25f) {
            val other = MaskCulture.traditions[r.nextInt(MaskCulture.traditions.size)]
            MaskCulture.blend(t, other, seed)
        } else MaskCulture.generate(t, seed)
        if (name != null) spec = spec.copy(name = name)
        if (from != null) for (c in controls) if (c.part in locked) spec = c.carry(from, spec)
        return spec
    }

    /** [count] masks near [spec], each with one or two unlocked parts carved afresh. */
    fun variations(spec: MaskSpec, seed: Long, count: Int, locked: Set<Part> = emptySet()): List<MaskSpec> {
        val r = Random(seed)
        val open = Part.entries.filter { it !in locked }
        if (open.isEmpty()) return emptyList()
        val home = MaskCulture.traditions.firstOrNull { it.id == spec.tradition.substringBefore('+') }
        return List(count) { i ->
            // Mostly the same people's carving; sometimes a part borrowed from another.
            val source = if (home != null && r.nextFloat() < 0.7f) home else MaskCulture.traditions[r.nextInt(MaskCulture.traditions.size)]
            val fresh = MaskCulture.generate(source, seed * 131 + i)
            val parts = open.shuffled(r).take(1 + r.nextInt(2)).toSet()
            controls.filter { it.part in parts }.fold(spec) { s, c -> c.carry(fresh, s) }
        }
    }

    /** [part] alone carved afresh from [seed]. */
    fun reroll(spec: MaskSpec, part: Part, seed: Long): MaskSpec {
        val home = MaskCulture.traditions.firstOrNull { it.id == spec.tradition.substringBefore('+') } ?: MaskCulture.traditions[Math.floorMod(seed, MaskCulture.traditions.size.toLong()).toInt()]
        val fresh = MaskCulture.generate(home, seed)
        return controls(part).fold(spec) { s, c -> c.carry(fresh, s) }
    }

    // ---- share codes ----------------------------------------------------------------------------

    const val PREFIX = "sm1:"

    fun isCode(text: String?): Boolean = text != null && text.trim().startsWith(PREFIX)

    /** The whole mask as a short code: every choice, colour, dial and its seed. */
    fun encode(spec: MaskSpec): String {
        val out = java.io.ByteArrayOutputStream()
        fun b(v: Int) = out.write(v and 0xFF)
        fun str(t: String) { val bytes = t.toByteArray(Charsets.UTF_8).take(255); b(bytes.size); bytes.forEach { b(it.toInt()) } }
        fun unit(v: Float) = b((v.coerceIn(0f, 1f) * (STEPS - 1) + 0.5f).toInt())
        fun mask(xs: Set<Int>) { val m = xs.fold(0) { a, i -> a or (1 shl i) }; b(m); b(m shr 8) }
        str(spec.name); str(spec.tradition)
        b(spec.form.ordinal); b(spec.outline.ordinal); unit(spec.width); unit(spec.length)
        b(spec.brow.ordinal); b(spec.eyes.ordinal); b(spec.nose.ordinal); b(spec.mouth.ordinal); b(spec.ears.ordinal)
        mask(spec.scars.map { it.ordinal }.toSet())
        b(spec.coiffure.ordinal); b(spec.crown.ordinal); unit(spec.crownSize); b(spec.count)
        b(spec.beard.ordinal); mask(spec.adorn.map { it.ordinal }.toSet()); b(spec.finish.ordinal); mask(spec.patterns.map { it.ordinal }.toSet())
        b(spec.wood); b(spec.hair); b(spec.accent); b(spec.accent2); b(spec.hornColour)
        b(spec.glow shr 16); b(spec.glow shr 8); b(spec.glow)
        unit(spec.raffia); b(spec.raffiaDye); b(spec.beads)
        b(Anatomy.Dial.entries.size); for (d in Anatomy.Dial.entries) unit(spec.dial(d))
        for (k in 7 downTo 0) b((spec.seed shr (k * 8)).toInt())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The mask in a share code, or null when it isn't one. */
    fun decode(code: String): MaskSpec? = runCatching {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return null
        val bytes = Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
        var at = 0
        fun b(): Int = bytes[at++].toInt() and 0xFF
        fun str(): String { val n = b(); val t = String(bytes, at, n, Charsets.UTF_8); at += n; return t }
        fun unit(): Float = b() / (STEPS - 1f)
        fun mask(): Int = b() or (b() shl 8)
        fun <E> set(entries: List<E>, m: Int): Set<E> = entries.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet()
        fun <E> one(entries: List<E>, i: Int): E = entries[i.coerceIn(0, entries.size - 1)]
        val name = str(); val tradition = str()
        val form = one(Anatomy.Form.entries, b()); val outline = one(Anatomy.Outline.entries, b()); val width = unit(); val length = unit()
        val brow = one(Anatomy.Brow.entries, b()); val eyes = one(Anatomy.Eyes.entries, b()); val nose = one(Anatomy.Nose.entries, b())
        val mouth = one(Anatomy.Mouth.entries, b()); val ears = one(Anatomy.Ears.entries, b())
        val scars = set(Anatomy.Scar.entries, mask())
        val coiffure = one(Anatomy.Coiffure.entries, b()); val crown = one(Anatomy.Crown.entries, b()); val crownSize = unit(); val count = b()
        val beard = one(Anatomy.Beard.entries, b()); val adorn = set(Anatomy.Adorn.entries, mask()); val finish = one(Anatomy.Finish.entries, b())
        val patterns = set(Anatomy.Pattern.entries, mask())
        val wood = b(); val hair = b(); val accent = b(); val accent2 = b(); val horn = b()
        val glow = (0xFF shl 24) or (b() shl 16) or (b() shl 8) or b()
        val raffia = unit(); val dye = bytes[at++].toInt(); val beads = b()
        val n = b()
        val dials = HashMap<Anatomy.Dial, Float>()
        for (i in 0 until n) { val v = unit(); Anatomy.Dial.entries.getOrNull(i)?.let { dials[it] = v } }
        var seed = 0L
        repeat(8) { seed = (seed shl 8) or b().toLong() }
        MaskSpec(
            name = name, tradition = tradition, form = form, outline = outline, width = width, length = length,
            brow = brow, eyes = eyes, nose = nose, mouth = mouth, ears = ears, scars = scars, coiffure = coiffure, crown = crown,
            crownSize = crownSize, count = count.coerceIn(1, 6), beard = beard, adorn = adorn, finish = finish, patterns = patterns,
            wood = wood, hair = hair, accent = accent, accent2 = accent2, hornColour = horn, glow = glow, raffia = raffia,
            raffiaDye = dye.coerceIn(MaskSpec.NATURAL, PIGMENTS.size - 1), beads = beads, dials = dials, seed = seed,
        )
    }.getOrNull()
}
