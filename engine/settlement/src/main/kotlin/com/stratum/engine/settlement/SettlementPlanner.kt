package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.settlement.culture.CityGenerator
import com.stratum.core.domain.settlement.culture.Mark
import com.stratum.core.domain.world.Chunk
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.random.Random

/**
 * Decides where every town in a world stands, and plans each one.
 *
 * The world is cut into square cells [CELL] blocks across. Each cell holds at
 * most one town, kept far enough inside the cell that two towns can never
 * touch. Whether a cell has a town, which recipe it follows and every street
 * in it are functions of the world seed and the cell alone, so a chunk can be
 * generated in any order and still draw its part of the same town.
 */
class SettlementPlanner(
    private val seed: Long,
    private val recipes: List<SettlementRecipe>,
    /** The biome id at a column, for recipes that only belong in some regions. */
    private val biomeAt: (x: Int, y: Int) -> String?,
    /** The natural ground height at a column, which a town is levelled to. */
    private val groundAt: (x: Int, y: Int) -> Int,
    private val layouts: SettlementLayouts = SettlementLayouts.standard,
    /** Puts a town on the world origin, where the player arrives. */
    private val startingTown: Boolean = false,
    /** Scales every recipe's chance: the world rules' town density. */
    private val density: Float = 1f,
    /** Which recipes are safe to begin in. By default, towns with nobody guarding them against the player. */
    private val welcoming: (SettlementRecipe) -> Boolean = { it.garrison.isEmpty() },
    /** How the starting town is shaped, when a player has changed it; the default leaves it to the seed. */
    private val home: HomeTown = HomeTown(),
    /**
     * The people whose way of building a town at (x, y) follows, by
     * building-tradition id, for towns laid out by their culture; null leaves
     * it to the recipe's own people.
     */
    private val cultureAt: (x: Int, y: Int) -> String? = { _, _ -> null },
) : SettlementAtlas {

    private val plans = ConcurrentHashMap<Long, Any>()

    override fun settlementsNear(x: Int, y: Int, radius: Int): List<SettlementPlan> {
        if (recipes.isEmpty()) return emptyList()
        val reach = radius + SettlementRecipe.MAX_RADIUS + BLEND
        val minCellX = floor((x - reach).toFloat() / CELL).toInt()
        val maxCellX = floor((x + reach).toFloat() / CELL).toInt()
        val minCellY = floor((y - reach).toFloat() / CELL).toInt()
        val maxCellY = floor((y + reach).toFloat() / CELL).toInt()
        return (minCellY..maxCellY).flatMap { cy -> (minCellX..maxCellX).mapNotNull { cx -> planFor(cx, cy) } }
            .filter { it.contains(x, y, radius + BLEND) }
    }

    /** The town in cell ([cellX], [cellY]), or null when the cell has none. Planned once, then remembered. */
    fun planFor(cellX: Int, cellY: Int): SettlementPlan? {
        val key = (cellX.toLong() shl 32) xor (cellY.toLong() and 0xFFFFFFFFL)
        val cached = plans.getOrPut(key) { plan(cellX, cellY) ?: NONE }
        return cached as? SettlementPlan
    }

    private fun plan(cellX: Int, cellY: Int): SettlementPlan? {
        val starting = startingTown && cellX == 0 && cellY == 0
        val random = Random(seed * PRIME_A + cellX * PRIME_B + cellY * PRIME_C + if (starting) home.variant * PRIME_A else 0L)
        // The starting town sits on the corner of its cell rather than inside
        // it, so its neighbours stay empty to guarantee nothing touches it.
        if (startingTown && !starting && kotlin.math.abs(cellX) <= 1 && kotlin.math.abs(cellY) <= 1) return null
        val centerX = if (starting) 0 else cellX * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        val centerY = if (starting) 0 else cellY * CELL + MARGIN + random.nextInt(CELL - 2 * MARGIN)
        val chosen = (if (starting) home.recipeId?.let { id -> recipes.firstOrNull { it.id == id } } else null)
            ?: chooseRecipe(biomeAt(centerX, centerY), random, starting) ?: return null
        val recipe = if (starting) home.applyTo(chosen) else chosen
        val rolled = recipe.minRadius + random.nextInt(recipe.maxRadius - recipe.minRadius + 1)
        // A town of a people's own making: its character first, which sets how big a place it is.
        val character = if (recipe.layoutId == SettlementRecipe.AFRICAN) {
            CityGenerator.roll(random.nextLong(), cultureAt(centerX, centerY) ?: CultureOfRecipe.of(recipe), gathering = starting)
        } else null
        val sized = character?.let { c ->
            val grown = if (Mark.GROWTH in c.marks) GROWN else 1f
            (rolled * c.form.scale.radius * grown).toInt().coerceIn(SettlementRecipe.MIN_RADIUS, SettlementRecipe.MAX_RADIUS)
        } ?: rolled
        val radius = if (starting) home.radiusFor(sized) else sized
        val site = SettlementSite(centerX, centerY, groundLevel(centerX, centerY, recipe), radius, character)
        val layout = layouts.layoutFor(recipe.layoutId).arrange(site, recipe, random)
        val name = character?.name ?: recipe.names.takeIf { it.isNotEmpty() }?.random(random) ?: recipe.name
        // The player's own say over their home town's wall stands over the town's character.
        val built = (layout.recipe ?: recipe).let { b ->
            when {
                !starting || home.walled == null -> b
                home.walled -> b.copy(wallBlockId = b.wallBlockId ?: recipe.wallBlockId ?: recipe.foundationBlockId)
                else -> b.copy(wallBlockId = null)
            }
        }
        return SettlementPlan(
            id = "${recipe.id}@$cellX,$cellY",
            name = name,
            recipe = built,
            centerX = centerX,
            centerY = centerY,
            groundZ = site.groundZ,
            radius = radius,
            roads = layout.roads,
            buildings = layout.buildings,
            walled = built.wallBlockId != null,
            square = layout.square,
            character = character,
        )
    }

    /** Which recipe a site gets, if any: weighted among those allowed here, then each one's own chance. */
    private fun chooseRecipe(biomeId: String?, random: Random, starting: Boolean): SettlementRecipe? {
        val eligible = recipes.filter { it.biomeIds.isEmpty() || biomeId in it.biomeIds }
        // The starting town is always friendly ground if the packs offer one.
        val pool = if (starting) eligible.filter(welcoming).ifEmpty { eligible } else eligible
        val total = pool.sumOf { it.weight.coerceAtLeast(0) }
        if (total <= 0) return null
        var roll = random.nextInt(total)
        val picked = pool.firstOrNull { roll -= it.weight.coerceAtLeast(0); roll < 0 } ?: return null
        return picked.takeIf { starting || random.nextFloat() < picked.chance * density }
    }

    /**
     * The height the town is levelled to: the land at its centre, kept low
     * enough that its tallest building and its roof still fit in the world.
     */
    private fun groundLevel(x: Int, y: Int, recipe: SettlementRecipe): Int {
        val tallest = (recipe.buildings.maxOfOrNull { it.height } ?: 0).coerceAtLeast(recipe.wallHeight)
        val ceiling = Chunk.HEIGHT - tallest - BuildingTemplate.MAX_SIZE / 2 - HEADROOM
        return groundAt(x, y).coerceIn(MIN_GROUND, ceiling)
    }

    companion object {
        /** Blocks per side of a site cell: two of the largest towns, their blends and a gap. */
        const val CELL = 2 * (SettlementRecipe.MAX_RADIUS + 16)
        /** The ring outside a town where its level ground eases back into the land. */
        const val BLEND = 8
        private const val MARGIN = SettlementRecipe.MAX_RADIUS + BLEND
        private const val MIN_GROUND = 4
        private const val HEADROOM = 2
        /** A town whose history says it grew stands this much wider. */
        private const val GROWN = 1.15f
        private const val PRIME_A = 6364136223846793005L
        private const val PRIME_B = 1442695040888963407L
        private const val PRIME_C = 2862933555777941757L
        private val NONE = Any()
    }
}

