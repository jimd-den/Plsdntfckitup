package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Climate
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.HeightFunction
import com.stratum.engine.microvoxel.gen.LatticeHeight
import com.stratum.engine.microvoxel.gen.Noise
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What other stages may ask about the geology: which province a column is in. */
interface GeoField {
    val provinces: List<Province>
    fun provinceAt(x: Int, y: Int): Province
}

/**
 * Where each province lies, and the ground it makes.
 *
 * The world is cut into irregular regions (a jittered Worley grid, [CELL]
 * microvoxels across); each takes the province that best fits the climate,
 * tectonics and elevation at its heart. Where two regions of different
 * provinces meet, their landforms are blended over [BLEND] microvoxels, so a
 * sand sea runs out onto a reg and a plateau's scarp dies into the plain.
 *
 * The ground's height is
 * `sea + vertical * (regional + province floor + landform relief)`, where
 * *regional* is one slow continental swell shared by all provinces (it puts
 * the coasts where they are) -- and then the simulated processes work it
 * over: water cuts valleys and lays fans ([Drainage]), rivers carve their
 * channels with levees and floodplains, and scree piles at the foot of
 * cliffs.
 *
 * ## Tiles
 *
 * The ground is built a *tile* at a time -- [FINE] x [FINE] lattice nodes,
 * one every `step` microvoxels (two chunks a side at the usual step of 4) --
 * into flat primitive arrays, cached, and interpolated per column. Working
 * a tile at once, rather than a node at a time, is what makes both the
 * speed and the simulation possible:
 *
 * - the slow, continent-scale fields (the continental swell, the warp that
 *   makes province borders wander, the land's grain) are sampled on a
 *   sparse grid and interpolated, not recomputed for every node;
 * - the few region hearts that can be nearest anywhere in the tile are
 *   found once, not nine hashes per node;
 * - scree needs each node's neighbours, which a tile has to hand: it is
 *   worked out over a margin of [screeReach] microvoxels, a bounded window,
 *   so a tile's scree matches its neighbour's exactly at the seam.
 *
 * Everything is a pure function of world coordinates, so any chunk comes
 * out the same alone, in any order, on any thread.
 */
