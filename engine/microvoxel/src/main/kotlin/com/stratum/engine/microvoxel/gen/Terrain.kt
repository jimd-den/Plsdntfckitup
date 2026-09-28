package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A height in microvoxels for any world column. Must be pure and cheap. */
fun interface HeightFunction {
    fun heightAt(x: Int, y: Int): Float
}

/**
 * A height field sampled on a world-aligned lattice every [step] columns and
 * interpolated between.
 *
 * Terrain noise has nothing finer than a few blocks in it, so evaluating the
 * whole noise stack for every quarter-block column is wasted work: sampling
 * once a block and interpolating is ~16x cheaper and looks the same -- and
 * because the lattice is aligned to world coordinates, every chunk and every
 * caller interpolates between identical samples, so there are no seams.
 * Samples are memoised per thread in a small direct-mapped table, which a
 * chunk's sweep over its columns hits almost every time.
 */
class LatticeHeight(private val source: HeightFunction, private val step: Int = 4) : HeightFunction {
    init { require(step >= 1) { "step must be positive" } }

    private class Memo { val keys = LongArray(SIZE) { Long.MIN_VALUE }; val values = FloatArray(SIZE) }

    private val memo = ThreadLocal.withInitial { Memo() }

    private fun node(ix: Int, iy: Int, m: Memo): Float {
        val key = (ix.toLong() shl 32) or (iy.toLong() and 0xFFFFFFFFL)
        val slot = ((ix * 73856093) xor (iy * 19349663)) and (SIZE - 1)
        if (m.keys[slot] == key) return m.values[slot]
        val v = source.heightAt(ix * step, iy * step)
        m.keys[slot] = key; m.values[slot] = v
        return v
    }

    override fun heightAt(x: Int, y: Int): Float {
        if (step == 1) return source.heightAt(x, y)
        val ix = Math.floorDiv(x, step); val iy = Math.floorDiv(y, step)
        val fx = (x - ix * step).toFloat() / step; val fy = (y - iy * step).toFloat() / step
        val m = memo.get()
        val a = node(ix, iy, m); val b = node(ix + 1, iy, m)
        val c = node(ix, iy + 1, m); val d = node(ix + 1, iy + 1, m)
        val top = a + (b - a) * fx
        return top + (c + (d - c) * fx - top) * fy
    }

    private companion object { const val SIZE = 4096 }
}

/** Heat and wet in 0..1, for choosing surface materials and vegetation. */
interface Climate {
    fun temperature(x: Int, y: Int): Float
    fun moisture(x: Int, y: Int): Float
}

/** What a pipeline's services are called. Third-party stages add their own keys. */
object Fields {
    /** The terrain before anything reshapes it. */
    val NATURAL_HEIGHT = FieldKey<HeightFunction>("natural_height")

    /** The ground as it finally is: stages that flatten or raise land replace this. */
    val SURFACE = FieldKey<HeightFunction>("surface")
    val CLIMATE = FieldKey<Climate>("climate")
    val SEA_LEVEL = FieldKey<Int>("sea_level")

    /** Per-chunk-column surface heights and top materials, computed once and shared by every stage. */
    val COLUMNS = FieldKey<ColumnSource>("columns")

    /** Where the ground is built on or paved, so vegetation stays off it. */
    val FOOTPRINT = FieldKey<Footprint>("footprint")

    /**
     * A host's own soils by column. Published before the stages are set up
     * (see [StageRegistry.build]'s `prepare`), it replaces the built-in
     * grass, dirt and rock: a pack's red earth, its turf, its granite.
     */
    val STRATA = FieldKey<Strata>("strata")

    /** Which geological province a column is in, when the land was built from them (`geology` on [TerrainStage]). */
    val GEOLOGY = FieldKey<com.stratum.engine.microvoxel.geo.GeoField>("geology")
}

/** The layers of ground at a column, as material ids. */
class Layers(val surface: Short, val subsurface: Short, val filler: Short)

fun interface Strata {
    /** The layers at a column, or null where the built-in ones should stand. */
    fun at(x: Int, y: Int): Layers?
}

/** The land use at a column. Default is wild land. */
interface Footprint {
    /** 0 = wild, 1 = fully urban. Vegetation density fades with it. */
    fun urban(x: Int, y: Int): Float

