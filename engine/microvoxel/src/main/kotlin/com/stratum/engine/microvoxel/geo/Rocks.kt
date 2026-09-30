package com.stratum.engine.microvoxel.geo

/**
 * Africa's rocks and regoliths as microvoxel materials.
 *
 * Colours are taken from how these rocks look weathered, in the field, in
 * daylight: the colour a traveller sees, not the colour of a fresh hand
 * specimen. Names are the ids other code refers to (see [R]).
 */
object R {
    // ---- Regolith: what weathering leaves on top ------------------------------------
    /** Ferricrete / laterite duricrust: the iron-cemented cap of West and Central African plateaus ("bowal" in Guinea). */
    const val LATERITE_CRUST = "geo:laterite_crust"
    /** Loose red lateritic gravel and soil below and around the crust. */
    const val LATERITE = "geo:laterite"
    /** The pale, mottled kaolinitic clay under a laterite: red and white streaks. */
    const val MOTTLED_CLAY = "geo:mottled_clay"
    /** Saprolite: rock rotted in place, still showing its grain. */
    const val SAPROLITE = "geo:saprolite"
    /** Deep red oxisol / ferralsol of the Congo basin and Guinean forest. */
    const val FERRALSOL = "geo:ferralsol"
    /** Black cotton soil (vertisol) of the Sudd, the Gezira and the Accra plains: cracks when dry. */
    const val VERTISOL = "geo:vertisol"
    /** Dark volcanic andosol of Kilimanjaro's and Mount Cameroon's slopes: the richest soil on the continent. */
    const val ANDOSOL = "geo:andosol"
    /** Calcrete: the white lime crust of the Kalahari and Karoo. */
    const val CALCRETE = "geo:calcrete"
    /** Gypsum crust (desert rose country), Tunisia and the Namib. */
    const val GYPCRETE = "geo:gypcrete"
    /** Salt crust of a pan or chott: Makgadikgadi, Etosha, Chott el Djerid, Dallol. */
    const val SALT = "geo:salt"
    /** Trona and natron crust of a soda lake shore: Natron, Magadi. */
    const val TRONA = "geo:trona"
    /** The yellow sulphur and potash crusts of Dallol in the Danakil. */
    const val SULPHUR = "geo:sulphur"
    /** Kalahari sand: iron-stained red. */
    const val KALAHARI_SAND = "geo:kalahari_sand"
    /** Saharan erg sand: gold to apricot. */
    const val ERG_SAND = "geo:erg_sand"
    /** Namib sand: deep orange-red from its iron coating, reddest furthest from the sea. */
    const val NAMIB_SAND = "geo:namib_sand"
    /** White coral sand of the Swahili coast and Madagascar. */
    const val CORAL_SAND = "geo:coral_sand"
    /** Reg: the gravel desert floor (Tanezrouft, the Ténéré's edge). */
    const val REG_GRAVEL = "geo:reg_gravel"
    /** Desert varnish: the black manganese skin on hamada stones. */
    const val DESERT_VARNISH = "geo:desert_varnish"
    /** River and delta mud; mangrove mud of the Niger delta and the Rufiji. */
    const val DELTA_MUD = "geo:delta_mud"
    /** Diatomite and lake-bed silts left by the Sahara's and the Rift's old lakes. */
    const val DIATOMITE = "geo:diatomite"
    /** Termite-mound clay: fine subsoil brought up and cemented. */
    const val TERMITE_CLAY = "geo:termite_clay"
    /** Scree and talus under a cliff. */
    const val TALUS = "geo:talus"

    // ---- Basement: the old cratons ----------------------------------------------------
    /** Pink-grey granite of inselbergs: Idanre, Olumo, Zuma Rock, Matobo, Spitzkoppe. */
    const val GRANITE = "geo:granite"
    /** Granite's weathered, lichen-dark skin. */
    const val GRANITE_WEATHERED = "geo:granite_weathered"
    /** Banded gneiss of the basement complex. */
    const val GNEISS = "geo:gneiss"
    const val GNEISS_DARK = "geo:gneiss_dark"
    /** Charnockite: the dark greenish granite of Idanre and Ado-Ekiti. */
    const val CHARNOCKITE = "geo:charnockite"
    /** Quartzite ridges: the Magaliesberg, the Barberton belt, Nigeria's Iseyin hills. */
    const val QUARTZITE = "geo:quartzite"
    /** Banded iron formation of the Transvaal and Mauritania's Kediet ej Jill. */
    const val BIF = "geo:banded_iron"
    const val BIF_RED = "geo:banded_iron_red"
    /** Greenstone and schist of the old belts. */
    const val SCHIST = "geo:schist"

