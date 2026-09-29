package com.stratum.engine.microvoxel.gen

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.MicroChunk
import com.stratum.engine.microvoxel.geo.GeoAtlas
import com.stratum.engine.microvoxel.geo.GeoDials
import com.stratum.engine.microvoxel.geo.Provinces
import com.stratum.engine.microvoxel.geo.R
import com.stratum.engine.microvoxel.geo.ResolvedProvince
import com.stratum.engine.microvoxel.geo.RockColumn
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `micro:terrain` with `geology` set: the land built from Africa's
 * geological provinces (see [Provinces] and [GeoAtlas]) instead of generic
 * noise hills.
 *
 * Per column it knows the province, where the province's floor is, how the
 * bedding is folded there, and what the simulated processes left (a river's
 * water, a fan, floodplain silt, scree), and lays the ground down as a
 * geologist would read it in a cliff:
 *
 * - the surface: soil, sand, crust or salt as the province has it, the
 *   packs' own soil where the province is living soil, scree on steep
 *   slopes and at the foot of cliffs, gravel fans, dark silt beside rivers,
 *   gravel in their beds, bare rock on cliffs;
 * - under it the weathering profile (a laterite's crust, gravel, mottled
 *   clay and saprolite);
 * - under that the bedding, in bands fixed to the province's floor so both
 *   walls of a gorge show the same stripes, bent by folds where the province
 *   is folded, capped by the province's hard rock above a height (the
 *   Karoo's dolerite, the Drakensberg's basalt), and cut by the province's
 *   rock processes (dykes, veins, ore, nodules, cross-bedding, an
 *   unconformity);
 * - water in the sea, in rivers, and in the landform's own hollows where
 *   the province holds lakes (a rift's red soda lakes, a floodplain's pools,
 *   a lagoon).
 */
internal object GeoTerrain {

    fun create(setup: StageSetup, geology: String): MicroStage {
        val o = setup.options
        val sea = o.int("seaLevel", 96)
        val palette = setup.palette
        val dials = GeoDials(
            erosion = o.float("erosion", 1f).coerceIn(0f, 2f),
            rivers = o.float("rivers", 1f).coerceIn(0f, 2f),
            dunes = o.float("dunes", 1f).coerceIn(0f, 2f),
            scree = o.float("scree", 1f).coerceIn(0f, 2f),
            rock = o.float("rockDetail", 1f).coerceIn(0f, 2f),
        )
        val resolved = GeoAtlas.resolve(Provinces.all(setup.seed), palette, setup.seed, dials)
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
            dials = dials,
        )
        val fields = setup.fields
        fields.publish(Fields.NATURAL_HEIGHT, atlas)
        fields.publish(Fields.SURFACE, atlas)
        fields.publish(Fields.CLIMATE, atlas.climate)
        fields.publish(Fields.SEA_LEVEL, sea)
        fields.publish(Fields.GEOLOGY, atlas)

