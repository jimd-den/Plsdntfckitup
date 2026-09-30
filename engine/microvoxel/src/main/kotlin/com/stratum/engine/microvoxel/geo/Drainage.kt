package com.stratum.engine.microvoxel.geo

import com.stratum.engine.microvoxel.gen.Hash
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the water goes, simulated.
 *
 * The land is sampled on a coarse lattice ([DrainTile.SPACING] microvoxels,
 * four blocks) in square tiles, each [DrainTile.CORE] nodes a side plus an
 * [DrainTile.APRON] of
 * neighbouring land so that water arriving from just outside is counted.
 * Within a tile, the drainage is solved exactly as a hydrologist would on a
 * digital elevation model, in four passes over flat primitive arrays:
 *
 * 1. **Priority-flood** (Barnes, Lehman and Mulla, 2014) from the tile's rim
 *    and the sea inwards: every node learns where its water leaves it, pits
 *    are filled so no water is trapped, and the order nodes were reached in
 *    is the drainage order.
 * 2. **Flow accumulation**: in reverse order each node passes the rain it
 *    has gathered to its receiver, so every node knows its catchment.
 * 3. **Stream-power erosion and deposition** in the same order: water cuts
 *    in proportion to the square root of its discharge times the slope, so
 *    gullies deepen into valleys downstream; the sediment it carries is
 *    dropped where the slope eases below what the flow can carry -- an
 *    alluvial fan at a mountain's foot, a spread of silt on a plain.
 * 4. **Rivers**: nodes whose catchment passes a threshold become channel
 *    segments, with a water level that only ever falls downstream.
 *
 * Everything is a pure function of the tile's coordinates, so a tile comes
 * out identical on any thread, in any order. Neighbouring tiles overlap by
 * their aprons; their erosion is cross-faded over [DrainTile.RAMP] nodes
 * either side of the seam ([DrainTile.weight]) so the land is continuous,
 * and each river segment belongs to exactly one tile, the one its upstream
 * node lies in.
 *
 * Cost: ~12,500 nodes and a handful of linear passes per tile, which covers
 * 16 x 16 chunk columns -- a few hundredths of a millisecond per column once
 * a view is streaming, and nothing but array reads after that.
 */
internal class DrainTile(val tx: Int, val ty: Int) {
    /** Erosion (negative) and deposition (positive) per domain node, microvoxels. */
    val delta = FloatArray(D * D)
    /** Sediment laid per domain node: the fan and floodplain marks. */
    val laid = FloatArray(D * D)
    /** River segments this tile owns, [SEG] floats each: x0, y0, x1, y1, level0, level1, halfWidth, depth, dry. */
    var segs = FloatArray(0)
    var segCount = 0
    /** Per core node, the index of the segment leaving it, or -1. */
    val segAt = IntArray(CORE * CORE) { -1 }

    /** Global lattice index of the domain's first node. */
    val gx0 = tx * CORE - APRON
    val gy0 = ty * CORE - APRON

    companion object {
        /** Microvoxels between lattice nodes: eight blocks. */
        const val SPACING = 32
        /** Nodes a side a tile owns: 1536 microvoxels, 24 chunks. */
        const val CORE = 48
        /** Nodes of neighbouring land simulated around the core: 96 blocks of catchment beyond the seam. */
        const val APRON = 12
        /** Nodes either side of a seam over which neighbouring tiles' erosion is cross-faded. */
        const val RAMP = 6
        const val D = CORE + 2 * APRON
        const val SEG = 9

        /**
         * The share of tile [t]'s erosion at global lattice position [p]
         * along one axis: 1 well inside its core, fading linearly to 0 over
         * [RAMP] nodes either side of the core's edges, and summing to 1
         * with the neighbour's share across every seam.
         */
        fun weight(p: Float, t: Int): Float {
            val q = p - t * CORE
            val w = min(0.5f + q / (2f * RAMP), 0.5f + (CORE - q) / (2f * RAMP))
            return w.coerceIn(0f, 1f)
        }
    }
}

