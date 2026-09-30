package com.stratum.engine.microvoxel.arch

/**
 * What Africa builds with, as microvoxel materials. Each is named for the
 * tradition that made it famous; many traditions share them.
 */
object A {
    /** Sun-dried earth (banco): Djenné, Timbuktu, Kano, the Dogon. */
    const val ADOBE = "arch:adobe"
    const val ADOBE_DARK = "arch:adobe_dark"
    /** The smooth mud render replastered every year at Djenné's Crépissage festival. */
    const val RENDER = "arch:render"
    /** Toron: the palm (ronier) beams left protruding from Sudano-Sahelian walls as permanent scaffolding. */
    const val TORON = "arch:toron"
    /** Burnished red laterite plaster of Asante shrines and Benin palaces. */
    const val RED_POLISH = "arch:red_polish"
    /** Lime plaster of the Swahili coast, whitewashed. */
    const val LIME = "arch:lime"
    /** Dressed coral-stone blocks of Kilwa's Great Mosque and Husuni Kubwa. */
    const val CORAL_BLOCK = "arch:coral_block"
    /** Boriti: mangrove poles spanning Swahili rooms, which set their width. */
    const val MANGROVE_POLE = "arch:mangrove_pole"
    /** Carved hardwood doors of Lamu and Zanzibar. */
    const val CARVED_DOOR = "arch:carved_door"
    /** Dressed granite blocks laid without mortar: Great Zimbabwe, Khami, Thulamela. */
    const val DRYSTONE = "arch:drystone"
    /** The darker courses laid as chevron and dentelle bands on Great Zimbabwe's walls. */
    const val DRYSTONE_DARK = "arch:drystone_dark"
    /** Aksumite masonry and stelae stone. */
    const val AKSUM_STONE = "arch:aksum_stone"
    /** The squared wooden ends ("monkey heads") of Aksumite wall frames. */
    const val AKSUM_TIMBER = "arch:aksum_timber"
    /** Red volcanic tuff, carved down into the rock at Lalibela. */
    const val ROCK_HEWN = "arch:rock_hewn"
    /** Nile mudbrick of Nubian villages, Kerma's deffufa and Meroë. */
    const val MUDBRICK = "arch:mudbrick"
    /** The blue, yellow and white paint of Nubian house fronts. */
    const val NUBIAN_BLUE = "arch:nubian_blue"
    const val NUBIAN_YELLOW = "arch:nubian_yellow"
    /** Pisé (rammed earth) of the Draa and Dades kasbahs and Aït Benhaddou. */
    const val PISE = "arch:pise"
    const val PISE_LIGHT = "arch:pise_light"
    /** Thick millet-stalk thatch: Dogon toguna and granary hats. */
    const val MILLET_THATCH = "arch:millet_thatch"
    /** Ndebele house painting: strong outlined colour fields. */
    const val NDEBELE_BLUE = "arch:ndebele_blue"
    const val NDEBELE_RED = "arch:ndebele_red"
    const val NDEBELE_YELLOW = "arch:ndebele_yellow"
    const val NDEBELE_GREEN = "arch:ndebele_green"
    const val PAINT_BLACK = "arch:paint_black"
    const val PAINT_WHITE = "arch:paint_white"
    /** The red earth wash of Kassena houses at Tiébélé, painted by the women of the compound. */
    const val KASSENA_RED = "arch:kassena_red"
    /** Woven grass of the Zulu iqukwane beehive house. */
    const val GRASS_WEAVE = "arch:grass_weave"
    /** Dung-and-mud plaster of Maasai inkajijik houses. */
    const val DUNG_PLASTER = "arch:dung_plaster"
    /** Thorn-branch fence of a Maasai enkang or a Zulu cattle kraal. */
    const val THORN = "arch:thorn"
    /** Carved hardwood veranda posts (Yoruba) and door frames. */
    const val POST = "arch:post"
    /** Hausa lime-and-mud plaster, and its raised relief (zayyana). */
    const val HAUSA_PLASTER = "arch:hausa_plaster"
    const val HAUSA_RELIEF = "arch:hausa_relief"
    /** Ochre wash. */
    const val OCHRE = "arch:ochre"
    /** Green-glazed tile of Maghrebi towers and fountains. */
    const val ZELLIGE = "arch:zellige"
    /** Palm frond and raffia matting. */
    const val RAFFIA = "arch:raffia"
    /** Musgum teleuk earth: sun-baked, ribbed. */
    const val MUSGUM_EARTH = "arch:musgum_earth"

    internal val table: List<Triple<String, Int, Float>> = listOf(
        Triple(ADOBE, 0xB98A5C, 0.1f),
        Triple(ADOBE_DARK, 0x8C6242, 0.1f),
        Triple(RENDER, 0xC49B6C, 0.05f),
        Triple(TORON, 0x5B3E26, 0.1f),
        Triple(RED_POLISH, 0x8A3622, 0.05f),
        Triple(LIME, 0xF1ECE0, 0.03f),
        Triple(CORAL_BLOCK, 0xD8CBAE, 0.1f),
        Triple(MANGROVE_POLE, 0x4E3A28, 0.08f),
        Triple(CARVED_DOOR, 0x5A3820, 0.08f),
        Triple(DRYSTONE, 0xA99B8E, 0.14f),
        Triple(DRYSTONE_DARK, 0x736960, 0.12f),
        Triple(AKSUM_STONE, 0x9C9484, 0.08f),
        Triple(AKSUM_TIMBER, 0x563B26, 0.08f),
        Triple(ROCK_HEWN, 0x9A5846, 0.1f),
        Triple(MUDBRICK, 0xA87A56, 0.1f),
        Triple(NUBIAN_BLUE, 0x3F7DB6, 0.04f),
        Triple(NUBIAN_YELLOW, 0xDEAE4A, 0.04f),
        Triple(PISE, 0xB5704A, 0.1f),
        Triple(PISE_LIGHT, 0xCB9066, 0.1f),
        Triple(MILLET_THATCH, 0xA48652, 0.18f),
        Triple(NDEBELE_BLUE, 0x2458A6, 0.03f),
        Triple(NDEBELE_RED, 0xB6322A, 0.03f),
        Triple(NDEBELE_YELLOW, 0xE4B22C, 0.03f),
        Triple(NDEBELE_GREEN, 0x2A7E48, 0.03f),
        Triple(PAINT_BLACK, 0x221E1C, 0.03f),
        Triple(PAINT_WHITE, 0xEFEADF, 0.03f),
        Triple(KASSENA_RED, 0x8C3E2A, 0.06f),
        Triple(GRASS_WEAVE, 0xB09A62, 0.16f),
        Triple(DUNG_PLASTER, 0x7C6A54, 0.1f),
        Triple(THORN, 0x5E4E36, 0.22f),
        Triple(POST, 0x684630, 0.08f),
        Triple(HAUSA_PLASTER, 0xC8A47C, 0.06f),
        Triple(HAUSA_RELIEF, 0x97704E, 0.06f),
        Triple(OCHRE, 0xC6852E, 0.06f),
        Triple(ZELLIGE, 0x2E7E6A, 0.05f),
        Triple(RAFFIA, 0x8E8A46, 0.16f),
        Triple(MUSGUM_EARTH, 0xAE7A52, 0.08f),
    )
}

/** The building materials, for [com.stratum.engine.microvoxel.MaterialPalette.standard]. */
object ArchMaterials {
    val all: List<Triple<String, Int, Float>> get() = A.table
}
