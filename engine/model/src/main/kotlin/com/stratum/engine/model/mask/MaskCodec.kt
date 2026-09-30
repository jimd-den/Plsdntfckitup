package com.stratum.engine.model.mask

/**
 * A genome as one line of text, kept in a generated model's tags.
 *
 * A kept mask is a plain [com.stratum.core.domain.micro.MicroModel] -- the
 * world only ever needs its voxels -- but when the studio opens it again the
 * dials should come back as they were, so the player can keep tuning rather
 * than start over. The tag costs a hundred bytes and makes that possible.
 *
 * The format is `key=value` pairs joined by `;`. Unknown keys are ignored and
 * missing keys keep their defaults, so a line written by an older or newer
 * build still opens.
 */
object MaskCodec {
    const val TAG_PREFIX = "mask-genome:"

    fun encode(g: MaskGenome): String = listOf(
        "t" to g.tradition.name, "f" to g.face.name, "w" to f(g.width), "h" to g.height.toString(), "b" to f(g.brow),
        "e" to g.eyes.name, "n" to g.nose.name, "m" to g.mouth.name, "i" to g.ichi.toString(), "k" to g.cheekMarks.toString(),
        "c" to g.crest.name, "ch" to f(g.crestHeight), "cc" to g.crestCount.toString(), "ea" to g.ears.name,
        "tu" to (if (g.tusks) "1" else "0"), "be" to g.beard.name, "p" to g.palette.toString(), "s" to (if (g.symmetric) "1" else "0"),
        "o" to f(g.ornament), "r" to f(g.relief), "fe" to f(g.features),
        "hs" to g.hair.name, "fp" to g.paint.name, "er" to g.earrings.name,
        "ht" to (if (g.hat) "1" else "0"), "sh" to (if (g.shades) "1" else "0"), "cn" to (if (g.chain) "1" else "0"), "fl" to (if (g.flower) "1" else "0"),
    ).joinToString(";") { (k, v) -> "$k=$v" }

    /** The genome in [text], or null when it is not one. [name] is carried separately: names are free text. */
    fun decode(text: String, name: String = "Mask"): MaskGenome? {
        val map = text.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        if (map.isEmpty()) return null
        val d = MaskGenome()
        return runCatching {
            MaskGenome(
                name = name,
                tradition = enumOr(map["t"], d.tradition), face = enumOr(map["f"], d.face),
                width = map["w"]?.toFloatOrNull() ?: d.width,
                height = (map["h"]?.toIntOrNull() ?: d.height).coerceIn(MaskGenome.MIN_HEIGHT, MaskGenome.MAX_HEIGHT),
                brow = map["b"]?.toFloatOrNull() ?: d.brow,
                eyes = enumOr(map["e"], d.eyes), nose = enumOr(map["n"], d.nose), mouth = enumOr(map["m"], d.mouth),
                ichi = map["i"]?.toIntOrNull() ?: d.ichi, cheekMarks = map["k"]?.toIntOrNull() ?: d.cheekMarks,
                crest = enumOr(map["c"], d.crest), crestHeight = map["ch"]?.toFloatOrNull() ?: d.crestHeight,
                crestCount = map["cc"]?.toIntOrNull() ?: d.crestCount, ears = enumOr(map["ea"], d.ears),
                tusks = map["tu"] == "1", beard = enumOr(map["be"], d.beard), palette = map["p"]?.toIntOrNull() ?: d.palette,
                symmetric = map["s"] != "0", ornament = map["o"]?.toFloatOrNull() ?: d.ornament,
                relief = map["r"]?.toFloatOrNull() ?: d.relief, features = map["fe"]?.toFloatOrNull() ?: d.features,
                hair = enumOr(map["hs"], d.hair), paint = enumOr(map["fp"], d.paint), earrings = enumOr(map["er"], d.earrings),
                hat = map["ht"] == "1", shades = map["sh"] == "1", chain = map["cn"] == "1", flower = map["fl"] == "1",
            ).normalised()
        }.getOrNull()
    }

    /** The genome a generated model was carved from, read back from its tags. */
    fun fromTags(tags: List<String>, name: String): MaskGenome? =
        tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.let { decode(it.removePrefix(TAG_PREFIX), name) }

    private fun f(v: Float): String = "%.3f".format(java.util.Locale.ROOT, v)

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
