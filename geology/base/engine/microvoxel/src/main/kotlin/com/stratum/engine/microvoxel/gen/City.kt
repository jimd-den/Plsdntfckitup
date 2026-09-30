package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An inclusive rectangle of world columns, in microvoxels. */
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0 + 1
    val depth: Int get() = y1 - y0 + 1
    fun contains(x: Int, y: Int) = x in x0..x1 && y in y0..y1
    fun intersects(o: Rect) = x0 <= o.x1 && x1 >= o.x0 && y0 <= o.y1 && y1 >= o.y0
    fun inset(d: Int) = Rect(x0 + d, y0 + d, x1 - d, y1 - d)
    fun grow(d: Int) = inset(-d)

    /** Distance outside the rectangle, 0 inside. */
    fun distance(x: Int, y: Int): Float {
        val dx = max(0, max(x0 - x, x - x1)); val dy = max(0, max(y0 - y, y - y1))
        return kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
    }
}

enum class RoadKind(val width: Int, val sidewalk: Int) {
    /** Region-border boulevards: they are what joins one region's streets to the next. */
    ARTERIAL(width = 44, sidewalk = 7),
    STREET(width = 28, sidewalk = 5),
}

/** A straight road; [alongX] says which way traffic runs. Sidewalks are its outer bands. */
data class Road(val rect: Rect, val kind: RoadKind, val alongX: Boolean)

enum class LotUse { BUILDING, PARK }

/** A parcel: what stands on it is decided here, how it looks by an [ArchitectureStyle]. */
data class Lot(
    val rect: Rect,
    val use: LotUse,
    /** Which [ArchitectureStyle] builds here. */
    val style: String,
    val floors: Int,
    /** Which side faces the street: 0 = -y, 1 = +x, 2 = +y, 3 = -x. Doors and shopfronts go here. */
    val front: Int,
    val seed: Long,
)

/** One region's plan: rural regions have no roads and no lots, only their boundary arterials if a neighbour is urban. */
class CityRegion(
    val rx: Int,
    val ry: Int,
    val urban: Boolean,
    val plateau: Float,
    val density: Float,
    val bounds: Rect,
    val roads: List<Road>,
    val lots: List<Lot>,
)

/**
 * The city map: an infinite grid of square regions, each planned alone.
 *
 * ## How a region is planned
 *
 * 1. **Urban or wild.** A slow noise field (plus the `density` option) and the
 *    lie of the land decide; a region under the sea or across a mountain
 *    stays wild.
 * 2. **Plateau.** An urban region takes the natural height at its centre,
 *    snapped to a whole block, and the terrain is blended onto it (see
 *    [surfaceAt]) so streets are walkable and buildings stand level.
 * 3. **Boulevards.** Every region owns the arterial along its west and south
 *    edges if it or the neighbour across is urban. Region edges are shared,
 *    so neighbouring regions agree on them without talking -- the whole road
 *    network is continuous across an infinite map.
 * 4. **Streets.** The interior is split by recursive, jittered bisection
 *    (a BSP, as in Parish & Müller's block subdivision) until blocks are
 *    walkable-sized; every split lays a street across its whole parent, so
 *    every street meets another street or a boulevard.
 * 5. **Lots.** Each block is cut into one or two rows of frontages, so every
 *    lot faces a street; the lot's use and style depend on how far it sits
 *    from the region's centre -- towers downtown, terraces, then villas and
 *    parks at the edge.
 *
 * Swap any step by publishing a different [CityPlanner] under
 * [CityPlanStage.KEY]: the road, building and vegetation stages only read
 * the interface.
 */
interface CityPlanner : Footprint {
    val regionSize: Int
    fun region(rx: Int, ry: Int): CityRegion

