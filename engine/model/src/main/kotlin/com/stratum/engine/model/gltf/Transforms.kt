package com.stratum.engine.model.gltf

import com.stratum.engine.model.ModelFormatException
import com.stratum.engine.model.ModelMaterial
import com.stratum.engine.model.ModelMesh
import com.stratum.engine.scene.Mat4
import kotlin.math.sqrt

/** Node transforms and primitive topology, as glTF defines them. */
internal object Transforms {

    /** Translation, then rotation (a unit quaternion x, y, z, w), then scale: T * R * S, column-major. */
    fun trs(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
        val (qx, qy, qz, qw) = normalizedQuaternion(r)
        val m = Mat4.identity()
        m[0] = (1 - 2 * (qy * qy + qz * qz)) * s[0]
        m[1] = (2 * (qx * qy + qz * qw)) * s[0]
        m[2] = (2 * (qx * qz - qy * qw)) * s[0]
        m[4] = (2 * (qx * qy - qz * qw)) * s[1]
        m[5] = (1 - 2 * (qx * qx + qz * qz)) * s[1]
        m[6] = (2 * (qy * qz + qx * qw)) * s[1]
        m[8] = (2 * (qx * qz + qy * qw)) * s[2]
        m[9] = (2 * (qy * qz - qx * qw)) * s[2]
        m[10] = (1 - 2 * (qx * qx + qy * qy)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]
        return m
    }

    private fun normalizedQuaternion(r: FloatArray): FloatArray {
        if (r.size != 4) throw ModelFormatException("A node rotation must be a quaternion of four numbers")
        val length = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2] + r[3] * r[3])
        return if (length < 1e-6f) floatArrayOf(0f, 0f, 0f, 1f) else FloatArray(4) { r[it] / length }
    }

    /**
     * The matrix normals are moved by: the inverse transpose of the upper 3x3.
     *
     * Not the transform itself, because a node scaled unevenly — a statue
     * stretched tall — would tilt every normal towards the stretched axis and
     * light its sides as if they faced upward.
     */
    fun normalMatrix(m: FloatArray): FloatArray {
        val upper = Mat4.identity()
        for (col in 0 until 3) for (row in 0 until 3) upper[col * 4 + row] = m[col * 4 + row]
        val inverse = Mat4.invert(upper) ?: return upper
        val out = Mat4.identity()
        for (col in 0 until 3) for (row in 0 until 3) out[col * 4 + row] = inverse[row * 4 + col]
        return out
    }

    /** Strips and fans turned into a plain triangle list; lists pass through. */
    fun triangulate(indices: IntArray, mode: Int): IntArray = when (mode) {
        5 -> {
            val out = ArrayList<Int>()
            for (i in 0 until indices.size - 2) {
                // Every other triangle of a strip is wound the other way.
                if (i % 2 == 0) out += listOf(indices[i], indices[i + 1], indices[i + 2])
                else out += listOf(indices[i + 1], indices[i], indices[i + 2])
            }
            out.toIntArray()
        }
        6 -> {
            val out = ArrayList<Int>()
            for (i in 1 until indices.size - 1) out += listOf(indices[0], indices[i], indices[i + 1])
            out.toIntArray()
        }
        else -> if (indices.size % 3 == 0) indices else indices.copyOf(indices.size - indices.size % 3)
    }
}

/**
 * Gathers primitives from many nodes into one mesh, transforming as it goes.
 *
 * Counts are checked on every add, so a file with a modest number of vertices
 * per part but thousands of parts is stopped at the limit rather than after it
 * has filled the heap.
 */
internal class MeshAccumulator(private val limits: ModelLimits) {
    private val positions = FloatList()
    private val normals = FloatList()
    private val uvs = FloatList()
    private val colors = IntList()
    private val indices = IntList()
    private val triangleMaterials = IntList()
    private var anyNormals = false
    private var anyUvs = false
    private var anyColors = false

    val triangleCount: Int get() = triangleMaterials.size

    fun add(
        positions: FloatArray,
        normals: FloatArray?,
        uvs: FloatArray?,
        colors: IntArray?,
        triangles: IntArray,
        material: Int,
        transform: FloatArray,
        normalMatrix: FloatArray,
    ) {
        val count = positions.size / 3
        val base = this.positions.size / 3
        if (base + count > limits.maxVertices) {
            throw ModelFormatException("The model has more than ${limits.maxVertices} vertices, too many for a phone")
        }
        if (triangleCount + triangles.size / 3 > limits.maxTriangles) {
            throw ModelFormatException("The model has more than ${limits.maxTriangles} triangles, too many for a phone")
        }
        val out = FloatArray(4)
        for (i in 0 until count) {
            Mat4.transform(transform, positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2], out)
            val w = if (out[3] == 0f) 1f else out[3]
            this.positions.add(out[0] / w, out[1] / w, out[2] / w)
            if (normals != null) {
                Mat4.transform(normalMatrix, normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2], out)
                // Only the 3x3 part applies to a direction; the w row is ignored.
                val nx = out[0] - normalMatrix[12]; val ny = out[1] - normalMatrix[13]; val nz = out[2] - normalMatrix[14]
                val length = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-6f } ?: 1f
                this.normals.add(nx / length, ny / length, nz / length)
            } else {
                this.normals.add(0f, 0f, 0f)
            }
            if (uvs != null) this.uvs.add(uvs[i * 2], uvs[i * 2 + 1]) else this.uvs.add(0f, 0f)
            this.colors.add(colors?.get(i) ?: ModelMaterial.WHITE)
        }
        anyNormals = anyNormals || normals != null
        anyUvs = anyUvs || uvs != null
        anyColors = anyColors || colors != null
        // A mirroring transform turns every triangle inside out; swap two corners to keep them facing out.
        val mirrored = determinant3(transform) < 0f
        for (t in 0 until triangles.size / 3) {
            val a = triangles[t * 3]; val b = triangles[t * 3 + 1]; val c = triangles[t * 3 + 2]
            if (a == b || b == c || a == c) continue
            indices.add(base + a)
            indices.add(base + if (mirrored) c else b)
            indices.add(base + if (mirrored) b else c)
            triangleMaterials.add(material)
        }
    }

    fun build(materials: List<ModelMaterial>): ModelMesh = ModelMesh(
        positions = positions.toArray(),
        indices = indices.toArray(),
        normals = if (anyNormals) normals.toArray() else null,
        uvs = if (anyUvs) uvs.toArray() else null,
        colors = if (anyColors) colors.toArray() else null,
        triangleMaterials = triangleMaterials.toArray(),
        materials = materials,
    )

    private fun determinant3(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
}

/** A growable float array, to avoid boxing tens of thousands of floats. */
internal class FloatList {
    private var data = FloatArray(1024)
    var size = 0
        private set

    fun add(a: Float, b: Float) {
        ensure(2)
        data[size++] = a; data[size++] = b
    }

    fun add(a: Float, b: Float, c: Float) {
        ensure(3)
        data[size++] = a; data[size++] = b; data[size++] = c
    }

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** A growable int array, for the same reason as [FloatList]. */
internal class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}
