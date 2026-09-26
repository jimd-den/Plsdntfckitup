package com.stratum.engine.settlement

import com.stratum.core.domain.settlement.Facing
import com.stratum.core.domain.settlement.Road
import com.stratum.core.domain.settlement.SettlementRecipe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A market town grown out from its square: streets radiate from the centre
 * and a ring road ties them together, buildings face whichever street they
 * stand on. The medieval default, and the fallback for unknown layout ids.
 */
object OrganicLayout : SettlementLayout {
    override val id = SettlementRecipe.ORGANIC

    override fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout {
        val plaza = plazaRadius(site)
        val spokes = 3 + random.nextInt(3) + if (site.radius > LARGE) 1 else 0
        val start = random.nextDouble() * 2 * PI
        val angles = List(spokes) { start + it * 2 * PI / spokes + (random.nextDouble() - 0.5) * SPOKE_JITTER }
        val spokeRoads = angles.map { angle -> radial(site, angle, plaza.toDouble(), (site.radius + EXIT).toDouble(), MAIN_ROAD) }
        val ring = if (site.radius >= RING_FROM) ring(site, site.radius * RING_SHARE, RING_SEGMENTS, SIDE_ROAD) else emptyList()
        val roads = spokeRoads + ring
        val packer = LotPacker(site, recipe, random, roads, square = false, plazaRadius = plaza)
        packer.hall()?.let { hall ->
            val between = angles.first() + PI / spokes
            placeFacingCentre(packer, site, hall, between, plaza + hall.depth / 2f + 1f)
        }
        packer.lineRoads(spokeRoads)
        packer.lineRoads(ring)
        return Layout(roads, packer.placed)
    }
}

/**
 * Streets on a grid: a planned city, a colonial fort town, the lower tiers
 * of a hive. Square, and dense.
 */
object GridLayout : SettlementLayout {
    override val id = SettlementRecipe.GRID

    override fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout {
        val spacing = BLOCK_MIN + random.nextInt(BLOCK_SPREAD)
        val reach = site.radius + EXIT
        val offsets = (-site.radius / spacing..site.radius / spacing).map { it * spacing }
        val roads = offsets.flatMap { offset ->
            listOf(
                Road(site.centerX + offset, site.centerY - reach, site.centerX + offset, site.centerY + reach, MAIN_ROAD),
                Road(site.centerX - reach, site.centerY + offset, site.centerX + reach, site.centerY + offset, MAIN_ROAD),
            )
        }
        val packer = LotPacker(site, recipe, random, roads, square = true, plazaRadius = GRID_PLAZA)
        packer.hall()?.let { hall -> packer.placeCentred(hall, site.centerX + spacing / 2f, site.centerY - spacing / 2f, Facing.SOUTH) }
        packer.lineRoads()
        return Layout(roads, packer.placed, square = true)
    }

    private const val BLOCK_MIN = 12
    private const val BLOCK_SPREAD = 4
    private const val GRID_PLAZA = 3
}

/**
 * A square walled fort: a keep to the north of the yard, two roads crossing
 * at the gatehouses, and barracks and towers along an inner lane that runs
 * round inside the wall.
 */
object FortressLayout : SettlementLayout {
    override val id = SettlementRecipe.FORTRESS

    override fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout {
        val reach = site.radius + EXIT
        val cross = listOf(
            Road(site.centerX, site.centerY - reach, site.centerX, site.centerY + reach, MAIN_ROAD),
            Road(site.centerX - reach, site.centerY, site.centerX + reach, site.centerY, MAIN_ROAD),
        )
        val inner = site.radius - INNER_LANE
        val lane = listOf(
            Road(site.centerX - inner, site.centerY - inner, site.centerX + inner, site.centerY - inner, SIDE_ROAD),
            Road(site.centerX + inner, site.centerY - inner, site.centerX + inner, site.centerY + inner, SIDE_ROAD),
            Road(site.centerX + inner, site.centerY + inner, site.centerX - inner, site.centerY + inner, SIDE_ROAD),
            Road(site.centerX - inner, site.centerY + inner, site.centerX - inner, site.centerY - inner, SIDE_ROAD),
        )
        val roads = cross + lane
        val packer = LotPacker(site, recipe, random, roads, square = true, plazaRadius = plazaRadius(site))
        packer.hall()?.let { keep -> packer.placeCentred(keep, site.centerX + 0.5f, site.centerY - site.radius * KEEP_SHARE, Facing.SOUTH) }
        packer.lineRoads(lane)
        packer.lineRoads(cross)
        return Layout(roads, packer.placed, square = true)
    }

