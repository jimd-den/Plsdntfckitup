package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.arch.PlanShape.COURTYARD
import com.stratum.engine.microvoxel.arch.PlanShape.L
import com.stratum.engine.microvoxel.arch.PlanShape.OCTAGON
import com.stratum.engine.microvoxel.arch.PlanShape.RECT
import com.stratum.engine.microvoxel.arch.PlanShape.ROUND
import com.stratum.engine.microvoxel.arch.PlanShape.STEPPED
import com.stratum.engine.microvoxel.arch.PlanShape.U
import com.stratum.engine.microvoxel.arch.RoofForm.BARREL
import com.stratum.engine.microvoxel.arch.RoofForm.CONE
import com.stratum.engine.microvoxel.arch.RoofForm.DOME
import com.stratum.engine.microvoxel.arch.RoofForm.FLAT
import com.stratum.engine.microvoxel.arch.RoofForm.GABLE
import com.stratum.engine.microvoxel.arch.RoofForm.HIP
import com.stratum.engine.microvoxel.arch.RoofForm.PYRAMID
import com.stratum.engine.microvoxel.arch.RoofForm.TERRACE
import com.stratum.engine.microvoxel.arch.WallPattern.BANDS
import com.stratum.engine.microvoxel.arch.WallPattern.CHECKER
import com.stratum.engine.microvoxel.arch.WallPattern.DIAMOND
import com.stratum.engine.microvoxel.arch.WallPattern.FRIEZE
import com.stratum.engine.microvoxel.arch.WallPattern.PILASTERS
import com.stratum.engine.microvoxel.arch.WallPattern.PLAIN
import com.stratum.engine.microvoxel.arch.WallPattern.PLINTH
import com.stratum.engine.microvoxel.arch.WallPattern.QUOINS
import com.stratum.engine.microvoxel.arch.WallPattern.ZIGZAG
import com.stratum.engine.microvoxel.arch.WindowForm.ARCHED
import com.stratum.engine.microvoxel.arch.WindowForm.GRID
import com.stratum.engine.microvoxel.arch.WindowForm.SHUTTERED
import com.stratum.engine.microvoxel.arch.WindowForm.SLIT
import com.stratum.engine.microvoxel.arch.WindowForm.SQUARE
import com.stratum.engine.microvoxel.arch.WindowForm.TALL

/**
 * Each tradition's grammar for invented buildings: the forms its builders
 * used, so a town that invents its own look still reads as its region's.
 *
 * A regional town that invents (see the settlements stage's `parametric`
 * share) does not borrow materials alone: its plans, roofs, storeys, wall
 * dress, openings and ornament come from here. Kano's quarter gets
 * flat-roofed earthen houses with zanko pinnacles and zayyana relief, never a
 * gable; Lamu gets storeyed coral-stone houses with terraces and carved doors;
 * Great Zimbabwe dry stone laid round, with chevron courses and conical
 * towers. What varies is everything the tradition itself varied: sizes,
 * storeys, courtyards, which dress and how much.
 *
 * Grounded in the same history as the traditions' painters; see [Traditions]
 * and docs/AFRICAN-WORLD.md.
 */
object Vernacular {

    private val EARTH = listOf(MaterialFamilies.EARTH.id)

