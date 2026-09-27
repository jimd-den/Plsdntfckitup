package com.stratum.engine.model

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Puts a model into the world's frame, at the world's scale.
 *
 * Generated models arrive at arbitrary size, somewhere near the origin, in
 * glTF's Y-up space. The game is Z-up and measured in blocks, and a prop
 * stands on the ground at the centre of its cell. So every model is turned
 * upright, centred over the origin in plan, dropped until its lowest point
 * touches z = 0, and scaled uniformly so it is [targetHeight] blocks tall —
 * or narrower than [maxFootprint] blocks, whichever is smaller, so a model
 * that came back lying flat does not become a prop thirty blocks wide.
 *
 * The turn from Y-up is (x, y, z) -> (-x, z, y): a proper rotation, so
 * triangles keep their winding, and the model's front (+Z in glTF) faces +y,
 * the south, which is towards the game's camera.
 */
object ModelNormalizer {

    fun normalize(
        mesh: ModelMesh,
        targetHeight: Float,
        maxFootprint: Float = targetHeight * DEFAULT_FOOTPRINT_RATIO,
        fromYUp: Boolean = true,
    ): ModelMesh {
        require(targetHeight > 0f) { "a model must be taller than nothing" }
        val count = mesh.vertexCount
        val turned = FloatArray(mesh.positions.size)
        val normals = mesh.normals?.let { FloatArray(it.size) }
        for (i in 0 until count) {
            val x = mesh.positions[i * 3]; val y = mesh.positions[i * 3 + 1]; val z = mesh.positions[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) throw ModelFormatException("Vertex $i is not a finite point")
            if (fromYUp) {
                turned[i * 3] = -x; turned[i * 3 + 1] = z; turned[i * 3 + 2] = y
            } else {
                turned[i * 3] = x; turned[i * 3 + 1] = y; turned[i * 3 + 2] = z
            }
            if (normals != null) {
                val n = mesh.normals
                if (fromYUp) {
                    normals[i * 3] = -n[i * 3]; normals[i * 3 + 1] = n[i * 3 + 2]; normals[i * 3 + 2] = n[i * 3 + 1]
                } else {
                    n.copyInto(normals, i * 3, i * 3, i * 3 + 3)
                }
            }
        }
        val box = mesh.withPositions(turned, normals).bounds() ?: throw ModelFormatException("The model has no vertices")
        val height = box.sizeZ
        val footprint = max(box.sizeX, box.sizeY)
        if (height <= EPSILON && footprint <= EPSILON) throw ModelFormatException("The model has no size: every vertex is in one place")
        val byHeight = if (height > EPSILON) targetHeight / height else Float.MAX_VALUE
        val byFootprint = if (footprint > EPSILON) maxFootprint / footprint else Float.MAX_VALUE
        val scale = minOf(byHeight, byFootprint)
        val cx = (box.minX + box.maxX) / 2f
        val cy = (box.minY + box.maxY) / 2f
        for (i in 0 until count) {
            turned[i * 3] = (turned[i * 3] - cx) * scale
            turned[i * 3 + 1] = (turned[i * 3 + 1] - cy) * scale
            turned[i * 3 + 2] = (turned[i * 3 + 2] - box.minZ) * scale
        }
        return mesh.withPositions(turned, normals ?: faceNormalsPerVertex(turned, mesh.indices))
    }

    /**
     * Normals for a model that shipped none: each vertex gets the area-weighted
     * average of the faces around it. The file's shared vertices decide what is
     * smooth, which is what its author meant.
     */
    fun faceNormalsPerVertex(positions: FloatArray, indices: IntArray): FloatArray {
        val out = FloatArray(positions.size)
        for (t in 0 until indices.size / 3) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val n = faceNormal(positions, a, b, c, normalize = false)
            for (v in intArrayOf(a, b, c)) {
                out[v * 3] += n[0]; out[v * 3 + 1] += n[1]; out[v * 3 + 2] += n[2]
            }
        }
        for (v in 0 until out.size / 3) {
            val l = sqrt(out[v * 3] * out[v * 3] + out[v * 3 + 1] * out[v * 3 + 1] + out[v * 3 + 2] * out[v * 3 + 2])
            if (l > EPSILON) { out[v * 3] /= l; out[v * 3 + 1] /= l; out[v * 3 + 2] /= l } else out[v * 3 + 2] = 1f
        }
        return out
    }

    /** The normal of one triangle, counter-clockwise front, unit length unless asked otherwise. */
    fun faceNormal(p: FloatArray, a: Int, b: Int, c: Int, normalize: Boolean = true): FloatArray {
        val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        if (!normalize) return floatArrayOf(nx, ny, nz)
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        return if (l < 1e-12f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(nx / l, ny / l, nz / l)
    }

    /** Wider than tall is allowed — a chest, a fallen log — but not without bound. */
    const val DEFAULT_FOOTPRINT_RATIO = 2f

    private const val EPSILON = 1e-6f
}