    // ---- Sedimentary cover ------------------------------------------------------------
    /** Red sandstone: Bandiagara, the Ennedi, the Atlas gorges. */
    const val SANDSTONE_RED = "geo:sandstone_red"
    /** Buff and golden sandstone: the Clarens formation, Tassili n'Ajjer. */
    const val SANDSTONE_BUFF = "geo:sandstone_buff"
    /** Pale, almost white sandstone: Table Mountain, the Nubian sandstone. */
    const val SANDSTONE_PALE = "geo:sandstone_pale"
    /** Karoo shale and mudstone, grey-green to purple. */
    const val SHALE = "geo:shale"
    const val MUDSTONE = "geo:mudstone"
    /** Pale limestone: the Atlas, the Egyptian plateau, the tsingy. */
    const val LIMESTONE = "geo:limestone"
    /** Tsingy's weathered grey limestone, razor-edged. */
    const val LIMESTONE_GREY = "geo:limestone_grey"
    /** White chalk of the Western Desert (the White Desert near Farafra). */
    const val CHALK = "geo:chalk"
    /** Coral rag: the fossil reef limestone of Kilwa, Lamu, Zanzibar and Mombasa. */
    const val CORAL_RAG = "geo:coral_rag"
    /** Ironstone and conglomerate of the Nubian and Nanka sands. */
    const val IRONSTONE = "geo:ironstone"
    /** The friable red-and-white sands of Nanka and Agulu, cut by gullies in south-east Nigeria. */
    const val NANKA_SAND = "geo:nanka_sand"

    // ---- Volcanic -----------------------------------------------------------------------
    /** Basalt: the Ethiopian traps, the Drakensberg lavas, Mount Cameroon. */
    const val BASALT = "geo:basalt"
    /** Basalt's weathered brown-grey skin and flow tops. */
    const val BASALT_WEATHERED = "geo:basalt_weathered"
    /** Red oxidised horizons between flows: the "red boles" of the Ethiopian traps. */
    const val RED_BOLE = "geo:red_bole"
    /** Dolerite: the dark sills and dykes that cap the Karoo's flat-topped hills. */
    const val DOLERITE = "geo:dolerite"
    /** Trachyte and phonolite of the Kenyan rift and the Cameroon line's necks (Rhumsiki). */
    const val PHONOLITE = "geo:phonolite"
    /** Welded tuff and ignimbrite of the rift shoulders. */
    const val TUFF = "geo:tuff"
    /** Black cinder and scoria of young cones. */
    const val SCORIA = "geo:scoria"
    /** Red scoria, oxidised: the colour of Kilimanjaro's parasitic cones. */
    const val SCORIA_RED = "geo:scoria_red"
    /** Natrocarbonatite: Ol Doinyo Lengai's lava, black when erupted, white within days. */
    const val CARBONATITE = "geo:carbonatite"

    // ---- Added with the simulated processes and the richer strata ---------------------
    // Registered after everything else (see GeoMaterials.added), so no older id moves.

