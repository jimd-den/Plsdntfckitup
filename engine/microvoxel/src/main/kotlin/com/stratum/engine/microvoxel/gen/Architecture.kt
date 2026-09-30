package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MaterialPalette.Companion.AIR
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A building resolved from a lot: where it stands and how tall it is. Pure data, derived identically by every chunk. */
class Building(
    val lot: Lot,
    val footprint: Rect,
    /** Ground-floor slab height: level with the highest ground under it, so nothing is buried. */
    val baseZ: Int,
    /** Lowest ground under it: the foundation reaches down to here. */
    val groundZ: Int,
    val floors: Int,
    val floorHeight: Int,
) {
    val seed: Long get() = lot.seed
    val front: Int get() = lot.front
    val wallTop: Int get() = baseZ + floors * floorHeight
}

/**
 * A way of building: a small shape grammar evaluated per voxel.
 *
 * A style is a *function* from a world voxel to a material rather than a
 * procedure that writes voxels. That is what makes buildings chunk-safe for
 * free: a chunk asks only about its own voxels, in any order, and a building
 * straddling four chunks comes out seamless. It also makes styles trivially
 * swappable and testable -- sample a column and look at it.
 *
 * Return [KEEP] to leave a voxel to the terrain, [AIR] to hollow it out.
 */
interface ArchitectureStyle {
    val id: String

    /** How strongly this style wants a lot at [downtown] (≤0 suburbs, ~1 centre). Zero never picks it. */
    fun weight(downtown: Float): Float

    /** Lot width along the street. */
    fun frontage(seed: Long): Int
    fun floors(downtown: Float, seed: Long): Int
    fun floorHeight(): Int = 12

    /** How far the walls stand in from the lot edge. */
    fun setback(lot: Lot): Int

    /** Height above [Building.wallTop] the roof and rooftop clutter may reach. */
    fun roofAllowance(b: Building): Int

    fun sample(b: Building, x: Int, y: Int, z: Int): Short

    companion object {
        const val KEEP: Short = Short.MIN_VALUE
    }
}

/** Styles by id, with a weighted pick. Publish a different one under [KEY] to change what cities look like. */
class ArchitectureRegistry {
    private val styles = LinkedHashMap<String, ArchitectureStyle>()
    val all: Collection<ArchitectureStyle> get() = styles.values

    fun register(style: ArchitectureStyle) = apply { styles[style.id] = style }
    operator fun get(id: String): ArchitectureStyle? = styles[id]

    fun pick(downtown: Float, seed: Long): ArchitectureStyle {
        require(styles.isNotEmpty()) { "No architecture styles registered" }
        val weights = styles.values.map { max(0f, it.weight(downtown)) }
        val total = weights.sum()
        // A restricted set may have no style that wants this spot; take the least unwilling.
        if (total <= 0f) return styles.values.maxByOrNull { it.weight(downtown) }!!
        var r = ((seed ushr 11) and 0xFFFFFF) / 16777216f * total
        styles.values.forEachIndexed { i, s -> r -= weights[i]; if (r <= 0f) return s }
        return styles.values.last()
    }

    companion object {
        val KEY = FieldKey<ArchitectureRegistry>("architecture")

        fun standard(palette: MaterialPalette = MaterialPalette.standard()) = ArchitectureRegistry()
            .register(TowerStyle(palette))
            .register(TerraceStyle(palette))
            .register(VillaStyle(palette))
    }
}

/**
 * Where a voxel sits relative to a footprint's walls: which side, how far in
 * (negative) or out (positive), and how far along the wall. The vocabulary
 * every facade rule is written in.
 */
internal class WallPoint {
    companion object {
        private val LOCAL = ThreadLocal.withInitial { WallPoint() }

        /**
         * This thread's scratch point. Styles are sampled for every voxel of
         * every building, and a fresh object per voxel is garbage a phone's
         * collector has to chase; no caller holds one across another call.
         */
        fun local(): WallPoint = LOCAL.get()
    }

    var side = 0          // 0 = -y, 1 = +x, 2 = +y, 3 = -x
    var depth = 0         // 0 = outer wall layer, 1 = inner layer, ... ; negative = outside by -depth
    var along = 0         // position along that wall, left to right seen from outside
    var length = 0        // wall length
    var inside = false

