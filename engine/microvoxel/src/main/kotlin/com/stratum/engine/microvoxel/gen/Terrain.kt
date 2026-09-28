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
class Columns(val heights: IntArray, val tops: ShortArray, val slopes: FloatArray) {
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
 * `height` (vertical stretch), `mountains` (0..2), `snowLine` (micro above sea).
 */
object TerrainStage : MicroStageFactory {
    const val ID = "micro:terrain"

    override fun create(setup: StageSetup): MicroStage {
        val o = setup.options
        val sea = o.int("seaLevel", 96)
        val scale = 1f / o.float("scale", 1f)
        val vertical = o.float("height", 1f)
        val mountains = o.float("mountains", 1f)
        val snowLine = sea + o.int("snowLine", 230)
        val seed = setup.seed
        val noise = Noise(seed)
        val climateNoise = Noise(seed xor 0x5A17)
        val natural = HeightFunction { ix, iy ->
            val x = ix * scale; val y = iy * scale
            val (wx, wy) = noise.warp(x, y, strength = 90f, scale = 0.0021f)
            val continent = noise.fbm(wx * 0.0009f + 31f, wy * 0.0009f - 17f, 4)
            val hills = noise.erodedFbm(wx * 0.0042f, wy * 0.0042f, 6, erosion = 0.9f)
            val mask = smooth(0.05f, 0.4f, noise.fbm(x * 0.0007f + 400f, y * 0.0007f - 250f, 2))
            val ridge = if (mask > 0f) noise.ridged(wx * 0.0032f, wy * 0.0032f, 5) else 0f
            sea + vertical * (continent * 150f + 18f + hills * 80f + mask * mountains * ridge * 300f)
        }
        val climate = object : Climate {
            override fun temperature(x: Int, y: Int) =
                (0.5f + climateNoise.fbm(x * 0.0005f, y * 0.0005f, 3) * 0.9f).coerceIn(0f, 1f)
            override fun moisture(x: Int, y: Int) =
                (0.5f + climateNoise.fbm(x * 0.0009f - 77f, y * 0.0009f + 13f, 3) * 0.9f).coerceIn(0f, 1f)
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
        for (y in 0 until s) for (x in 0 until s) {
            val h = raw[(y + 1) * w + x + 1]
            val gx = (raw[(y + 1) * w + x + 2] - raw[(y + 1) * w + x]) * 0.5f
            val gy = (raw[(y + 2) * w + x + 1] - raw[y * w + x + 1]) * 0.5f
            val slope = sqrt(gx * gx + gy * gy)
            val wx = ox + x; val wy = oy + y
            val hi = floor(h).toInt()
            val jitter = Hash.unit(fields.seed, wx, wy, 0, 9) * 18f
            tops[y * s + x] = when {
                slope > 1.35f -> if (hi > snowLine) mat.darkStone else mat.stone
                hi > snowLine + jitter - 20f && slope < 0.9f -> mat.snow
                hi <= sea + 3 -> if (hi < sea - 6) mat.gravel else mat.sand
                slope > 0.9f -> mat.gravel
                climate.moisture(wx, wy) < 0.32f || climate.temperature(wx, wy) > 0.78f -> mat.dryGrass
                else -> mat.grass
            }
            heights[y * s + x] = hi
            slopes[y * s + x] = slope
        }
        return Columns(heights, tops, slopes)
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
        if (rockTop >= ctx.z0) ctx.fill(ctx.x0, ctx.y0, ctx.z0, ctx.x1, ctx.y1, min(rockTop, ctx.z1), mat.stone)
        val start = max(ctx.z0, rockTop + 1)
        val end = min(ctx.z1, top)
        if (start > end) return
        for (ly in 0 until s) for (lx in 0 until s) {
            val h = cols.height(lx, ly)
            val surfaceMat = cols.top(lx, ly)
            val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
            val soil = if (surfaceMat == mat.stone || surfaceMat == mat.darkStone) 0 else 3 + Hash.int(ctx.fields.seed, wx, wy, 11, 5)
            val subsoil = if (surfaceMat == mat.sand) mat.sand else mat.dirt
            for (z in start..end) {
                val m = when {
                    z > h -> if (z <= sea) mat.water else break
                    z == h -> if (h < sea && surfaceMat != mat.sand && surfaceMat != mat.gravel) mat.dirt else surfaceMat
                    z > h - soil -> subsoil
                    z < h - 40 && Hash.unit(ctx.fields.seed, wx, wy, z, 3) < 0.5f -> mat.darkStone
                    else -> mat.stone
                }
                ctx.set(wx, wy, z, m)
            }
        }
    }

    /** Deepest soil layer; anything below it is rock and can be bulk-filled. */
    private const val SOIL_MAX = 8

    internal fun smooth(a: Float, b: Float, x: Float): Float {
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
object CavesStage : MicroStageFactory {
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