    /** Regions whose plans may touch [area]. */
    fun regionsTouching(area: Rect): List<CityRegion> {
        val m = RoadKind.ARTERIAL.width
        val out = ArrayList<CityRegion>(4)
        for (ry in Math.floorDiv(area.y0 - m, regionSize)..Math.floorDiv(area.y1 + m, regionSize))
            for (rx in Math.floorDiv(area.x0 - m, regionSize)..Math.floorDiv(area.x1 + m, regionSize)) out += region(rx, ry)
        return out
    }
}

class DefaultCityPlanner(
    private val seed: Long,
    private val natural: HeightFunction,
    private val sea: Int,
    override val regionSize: Int,
    private val density: Float,
    private val styles: ArchitectureRegistry,
    /** No building rises above this many floors; a world with a low ceiling says so here. */
    private val maxFloors: Int = Int.MAX_VALUE,
) : CityPlanner {

    private val noise = Noise(seed xor 0xC17L)
    private val cache = ConcurrentHashMap<Long, CityRegion>()

    /** Urban score and plateau per region: asked for every column by the terrain blend, so remembered. */
    private val scores = ConcurrentHashMap<Long, FloatArray>()

    private fun info(rx: Int, ry: Int): FloatArray {
        val key = (rx.toLong() shl 32) or (ry.toLong() and 0xFFFFFFFFL)
        scores[key]?.let { return it }
        if (scores.size > 16384) scores.clear()
        return floatArrayOf(computeScore(rx, ry), computePlateau(rx, ry)).also { scores[key] = it }
    }

    /** Cheap: decides urban-ness without planning streets, for the terrain blend. */
    private fun isUrban(rx: Int, ry: Int): Boolean = urbanScore(rx, ry) > 0f

    private fun urbanScore(rx: Int, ry: Int): Float = info(rx, ry)[0]

    private fun plateau(rx: Int, ry: Int): Float = info(rx, ry)[1]

    private fun computeScore(rx: Int, ry: Int): Float {
        val n = noise.fbm(rx * 0.31f + 3.1f, ry * 0.31f - 7.7f, 3) + density - 0.5f
        if (n <= 0f) return 0f
        val r = regionSize
        val c = natural.heightAt(rx * r + r / 2, ry * r + r / 2)
        if (c < sea + 6) return 0f
        var lo = c; var hi = c
        for ((ox, oy) in CORNERS) {
            val h = natural.heightAt(rx * r + (ox * r).toInt(), ry * r + (oy * r).toInt())
            lo = min(lo, h); hi = max(hi, h)
        }
        if (hi - lo > r * 0.35f) return 0f // too rugged to build a town on
        return n
    }

    private fun computePlateau(rx: Int, ry: Int): Float {
        val r = regionSize
        val c = natural.heightAt(rx * r + r / 2, ry * r + r / 2)
        return ((c / BLOCK).roundToInt() * BLOCK).toFloat().coerceAtLeast((sea + 8).toFloat())
    }

    override fun region(rx: Int, ry: Int): CityRegion {
        val key = (rx.toLong() shl 32) or (ry.toLong() and 0xFFFFFFFFL)
        cache[key]?.let { return it }
        if (cache.size > 4096) cache.clear()
        return plan(rx, ry).also { cache[key] = it }
    }

    private fun plan(rx: Int, ry: Int): CityRegion {
        val r = regionSize
        val bounds = Rect(rx * r, ry * r, rx * r + r - 1, ry * r + r - 1)
        val score = urbanScore(rx, ry)
        val urban = score > 0f
        val roads = ArrayList<Road>()
        val a = RoadKind.ARTERIAL.width
        val half = a / 2
        // West and south boulevards, centred on the region edge, running a half-width past each corner so crossings join.
        if (urban || isUrban(rx - 1, ry)) roads += Road(Rect(bounds.x0 - half, bounds.y0 - half, bounds.x0 - half + a - 1, bounds.y1 + half), RoadKind.ARTERIAL, alongX = false)
        if (urban || isUrban(rx, ry - 1)) roads += Road(Rect(bounds.x0 - half, bounds.y0 - half, bounds.x1 + half, bounds.y0 - half + a - 1), RoadKind.ARTERIAL, alongX = true)
        if (!urban) return CityRegion(rx, ry, false, natural.heightAt(bounds.x0 + r / 2, bounds.y0 + r / 2), 0f, bounds, roads, emptyList())

        // The neighbours' boulevards start at their edge minus half a width, so the interior stops one short of that.
        val interior = Rect(bounds.x0 + half, bounds.y0 + half, bounds.x1 - half, bounds.y1 - half)
        val blocks = ArrayList<Rect>()
        split(interior, rx, ry, 0, roads, blocks)
        val lots = ArrayList<Lot>()
        val cx = (bounds.x0 + bounds.x1) / 2f; val cy = (bounds.y0 + bounds.y1) / 2f
        val intensity = (score * 2.2f + density * 0.6f).coerceIn(0f, 1.2f)
        for ((i, block) in blocks.withIndex()) subdivide(block, rx, ry, i, cx, cy, intensity, lots)
        return CityRegion(rx, ry, true, plateau(rx, ry), intensity, bounds, roads, lots)
    }

    private fun split(area: Rect, rx: Int, ry: Int, depth: Int, roads: MutableList<Road>, blocks: MutableList<Rect>) {
        val alongX = area.width >= area.depth
        val long = if (alongX) area.width else area.depth
        val w = RoadKind.STREET.width
        if (long < MAX_BLOCK || depth > 6) { blocks += area; return }
        val salt = depth * 31 + area.x0 * 7 + area.y0 * 13
        val t = 0.36f + Hash.unit(seed, rx, ry, salt, 5) * 0.28f
        if (alongX) {
            val cut = area.x0 + (area.width * t).toInt() - w / 2
            roads += Road(Rect(cut, area.y0 - 4, cut + w - 1, area.y1 + 4), RoadKind.STREET, alongX = false)
            split(Rect(area.x0, area.y0, cut - 1, area.y1), rx, ry, depth + 1, roads, blocks)
            split(Rect(cut + w, area.y0, area.x1, area.y1), rx, ry, depth + 1, roads, blocks)
        } else {
            val cut = area.y0 + (area.depth * t).toInt() - w / 2
            roads += Road(Rect(area.x0 - 4, cut, area.x1 + 4, cut + w - 1), RoadKind.STREET, alongX = true)
            split(Rect(area.x0, area.y0, area.x1, cut - 1), rx, ry, depth + 1, roads, blocks)
            split(Rect(area.x0, cut + w, area.x1, area.y1), rx, ry, depth + 1, roads, blocks)
        }
    }

    private fun subdivide(block: Rect, rx: Int, ry: Int, index: Int, cx: Float, cy: Float, intensity: Float, out: MutableList<Lot>) {
        val alongX = block.width >= block.depth
        val short = if (alongX) block.depth else block.width
        // Two back-to-back rows when deep enough, so every lot still has a frontage.
        val rows = if (short > 100) 2 else 1
        for (row in 0 until rows) {
            val rowRect = when {
                rows == 1 -> block
                alongX -> if (row == 0) Rect(block.x0, block.y0, block.x1, block.y0 + short / 2 - 1) else Rect(block.x0, block.y0 + short / 2, block.x1, block.y1)
                else -> if (row == 0) Rect(block.x0, block.y0, block.x0 + short / 2 - 1, block.y1) else Rect(block.x0 + short / 2, block.y0, block.x1, block.y1)
            }
            val front = when {
                alongX -> if (row == 0) 0 else 2
                else -> if (row == 0) 3 else 1
            }
            var at = if (alongX) rowRect.x0 else rowRect.y0
            val end = if (alongX) rowRect.x1 else rowRect.y1
            var n = 0
            while (at <= end) {
                val lotSeed = Hash.mix(seed, rx, ry, index * 64 + row * 32 + n, 17)
                val dist = run {
                    val mx = if (alongX) at.toFloat() else (rowRect.x0 + rowRect.x1) / 2f
                    val my = if (alongX) (rowRect.y0 + rowRect.y1) / 2f else at.toFloat()
                    (abs(mx - cx) + abs(my - cy)) / regionSize
                }
                val downtown = (1f - dist * 1.6f) * intensity
                val style = styles.pick(downtown, lotSeed)
                val width = style.frontage(lotSeed).coerceAtMost(end - at + 1).let { if (end - at + 1 - it < 20) end - at + 1 else it }
                val rect = if (alongX) Rect(at, rowRect.y0, at + width - 1, rowRect.y1) else Rect(rowRect.x0, at, rowRect.x1, at + width - 1)
                val park = Hash.unit(seed, rx, ry, index * 64 + row * 32 + n, 23) < 0.08f + max(0f, dist - 0.55f) * 0.5f
                out += if (park) Lot(rect, LotUse.PARK, "park", 0, front, lotSeed)
                else Lot(rect, LotUse.BUILDING, style.id, style.floors(downtown, lotSeed).coerceIn(1, maxFloors), front, lotSeed)
                at += width
                n++
            }
        }
    }

    // ---- Footprint and terrain blend -------------------------------------------------

    private fun urbanWeight(x: Int, y: Int, into: FloatArray? = null): Float {
        val r = regionSize
        val rx0 = Math.floorDiv(x - BLEND, r); val rx1 = Math.floorDiv(x + BLEND, r)
        val ry0 = Math.floorDiv(y - BLEND, r); val ry1 = Math.floorDiv(y + BLEND, r)
        var best = 0f; var hSum = 0f; var wSum = 0f
        for (ry in ry0..ry1) for (rx in rx0..rx1) {
            if (!isUrban(rx, ry)) continue
            val d = Rect(rx * r, ry * r, rx * r + r - 1, ry * r + r - 1).distance(x, y)
            val w = TerrainStage.smooth(0f, 1f, 1f - d / BLEND)
            if (w <= 0f) continue
            best = max(best, w)
            hSum += plateau(rx, ry) * w; wSum += w
        }
        if (into != null) into[0] = if (wSum > 0f) hSum / wSum else 0f
        return best
    }

    /** The terrain with urban regions blended onto their plateaus. */
    fun surfaceAt(x: Int, y: Int): Float {
        val n = natural.heightAt(x, y)
        val tmp = FloatArray(1)
        val w = urbanWeight(x, y, tmp)
        return if (w <= 0f) n else n + (tmp[0] - n) * w
    }

    override fun urban(x: Int, y: Int): Float {
        val w = urbanWeight(x, y)
        if (w <= 0f) return 0f
        val region = region(Math.floorDiv(x, regionSize), Math.floorDiv(y, regionSize))
        return if (region.lots.any { it.use == LotUse.PARK && it.rect.contains(x, y) }) 0.2f else w
    }

    override fun isOccupied(x: Int, y: Int): Boolean {
        for (region in regionsTouching(Rect(x, y, x, y))) {
            if (region.roads.any { it.rect.contains(x, y) }) return true
            if (region.lots.any { it.use == LotUse.BUILDING && it.rect.contains(x, y) }) return true
        }
        return false
    }

    /** Occupancy of a whole chunk column at once: one rasterisation instead of 4096 lookups. */
    override fun occupancyMask(cx: Int, cy: Int): BooleanArray {
        val s = MicroChunk.SIZE
        val area = Rect(cx * s, cy * s, cx * s + s - 1, cy * s + s - 1)
        val out = BooleanArray(s * s)
        for (region in regionsTouching(area)) {
            val rects = region.roads.map { it.rect } + region.lots.filter { it.use == LotUse.BUILDING }.map { it.rect }
            for (r in rects) {
                if (!r.intersects(area)) continue
                for (y in max(r.y0, area.y0)..min(r.y1, area.y1)) for (x in max(r.x0, area.x0)..min(r.x1, area.x1)) out[(y - area.y0) * s + x - area.x0] = true
            }
        }
        return out
    }

    companion object {
        const val BLOCK = 4
        const val MAX_BLOCK = 170
        const val BLEND = 96
        private val CORNERS = listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f, 0.9f to 0.9f)
    }
}

