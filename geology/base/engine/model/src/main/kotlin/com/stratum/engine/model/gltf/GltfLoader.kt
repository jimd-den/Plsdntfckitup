package com.stratum.engine.model.gltf

import com.stratum.engine.model.ModelFormatException
import com.stratum.engine.model.ModelMaterial
import com.stratum.engine.model.ModelMesh
import com.stratum.engine.scene.Mat4
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.forge.Pixels
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow

/**
 * Reads a glTF 2.0 document into one flat [ModelMesh].
 *
 * Covers what mesh generators actually emit — triangle meshes with positions,
 * normals, one or two UV sets, vertex colours, 8/16/32-bit indices, a node tree
 * with matrices or TRS, and a base colour that may be a texture embedded in the
 * binary chunk — and refuses the rest by name: sparse accessors, external
 * files, and draco-compressed geometry all fail with a sentence rather than a
 * model with holes in it.
 *
 * Images are decoded by [decodeImage], because decoding a PNG or JPEG is a
 * platform concern: Android has a decoder, the JVM has another, and this module
 * must run on both. With no decoder the model keeps its base colours and loses
 * only the pictures.
 */
class GltfLoader(
    private val decodeImage: ((ByteArray) -> Texture?)? = null,
    private val limits: ModelLimits = ModelLimits(),
) {

    fun loadGlb(bytes: ByteArray): ModelMesh {
        val container = GlbContainer.read(bytes, limits)
        return load(container.json, container.bin)
    }

    /** A document and the bytes of its first buffer. */
    fun load(json: String, bin: ByteArray?): ModelMesh {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (failure: SerializationException) {
            throw ModelFormatException("The model's JSON does not parse: ${failure.message?.lineSequence()?.firstOrNull()}", failure)
        } catch (failure: IllegalArgumentException) {
            throw ModelFormatException("The model's JSON is not an object", failure)
        }
        return Document(root, bin).mesh()
    }

    private inner class Document(private val root: JsonObject, private val bin: ByteArray?) {

        private val accessors = root.list("accessors")
        private val bufferViews = root.list("bufferViews")
        private val buffers = root.list("buffers").mapIndexed { index, buffer -> lazy { bufferBytes(index, buffer) } }
        private val meshes = root.list("meshes")
        private val nodes = root.list("nodes")
        private val materials: List<ModelMaterial>
        private val materialTexCoords: List<Int>

        init {
            root.obj("asset")?.string("version")?.let { version ->
                if (!version.startsWith("2.")) throw ModelFormatException("glTF version $version is not supported; only 2.0 is")
            }
            val required = root["extensionsRequired"]?.let { it as? JsonArray }?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            required.firstOrNull { it !in SUPPORTED_EXTENSIONS }?.let {
                throw ModelFormatException("The model needs the '$it' extension, which is not supported. Export it without compression.")
            }
            val textures = root.list("textures")
            val images = root.list("images")
            val decoded = HashMap<Int, Texture?>()
            fun textureOf(index: Int?): Texture? {
                val source = index?.let(textures::getOrNull)?.int("source") ?: return null
                return decoded.getOrPut(source) { images.getOrNull(source)?.let(::imageTexture) }
            }
            val parsed = root.list("materials").map { material ->
                val pbr = material.obj("pbrMetallicRoughness")
                val factor = pbr?.floats("baseColorFactor")?.takeIf { it.size == 4 }
                val colour = factor?.let { linearToDisplay(it[0], it[1], it[2], it[3]) } ?: ModelMaterial.WHITE
                val textureInfo = pbr?.obj("baseColorTexture")
                ModelMaterial(
                    name = material.string("name").orEmpty(),
                    baseColor = colour,
                    texture = textureOf(textureInfo?.int("index")),
                ) to (textureInfo?.int("texCoord") ?: 0)
            }
            materials = parsed.map { it.first } + ModelMaterial.DEFAULT
            materialTexCoords = parsed.map { it.second } + 0
        }

        /** The default material, appended last, for primitives that name none. */
        private val defaultMaterial: Int get() = materials.lastIndex

        fun mesh(): ModelMesh {
            val out = MeshAccumulator(limits)
            val sceneIndex = root.int("scene") ?: 0
            val scene = root.list("scenes").getOrNull(sceneIndex)
            val rootNodes = scene?.ints("nodes")
                // A file with no scenes is legal; its nodes that nothing parents are the roots.
                ?: nodes.indices.filter { index -> nodes.none { index in (it.ints("children") ?: emptyList()) } }
            if (rootNodes.isEmpty() && meshes.isNotEmpty()) {
                // Meshes with no node at all: take them as they are.
                meshes.indices.forEach { addMesh(it, Mat4.identity(), out) }
            }
            val visited = HashSet<Int>()
            rootNodes.forEach { visit(it, Mat4.identity(), out, visited) }
            if (out.triangleCount == 0) throw ModelFormatException("The model has no triangles")
            return out.build(materials)
        }

        private fun visit(index: Int, parent: FloatArray, out: MeshAccumulator, visited: MutableSet<Int>) {
            val node = nodes.getOrNull(index) ?: throw ModelFormatException("Node $index does not exist")
            // A node tree is a tree; a cycle would recurse forever on a hostile file.
            if (!visited.add(index)) throw ModelFormatException("Node $index appears twice in the scene")
            val world = Mat4.multiply(parent, localTransform(node))
            node.int("mesh")?.let { addMesh(it, world, out) }
            node.ints("children")?.forEach { visit(it, world, out, visited) }
        }

        private fun localTransform(node: JsonObject): FloatArray {
            node.floats("matrix")?.let { m ->
                if (m.size != 16) throw ModelFormatException("A node matrix must have 16 numbers")
                return m
            }
            val t = node.floats("translation") ?: floatArrayOf(0f, 0f, 0f)
            val r = node.floats("rotation") ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = node.floats("scale") ?: floatArrayOf(1f, 1f, 1f)
            return Transforms.trs(t, r, s)
        }

        private fun addMesh(index: Int, transform: FloatArray, out: MeshAccumulator) {
            val mesh = meshes.getOrNull(index) ?: throw ModelFormatException("Mesh $index does not exist")
            val normalMatrix = Transforms.normalMatrix(transform)
            mesh.list("primitives").forEach { primitive ->
                val mode = primitive.int("mode") ?: TRIANGLES
                if (mode != TRIANGLES && mode != TRIANGLE_STRIP && mode != TRIANGLE_FAN) return@forEach
                val attributes = primitive.obj("attributes") ?: throw ModelFormatException("A primitive has no attributes")
                if (primitive.obj("extensions")?.containsKey("KHR_draco_mesh_compression") == true) {
                    throw ModelFormatException("The model is draco-compressed, which is not supported. Export it without compression.")
                }
                val positionAccessor = attributes.int("POSITION") ?: throw ModelFormatException("A primitive has no POSITION")
                val positions = readFloats(positionAccessor, expectComponents = 3)
                val count = positions.size / 3
                val materialIndex = primitive.int("material")?.takeIf { it in 0 until defaultMaterial } ?: defaultMaterial
                val texCoord = materialTexCoords[materialIndex]
                val normals = attributes.int("NORMAL")?.let { readFloats(it, expectComponents = 3) }?.takeIf { it.size == count * 3 }
                val uvs = attributes.int("TEXCOORD_$texCoord")?.let { readFloats(it, expectComponents = 2) }?.takeIf { it.size == count * 2 }
                val colors = attributes.int("COLOR_0")?.let(::readColors)?.takeIf { it.size == count }
                val raw = primitive.int("indices")?.let(::readIndices) ?: IntArray(count) { it }
                raw.forEach { if (it !in 0 until count) throw ModelFormatException("An index points at vertex $it of $count") }
                val triangles = Transforms.triangulate(raw, mode)
                out.add(positions, normals, uvs, colors, triangles, materialIndex, transform, normalMatrix)
            }
        }

        // ---- accessors ------------------------------------------------------

        private fun accessor(index: Int): JsonObject =
            accessors.getOrNull(index) ?: throw ModelFormatException("Accessor $index does not exist")

        private fun componentsOf(type: String?): Int = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16
            else -> throw ModelFormatException("Unknown accessor type '$type'")
        }

        private fun sizeOf(componentType: Int): Int = when (componentType) {
            BYTE, UNSIGNED_BYTE -> 1
            SHORT, UNSIGNED_SHORT -> 2
            UNSIGNED_INT, FLOAT -> 4
            else -> throw ModelFormatException("Unknown component type $componentType")
        }

        /**
         * Where an accessor's elements are, with every bound checked.
         *
         * Null for an accessor with no buffer view, which glTF defines as all
         * zeros.
         */
        private fun viewFor(accessor: JsonObject, components: Int, componentSize: Int, count: Int): View? {
            if (accessor.containsKey("sparse")) throw ModelFormatException("Sparse accessors are not supported")
            val viewIndex = accessor.int("bufferView") ?: return null
            val view = bufferViews.getOrNull(viewIndex) ?: throw ModelFormatException("Buffer view $viewIndex does not exist")
            val buffer = view.int("buffer")?.let { buffers.getOrNull(it)?.value }
                ?: throw ModelFormatException("Buffer view $viewIndex names a buffer that does not exist")
            val viewOffset = view.int("byteOffset") ?: 0
            val viewLength = view.int("byteLength") ?: throw ModelFormatException("Buffer view $viewIndex has no length")
            if (viewOffset < 0 || viewLength < 0 || viewOffset.toLong() + viewLength > buffer.size) {
                throw ModelFormatException("Buffer view $viewIndex runs past the end of its buffer")
            }
            val element = components * componentSize
            val stride = view.int("byteStride")?.takeIf { it > 0 } ?: element
            if (stride < element) throw ModelFormatException("Buffer view $viewIndex has a stride smaller than its elements")
            val offset = accessor.int("byteOffset") ?: 0
            if (count > 0 && offset.toLong() + stride.toLong() * (count - 1) + element > viewLength) {
                throw ModelFormatException("An accessor of $count elements does not fit in buffer view $viewIndex")
            }
            return View(buffer, viewOffset + offset, stride, viewLength)
        }

        private fun readFloats(index: Int, expectComponents: Int): FloatArray {
            val accessor = accessor(index)
            val components = componentsOf(accessor.string("type"))
            if (components != expectComponents) {
                throw ModelFormatException("Accessor $index has $components components where $expectComponents were expected")
            }
            val count = accessor.int("count") ?: throw ModelFormatException("Accessor $index has no count")
            if (count < 0 || count > limits.maxVertices) {
                throw ModelFormatException("The model has $count vertices in one part; a phone can take ${limits.maxVertices}")
            }
            val type = accessor.int("componentType") ?: throw ModelFormatException("Accessor $index has no component type")
            val normalized = accessor.bool("normalized") ?: false
            val size = sizeOf(type)
            val out = FloatArray(count * components)
            val view = viewFor(accessor, components, size, count) ?: return out
            for (i in 0 until count) {
                val base = view.offset + i * view.stride
                for (c in 0 until components) out[i * components + c] = component(view.bytes, base + c * size, type, normalized)
            }
            return out
        }

        /** COLOR_0 as ARGB, from three or four components of any legal type. */
        private fun readColors(index: Int): IntArray {
            val accessor = accessor(index)
            val components = componentsOf(accessor.string("type"))
            if (components != 3 && components != 4) throw ModelFormatException("COLOR_0 must be VEC3 or VEC4")
            val count = accessor.int("count") ?: 0
            if (count < 0 || count > limits.maxVertices) throw ModelFormatException("Too many vertex colours ($count)")
            val type = accessor.int("componentType") ?: FLOAT
            val size = sizeOf(type)
            val view = viewFor(accessor, components, size, count) ?: return IntArray(count) { ModelMaterial.WHITE }
            return IntArray(count) { i ->
                val base = view.offset + i * view.stride
                // Colours are normalised by definition whatever the flag says.
                val r = component(view.bytes, base, type, true)
                val g = component(view.bytes, base + size, type, true)
                val b = component(view.bytes, base + 2 * size, type, true)
                val a = if (components == 4) component(view.bytes, base + 3 * size, type, true) else 1f
                linearToDisplay(r, g, b, a)
            }
        }

        private fun readIndices(index: Int): IntArray {
            val accessor = accessor(index)
            if (componentsOf(accessor.string("type")) != 1) throw ModelFormatException("Indices must be scalars")
            val count = accessor.int("count") ?: 0
            if (count < 0 || count > limits.maxTriangles * 3L) {
                throw ModelFormatException("The model has ${count / 3} triangles in one part; a phone can take ${limits.maxTriangles}")
            }
            val type = accessor.int("componentType") ?: throw ModelFormatException("Indices have no component type")
            if (type != UNSIGNED_BYTE && type != UNSIGNED_SHORT && type != UNSIGNED_INT) {
                throw ModelFormatException("Indices must be unsigned integers, not component type $type")
            }
            val size = sizeOf(type)
            val view = viewFor(accessor, 1, size, count) ?: return IntArray(count)
            return IntArray(count) { i ->
                val at = view.offset + i * view.stride
                when (type) {
                    UNSIGNED_BYTE -> view.bytes[at].toInt() and 0xFF
                    UNSIGNED_SHORT -> short(view.bytes, at) and 0xFFFF
                    else -> GlbContainer.int(view.bytes, at).also { if (it < 0) throw ModelFormatException("An index is larger than any model") }
                }
            }
        }

        private fun component(bytes: ByteArray, at: Int, type: Int, normalized: Boolean): Float = when (type) {
            FLOAT -> Float.fromBits(GlbContainer.int(bytes, at))
            UNSIGNED_BYTE -> (bytes[at].toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
            BYTE -> bytes[at].toInt().let { if (normalized) maxOf(it / 127f, -1f) else it.toFloat() }
            UNSIGNED_SHORT -> (short(bytes, at) and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
            SHORT -> short(bytes, at).toShort().toInt().let { if (normalized) maxOf(it / 32767f, -1f) else it.toFloat() }
            UNSIGNED_INT -> GlbContainer.int(bytes, at).toLong().and(0xFFFFFFFFL).toFloat()
            else -> throw ModelFormatException("Unknown component type $type")
        }

        private fun short(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

        // ---- buffers and images ---------------------------------------------

        @OptIn(ExperimentalEncodingApi::class)
        private fun bufferBytes(index: Int, buffer: JsonObject): ByteArray {
            val uri = buffer.string("uri")
            val bytes = when {
                uri == null -> if (index == 0) bin ?: throw ModelFormatException("Buffer 0 has no data: the GLB has no binary chunk")
                else throw ModelFormatException("Buffer $index has no data")
                uri.startsWith("data:") -> {
                    val comma = uri.indexOf(',')
                    if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) throw ModelFormatException("Buffer $index is a data URI that is not base64")
                    try {
                        Base64.decode(uri, comma + 1)
                    } catch (failure: IllegalArgumentException) {
                        throw ModelFormatException("Buffer $index is not valid base64", failure)
                    }
                }
                else -> throw ModelFormatException("Buffer $index points at an external file '$uri'. Export a single .glb instead.")
            }
            val declared = buffer.int("byteLength") ?: bytes.size
            if (declared > bytes.size) throw ModelFormatException("Buffer $index declares $declared bytes but holds ${bytes.size}")
            return bytes
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun imageTexture(image: JsonObject): Texture? {
            val decode = decodeImage ?: return null
            val bytes = image.int("bufferView")?.let { index ->
                val view = bufferViews.getOrNull(index) ?: return null
                val buffer = view.int("buffer")?.let { buffers.getOrNull(it)?.value } ?: return null
                val offset = view.int("byteOffset") ?: 0
                val length = view.int("byteLength") ?: return null
                if (offset < 0 || length < 0 || offset.toLong() + length > buffer.size) {
                    throw ModelFormatException("An image runs past the end of its buffer")
                }
                buffer.copyOfRange(offset, offset + length)
            } ?: image.string("uri")?.takeIf { it.startsWith("data:") }?.let { uri ->
                runCatching { Base64.decode(uri, uri.indexOf(',') + 1) }.getOrNull()
            } ?: return null
            // A texture that will not decode costs the model its picture, not the model.
            val texture = runCatching { decode(bytes) }.getOrNull() ?: return null
            return Pixels.downscale(texture, limits.maxTextureEdge)
        }
    }

    private companion object {
        const val TRIANGLES = 4
        const val TRIANGLE_STRIP = 5
        const val TRIANGLE_FAN = 6

        const val BYTE = 5120
        const val UNSIGNED_BYTE = 5121
        const val SHORT = 5122
        const val UNSIGNED_SHORT = 5123
        const val UNSIGNED_INT = 5125
        const val FLOAT = 5126

        /** Extensions a file may *require* and still be read correctly, because they change only what is ignored here. */
        val SUPPORTED_EXTENSIONS = setOf("KHR_materials_unlit", "KHR_texture_transform", "KHR_materials_emissive_strength")

        /**
         * glTF states colour factors and vertex colours in linear light; the
         * game's colours are display colours. Converted once here, so a factor
         * of 0.5 is the mid-grey it looks like rather than a near-black.
         */
        fun linearToDisplay(r: Float, g: Float, b: Float, a: Float): Int {
            fun ch(v: Float) = (v.coerceIn(0f, 1f).pow(1f / 2.2f) * 255f + 0.5f).toInt()
            return ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}

/** Where an accessor's elements are, bounds already checked. */
private class View(val bytes: ByteArray, val offset: Int, val stride: Int, val length: Int)

// ---- JSON helpers: every read is optional and typed, so a wrong type is a missing value. ----

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.ints(key: String): List<Int>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
internal fun JsonObject.floats(key: String): FloatArray? =
    (this[key] as? JsonArray)?.let { array -> FloatArray(array.size) { i -> (array[i] as? JsonPrimitive)?.floatOrNull ?: 0f } }