    /** Fills this from a column relative to [r]; returns false for the diagonal corner zones outside. */
    fun locate(r: Rect, x: Int, y: Int): Boolean {
        inside = r.contains(x, y)
        if (inside) {
            val d0 = y - r.y0; val d1 = r.x1 - x; val d2 = r.y1 - y; val d3 = x - r.x0
            val m = min(min(d0, d1), min(d2, d3))
            side = when (m) { d0 -> 0; d2 -> 2; d3 -> 3; else -> 1 }
            depth = m
        } else {
            val outX = x < r.x0 || x > r.x1; val outY = y < r.y0 || y > r.y1
            if (outX && outY) return false
            side = when { y < r.y0 -> 0; x > r.x1 -> 1; y > r.y1 -> 2; else -> 3 }
            depth = -when (side) { 0 -> r.y0 - y; 1 -> x - r.x1; 2 -> y - r.y1; else -> r.x0 - x }
        }
        when (side) {
            0 -> { along = x - r.x0; length = r.width }
            2 -> { along = r.x1 - x; length = r.width }
            1 -> { along = y - r.y0; length = r.depth }
            else -> { along = r.y1 - y; length = r.depth }
        }
        return true
    }
}

/**
 * Regular bays along a wall: where the windows go. Windows are centred so
 * both ends of a facade get the same margin -- which is most of the
 * difference between a facade and a spreadsheet of holes.
 */
internal class Bays(length: Int, pitch: Int, val width: Int, margin: Int) {
    val count = max(1, (length - 2 * margin + (pitch - width)) / pitch)
    private val pitch = pitch
    private val start = (length - (count * pitch - (pitch - width))) / 2

    /** Index of the bay whose window covers [u], -1 if none; [pad] widens the window (frames, sills). */
    fun bayAt(u: Int, pad: Int = 0): Int {
        val rel = u - start + pad
        if (rel < 0) return -1
        val bay = rel / pitch
        if (bay >= count) return -1
        return if (rel % pitch < width + 2 * pad) bay else -1
    }
}

/** Materials the built-in styles share. */
internal class StyleMaterials(p: MaterialPalette) {
    val brick = p.id(M.BRICK); val brickDark = p.id(M.BRICK_DARK)
    val plaster = p.id(M.PLASTER); val plasterBlue = p.id(M.PLASTER_BLUE)
    val concrete = p.id(M.CONCRETE); val glass = p.id(M.GLASS); val lit = p.id(M.GLASS_LIT)
    val tile = p.id(M.ROOF_TILE); val slate = p.id(M.ROOF_SLATE); val timber = p.id(M.TIMBER)
    val metal = p.id(M.METAL); val red = p.id(M.FLOWER_RED); val white = p.id(M.LANE_WHITE)
    val stone = p.id(M.STONE); val dark = p.id(M.DARK_STONE)
}

/**
 * Brick terraced houses with tiled gable roofs: sash windows with white
 * frames and projecting sills, a string course per floor, quoins at the
 * corners, chimneys, and -- on busy streets -- a shopfront with an awning.
 */
class TerraceStyle(palette: MaterialPalette) : ArchitectureStyle {
    private val m = StyleMaterials(palette)
    override val id = "terrace"
    override fun weight(downtown: Float) = 1.2f - abs(downtown - 0.45f) * 1.6f
    override fun frontage(seed: Long) = 30 + ((seed ushr 20) and 15).toInt()
    override fun floors(downtown: Float, seed: Long) = 2 + ((seed ushr 24) and 1).toInt() + if (downtown > 0.5f) 1 else 0
    override fun setback(lot: Lot) = 2
    override fun roofAllowance(b: Building) = min(b.footprint.width, b.footprint.depth) / 2 + 12

