package com.stratum.engine.model

import com.stratum.engine.model.gltf.FloatList
import com.stratum.engine.model.gltf.IntList
import com.stratum.engine.model.gltf.ModelLimits

/**
 * Reads a Wavefront OBJ: positions, optional per-vertex colours (the common
 * `v x y z r g b` extension), and faces of any size fanned into triangles.
 *
 * Geometry only. Materials live in a separate `.mtl` file that an endpoint
 * returning one OBJ does not send, so an OBJ model is drawn in its vertex
 * colours or plain white, and voxelised to the nearest light block. That is
 * why GLB is the format asked for and this is the fallback.
 */
class ObjLoader(private val limits: ModelLimits = ModelLimits()) {

    fun load(bytes: ByteArray): ModelMesh {
        if (bytes.size > limits.maxFileBytes) throw ModelFormatException("The model is too large for a phone (${bytes.size} bytes)")
        val positions = FloatList()
        val colors = IntList()
        var anyColor = false
        val indices = IntList()
        var lineNumber = 0
        String(bytes, Charsets.UTF_8).lineSequence().forEach { raw ->
            lineNumber++
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE)
            when (parts[0]) {
                "v" -> {
                    if (parts.size < 4) throw ModelFormatException("Line $lineNumber: a vertex needs x, y and z")
                    positions.add(number(parts[1], lineNumber), number(parts[2], lineNumber), number(parts[3], lineNumber))
                    if (parts.size >= 7) {
                        anyColor = true
                        colors.add(Colors.fromFloats(number(parts[4], lineNumber), number(parts[5], lineNumber), number(parts[6], lineNumber), 1f))
                    } else {
                        colors.add(ModelMaterial.WHITE)
                    }
                    if (positions.size / 3 > limits.maxVertices) throw ModelFormatException("More than ${limits.maxVertices} vertices")
                }
                "f" -> {
                    val count = positions.size / 3
                    val corners = parts.drop(1).map { token ->
                        val index = token.substringBefore('/').toIntOrNull()
                            ?: throw ModelFormatException("Line $lineNumber: '$token' is not a face corner")
                        // Negative indices count back from the latest vertex.
                        val resolved = if (index < 0) count + index else index - 1
                        if (resolved !in 0 until count) throw ModelFormatException("Line $lineNumber: vertex $index does not exist")
                        resolved
                    }
                    if (corners.size < 3) throw ModelFormatException("Line $lineNumber: a face needs three corners")
                    for (i in 1 until corners.size - 1) {
                        indices.add(corners[0]); indices.add(corners[i]); indices.add(corners[i + 1])
                    }
                    if (indices.size / 3 > limits.maxTriangles) throw ModelFormatException("More than ${limits.maxTriangles} triangles")
                }
            }
        }
        if (indices.size == 0) throw ModelFormatException("The OBJ file has no faces")
        val indexArray = indices.toArray()
        return ModelMesh(
            positions = positions.toArray(),
            indices = indexArray,
            colors = if (anyColor) colors.toArray() else null,
            triangleMaterials = IntArray(indexArray.size / 3),
        )
    }

    private fun number(text: String, line: Int): Float =
        text.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw ModelFormatException("Line $line: '$text' is not a number")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
