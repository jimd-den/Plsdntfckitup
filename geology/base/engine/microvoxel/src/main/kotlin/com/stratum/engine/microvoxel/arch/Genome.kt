package com.stratum.engine.microvoxel.arch

import com.stratum.engine.microvoxel.M
import kotlin.random.Random

/** The outline of a building's plan inside its box. */
enum class PlanShape { RECT, L, T, U, COURTYARD, ROUND, OCTAGON, CROSS, STEPPED }

/** How a building is roofed. Towers take one too. */
enum class RoofForm { GABLE, HIP, FLAT, DOME, CONE, PYRAMID, BARREL, MANSARD, SAWTOOTH, ONION, TERRACE }

/** What is painted, laid or carved into the outer skin of the walls. */
enum class WallPattern { PLAIN, BANDS, PLINTH, QUOINS, CHECKER, ZIGZAG, DIAMOND, PILASTERS, FRIEZE }

/** The shape of an opening in an upper storey. */
enum class WindowForm { SLIT, SQUARE, ARCHED, ROUND, TALL, GRID, SHUTTERED }

/**
 * Materials that look right together: walls, what picks them out, what
 * roofs them and what frames them. A building takes one of each from a
 * family, so an earthen town never grows a glass tower by accident -- unless
 * its rules allow the modern family.
 */
data class MaterialFamily(
    val id: String,
    val walls: List<String>,
    val accents: List<String>,
    /** Roof materials as pairs: the main course and the one laid between. */
    val roofs: List<Pair<String, String>>,
    val trims: List<String>,
)

object MaterialFamilies {
    val EARTH = MaterialFamily(
        "earth",
        walls = listOf(A.ADOBE, A.RENDER, M.MUD, A.PISE, A.MUDBRICK, A.MUSGUM_EARTH, A.HAUSA_PLASTER),
        accents = listOf(A.ADOBE_DARK, M.MUD_DARK, A.HAUSA_RELIEF, A.RED_POLISH, M.NZU),
        roofs = listOf(M.THATCH to M.THATCH_DARK, A.MILLET_THATCH to M.THATCH_DARK, A.ADOBE to A.ADOBE_DARK),
        trims = listOf(A.TORON, A.POST, M.TIMBER),
    )
    val STONE = MaterialFamily(
        "stone",
        walls = listOf(A.DRYSTONE, A.AKSUM_STONE, A.CORAL_BLOCK, M.STONE, A.ROCK_HEWN),
        accents = listOf(A.DRYSTONE_DARK, M.DARK_STONE, A.AKSUM_TIMBER, A.LIME),
        roofs = listOf(M.ROOF_SLATE to M.DARK_STONE, A.DRYSTONE to A.DRYSTONE_DARK, M.ROOF_TILE to M.BRICK_DARK),
        trims = listOf(A.AKSUM_TIMBER, M.TIMBER, M.DARK_STONE),
    )
    val LIME = MaterialFamily(
        "lime",
        walls = listOf(A.LIME, M.PLASTER, A.PAINT_WHITE, A.CORAL_BLOCK),
        accents = listOf(A.ZELLIGE, A.NUBIAN_BLUE, A.OCHRE, A.CARVED_DOOR, A.NUBIAN_YELLOW),
        roofs = listOf(M.ROOF_TILE to M.BRICK_DARK, A.LIME to A.CORAL_BLOCK, A.ZELLIGE to A.LIME),
        trims = listOf(A.MANGROVE_POLE, A.CARVED_DOOR, A.ZELLIGE),
    )
    val BRICK = MaterialFamily(
        "brick",
        walls = listOf(M.BRICK, M.BRICK_DARK, A.MUDBRICK),
        accents = listOf(M.CONCRETE, M.PLASTER, A.LIME, M.DARK_STONE),
        roofs = listOf(M.ROOF_TILE to M.BRICK_DARK, M.ROOF_SLATE to M.DARK_STONE, M.METAL to M.CONCRETE),
        trims = listOf(M.TIMBER, M.CONCRETE, M.METAL),
    )
    val TIMBER = MaterialFamily(
        "timber",
        walls = listOf(M.TIMBER, A.POST, M.BARK, A.DUNG_PLASTER),
        accents = listOf(M.PLASTER, A.PAINT_BLACK, M.THATCH_DARK, A.OCHRE),
        roofs = listOf(M.THATCH to M.THATCH_DARK, A.GRASS_WEAVE to M.THATCH_DARK, A.RAFFIA to M.THATCH_DARK, M.ROOF_SLATE to M.DARK_STONE),
        trims = listOf(M.BARK, A.POST, A.MANGROVE_POLE),
    )
    val PAINTED = MaterialFamily(
        "painted",
        walls = listOf(A.PAINT_WHITE, A.NUBIAN_YELLOW, M.PLASTER_BLUE, A.OCHRE, A.KASSENA_RED),
        accents = listOf(A.NDEBELE_BLUE, A.NDEBELE_RED, A.NDEBELE_YELLOW, A.NDEBELE_GREEN, A.PAINT_BLACK),
        roofs = listOf(M.THATCH to M.THATCH_DARK, M.ROOF_TILE to M.BRICK_DARK, M.METAL to M.CONCRETE),
        trims = listOf(A.PAINT_BLACK, M.TIMBER, A.POST),
    )
    val MODERN = MaterialFamily(
        "modern",
        walls = listOf(M.CONCRETE, M.PLASTER, M.PLASTER_BLUE, M.SIDEWALK),
        accents = listOf(M.METAL, M.GLASS, M.CURB, M.BRICK),
        roofs = listOf(M.CONCRETE to M.CURB, M.METAL to M.CONCRETE, M.ASPHALT to M.CONCRETE),
        trims = listOf(M.METAL, M.CONCRETE, M.GLASS),
    )