    val rules: Map<String, GenomeRules> = mapOf(
        // Djenné, Timbuktu, Mopti: adobe under mud render, flat roofs behind parapets, pilasters rising
        // into rounded pinnacles, toron beams, storeyed merchants' houses with Moroccan-style windows.
        "sudano_sahelian" to GenomeRules(
            plans = listOf(RECT, COURTYARD, L, STEPPED), roofs = listOf(FLAT, TERRACE), families = EARTH,
            minUpper = 0, maxUpper = 2, ornament = 0.8f, towers = 0.15f,
            patterns = listOf(PILASTERS, FRIEZE, PLINTH), windows = listOf(SLIT, TALL, SQUARE),
            towerRoofs = listOf(FLAT, CONE), beams = 0.8f, veranda = 0.05f, pinnacles = 0.85f,
            walls = listOf(A.ADOBE, A.RENDER, A.MUDBRICK), accents = listOf(A.ADOBE_DARK, A.RENDER),
            roofMaterials = listOf(A.ADOBE to A.ADOBE_DARK),
        ),
        // Kano and Zaria: courtyard houses of tubali mudbrick, flat roofs with zanko horns at the corners,
        // zayyana relief in interlace and diamonds round the doors, domed rooms inside.
        "hausa" to GenomeRules(
            plans = listOf(COURTYARD, RECT, U, L), roofs = listOf(FLAT, FLAT, DOME), families = EARTH,
            minUpper = 0, maxUpper = 1, ornament = 0.9f, towers = 0.05f,
            patterns = listOf(DIAMOND, ZIGZAG, FRIEZE), windows = listOf(SLIT, SQUARE),
            beams = 0.2f, veranda = 0.05f, pinnacles = 0.9f,
            walls = listOf(A.HAUSA_PLASTER, A.ADOBE, A.RENDER), accents = listOf(A.HAUSA_RELIEF, M.NZU, A.ADOBE_DARK),
            roofMaterials = listOf(A.ADOBE to A.ADOBE_DARK, A.HAUSA_PLASTER to A.HAUSA_RELIEF),
        ),
        // Bandiagara: square family houses with flat roofs, round granaries under thatch hats.
        "dogon" to GenomeRules(
            plans = listOf(RECT, ROUND, ROUND), roofs = listOf(FLAT, CONE, CONE), families = EARTH,
            minUpper = 0, maxUpper = 1, ornament = 0.4f, towers = 0.1f,
            patterns = listOf(PLAIN, PLINTH), windows = listOf(SLIT), towerRoofs = listOf(CONE),
            beams = 0.3f, veranda = 0f, pinnacles = 0.2f,
            walls = listOf(A.ADOBE, A.ADOBE_DARK, M.MUD), accents = listOf(A.ADOBE_DARK, A.TORON),
            roofMaterials = listOf(A.MILLET_THATCH to M.THATCH_DARK, A.ADOBE to A.ADOBE_DARK),
        ),
        // Koutammakou: the takienta, a fortress-house of round earthen towers under conical thatch.
        "batammariba" to GenomeRules(
            plans = listOf(ROUND, OCTAGON, RECT), roofs = listOf(CONE, FLAT, TERRACE), families = EARTH,
            minUpper = 1, maxUpper = 1, ornament = 0.5f, towers = 0.8f,
            patterns = listOf(PLINTH, BANDS), windows = listOf(SLIT), towerRoofs = listOf(CONE),
            beams = 0.1f, veranda = 0f, pinnacles = 0.1f,
            walls = listOf(A.ADOBE, M.MUD), accents = listOf(A.ADOBE_DARK, M.MUD_DARK),
            roofMaterials = listOf(A.MILLET_THATCH to M.THATCH_DARK),
        ),
        // Tiébélé: low houses, round and square, painted by the women of the compound in bold geometry.
        "kassena" to GenomeRules(
            plans = listOf(ROUND, RECT, OCTAGON), roofs = listOf(FLAT, TERRACE), families = listOf(MaterialFamilies.PAINTED.id),
            minUpper = 0, maxUpper = 0, ornament = 1f, towers = 0f,
            patterns = listOf(DIAMOND, ZIGZAG, CHECKER, BANDS), windows = listOf(SLIT),
            beams = 0f, veranda = 0f, pinnacles = 0.1f,
            walls = listOf(A.KASSENA_RED, A.OCHRE), accents = listOf(A.PAINT_BLACK, A.PAINT_WHITE),
            roofMaterials = listOf(A.KASSENA_RED to A.ADOBE_DARK),
        ),
        // The Logone floodplain: teleuk, earth shells rising as domes, ribbed for the climb.
        "musgum" to GenomeRules(
            plans = listOf(ROUND), roofs = listOf(DOME, RoofForm.ONION), families = EARTH,
            minUpper = 0, maxUpper = 0, ornament = 0.6f, towers = 0f, patterns = listOf(BANDS, PLAIN), windows = listOf(SLIT),
            beams = 0f, veranda = 0f, pinnacles = 0f, walls = listOf(A.MUSGUM_EARTH), accents = listOf(A.ADOBE_DARK),
            roofMaterials = listOf(A.MUSGUM_EARTH to A.ADOBE_DARK),
        ),
        // Igboland: compounds of mud-walled houses under steep thatch, verandas, uli painted in nzu.
        "igbo" to GenomeRules(
            plans = listOf(RECT, COURTYARD, L, ROUND), roofs = listOf(GABLE, HIP, CONE), families = EARTH,
            minUpper = 0, maxUpper = 0, ornament = 0.6f, towers = 0f,
            patterns = listOf(PLINTH, ZIGZAG, PLAIN), windows = listOf(SLIT, SQUARE),
            beams = 0f, veranda = 0.5f, pinnacles = 0f,
            walls = listOf(M.MUD, A.RED_POLISH, A.ADOBE), accents = listOf(M.NZU, M.MUD_DARK),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK, A.RAFFIA to M.THATCH_DARK),
        ),
        // Ile-Ife, Oyo: the courtyard (impluvium) house, hipped thatch over carved veranda posts.
        "yoruba" to GenomeRules(
            plans = listOf(COURTYARD, U, RECT), roofs = listOf(HIP, GABLE), families = EARTH,
            minUpper = 0, maxUpper = 1, ornament = 0.6f, towers = 0f,
            patterns = listOf(PLINTH, PLAIN, BANDS), windows = listOf(SQUARE, SHUTTERED),
            beams = 0f, veranda = 0.8f, pinnacles = 0f,
            walls = listOf(M.MUD, M.PLASTER, A.RENDER), accents = listOf(M.MUD_DARK, A.POST),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK, M.ROOF_TILE to M.BRICK_DARK),
        ),
        // Kumasi: four buildings round a court, steep thatch, walls red below and white above with relief.
        "asante" to GenomeRules(
            plans = listOf(COURTYARD, U, RECT), roofs = listOf(GABLE, HIP), families = EARTH,
            minUpper = 0, maxUpper = 0, ornament = 0.8f, towers = 0f,
            patterns = listOf(PLINTH, FRIEZE, DIAMOND), windows = listOf(SLIT, SQUARE),
            beams = 0f, veranda = 0.3f, pinnacles = 0f,
            walls = listOf(M.PLASTER, A.LIME), accents = listOf(A.RED_POLISH, A.KASSENA_RED),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK),
        ),
        // Benin City: burnished red walls in long ridged courses, impluvium courts, steep shingle gables.
        "benin" to GenomeRules(
            plans = listOf(COURTYARD, RECT, U), roofs = listOf(GABLE, HIP, PYRAMID), families = EARTH,
            minUpper = 0, maxUpper = 0, ornament = 0.6f, towers = 0.1f,
            patterns = listOf(BANDS, PLINTH), windows = listOf(SLIT), towerRoofs = listOf(PYRAMID, CONE),
            beams = 0f, veranda = 0.3f, pinnacles = 0f,
            walls = listOf(A.RED_POLISH, A.KASSENA_RED), accents = listOf(M.MUD_DARK, A.ADOBE_DARK),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK, M.ROOF_TILE to M.BRICK_DARK),
        ),
        // Kilwa, Lamu, Zanzibar: storeyed coral-stone houses, lime-washed, flat roofs and terraces,
        // carved doors, pillar tombs; mangrove poles set the width of the rooms.
        "swahili" to GenomeRules(
            plans = listOf(RECT, COURTYARD, L, STEPPED), roofs = listOf(FLAT, TERRACE, DOME), families = listOf(MaterialFamilies.LIME.id),
            minUpper = 1, maxUpper = 3, ornament = 0.7f, towers = 0.1f,
            patterns = listOf(FRIEZE, PLAIN, QUOINS), windows = listOf(SHUTTERED, ARCHED, GRID), towerRoofs = listOf(DOME, FLAT),
            beams = 0.4f, veranda = 0.2f, pinnacles = 0.5f,
            walls = listOf(A.LIME, A.CORAL_BLOCK, M.PLASTER), accents = listOf(A.CARVED_DOOR, A.CORAL_BLOCK, A.MANGROVE_POLE),
            roofMaterials = listOf(A.LIME to A.CORAL_BLOCK),
        ),
        // Aksum, Lalibela, the Tigray churches: dressed stone with timber frames ("monkey heads"),
        // stepped setbacks, flat roofs; round tukuls under thatch cones beside them.
        "aksumite" to GenomeRules(
            plans = listOf(RECT, STEPPED, ROUND), roofs = listOf(FLAT, CONE, TERRACE), families = listOf(MaterialFamilies.STONE.id),
            minUpper = 0, maxUpper = 2, ornament = 0.6f, towers = 0.1f,
            patterns = listOf(BANDS, QUOINS, PLAIN), windows = listOf(SQUARE, ARCHED, GRID),
            beams = 0.9f, veranda = 0f, pinnacles = 0.1f,
            walls = listOf(A.AKSUM_STONE, A.ROCK_HEWN), accents = listOf(A.AKSUM_TIMBER, A.DRYSTONE_DARK),
            roofMaterials = listOf(A.AKSUM_STONE to A.DRYSTONE_DARK, M.THATCH to M.THATCH_DARK),
        ),
        // Old Dongola, Kerma, Soleb: mudbrick under barrel vaults and domes, fronts painted blue and yellow.
        "nubian" to GenomeRules(
            plans = listOf(RECT, COURTYARD, U), roofs = listOf(BARREL, DOME, FLAT), families = EARTH,
            minUpper = 0, maxUpper = 1, ornament = 0.8f, towers = 0.05f,
            patterns = listOf(FRIEZE, DIAMOND, BANDS), windows = listOf(ARCHED, SLIT, GRID),
            beams = 0f, veranda = 0.05f, pinnacles = 0.2f,
            walls = listOf(A.MUDBRICK, M.PLASTER, A.NUBIAN_YELLOW), accents = listOf(A.NUBIAN_BLUE, A.PAINT_WHITE, A.NUBIAN_YELLOW),
            roofMaterials = listOf(A.MUDBRICK to A.ADOBE_DARK),
        ),
        // The Draa and Dades: ksour and kasbahs of pisé, towers at the corners tapering to crenellations,
        // relief in brick patterns high on the towers, courtyards within.
        "amazigh" to GenomeRules(
            plans = listOf(COURTYARD, RECT, STEPPED), roofs = listOf(FLAT, TERRACE), families = EARTH,
            minUpper = 1, maxUpper = 3, ornament = 0.8f, towers = 0.6f,
            patterns = listOf(DIAMOND, CHECKER, FRIEZE), windows = listOf(SLIT, GRID), towerRoofs = listOf(FLAT),
            beams = 0.1f, veranda = 0f, pinnacles = 0.7f,
            walls = listOf(A.PISE, A.PISE_LIGHT), accents = listOf(A.PISE_LIGHT, A.ZELLIGE, A.ADOBE_DARK),
            roofMaterials = listOf(A.PISE to A.PISE_LIGHT),
        ),
        // Great Zimbabwe, Khami, Thulamela: coursed dry stone laid round, chevron and dentelle bands,
        // conical towers; daga (earth) houses under thatch inside the enclosures.
        "great_zimbabwe" to GenomeRules(
            plans = listOf(ROUND, OCTAGON, ROUND), roofs = listOf(CONE, CONE, FLAT), families = listOf(MaterialFamilies.STONE.id),
            minUpper = 0, maxUpper = 0, ornament = 0.8f, towers = 0.25f,
            patterns = listOf(ZIGZAG, FRIEZE, BANDS), windows = listOf(SLIT), towerRoofs = listOf(CONE, FLAT),
            beams = 0f, veranda = 0f, pinnacles = 0.2f,
            walls = listOf(A.DRYSTONE), accents = listOf(A.DRYSTONE_DARK),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK, A.DRYSTONE to A.DRYSTONE_DARK),
        ),
        // Ndebele homesteads: gabled and round houses painted in outlined geometric colour fields.
        "ndebele" to GenomeRules(
            plans = listOf(RECT, ROUND, L), roofs = listOf(GABLE, CONE, HIP), families = listOf(MaterialFamilies.PAINTED.id),
            minUpper = 0, maxUpper = 0, ornament = 1f, towers = 0f,
            patterns = listOf(DIAMOND, CHECKER, ZIGZAG, PILASTERS), windows = listOf(SQUARE, SLIT),
            beams = 0f, veranda = 0.2f, pinnacles = 0.1f,
            walls = listOf(A.PAINT_WHITE), accents = listOf(A.NDEBELE_BLUE, A.NDEBELE_RED, A.NDEBELE_YELLOW, A.NDEBELE_GREEN, A.PAINT_BLACK),
            roofMaterials = listOf(M.THATCH to M.THATCH_DARK),
        ),
        // KwaZulu: iqukwane, the beehive house of woven grass, round in a ring about the cattle kraal.
        "zulu" to GenomeRules(
            plans = listOf(ROUND), roofs = listOf(DOME, CONE), families = listOf(MaterialFamilies.TIMBER.id),
            minUpper = 0, maxUpper = 0, ornament = 0.3f, towers = 0f, patterns = listOf(PLAIN, BANDS), windows = listOf(SLIT),
            beams = 0f, veranda = 0f, pinnacles = 0f, walls = listOf(A.GRASS_WEAVE), accents = listOf(M.THATCH_DARK),
            roofMaterials = listOf(A.GRASS_WEAVE to M.THATCH_DARK),
        ),
        // Maasai inkajijik and other pastoral houses: low loaves of dung plaster on bent poles.
        "pastoral" to GenomeRules(
            plans = listOf(ROUND, OCTAGON, RECT), roofs = listOf(DOME, FLAT), families = listOf(MaterialFamilies.TIMBER.id),
            minUpper = 0, maxUpper = 0, ornament = 0.2f, towers = 0f, patterns = listOf(PLAIN), windows = listOf(SLIT),
            beams = 0f, veranda = 0f, pinnacles = 0f, walls = listOf(A.DUNG_PLASTER), accents = listOf(A.THORN),
            roofMaterials = listOf(A.DUNG_PLASTER to M.MUD_DARK),
        ),
    )

    /**
     * The rules an invented town of tradition [id] builds by. Where the world
     * narrowed the forms itself ([base] differs from the defaults), its
     * choices win; its ornament, towers and variety always do.
     */
    fun rulesFor(id: String, base: GenomeRules = GenomeRules()): GenomeRules {
        val own = rules[id] ?: return base
        val d = GenomeRules()
        return own.copy(
            plans = if (base.plans != d.plans) base.plans else own.plans,
            roofs = if (base.roofs != d.roofs) base.roofs else own.roofs,
            families = if (base.families != d.families) base.families else own.families,
            walls = if (base.families != d.families) emptyList() else own.walls,
            accents = if (base.families != d.families) emptyList() else own.accents,
            roofMaterials = if (base.families != d.families) emptyList() else own.roofMaterials,
            minUpper = if (base.minUpper != d.minUpper || base.maxUpper != d.maxUpper) base.minUpper else own.minUpper,
            maxUpper = if (base.minUpper != d.minUpper || base.maxUpper != d.maxUpper) base.maxUpper else own.maxUpper,
            ornament = if (base.ornament != d.ornament) base.ornament else own.ornament,
            towers = if (base.towers != d.towers) base.towers else own.towers,
            variety = base.variety,
        )
    }
}
