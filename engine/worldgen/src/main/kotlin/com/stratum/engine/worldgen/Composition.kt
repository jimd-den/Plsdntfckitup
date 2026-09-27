package com.stratum.engine.worldgen

import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.Landmark
import com.stratum.core.domain.world.TerrainRecipe
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The deliberate layout of the land: paths, the landmarks they lead to, and
 * the groves and clearings scenery grows in.
 *
 * The layered generator's composition, answerable for any column so that a
 * tree rooted in the next chunk, or a structure deciding where to stand, sees
 * the same paths and clearings the decoration pass draws.
 */
class Composition(private val services: WorldServices, private val recipe: TerrainRecipe) {

    private val grove = services.noise(GROVE_SALT)
    private val paths = services.noise(PATH_SALT)

    /** Landmark sites by grid cell; [NO_SITE] where a cell has none. */
    private val sites = ConcurrentHashMap<Long, Any>()

    /** A landmark placed in the world, with the height its pad is levelled to. */
    class Site(val x: Int, val y: Int, val z: Int, val landmark: Landmark)

    /** How thickly scenery grows here, around 1; see [TerrainRecipe.scatterDensity]. */
    fun density(x: Int, y: Int): Float =
        recipe.scatterDensity(grove.fractal(x * recipe.scatterClusterScale, y * recipe.scatterClusterScale, octaves = 2))

    /**
     * Roughly how many blocks a column is from the nearest path.
     *
     * Paths are the 0.5 contour of a slow noise field: a winding line that
     * never ends abruptly and never crosses itself. The field's value over its
     * slope is the distance to the contour, so a path keeps its width through
     * gentle and tight bends alike.
     */
    fun pathDistance(x: Int, y: Int): Float {
        fun at(px: Int, py: Int) = paths.fractal(px * PATH_SCALE, py * PATH_SCALE, octaves = 2)
        val here = at(x, y) - 0.5f
        val gx = (at(x + 1, y) - at(x - 1, y)) / 2f
        val gy = (at(x, y + 1) - at(x, y - 1)) / 2f
        val slope = sqrt(gx * gx + gy * gy).coerceAtLeast(MIN_PATH_SLOPE)
        return abs(here) / slope
    }

    /** Whether a column of [biome] lies on its path, and so is trodden bare. */
    fun onPath(x: Int, y: Int, biome: BiomeDefinition): Boolean =
        biome.composition.pathBlockId != null && pathDistance(x, y) <= biome.composition.pathWidth / 2f

    /** Whether scenery is kept off a column: a path and its verge, or a landmark's clearing. */
    fun isClear(x: Int, y: Int, biome: BiomeDefinition): Boolean {
        if (biome.composition.pathBlockId != null && pathDistance(x, y) <= biome.composition.pathWidth / 2f + PATH_VERGE) return true
        val site = siteAround(x, y) ?: return false
        return distance(x - site.x, y - site.y) <= site.landmark.clearRadius
    }

    /**
     * The landmark whose clearing holds this column, if any.
     *
     * Sites sit one per cell of a fixed world grid, never nearer a cell's edge
     * than the largest clearing, so a column can only be in its own cell's
     * clearing: one lookup, and chunks stay independent.
     */
    fun siteAround(x: Int, y: Int): Site? {
        val cx = Math.floorDiv(x, SITE_GRID)
        val cy = Math.floorDiv(y, SITE_GRID)
        val key = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)
        val site = (sites[key] ?: (siteIn(cx, cy) ?: NO_SITE).also {
            if (sites.size > CACHE) sites.clear()
            sites[key] = it
        }) as? Site ?: return null
        return site.takeIf { distance(x - it.x, y - it.y) <= it.landmark.clearRadius }
    }

    private fun siteIn(cx: Int, cy: Int): Site? {
        val margin = Landmark.MAX_CLEAR_RADIUS
        val span = SITE_GRID - 2 * margin
        val seed = services.seed
        val biomes = services.biomes
        // Several candidate spots; the one nearest a path wins, so roads run
        // through set pieces instead of passing a shrine by twenty blocks.
        var bestX = 0
        var bestY = 0
        var best = Float.MAX_VALUE
        for (k in 0 until SITE_CANDIDATES) {
            val x = cx * SITE_GRID + margin + PositionalRandom.intAt(seed, cx, cy, SITE_SALT + 1 + k * 2, span)
            val y = cy * SITE_GRID + margin + PositionalRandom.intAt(seed, cx, cy, SITE_SALT + 2 + k * 2, span)
            val score = if (biomes.biomeAt(x, y).composition.pathBlockId != null) pathDistance(x, y).coerceAtMost(FAR) else 0f
            if (score < best) {
                best = score
                bestX = x
                bestY = y
            }
        }
        val landmark = biomes.biomeAt(bestX, bestY).composition.landmark ?: return null
        if (PositionalRandom.floatAt(seed, cx, cy, SITE_SALT) >= landmark.chance) return null
        // Only in the open: a shrine half-buried in a thicket is clutter, not a place.
        if (density(bestX, bestY) > CLEARING_DENSITY) return null
        return Site(bestX, bestY, services.surfaceAt(bestX, bestY), landmark)
    }

    private companion object {
        const val GROVE_SALT = 53_029L
        const val PATH_SALT = 71_003L
        const val SITE_GRID = 44
        const val SITE_SALT = 9_001
        const val SITE_CANDIDATES = 12
        const val CLEARING_DENSITY = 0.9f
        const val CACHE = 4_096
        const val PATH_SCALE = 0.02f
        const val MIN_PATH_SLOPE = 0.012f
        const val PATH_VERGE = 1.5f
        const val FAR = 1_000f
        val NO_SITE = Any()
    }
}

internal fun distance(dx: Int, dy: Int): Float = sqrt((dx * dx + dy * dy).toFloat())