    override fun sample(b: Building, x: Int, y: Int, z: Int): Short {
        val fp = b.footprint
        val w = WallPoint.local()
        if (!w.locate(fp, x, y)) return ArchitectureStyle.KEEP
        val dz = z - b.baseZ
        val F = b.floorHeight
        val wallTop = b.floors * F
        val ridgeAlongX = b.front == 0 || b.front == 2
        val shop = (b.seed and 1L) == 0L && b.lot.floors >= 3
        val wallMat = if (((b.seed ushr 3) and 3L) == 0L) m.plaster else m.brick

        if (dz < 0) return if (w.inside && z >= b.groundZ - 1) m.brickDark else ArchitectureStyle.KEEP
        if (dz >= wallTop - 1) return roof(b, x, y, z, ridgeAlongX, wallMat)
        val floor = dz / F; val fz = dz % F
        val bays = Bays(w.length, 10, 4, 4)

        if (!w.inside) {
            val out = -w.depth
            // Shopfront awning: a striped canopy sloping out over the pavement.
            if (shop && floor == 0 && w.side == b.front && out <= 3 && fz == F - 2 - (out - 1) / 2 && w.along in 2 until w.length - 2)
                return if ((w.along / 3) % 2 == 0) m.red else m.white
            if (out != 1) return ArchitectureStyle.KEEP
            if (fz == 0 && floor > 0) return m.plaster // string course between floors
            if (fz == 2 && bays.bayAt(w.along, 1) >= 0 && !(shop && floor == 0 && w.side == b.front)) return m.plaster // sill
            return ArchitectureStyle.KEEP
        }
        if (w.depth >= 2) return if (fz == 0) m.timber else AIR // floors and rooms
        // Quoins: alternating long-and-short stones up each corner.
        val corner = w.along <= 1 || w.along >= w.length - 2
        if (corner && w.depth == 0) return if ((dz / 3) % 2 == 0) m.plaster else wallMat
        if (floor == 0 && w.side == b.front) {
            val mid = w.length / 2
            if (abs(w.along - mid) <= 2 && fz in 1..8) return if (w.depth == 1) m.timber else AIR // door
            if (shop && w.along in 3 until w.length - 3 && fz in 2..8) return if (w.depth == 1) m.glass else if (fz == 2 || fz == 8) m.timber else AIR
        }
        val bay = bays.bayAt(w.along)
        if (bay >= 0 && fz in 3..8) return when {
            w.depth == 1 -> glass(b, w, bay, floor)
            fz == 5 -> m.white // glazing bar
            else -> AIR
        }
        if (bays.bayAt(w.along, 1) >= 0 && fz in 2..9 && w.depth == 0) return m.white // frame
        return if (w.depth == 0 && wallMat == m.brick && Hash.unit(b.seed, x, y, z, 5) < 0.12f) m.brickDark else wallMat
    }

    private fun roof(b: Building, x: Int, y: Int, z: Int, alongX: Boolean, wallMat: Short): Short {
        val fp = b.footprint
        val eave = 2
        val across = if (alongX) y - fp.y0 else x - fp.x0
        val span = if (alongX) fp.depth else fp.width
        val along = if (alongX) x - fp.x0 else y - fp.y0
        val len = if (alongX) fp.width else fp.depth
        if (across < -eave || across >= span + eave || along < -1 || along > len) return ArchitectureStyle.KEEP
        val t = min(across, span - 1 - across) + 1
        val roofZ = b.wallTop - 1 + floor(t * 0.8f).toInt()
        val dz = z - roofZ
        // Chimney on the ridge, one per house, rising through the tiles.
        val chimneyAt = len / 4
        if (along in chimneyAt..chimneyAt + 2 && abs(across - span / 2) <= 1 && z <= b.wallTop + span / 2 + 6)
            return if (z >= b.wallTop + span / 2 + 5) m.dark else m.brickDark
        if (dz in -1..0) return m.tile
        if (dz < -1 && across in 0 until span && along in 0 until len) {
            val gableEnd = along == 0 || along == len - 1
            return if (gableEnd) wallMat else AIR
        }
        return ArchitectureStyle.KEEP
    }

    private fun glass(b: Building, w: WallPoint, bay: Int, floor: Int): Short =
        if (Hash.unit(b.seed, w.side, bay, floor, 41) < 0.3f) m.lit else m.glass
}

/**
 * Detached plastered villas with slate hip roofs, timber corner posts and
 * shutters, set back in their gardens.
 */
class VillaStyle(palette: MaterialPalette) : ArchitectureStyle {
    private val m = StyleMaterials(palette)
    override val id = "villa"
    override fun weight(downtown: Float) = 1f - downtown * 1.4f
    override fun frontage(seed: Long) = 44 + ((seed ushr 20) and 15).toInt()
    override fun floors(downtown: Float, seed: Long) = 1 + ((seed ushr 24) and 1).toInt()
    override fun setback(lot: Lot) = 7
    override fun roofAllowance(b: Building) = min(b.footprint.width, b.footprint.depth) / 2 + 4