/**
 * `micro:city_plan` -- publishes the city map and reshapes the terrain under it.
 * Writes no voxels itself; [RoadsStage] and [BuildingsStage] draw the plan.
 *
 * Options: `regionSize` (micro, default 512 = 128 blocks), `density` (0..1),
 * `styles` (comma-separated style ids to build with, default all),
 * `maxFloors` (cap on every building).
 */
object CityPlanStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Cities", "Grid cities with boulevards and lots, flattening the land under them.",
        listOf(
            StageParam.Number("density", "City density", "How much of the land is city.", 0f, 1f, 0.5f),
            StageParam.Number("regionSize", "District size", "How big each city district is, in quarter-blocks.", 256f, 1024f, 512f, 64f),
            StageParam.Number("maxFloors", "Tallest", "No building rises above this many floors.", 1f, 16f, 16f, 1f),
        ),
    )

    const val ID = "micro:city_plan"
    val KEY = FieldKey<CityPlanner>("city")

    override fun create(setup: StageSetup): MicroStage {
        val f = setup.fields
        val all = f.get(ArchitectureRegistry.KEY) ?: ArchitectureRegistry.standard(setup.palette).also { f.publish(ArchitectureRegistry.KEY, it) }
        val wanted = setup.options.string("styles", "").split(',').map(String::trim).filter(String::isNotEmpty)
        val styles = if (wanted.isEmpty()) all else ArchitectureRegistry().also { r ->
            wanted.forEach { id ->
                r.register(all[id] ?: throw IllegalArgumentException("Stage '$ID' option 'styles' names unknown style '$id'. Known: ${all.all.joinToString { it.id }}"))
            }
        }
        val planner = DefaultCityPlanner(
            seed = setup.seed,
            natural = f.require(Fields.NATURAL_HEIGHT),
            sea = f.require(Fields.SEA_LEVEL),
            regionSize = setup.options.int("regionSize", 512),
            density = setup.options.float("density", 0.5f),
            styles = styles,
            maxFloors = setup.options.int("maxFloors", Int.MAX_VALUE),
        )
        f.publish(KEY, planner)
        f.publish(Fields.FOOTPRINT, planner)
        // On the same lattice as the natural height, so the blend is worked out once a block, not once a microvoxel.
        f.publish(Fields.SURFACE, LatticeHeight(HeightFunction(planner::surfaceAt), setup.options.int("sampleStep", 4)))
        return MicroStage { }
    }
}