    /** True where a road, pavement or building owns the ground. */
    fun isOccupied(x: Int, y: Int): Boolean

    /**
     * [isOccupied] for a whole chunk column at once, row-major 64 x 64, or
     * null to make callers ask column by column.
     */
    fun occupancyMask(cx: Int, cy: Int): BooleanArray? = null
}

/** One chunk column's worth of cached surface data. */
class Columns(
    val heights: IntArray,
    val tops: ShortArray,
    val slopes: FloatArray,
    /** What lies under the top: soil, then rock. */
    val subs: ShortArray = ShortArray(0),
    val fills: ShortArray = ShortArray(0),
    /** Province, floor, folds and water per column, when the land was built from geology. */
    val geo: GeoColumns? = null,
) {
    fun height(lx: Int, ly: Int) = heights[ly * MicroChunk.SIZE + lx]
    fun top(lx: Int, ly: Int) = tops[ly * MicroChunk.SIZE + lx]
    fun slope(lx: Int, ly: Int) = slopes[ly * MicroChunk.SIZE + lx]
}

fun interface ColumnSource {
    fun columns(cx: Int, cy: Int): Columns
}

/**
 * `micro:terrain` -- the ground itself.
 *
 * Height is a sum of three shapes, each doing one job:
 *  - a slow, domain-warped *continent* field decides land and sea;
 *  - *eroded fbm* (octaves damped by accumulated slope) gives hills whose
 *    detail pools in valleys and on plateaus the way erosion leaves it;
 *  - *ridged* noise under a mountain mask raises spines and escarpments.
 *
 * Because heights are continuous and the voxels are a quarter block, slopes
 * come out as quarter-block steps -- a hillside instead of a staircase.
 *
 * Options: `seaLevel` (micro), `scale` (horizontal stretch, 1 = default),
 * `height` (vertical stretch), `mountains` (0..2), `snowLine` (micro above sea),
 * `maxHeight` / `minHeight` (micro; peaks ease under the ceiling),
 * `sampleStep` (noise sampled every this many columns and interpolated; 1 = every column),
 * `spawnRadius` / `spawnRise` (dry land lifted around the origin; 0 = off),
 * `terrace` (micro; land gathers into soft plateaus this tall, as laterite
 * escarpments do; 0 = off).
 *
 * When a host publishes [Fields.STRATA], the surface, soil and rock are the
 * host's own (a pack's biome blocks); beaches, snow, scree and water stay.
 */
object TerrainStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Land", "Hills, valleys, lakes and the ground's own soils.",
        listOf(
            StageParam.Choice(
                "geology", "Landscape",
                "Africa: every kind of country, placed by climate and tectonics. Or one province everywhere. Classic: generic hills.",
                listOf(GeoTerrain.AFRICA) + com.stratum.engine.microvoxel.geo.Provinces.all(0L).map { it.id } + GeoTerrain.CLASSIC,
                GeoTerrain.CLASSIC,
            ),
            StageParam.Number("height", "Hilliness", "How tall the land rises and how deep it falls.", 0.05f, 1f, 1f),
            StageParam.Number("mountains", "Mountains", "How much of the land is ridged peaks.", 0f, 2f, 1f),
            StageParam.Number("scale", "Breadth", "How wide hills and valleys are; bigger is broader.", 0.3f, 2f, 1f),
            StageParam.Number("terrace", "Plateaus", "Gathers the land into shelves this many quarter-blocks tall; 0 is off.", 0f, 16f, 0f, 1f),
            StageParam.Number("spawnRise", "Home rise", "How far above the water the land around home is lifted.", 0f, 40f, 10f, 1f),
            StageParam.Number("spawnRadius", "Home land", "How far the lifted land around home reaches, in quarter-blocks.", 0f, 800f, 360f, 8f),
        ),
    )

    const val ID = "micro:terrain"

    override fun create(setup: StageSetup): MicroStage {
        val o = setup.options
        val geology = o.string("geology", GeoTerrain.CLASSIC)
        if (geology != GeoTerrain.CLASSIC) return GeoTerrain.create(setup, geology)
        val sea = o.int("seaLevel", 96)
        val scale = 1f / o.float("scale", 1f)
        val vertical = o.float("height", 1f)
        val mountains = o.float("mountains", 1f)
        val snowLine = sea + o.int("snowLine", 230)
        // A world with a ceiling -- the block world is 48 blocks tall -- rounds
        // its peaks off softly below it instead of slicing them flat.
        val maxHeight = o.float("maxHeight", Float.MAX_VALUE)
        val minHeight = o.float("minHeight", 2f)
        // Dry land where the player arrives: the ground within `spawnRadius` of
        // the origin is lifted at least `spawnRise` above the sea, easing back to
        // the natural land at the edge. 0 turns it off.
        val spawnRadius = o.float("spawnRadius", 360f)
        val spawnRise = o.float("spawnRise", 10f)
        val terrace = o.float("terrace", 0f)
        val seed = setup.seed
        val noise = Noise(seed)
        val climateNoise = Noise(seed xor 0x5A17)
        val sampleStep = o.int("sampleStep", 4)
        val natural = LatticeHeight(step = sampleStep, source = HeightFunction { ix, iy ->
            val x = ix * scale; val y = iy * scale
            val (wx, wy) = noise.warp(x, y, strength = 90f, scale = 0.0021f)
            val continent = noise.fbm(wx * 0.0009f + 31f, wy * 0.0009f - 17f, 4)
            val hills = noise.erodedFbm(wx * 0.0042f, wy * 0.0042f, 6, erosion = 0.9f)
            val mask = smooth(0.05f, 0.4f, noise.fbm(x * 0.0007f + 400f, y * 0.0007f - 250f, 2))
            val ridge = if (mask > 0f) noise.ridged(wx * 0.0032f, wy * 0.0032f, 5) else 0f
            var h = sea + vertical * (continent * 150f + 18f + hills * 80f + mask * mountains * ridge * 300f)
            if (spawnRadius > 0f && h < sea + spawnRise) {
                val d = kotlin.math.sqrt(ix.toFloat() * ix + iy.toFloat() * iy) / spawnRadius
                if (d < 1f) h += (sea + spawnRise - h) * smooth(0f, 1f, 1f - d)
            }
            if (terrace > 0f) h = terraced(h, terrace)
            softCeiling(h, maxHeight).coerceAtLeast(minHeight)
        })
        // Climate changes over hundreds of blocks: a coarse lattice is exact enough and nearly free.
        val warmth = LatticeHeight(step = 16, source = HeightFunction { x, y ->
            (0.5f + climateNoise.fbm(x * 0.0005f, y * 0.0005f, 3) * 0.9f).coerceIn(0f, 1f)
        })
        val wetness = LatticeHeight(step = 16, source = HeightFunction { x, y ->
            (0.5f + climateNoise.fbm(x * 0.0009f - 77f, y * 0.0009f + 13f, 3) * 0.9f).coerceIn(0f, 1f)
        })
        val climate = object : Climate {
            override fun temperature(x: Int, y: Int) = warmth.heightAt(x, y)
            override fun moisture(x: Int, y: Int) = wetness.heightAt(x, y)
        }
        val fields = setup.fields
        fields.publish(Fields.NATURAL_HEIGHT, natural)
        fields.publish(Fields.SURFACE, natural)
        fields.publish(Fields.CLIMATE, climate)
        fields.publish(Fields.SEA_LEVEL, sea)

        val p = setup.palette
        val mat = SurfaceMaterials(p)
        val cache = ColumnCache<Columns>()
        // Built lazily so it reads the *final* SURFACE, after later stages have reshaped it.
        fields.publish(Fields.COLUMNS, ColumnSource { cx, cy ->
            cache.get(cx, cy) { buildColumns(cx, cy, fields, mat, sea, snowLine) }
        })

        return MicroStage { ctx -> fill(ctx, sea, mat) }
    }

    private fun buildColumns(cx: Int, cy: Int, fields: WorldFields, mat: SurfaceMaterials, sea: Int, snowLine: Int): Columns {
        val s = MicroChunk.SIZE
        val surface = fields.require(Fields.SURFACE)
        val climate = fields.require(Fields.CLIMATE)
        val ox = cx * s; val oy = cy * s
        // One ring of border so slope is exact at the chunk edge.
        val w = s + 2
        val raw = FloatArray(w * w)
        for (y in 0 until w) for (x in 0 until w) raw[y * w + x] = surface.heightAt(ox + x - 1, oy + y - 1)
        val heights = IntArray(s * s); val tops = ShortArray(s * s); val slopes = FloatArray(s * s)
        val subs = ShortArray(s * s); val fills = ShortArray(s * s)
        val strata = fields.get(Fields.STRATA)
        for (y in 0 until s) for (x in 0 until s) {
            val h = raw[(y + 1) * w + x + 1]
            val gx = (raw[(y + 1) * w + x + 2] - raw[(y + 1) * w + x]) * 0.5f
            val gy = (raw[(y + 2) * w + x + 1] - raw[y * w + x + 1]) * 0.5f
            val slope = sqrt(gx * gx + gy * gy)
            val wx = ox + x; val wy = oy + y
            val hi = floor(h).toInt()
            val jitter = Hash.unit(fields.seed, wx, wy, 0, 9) * 18f
            val own = strata?.at(wx, wy)
            val rock = own?.filler ?: mat.stone
            tops[y * s + x] = when {
                slope > 1.35f -> if (hi > snowLine) mat.darkStone else rock
                hi > snowLine + jitter - 20f && slope < 0.9f -> mat.snow
                hi <= sea + 3 -> if (hi < sea - 6) mat.gravel else mat.sand
                slope > 0.9f -> own?.subsurface ?: mat.gravel
                own != null -> own.surface
                climate.moisture(wx, wy) < 0.32f || climate.temperature(wx, wy) > 0.78f -> mat.dryGrass
                else -> mat.grass
            }
            subs[y * s + x] = own?.subsurface ?: mat.dirt
            fills[y * s + x] = rock
            heights[y * s + x] = hi
            slopes[y * s + x] = slope
        }
        return Columns(heights, tops, slopes, subs, fills)
    }

    private fun fill(ctx: MicroGenContext, sea: Int, mat: SurfaceMaterials) {
        val s = MicroChunk.SIZE
        val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
        var lowest = Int.MAX_VALUE; var highest = Int.MIN_VALUE
        for (h in cols.heights) { lowest = min(lowest, h); highest = max(highest, h) }
        val top = max(highest, sea)
        if (ctx.z0 > top) return // open sky: stays one uniform brick table.
        // Bulk rock under the shallowest soil: whole bricks, no per-voxel work.
        val rockTop = lowest - SOIL_MAX - 1
        val bedrock = if (cols.fills.isEmpty()) mat.stone else cols.fills[(s / 2) * s + s / 2]
        if (rockTop >= ctx.z0) ctx.fill(ctx.x0, ctx.y0, ctx.z0, ctx.x1, ctx.y1, min(rockTop, ctx.z1), bedrock)
        val end = min(ctx.z1, top)
        // Per 8 x 8 group of columns, rock under the group's lowest soil is also
        // whole bricks: in hills the chunk-wide floor is far below most columns.
        val groupFloor = IntArray((s / 8) * (s / 8))
        for (gy in 0 until s / 8) for (gx in 0 until s / 8) {
            var low = Int.MAX_VALUE
            for (y in gy * 8 until gy * 8 + 8) for (x in gx * 8 until gx * 8 + 8) low = min(low, cols.height(x, y))
            val floorZ = (Math.floorDiv(low - SOIL_MAX - 1 - ctx.z0 + 1, 8) * 8) + ctx.z0 - 1 // last z of the last whole brick below the soil
            groupFloor[gy * (s / 8) + gx] = floorZ
            if (floorZ > rockTop && floorZ >= ctx.z0)
                ctx.fill(ctx.x0 + gx * 8, ctx.y0 + gy * 8, max(ctx.z0, rockTop + 1), ctx.x0 + gx * 8 + 7, ctx.y0 + gy * 8 + 7, min(floorZ, ctx.z1), bedrock)
        }
        for (ly in 0 until s) for (lx in 0 until s) {
            val start = max(ctx.z0, max(rockTop, groupFloor[(ly / 8) * (s / 8) + lx / 8]) + 1)
            if (start > end) continue
            val h = cols.height(lx, ly)
            val surfaceMat = cols.top(lx, ly)
            val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
            val rock = if (cols.fills.isEmpty()) mat.stone else cols.fills[ly * s + lx]
            val soil = if (surfaceMat == mat.stone || surfaceMat == mat.darkStone || surfaceMat == rock) 0 else 3 + Hash.int(ctx.fields.seed, wx, wy, 11, 5)
            val subsoil = if (surfaceMat == mat.sand) mat.sand else if (cols.subs.isEmpty()) mat.dirt else cols.subs[ly * s + lx]
            for (z in start..end) {
                val m = when {
                    z > h -> if (z <= sea) mat.water else break
                    z == h -> if (h < sea && surfaceMat != mat.sand && surfaceMat != mat.gravel) subsoil else surfaceMat
                    z > h - soil -> subsoil
                    z < h - 40 && rock == mat.stone && Hash.unit(ctx.fields.seed, wx, wy, z, 3) < 0.5f -> mat.darkStone
                    else -> rock
                }
                ctx.set(wx, wy, z, m)
            }
        }
    }

    /** Deepest soil layer; anything below it is rock and can be bulk-filled. */
    private const val SOIL_MAX = 8

    /**
     * Soft terraces: most of each step is nearly flat and the rise between is
     * a short steep bank -- plateaus with clean ledges, still continuous, so
     * slopes stay quarter-block smooth rather than becoming cliffs.
     */
    internal fun terraced(h: Float, step: Float): Float {
        val base = kotlin.math.floor(h / step) * step
        val t = (h - base) / step
        val eased = smooth(0.62f, 1f, t)
        return base + eased * step
    }

    /** Leaves heights well under [ceiling] alone and eases the rest towards it, never past it. */
    internal fun softCeiling(h: Float, ceiling: Float): Float {
        if (ceiling == Float.MAX_VALUE) return h
        val knee = ceiling - 24f
        if (h <= knee) return h
        val over = h - knee
        return knee + 24f * (over / (over + 24f))
    }

    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