    override fun sample(b: Building, x: Int, y: Int, z: Int): Short {
        val fp = b.footprint
        val w = WallPoint.local()
        val dz = z - b.baseZ
        val F = b.floorHeight
        val wallTop = b.floors * F
        if (dz >= wallTop - 1) return hipRoof(b, x, y, z)
        if (!w.locate(fp, x, y)) return ArchitectureStyle.KEEP
        if (dz < 0) return if (w.inside && z >= b.groundZ - 1) m.stone else ArchitectureStyle.KEEP
        val wall = if ((b.seed and 2L) == 0L) m.plaster else m.plasterBlue
        val floor = dz / F; val fz = dz % F
        val bays = Bays(w.length, 13, 5, 5)
        if (!w.inside) {
            if (-w.depth != 1) return ArchitectureStyle.KEEP
            val bay = bays.bayAt(w.along, 2)
            // Shutters either side of each window.
            if (bay >= 0 && bays.bayAt(w.along) < 0 && fz in 3..9) return m.timber
            return ArchitectureStyle.KEEP
        }
        if (w.depth >= 2) return if (fz == 0) m.timber else AIR
        if (w.along <= 1 || w.along >= w.length - 2) return m.timber // corner posts
        if (fz == 0 && w.depth == 0) return m.timber // floor beam
        if (floor == 0 && w.side == b.front && abs(w.along - w.length / 2) <= 2 && fz in 1..9) return if (w.depth == 1) m.timber else AIR
        val bay = bays.bayAt(w.along)
        if (bay >= 0 && fz in 3..9) return if (w.depth == 1) (if (Hash.unit(b.seed, w.side, bay, floor, 43) < 0.25f) m.lit else m.glass) else AIR
        return wall
    }

    private fun hipRoof(b: Building, x: Int, y: Int, z: Int): Short {
        val fp = b.footprint
        val eave = 3
        val r = fp.grow(eave)
        if (!r.contains(x, y)) return ArchitectureStyle.KEEP
        val t = min(min(x - r.x0, r.x1 - x), min(y - r.y0, r.y1 - y))
        val roofZ = b.wallTop - 2 + floor(t * 0.7f).toInt()
        val dz = z - roofZ
        if (dz in -1..0) return m.slate
        if (dz < -1 && fp.contains(x, y) && z >= b.wallTop - 1) return AIR
        return ArchitectureStyle.KEEP
    }
}

/**
 * Glass-and-concrete towers downtown: curtain walls with mullions and
 * spandrels, stepped setbacks, a parapet, rooftop plant and, on the
 * tallest, a mast.
 */
class TowerStyle(palette: MaterialPalette) : ArchitectureStyle {
    private val m = StyleMaterials(palette)
    override val id = "tower"
    override fun weight(downtown: Float) = (downtown - 0.45f) * 3f
    override fun frontage(seed: Long) = 56 + ((seed ushr 20) and 15).toInt()
    override fun floors(downtown: Float, seed: Long) = 5 + (downtown * 10).toInt() + ((seed ushr 24) and 7).toInt()
    override fun setback(lot: Lot) = 4
    override fun roofAllowance(b: Building) = if (b.floors > 12) 40 else 8

    /** Setback tiers; a narrow plot cannot step back, so it rises straight. */
    private fun tiers(b: Building) = when {
        min(b.footprint.width, b.footprint.depth) < 40 -> 1
        b.floors >= 14 && min(b.footprint.width, b.footprint.depth) >= 52 -> 3
        b.floors >= 9 -> 2
        else -> 1
    }

    private fun tierOf(b: Building, floor: Int): Int {
        val n = tiers(b)
        for (t in n - 1 downTo 1) if (floor >= b.floors * (t + 2) / (n + 2)) return t
        return 0
    }

    private fun tierStartFloor(b: Building, t: Int) = if (t == 0) 0 else b.floors * (t + 2) / (tiers(b) + 2)

    override fun sample(b: Building, x: Int, y: Int, z: Int): Short {
        val dz = z - b.baseZ
        val F = b.floorHeight
        if (dz < 0) return if (b.footprint.contains(x, y) && z >= b.groundZ - 1) m.concrete else ArchitectureStyle.KEEP
        val floor = dz / F; val fz = dz % F
        if (floor >= b.floors) return rooftop(b, x, y, dz - b.floors * F, tierOf(b, b.floors - 1))
        val tier = tierOf(b, floor)
        val rect = b.footprint.inset(tier * 6)
        if (!rect.contains(x, y)) {
            // A terrace on the lower tier's roof, with its own parapet.
            if (tier > 0) {
                val lower = b.footprint.inset((tier - 1) * 6)
                if (lower.contains(x, y) && floor == tierStartFloor(b, tier)) return terrace(lower, x, y, fz)
            }
            return ArchitectureStyle.KEEP
        }
        val w = WallPoint.local(); w.locate(rect, x, y)
        if (w.depth >= 2) return if (fz == 0) m.concrete else AIR
        if (w.along <= 1 || w.along >= w.length - 2) return m.concrete // corner columns
        if (floor == 0) {
            // Double-height lobby glazing, set back one voxel behind the columns.
            if (w.along % 8 == 0) return m.concrete
            return if (w.depth == 1) m.glass else AIR
        }
        if (fz < 3) return m.concrete // spandrel band
        if (w.along % 6 == 0) return if (w.depth == 0) m.metal else m.concrete // mullions
        if (w.depth == 1) return AIR
        return if (Hash.unit(b.seed, w.side * 1000 + w.along / 6, floor, 0, 47) < 0.22f) m.lit else m.glass
    }