    /** Alluvial fan gravel and sand, spread where a steep stream meets flat ground: the bajadas under the Atlas and the rift shoulders. */
    const val ALLUVIUM = "geo:alluvium"
    /** Floodplain silt: the dark, fertile overbank mud every African farming river leaves, from the Niger's fadama to the Nile's. */
    const val SILT = "geo:silt"
    /** A river's bed of rounded gravel and coarse sand, and a dry wadi's floor. */
    const val RIVER_GRAVEL = "geo:river_gravel"
    /** Milky vein quartz, filling the cracks of the old basement: the reefs prospectors followed. */
    const val QUARTZ = "geo:quartz"
    /** Gold-bearing quartz reef, rusty with sulphides: the Ashanti belt (Obuasi), the Barberton greenstones, the Zimbabwe craton. */
    const val GOLD_REEF = "geo:gold_reef"
    /** Malachite: green copper ore of the Central African Copperbelt (Katanga and Zambia). */
    const val MALACHITE = "geo:malachite"
    /** Bauxite: the pink-cream aluminium ore under the bowal of Guinea's Boké and Fouta Djallon. */
    const val BAUXITE = "geo:bauxite"
    /** Shelly limestone packed with fossils: the ammonite beds of Erfoud, the whale-bearing Eocene of Wadi Al-Hitan. */
    const val FOSSIL_LIMESTONE = "geo:fossil_limestone"
    /** A Karoo bone bed: mudstone full of the fossil reptiles the Beaufort group is famous for. */
    const val BONE_BED = "geo:bone_bed"
    /** Migmatite: half-melted gneiss swirled with pink granite veins, the deepest basement of the Man and Zimbabwe shields. */
    const val MIGMATITE = "geo:migmatite"
    /** Greenstone: the dark green metavolcanics of the Archaean belts (Barberton, Bulawayo), where the gold is. */
    const val GREENSTONE = "geo:greenstone"
    /** Conglomerate: the pebbly bed laid on an old erosion surface, the first rock over an unconformity. */
    const val CONGLOMERATE = "geo:conglomerate"
    /** Grey Nama limestone and dolomite of the Fish River Canyon's rim. */
    const val DOLOMITE = "geo:dolomite"
    /** Pale grey coastal dune sand of the Maputaland and Wild Coast cordons. */
    const val DUNE_SAND = "geo:dune_sand"
    /** Lagoon mud: grey-black, organic, behind the barrier beaches of Lagos, Ébrié and Keta. */
    const val LAGOON_MUD = "geo:lagoon_mud"

    /** Surfaces nothing grows on: salt, loose dune sand, bare rock. */
    val BARE: Set<String> = setOf(
        SALT, TRONA, SULPHUR, ERG_SAND, NAMIB_SAND, CORAL_SAND, DESERT_VARNISH, TALUS, DIATOMITE, GYPCRETE,
        GRANITE, GRANITE_WEATHERED, GNEISS, GNEISS_DARK, CHARNOCKITE, QUARTZITE, BIF, BIF_RED, SCHIST,
        SANDSTONE_RED, SANDSTONE_BUFF, SANDSTONE_PALE, SHALE, MUDSTONE, LIMESTONE, LIMESTONE_GREY, CHALK, CORAL_RAG,
        IRONSTONE, BASALT, BASALT_WEATHERED, RED_BOLE, DOLERITE, PHONOLITE, TUFF, SCORIA, SCORIA_RED, CARBONATITE,
        LATERITE_CRUST, DELTA_MUD,
        RIVER_GRAVEL, QUARTZ, GOLD_REEF, MALACHITE, BAUXITE, FOSSIL_LIMESTONE, BONE_BED, MIGMATITE, GREENSTONE,
        CONGLOMERATE, DOLOMITE, DUNE_SAND, LAGOON_MUD,
    )

    /** Surfaces strewn with loose stones. */
    val PEBBLY: Set<String> = setOf(REG_GRAVEL, TALUS, LATERITE_CRUST, ALLUVIUM, RIVER_GRAVEL)