    private const val INNER_LANE = 6
    private const val KEEP_SHARE = 0.4f
}

/**
 * A camp: tents in rings facing a central fire, paths out through the
 * palisade at the four quarters. What a warband, a nomad clan or a siege
 * army builds, and the sparsest layout, so it reads as temporary.
 */
object CampLayout : SettlementLayout {
    override val id = SettlementRecipe.CAMP

    override fun arrange(site: SettlementSite, recipe: SettlementRecipe, random: Random): Layout {
        val plaza = plazaRadius(site)
        val roads = List(4) { radial(site, it * PI / 2, plaza.toDouble(), (site.radius + EXIT).toDouble(), PATH) }
        val packer = LotPacker(site, recipe, random, roads, square = false, plazaRadius = plaza)
        packer.hall()?.let { hall -> placeFacingCentre(packer, site, hall, PI / 4, plaza + hall.depth / 2f + 1f) }
        RINGS.forEach { share -> ringOfTents(packer, site, site.radius * share, random) }
        return Layout(roads, packer.placed)
    }

    private fun ringOfTents(packer: LotPacker, site: SettlementSite, radius: Float, random: Random) {
        val offset = random.nextDouble() * 2 * PI
        val slots = (2 * PI * radius / TENT_SLOT).toInt().coerceAtLeast(3)
        repeat(slots) { i ->
            val template = packer.nextTemplate() ?: return
            placeFacingCentre(packer, site, template, offset + i * 2 * PI / slots, radius)
        }
    }

    private val RINGS = listOf(0.42f, 0.72f)
    private const val TENT_SLOT = 8f
    private const val PATH = 2
}

// ---- shared street geometry ----------------------------------------------------

/** A straight road from [from] to [to] blocks out from the centre, along [angle]. */
internal fun radial(site: SettlementSite, angle: Double, from: Double, to: Double, width: Int) = Road(
    (site.centerX + cos(angle) * from).roundToInt(),
    (site.centerY + sin(angle) * from).roundToInt(),
    (site.centerX + cos(angle) * to).roundToInt(),
    (site.centerY + sin(angle) * to).roundToInt(),
    width,
)

/** A ring road as a polygon of [segments] straight pieces. */
internal fun ring(site: SettlementSite, radius: Float, segments: Int, width: Int): List<Road> = List(segments) { i ->
    val a = i * 2 * PI / segments
    val b = (i + 1) * 2 * PI / segments
    Road(
        (site.centerX + cos(a) * radius).roundToInt(), (site.centerY + sin(a) * radius).roundToInt(),
        (site.centerX + cos(b) * radius).roundToInt(), (site.centerY + sin(b) * radius).roundToInt(),
        width,
    )
}

/** Places [template] [distance] out along [angle], its door towards the centre. */
internal fun placeFacingCentre(packer: LotPacker, site: SettlementSite, template: com.stratum.core.domain.settlement.BuildingTemplate, angle: Double, distance: Float): Boolean {
    val cx = site.centerX + 0.5f + (cos(angle) * distance).toFloat()
    val cy = site.centerY + 0.5f + (sin(angle) * distance).toFloat()
    return packer.placeCentred(template, cx, cy, LotPacker.facingToward(-cos(angle).toFloat(), -sin(angle).toFloat()))
}

/** The square in the middle, kept clear: where the player arrives. */
internal fun plazaRadius(site: SettlementSite): Int = (site.radius / PLAZA_DIVISOR).coerceIn(PLAZA_MIN, PLAZA_MAX)

/** How far a road runs past the wall, so a gate leads somewhere. */
internal const val EXIT = 6
internal const val MAIN_ROAD = 3
internal const val SIDE_ROAD = 2
private const val LARGE = 30
private const val RING_FROM = 20
private const val RING_SHARE = 0.62f
private const val RING_SEGMENTS = 10
private const val SPOKE_JITTER = 0.5
private const val PLAZA_DIVISOR = 6
private const val PLAZA_MIN = 4
private const val PLAZA_MAX = 7
