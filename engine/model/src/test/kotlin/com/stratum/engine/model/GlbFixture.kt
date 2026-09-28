package com.stratum.engine.model

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Builds small GLB files in memory, so every parser test states exactly what
 * is in its file instead of depending on a binary fixture nobody can read.
 *
 * Writes what an exporter writes: one binary buffer, a buffer view and an
 * accessor per attribute, 4-byte alignment, and a JSON chunk padded with
 * spaces.
 */
class GlbFixture {
    private val bin = ByteArrayOutputStream()
    private val bufferViews = ArrayList<String>()
    private val accessors = ArrayList<String>()
    private val meshes = ArrayList<String>()
    private val nodes = ArrayList<String>()
    private val materials = ArrayList<String>()
    private val images = ArrayList<String>()
    private val textures = ArrayList<String>()
    var extraRoot: String = ""
    var sceneNodes: List<Int>? = null

    private fun align() {
        while (bin.size() % 4 != 0) bin.write(0)
    }

    private fun view(bytes: ByteArray, target: Int? = null): Int {
        align()
        val offset = bin.size()
        bin.write(bytes)
        bufferViews += """{"buffer":0,"byteOffset":$offset,"byteLength":${bytes.size}${target?.let { ",\"target\":$it" } ?: ""}}"""
        return bufferViews.lastIndex
    }

    fun floats(values: FloatArray, type: String, withBounds: Boolean = false): Int {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        val view = view(buffer.array())
        val components = when (type) { "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 1 }
        val bounds = if (withBounds && components == 3) {
            val xs = values.filterIndexed { i, _ -> i % 3 == 0 }; val ys = values.filterIndexed { i, _ -> i % 3 == 1 }; val zs = values.filterIndexed { i, _ -> i % 3 == 2 }
            ""","min":[${xs.min()},${ys.min()},${zs.min()}],"max":[${xs.max()},${ys.max()},${zs.max()}]"""
        } else ""
        accessors += """{"bufferView":$view,"componentType":5126,"count":${values.size / components},"type":"$type"$bounds}"""
        return accessors.lastIndex
    }

    /** Indices as 8, 16 or 32-bit unsigned integers. */
    fun indices(values: IntArray, bits: Int = 16): Int {
        val size = bits / 8
        val buffer = ByteBuffer.allocate(values.size * size).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach {
            when (bits) {
                8 -> buffer.put(it.toByte())
                16 -> buffer.putShort(it.toShort())
                else -> buffer.putInt(it)
            }
        }
        val view = view(buffer.array())
        val type = when (bits) { 8 -> 5121; 16 -> 5123; else -> 5125 }
        accessors += """{"bufferView":$view,"componentType":$type,"count":${values.size},"type":"SCALAR"}"""
        return accessors.lastIndex
    }

    /** Vertex colours as normalised unsigned bytes, RGBA. */
    fun byteColors(values: IntArray): Int {
        val buffer = ByteBuffer.allocate(values.size * 4)
        values.forEach { buffer.put(((it ushr 16) and 0xFF).toByte()); buffer.put(((it ushr 8) and 0xFF).toByte()); buffer.put((it and 0xFF).toByte()); buffer.put(((it ushr 24) and 0xFF).toByte()) }
        val view = view(buffer.array())
        accessors += """{"bufferView":$view,"componentType":5121,"normalized":true,"count":${values.size},"type":"VEC4"}"""
        return accessors.lastIndex
    }

    fun rawAccessor(json: String): Int {
        accessors += json
        return accessors.lastIndex
    }

    /** A solid-colour PNG, embedded, as a texture index. */
    fun pngTexture(width: Int, height: Int, argb: (Int, Int) -> Int): Int {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, argb(x, y))
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val view = view(png)
        images += """{"bufferView":$view,"mimeType":"image/png"}"""
        textures += """{"source":${images.lastIndex}}"""
        return textures.lastIndex
    }

    fun material(baseColorFactor: FloatArray? = null, texture: Int? = null): Int {
        val parts = listOfNotNull(
            baseColorFactor?.let { "\"baseColorFactor\":[${it.joinToString(",")}]" },
            texture?.let { "\"baseColorTexture\":{\"index\":$it}" },
        )
        materials += """{"pbrMetallicRoughness":{${parts.joinToString(",")}}}"""
        return materials.lastIndex
    }

    fun mesh(
        positions: Int,
        indices: Int? = null,
        normals: Int? = null,
        uvs: Int? = null,
        colors: Int? = null,
        material: Int? = null,
        mode: Int? = null,
        extensions: String? = null,
    ): Int {
        val attributes = listOfNotNull(
            "\"POSITION\":$positions",
            normals?.let { "\"NORMAL\":$it" },
            uvs?.let { "\"TEXCOORD_0\":$it" },
            colors?.let { "\"COLOR_0\":$it" },
        ).joinToString(",")
        val extra = listOfNotNull(
            indices?.let { "\"indices\":$it" },
            material?.let { "\"material\":$it" },
            mode?.let { "\"mode\":$it" },
            extensions?.let { "\"extensions\":$it" },
        ).joinToString("") { ",$it" }
        meshes += """{"primitives":[{"attributes":{$attributes}$extra}]}"""
        return meshes.lastIndex
    }