    /** Name, colour, jitter: registration data, in a fixed order so ids never move. */
    internal val table: List<Triple<String, Int, Float>> = listOf(
        Triple(LATERITE_CRUST, 0x7A3822, 0.16f),
        Triple(LATERITE, 0xA2502E, 0.14f),
        Triple(MOTTLED_CLAY, 0xC99478, 0.18f),
        Triple(SAPROLITE, 0xB48A6A, 0.14f),
        Triple(FERRALSOL, 0x9C4028, 0.1f),
        Triple(VERTISOL, 0x564B40, 0.1f),
        Triple(ANDOSOL, 0x3E3026, 0.1f),
        Triple(CALCRETE, 0xD9CCAA, 0.1f),
        Triple(GYPCRETE, 0xE4D8C0, 0.08f),
        Triple(SALT, 0xF1EEE6, 0.04f),
        Triple(TRONA, 0xEBD6CA, 0.07f),
        Triple(SULPHUR, 0xD8C83A, 0.14f),
        Triple(KALAHARI_SAND, 0xC06A34, 0.08f),
        Triple(ERG_SAND, 0xE2AC6A, 0.06f),
        Triple(NAMIB_SAND, 0xCC6232, 0.07f),
        Triple(CORAL_SAND, 0xF0E8D4, 0.05f),
        Triple(REG_GRAVEL, 0x9E7E5E, 0.16f),
        Triple(DESERT_VARNISH, 0x4A3326, 0.12f),
        Triple(DELTA_MUD, 0x4E4436, 0.08f),
        Triple(DIATOMITE, 0xE6E0D2, 0.06f),
        Triple(TERMITE_CLAY, 0xB2643C, 0.12f),
        Triple(TALUS, 0x8A7C6C, 0.2f),
        Triple(GRANITE, 0xB6A69A, 0.1f),
        Triple(GRANITE_WEATHERED, 0x8C8078, 0.14f),
        Triple(GNEISS, 0xA0968E, 0.1f),
        Triple(GNEISS_DARK, 0x6A625C, 0.1f),
        Triple(CHARNOCKITE, 0x5E6258, 0.1f),
        Triple(QUARTZITE, 0xE0D6CC, 0.08f),
        Triple(BIF, 0x4A4046, 0.08f),
        Triple(BIF_RED, 0x8E3428, 0.1f),
        Triple(SCHIST, 0x6E7466, 0.12f),
        Triple(SANDSTONE_RED, 0xB0603C, 0.1f),
        Triple(SANDSTONE_BUFF, 0xCFA164, 0.1f),
        Triple(SANDSTONE_PALE, 0xDDCBA6, 0.08f),
        Triple(SHALE, 0x6C6A5C, 0.1f),
        Triple(MUDSTONE, 0x8A6658, 0.1f),
        Triple(LIMESTONE, 0xD6CDB8, 0.08f),
        Triple(LIMESTONE_GREY, 0x8E8C88, 0.14f),
        Triple(CHALK, 0xF2EEE2, 0.05f),
        Triple(CORAL_RAG, 0xDCCFB2, 0.16f),
        Triple(IRONSTONE, 0x6A3A26, 0.14f),
        Triple(NANKA_SAND, 0xD8A07C, 0.14f),
        Triple(BASALT, 0x3B3A38, 0.08f),
        Triple(BASALT_WEATHERED, 0x5C5048, 0.12f),
        Triple(RED_BOLE, 0x7E3A2A, 0.1f),
        Triple(DOLERITE, 0x2E3133, 0.08f),
        Triple(PHONOLITE, 0x747066, 0.1f),
        Triple(TUFF, 0xB9A688, 0.12f),
        Triple(SCORIA, 0x2A2524, 0.18f),
        Triple(SCORIA_RED, 0x7A3226, 0.18f),
        Triple(CARBONATITE, 0xE8E6DE, 0.06f),
    )

    /** Registered after every other built-in material, paints included, so saved worlds keep their ids. */
    internal val added: List<Triple<String, Int, Float>> = listOf(
        Triple(ALLUVIUM, 0x9A8466, 0.2f),
        Triple(SILT, 0x5E4F3E, 0.08f),
        Triple(RIVER_GRAVEL, 0x847A6E, 0.22f),
        Triple(QUARTZ, 0xEEEBE4, 0.06f),
        Triple(GOLD_REEF, 0xC9A544, 0.2f),
        Triple(MALACHITE, 0x2F8A5C, 0.2f),
        Triple(BAUXITE, 0xD6A48E, 0.14f),
        Triple(FOSSIL_LIMESTONE, 0xC8BBA0, 0.22f),
        Triple(BONE_BED, 0x9A7E6A, 0.22f),
        Triple(MIGMATITE, 0xB08E86, 0.18f),
        Triple(GREENSTONE, 0x4E5E4A, 0.12f),
        Triple(CONGLOMERATE, 0x8E7460, 0.26f),
        Triple(DOLOMITE, 0xA8A49A, 0.1f),
        Triple(DUNE_SAND, 0xDCCDA8, 0.06f),
        Triple(LAGOON_MUD, 0x3E3C34, 0.08f),
    )
}

/** The rocks, for [com.stratum.engine.microvoxel.MaterialPalette.standard]. */
object GeoMaterials {
    val all: List<Triple<String, Int, Float>> get() = R.table

    /** Rocks added later, registered at the very end of the standard palette so no older id moves. */
    val added: List<Triple<String, Int, Float>> get() = R.added
}
