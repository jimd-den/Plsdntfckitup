package com.stratum.engine.model.mask

import com.stratum.engine.scene.GlowChannel
import com.stratum.engine.scene.SpiritMesh
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a [MaskGenome] into a smooth, glowing [SpiritMesh]: the mask as a
 * floating object rather than a stack of voxels.
 *
 * ## How it is built
 *
 * The mask's design is already a set of smooth analytic shapes
 * ([IgboMaskGenerator.Design]): for any point of the front view it says how
 * far the surface stands out, which colour it is, and whether it glows. The
 * voxel generator samples that once per column; here it becomes a solid in
 * three steps.
 *
 * 1. **A signed distance field.** On a regular grid, each column is sampled
 *    at a few sub-points: the share of them inside the silhouette gives the
 *    outline to sub-cell accuracy, and their mean height gives the relief.
 *    The solid is the shell between a back surface and the relief standing
 *    on it, bent round like a real mask worn on a face (the sides sweep
 *    back, the crest leans back a little), so it reads as an object from
 *    every side and not as a cut-out.
 * 2. **Surface nets.** One vertex per grid cell the surface passes through,
 *    at the average of where the surface crosses the cell's edges, and one
 *    quad per crossed grid edge. Watertight by construction, no ambiguous
 *    cases, and a smoother result than marching cubes at the same size.
 *    Normals come from the field's gradient, so the shading is smooth across
 *    the facets.
 * 3. **Crisp colour.** The flat designer colour fields are not painted per
 *    facet (which would stair-step at the grid) nor blended per vertex (which
 *    would blur). A triangle whose corners fall in different fields is cut
 *    along the boundary, found by bisection against the analytic design, so
 *    an eye or a lip keeps a clean curved edge at any size. Vertices are
 *    shared inside a field and split across a boundary.
 *
 * ## The budget
 *
 * The grid's pitch is chosen from the silhouette's area to land the mesh in
 * [budget] triangles (about 1.5k to 4k for a phone), then checked and
 * coarsened if the cutting pushed it over: resolution is the decimation, so
 * the triangles are spent evenly and the outline never collapses.
 *
 * Pure and deterministic, a few tens of milliseconds a mask; [cached] keeps
 * one mesh per genome and budget for the life of the process.
 */
object MaskSpiritMesher {

    /** Triangles for the hero's companion: close to the camera and the centre of attention. */
    const val COMPANION_BUDGET = 3600

    /** Triangles for a monster's mask: smaller on screen, and there may be several. */
    const val MONSTER_BUDGET = 1600