/**
 * `micro:roads` -- draws the plan's roads: asphalt, raised kerbs and
 * pavements, lane markings, and street lamps. Where two roads overlap it is a
 * junction: asphalt wins over pavement and markings are left out.
 */
object RoadsStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Streets", "The cities' asphalt, kerbs, markings and lamps.",
        listOf(StageParam.Number("lampSpacing", "Lamp spacing", "Quarter-blocks between street lamps.", 16f, 96f, 40f, 4f)),
    )

    const val ID = "micro:roads"

    override fun create(setup: StageSetup): MicroStage {
        val p = setup.palette
        val asphalt = p.id(M.ASPHALT); val white = p.id(M.LANE_WHITE); val yellow = p.id(M.LANE_YELLOW)
        val sidewalk = p.id(M.SIDEWALK); val curb = p.id(M.CURB); val metal = p.id(M.METAL); val lamp = p.id(M.LAMP)
        val gravel = p.id(M.GRAVEL)
        val lampSpacing = setup.options.int("lampSpacing", 40)
        return MicroStage { ctx ->
            val city = ctx.fields.get(CityPlanStage.KEY) ?: return@MicroStage
            val area = Rect(ctx.x0, ctx.y0, ctx.x1, ctx.y1)
            val roads = city.regionsTouching(area).flatMap { it.roads }.filter { it.rect.intersects(area) }
            if (roads.isEmpty()) return@MicroStage
            val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
            val lo = cols.heights.min() - 3; val hi = cols.heights.max() + 12
            if (hi < ctx.z0 || lo > ctx.z1) return@MicroStage
            for (y in area.y0..area.y1) for (x in area.x0..area.x1) {
                var onAsphalt: Road? = null; var asphaltCount = 0; var walk: Road? = null; var curbHere = false
                for (road in roads) {
                    if (!road.rect.contains(x, y)) continue
                    val across = if (road.alongX) y - road.rect.y0 else x - road.rect.x0
                    val width = if (road.alongX) road.rect.depth else road.rect.width
                    val sw = road.kind.sidewalk
                    if (across < sw || across >= width - sw) {
                        if (walk == null) walk = road
                        if (across == sw - 1 || across == width - sw) curbHere = true
                    } else { onAsphalt = road; asphaltCount++ }
                }
                if (onAsphalt == null && walk == null) continue
                val h = cols.height(x - ctx.x0, y - ctx.y0)
                // Clear anything above the pavement, then lay a two-voxel sub-base.
                for (z in h + 1..h + 10) if (z in ctx.z0..ctx.z1) ctx.set(x, y, z, MaterialPalette.AIR)
                if (h - 2 in ctx.z0..ctx.z1) ctx.set(x, y, h - 2, gravel)
                if (h - 1 in ctx.z0..ctx.z1) ctx.set(x, y, h - 1, gravel)
                val road = onAsphalt
                if (road != null) {
                    val m = if (asphaltCount == 1) marking(road, x, y, asphalt, white, yellow) else asphalt
                    if (h in ctx.z0..ctx.z1) ctx.set(x, y, h, m)
                } else {
                    // Pavement one voxel above the road: a quarter-block kerb, the kind of detail blocks cannot draw.
                    if (h in ctx.z0..ctx.z1) ctx.set(x, y, h, sidewalk)
                    if (h + 1 in ctx.z0..ctx.z1) ctx.set(x, y, h + 1, if (curbHere) curb else sidewalk)
                    val wr = walk!!
                    val along = if (wr.alongX) x else y
                    val across = if (wr.alongX) y - wr.rect.y0 else x - wr.rect.x0
                    val width = if (wr.alongX) wr.rect.depth else wr.rect.width
                    val lampLine = across == 2 || across == width - 3
                    if (lampLine && Math.floorMod(along, lampSpacing) == 0) {
                        for (z in h + 2..h + 17) if (z in ctx.z0..ctx.z1) ctx.set(x, y, z, metal)
                        // A short arm reaching over the road, with the lamp hanging from it.
                        val dir = if (across == 2) 1 else -1
                        for (k in 1..3) {
                            val ax = if (wr.alongX) x else x + dir * k
                            val ay = if (wr.alongX) y + dir * k else y
                            if (ctx.overlaps(ax, ay, h + 17, ax, ay, h + 17)) ctx.set(ax, ay, h + 17, metal)
                            if (k == 3 && ctx.overlaps(ax, ay, h + 16, ax, ay, h + 16)) ctx.set(ax, ay, h + 16, lamp)
                        }
                    }
                }
            }
        }
    }

    private fun marking(road: Road, x: Int, y: Int, asphalt: Short, white: Short, yellow: Short): Short {
        val across = if (road.alongX) y - road.rect.y0 else x - road.rect.x0
        val along = if (road.alongX) x else y
        val width = if (road.alongX) road.rect.depth else road.rect.width
        val centre = width / 2
        return when (road.kind) {
            RoadKind.ARTERIAL -> when {
                across == centre - 1 || across == centre + 1 -> yellow // double yellow
                (across == centre - 9 || across == centre + 9) && Math.floorMod(along, 16) < 8 -> white
                else -> asphalt
            }
            RoadKind.STREET -> if (across == centre && Math.floorMod(along, 14) < 7) white else asphalt
        }
    }
}