class GeoAtlas(
    private val seed: Long,
    val resolved: List<ResolvedProvince>,
    private val sea: Int,
    private val vertical: Float = 1f,
    private val scale: Float = 1f,
    private val maxHeight: Float = Float.MAX_VALUE,
    private val minHeight: Float = 2f,
    private val spawnRadius: Float = 0f,
    private val spawnRise: Float = 0f,
    /** Every region is this province: for a world of one kind of country, or to look at one. */
    only: String? = null,
    /** The region at the world's origin is this province, so a home is always in the same kind of country. */
    home: String? = null,
    private val step: Int = 4,
    private val dials: GeoDials = GeoDials(),
) : HeightFunction, GeoField {

    init { require(step >= 1) { "step must be positive" } }

    override val provinces: List<Province> = resolved.map { it.province }
    private val noise = Noise(seed)
    private val climateNoise = Noise(seed xor 0x5A17)
    private val tectonicNoise = Noise(seed xor 0x7EC7)
    private val onlyIndex = only?.let { id -> indexOf(id) }
    private val homeIndex = home?.let { id -> indexOf(id) }

    private fun indexOf(id: String): Int = provinces.indexOfFirst { it.id == id }.also {
        require(it >= 0) { "No province '$id'. Known: ${provinces.joinToString { p -> p.id }}" }
    }

    // ---- Climate: the same slow fields the classic terrain uses ---------------------------

    // Continental climate changes over thousands of blocks: whole zones of desert, savanna and
    // forest, not a patchwork. A little faster detail keeps the next zone a walk away.
    private val warmth = LatticeHeight(step = 16, source = HeightFunction { x, y ->
        (0.5f + (climateNoise.fbm(x * 0.00011f, y * 0.00011f, 3) * 0.8f + climateNoise.fbm(x * 0.0006f, y * 0.0006f, 2) * 0.25f) * 1.3f).coerceIn(0f, 1f)
    })
    private val wetness = LatticeHeight(step = 16, source = HeightFunction { x, y ->
        (0.5f + (climateNoise.fbm(x * 0.00013f - 77f, y * 0.00013f + 13f, 3) * 0.8f + climateNoise.fbm(x * 0.0007f + 5f, y * 0.0007f, 2) * 0.25f) * 1.3f).coerceIn(0f, 1f)
    })
    val climate = object : Climate {
        override fun temperature(x: Int, y: Int) = warmth.heightAt(x, y)
        override fun moisture(x: Int, y: Int) = wetness.heightAt(x, y)
    }

    // ---- Regions ----------------------------------------------------------------------------

    private val regions = ConcurrentHashMap<Long, Int>()
    private val homeCell: Long = run {
        // The region whose heart is nearest the origin.
        var best = Long.MIN_VALUE; var bestD = Float.MAX_VALUE
        for (j in -1..1) for (i in -1..1) {
            val dx = featureX(i, j); val dy = featureY(i, j)
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = key(i, j) }
        }
        best
    }

    private fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)
    private fun featureX(i: Int, j: Int) = (i + 0.15f + 0.7f * Hash.unit(seed, i, j, 0, 811)) * CELL
    private fun featureY(i: Int, j: Int) = (j + 0.15f + 0.7f * Hash.unit(seed, i, j, 0, 812)) * CELL

    /** The province of region (i, j): the best fit at its heart, with a little chance so neighbours differ. */
    private fun regionProvince(i: Int, j: Int): Int {
        onlyIndex?.let { return it }
        val k = key(i, j)
        if (homeIndex != null && k == homeCell) return homeIndex
        return regions.getOrPut(k) {
            val fx = featureX(i, j); val fy = featureY(i, j)
            val heat = warmth.heightAt(fx.toInt(), fy.toInt())
            val wet = wetness.heightAt(fx.toInt(), fy.toInt())
            val tectonic = tectonicAt(fx, fy)
            val elevation = elevationClass(continent(fx, fy))
            var best = 0; var bestScore = Float.MAX_VALUE
            for ((index, p) in provinces.withIndex()) {
                val score = p.misfit(heat, wet, tectonic, elevation) + Hash.unit(seed, i, j, index, 813) * JITTER
                if (score < bestScore) { bestScore = score; best = index }
            }
            best
        }
    }

    private fun tectonicAt(x: Float, y: Float) = (0.42f + tectonicNoise.fbm(x * 0.00016f, y * 0.00016f, 3) * 1.4f).coerceIn(0f, 1f)

    private fun continent(x: Float, y: Float): Float {
        val (wx, wy) = noise.warp(x, y, strength = 90f, scale = 0.0021f)
        return noise.fbm(wx * 0.0009f + 31f, wy * 0.0009f - 17f, 4)
    }

    private fun elevationClass(c: Float) = Shape.smooth(-0.05f, 0.5f, c)

    /** The land's direction: a slow field of angles, so rifts, folds and dunes line up regionally. */
    private fun grain(x: Float, y: Float) = noise.fbm(x * 0.00022f + 300f, y * 0.00022f - 90f, 2) * 4.7f

    /** How the bedding is bent here; see [Fold]. Uses the fold belt's own phase, so the layers follow its ridges. */
    private fun foldOffset(p: Province, x: Float, y: Float, grain: Float): Float {
        val f = p.strata.fold
        if (f.amplitude == 0f && f.tilt == 0f) return 0f
        val u = Shape.across(x, y, grain); val v = Shape.along(x, y, grain)
        val phase = u / f.wavelength * 6.2832f + noise.fbm(v * 0.0015f, u * 0.0005f, 2) * 1.6f
        return f.amplitude * (0.5f + 0.5f * cos(phase)) + f.tilt * u
    }

    private fun softCeiling(h: Float): Float {
        if (maxHeight == Float.MAX_VALUE) return h
        val knee = maxHeight - 24f
        if (h <= knee) return h
        val over = h - knee
        return knee + 24f * (over / (over + 24f))
    }

    // ---- Raw ground: landforms, blended, before any process works on it ----------------------

    /**
     * World microvoxels between samples of the slow fields: 32 at the usual
     * breadth, closer when the land is squeezed. A power of two, so every
     * block interpolating a point gets bit-identical weights.
     */
    private val slowStep = Integer.highestOneBit((32f / scale).toInt().coerceIn(4, 64))

    /**
     * The raw ground for an [n] x [n] block of points [spacing] apart from
     * world (wx0, wy0), row-major: heights, the province's floor, the fold
     * offset (any of them null when not wanted -- with no [height], only the
     * provinces are worked out) and the province.
     */
    private fun rawBlock(
        wx0: Int, wy0: Int, n: Int, spacing: Int, height: FloatArray?, floor: FloatArray?, fold: FloatArray?, province: IntArray,
        ss: Int = slowStep,
    ) {
        // The slow fields on their own sparse, world-aligned grid.
        val span = (n - 1) * spacing
        val sx0 = Math.floorDiv(wx0, ss); val sy0 = Math.floorDiv(wy0, ss)
        val sw = Math.floorDiv(wx0 + span, ss) - sx0 + 2; val sh = Math.floorDiv(wy0 + span, ss) - sy0 + 2
        val cont = FloatArray(sw * sh); val warpX = FloatArray(sw * sh); val warpY = FloatArray(sw * sh); val grains = FloatArray(sw * sh)
        var bxMin = Float.MAX_VALUE; var bxMax = -Float.MAX_VALUE; var byMin = Float.MAX_VALUE; var byMax = -Float.MAX_VALUE
        for (j in 0 until sh) for (i in 0 until sw) {
            val x = (sx0 + i) * ss * scale; val y = (sy0 + j) * ss * scale
            val k = j * sw + i
            if (height != null) {
                cont[k] = continent(x, y)
                grains[k] = grain(x, y)
            }
            warpX[k] = noise.fbm(x * 0.0009f + 91f, y * 0.0009f, 3) * BORDER_WARP
            warpY[k] = noise.fbm(x * 0.0009f, y * 0.0009f - 57f, 3) * BORDER_WARP
            bxMin = min(bxMin, x + warpX[k]); bxMax = max(bxMax, x + warpX[k])
            byMin = min(byMin, y + warpY[k]); byMax = max(byMax, y + warpY[k])
        }
        // Every region heart that could be nearest to any point here. Hearts are jittered within the middle
        // 70% of their cells, so none more than two cells away can win: with that margin the nearest is exact,
        // and any block containing a point finds the same province for it.
        val ci0 = floor(bxMin / CELL).toInt() - 2; val ci1 = floor(bxMax / CELL).toInt() + 2
        val cj0 = floor(byMin / CELL).toInt() - 2; val cj1 = floor(byMax / CELL).toInt() + 2
        val count = (ci1 - ci0 + 1) * (cj1 - cj0 + 1)
        val hx = FloatArray(count); val hy = FloatArray(count); val hp = IntArray(count)
        var c = 0
        for (j in cj0..cj1) for (i in ci0..ci1) { hx[c] = featureX(i, j); hy[c] = featureY(i, j); hp[c] = regionProvince(i, j); c++ }

        for (j in 0 until n) for (i in 0 until n) {
            val wx = wx0 + i * spacing; val wy = wy0 + j * spacing
            // Bilinear in the slow grid.
            val gx = (wx - sx0 * ss).toFloat() / ss; val gy = (wy - sy0 * ss).toFloat() / ss
            val ix = gx.toInt(); val iy = gy.toInt()
            val fx = gx - ix; val fy = gy - iy
            val a = iy * sw + ix; val b = a + 1; val cc = a + sw; val d = cc + 1
            fun lerp(f: FloatArray): Float { val top = f[a] + (f[b] - f[a]) * fx; return top + (f[cc] + (f[d] - f[cc]) * fx - top) * fy }
            val x = wx * scale; val y = wy * scale
            val bx = x + lerp(warpX); val by = y + lerp(warpY)
            var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE; var p1 = 0; var p2 = 0
            for (k in 0 until count) {
                val dx = hx[k] - bx; val dy = hy[k] - by
                val dd = dx * dx + dy * dy
                if (dd < d1) { d2 = d1; p2 = p1; d1 = dd; p1 = hp[k] } else if (dd < d2) { d2 = dd; p2 = hp[k] }
            }
            val k = j * n + i
            province[k] = p1
            if (height == null) continue
            val regional = (lerp(cont) * 70f).coerceIn(-36f, 26f)
            val grain = lerp(grains)
            d1 = sqrt(d1); d2 = sqrt(d2)
            val pa = resolved[p1]
            var relief = pa.relief(x, y, grain, noise)
            var floorLevel = pa.province.base
            var bend = if (fold != null) foldOffset(pa.province, x, y, grain) else 0f
            if (p2 != p1 && d2 - d1 < BLEND) {
                // Blend towards the neighbour: half and half on the border, all ours at BLEND inside.
                val w = 0.5f + 0.5f * Shape.smooth(0f, BLEND, d2 - d1)
                val pb = resolved[p2]
                relief = relief * w + pb.relief(x, y, grain, noise) * (1f - w)
                floorLevel = floorLevel * w + pb.province.base * (1f - w)
                if (fold != null) bend = bend * w + foldOffset(pb.province, x, y, grain) * (1f - w)
            }
            height[k] = sea + vertical * (regional + floorLevel + relief)
            floor?.set(k, sea + vertical * (regional + floorLevel))
            fold?.set(k, bend)
        }
    }

    // ---- Drainage tiles ---------------------------------------------------------------------

    private val drainage = Drainage(seed, sea, resolved, dials) { gx0, gy0, n, spacing, heights, provinces ->
        // The drainage only needs the land's shape: its slow fields can be four times sparser.
        rawBlock(gx0 * spacing, gy0 * spacing, n, spacing, heights, null, null, provinces, slowStep * 4)
    }
    private val drains = AtomicReferenceArray<DrainTile?>(DRAIN_SLOTS)

    private fun drain(tx: Int, ty: Int): DrainTile {
        val slot = gridSlot(tx, ty, DRAIN_SIDE)
        drains.get(slot)?.let { if (it.tx == tx && it.ty == ty) return it }
        return drainage.build(tx, ty).also { drains.set(slot, it) }
    }

    private val simulate = dials.erosion > 0f || dials.rivers > 0f

    // ---- Fine tiles -----------------------------------------------------------------------------

    /** Scree reaches this far from a cliff, microvoxels: also the margin a tile works over. */
    private val screeReach = 20
    private val margin = (screeReach + step - 1) / step

    /** One tile of finished ground, (FINE + 1)^2 nodes so interpolation never leaves it. */
    private class FineTile(val tx: Int, val ty: Int) {
        val height = FloatArray(E * E); val floor = FloatArray(E * E); val fold = FloatArray(E * E)
        val province = IntArray(E * E); val water = FloatArray(E * E); val mark = ByteArray(E * E)
    }

    private val tiles = AtomicReferenceArray<FineTile?>(FINE_SLOTS)
    @Volatile private var last: FineTile? = null

    private fun tile(tx: Int, ty: Int): FineTile {
        last?.let { if (it.tx == tx && it.ty == ty) return it }
        val slot = gridSlot(tx, ty, FINE_SIDE)
        val t = tiles.get(slot)?.takeIf { it.tx == tx && it.ty == ty } ?: buildFine(tx, ty).also { tiles.set(slot, it) }
        last = t
        return t
    }

    private fun buildFine(tx: Int, ty: Int): FineTile {
        val out = FineTile(tx, ty)
        val m = margin; val dn = E + 2 * m; val s = step
        val gx0 = tx * FINE - m; val gy0 = ty * FINE - m
        val wx0 = gx0 * s; val wy0 = gy0 * s
        val h = FloatArray(dn * dn); val flo = FloatArray(dn * dn); val fold = FloatArray(dn * dn); val prov = IntArray(dn * dn)
        rawBlock(wx0, wy0, dn, s, h, flo, fold, prov)
        val laid = FloatArray(dn * dn)
        if (simulate) erode(wx0, wy0, dn, h, laid)
        val talus = scree(dn, h, prov)
        // The finished core, with rivers carved into it.
        val segs = if (dials.rivers > 0f) riversNear(tx, ty) else EMPTY
        for (j in 0 until E) for (i in 0 until E) {
            val k = (j + m) * dn + (i + m)
            val o = j * E + i
            val wx = (tx * FINE + i) * s; val wy = (ty * FINE + j) * s
            var hn = h[k] + talus[o]
            var mark = when {
                laid[k] > 0.8f -> MARK_FAN
                talus[o] > 1.2f -> MARK_TALUS
                else -> 0
            }
            var water = NO_WATER
            if (segs.isNotEmpty()) {
                val river = carve(segs, wx.toFloat(), wy.toFloat(), hn)
                if (river != null) { hn = river.height; water = river.water; if (river.mark > mark) mark = river.mark }
            }
            if (spawnRadius > 0f && hn < sea + spawnRise) {
                val d = sqrt(wx.toFloat() * wx + wy.toFloat() * wy) / spawnRadius
                if (d < 1f) hn += (sea + spawnRise - hn) * Shape.smooth(0f, 1f, 1f - d)
            }
            out.height[o] = softCeiling(hn).coerceAtLeast(minHeight)
            out.water[o] = if (water == NO_WATER) NO_WATER else softCeiling(water)
            out.floor[o] = flo[k]; out.fold[o] = fold[k]; out.province[o] = prov[k]; out.mark[o] = mark.toByte()
        }
        return out
    }

    /** Adds the drainage tiles' erosion and deposition to a block of nodes, cross-fading tiles at their seams. */
    private fun erode(wx0: Int, wy0: Int, dn: Int, h: FloatArray, laid: FloatArray) {
        val ds = DrainTile.SPACING.toFloat()
        for (j in 0 until dn) for (i in 0 until dn) {
            val p = (wx0 + i * step) / ds; val q = (wy0 + j * step) / ds
            val tx = Math.floorDiv(floor(p).toInt(), DrainTile.CORE); val ty = Math.floorDiv(floor(q).toInt(), DrainTile.CORE)
            var dh = 0f; var dl = 0f
            for (b in ty - 1..ty + 1) {
                val wy = DrainTile.weight(q, b)
                if (wy <= 0f) continue
                for (a in tx - 1..tx + 1) {
                    val wx = DrainTile.weight(p, a)
                    if (wx <= 0f) continue
                    val t = drain(a, b)
                    val lp = p - t.gx0; val lq = q - t.gy0
                    val li = lp.toInt(); val lj = lq.toInt()
                    val fx = lp - li; val fy = lq - lj
                    val c = lj * DrainTile.D + li
                    fun lerp(f: FloatArray): Float {
                        val top = f[c] + (f[c + 1] - f[c]) * fx
                        return top + (f[c + DrainTile.D] + (f[c + DrainTile.D + 1] - f[c + DrainTile.D]) * fx - top) * fy
                    }
                    val w = wx * wy
                    dh += lerp(t.delta) * w; dl += lerp(t.laid) * w
                }
            }
            val k = j * dn + i
            // The drainage worked on heights already stretched by `vertical`, so its cuts are in world units too.
            h[k] += dh; laid[k] = dl
        }
    }

    /**
     * Scree: rubble fallen from a cliff lies against its foot at its angle of
     * rest, reaching about a third of the way up. For each core node, the
     * tallest pile any node within [screeReach] would shed onto it -- a
     * third of the height difference, less the angle of rest times the
     * distance -- found as a bounded, separable max-filter over the tile's
     * margin, so it costs a few dozen operations per node and matches across
     * seams exactly.
     */
    private fun scree(dn: Int, h: FloatArray, prov: IntArray): FloatArray {
        val out = FloatArray(E * E)
        if (dials.scree <= 0f) return out
        val m = margin; val r = m
        val fall = SCREE_SLOPE * step
        // Along rows first, for the core's columns over every row of the margin.
        val rows = FloatArray(dn * E)
        for (j in 0 until dn) for (i in 0 until E) {
            val c = j * dn + i + m
            var best = h[c] * SCREE_SHARE
            for (d in 1..r) {
                val drop = fall * d
                best = max(best, max(h[c - d], h[c + d]) * SCREE_SHARE - drop)
            }
            rows[j * E + i] = best
        }
        for (j in 0 until E) for (i in 0 until E) {
            val k = (j + m) * dn + i + m
            val w = resolved[prov[k]].scree * dials.scree
            if (w <= 0f) continue
            var best = rows[(j + m) * E + i]
            for (d in 1..r) best = max(best, max(rows[(j + m - d) * E + i], rows[(j + m + d) * E + i]) - fall * d)
            val pile = (best - h[k] * SCREE_SHARE).coerceIn(0f, SCREE_CAP * vertical.coerceAtLeast(0.3f))
            out[j * E + i] = pile * w.coerceAtMost(1.5f)
        }
        return out
    }

    // ---- Rivers -----------------------------------------------------------------------------

    /** The river segments that can touch a fine tile, packed as the drainage tiles pack them. */
    private fun riversNear(tx: Int, ty: Int): FloatArray {
        val reach = Drainage.HALF_MAX + RIVER_REACH
        val ds = DrainTile.SPACING
        val x0 = tx * FINE * step - reach; val x1 = (tx + 1) * FINE * step + reach
        val y0 = ty * FINE * step - reach; val y1 = (ty + 1) * FINE * step + reach
        val out = ArrayList<Float>()
        for (gj in Math.floorDiv(y0.toInt(), ds) - 2..Math.floorDiv(y1.toInt(), ds) + 2)
            for (gi in Math.floorDiv(x0.toInt(), ds) - 2..Math.floorDiv(x1.toInt(), ds) + 2) {
                val t = drain(Math.floorDiv(gi, DrainTile.CORE), Math.floorDiv(gj, DrainTile.CORE))
                val si = t.segAt[(gj - t.ty * DrainTile.CORE) * DrainTile.CORE + (gi - t.tx * DrainTile.CORE)]
                if (si < 0) continue
                val o = si * DrainTile.SEG
                val s = t.segs
                if (max(s[o], s[o + 2]) < x0 || min(s[o], s[o + 2]) > x1 || max(s[o + 1], s[o + 3]) < y0 || min(s[o + 1], s[o + 3]) > y1) continue
                for (f in 0 until DrainTile.SEG) out += s[o + f]
            }
        return if (out.isEmpty()) EMPTY else out.toFloatArray()
    }

    private class Carved(var height: Float = 0f, var water: Float = NO_WATER, var mark: Int = 0)
    private val carvedScratch = ThreadLocal.withInitial { Carved() }

    /**
     * A river's work at one point: its valley walls cut down to it, levees
     * raised along a wet channel so the water stays in it, silt on the
     * floodplain beside it, and the channel itself. Null when no river is
     * near enough to matter.
     */
    private fun carve(segs: FloatArray, x: Float, y: Float, h: Float): Carved? {
        var hn = h
        var touched = false
        val n = segs.size / DrainTile.SEG
        // Nearest point and level on each segment, reused by the three passes.
        var nearest = -1; var nearestD = Float.MAX_VALUE
        for (s in 0 until n) {
            val o = s * DrainTile.SEG
            val d = distance(segs, o, x, y)
            val half = segs[o + 6]
            if (d > half + RIVER_REACH) continue
            touched = true
            val lvl = levelAt(segs, o, x, y)
            if (d < nearestD) { nearestD = d; nearest = s }
            val out = d - half
            val bank = if (out <= 0f) lvl + BANK else lvl + BANK + out * 0.45f + max(0f, out - 20f).let { it * it * 0.04f }
            hn = min(hn, bank)
        }
        if (!touched) return null
        val c = carvedScratch.get()
        c.mark = 0; c.water = NO_WATER
        for (s in 0 until n) {
            val o = s * DrainTile.SEG
            val half = segs[o + 6]
            val d = distance(segs, o, x, y)
            if (d > half + FLOODPLAIN) continue
            val lvl = levelAt(segs, o, x, y)
            val dry = segs[o + 8] > 0.5f
            val out = d - half
            if (!dry && out >= 0f && out < LEVEE) hn = max(hn, min(h, lvl + 0.7f).coerceAtLeast(lvl + 0.4f))
            if (!dry && out >= 0f && hn < lvl + 5f) c.mark = max(c.mark, MARK_SILT)
        }
        for (s in 0 until n) {
            val o = s * DrainTile.SEG
            val half = segs[o + 6]
            val d = distance(segs, o, x, y)
            if (d >= half) continue
            val lvl = levelAt(segs, o, x, y)
            val t = d / half
            hn = min(hn, lvl - segs[o + 7] * (1f - t * t))
            c.mark = MARK_CHANNEL
            if (segs[o + 8] < 0.5f) c.water = max(c.water, lvl)
        }
        c.height = hn
        return c
    }

    private fun distance(s: FloatArray, o: Int, x: Float, y: Float): Float {
        val t = along(s, o, x, y)
        val px = s[o] + (s[o + 2] - s[o]) * t - x; val py = s[o + 1] + (s[o + 3] - s[o + 1]) * t - y
        return sqrt(px * px + py * py)
    }

    private fun levelAt(s: FloatArray, o: Int, x: Float, y: Float): Float = s[o + 4] + (s[o + 5] - s[o + 4]) * along(s, o, x, y)

    private fun along(s: FloatArray, o: Int, x: Float, y: Float): Float {
        val dx = s[o + 2] - s[o]; val dy = s[o + 3] - s[o + 1]
        val len = dx * dx + dy * dy
        if (len <= 0f) return 0f
        return (((x - s[o]) * dx + (y - s[o + 1]) * dy) / len).coerceIn(0f, 1f)
    }

    // ---- Queries ------------------------------------------------------------------------------

    /** Everything the material rules need about one column. */
    class Column {
        var height = 0f; var fold = 0f; var province = 0
        /** The province's floor here: where its bedding and caps count from, whatever erosion took off the top. */
        var floor = 0f
        /** A river's surface over this column, or [NO_WATER]. */
        var water = NO_WATER
        /** What the processes left on top: [MARK_TALUS], [MARK_FAN], [MARK_SILT], [MARK_CHANNEL], or 0. */
        var mark = 0
        /** How high the land stands above the province's floor. */
        val relief: Float get() = height - floor
    }

    fun column(x: Int, y: Int, out: Column): Column {
        val ix = Math.floorDiv(x, step); val iy = Math.floorDiv(y, step)
        val tx = Math.floorDiv(ix, FINE); val ty = Math.floorDiv(iy, FINE)
        val t = tile(tx, ty)
        val li = ix - tx * FINE; val lj = iy - ty * FINE
        val fx = (x - ix * step).toFloat() / step; val fy = (y - iy * step).toFloat() / step
        val a = lj * E + li; val b = a + 1; val c = a + E; val d = c + 1
        fun lerp(arr: FloatArray): Float {
            val top = arr[a] + (arr[b] - arr[a]) * fx
            return top + (arr[c] + (arr[d] - arr[c]) * fx - top) * fy
        }
        out.height = lerp(t.height); out.floor = lerp(t.floor); out.fold = lerp(t.fold)
        // The nearest node's province and mark: borders come out one block ragged, like real contacts.
        val near = if (fy < 0.5f) (if (fx < 0.5f) a else b) else (if (fx < 0.5f) c else d)
        out.province = t.province[near]
        out.mark = t.mark[near].toInt()
        out.water = max(max(t.water[a], t.water[b]), max(t.water[c], t.water[d]))
        return out
    }

    override fun heightAt(x: Int, y: Int): Float {
        val ix = Math.floorDiv(x, step); val iy = Math.floorDiv(y, step)
        val tx = Math.floorDiv(ix, FINE); val ty = Math.floorDiv(iy, FINE)
        val t = tile(tx, ty)
        val a = (iy - ty * FINE) * E + (ix - tx * FINE)
        val fx = (x - ix * step).toFloat() / step; val fy = (y - iy * step).toFloat() / step
        val hh = t.height
        val top = hh[a] + (hh[a + 1] - hh[a]) * fx
        return top + (hh[a + E] + (hh[a + E + 1] - hh[a + E]) * fx - top) * fy
    }

    /** Provinces alone for a fine tile's nodes: what [provinceAt] reads where the ground itself was never needed. */
    private class ProvinceTile(val tx: Int, val ty: Int) { val province = IntArray(E * E) }
    private val provinceTiles = AtomicReferenceArray<ProvinceTile?>(FINE_SLOTS)

    /**
     * The province at the lattice node nearest (x, y). Read from the ground's
     * own tile when it is built; otherwise from a tile of provinces alone, a
     * few dozen times cheaper -- so a map of a continent does not build its
     * ground. Both find the exact nearest region heart, so they always agree.
     */
    override fun provinceAt(x: Int, y: Int): Province {
        val ix = Math.floorDiv(x + step / 2, step); val iy = Math.floorDiv(y + step / 2, step)
        val tx = Math.floorDiv(ix, FINE); val ty = Math.floorDiv(iy, FINE)
        val at = (iy - ty * FINE) * E + (ix - tx * FINE)
        val slot = gridSlot(tx, ty, FINE_SIDE)
        tiles.get(slot)?.let { if (it.tx == tx && it.ty == ty) return provinces[it.province[at]] }
        val t = provinceTiles.get(slot)?.takeIf { it.tx == tx && it.ty == ty } ?: ProvinceTile(tx, ty).also {
            rawBlock(tx * FINE * step, ty * FINE * step, E, step, null, null, null, it.province)
            provinceTiles.set(slot, it)
        }
        return provinces[t.province[at]]
    }

    companion object {
        /** Microvoxels across a region: about 500 blocks, a morning's walk. */
        const val CELL = 2000f
        /** How far borders wander from the straight lines between region hearts. */
        const val BORDER_WARP = 700f
        /** How far either side of a border two provinces' landforms blend. */
        const val BLEND = 220f
        /** How much chance mixes into choosing a region's province, against pure fit. */
        const val JITTER = 0.35f

        /** Lattice nodes a side a fine tile owns. */
        private const val FINE = 32
        private const val E = FINE + 1
        /** Fine tiles cached: a 16 x 16 window of neighbours. */
        private const val FINE_SIDE = 16
        private const val FINE_SLOTS = FINE_SIDE * FINE_SIDE
        /** Drainage tiles cached: a 4 x 4 window, more than the 2 x 2 a fine tile's erosion reads. */
        private const val DRAIN_SIDE = 4
        private const val DRAIN_SLOTS = DRAIN_SIDE * DRAIN_SIDE

        /**
         * A tile's cache slot: its position modulo a [side] x [side] grid, so
         * any [side] x [side] block of neighbouring tiles never shares a slot.
         * A hashed slot let two neighbours evict each other on every node of
         * an erosion pass, rebuilding a drainage tile thousands of times.
         */
        private fun gridSlot(tx: Int, ty: Int, side: Int) = Math.floorMod(ty, side) * side + Math.floorMod(tx, side)

        /** Scree reaches this share of the cliff above it... */
        private const val SCREE_SHARE = 0.32f
        /** ...and falls away at its angle of rest, rise per run. */
        private const val SCREE_SLOPE = 0.7f
        private const val SCREE_CAP = 22f

        /** How far beyond its channel a river's valley walls are cut, microvoxels. */
        private const val RIVER_REACH = 64f
        /** A river's banks stand this far above its water. */
        private const val BANK = 0.8f
        /** Levees are this wide, microvoxels from the channel's edge. */
        private const val LEVEE = 3f
        /** Silt spreads this far from the channel over low ground. */
        private const val FLOODPLAIN = 26f

        const val NO_WATER = -1e9f
        const val MARK_TALUS = 1
        const val MARK_FAN = 2
        const val MARK_SILT = 3
        const val MARK_CHANNEL = 4

        private val EMPTY = FloatArray(0)

        /** Every province, resolved against [palette], its steps built with [seed] and turned by [dials]. */
        fun resolve(provinces: List<Province>, palette: MaterialPalette, seed: Long = 0L, dials: GeoDials = GeoDials()) =
            provinces.map { ResolvedProvince(it, palette, seed, dials) }
    }
}