    private val cache = object : LinkedHashMap<String, SpiritMesh>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpiritMesh>?): Boolean = size > CACHE_SIZE
    }

    /** The mesh for [genome], built once per genome and budget and then shared. */
    fun cached(genome: MaskGenome, budget: Int = COMPANION_BUDGET): SpiritMesh {
        val key = MaskCodec.encode(genome.normalised()) + "@" + budget
        synchronized(cache) { cache[key]?.let { return it } }
        val mesh = build(genome, budget)
        synchronized(cache) { cache[key] = mesh }
        return mesh
    }

    /** Builds the mesh, landing it at or under [budget] triangles. */
    fun build(genome: MaskGenome, budget: Int = COMPANION_BUDGET): SpiritMesh {
        val g = genome.normalised()
        val palette = MaskPalettes[g.palette]
        val bounds = bounds(g, palette)
        val area = bounds.area
        // Front and back are one quad per column each, the walls a share on
        // top, and the colour cuts add some: aim the grid at about 60% of the
        // budget and let the check below catch the rest.
        var cell = sqrt(4.4f * area / (budget * 0.6f)).coerceIn(0.03f, 0.3f)
        var best: SpiritMesh? = null
        repeat(MAX_TRIES) {
            val mesh = Build(g, palette, bounds, cell).run()
            if (mesh.triangleCount <= budget) return mesh
            best = mesh
            cell *= sqrt(mesh.triangleCount / budget.toFloat()) * 1.04f
        }
        return best!!
    }

    /** The silhouette's extent and area in face units, found on a probe grid. */
    internal class Bounds(val uMax: Float, val vMin: Float, val vMax: Float, val area: Float)

    private fun bounds(g: MaskGenome, p: MaskPalette): Bounds {
        val probe = IgboMaskGenerator.Design(g, p, pixel = 0.06f, floating = true)
        val c = IgboMaskGenerator.Cell()
        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE; var uMax = 0f
        var covered = 0
        val step = 0.03f
        var v = -3f
        while (v <= 5f) {
            var u = -4f
            while (u <= 4f) {
                probe.sample(u, v, c)
                if (c.h > 0f) {
                    covered++
                    vMin = min(vMin, v); vMax = max(vMax, v); uMax = max(uMax, abs(u))
                }
                u += step
            }
            v += step
        }
        return Bounds(uMax + step, vMin - step, vMax + step, covered * step * step)
    }

    /** One attempt at one grid pitch. */
    private class Build(val g: MaskGenome, val p: MaskPalette, val b: Bounds, val cell: Float) {
        /** Lines drawn at least this wide, so the grid can hold them: bolder, cleaner, more Deco. */
        val design = IgboMaskGenerator.Design(g, p, pixel = cell * 1.25f, floating = true)
        val sample = IgboMaskGenerator.Cell()

        // The grid: symmetric about u = 0 so a symmetric mask meshes symmetric.
        val half = ceil(b.uMax / cell).toInt() + 2
        val nu = half * 2 + 1
        val v0 = b.vMin - 2 * cell
        val nv = ceil((b.vMax - b.vMin) / cell).toInt() + 5
        val cw = cell * 0.7f
        val coverage = FloatArray(nu * nv)
        val relief = FloatArray(nu * nv)
        var w0 = 0f
        var nw = 0
        lateinit var field: FloatArray

        fun u(i: Int) = (i - half) * cell
        fun v(j: Int) = v0 + j * cell
        fun w(k: Int) = w0 + k * cw

        /** The mask's curve: the sides sweep back round the head, the crest leans back. */
        fun bend(u: Float, v: Float): Float = -(BEND_ACROSS * u * u + BEND_DOWN * (v - 0.1f) * (v - 0.1f))

        fun run(): SpiritMesh {
            sampleColumns()
            buildField()
            return mesh()
        }

        private fun sampleColumns() {
            val sub = SUBSAMPLES
            for (j in 0 until nv) for (i in 0 until nu) {
                var inside = 0; var sum = 0f
                for (sj in 0 until sub) for (si in 0 until sub) {
                    val su = u(i) + ((si + 0.5f) / sub - 0.5f) * cell
                    val sv = v(j) + ((sj + 0.5f) / sub - 0.5f) * cell
                    design.sample(su, sv, sample)
                    if (sample.h > 0f) { inside++; sum += sample.h }
                }
                coverage[j * nu + i] = inside / (sub * sub).toFloat()
                relief[j * nu + i] = if (inside > 0) sum / inside else 0f
            }
        }

        private fun buildField() {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (j in 0 until nv) for (i in 0 until nu) {
                if (coverage[j * nu + i] <= 0f) continue
                val c = bend(u(i), v(j))
                lo = min(lo, c - THICKNESS); hi = max(hi, c + relief[j * nu + i])
            }
            w0 = lo - 2 * cw
            nw = ceil((hi - lo) / cw).toInt() + 5
            field = FloatArray(nu * nv * nw)
            for (j in 0 until nv) for (i in 0 until nu) {
                val col = j * nu + i
                // Signed distance to the outline, from the covered share: exact
                // enough near the edge, which is the only place it matters.
                val outline = (0.5f - coverage[col]) * cell
                val c = bend(u(i), v(j))
                val front = c + relief[col]
                val back = c - THICKNESS
                for (k in 0 until nw) {
                    val ww = w(k)
                    field[(k * nv + j) * nu + i] = max(outline, max(ww - front, back - ww))
                }
            }
        }

        fun f(i: Int, j: Int, k: Int) = field[(k * nv + j) * nu + i]

        /** The field between grid nodes, trilinearly; outside the grid, empty space. */
        fun at(uu: Float, vv: Float, ww: Float): Float {
            val fi = uu / cell + half; val fj = (vv - v0) / cell; val fk = (ww - w0) / cw
            val i = fi.toInt().coerceIn(0, nu - 2); val j = fj.toInt().coerceIn(0, nv - 2); val k = fk.toInt().coerceIn(0, nw - 2)
            val tx = (fi - i).coerceIn(0f, 1f); val ty = (fj - j).coerceIn(0f, 1f); val tz = (fk - k).coerceIn(0f, 1f)
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            val c00 = lerp(f(i, j, k), f(i + 1, j, k), tx); val c10 = lerp(f(i, j + 1, k), f(i + 1, j + 1, k), tx)
            val c01 = lerp(f(i, j, k + 1), f(i + 1, j, k + 1), tx); val c11 = lerp(f(i, j + 1, k + 1), f(i + 1, j + 1, k + 1), tx)
            return lerp(lerp(c00, c10, ty), lerp(c01, c11, ty), tz)
        }

        private fun mesh(): SpiritMesh {
            // ---- Surface nets: a vertex per crossed cell ------------------
            val cellVertex = IntArray((nu - 1) * (nv - 1) * (nw - 1)) { -1 }
            val pos = FloatList()
            for (k in 0 until nw - 1) for (j in 0 until nv - 1) for (i in 0 until nu - 1) {
                var su = 0f; var sv = 0f; var sw = 0f; var n = 0
                for (e in EDGES.indices step 2) {
                    val a = EDGES[e]; val bb = EDGES[e + 1]
                    val ai = i + (a and 1); val aj = j + ((a shr 1) and 1); val ak = k + ((a shr 2) and 1)
                    val bi = i + (bb and 1); val bj = j + ((bb shr 1) and 1); val bk = k + ((bb shr 2) and 1)
                    val fa = f(ai, aj, ak); val fb = f(bi, bj, bk)
                    if ((fa < 0f) == (fb < 0f)) continue
                    val t = (fa / (fa - fb)).coerceIn(0f, 1f)
                    su += u(ai) + (u(bi) - u(ai)) * t
                    sv += v(aj) + (v(bj) - v(aj)) * t
                    sw += w(ak) + (w(bk) - w(ak)) * t
                    n++
                }
                if (n == 0) continue
                cellVertex[(k * (nv - 1) + j) * (nu - 1) + i] = pos.size / 3
                pos.add(su / n); pos.add(sv / n); pos.add(sw / n)
            }
            fun cv(i: Int, j: Int, k: Int) = cellVertex[(k * (nv - 1) + j) * (nu - 1) + i]

            // ---- A quad per crossed edge ------------------------------------
            val quads = IntList()
            for (k in 1 until nw - 1) for (j in 1 until nv - 1) for (i in 1 until nu - 1) {
                val inside = f(i, j, k) < 0f
                // Along u: the four cells round the edge (i,j,k)-(i+1,j,k).
                if (i < nu - 1 && inside != (f(i + 1, j, k) < 0f)) {
                    quad(quads, cv(i, j - 1, k - 1), cv(i, j, k - 1), cv(i, j, k), cv(i, j - 1, k), inside)
                }
                if (j < nv - 1 && inside != (f(i, j + 1, k) < 0f)) {
                    quad(quads, cv(i - 1, j, k - 1), cv(i - 1, j, k), cv(i, j, k), cv(i, j, k - 1), inside)
                }
                if (k < nw - 1 && inside != (f(i, j, k + 1) < 0f)) {
                    quad(quads, cv(i - 1, j - 1, k), cv(i, j - 1, k), cv(i, j, k), cv(i - 1, j, k), inside)
                }
            }

            smooth(pos, quads)

            // ---- Normals from the gradient ---------------------------------
            val nrm = FloatArray(pos.size)
            val h = cell * 0.5f
            for (vi in 0 until pos.size / 3) {
                val uu = pos[vi * 3]; val vv = pos[vi * 3 + 1]; val ww = pos[vi * 3 + 2]
                var gx = at(uu + h, vv, ww) - at(uu - h, vv, ww)
                var gy = at(uu, vv + h, ww) - at(uu, vv - h, ww)
                var gz = at(uu, vv, ww + h) - at(uu, vv, ww - h)
                val l = sqrt(gx * gx + gy * gy + gz * gz)
                if (l < 1e-6f) { gx = 0f; gy = 0f; gz = 1f } else { gx /= l; gy /= l; gz /= l }
                nrm[vi * 3] = gx; nrm[vi * 3 + 1] = gy; nrm[vi * 3 + 2] = gz
            }

            return Colourist(this, pos, nrm).paint(quads)
        }

        /**
         * Taubin smoothing: a shrink step and an inflate step, twice. Surface
         * nets leave a faint ripple where the grid crosses a curve at a
         * shallow angle; this irons it out without shrinking the mask or
         * rounding away its silhouette the way plain averaging would.
         */
        private fun smooth(pos: FloatList, quads: IntList) {
            val n = pos.size / 3
            val degree = IntArray(n + 1)
            var q = 0
            while (q < quads.size) { for (c in 0 until 4) degree[quads[q + c]] += 2; q += 4 }
            val start = IntArray(n + 1)
            for (i in 0 until n) start[i + 1] = start[i] + degree[i]
            val fill = start.copyOf()
            val nb = IntArray(start[n])
            q = 0
            while (q < quads.size) {
                for (c in 0 until 4) {
                    val a = quads[q + c]
                    nb[fill[a]++] = quads[q + (c + 1) % 4]
                    nb[fill[a]++] = quads[q + (c + 3) % 4]
                }
                q += 4
            }
            val next = FloatArray(pos.size)
            repeat(SMOOTH_PASSES * 2) { pass ->
                val factor = if (pass % 2 == 0) TAUBIN_SHRINK else TAUBIN_INFLATE
                for (i in 0 until n) {
                    val count = start[i + 1] - start[i]
                    for (d in 0 until 3) {
                        if (count == 0) { next[i * 3 + d] = pos[i * 3 + d]; continue }
                        var sum = 0f
                        for (e in start[i] until start[i + 1]) sum += pos[nb[e] * 3 + d]
                        next[i * 3 + d] = pos[i * 3 + d] + factor * (sum / count - pos[i * 3 + d])
                    }
                }
                next.copyInto(pos.data, 0, 0, pos.size)
            }
        }

        /** Two triangles for a crossed edge, wound so they face out of the solid. */
        private fun quad(out: IntList, a: Int, b: Int, c: Int, d: Int, inside: Boolean) {
            if (a < 0 || b < 0 || c < 0 || d < 0) return
            if (inside) { out.add(a); out.add(b); out.add(c); out.add(d) } else { out.add(d); out.add(c); out.add(b); out.add(a) }
        }

        /** Region key of a front-view point: slot and glow together, so a glowing line has a crisp edge too. */
        fun regionAt(uu: Float, vv: Float): Int {
            design.sample(uu, vv, sample)
            if (sample.h > 0f) return sample.slot * 4 + sample.glow
            // Just outside the outline (a wall's corner): the nearest field inside.
            for (r in 1..3) for (dir in 0 until 8) {
                val a = dir * (Math.PI / 4).toFloat()
                design.sample(uu + kotlin.math.cos(a) * r * cell * 0.4f, vv + kotlin.math.sin(a) * r * cell * 0.4f, sample)
                if (sample.h > 0f) return sample.slot * 4 + sample.glow
            }
            return FACE_SLOT * 4
        }
    }

    /**
     * Colours the net: splits triangles along colour boundaries, shares
     * vertices within a field, and lays the result out in model space.
     */
    private class Colourist(val b: Build, val pos: FloatList, val nrm: FloatArray) {
        val outPos = FloatList(); val outNrm = FloatList(); val outRegion = IntList(); val tris = IntList()
        private val shared = HashMap<Long, Int>()
        private val edgeCuts = HashMap<Long, Int>()
        // Scratch for a cut point.
        private val cut = FloatArray(6)

        fun paint(quads: IntList): SpiritMesh {
            var q = 0
            while (q < quads.size) {
                val a = quads[q]; val bb = quads[q + 1]; val c = quads[q + 2]; val d = quads[q + 3]
                // Split the quad along its shorter diagonal: fewer slivers.
                if (dist2(a, c) <= dist2(bb, d)) { triangle(a, bb, c); triangle(a, c, d) } else { triangle(a, bb, d); triangle(bb, c, d) }
                q += 4
            }
            return layout()
        }

        private fun dist2(a: Int, c: Int): Float {
            val dx = pos[a * 3] - pos[c * 3]; val dy = pos[a * 3 + 1] - pos[c * 3 + 1]; val dz = pos[a * 3 + 2] - pos[c * 3 + 2]
            return dx * dx + dy * dy + dz * dz
        }

        /** Front (faces the viewer), back, or wall, from the triangle's own facing. */
        private fun facing(a: Int, bb: Int, c: Int): Int {
            val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
            val e1x = pos[bb * 3] - ax; val e1y = pos[bb * 3 + 1] - ay; val e1z = pos[bb * 3 + 2] - az
            val e2x = pos[c * 3] - ax; val e2y = pos[c * 3 + 1] - ay; val e2z = pos[c * 3 + 2] - az
            val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            val facingW = nz / l
            return when {
                facingW > 0.3f -> FRONT
                facingW < -0.3f -> BACK
                else -> WALL
            }
        }

        private fun triangle(a: Int, bb: Int, c: Int) {
            when (facing(a, bb, c)) {
                BACK -> emit(a, bb, c, BACK_REGION)
                WALL -> {
                    val cu = (pos[a * 3] + pos[bb * 3] + pos[c * 3]) / 3f
                    val cvv = (pos[a * 3 + 1] + pos[bb * 3 + 1] + pos[c * 3 + 1]) / 3f
                    // Walls carry their field's colour but never glow: light
                    // belongs to the drawn face, not to its thickness.
                    emit(a, bb, c, (b.regionAt(cu, cvv) and 3.inv()))
                }
                else -> front(a, bb, c)
            }
        }

        private fun front(a: Int, bb: Int, c: Int) {
            val ra = b.regionAt(pos[a * 3], pos[a * 3 + 1])
            val rb = b.regionAt(pos[bb * 3], pos[bb * 3 + 1])
            val rc = b.regionAt(pos[c * 3], pos[c * 3 + 1])
            if (ra == rb && rb == rc) { emit(a, bb, c, ra); return }
            if (rb == rc) { split(a, bb, c, ra, rb); return }
            if (ra == rc) { split(bb, c, a, rb, ra); return }
            if (ra == rb) { split(c, a, bb, rc, ra); return }
            // Three fields meet: each corner keeps its own share, cut at the
            // edges' boundaries and the centre.
            val ab = cutOn(a, bb, ra); val bc = cutOn(bb, c, rb); val ca = cutOn(c, a, rc)
            val g = centre(a, bb, c)
            emitRaw(vert(a, ra), vertCut(ab, ra), vertCut(g, ra)); emitRaw(vert(a, ra), vertCut(g, ra), vertCut(ca, ra))
            emitRaw(vert(bb, rb), vertCut(bc, rb), vertCut(g, rb)); emitRaw(vert(bb, rb), vertCut(g, rb), vertCut(ab, rb))
            emitRaw(vert(c, rc), vertCut(ca, rc), vertCut(g, rc)); emitRaw(vert(c, rc), vertCut(g, rc), vertCut(bc, rc))
        }

        /** Corner [a] alone in field [ra]; [bb] and [c] share [rb]. */
        private fun split(a: Int, bb: Int, c: Int, ra: Int, rb: Int) {
            val ab = cutOn(a, bb, ra); val ac = cutOn(a, c, ra)
            emitRaw(vert(a, ra), vertCut(ab, ra), vertCut(ac, ra))
            emitRaw(vertCut(ab, rb), vert(bb, rb), vert(c, rb))
            emitRaw(vertCut(ab, rb), vert(c, rb), vertCut(ac, rb))
        }

        // ---- Cut points: a raw point index into cutPos, shared per edge ----
        private val cutPos = FloatList(); private val cutNrm = FloatList()

        /** Where the field [from] (the colour at [a]) ends along a..b, by bisection against the design. */
        private fun cutOn(a: Int, bb: Int, from: Int): Int {
            val lo = min(a, bb); val hi = max(a, bb)
            val key = lo.toLong() * 0x100000L + hi
            edgeCuts[key]?.let { return it }
            // Always bisect from the lower index, so both triangles on this edge find the same point.
            val start = if (lo == a) from else b.regionAt(pos[lo * 3], pos[lo * 3 + 1])
            var t0 = 0f; var t1 = 1f
            repeat(BISECTIONS) {
                val t = (t0 + t1) * 0.5f
                val uu = pos[lo * 3] + (pos[hi * 3] - pos[lo * 3]) * t
                val vv = pos[lo * 3 + 1] + (pos[hi * 3 + 1] - pos[lo * 3 + 1]) * t
                if (b.regionAt(uu, vv) == start) t0 = t else t1 = t
            }
            val t = (t0 + t1) * 0.5f
            val index = cutPos.size / 3
            for (d in 0 until 3) {
                cutPos.add(pos[lo * 3 + d] + (pos[hi * 3 + d] - pos[lo * 3 + d]) * t)
                cutNrm.add(nrm[lo * 3 + d] + (nrm[hi * 3 + d] - nrm[lo * 3 + d]) * t)
            }
            edgeCuts[key] = index
            return index
        }

        private fun centre(a: Int, bb: Int, c: Int): Int {
            val index = cutPos.size / 3
            for (d in 0 until 3) {
                cutPos.add((pos[a * 3 + d] + pos[bb * 3 + d] + pos[c * 3 + d]) / 3f)
                cutNrm.add((nrm[a * 3 + d] + nrm[bb * 3 + d] + nrm[c * 3 + d]) / 3f)
            }
            return index
        }

        /** An output vertex for net vertex [i] in [region], shared within the region. */
        private fun vert(i: Int, region: Int): Int {
            val key = (i.toLong() shl 20) or region.toLong()
            return shared.getOrPut(key) { add(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2], region) }
        }

        private fun vertCut(i: Int, region: Int): Int {
            val key = (1L shl 62) or (i.toLong() shl 20) or region.toLong()
            return shared.getOrPut(key) {
                add(cutPos[i * 3], cutPos[i * 3 + 1], cutPos[i * 3 + 2], cutNrm[i * 3], cutNrm[i * 3 + 1], cutNrm[i * 3 + 2], region)
            }
        }

        private fun add(uu: Float, vv: Float, ww: Float, nu: Float, nv: Float, nw: Float, region: Int): Int {
            val index = outPos.size / 3
            outPos.add(uu); outPos.add(vv); outPos.add(ww)
            val l = sqrt(nu * nu + nv * nv + nw * nw).coerceAtLeast(1e-6f)
            outNrm.add(nu / l); outNrm.add(nv / l); outNrm.add(nw / l)
            outRegion.add(region)
            return index
        }

        private fun emit(a: Int, bb: Int, c: Int, region: Int) = emitRaw(vert(a, region), vert(bb, region), vert(c, region))

        private fun emitRaw(a: Int, bb: Int, c: Int) {
            if (a == bb || bb == c || a == c) return
            tris.add(a); tris.add(bb); tris.add(c)
        }

        /** Face units to model space: x = -u (so the design's right is the viewer's right), y = w forward, z = v up, height 1. */
        private fun layout(): SpiritMesh {
            val n = outPos.size / 3
            val height = b.b.vMax - b.b.vMin
            val scale = 1f / height
            val midV = (b.b.vMin + b.b.vMax) / 2f
            val positions = FloatArray(n * 3); val normals = FloatArray(n * 3)
            val colours = IntArray(n); val channels = ByteArray(n); val glows = IntArray(n)
            val look = SpiritLook.of(b.p)
            val eyeSum = DoubleArray(8)
            for (i in 0 until n) {
                val uu = outPos[i * 3]; val vv = outPos[i * 3 + 1]; val ww = outPos[i * 3 + 2]
                positions[i * 3] = -uu * scale
                positions[i * 3 + 1] = ww * scale
                positions[i * 3 + 2] = (vv - midV) * scale
                normals[i * 3] = -outNrm[i * 3]
                normals[i * 3 + 1] = outNrm[i * 3 + 2]
                normals[i * 3 + 2] = outNrm[i * 3 + 1]
                val region = outRegion[i]
                val slot = region shr 2
                val glow = region and 3
                colours[i] = look.colour(slot)
                channels[i] = glow.toByte()
                glows[i] = look.glow(glow)
                if (glow == GlowChannel.EYES.toInt()) {
                    val side = if (positions[i * 3] < 0f) 0 else 4
                    eyeSum[side] += positions[i * 3].toDouble(); eyeSum[side + 1] += positions[i * 3 + 1].toDouble()
                    eyeSum[side + 2] += positions[i * 3 + 2].toDouble(); eyeSum[side + 3] += 1.0
                }
            }
            // The mirror flipped handedness: turn every triangle so its winding
            // agrees with its normals (outward, counter-clockwise).
            val idx = tris.toArray()
            for (t in idx.indices step 3) {
                val a = idx[t]; val bb = idx[t + 1]; val c = idx[t + 2]
                val e1x = positions[bb * 3] - positions[a * 3]; val e1y = positions[bb * 3 + 1] - positions[a * 3 + 1]; val e1z = positions[bb * 3 + 2] - positions[a * 3 + 2]
                val e2x = positions[c * 3] - positions[a * 3]; val e2y = positions[c * 3 + 1] - positions[a * 3 + 1]; val e2z = positions[c * 3 + 2] - positions[a * 3 + 2]
                val fx = e1y * e2z - e1z * e2y; val fy = e1z * e2x - e1x * e2z; val fz = e1x * e2y - e1y * e2x
                val nx = normals[a * 3] + normals[bb * 3] + normals[c * 3]
                val ny = normals[a * 3 + 1] + normals[bb * 3 + 1] + normals[c * 3 + 1]
                val nz = normals[a * 3 + 2] + normals[bb * 3 + 2] + normals[c * 3 + 2]
                if (fx * nx + fy * ny + fz * nz < 0f) { idx[t + 1] = c; idx[t + 2] = bb }
            }
            val eyes = FloatArray(6)
            for (side in 0 until 2) {
                val o = side * 4
                if (eyeSum[o + 3] > 0) {
                    for (d in 0 until 3) eyes[side * 3 + d] = (eyeSum[o + d] / eyeSum[o + 3]).toFloat()
                } else {
                    // No lit eye (a closed crescent drawn too fine): where eyes sit on a face.
                    eyes[side * 3] = if (side == 0) -0.12f else 0.12f
                    eyes[side * 3 + 1] = 0.1f
                    eyes[side * 3 + 2] = (0.1f - midV) * scale
                }
            }
            return SpiritMesh(
                positions, normals, colours, channels, glows, idx, eyes, look.aura, look.auraSecond,
                faceColor = SpiritLook.argb(b.p.face), fringeColors = look.fringe, charmColor = SpiritLook.argb(b.p.ivory),
            )
        }
    }

    /**
     * How a palette glows: the flat colours for each slot, and which of the
     * scheme's colours burns bright enough to be its light. A dark accent
     * (Okoroshi's ink) cannot glow, so the light falls to the next jewel in
     * the scheme, and failing all of them to a warm brass.
     */
    internal class SpiritLook(private val p: MaskPalette) {
        private val slots = intArrayOf(argb(p.face), argb(p.face), argb(p.ink), argb(p.crest), argb(p.second), argb(p.accent), argb(p.ivory), argb(p.back))

        /** The light colour: the first saturated, mid-to-bright jewel in the scheme. */
        val aura: Int = listOf(p.accent, p.second, p.crest, p.face, p.ivory).map(::argb).firstOrNull { glowable(it) } ?: BRASS
        val auraSecond: Int = listOf(p.second, p.crest, p.accent, p.face).map(::argb).firstOrNull { glowable(it) && it != aura } ?: lighten(aura, 0.45f)
        private val eyes = lighten(aura, 0.2f)
        private val lines = aura
        private val crest = if (glowable(argb(p.accent))) argb(p.accent) else lighten(aura, 0.1f)

        /**
         * The raffia, in bands of the scheme's two strongest masses: the
         * crest colour and the second field, like the striped costume under a
         * real masquerade -- and a Deco awning.
         */
        val fringe: IntArray = listOf(p.crest, p.second, p.crest, p.accent).map(::argb).distinct().let { d ->
            if (d.size == 1) intArrayOf(d[0], lighten(d[0], 0.5f)) else intArrayOf(argb(p.crest), argb(if (p.second != p.crest) p.second else p.accent))
        }

        /** The back of the mask: its crest colour, a shade deeper, like lacquer. */
        private val back = darken(argb(p.crest), 0.2f)

        fun colour(slot: Int): Int = if (slot == BACK_SLOT_MARK) back else slots[slot.coerceIn(0, slots.size - 1)]

        fun glow(channel: Int): Int = when (channel) {
            GlowChannel.EYES.toInt() -> eyes
            GlowChannel.LINES.toInt() -> lines
            GlowChannel.CREST.toInt() -> crest
            else -> aura
        }

        companion object {
            fun of(p: MaskPalette) = SpiritLook(p)
            private const val BRASS = 0xFFE9B949.toInt()

            fun argb(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)

            private fun glowable(c: Int): Boolean {
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl))
                val lum = (0.299f * r + 0.587f * g + 0.114f * bl) / 255f
                val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
                return lum in 0.3f..0.93f && sat > 0.3f
            }

            fun lighten(c: Int, t: Float): Int = mix(c, 0xFFFFF8EC.toInt(), t)
            fun darken(c: Int, t: Float): Int = mix(c, 0xFF000000.toInt(), t)

            private fun mix(a: Int, b: Int, t: Float): Int {
                fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).toInt().coerceIn(0, 255)
                return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }

    /** A growable float list without boxing. */
    internal class FloatList {
        var data = FloatArray(1024); var size = 0
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
    }

    internal class IntList {
        var data = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        operator fun get(i: Int) = data[i]
        fun toArray() = data.copyOf(size)
    }

    /** The twelve edges of a cell as corner pairs; corner bits are u, v, w. */
    private val EDGES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)

    private const val FRONT = 0
    private const val BACK = 1
    private const val WALL = 2
    private const val FACE_SLOT = 1
    /** A slot number past the palette's, for the lacquered back. */
    private const val BACK_SLOT_MARK = 15
    private const val BACK_REGION = BACK_SLOT_MARK * 4
    private const val SUBSAMPLES = 4
    private const val SMOOTH_PASSES = 2
    private const val TAUBIN_SHRINK = 0.5f
    private const val TAUBIN_INFLATE = -0.53f
    private const val BISECTIONS = 6
    private const val MAX_TRIES = 4
    private const val CACHE_SIZE = 24

    /** Thickness of the shell behind the relief, in face units. */
    private const val THICKNESS = 0.1f
    private const val BEND_ACROSS = 0.22f
    private const val BEND_DOWN = 0.05f
}