internal class SurfaceMaterials(p: MaterialPalette) {
    val grass = p.id(M.GRASS)
    val dryGrass = p.id(M.GRASS_DRY)
    val dirt = p.id(M.DIRT)
    val stone = p.id(M.STONE)
    val darkStone = p.id(M.DARK_STONE)
    val sand = p.id(M.SAND)
    val snow = p.id(M.SNOW)
    val water = p.id(M.WATER)
    val gravel = p.id(M.GRAVEL)
}

/**
 * `micro:caves` -- 3D noise tunnels under the surface. Off in the low-spec
 * presets: it is the one stage that samples noise per voxel.
 *
 * Options: `threshold` (0..1, lower = more cave), `minDepth` (micro below surface).
 */
object CavesStage : MicroStageFactory, Describable {
    override fun describe() = StageInfo(
        ID, "Caves", "Tunnels winding under the ground.",
        listOf(
            StageParam.Number("threshold", "Openness", "How much of the underground is tunnel.", 0.02f, 0.3f, 0.12f),
            StageParam.Number("minDepth", "Roof", "How far under the surface tunnels stay, in quarter-blocks.", 4f, 40f, 14f, 1f),
        ),
    )

    const val ID = "micro:caves"
    override fun create(setup: StageSetup): MicroStage {
        val threshold = setup.options.float("threshold", 0.12f)
        val minDepth = setup.options.int("minDepth", 14)
        val noise = Noise(setup.seed xor 0xCA7E)
        return MicroStage { ctx ->
            val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
            if (ctx.z0 > cols.heights.max()) return@MicroStage
            for (ly in 0 until MicroChunk.SIZE) for (lx in 0 until MicroChunk.SIZE) {
                val roof = cols.height(lx, ly) - minDepth
                val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
                for (z in ctx.z0..min(ctx.z1, roof)) {
                    // Two ridged fields crossing make long tubes ("spaghetti caves").
                    val a = noise.value3(wx * 0.02f, wy * 0.02f, z * 0.03f)
                    val b = noise.value3(wx * 0.02f + 50f, wy * 0.02f, z * 0.03f + 20f)
                    if (a * a + b * b < threshold * threshold) ctx.set(wx, wy, z, MaterialPalette.AIR)
                }
            }
        }
    }
}
