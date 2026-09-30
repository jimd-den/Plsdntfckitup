package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.M

/**
 * Africa's geological provinces: twenty-four kinds of country, from
 * rainforest basin to sand sea, rift to fold belt, canyon to lagoon, each
 * with its real rocks.
 *
 * The continent is mostly very old rock -- Archaean cratons, their granites
 * and gneisses worn flat and weathered deep under tropical rain into
 * laterite -- with younger sediments laid over it in basins (the Congo, the
 * Karoo, the Sahara's sandstones), flood basalts poured out where the crust
 * stretched (Ethiopia, the Drakensberg), a rift still opening down the east,
 * one fold belt in the north-west (the Atlas), and the deserts' sand, gravel
 * and salt on top of it all. Each province here is one of those settings,
 * with the landform, the layered rock and the surface that go with it.
 */
object Provinces {

    const val LATERITE_PLATEAU = "laterite_plateau"
    const val INSELBERGS = "inselbergs"
    const val RAINFOREST_BASIN = "rainforest_basin"
    const val FOREST_HILLS = "forest_hills"
    const val SAHEL_PLAIN = "sahel_plain"
    const val RIFT = "rift_valley"
    const val TRAPS = "highland_traps"
    const val VOLCANIC_NECKS = "volcanic_necks"
    const val SANDSTONE_ESCARPMENT = "sandstone_escarpment"
    const val ERG = "erg"
    const val REG_HAMADA = "reg_hamada"
    const val SALT_PAN = "salt_pan"
    const val KALAHARI = "kalahari"
    const val NAMIB = "namib"
    const val KAROO = "karoo"
    const val DRAKENSBERG = "drakensberg"
    const val FOLD_BELT = "atlas_folds"
    const val TSINGY = "tsingy"
    const val CORAL_COAST = "coral_coast"
    const val DELTA = "delta"
    const val CANYON = "canyon_country"
    const val COASTAL_DUNES = "coastal_dunes"
    const val MONTANE = "montane_plateau"
    const val SHIELD = "basement_shield"

    /** Tree kinds the vegetation stages know. */
    object Trees {
        const val IROKO = "iroko"
        const val OIL_PALM = "oil_palm"
        const val BAOBAB = "baobab"
        const val ACACIA = "acacia"
        const val DATE_PALM = "date_palm"
        const val EUPHORBIA = "euphorbia"
        const val MANGROVE = "mangrove"
        const val BROADLEAF = "broadleaf"
    }

