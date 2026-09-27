package com.stratum.engine.model

import com.stratum.engine.model.gltf.FloatList
import com.stratum.engine.model.gltf.IntList
import kotlin.math.floor

/**
 * Triangles with a colour each, which is all a prop, a voxel and a sprite need.
 *
 * [hints] carries, per triangle, the direction the file said the surface
 * faces, so the final flat normal can be turned the right way out even after
 * decimation has moved its corners.
 */
class ColoredMesh(
    val positions: FloatArray,
    val indices: IntArray,
    val colors: IntArray,
    val hints: FloatArray,
) {
    val triangleCount: Int get() = indices.size / 3
    val vertexCount: Int get() = positions.size / 3

    companion object {

        /**
         * Paints every triangle with the average of its surface at four points:
         * the centre and near each corner.
         *
         * Four samples rather than one because a generated texture is laid out
         * in islands, and a single sample at the centre of a thin triangle often
         * lands on the island's padding colour.
         */
        fun of(mesh: ModelMesh): ColoredMesh {
            val triangles = mesh.triangleCount
            val colors = IntArray(triangles)
            val hints = FloatArray(triangles * 3)
            for (t in 0 until triangles) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for ((w0, w1, w2) in SAMPLES) {
                    val c = mesh.colorAt(t, w0, w1, w2)
                    a += Colors.a(c); r += Colors.r(c); g += Colors.g(c); b += Colors.b(c)
                }
                colors[t] = Colors.argb(255, r / SAMPLES.size, g / SAMPLES.size, b / SAMPLES.size)
                val normals = mesh.normals
                if (normals != null) {
                    for (corner in 0 until 3) {
                        val v = mesh.indices[t * 3 + corner]
                        hints[t * 3] += normals[v * 3]; hints[t * 3 + 1] += normals[v * 3 + 1]; hints[t * 3 + 2] += normals[v * 3 + 2]
                    }
                }
            }
            return ColoredMesh(mesh.positions.copyOf(), mesh.indices.copyOf(), colors, hints)
        }

        private val SAMPLES = listOf(
            Triple(1f / 3, 1f / 3, 1f / 3),
            Triple(0.6f, 0.2f, 0.2f),
            Triple(0.2f, 0.6f, 0.2f),
            Triple(0.2f, 0.2f, 0.6f),
        )
    }
}

/**
 * Brings a mesh under a triangle budget by vertex clustering.
 *
 * Space is cut into a grid and every vertex in a cell is merged into one at
 * their average; triangles whose corners land in fewer than three cells vanish.
 * Crude beside edge-collapse decimation, and exactly right here: it runs in
 * linear time on a phone, never fails on the non-manifold soup generators
 * produce, and its result is already snapped to a lattice, which at a prop's
 * size looks like deliberate low-poly rather than damage.
 *
 * The grid is the finest one that fits the budget, found by bisection, so a
 * model that is already small enough is returned untouched.
 */
object ModelDecimator {

    fun decimate(mesh: ColoredMesh, maxTriangles: Int): ColoredMesh {
        require(maxTriangles > 0) { "a budget of no triangles leaves nothing to draw" }
        if (mesh.triangleCount <= maxTriangles) return mesh
        var low = MIN_CELLS
        var high = MAX_CELLS
        var best: ColoredMesh = cluster(mesh, MIN_CELLS)
        while (low <= high) {
            val mid = (low + high) / 2
            val attempt = cluster(mesh, mid)
            if (attempt.triangleCount <= maxTriangles) {
                best = attempt
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // The coarsest grid can still be over a tiny budget; the largest triangles are what to keep.
        return if (best.triangleCount <= maxTriangles) best else keepLargest(best, maxTriangles)
    }

    /** Merges vertices on a grid of [cells] along the model's longest side. */
    fun cluster(mesh: ColoredMesh, cells: Int): ColoredMesh {
        val count = mesh.vertexCount
        if (count == 0) return mesh
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val p = mesh.positions
        for (i in 0 until count) {
            minX = minOf(minX, p[i * 3]); maxX = maxOf(maxX, p[i * 3])
            minY = minOf(minY, p[i * 3 + 1]); maxY = maxOf(maxY, p[i * 3 + 1])
            minZ = minOf(minZ, p[i * 3 + 2]); maxZ = maxOf(maxZ, p[i * 3 + 2])
        }
        val size = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(1e-6f)
        val cell = size / cells
        val clusterOf = IntArray(count)
        val ids = HashMap<Long, Int>()
        val sums = ArrayList<FloatArray>()
        for (i in 0 until count) {
            val ix = floor((p[i * 3] - minX) / cell).toLong().coerceIn(0, cells.toLong())
            val iy = floor((p[i * 3 + 1] - minY) / cell).toLong().coerceIn(0, cells.toLong())
            val iz = floor((p[i * 3 + 2] - minZ) / cell).toLong().coerceIn(0, cells.toLong())
            val key = (ix shl 42) or (iy shl 21) or iz
            val id = ids.getOrPut(key) { sums += FloatArray(4); sums.lastIndex }
            clusterOf[i] = id
            val s = sums[id]
            s[0] += p[i * 3]; s[1] += p[i * 3 + 1]; s[2] += p[i * 3 + 2]; s[3] += 1f
        }
        val positions = FloatArray(sums.size * 3)
        sums.forEachIndexed { id, s -> positions[id * 3] = s[0] / s[3]; positions[id * 3 + 1] = s[1] / s[3]; positions[id * 3 + 2] = s[2] / s[3] }

        val indices = IntList()
        val colors = IntList()
        val hints = FloatList()
        val seen = HashSet<Long>()
        for (t in 0 until mesh.triangleCount) {
            val a = clusterOf[mesh.indices[t * 3]]; val b = clusterOf[mesh.indices[t * 3 + 1]]; val c = clusterOf[mesh.indices[t * 3 + 2]]
            if (a == b || b == c || a == c) continue
            // The same three cells twice is one triangle drawn twice; keep the first.
            val sorted = intArrayOf(a, b, c).apply { sort() }
            if (!seen.add((sorted[0].toLong() shl 42) or (sorted[1].toLong() shl 21) or sorted[2].toLong())) continue
            indices.add(a); indices.add(b); indices.add(c)
            colors.add(mesh.colors[t])
            hints.add(mesh.hints[t * 3], mesh.hints[t * 3 + 1], mesh.hints[t * 3 + 2])
        }
        return ColoredMesh(positions, indices.toArray(), colors.toArray(), hints.toArray())
    }

    private fun keepLargest(mesh: ColoredMesh, maxTriangles: Int): ColoredMesh {
        val order = (0 until mesh.triangleCount).sortedByDescending { t ->
            val n = ModelNormalizer.faceNormal(mesh.positions, mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2], normalize = false)
            n[0] * n[0] + n[1] * n[1] + n[2] * n[2]
        }.take(maxTriangles)
        val indices = IntArray(order.size * 3)
        val colors = IntArray(order.size)
        val hints = FloatArray(order.size * 3)
        order.forEachIndexed { i, t ->
            mesh.indices.copyInto(indices, i * 3, t * 3, t * 3 + 3)
            colors[i] = mesh.colors[t]
            mesh.hints.copyInto(hints, i * 3, t * 3, t * 3 + 3)
        }
        return ColoredMesh(mesh.positions, indices, colors, hints)
    }

    private const val MIN_CELLS = 2
    private const val MAX_CELLS = 512
}