    val all: List<MaterialFamily> = listOf(EARTH, STONE, LIME, BRICK, TIMBER, PAINTED, MODERN)
    val ids: List<String> = all.map { it.id }

    fun named(id: String): MaterialFamily = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("No material family '$id'. Known: ${ids.joinToString()}")

    /** The family nearest a tradition's own materials, for towns that vary their land's way of building. */
    fun forTradition(id: String): MaterialFamily = when (id) {
        "sudano_sahelian", "hausa", "dogon", "batammariba", "musgum", "nubian", "amazigh", "igbo", "yoruba" -> EARTH
        "great_zimbabwe", "aksumite" -> STONE
        "swahili" -> LIME
        "ndebele", "kassena" -> PAINTED
        "zulu", "pastoral", "asante", "benin" -> TIMBER
        else -> BRICK
    }
}

/**
 * What a town's buildings may be: the space a [BuildingGenome] is rolled
 * from. Every list is a whitelist, so a world description can say "only
 * domes and flat roofs, stone and lime, two to four storeys" and get exactly
 * that, in endless combinations.
 */
data class GenomeRules(
    val plans: List<PlanShape> = PlanShape.entries,
    val roofs: List<RoofForm> = RoofForm.entries,
    val families: List<String> = MaterialFamilies.ids,
    /** Storeys above the plan's own, least and most. */
    val minUpper: Int = 0,
    val maxUpper: Int = 2,
    /** 0 bare walls, 1 every building dressed in all it can carry. */
    val ornament: Float = 0.5f,
    /** Chance a building raises towers. */
    val towers: Float = 0.2f,
    /** How far a building strays from its town's shared look: 0 all alike, 1 each its own. */
    val variety: Float = 0.45f,
    /** Wall dress allowed; empty lets ornament decide among all. */
    val patterns: List<WallPattern> = emptyList(),
    val windows: List<WindowForm> = WindowForm.entries,
    val towerRoofs: List<RoofForm> = listOf(RoofForm.CONE, RoofForm.DOME, RoofForm.FLAT, RoofForm.ONION, RoofForm.PYRAMID),
    /** Chances that override the ornament's: jutting beams, a veranda, pinnacles. Null leaves them to [ornament]. */
    val beams: Float? = null,
    val veranda: Float? = null,
    val pinnacles: Float? = null,
    /** Exact material names to build with, overriding the families' own; empty keeps the families'. */
    val walls: List<String> = emptyList(),
    val accents: List<String> = emptyList(),
    val roofMaterials: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(plans.isNotEmpty() && roofs.isNotEmpty() && families.isNotEmpty()) { "genome rules need at least one plan, roof and material family" }
        require(minUpper in 0..MAX_UPPER && maxUpper in minUpper..MAX_UPPER) { "storeys above the ground are 0..$MAX_UPPER, least first" }
        families.forEach { MaterialFamilies.named(it) }
    }

    companion object {
        const val MAX_UPPER = 5
        const val ANY = "any"

        /**
         * Rules from stage options: `plans`, `roofs` and `materials` as
         * comma-separated names (or `any`), `storeys` as `2` or `1-3`, and
         * `ornament`, `towers` and `variety` from 0 to 1. Unknown names are an
         * error that says what is known.
         */
        fun parse(option: (String) -> String?): GenomeRules {
            val d = GenomeRules()
            fun <E : Enum<E>> list(key: String, all: List<E>): List<E> {
                val raw = option(key)?.trim()?.lowercase() ?: return all
                if (raw.isEmpty() || raw == ANY) return all
                return raw.split(',', ' ', '|').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
                    all.firstOrNull { it.name.lowercase() == name } ?: throw IllegalArgumentException(
                        "Unknown $key '$name'. Known: ${all.joinToString { it.name.lowercase() }}",
                    )
                }
            }
            val families = option("materials")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != ANY }
                ?.split(',', ' ', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.onEach { MaterialFamilies.named(it) } ?: d.families
            val storeys = option("storeys")?.trim()?.takeIf { it.isNotEmpty() && it != ANY }?.let { s ->
                val parts = s.split('-').map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("storeys is a number or a range like 1-3, not '$s'") }
                parts.first().coerceIn(0, MAX_UPPER) to parts.last().coerceIn(0, MAX_UPPER)
            }
            fun unit(key: String, default: Float) = option(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                (it.toFloatOrNull() ?: throw IllegalArgumentException("$key is a number from 0 to 1, not '$it'")).coerceIn(0f, 1f)
            } ?: default
            return GenomeRules(
                plans = list("plans", PlanShape.entries),
                roofs = list("roofs", RoofForm.entries),
                families = families,
                minUpper = storeys?.let { minOf(it.first, it.second) } ?: d.minUpper,
                maxUpper = storeys?.let { maxOf(it.first, it.second) } ?: d.maxUpper,
                ornament = unit("ornament", d.ornament),
                towers = unit("towers", d.towers),
                variety = unit("variety", d.variety),
            )
        }
    }
}