    private fun terrace(lower: Rect, x: Int, y: Int, fz: Int): Short {
        val w = WallPoint.local(); w.locate(lower, x, y)
        return when {
            fz == 0 -> m.concrete
            fz <= 2 && w.depth == 0 -> m.concrete
            else -> AIR
        }
    }

    private fun rooftop(b: Building, x: Int, y: Int, h: Int, tier: Int): Short {
        val rect = b.footprint.inset(tier * 6)
        if (!rect.contains(x, y)) return ArchitectureStyle.KEEP
        val w = WallPoint.local(); w.locate(rect, x, y)
        if (h == 0) return m.concrete
        if (h <= 3 && w.depth == 0) return m.concrete // parapet
        // Plant boxes and a lift overrun, placed from the building's seed.
        val cx = (rect.x0 + rect.x1) / 2; val cy = (rect.y0 + rect.y1) / 2
        val ox = ((b.seed ushr 30) and 7).toInt() - 4; val oy = ((b.seed ushr 34) and 7).toInt() - 4
        if (abs(x - (cx + ox)) <= 5 && abs(y - (cy + oy)) <= 4 && h <= 7) return if (h == 7) m.metal else m.concrete
        if (abs(x - (cx - 9)) <= 2 && abs(y - (cy + 6)) <= 2 && h <= 4 && rect.width > 30) return m.metal
        if (b.floors > 12 && x == cx && y == cy && h <= 36) return if (h >= 34) m.lit else m.metal
        return ArchitectureStyle.KEEP
    }
}

/**
 * `micro:buildings` -- rasterises every building whose bounds touch the chunk.
 * Each is resolved from its lot by the style the planner picked, sampled over
 * the intersection of its bounding box and the chunk, and nothing else.
 */
object BuildingsStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(ID, "City buildings", "Terraces, villas and towers on the cities' lots.")

    const val ID = "micro:buildings"

    override fun create(setup: StageSetup): MicroStage {
        val styles = setup.fields.get(ArchitectureRegistry.KEY)
            ?: ArchitectureRegistry.standard(setup.palette).also { setup.fields.publish(ArchitectureRegistry.KEY, it) }
        return MicroStage { ctx ->
            val city = ctx.fields.get(CityPlanStage.KEY) ?: return@MicroStage
            val surface = ctx.fields.require(Fields.SURFACE)
            val area = Rect(ctx.x0, ctx.y0, ctx.x1, ctx.y1)
            for (region in city.regionsTouching(area)) for (lot in region.lots) {
                if (lot.use != LotUse.BUILDING || !lot.rect.grow(4).intersects(area)) continue
                val style = styles[lot.style] ?: continue
                val b = resolve(lot, style, surface)
                val box = b.footprint.grow(4)
                val zTop = b.wallTop + style.roofAllowance(b) + 2
                if (!ctx.overlaps(box.x0, box.y0, b.groundZ - 2, box.x1, box.y1, zTop)) continue
                for (z in max(ctx.z0, b.groundZ - 2)..min(ctx.z1, zTop))
                    for (y in max(ctx.y0, box.y0)..min(ctx.y1, box.y1))
                        for (x in max(ctx.x0, box.x0)..min(ctx.x1, box.x1)) {
                            val v = style.sample(b, x, y, z)
                            if (v != ArchitectureStyle.KEEP) ctx.set(x, y, z, v)
                        }
            }
        }
    }

    /** The building a lot gets. Public so maps, tests and gameplay can ask what stands where without voxels. */
    fun resolve(lot: Lot, style: ArchitectureStyle, surface: HeightFunction): Building {
        val fp = lot.rect.inset(style.setback(lot))
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for ((x, y) in listOf(fp.x0 to fp.y0, fp.x1 to fp.y0, fp.x0 to fp.y1, fp.x1 to fp.y1, (fp.x0 + fp.x1) / 2 to (fp.y0 + fp.y1) / 2)) {
            val h = surface.heightAt(x, y); lo = min(lo, h); hi = max(hi, h)
        }
        return Building(lot, fp, baseZ = ceil(hi).toInt() + 2, groundZ = floor(lo).toInt(), floors = lot.floors, floorHeight = style.floorHeight())
    }
}
