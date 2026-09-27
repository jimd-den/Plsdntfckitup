package com.stratum.engine.model

import com.stratum.engine.model.GlbFixture.Companion.CUBE_INDICES
import com.stratum.engine.model.GlbFixture.Companion.CUBE_POSITIONS
import com.stratum.engine.model.gltf.GlbContainer
import com.stratum.engine.model.gltf.GltfLoader
import com.stratum.engine.model.gltf.ModelLimits
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GltfLoaderTest {

    private val loader = GltfLoader(GlbFixture::decode)

    @Test
    fun `a unit cube loads with its twelve triangles and its bounds`() {
        val mesh = loader.loadGlb(GlbFixture.cube())

        assertEquals(8, mesh.vertexCount)
        assertEquals(12, mesh.triangleCount)
        val box = assertNotNull(mesh.bounds())
        assertEquals(Bounds(0f, 0f, 0f, 1f, 1f, 1f), box)
        assertNull(mesh.normals, "the file had no normals and none are invented at load")
    }

    @Test
    fun `8, 16 and 32-bit indices read the same triangles`() {
        val meshes = listOf(8, 16, 32).map { bits ->
            val f = GlbFixture()
            val positions = f.floats(CUBE_POSITIONS, "VEC3")
            f.node(mesh = f.mesh(positions, f.indices(CUBE_INDICES, bits)))
            loader.loadGlb(f.build())
        }
        meshes.forEach { assertTrue(it.indices.contentEquals(CUBE_INDICES), "indices differ") }
    }

    @Test
    fun `node transforms are applied down the tree`() {
        val f = GlbFixture()
        val mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES))
        val child = f.node(mesh = mesh, translation = floatArrayOf(10f, 0f, 0f))
        f.node(scale = floatArrayOf(2f, 2f, 2f), children = listOf(child))

        val box = loader.loadGlb(f.build()).bounds()!!

        // Scale applies to the child's translation too: 2 * (10 + [0, 1]).
        assertEquals(20f, box.minX, 1e-4f)
        assertEquals(22f, box.maxX, 1e-4f)
        assertEquals(2f, box.maxY, 1e-4f)
    }

    @Test
    fun `a quarter turn about y moves x onto minus z`() {
        val f = GlbFixture()
        val mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES))
        val s = kotlin.math.sqrt(0.5f)
        f.node(mesh = mesh, rotation = floatArrayOf(0f, s, 0f, s))

        val box = loader.loadGlb(f.build()).bounds()!!

        assertEquals(-1f, box.minZ, 1e-4f)
        assertEquals(0f, box.maxZ, 1e-4f)
        assertEquals(0f, box.minX, 1e-4f)
        assertEquals(1f, box.maxX, 1e-4f)
    }

    @Test
    fun `a mirroring matrix keeps triangles facing out`() {
        val f = GlbFixture()
        val mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES))
        f.node(mesh = mesh, matrix = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        val loaded = loader.loadGlb(f.build())

        // The top face (z = 1) must still point up after x is mirrored.
        val top = (0 until loaded.triangleCount).first { t ->
            (0 until 3).all { loaded.positions[loaded.indices[t * 3 + it] * 3 + 2] == 1f }
        }
        val n = ModelNormalizer.faceNormal(loaded.positions, loaded.indices[top * 3], loaded.indices[top * 3 + 1], loaded.indices[top * 3 + 2])
        assertTrue(n[2] > 0.9f, "the lid faces ${n.toList()}")
    }

    @Test
    fun `strips and fans become triangle lists`() {
        val f = GlbFixture()
        val quad = f.floats(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f), "VEC3")
        f.node(mesh = f.mesh(quad, f.indices(intArrayOf(0, 1, 2, 3)), mode = 5))
        assertEquals(2, loader.loadGlb(f.build()).triangleCount)

        val g = GlbFixture()
        val fan = g.floats(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, -1f, 0.5f, 0f), "VEC3")
        g.node(mesh = g.mesh(fan, g.indices(intArrayOf(0, 1, 2, 3, 4)), mode = 6))
        assertEquals(3, loader.loadGlb(g.build()).triangleCount)
    }

    @Test
    fun `base colour factors are stated in linear light and read as display colour`() {
        val mesh = loader.loadGlb(GlbFixture.cube(material = { it.material(floatArrayOf(0.5f, 0f, 1f, 1f)) }))
        val color = mesh.colorAt(0, 1f / 3, 1f / 3, 1f / 3)

        assertEquals(255, Colors.b(color))
        assertEquals(0, Colors.g(color))
        // Linear 0.5 is about 186 on screen, not 128.
        assertTrue(Colors.r(color) in 180..190, "red was ${Colors.r(color)}")
    }

    @Test
    fun `an embedded texture colours the surface, multiplied by the factor`() {
        val uvs = FloatArray(16) { if (it % 2 == 0) 0.25f else 0.75f } // every corner samples the lower-left quadrant
        val bytes = GlbFixture.cube(
            material = { f ->
                val texture = f.pngTexture(4, 4) { x, y -> if (x < 2 && y >= 2) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }
                f.material(texture = texture)
            },
            uvs = uvs,
        )
        val color = loader.loadGlb(bytes).colorAt(3, 1f / 3, 1f / 3, 1f / 3)

        assertEquals(0xFF00FF00.toInt(), color)
    }

    @Test
    fun `without a decoder the texture is dropped and the base colour kept`() {
        val bytes = GlbFixture.cube(material = { f -> f.material(floatArrayOf(1f, 1f, 1f, 1f), f.pngTexture(2, 2) { _, _ -> 0xFF0000FF.toInt() }) })
        val mesh = GltfLoader(decodeImage = null).loadGlb(bytes)

        assertEquals(ModelMaterial.WHITE, mesh.colorAt(0, 1f, 0f, 0f))
    }

    @Test
    fun `vertex colours multiply the material`() {
        val f = GlbFixture()
        val positions = f.floats(CUBE_POSITIONS, "VEC3")
        val colors = f.byteColors(IntArray(8) { 0xFF0000FF.toInt() })
        f.node(mesh = f.mesh(positions, f.indices(CUBE_INDICES), colors = colors))

        val mesh = loader.loadGlb(f.build())

        assertNotNull(mesh.colors)
        assertEquals(255, Colors.b(mesh.colorAt(0, 1f, 0f, 0f)))
        assertEquals(0, Colors.r(mesh.colorAt(0, 1f, 0f, 0f)))
    }

    // ---- refusing bad files -------------------------------------------------

    @Test
    fun `a file that is not a GLB is refused by its first bytes`() {
        val html = "<html><body>502 Bad Gateway</body></html>".toByteArray()
        val failure = assertFailsWith<ModelFormatException> { loader.loadGlb(html) }
        assertTrue("glTF" in failure.message.orEmpty())
    }

    @Test
    fun `a truncated download says so`() {
        val whole = GlbFixture.cube()
        val failure = assertFailsWith<ModelFormatException> { loader.loadGlb(whole.copyOf(whole.size - 40)) }
        assertTrue("cut short" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a chunk that claims more than the file holds is refused`() {
        val bytes = GlbFixture.cube()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1_000_000)
        assertFailsWith<ModelFormatException> { GlbContainer.read(bytes) }
    }

    @Test
    fun `glTF 1 is refused by version`() {
        val bytes = GlbFixture.cube()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1)
        val failure = assertFailsWith<ModelFormatException> { loader.loadGlb(bytes) }
        assertTrue("version 1" in failure.message.orEmpty())
    }

    @Test
    fun `an accessor that runs past its buffer view is refused`() {
        val f = GlbFixture()
        val positions = f.floats(CUBE_POSITIONS, "VEC3")
        val lying = f.rawAccessor("""{"bufferView":0,"componentType":5126,"count":800,"type":"VEC3"}""")
        f.node(mesh = f.mesh(lying, f.indices(CUBE_INDICES)))
        check(positions == 0)
        val failure = assertFailsWith<ModelFormatException> { loader.loadGlb(f.build()) }
        assertTrue("does not fit" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an index past the last vertex is refused`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(intArrayOf(0, 1, 99))))
        assertFailsWith<ModelFormatException> { loader.loadGlb(f.build()) }
    }

    @Test
    fun `draco compression and external buffers are refused by name`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES)))
        f.extraRoot = ",\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]"
        val draco = assertFailsWith<ModelFormatException> { loader.loadGlb(f.build()) }
        assertTrue("KHR_draco_mesh_compression" in draco.message.orEmpty())

        val json = GlbFixture().run {
            node(mesh = mesh(floats(CUBE_POSITIONS, "VEC3"), indices(CUBE_INDICES)))
            json().replace("\"buffers\":[{", "\"buffers\":[{\"uri\":\"model.bin\",")
        }
        val external = assertFailsWith<ModelFormatException> { loader.load(json, null) }
        assertTrue("external file" in external.message.orEmpty())
    }

    @Test
    fun `models too large for a phone are refused before they are expanded`() {
        val limits = ModelLimits(maxVertices = 4)
        val failure = assertFailsWith<ModelFormatException> { GltfLoader(limits = limits).loadGlb(GlbFixture.cube()) }
        assertTrue("vertices" in failure.message.orEmpty())

        val small = ModelLimits(maxFileBytes = 64)
        assertFailsWith<ModelFormatException> { GltfLoader(limits = small).loadGlb(GlbFixture.cube()) }
    }

    @Test
    fun `broken JSON and a model with no triangles are refused`() {
        assertFailsWith<ModelFormatException> { loader.load("{ not json", null) }
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(floatArrayOf(0f, 0f, 0f), "VEC3"), mode = 0))
        assertFailsWith<ModelFormatException> { loader.loadGlb(f.build()) }
    }

    @Test
    fun `a node cycle is refused rather than followed forever`() {
        val f = GlbFixture()
        val mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES))
        f.node(mesh = mesh, children = listOf(1))
        f.node(children = listOf(0))
        f.sceneNodes = listOf(0)
        assertFailsWith<ModelFormatException> { loader.loadGlb(f.build()) }
    }

    @Test
    fun `a data uri buffer is read like the binary chunk`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES)))
        val glb = f.build()
        val container = GlbContainer.read(glb)
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        val encoded = kotlin.io.encoding.Base64.encode(container.bin!!)
        val json = container.json.replace("\"buffers\":[{", "\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,$encoded\",")

        assertEquals(12, loader.load(json, null).triangleCount)
    }
}