/**
 * One building's dials. Each is chosen on its own, so the number of distinct
 * buildings is the product of their choices -- see [VARIANTS] -- and every
 * one of them is drawn by the same painter ([ParametricTradition]).
 *
 * A town shares a look: each dial first takes the town's choice, and a
 * building strays from it with the rules' variety. So a street reads as one
 * place, and no two houses on it are quite the same.
 */
data class BuildingGenome(
    val plan: PlanShape,
    /** Storeys above the plan's own walls. */
    val upper: Int,
    /** Voxels to an upper storey. */
    val storeyHeight: Int,
    val family: String,
    val wall: String,
    val accent: String,
    val roofMaterial: String,
    val roofAlt: String,
    val trim: String,
    val pattern: WallPattern,
    val windows: WindowForm,
    val windowSpacing: Int,
    val roof: RoofForm,
    val pitch: Float,
    val eaves: Int,
    /** Voxels of stepped plinth around the foot. */
    val plinth: Int,
    val veranda: Boolean,
    /** 0, 1 (a minaret), 2 (flanking the back) or 4 (one at each corner). */
    val towers: Int,
    val towerRoof: RoofForm,
    val chimney: Boolean,
    val balcony: Boolean,
    /** Beams jutting from the wall (the Sahel's toron, a timber frame's joist ends). */
    val beams: Boolean,
    val pinnacles: Boolean,
    /** Upper storeys overhang the one below. */
    val jetty: Boolean,
    /** A roof garden on a flat roof. */
    val garden: Boolean,
    val finial: Boolean,
    /** Voxels each upper storey steps in, on a stepped plan. */
    val setback: Int,
    /** Dome height to its span. */
    val domeRatio: Float,
) {
    /** A line a player can read: what this building is. */
    val summary: String
        get() = buildList {
            add("${plan.name.lowercase()} plan")
            if (upper > 0) add("${upper + 1} storeys")
            add("${wall.substringAfter(':').replace('_', ' ')} walls")
            if (pattern != WallPattern.PLAIN) add("${pattern.name.lowercase()} ${accent.substringAfter(':').replace('_', ' ')}")
            add("${roof.name.lowercase()} roof of ${roofMaterial.substringAfter(':').replace('_', ' ')}")
            if (towers > 0) add(if (towers == 1) "a minaret" else "$towers ${towerRoof.name.lowercase()} towers")
            if (veranda) add("veranda")
            if (balcony) add("balconies")
            if (garden) add("roof garden")
        }.joinToString(", ")

    companion object {
        /**
         * How many distinct genomes the dials allow, before materials: the
         * size of the space a town's buildings are drawn from. Materials
         * multiply it again by every family's walls, accents, roofs and trims.
         */
        val VARIANTS: Double
            get() {
                val shapes = PlanShape.entries.size.toDouble() * (GenomeRules.MAX_UPPER + 1) * 5 // storey heights
                val skins = WallPattern.entries.size.toDouble() * WindowForm.entries.size * 5 // spacing
                val roofs = RoofForm.entries.size.toDouble() * 6 /* pitches */ * 4 /* eaves */ * 4 /* dome ratios */
                val extras = Math.pow(2.0, 9.0) * 4 /* tower counts */ * RoofForm.entries.size * 4 /* plinths */ * 4 /* setbacks */
                val materials = MaterialFamilies.all.sumOf { f -> f.walls.size.toDouble() * f.accents.size * f.roofs.size * f.trims.size }
                return shapes * skins * roofs * extras * materials
            }

        /**
         * The genome of the building [seed] in the town [town], within
         * [rules]. The same numbers give the same building on every device.
         */
        fun roll(seed: Long, town: Long, rules: GenomeRules = GenomeRules()): BuildingGenome {
            val own = Random(seed)
            val stray = rules.variety
            // Each dial draws the town's choice with its own salt, and the building's with its own dice.
            fun <T> pick(options: List<T>, salt: Int): T {
                val shared = options[Random(town * 31 + salt).nextInt(options.size)]
                return if (own.nextFloat() < stray) options[own.nextInt(options.size)] else shared
            }
            fun chance(p: Float, salt: Int): Boolean {
                val shared = Random(town * 131 + salt).nextFloat() < p
                return if (own.nextFloat() < stray) own.nextFloat() < p else shared
            }
            val family = MaterialFamilies.named(pick(rules.families, 1))
            val roofPair = pick(rules.roofMaterials.ifEmpty { family.roofs }, 5)
            val ornament = rules.ornament
            val roof = pick(rules.roofs, 9)
            val upper = if (rules.maxUpper == rules.minUpper) rules.minUpper
            else rules.minUpper + (if (own.nextFloat() < stray) own.nextInt(rules.maxUpper - rules.minUpper + 1)
            else Random(town * 31 + 2).nextInt(rules.maxUpper - rules.minUpper + 1))
            val towers = if (!chance(rules.towers, 20)) 0 else pick(listOf(1, 2, 2, 4), 21)
            val patterns = rules.patterns.ifEmpty { WallPattern.entries.drop(1) }
            return BuildingGenome(
                plan = pick(rules.plans, 0),
                upper = upper,
                storeyHeight = pick(listOf(9, 10, 11, 12, 14), 3),
                family = family.id,
                wall = pick(rules.walls.ifEmpty { family.walls }, 4),
                accent = pick(rules.accents.ifEmpty { family.accents }, 6),
                roofMaterial = roofPair.first,
                roofAlt = roofPair.second,
                trim = pick(family.trims, 7),
                pattern = if (rules.patterns.isNotEmpty() || chance(0.25f + 0.75f * ornament, 8)) pick(patterns, 10) else WallPattern.PLAIN,
                windows = pick(rules.windows, 11),
                windowSpacing = pick(listOf(6, 7, 8, 10, 12), 12),
                roof = roof,
                pitch = pick(listOf(0.5f, 0.65f, 0.8f, 1f, 1.2f, 1.5f), 13),
                eaves = pick(listOf(0, 1, 2, 3), 14),
                plinth = pick(listOf(0, 2, 3, 4), 15),
                veranda = chance(rules.veranda ?: (0.15f + 0.4f * ornament), 16),
                towers = towers,
                towerRoof = pick(rules.towerRoofs, 22),
                chimney = chance(0.25f, 17),
                balcony = upper > 0 && chance(0.2f + 0.5f * ornament, 18),
                beams = chance(rules.beams ?: (0.1f + 0.3f * ornament), 19),
                pinnacles = chance(rules.pinnacles ?: (0.1f + 0.5f * ornament), 23),
                jetty = upper > 0 && chance(0.25f, 24),
                garden = chance(0.3f + 0.3f * ornament, 25),
                finial = chance(0.3f + 0.6f * ornament, 26),
                setback = pick(listOf(2, 3, 4, 6), 27),
                domeRatio = pick(listOf(0.7f, 0.9f, 1.1f, 1.4f), 28),
            )
        }
    }
}