    /** A node, with any of a mesh, a TRS transform, a matrix and children. */
    fun node(
        mesh: Int? = null,
        translation: FloatArray? = null,
        rotation: FloatArray? = null,
        scale: FloatArray? = null,
        matrix: FloatArray? = null,
        children: List<Int> = emptyList(),
    ): Int {
        val parts = listOfNotNull(
            mesh?.let { "\"mesh\":$it" },
            translation?.let { "\"translation\":[${it.joinToString(",")}]" },
            rotation?.let { "\"rotation\":[${it.joinToString(",")}]" },
            scale?.let { "\"scale\":[${it.joinToString(",")}]" },
            matrix?.let { "\"matrix\":[${it.joinToString(",")}]" },
            children.takeIf { it.isNotEmpty() }?.let { "\"children\":[${it.joinToString(",")}]" },
        )
        nodes += "{${parts.joinToString(",")}}"
        return nodes.lastIndex
    }

    fun json(): String {
        align()
        val roots = sceneNodes ?: nodes.indices.filter { index -> nodes.none { it.contains("\"children\"") && Regex("\"children\":\\[([0-9,]*)]").find(it)?.groupValues?.get(1)?.split(",")?.contains("$index") == true } }
        return buildString {
            append("{\"asset\":{\"version\":\"2.0\"}")
            append(",\"scene\":0,\"scenes\":[{\"nodes\":[${roots.joinToString(",")}]}]")
            append(",\"nodes\":[${nodes.joinToString(",")}]")
            append(",\"meshes\":[${meshes.joinToString(",")}]")
            append(",\"accessors\":[${accessors.joinToString(",")}]")
            append(",\"bufferViews\":[${bufferViews.joinToString(",")}]")
            append(",\"buffers\":[{\"byteLength\":${bin.size()}}]")
            if (materials.isNotEmpty()) append(",\"materials\":[${materials.joinToString(",")}]")
            if (images.isNotEmpty()) append(",\"images\":[${images.joinToString(",")}]")
            if (textures.isNotEmpty()) append(",\"textures\":[${textures.joinToString(",")}]")
            append(extraRoot)
            append("}")
        }
    }

    fun build(json: String = json()): ByteArray = glb(json, bin.toByteArray())

    companion object {

        fun glb(json: String, bin: ByteArray?): ByteArray {
            val jsonBytes = json.toByteArray(Charsets.UTF_8).let { bytes ->
                bytes + ByteArray((4 - bytes.size % 4) % 4) { ' '.code.toByte() }
            }
            val binBytes = bin?.let { it + ByteArray((4 - it.size % 4) % 4) }
            val total = 12 + 8 + jsonBytes.size + (binBytes?.let { 8 + it.size } ?: 0)
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
            out.putInt(jsonBytes.size); out.putInt(0x4E4F534A); out.put(jsonBytes)
            if (binBytes != null) { out.putInt(binBytes.size); out.putInt(0x004E4942); out.put(binBytes) }
            return out.array()
        }

        /** A unit cube from 0 to 1 on every axis: 8 corners, 12 triangles wound outward. */
        val CUBE_POSITIONS = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f,
        )
        val CUBE_INDICES = intArrayOf(
            0, 2, 1, 0, 3, 2, // z = 0
            4, 5, 6, 4, 6, 7, // z = 1
            0, 1, 5, 0, 5, 4, // y = 0
            2, 3, 7, 2, 7, 6, // y = 1
            1, 2, 6, 1, 6, 5, // x = 1
            0, 4, 7, 0, 7, 3, // x = 0
        )

        /** A one-node unit cube with an optional material. */
        fun cube(material: (GlbFixture) -> Int? = { null }, uvs: FloatArray? = null): ByteArray {
            val f = GlbFixture()
            val positions = f.floats(CUBE_POSITIONS, "VEC3", withBounds = true)
            val indices = f.indices(CUBE_INDICES)
            val uv = uvs?.let { f.floats(it, "VEC2") }
            f.node(mesh = f.mesh(positions, indices, uvs = uv, material = material(f)))
            return f.build()
        }

        /** Decodes PNG and JPEG bytes the way the JVM tools do. */
        fun decode(bytes: ByteArray): com.stratum.engine.scene.Texture? {
            val image = ImageIO.read(bytes.inputStream()) ?: return null
            val argb = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
            return com.stratum.engine.scene.Texture(image.width, image.height, argb)
        }
    }
}
