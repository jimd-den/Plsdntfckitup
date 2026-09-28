package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.geo.GeoAtlas
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.geo.ResolvedProvince
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `micro:terrain` with `geology` set: the land built from Africa's
 * geological provinces (see [Provinces] and [GeoAtlas]) instead of generic
 * noise hills.
 *
 * Per column it knows the province, how high the landform stands above the
 * province's floor, and how the bedding is folded there, and lays the ground
 * down as a geologist would read it in a cliff:
 *
 * - the surface: soil, sand, crust or salt as the province has it, the
 *   packs' own soil where the province is living soil, scree on steep
 *   slopes, bare rock on cliffs;
 * - under it the weathering profile (a laterite's crust, gravel, mottled
 *   clay and saprolite);
 * - under that the bedding, in bands fixed to the province's floor so both
 *   walls of a gorge show the same stripes, bent by folds where the province
 *   is folded, and capped by the province's hard rock above a height (the
 *   Karoo's dolerite, the Drakensberg's basalt);
 * - water in the sea, and in the landform's own hollows where the province
 *   holds lakes (a rift's red soda lakes, a floodplain's pools).
 */
internal object GeoTerrain {

    fun create(setup: StageSetup, geology: String): MicroStage {
        val o = setup.options
        val sea = o.int("seaLevel", 96)
        val palette = setup.palette
        val resolved = GeoAtlas.resolve(Provinces.all(setup.seed), palette)
        val atlas = GeoAtlas(
            seed = setup.seed,
            resolved = resolved,
            sea = sea,
            vertical = o.float("height", 1f),
            scale = 1f / o.float("scale", 1f),
            maxHeight = o.float("maxHeight", Float.MAX_VALUE),
            minHeight = o.float("minHeight", 2f),
            spawnRadius = o.float("spawnRadius", 360f),
            spawnRise = o.float("spawnRise", 10f),
            only = geology.takeIf { it != AFRICA },
            home = o.string("home", "").takeIf { it.isNotBlank() && geology == AFRICA },
            step = o.int("sampleStep", 4),
        )
        val fields = setup.fields
        fields.publish(Fields.NATURAL_HEIGHT, atlas)
        fields.publish(Fields.SURFACE, atlas)
        fields.publish(Fields.CLIMATE, atlas.climate)
        fields.publish(Fields.SEA_LEVEL, sea)
        fields.publish(Fields.GEOLOGY, atlas)

        val mat = SurfaceMaterials(palette)
        val cache = ColumnCache<Columns>()
        val vertical = o.float("height", 1f)
        fields.publish(Fields.COLUMNS, ColumnSource { cx, cy ->
            cache.get(cx, cy) { columns(cx, cy, fields, atlas, resolved, mat, sea, vertical) }
        })
        return MicroStage { ctx -> fill(ctx, sea, mat, resolved) }
    }

    const val AFRICA = "africa"
    const val CLASSIC = "classic"

    private fun columns(
        cx: Int, cy: Int, fields: WorldFields, atlas: GeoAtlas, resolved: List<ResolvedProvince>,
        mat: SurfaceMaterials, sea: Int, vertical: Float,
    ): Columns {
        val s = MicroChunk.SIZE
        val surface = fields.require(Fields.SURFACE)
        val ox = cx * s; val oy = cy * s
        val w = s + 2
        val raw = FloatArray(w * w)
        for (y in 0 until w) for (x in 0 until w) raw[y * w + x] = surface.heightAt(ox + x - 1, oy + y - 1)
        val n = s * s
        val heights = IntArray(n); val tops = ShortArray(n); val slopes = FloatArray(n)
        val subs = ShortArray(n); val fills = ShortArray(n)
        val geo = GeoColumns(n)
        val strata = fields.get(Fields.STRATA)
        val col = GeoAtlas.Column()
        for (y in 0 until s) for (x in 0 until s) {
            val i = y * s + x
            val h = raw[(y + 1) * w + x + 1]
            val gx = (raw[(y + 1) * w + x + 2] - raw[(y + 1) * w + x]) * 0.5f
            val gy = (raw[(y + 2) * w + x + 1] - raw[y * w + x + 1]) * 0.5f
            val slope = sqrt(gx * gx + gy * gy)
            val wx = ox + x; val wy = oy + y
            atlas.column(wx, wy, col)
            val p = resolved[col.province]
            val hi = floor(h).toInt()
            // Levelled by a later stage (a town): the landform's hollows no longer apply there.
            val natural = kotlin.math.abs(h - col.height) < 1f
            var water = sea; var waterMat = mat.water
            if (natural && p.lake != null) {
                val level = floor(col.floor + p.province.surface.lakeBelow * vertical).toInt()
                if (level > sea && hi < level) { water = level; waterMat = p.lake }
            }
            val bare = slope > p.province.surface.cliff || (natural && col.relief > p.province.surface.bareAbove * vertical)
            val own = if (p.province.surface.soil) strata?.at(wx, wy) else null
            tops[i] = when {
                hi <= sea + 2 && hi >= sea - 12 && !bare -> p.shore
                bare -> MaterialPalette.AIR // read from the bedding, voxel by voxel
                hi < water -> p.floor ?: p.slope
                natural && p.floor != null && col.relief < p.province.surface.floorBelow * vertical -> p.floor
                slope > 0.8f -> p.slope
                own != null -> own.surface
                else -> p.ground
            }
            subs[i] = if (p.profile.isNotEmpty()) p.profile[0] else p.deep
            fills[i] = p.deep
            heights[i] = hi
            slopes[i] = slope
            // Lowest neighbour: the deepest this column's side is ever seen from.
            var low = hi
            low = min(low, floor(raw[(y + 1) * w + x]).toInt()); low = min(low, floor(raw[(y + 1) * w + x + 2]).toInt())
            low = min(low, floor(raw[y * w + x + 1]).toInt()); low = min(low, floor(raw[(y + 2) * w + x + 1]).toInt())
            geo.province[i] = col.province
            geo.floor[i] = col.floor
            geo.fold[i] = col.fold
            geo.water[i] = water
            geo.waterMat[i] = waterMat
            geo.low[i] = low
            geo.bare[i] = bare
        }
        return Columns(heights, tops, slopes, subs, fills, geo)
    }