/**
 * The player's say over their home town, which otherwise follows the seed
 * like any other town. Everything here is optional: null or the neutral
 * value keeps what the seed would have built.
 */
data class HomeTown(
    /** Which recipe the home town follows, by id; null picks a welcoming one as usual. */
    val recipeId: String? = null,
    /** Scales the town's radius, 0.5 (a hamlet) to 2 (a city), within the recipe's legal range. */
    val size: Float = 1f,
    /** The street pattern, by layout id; null keeps the recipe's. */
    val layoutId: String? = null,
    /** Whether the town has its wall; null keeps the recipe's. */
    val walled: Boolean? = null,
    /** Another roll of the same town: moves every house while keeping its kind and size. */
    val variant: Int = 0,
) {
    fun applyTo(recipe: SettlementRecipe): SettlementRecipe {
        var r = recipe
        layoutId?.let { r = r.copy(layoutId = it) }
        when (walled) {
            false -> r = r.copy(wallBlockId = null)
            true -> if (r.wallBlockId == null) r = r.copy(wallBlockId = r.foundationBlockId)
            null -> Unit
        }
        return r
    }

    fun radiusFor(rolled: Int): Int =
        (rolled * size).toInt().coerceIn(SettlementRecipe.MIN_RADIUS, SettlementRecipe.MAX_RADIUS)
}