        val mat = GeoSurfaces(palette)
        // Columns are only read while a column's chunks are generated; the chunks themselves are cached
        // downstream. Each costs ~120 KB, so a phone keeps a view's worth, not hundreds.
        val cache = ColumnCache<Columns>(COLUMN_CACHE)
        val vertical = o.float("height", 1f)
        fields.publish(Fields.COLUMNS, ColumnSource { cx, cy ->
            cache.get(cx, cy) { columns(cx, cy, fields, atlas, resolved, mat, sea, vertical) }
        })
        return MicroStage { ctx -> fill(ctx, sea, resolved) }
    }

    const val AFRICA = "africa"
    private const val COLUMN_CACHE = 72
    const val CLASSIC = "classic"

    /** The surface materials the processes leave, resolved once per world. */
    private class GeoSurfaces(p: MaterialPalette) {
        val water = p.id(com.stratum.engine.microvoxel.M.WATER)
        val alluvium = p.id(R.ALLUVIUM)
        val silt = p.id(R.SILT)
        val riverBed = p.id(R.RIVER_GRAVEL)
    }

    private fun columns(
        cx: Int, cy: Int, fields: WorldFields, atlas: GeoAtlas, resolved: List<ResolvedProvince>,
        mat: GeoSurfaces, sea: Int, vertical: Float,
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
            val surf = p.province.surface
            val hi = floor(h).toInt()
            // Levelled by a later stage (a town): the landform's hollows and rivers no longer apply there.
            val natural = kotlin.math.abs(h - col.height) < 1f
            var water = sea; var waterMat = mat.water
            if (natural && p.lake != null) {
                val level = floor(col.floor + surf.lakeBelow * vertical).toInt()
                if (level > sea && hi < level) { water = level; waterMat = p.lake }
            }
            val mark = if (natural) col.mark else 0
            if (natural && col.water != GeoAtlas.NO_WATER) {
                val level = floor(col.water).toInt()
                if (level > water && hi < level) { water = level; waterMat = mat.water }
            }
            val bare = slope > surf.cliff || (natural && col.relief > surf.bareAbove * vertical)
            val own = if (surf.soil) strata?.at(wx, wy) else null
            tops[i] = when {
                mark == GeoAtlas.MARK_CHANNEL && !bare -> mat.riverBed
                hi <= sea + 2 && hi >= sea - 12 && !bare -> p.shore
                bare -> MaterialPalette.AIR // read from the bedding, voxel by voxel
                hi < water -> p.floor ?: p.slope
                natural && p.floor != null && col.relief < surf.floorBelow * vertical -> p.floor
                mark == GeoAtlas.MARK_SILT && slope < 0.6f -> mat.silt
                slope > 0.8f -> p.slope
                mark == GeoAtlas.MARK_TALUS -> p.slope
                mark == GeoAtlas.MARK_FAN && slope < 0.6f -> mat.alluvium
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

    private fun fill(ctx: MicroGenContext, sea: Int, resolved: List<ResolvedProvince>) {
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
        val groups = s / 8
        val groupFloor = IntArray(groups * groups)
        // Per 8 x 8 group: the highest ground, below which each column is filled voxel by voxel, and
        // the one water surface over it all when there is one -- open water is whole bricks.
        val groupGround = IntArray(groups * groups)
        for (gy in 0 until groups) for (gx in 0 until groups) {
            var low = Int.MAX_VALUE; var high = Int.MIN_VALUE
            var wLow = Int.MAX_VALUE; var wHigh = Int.MIN_VALUE; var wMat: Short = -1; var oneMat = true
            for (y in gy * 8 until gy * 8 + 8) for (x in gx * 8 until gx * 8 + 8) {
                val i = y * s + x
                low = min(low, geo.low[i]); high = max(high, cols.heights[i])
                wLow = min(wLow, geo.water[i]); wHigh = max(wHigh, geo.water[i])
                if (wMat < 0) wMat = geo.waterMat[i] else if (wMat != geo.waterMat[i]) oneMat = false
            }
            val floorZ = (Math.floorDiv(low - soilMax - 1 - ctx.z0 + 1, 8) * 8) + ctx.z0 - 1
            val g = gy * groups + gx
            groupFloor[g] = floorZ
            groupGround[g] = Int.MAX_VALUE
            if (floorZ > rockTop && floorZ >= ctx.z0) {
                ctx.fill(ctx.x0 + gx * 8, ctx.y0 + gy * 8, max(ctx.z0, rockTop + 1), ctx.x0 + gx * 8 + 7, ctx.y0 + gy * 8 + 7, min(floorZ, ctx.z1), centre.deep)
            }
            if (oneMat && wLow == wHigh && wLow > high) {
                groupGround[g] = high
                val z0 = max(ctx.z0, high + 1); val z1 = min(ctx.z1, wLow)
                if (z0 <= z1) ctx.fill(ctx.x0 + gx * 8, ctx.y0 + gy * 8, z0, ctx.x0 + gx * 8 + 7, ctx.y0 + gy * 8 + 7, z1, wMat)
            }
        }
        val end = min(ctx.z1, top)
        val rock = RockColumn()
        for (ly in 0 until s) for (lx in 0 until s) {
            val i = ly * s + lx
            val g = (ly / 8) * groups + lx / 8
            val start = max(ctx.z0, max(rockTop, groupFloor[g]) + 1)
            if (start > end) continue
            val p = resolved[geo.province[i]]
            val h = cols.heights[i]
            val topMat = cols.tops[i]
            val bare = geo.bare[i]
            val floorZ = geo.floor[i]
            val fold = geo.fold[i]
            val wx = ctx.x0 + lx; val wy = ctx.y0 + ly
            // The ground: bedding and caps, then the rock processes, then profile and surface over them.
            val groundTop = min(h, end)
            if (start <= groundTop) {
                val count = groundTop - start + 1
                val soil = if (bare) 0 else p.profile.size + 1
                rock.reset(wx, wy, h, start, count, floorZ, fold, soil)
                val m = rock.mat
                val capFrom = if (p.cap != null) floorZ + p.province.strata.capAbove else Float.MAX_VALUE
                val cap: Short = p.cap ?: 0
                val base = fold - floorZ
                for (z in start..groundTop) m[z - start] = if (z > capFrom) cap else p.bed(floor(z + base).toInt())
                if (p.rocks.isNotEmpty() && rock.rockTop >= start) for (r in p.rocks) r.apply(rock)
                if (!bare) {
                    for (z in max(start, h - p.profile.size)..groundTop) m[z - start] = if (z == h) topMat else p.profile[h - z - 1]
                }
                for (z in start..groundTop) ctx.set(wx, wy, z, m[z - start])
            }
            // Water over it, up to where the group's brick fill took over.
            val water = geo.water[i]
            val waterEnd = min(min(end, water), if (groupGround[g] == Int.MAX_VALUE) Int.MAX_VALUE else groupGround[g])
            if (water > h) for (z in max(start, h + 1)..waterEnd) ctx.set(wx, wy, z, geo.waterMat[i])
        }
    }
}

/** Per-column geology, when the terrain was built from provinces. */
class GeoColumns(size: Int) {
    val province = IntArray(size)
    /** The province's floor: where its bedding and caps count from. */
    val floor = FloatArray(size)
    val fold = FloatArray(size)
    /** The water surface over this column: the sea, a lake's own level, or a river's. */
    val water = IntArray(size)
    val waterMat = ShortArray(size)
    /** The lowest of this column and its neighbours: how deep its side can be seen. */
    val low = IntArray(size)
    /** Too steep for soil: bare rock, its bedding showing. */
    val bare = BooleanArray(size)
}