/** Builds [DrainTile]s. Stateless apart from its inputs; see [DrainTile] for the method. */
internal class Drainage(
    private val seed: Long,
    private val sea: Int,
    private val resolved: List<ResolvedProvince>,
    private val dials: GeoDials,
    /** Raw ground for a whole block of lattice nodes: fills heights and provinces, row-major. */
    private val sample: (gx0: Int, gy0: Int, n: Int, spacing: Int, heights: FloatArray, provinces: IntArray) -> Unit,
) {
    private val d = DrainTile.D
    private val n = d * d

    // Neighbour offsets and distances, eight-connected.
    private val ox = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val oy = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private val dist = floatArrayOf(1f, 1f, 1f, 1f, 1.4142f, 1.4142f, 1.4142f, 1.4142f)

    fun build(tx: Int, ty: Int): DrainTile {
        val t = DrainTile(tx, ty)
        val h0 = FloatArray(n); val prov = IntArray(n)
        sample(t.gx0, t.gy0, d, DrainTile.SPACING, h0, prov)

        // 1. Priority-flood: rec is where each node's water goes (-1 at an outlet), order the flood order.
        val filled = FloatArray(n); val rec = IntArray(n) { -1 }; val order = IntArray(n)
        val queued = BooleanArray(n)
        val heap = MinHeap(n, filled)
        for (j in 0 until d) for (i in 0 until d) {
            val k = j * d + i
            if (i == 0 || j == 0 || i == d - 1 || j == d - 1 || h0[k] < sea - 1) {
                filled[k] = h0[k]; queued[k] = true; heap.push(k)
            }
        }
        var count = 0
        while (heap.size > 0) {
            val k = heap.pop()
            order[count++] = k
            val i = k % d; val j = k / d
            for (e in 0 until 8) {
                val a = i + ox[e]; val b = j + oy[e]
                if (a < 0 || b < 0 || a >= d || b >= d) continue
                val m = b * d + a
                if (queued[m]) continue
                queued[m] = true
                filled[m] = max(h0[m], filled[k] + EPSILON * dist[e])
                rec[m] = k
                heap.push(m)
            }
        }

        // 2. Flow accumulation: rain gathered from the node and everything upstream.
        val acc = FloatArray(n)
        for (k in 0 until n) acc[k] = resolved[prov[k]].rain + 0.15f
        for (c in n - 1 downTo 0) {
            val k = order[c]; val r = rec[k]
            if (r >= 0) acc[r] += acc[k]
        }

        // 3. Erosion and deposition, sources first, carrying sediment down the tree.
        val cut = FloatArray(n); val sediment = FloatArray(n)
        val kErode = K_ERODE * dials.erosion
        for (c in n - 1 downTo 0) {
            val k = order[c]; val r = rec[k]
            if (r < 0) continue
            val p = resolved[prov[k]]
            val step = kotlin.math.abs(k - r)
            val run = if (step == 1 || step == d) 1f else 1.4142f
            val slope = max(0f, h0[k] - h0[r]) / (run * DrainTile.SPACING)
            val flow = max(0f, sqrt(acc[k]) - FLOW_FLOOR)
            var e = kErode * p.erosion * flow * min(slope, 1.2f)
            e = min(e, MAX_CUT * p.erosion).coerceAtMost(max(0f, h0[k] - (sea - 2)))
            cut[k] = e
            var load = sediment[k] + e
            val capacity = K_CARRY * flow * slope
            if (load > capacity && p.deposit > 0f) {
                val drop = min(MAX_DROP * p.deposit * dials.erosion, (load - capacity) * 0.12f)
                t.laid[k] = drop
                load -= drop * SPREAD
            }
            sediment[r] += max(0f, load)
        }
        // Valleys are wider than the thread of water that cut them: widen each cut into its neighbours.
        widen(cut)
        blur(t.laid)
        for (k in 0 until n) t.delta[k] = t.laid[k] - cut[k]

        // 4. Rivers: segments from every core node whose catchment is big enough, levels falling downstream.
        if (dials.rivers > 0f) rivers(t, h0, prov, rec, order, acc)
        return t
    }

    private fun rivers(t: DrainTile, h0: FloatArray, prov: IntArray, rec: IntArray, order: IntArray, acc: FloatArray) {
        val level = FloatArray(n)
        for (k in 0 until n) level[k] = h0[k] + t.delta[k] - FREEBOARD
        for (c in n - 1 downTo 0) {
            val k = order[c]; val r = rec[k]
            if (r >= 0 && level[r] > level[k] - 0.05f) level[r] = level[k] - 0.05f
        }
        val threshold = RIVER_ACC / dials.rivers
        val segs = ArrayList<Float>()
        val a = DrainTile.APRON; val core = DrainTile.CORE; val s = DrainTile.SPACING.toFloat()
        for (j in a until a + core) for (i in a until a + core) {
            val k = j * d + i
            val r = rec[k]
            if (r < 0 || acc[k] < threshold) continue
            val gi = t.gx0 + i; val gj = t.gy0 + j
            val ri = t.gx0 + r % d; val rj = t.gy0 + r / d
            val grow = ((sqrt(acc[k]) - sqrt(threshold)) / (sqrt(threshold) * 2.5f)).coerceIn(0f, 1f)
            val half = HALF_MIN + (HALF_MAX - HALF_MIN) * grow * grow * (3f - 2f * grow)
            t.segAt[(j - a) * core + (i - a)] = segs.size / DrainTile.SEG
            segs += jitterX(gi, gj) * s; segs += jitterY(gi, gj) * s
            segs += jitterX(ri, rj) * s; segs += jitterY(ri, rj) * s
            segs += level[k]; segs += level[r]
            segs += half; segs += 1.2f + half * 0.35f
            segs += if (resolved[prov[k]].dry) 1f else 0f
        }
        t.segs = segs.toFloatArray()
        t.segCount = segs.size / DrainTile.SEG
    }

    // A river's course bends between nodes: each node's channel point is jittered, the same in every tile.
    private fun jitterX(gi: Int, gj: Int) = gi + (Hash.unit(seed, gi, gj, 0, 1201) - 0.5f) * 0.7f
    private fun jitterY(gi: Int, gj: Int) = gj + (Hash.unit(seed, gi, gj, 0, 1202) - 0.5f) * 0.7f

    /** Each node's cut is at least 60% of its deepest neighbour's: V-shaped valley sides. */
    private fun widen(f: FloatArray) {
        val src = f.copyOf()
        for (j in 1 until d - 1) for (i in 1 until d - 1) {
            val k = j * d + i
            val m = max(max(src[k - 1], src[k + 1]), max(src[k - d], src[k + d]))
            if (m * 0.6f > f[k]) f[k] = m * 0.6f
        }
    }

    /** A 1-2-1 blur: fans spread out from the thread of water that laid them. */
    private fun blur(f: FloatArray) {
        val src = f.copyOf()
        for (j in 1 until d - 1) for (i in 1 until d - 1) {
            val k = j * d + i
            f[k] = (src[k] * 4f + (src[k - 1] + src[k + 1] + src[k - d] + src[k + d]) * 2f +
                src[k - d - 1] + src[k - d + 1] + src[k + d - 1] + src[k + d + 1]) / 16f
        }
    }

    /** A binary min-heap of node indices keyed by [key]; ties go to the node pushed first, so the order is fixed. */
    private class MinHeap(capacity: Int, private val key: FloatArray) {
        private val items = IntArray(capacity)
        private val stamp = IntArray(capacity)
        private var next = 0
        var size = 0
            private set

        private fun less(a: Int, b: Int): Boolean {
            val ka = key[items[a]]; val kb = key[items[b]]
            return ka < kb || (ka == kb && stamp[a] < stamp[b])
        }

        private fun swap(a: Int, b: Int) {
            val t = items[a]; items[a] = items[b]; items[b] = t
            val s = stamp[a]; stamp[a] = stamp[b]; stamp[b] = s
        }

        fun push(v: Int) {
            var i = size++
            items[i] = v; stamp[i] = next++
            while (i > 0) {
                val p = (i - 1) / 2
                if (!less(i, p)) break
                swap(i, p); i = p
            }
        }

        fun pop(): Int {
            val top = items[0]
            size--
            if (size > 0) {
                items[0] = items[size]; stamp[0] = stamp[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1
                    var m = i
                    if (l < size && less(l, m)) m = l
                    if (r < size && less(r, m)) m = r
                    if (m == i) break
                    swap(i, m); i = m
                }
            }
            return top
        }
    }

    companion object {
        /** How much a filled pit's floor is raised per node so water always runs out of it. */
        const val EPSILON = 0.01f
        /** Cutting rate, microvoxels per unit of sqrt-discharge and slope. */
        const val K_ERODE = 2.2f
        /** Discharge below which water only washes the slope instead of cutting it (ridge tops stay sharp). */
        const val FLOW_FLOOR = 1.6f
        /** No valley is cut deeper than this at one node, microvoxels. */
        const val MAX_CUT = 30f
        /** How much sediment a flow can carry per unit of sqrt-discharge and slope. */
        const val K_CARRY = 5f
        /** Most sediment laid at one node, microvoxels. */
        const val MAX_DROP = 5f
        /** Sediment used per microvoxel laid on the channel: the rest of the fan spreads to the sides. */
        const val SPREAD = 8f
        /** Catchment, in nodes of average rain, at which a stream becomes a river. */
        const val RIVER_ACC = 60f
        /** A river's surface below the valley floor the lattice gives. */
        const val FREEBOARD = 1.5f
        const val HALF_MIN = 2f
        const val HALF_MAX = 9f
    }
}