    private fun fill(ctx: MicroGenContext, sea: Int, mat: SurfaceMaterials, resolved: List<ResolvedProvince>) {
        val s = MicroChunk.SIZE
        val cols = ctx.fields.require(Fields.COLUMNS).columns(ctx.pos.x, ctx.pos.y)
        val geo = cols.geo!!
        var lowest = Int.MAX_VALUE; var highest = Int.MIN_VALUE; var deepest = 0
        for (i in cols.heights.indices) {
            lowest = min(lowest, geo.low[i]); highest = max(highest, max(cols.heights[i], geo.water[i]))
            deepest = max(deepest, resolved[geo.province[i]].profile.size)
        }
        val top = max(highest, sea)
        if (ctx.z0 > top) return
        val soilMax = deepest + 2
        // Bulk rock, in whole bricks, under anything that could ever be seen.
        val centre = resolved[geo.province[(s / 2) * s + s / 2]]
        val rockTop = lowest - soilMax - 1
        if (rockTop >= ctx.z0) ctx.fill(ctx.x0, ctx.y0, ctx.z0, ctx.x1, ctx.y1, min(rockTop, ctx.z1), centre.deep)
        val groupFloor = IntArray((s / 8) * (s / 8))
        for (gy in 0 until s / 8) for (gx in 0 until s / 8) {
            var low = Int.MAX_VALUE
            for (y in gy * 8 until gy * 8 + 8) for (x in gx * 8 until gx * 8 + 8) low = min(low, geo.low[y * s + x])
            val floorZ = (Math.floorDiv(low - soilMax - 1 - ctx.z0 + 1, 8) * 8) + ctx.z0 - 1
            groupFloor[gy * (s / 8) + gx] = floorZ
            if (floorZ > rockTop && floorZ >= ctx.z0) {
                ctx.fill(ctx.x0 + gx * 8, ctx.y0 + gy * 8, max(ctx.z0, rockTop + 1), ctx.x0 + gx * 8 + 7, ctx.y0 + gy * 8 + 7, min(floorZ, ctx.z1), centre.deep)
            }
        }
        val end = min(ctx.z1, top)
        for (ly in 0 until s) for (lx in 0 until s) {
            val i = ly * s + lx
            val start = max(ctx.z0, max(rockTop, groupFloor[(ly / 8) * (s / 8) + lx / 8]) + 1)
            if (start > end) continue
            val p = resolved[geo.province[i]]
            val h = cols.heights[i]
            val topMat = cols.tops[i]
            val bare = geo.bare[i]
            val floorZ = geo.floor[i]
            val fold = geo.fold[i]
            val water = geo.water[i]
            val capFrom = if (p.cap != null) floorZ + p.province.strata.capAbove else Float.MAX_VALUE
            val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
            for (z in start..end) {
                val m = when {
                    z > h -> if (z <= water) geo.waterMat[i] else break
                    !bare && z == h -> topMat
                    !bare && h - z <= p.profile.size -> p.profile[h - z - 1]
                    z > capFrom -> p.cap!!
                    else -> p.bed(floor(z - floorZ + fold).toInt())
                }
                ctx.set(wx, wy, z, m)
            }
        }
    }
}

/** Per-column geology, when the terrain was built from provinces. */
class GeoColumns(size: Int) {
    val province = IntArray(size)
    /** The province's floor: where its bedding and caps count from. */
    val floor = FloatArray(size)
    val fold = FloatArray(size)
    /** The water surface over this column: the sea, or a lake's own level. */
    val water = IntArray(size)
    val waterMat = ShortArray(size)
    /** The lowest of this column and its neighbours: how deep its side can be seen. */
    val low = IntArray(size)
    /** Too steep for soil: bare rock, its bedding showing. */
    val bare = BooleanArray(size)
}
