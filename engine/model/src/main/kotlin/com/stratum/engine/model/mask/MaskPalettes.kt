package com.stratum.engine.model.mask

/**
 * One colour scheme for a mask: a handful of flat colours, each with a job.
 *
 * The rule the schemes follow is the fashion plate's, not the paint box's:
 * a large quiet field ([face]), one strong contrasting mass ([crest]), a
 * supporting field ([second]), a single jewel ([accent]) used sparingly, a
 * line colour ([ink]) that reads crisply against the face, and [ivory] for
 * teeth and tusks. No gradients, no noise: every colour is a flat field, and
 * pattern only appears where it is drawn on purpose (uli lines, chevrons,
 * stripes, rings).
 *
 * [back] is the bare wood of the board behind: what the mask shows from the
 * rear or at its edges, like a carving whose paint stops at the front.
 */
data class MaskPalette(
    val name: String,
    val face: String,
    val ink: String,
    val crest: String,
    val second: String,
    val accent: String,
    val ivory: String,
    val back: String = "#3B2A20",
) {
    /** The slots in the order a model's palette lists them. */
    fun colours(): List<String> = listOf(face, ink, crest, second, accent, ivory, back)

    /** True when [face] is light, so lines and slits read dark on it. */
    val lightFace: Boolean get() = luminance(face) > 0.5f

    /** Perceived brightness of a `#RRGGBB` colour, 0 black to 1 white. */
    fun luminance(hex: String): Float {
        val rgb = hex.removePrefix("#").toInt(16)
        return (0.299f * (rgb shr 16 and 255) + 0.587f * (rgb shr 8 and 255) + 0.114f * (rgb and 255)) / 255f
    }
}

/**
 * The curated schemes. Each is a few flat colours with a hard contrast
 * between face and crest and one jewel accent -- the restraint of a 1920s
 * fashion plate: kaolin and lampblack with a flash of vermilion, indigo and
 * ochre with a bead of turquoise, jade and coral picked out in gold.
 *
 * Colours are picked to hold up when the renderer shades them: the darkest
 * faces are never pure black and the lightest never pure white, so a shaded
 * side still reads as the same colour and a lit top never blows out.
 */
object MaskPalettes {
    const val KAOLIN_VERMILION = 0
    const val INDIGO_OCHRE = 1
    const val JADE_CORAL = 2
    const val PLUM_SAFFRON = 3
    const val ONYX = 4
    const val MBARI = 5
    const val TERRACOTTA = 6
    const val KAOLIN_INK = 7
    const val ROSE_MIDNIGHT = 8
    const val LAPIS = 9
    const val EMBER = 10
    const val CAMWOOD = 11
    const val KAOLIN_CAMWOOD = 12
    const val OCHRE = 13
    const val POPPY = 14

    val all: List<MaskPalette> = listOf(
        // The maiden's own: whitened with nzu, lined in lampblack, lips of camwood red.
        MaskPalette("Kaolin & vermilion", face = "#F1EBDF", ink = "#1A171B", crest = "#1A171B", second = "#E0412B", accent = "#E0412B", ivory = "#FBF6EC"),
        MaskPalette("Indigo & ochre", face = "#D99A3C", ink = "#141B3A", crest = "#233A7A", second = "#F2E6CE", accent = "#1FA39A", ivory = "#F7EEDC"),
        MaskPalette("Jade & coral", face = "#2A8A6E", ink = "#F3E7D3", crest = "#123B33", second = "#EE7457", accent = "#D9AE3A", ivory = "#F6EDDC"),
        MaskPalette("Plum & saffron", face = "#F3E6CD", ink = "#3A1834", crest = "#5E2654", second = "#F2A81D", accent = "#F2A81D", ivory = "#FFF7E6"),
        // Okoroshi Ojo: the dark spirit, black and white and one line of gold.
        MaskPalette("Onyx & gold", face = "#1C1B1F", ink = "#F4F0E6", crest = "#F4F0E6", second = "#1C1B1F", accent = "#D2A849", ivory = "#F4F0E6"),
        // The Mbari house: earth-goddess colours in flat fields.
        MaskPalette("Mbari", face = "#F2C53D", ink = "#1B1A1E", crest = "#1E5AA8", second = "#DC3B2C", accent = "#2E8F5A", ivory = "#FFFBF0"),
        MaskPalette("Terracotta & teal", face = "#C8643B", ink = "#231915", crest = "#1F3D40", second = "#F0DEC0", accent = "#35A9A3", ivory = "#F6EBD6"),
        // Okoroshi Oma: the beautiful white spirit, lined in black.
        MaskPalette("Kaolin & ink", face = "#F2EEE6", ink = "#19181C", crest = "#19181C", second = "#F2EEE6", accent = "#19181C", ivory = "#FFFFFF"),
        MaskPalette("Rose & midnight", face = "#E7ABA2", ink = "#1C2140", crest = "#1C2140", second = "#F5EADB", accent = "#CFA43C", ivory = "#FBF3E8"),
        MaskPalette("Lapis & brass", face = "#2C4F9E", ink = "#F1E4C8", crest = "#14204A", second = "#F1E4C8", accent = "#D6A53A", ivory = "#F7EEDC"),
        // Mgbedike: dark, dangerous, one ember of red.
        MaskPalette("Ember", face = "#2C2220", ink = "#F0E3CC", crest = "#7E2A22", second = "#E3B544", accent = "#E4492C", ivory = "#F0E3CC"),
        // The emoji palettes: camwood (uhie) red, kaolin (nzu) white, ochre, dark hair and gold.
        MaskPalette("Camwood", face = "#8E2C2F", ink = "#2A1616", crest = "#3B2320", second = "#E8B33A", accent = "#E9B640", ivory = "#F4EEE3"),
        MaskPalette("Kaolin & camwood", face = "#F4EEE3", ink = "#2A1616", crest = "#3B2320", second = "#8E2C2F", accent = "#E9B640", ivory = "#F4EEE3"),
        MaskPalette("Ochre", face = "#EDB63C", ink = "#2A1616", crest = "#3B2320", second = "#8E2C2F", accent = "#F2CC5C", ivory = "#F4EEE3"),
        MaskPalette("Poppy", face = "#2B1718", ink = "#F4EEE3", crest = "#E2313B", second = "#B81F2B", accent = "#E9B640", ivory = "#F4EEE3"),
    )

    /** Schemes whose face is pale: the maiden's whitened face and its kin. */
    val lightFaced: Array<Int> = arrayOf(KAOLIN_VERMILION, PLUM_SAFFRON, KAOLIN_INK, ROSE_MIDNIGHT, INDIGO_OCHRE, MBARI)

    /** Schemes whose face is dark: Mgbedike and Okoroshi Ojo. */
    val darkFaced: Array<Int> = arrayOf(EMBER, ONYX, LAPIS, JADE_CORAL, TERRACOTTA)

    operator fun get(index: Int): MaskPalette = all[Math.floorMod(index, all.size)]
}
