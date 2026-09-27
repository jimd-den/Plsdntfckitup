package com.stratum.engine.model

import com.stratum.engine.scene.Texture

/**
 * What a surface is painted with: a colour, optionally multiplied by a picture.
 *
 * The metallic-roughness half of glTF's material is dropped on purpose. The
 * game lights everything with one equation that has no specular term, so a
 * metalness value would be read and then ignored; the colour is what survives
 * into a voxel, a vertex and a sprite.
 */
data class ModelMaterial(
    val name: String = "",
    /** Linear-ish ARGB, as the file stated it; white when the file said nothing. */
    val baseColor: Int = WHITE,
    val texture: Texture? = null,
) {
    companion object {
        const val WHITE = -0x1
        val DEFAULT = ModelMaterial()
    }
}

/**
 * A triangle mesh, flattened: every primitive of every node, already moved by
 * its node's transform, in one set of arrays.
 *
 * Flat arrays rather than objects per vertex because a generated model runs to
 * tens of thousands of vertices and this lives on a phone. One index list and
 * a material per triangle is enough to voxelise, colour, decimate and bake;
 * nothing downstream needs the file's node tree back.
 *
 * Coordinates are whatever the stage that produced this says they are: the
 * loader leaves them in the file's own Y-up space, and [ModelNormalizer] turns
 * them into the world's Z-up blocks.
 */
class ModelMesh(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    val indices: IntArray,
    /** x, y, z per vertex, or null when the file had none. */
    val normals: FloatArray? = null,
    /** u, v per vertex, or null. */
    val uvs: FloatArray? = null,
    /** ARGB per vertex, or null. Multiplies the material colour, as glTF's COLOR_0 does. */
    val colors: IntArray? = null,
    /** Index into [materials] for every triangle. */
    val triangleMaterials: IntArray = IntArray(indices.size / 3),
    val materials: List<ModelMaterial> = listOf(ModelMaterial.DEFAULT),
) {
    init {
        require(positions.size % 3 == 0) { "positions must be x, y, z triples" }
        require(indices.size % 3 == 0) { "indices must be whole triangles" }
        require(triangleMaterials.size == indices.size / 3) { "every triangle needs a material" }
        require(normals == null || normals.size == positions.size) { "one normal per vertex" }
        require(uvs == null || uvs.size / 2 == vertexCount) { "one uv per vertex" }
        require(colors == null || colors.size == vertexCount) { "one colour per vertex" }
        require(materials.isNotEmpty()) { "at least one material" }
        val vertices = vertexCount
        indices.forEach { require(it in 0 until vertices) { "index $it is outside the $vertices vertices" } }
        triangleMaterials.forEach { require(it in materials.indices) { "material $it does not exist" } }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** The smallest box around every vertex, or null for an empty mesh. */
    fun bounds(): Bounds? {
        if (vertexCount == 0) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * The colour of the surface at a point on a triangle, given by barycentric
     * weights: the material colour, times the texture, times the vertex colour.
     *
     * This is the single place that decides what colour a model *is*, so the
     * voxels, the flat-shaded prop and the baked sprite can never disagree.
     */
    fun colorAt(triangle: Int, w0: Float, w1: Float, w2: Float): Int {
        val material = materials[triangleMaterials[triangle]]
        val a = indices[triangle * 3]; val b = indices[triangle * 3 + 1]; val c = indices[triangle * 3 + 2]
        var color = material.baseColor
        val texture = material.texture
        if (texture != null && uvs != null) {
            val u = uvs[a * 2] * w0 + uvs[b * 2] * w1 + uvs[c * 2] * w2
            val v = uvs[a * 2 + 1] * w0 + uvs[b * 2 + 1] * w1 + uvs[c * 2 + 1] * w2
            color = Colors.multiply(color, sample(texture, u, v))
        }
        if (colors != null) {
            color = Colors.multiply(color, Colors.blend3(colors[a], colors[b], colors[c], w0, w1, w2))
        }
        return color
    }

    /** Nearest texel with wrapping, which is glTF's default sampler. */
    private fun sample(texture: Texture, u: Float, v: Float): Int {
        val fu = u - kotlin.math.floor(u)
        val fv = v - kotlin.math.floor(v)
        val x = (fu * texture.width).toInt().coerceIn(0, texture.width - 1)
        val y = (fv * texture.height).toInt().coerceIn(0, texture.height - 1)
        return texture.argb[y * texture.width + x]
    }

    /** A copy with the same topology and new positions and normals. */
    fun withPositions(positions: FloatArray, normals: FloatArray? = this.normals): ModelMesh =
        ModelMesh(positions, indices, normals, uvs, colors, triangleMaterials, materials)
}

/** An axis-aligned box. */
data class Bounds(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float) {
    val sizeX: Float get() = maxX - minX
    val sizeY: Float get() = maxY - minY
    val sizeZ: Float get() = maxZ - minZ
}

/** Why a model could not be read, in words for the person who made the file. */
class ModelFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