    fun all(seed: Long): List<Province> = listOf(
        Province(
            LATERITE_PLATEAU, "Laterite plateaus",
            "Fouta Djallon (Guinea), the Mossi plateau (Burkina Faso), the bowé of Guinea and Mali",
            "Mesas capped with iron crust above wooded pediments; the crust is quarried for building stone.",
            Niche(0.65f, 0.25f, 0.5f, 0.25f, 0.2f, 0.5f, 0.55f, 0.4f),
            base = 6f, processes = listOf(
                step("laterite_plateau"),
                step("erosion", "strength" to 0.8f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.8f),
                step("scree", "amount" to 0.6f),
                step("ore_lenses", "material" to R.BAUXITE, "chance" to 0.2f, "layer" to 18f),
                step("dykes", "chance" to 0.3f),
                step("veins", "chance" to 0.2f),
            ),
            strata = Strata(
                profile = listOf(Band(R.LATERITE_CRUST, 3), Band(R.LATERITE, 4), Band(R.MOTTLED_CLAY, 8), Band(R.SAPROLITE, 12)),
                bedding = listOf(Band(R.GNEISS, 14), Band(R.GNEISS_DARK, 3), Band(R.GRANITE, 10), Band(R.GNEISS, 6), Band(R.SCHIST, 3)),
            ),
            surface = Surface(ground = R.LATERITE, soil = true, slope = R.LATERITE_CRUST, cliff = 1.1f),
            fertility = 0.55f, trees = listOf(Trees.BAOBAB, Trees.ACACIA, Trees.BROADLEAF),
        ),
        Province(
            INSELBERGS, "Granite inselbergs",
            "Idanre Hills, Olumo Rock and Zuma Rock (Nigeria), Matobo Hills (Zimbabwe), Spitzkoppe (Namibia), Sibebe (Eswatini)",
            "Bare granite domes and piled boulders rising sheer from a flat plain: the worn-down roots of the craton.",
            Niche(0.6f, 0.3f, 0.4f, 0.3f, 0.12f, 0.3f, 0.5f, 0.5f),
            base = 4f, processes = listOf(
                step("inselbergs"),
                step("erosion", "strength" to 0.5f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.6f),
                step("scree", "amount" to 1.1f),
                step("dykes", "chance" to 0.4f),
                step("veins", "chance" to 0.4f),
            ),
            strata = Strata(
                profile = listOf(Band(R.LATERITE, 2), Band(R.SAPROLITE, 6)),
                bedding = listOf(Band(R.GRANITE, 20), Band(R.GRANITE_WEATHERED, 2), Band(R.GRANITE, 12), Band(R.CHARNOCKITE, 4)),
            ),
            surface = Surface(ground = M.GRASS_DRY, soil = true, slope = R.GRANITE_WEATHERED, cliff = 0.9f, bareAbove = 14f),
            fertility = 0.5f, trees = listOf(Trees.BAOBAB, Trees.ACACIA, Trees.EUPHORBIA),
        ),
        Province(
            RAINFOREST_BASIN, "Rainforest basin",
            "The Cuvette Centrale of the Congo basin, the Sangha and the Ubangi",
            "Low, wet, red-soiled country under closed forest, cut by broad rivers and swamp forest.",
            Niche(0.7f, 0.25f, 0.92f, 0.2f, 0.15f, 0.5f, 0.3f, 0.35f),
            base = 2f, processes = listOf(
                step("rainforest_basin"),
                step("erosion", "strength" to 0.6f, "deposit" to 1.2f),
                step("rivers", "rain" to 1.4f),
                step("concretions", "chance" to 0.2f),
            ),
            strata = Strata(
                profile = listOf(Band(R.FERRALSOL, 10), Band(R.MOTTLED_CLAY, 10)),
                bedding = listOf(Band(R.SANDSTONE_BUFF, 8), Band(R.SHALE, 4), Band(R.SANDSTONE_RED, 6), Band(R.MUDSTONE, 3)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.FERRALSOL, floor = R.DELTA_MUD, floorBelow = -2f),
            fertility = 1f, trees = listOf(Trees.IROKO, Trees.OIL_PALM, Trees.BROADLEAF),
        ),
        Province(
            FOREST_HILLS, "Guinean forest hills",
            "The Igbo and Yoruba uplands of southern Nigeria, the Agulu-Nanka gullies, the Ashanti uplands (Ghana)",
            "Rolling red-earth hills under farm-bush and forest, torn in places by deep erosion gullies.",
            Niche(0.65f, 0.25f, 0.75f, 0.2f, 0.25f, 0.5f, 0.45f, 0.4f),
            base = 4f, processes = listOf(
                step("forest_hills"),
                step("erosion", "strength" to 1.2f, "deposit" to 1.0f),
                step("rivers", "rain" to 1.2f),
                step("scree", "amount" to 0.4f),
                step("concretions"),
                step("cross_bedding", "hosts" to R.NANKA_SAND, "with" to R.MOTTLED_CLAY, "set" to 5f),
            ),
            strata = Strata(
                profile = listOf(Band(R.FERRALSOL, 5), Band(R.LATERITE, 3)),
                bedding = listOf(Band(R.NANKA_SAND, 10), Band(R.IRONSTONE, 2), Band(R.NANKA_SAND, 6), Band(R.MOTTLED_CLAY, 4), Band(R.SANDSTONE_RED, 5)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.FERRALSOL, cliff = 1.1f),
            fertility = 0.95f, trees = listOf(Trees.OIL_PALM, Trees.IROKO, Trees.BROADLEAF),
        ),
        Province(
            SAHEL_PLAIN, "Sahel floodplain",
            "The Inner Niger Delta's margins (Mali), the Lake Chad plain, the Gezira (Sudan)",
            "Flat Sahelian country of black cracking clay and seasonal pools, with baobab, acacia and termite mounds.",
            Niche(0.8f, 0.2f, 0.3f, 0.2f, 0.2f, 0.5f, 0.3f, 0.4f),
            base = 3f, processes = listOf(
                step("sahel_plain"),
                step("erosion", "strength" to 0.3f, "deposit" to 1.4f),
                step("rivers", "rain" to 0.6f),
            ),
            strata = Strata(
                profile = listOf(Band(R.VERTISOL, 5), Band(R.DIATOMITE, 3), Band(R.MOTTLED_CLAY, 6)),
                bedding = listOf(Band(R.SANDSTONE_BUFF, 10), Band(R.MUDSTONE, 5), Band(R.SANDSTONE_PALE, 6)),
            ),
            surface = Surface(ground = M.GRASS_DRY, soil = true, slope = R.VERTISOL, floor = R.VERTISOL, floorBelow = 2.5f, lake = M.WATER, lakeBelow = 1.2f),
            fertility = 0.45f, trees = listOf(Trees.BAOBAB, Trees.ACACIA),
        ),
        Province(
            RIFT, "Rift valley",
            "The Kenyan rift (Longonot, Suswa, Menengai), Lake Natron and Ol Doinyo Lengai (Tanzania), the Afar (Ethiopia)",
            "A stepped graben between faulted shoulders, its floor dotted with volcanoes and red soda lakes.",
            Niche(0.55f, 0.35f, 0.45f, 0.4f, 0.92f, 0.15f, 0.6f, 0.5f),
            base = 6f, processes = listOf(
                step("rift"),
                step("faults", "throw" to 7f, "spacing" to 640f),
                step("erosion", "strength" to 0.8f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.5f),
                step("scree", "amount" to 1.0f),
                step("dykes", "material" to R.BASALT, "chance" to 0.5f, "spacing" to 300f),
                step("columnar_joints"),
            ),
            strata = Strata(
                profile = listOf(Band(R.ANDOSOL, 3), Band(R.TUFF, 5)),
                bedding = listOf(Band(R.PHONOLITE, 9), Band(R.TUFF, 4), Band(R.BASALT, 8), Band(R.SCORIA_RED, 2), Band(R.TUFF, 3)),
            ),
            surface = Surface(
                ground = M.GRASS_DRY, soil = true, slope = R.TUFF, cliff = 1.0f,
                floor = R.TRONA, floorBelow = 4f, lake = M.SODA_WATER, lakeBelow = 2.2f,
            ),
            fertility = 0.4f, trees = listOf(Trees.ACACIA, Trees.EUPHORBIA),
        ),
        Province(
            TRAPS, "Basalt traps and ambas",
            "The Simien Mountains, the Blue Nile gorge, Amba Aradam and the Tigray ambas (Ethiopia)",
            "Stacked lava flows cut into flat-topped tablelands by gorges; each flow a cliff band with red boles between.",
            Niche(0.35f, 0.25f, 0.55f, 0.35f, 0.7f, 0.25f, 0.9f, 0.25f),
            base = 18f, processes = listOf(
                step("traps"),
                step("erosion", "strength" to 1.2f, "deposit" to 1.0f),
                step("rivers", "rain" to 1.0f),
                step("scree", "amount" to 1.0f),
                step("columnar_joints"),
            ),
            strata = Strata(
                profile = listOf(Band(R.VERTISOL, 2), Band(R.BASALT_WEATHERED, 2)),
                bedding = listOf(Band(R.BASALT, 7), Band(R.BASALT_WEATHERED, 1), Band(R.RED_BOLE, 1)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.BASALT_WEATHERED, cliff = 0.95f),
            fertility = 0.6f, trees = listOf(Trees.EUPHORBIA, Trees.BROADLEAF),
        ),
        Province(
            VOLCANIC_NECKS, "Volcanic necks and cones",
            "Rhumsiki and the Mandara Mountains, Mount Cameroon and the Cameroon line, the Virunga",
            "The hard throats of eroded volcanoes stand as spires over green hills; young cinder cones between.",
            Niche(0.55f, 0.3f, 0.6f, 0.35f, 0.78f, 0.2f, 0.6f, 0.4f),
            base = 6f, processes = listOf(
                step("volcanic_necks"),
                step("erosion", "strength" to 1.0f, "deposit" to 1.0f),
                step("rivers", "rain" to 1.0f),
                step("scree", "amount" to 0.8f),
                step("columnar_joints", "seam" to R.SCORIA, "size" to 2.6f),
            ),
            strata = Strata(
                profile = listOf(Band(R.ANDOSOL, 5)),
                bedding = listOf(Band(R.PHONOLITE, 12), Band(R.BASALT, 8), Band(R.SCORIA, 3)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.SCORIA, cliff = 0.95f, bareAbove = 48f),
            fertility = 0.8f, trees = listOf(Trees.BROADLEAF, Trees.EUPHORBIA, Trees.OIL_PALM),
        ),
        Province(
            SANDSTONE_ESCARPMENT, "Sandstone escarpment",
            "The Bandiagara escarpment (Mali), the Ennedi (Chad), the Tassili n'Ajjer (Algeria)",
            "A tabletop plateau ending in one sheer cliff, pillars and buttes stranded before it.",
            Niche(0.75f, 0.25f, 0.25f, 0.25f, 0.3f, 0.5f, 0.6f, 0.4f),
            base = 4f, processes = listOf(
                step("sandstone_escarpment"),
                step("erosion", "strength" to 0.6f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.3f),
                step("scree", "amount" to 1.2f),
                step("cross_bedding"),
                step("concretions", "chance" to 0.15f),
            ),
            strata = Strata(
                profile = listOf(Band(R.ERG_SAND, 1)),
                bedding = listOf(Band(R.SANDSTONE_RED, 6), Band(R.SANDSTONE_BUFF, 3), Band(R.SANDSTONE_RED, 4), Band(R.SANDSTONE_PALE, 2), Band(R.SANDSTONE_RED, 5), Band(R.IRONSTONE, 1)),
            ),
            surface = Surface(ground = R.SANDSTONE_RED, slope = R.TALUS, cliff = 0.85f),
            fertility = 0.25f, trees = listOf(Trees.BAOBAB, Trees.ACACIA),
        ),
        Province(
            ERG, "Sand sea",
            "The Grand Erg Oriental and Occidental (Algeria), Erg Chebbi (Morocco), the Ténéré (Niger)",
            "Seif dunes lined up with the wind, star dunes where the winds meet, gravel in the corridors between.",
            Niche(0.92f, 0.12f, 0.04f, 0.1f, 0.2f, 0.6f, 0.4f, 0.5f),
            base = 3f, processes = listOf(
                step("erg"),
                step("barchans", "height" to 11f, "spacing" to 160f),
                step("rivers", "rain" to 0.05f),
            ),
            strata = Strata(
                profile = listOf(Band(R.ERG_SAND, 24)),
                bedding = listOf(Band(R.SANDSTONE_PALE, 10), Band(R.SANDSTONE_BUFF, 6)),
            ),
            surface = Surface(ground = R.ERG_SAND, slope = R.ERG_SAND, cliff = 9f, shore = R.ERG_SAND, floor = R.REG_GRAVEL, floorBelow = 3f),
            fertility = 0.02f, trees = listOf(Trees.DATE_PALM),
        ),
        Province(
            REG_HAMADA, "Reg and hamada",
            "The Tanezrouft and the Hamada du Draa (Algeria), the Borkou yardangs (Chad), Egypt's White Desert",
            "Gravel plains to the horizon, black-varnished tablelands, and wind-sculpted yardang ridges.",
            Niche(0.88f, 0.15f, 0.08f, 0.12f, 0.3f, 0.6f, 0.55f, 0.4f),
            base = 4f, processes = listOf(
                step("reg_hamada"),
                step("barchans", "height" to 8f, "spacing" to 220f, "chance" to 0.3f),
                step("erosion", "strength" to 0.4f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.15f),
                step("scree", "amount" to 0.8f),
                step("concretions", "material" to R.DESERT_VARNISH, "hosts" to "${R.LIMESTONE},${R.CHALK}", "chance" to 0.2f),
            ),
            strata = Strata(
                profile = listOf(Band(R.DESERT_VARNISH, 1), Band(R.REG_GRAVEL, 3)),
                // The fossil band: Wadi Al-Hitan's whale-bearing Eocene limestone.
                bedding = listOf(Band(R.LIMESTONE, 6), Band(R.FOSSIL_LIMESTONE, 1), Band(R.CHALK, 4), Band(R.SANDSTONE_PALE, 5), Band(R.MUDSTONE, 2)),
            ),
            surface = Surface(ground = R.REG_GRAVEL, slope = R.DESERT_VARNISH, cliff = 0.9f),
            fertility = 0.03f, trees = listOf(Trees.DATE_PALM, Trees.ACACIA),
        ),
        Province(
            SALT_PAN, "Salt pans",
            "Makgadikgadi and Kubu Island (Botswana), Etosha (Namibia), Chott el Djerid (Tunisia), Dallol (Ethiopia)",
            "A white floor flat as a table, its crust buckled into polygons, granite islands rising from it.",
            Niche(0.8f, 0.25f, 0.15f, 0.2f, 0.3f, 0.6f, 0.2f, 0.3f),
            base = 2f, processes = listOf(
                step("salt_pan"),
                step("rivers", "rain" to 0.1f),
                step("scree", "amount" to 0.5f),
            ),
            strata = Strata(
                profile = listOf(Band(R.SALT, 2), Band(R.DIATOMITE, 4), Band(R.CALCRETE, 4)),
                bedding = listOf(Band(R.CALCRETE, 6), Band(R.SANDSTONE_PALE, 6), Band(R.GRANITE, 10)),
            ),
            surface = Surface(ground = R.CALCRETE, slope = R.GRANITE_WEATHERED, cliff = 0.9f, floor = R.SALT, floorBelow = 1.5f, bareAbove = 16f),
            fertility = 0.1f, trees = listOf(Trees.BAOBAB),
        ),
        Province(
            KALAHARI, "Kalahari sandveld",
            "The Kalahari of Botswana and the Northern Cape, the Central Kalahari's fossil valleys",
            "Endless red sand under grass and thorn scrub, long low fossil dunes, small calcrete pans.",
            Niche(0.7f, 0.25f, 0.22f, 0.15f, 0.15f, 0.5f, 0.4f, 0.4f),
            base = 4f, processes = listOf(
                step("kalahari"),
                step("erosion", "strength" to 0.2f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.1f),
            ),
            strata = Strata(
                profile = listOf(Band(R.KALAHARI_SAND, 10), Band(R.CALCRETE, 4)),
                bedding = listOf(Band(R.CALCRETE, 5), Band(R.SANDSTONE_RED, 8), Band(R.BASALT, 4)),
            ),
            surface = Surface(ground = R.KALAHARI_SAND, slope = R.KALAHARI_SAND, cliff = 9f, shore = R.KALAHARI_SAND, floor = R.CALCRETE, floorBelow = 1.5f),
            fertility = 0.3f, trees = listOf(Trees.ACACIA, Trees.BAOBAB),
        ),
        Province(
            NAMIB, "Namib dunes",
            "Sossusvlei and the Namib Sand Sea (Namibia), the Skeleton Coast",
            "The tallest dunes on earth, deep orange with iron, beside the cold Atlantic and gravel flats.",
            Niche(0.7f, 0.2f, 0.02f, 0.08f, 0.2f, 0.6f, 0.12f, 0.25f),
            base = 2f, processes = listOf(
                step("namib"),
                step("barchans", "height" to 13f, "spacing" to 200f, "chance" to 0.45f),
                step("rivers", "rain" to 0.05f),
            ),
            strata = Strata(
                profile = listOf(Band(R.NAMIB_SAND, 28)),
                bedding = listOf(Band(R.SANDSTONE_RED, 8), Band(R.GYPCRETE, 2), Band(R.GNEISS, 8)),
            ),
            surface = Surface(ground = R.NAMIB_SAND, slope = R.NAMIB_SAND, cliff = 9f, shore = R.NAMIB_SAND, floor = R.REG_GRAVEL, floorBelow = 4f),
            fertility = 0.01f, trees = listOf(Trees.ACACIA),
        ),
        Province(
            KAROO, "Karoo mesas",
            "The Great Karoo and the Valley of Desolation (South Africa)",
            "Flat-lying shale and sandstone; every mesa and conical koppie wears the same dark dolerite cap.",
            Niche(0.5f, 0.25f, 0.2f, 0.18f, 0.25f, 0.5f, 0.6f, 0.4f),
            base = 6f, processes = listOf(
                step("karoo"),
                step("erosion", "strength" to 0.7f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.35f),
                step("scree", "amount" to 1.1f),
                step("dykes", "chance" to 0.8f, "width" to 4f),
                step("concretions", "material" to R.CALCRETE, "chance" to 0.25f),
            ),
            strata = Strata(
                profile = listOf(Band(R.CALCRETE, 1), Band(R.MUDSTONE, 3)),
                // A bone bed in the Beaufort mudstones, where the Karoo's fossil reptiles lie.
                bedding = listOf(Band(R.SHALE, 5), Band(R.MUDSTONE, 3), Band(R.BONE_BED, 1), Band(R.SANDSTONE_BUFF, 4), Band(R.SHALE, 4)),
                cap = R.DOLERITE, capAbove = 24f,
            ),
            surface = Surface(ground = M.GRASS_DRY, soil = true, slope = R.TALUS, cliff = 0.9f),
            fertility = 0.25f, trees = listOf(Trees.ACACIA, Trees.EUPHORBIA),
        ),
        Province(
            DRAKENSBERG, "Drakensberg escarpment",
            "The Amphitheatre and Golden Gate (South Africa), the Maloti of Lesotho",
            "The Great Escarpment: golden sandstone cliffs under a wall of basalt to the summit plateau.",
            Niche(0.35f, 0.25f, 0.55f, 0.3f, 0.4f, 0.4f, 0.9f, 0.25f),
            base = 8f, processes = listOf(
                step("drakensberg"),
                step("erosion", "strength" to 1.2f, "deposit" to 1.0f),
                step("rivers", "rain" to 1.1f),
                step("scree", "amount" to 1.0f),
                step("cross_bedding", "set" to 9f, "lamina" to 2.6f),
                step("columnar_joints"),
            ),
            strata = Strata(
                profile = listOf(Band(M.DIRT, 3)),
                bedding = listOf(Band(R.SANDSTONE_BUFF, 7), Band(R.SANDSTONE_PALE, 2), Band(R.SANDSTONE_BUFF, 4), Band(R.MUDSTONE, 2)),
                cap = R.BASALT, capAbove = 44f,
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.TALUS, cliff = 0.9f),
            fertility = 0.55f, trees = listOf(Trees.BROADLEAF, Trees.EUPHORBIA),
        ),
        Province(
            FOLD_BELT, "Atlas fold belt",
            "The High Atlas, the Todra and Dades gorges, the Anti-Atlas (Morocco)",
            "Parallel ridges of folded limestone and red sandstone, the layers bent with the hills, cut by slot gorges.",
            Niche(0.45f, 0.3f, 0.35f, 0.3f, 0.62f, 0.3f, 0.8f, 0.3f),
            base = 8f, processes = listOf(
                step("fold_belt", "wavelength" to 360f),
                step("faults", "throw" to 6f, "spacing" to 1100f),
                step("erosion", "strength" to 1.1f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.7f),
                step("scree", "amount" to 1.0f),
                step("veins", "material" to R.QUARTZ, "richShare" to 0f, "chance" to 0.2f),
            ),
            strata = Strata(
                profile = listOf(Band(R.TALUS, 2)),
                // The ammonite limestone quarried around Erfoud.
                bedding = listOf(Band(R.LIMESTONE, 5), Band(R.SANDSTONE_RED, 4), Band(R.LIMESTONE_GREY, 3), Band(R.FOSSIL_LIMESTONE, 2), Band(R.MUDSTONE, 3), Band(R.SANDSTONE_RED, 3)),
                fold = Fold(amplitude = 40f, wavelength = 360f),
            ),
            surface = Surface(ground = M.GRASS_DRY, soil = true, slope = R.TALUS, cliff = 0.9f),
            fertility = 0.3f, trees = listOf(Trees.BROADLEAF, Trees.DATE_PALM),
        ),
        Province(
            TSINGY, "Tsingy karst",
            "Tsingy de Bemaraha and Ankarana (Madagascar)",
            "A limestone plateau dissolved into a forest of grey razor pinnacles, cut by deep crevasses.",
            Niche(0.7f, 0.25f, 0.55f, 0.3f, 0.2f, 0.5f, 0.5f, 0.4f),
            base = 4f, processes = listOf(
                step("tsingy"),
                step("sinkholes", "depth" to 12f, "spacing" to 120f),
                step("erosion", "strength" to 0.2f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.3f),
                step("scree", "amount" to 0.3f),
            ),
            strata = Strata(
                profile = listOf(Band(R.LIMESTONE_GREY, 2)),
                bedding = listOf(Band(R.LIMESTONE, 8), Band(R.FOSSIL_LIMESTONE, 1), Band(R.LIMESTONE_GREY, 3)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.LIMESTONE_GREY, cliff = 0.8f, bareAbove = 29f),
            fertility = 0.45f, trees = listOf(Trees.BAOBAB, Trees.BROADLEAF),
        ),
        Province(
            CORAL_COAST, "Coral coast",
            "Lamu, Mombasa, Zanzibar and Kilwa (the Swahili coast), Mozambique Island",
            "Raised fossil-reef terraces, white coral beaches and lagoons: the stone the Swahili towns are built of.",
            Niche(0.75f, 0.25f, 0.6f, 0.3f, 0.2f, 0.5f, 0.05f, 0.12f),
            base = 0f, processes = listOf(
                step("coral_coast"),
                step("erosion", "strength" to 0.3f, "deposit" to 1.0f),
                step("rivers", "rain" to 0.5f),
            ),
            strata = Strata(
                profile = listOf(Band(R.CORAL_SAND, 2)),
                bedding = listOf(Band(R.CORAL_RAG, 6), Band(R.LIMESTONE, 3)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.CORAL_RAG, shore = R.CORAL_SAND, cliff = 0.8f),
            fertility = 0.6f, trees = listOf(Trees.OIL_PALM, Trees.BAOBAB, Trees.MANGROVE),
        ),
        Province(
            DELTA, "Delta and mangrove",
            "The Niger delta, the Rufiji delta (Tanzania), the Okavango's channels",
            "Creeks and levees barely above the tide, mangrove on the mud.",
            Niche(0.75f, 0.25f, 0.85f, 0.2f, 0.2f, 0.5f, 0.02f, 0.1f),
            base = 0f, processes = listOf(
                step("delta"),
                step("erosion", "strength" to 0.1f, "deposit" to 1.5f),
                step("rivers", "rain" to 1.2f),
            ),
            strata = Strata(
                profile = listOf(Band(R.DELTA_MUD, 8)),
                bedding = listOf(Band(R.MUDSTONE, 6), Band(R.SANDSTONE_BUFF, 4)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.DELTA_MUD, shore = R.DELTA_MUD, floor = R.DELTA_MUD, floorBelow = 1.5f),
            fertility = 0.85f, trees = listOf(Trees.MANGROVE, Trees.OIL_PALM, Trees.IROKO),
        ),
        Province(
            CANYON, "Canyon country",
            "The Fish River Canyon (Namibia), the Blyde River Canyon (South Africa), the Tekezé gorge (Ethiopia)",
            "A flat stony plateau cut by one deep, looping canyon; low in its walls, flat beds rest on the tilted basement.",
            Niche(0.62f, 0.2f, 0.14f, 0.12f, 0.35f, 0.3f, 0.78f, 0.22f),
            base = 10f, processes = listOf(
                step("canyon"),
                step("faults", "throw" to 5f, "spacing" to 1300f),
                step("erosion", "strength" to 0.8f, "deposit" to 0.8f),
                step("rivers", "rain" to 0.5f, "wet" to 0.3f),
                step("scree", "amount" to 1.1f),
                // The great unconformity: Nama shelf beds over Namaqua gneiss, with a pebble bed between.
                step("unconformity", "depth" to 14f, "tilt" to 1.4f, "bands" to "${R.GNEISS}*5,${R.MIGMATITE}*3,${R.SCHIST}*3,${R.GNEISS_DARK}*2"),
                step("dykes", "chance" to 0.35f),
            ),
            strata = Strata(
                profile = listOf(Band(R.REG_GRAVEL, 1), Band(R.CALCRETE, 2)),
                bedding = listOf(Band(R.DOLOMITE, 5), Band(R.SHALE, 3), Band(R.SANDSTONE_PALE, 4), Band(R.LIMESTONE_GREY, 3), Band(R.SANDSTONE_BUFF, 3)),
            ),
            surface = Surface(ground = R.REG_GRAVEL, slope = R.TALUS, cliff = 0.85f),
            fertility = 0.12f, trees = listOf(Trees.EUPHORBIA, Trees.ACACIA),
        ),
        Province(
            COASTAL_DUNES, "Dune cordon and lagoon",
            "The Maputaland and Wild Coast dune cordons (Mozambique, South Africa), the lagoons of Lagos (Nigeria), Ébrié (Côte d'Ivoire) and Keta (Ghana)",
            "Forested sand ridges along the shore with a long, still lagoon trapped behind them.",
            Niche(0.74f, 0.25f, 0.55f, 0.25f, 0.15f, 0.4f, 0.02f, 0.1f),
            base = 1f, processes = listOf(
                step("dune_cordon"),
                step("erosion", "strength" to 0.2f, "deposit" to 1.2f),
                step("rivers", "rain" to 0.7f),
            ),
            strata = Strata(
                profile = listOf(Band(R.DUNE_SAND, 10), Band(R.LAGOON_MUD, 3)),
                bedding = listOf(Band(R.SANDSTONE_PALE, 5), Band(R.LAGOON_MUD, 2), Band(R.CORAL_RAG, 3), Band(R.SANDSTONE_BUFF, 4)),
            ),
            surface = Surface(
                ground = M.GRASS, soil = true, slope = R.DUNE_SAND, shore = R.DUNE_SAND, cliff = 1.4f,
                floor = R.LAGOON_MUD, floorBelow = 0f, lake = M.WATER, lakeBelow = -1.5f,
            ),
            fertility = 0.7f, trees = listOf(Trees.OIL_PALM, Trees.MANGROVE, Trees.BROADLEAF),
        ),
        Province(
            MONTANE, "Montane plateau",
            "The Nyika plateau (Malawi), the Jos Plateau (Nigeria), the Bamenda highlands (Cameroon), the Aberdares (Kenya)",
            "High, cool, rolling grassland with granite knolls, forest in the valleys and a stream in every fold.",
            Niche(0.3f, 0.22f, 0.72f, 0.22f, 0.4f, 0.4f, 0.88f, 0.22f),
            base = 14f, processes = listOf(
                step("montane_plateau"),
                step("erosion", "strength" to 1.1f, "deposit" to 0.8f),
                step("rivers", "rain" to 1.4f),
                step("scree", "amount" to 0.5f),
                step("dykes", "chance" to 0.3f),
                step("veins", "chance" to 0.25f, "richShare" to 0f),
            ),
            strata = Strata(
                profile = listOf(Band(R.ANDOSOL, 3), Band(R.FERRALSOL, 3), Band(R.SAPROLITE, 6)),
                bedding = listOf(Band(R.GRANITE, 12), Band(R.GNEISS, 6), Band(R.GRANITE_WEATHERED, 1), Band(R.MIGMATITE, 4)),
            ),
            surface = Surface(ground = M.GRASS, soil = true, slope = R.GRANITE_WEATHERED, cliff = 1.0f, bareAbove = 40f),
            fertility = 0.8f, trees = listOf(Trees.BROADLEAF, Trees.EUPHORBIA),
        ),
        Province(
            SHIELD, "Basement shield",
            "The Zimbabwe craton and its Great Dyke, the Barberton greenstone belt (South Africa), the Man shield and the Nimba iron ridges (Guinea, Liberia, Côte d'Ivoire), the Ashanti gold belt (Ghana)",
            "The worn roots of the oldest crust: low rolling country crossed by quartzite and iron ridges, dykes and gold-bearing reefs.",
            Niche(0.58f, 0.2f, 0.5f, 0.2f, 0.04f, 0.1f, 0.45f, 0.35f),
            base = 4f, processes = listOf(
                step("shield"),
                step("erosion", "strength" to 0.6f),
                step("rivers", "rain" to 0.8f),
                step("scree", "amount" to 0.5f),
                step("dykes", "chance" to 0.7f, "width" to 5f, "spacing" to 520f),
                step("veins", "chance" to 0.5f, "richShare" to 0.25f),
                step("ore_lenses", "material" to R.BIF_RED, "chance" to 0.2f),
                step("ore_lenses", "material" to R.GOLD_REEF, "chance" to 0.06f, "cell" to 56f),
            ),
            strata = Strata(
                profile = listOf(Band(R.LATERITE, 2), Band(R.MOTTLED_CLAY, 3), Band(R.SAPROLITE, 6)),
                bedding = listOf(
                    Band(R.GNEISS, 5), Band(R.MIGMATITE, 3), Band(R.GREENSTONE, 4), Band(R.QUARTZITE, 2), Band(R.BIF, 1), Band(R.BIF_RED, 1),
                    Band(R.SCHIST, 3), Band(R.GNEISS_DARK, 2),
                ),
                // Steeply dipping: the belts stand on end.
                fold = Fold(amplitude = 18f, wavelength = 300f, tilt = 0.9f),
            ),
            surface = Surface(ground = M.GRASS_DRY, soil = true, slope = R.QUARTZITE, cliff = 1.0f),
            fertility = 0.5f, trees = listOf(Trees.BAOBAB, Trees.ACACIA, Trees.BROADLEAF),
        ),
    )
}
