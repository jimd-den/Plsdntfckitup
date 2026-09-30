package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.MaterialPalette
import com.stratum.engine.microvoxel.gen.Climate
import com.stratum.engine.microvoxel.gen.Hash
import com.stratum.engine.microvoxel.gen.HeightFunction
import com.stratum.engine.microvoxel.gen.LatticeHeight
import com.stratum.engine.microvoxel.gen.Noise
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor
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
 * the coasts where they are). Everything is sampled once a block on a
 * world-aligned lattice and interpolated -- the same trick as
 * [LatticeHeight] -- with the province, its relief and the fold offset of its
 * bedding carried along, so the material rules can read them per column for
 * free.
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
) : HeightFunction, GeoField {

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

    // ---- The lattice ------------------------------------------------------------------------

    private class Node { var height = 0f; var relief = 0f; var fold = 0f; var province = 0 }

    private class Memo {
        val keys = LongArray(SIZE) { Long.MIN_VALUE }
        val height = FloatArray(SIZE); val relief = FloatArray(SIZE); val fold = FloatArray(SIZE); val province = IntArray(SIZE)
    }

    private val memo = ThreadLocal.withInitial { Memo() }
    private val scratch = ThreadLocal.withInitial { Node() }

    private fun node(ix: Int, iy: Int, m: Memo): Int {
        val key = (ix.toLong() shl 32) or (iy.toLong() and 0xFFFFFFFFL)
        val slot = ((ix * 73856093) xor (iy * 19349663)) and (SIZE - 1)
        if (m.keys[slot] != key) {
            val n = scratch.get()
            compute(ix * step, iy * step, n)
            m.keys[slot] = key; m.height[slot] = n.height; m.relief[slot] = n.relief; m.fold[slot] = n.fold; m.province[slot] = n.province
        }
        return slot
    }

    /** The ground at one lattice node, from scratch. */
    private fun compute(ix: Int, iy: Int, out: Node) {
        val x = ix * scale; val y = iy * scale
        val c = continent(x, y)
        val regional = (c * 70f).coerceIn(-36f, 26f)
        // The two nearest region hearts, measured through a warp so borders wander like real contacts.
        val bx = x + noise.fbm(x * 0.0009f + 91f, y * 0.0009f, 3) * BORDER_WARP
        val by = y + noise.fbm(x * 0.0009f, y * 0.0009f - 57f, 3) * BORDER_WARP
        val ci = floor(bx / CELL).toInt(); val cj = floor(by / CELL).toInt()
        var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE; var r1 = 0L; var r2 = 0L
        for (oj in -1..1) for (oi in -1..1) {
            val i = ci + oi; val j = cj + oj
            val dx = featureX(i, j) - bx; val dy = featureY(i, j) - by
            val d = sqrt(dx * dx + dy * dy)
            if (d < d1) { d2 = d1; r2 = r1; d1 = d; r1 = key(i, j) } else if (d < d2) { d2 = d; r2 = key(i, j) }
        }
        val p1 = regionProvince((r1 shr 32).toInt(), r1.toInt())
        val p2 = regionProvince((r2 shr 32).toInt(), r2.toInt())
        val grain = grain(x, y)
        val a = resolved[p1].province
        var relief = a.landform.relief(x, y, grain, noise)
        var floorLevel = a.base
        var fold = foldOffset(a, x, y, grain)
        if (p2 != p1 && d2 - d1 < BLEND) {
            // Blend towards the neighbour: half and half on the border, all ours at BLEND inside.
            val w = 0.5f + 0.5f * Shape.smooth(0f, BLEND, d2 - d1)
            val b = resolved[p2].province
            relief = relief * w + b.landform.relief(x, y, grain, noise) * (1f - w)
            floorLevel = floorLevel * w + b.base * (1f - w)
            fold = fold * w + foldOffset(b, x, y, grain) * (1f - w)
        }
        var h = sea + vertical * (regional + floorLevel + relief)
        if (spawnRadius > 0f && h < sea + spawnRise) {
            val d = sqrt(ix.toFloat() * ix + iy.toFloat() * iy) / spawnRadius
            if (d < 1f) h += (sea + spawnRise - h) * Shape.smooth(0f, 1f, 1f - d)
        }
        h = softCeiling(h).coerceAtLeast(minHeight)
        out.height = h
        out.relief = relief * vertical
        out.fold = fold
        out.province = p1
    }

    /** How far the bedding is bent here; see [Fold]. Uses the fold belt's own phase, so the layers follow its ridges. */
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

    // ---- Queries ------------------------------------------------------------------------------

    /** Everything the material rules need about one column. */
    class Column {
        var height = 0f; var relief = 0f; var fold = 0f; var province = 0
        /** The province's floor here: the ground minus the landform's relief. Caps and bedding count from it. */
        val floor: Float get() = height - relief
    }

    fun column(x: Int, y: Int, out: Column): Column {
        val m = memo.get()
        if (step == 1) {
            val s = node(x, y, m)
            out.height = m.height[s]; out.relief = m.relief[s]; out.fold = m.fold[s]; out.province = m.province[s]
            return out
        }
        val ix = Math.floorDiv(x, step); val iy = Math.floorDiv(y, step)
        val fx = (x - ix * step).toFloat() / step; val fy = (y - iy * step).toFloat() / step
        val a = node(ix, iy, m); val b = node(ix + 1, iy, m); val c = node(ix, iy + 1, m); val d = node(ix + 1, iy + 1, m)
        fun lerp(arr: FloatArray): Float {
            val top = arr[a] + (arr[b] - arr[a]) * fx
            return top + (arr[c] + (arr[d] - arr[c]) * fx - top) * fy
        }
        out.height = lerp(m.height); out.relief = lerp(m.relief); out.fold = lerp(m.fold)
        // The nearest node's province: borders come out one block ragged, like real contacts.
        out.province = m.province[if (fy < 0.5f) (if (fx < 0.5f) a else b) else (if (fx < 0.5f) c else d)]
        return out
    }

    private val columnScratch = ThreadLocal.withInitial { Column() }

    override fun heightAt(x: Int, y: Int): Float = column(x, y, columnScratch.get()).height

    override fun provinceAt(x: Int, y: Int): Province = provinces[column(x, y, columnScratch.get()).province]

    companion object {
        /** Microvoxels across a region: about 500 blocks, a morning's walk. */
        const val CELL = 2000f
        /** How far borders wander from the straight lines between region hearts. */
        const val BORDER_WARP = 700f
        /** How far either side of a border two provinces' landforms blend. */
        const val BLEND = 220f
        /** How much chance mixes into choosing a region's province, against pure fit. */
        const val JITTER = 0.35f
        private const val SIZE = 4096

        /** Every province, resolved against [palette]. */
        fun resolve(provinces: List<Province>, palette: MaterialPalette) = provinces.map { ResolvedProvince(it, palette) }
    }
}
