package com.stratum.engine.model

import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.engine.model.GlbFixture.Companion.CUBE_INDICES
import com.stratum.engine.model.GlbFixture.Companion.CUBE_POSITIONS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelPipelineTest {

    private val pipeline = ModelPipeline(GlbFixture::decode)

    /** A box twice as tall as it is wide, along glTF's up axis (y). */
    private fun tallBox(color: FloatArray = floatArrayOf(1f, 0f, 0f, 1f)): ModelMesh {
        val f = GlbFixture()
        val positions = f.floats(CUBE_POSITIONS, "VEC3")
        val mesh = f.mesh(positions, f.indices(CUBE_INDICES), material = f.material(color))
        f.node(mesh = mesh, scale = floatArrayOf(1f, 2f, 1f), translation = floatArrayOf(5f, 7f, -3f))
        return pipeline.load(f.build()).getOrThrow()
    }

    @Test
    fun `normalising stands a y-up model upright, centred and on the ground`() {
        val normalized = pipeline.normalize(tallBox(), heightBlocks = 3f)
        val box = normalized.bounds()!!

        assertEquals(0f, box.minZ, 1e-4f)
        assertEquals(3f, box.maxZ, 1e-4f)
        assertEquals(0f, (box.minX + box.maxX) / 2f, 1e-4f)
        assertEquals(0f, (box.minY + box.maxY) / 2f, 1e-4f)
        assertEquals(1.5f, box.sizeX, 1e-4f)
        assertEquals(1.5f, box.sizeY, 1e-4f)
        assertTrue(normalized.normals != null, "normals are computed for a file that had none")
    }

    @Test
    fun `a model lying flat is held to its footprint rather than blown up to height`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES)), scale = floatArrayOf(10f, 0.1f, 10f))
        val box = pipeline.normalize(pipeline.load(f.build()).getOrThrow(), heightBlocks = 2f).bounds()!!

        assertTrue(box.sizeX <= 4f + 1e-4f && box.sizeY <= 4f + 1e-4f, "footprint was ${box.sizeX} x ${box.sizeY}")
    }

    @Test
    fun `a flat point cloud with no size is refused`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(FloatArray(9), "VEC3"), f.indices(intArrayOf(0, 1, 2))))
        val loaded = pipeline.load(f.build()).getOrThrow()
        val failure = assertFailsWith<ModelFormatException> { pipeline.normalize(loaded, 2f) }
        assertTrue("no size" in failure.message.orEmpty())
    }

    @Test
    fun `a prop keeps the model's colour and its faces point out`() {
        val prop = pipeline.prop(pipeline.normalize(tallBox(), 2f))

        assertEquals(12, prop.triangleCount)
        assertTrue(prop.colors.all { Colors.r(it) == 255 && Colors.g(it) == 0 }, "the red box is not red")
        // Every face normal points away from the centre of the box.
        for (t in 0 until prop.triangleCount) {
            val cx = (prop.positions[t * 9] + prop.positions[t * 9 + 3] + prop.positions[t * 9 + 6]) / 3f
            val cy = (prop.positions[t * 9 + 1] + prop.positions[t * 9 + 4] + prop.positions[t * 9 + 7]) / 3f
            val cz = (prop.positions[t * 9 + 2] + prop.positions[t * 9 + 5] + prop.positions[t * 9 + 8]) / 3f - 1f
            val dot = cx * prop.normals[t * 3] + cy * prop.normals[t * 3 + 1] + cz * prop.normals[t * 3 + 2]
            assertTrue(dot > 0f, "triangle $t faces inward")
        }
        assertEquals(2f, prop.height, 1e-4f)
    }

    /** A finely tessellated sphere: many small triangles, like a generated mesh. */
    private fun sphere(rings: Int, segments: Int): ModelMesh {
        val positions = ArrayList<Float>()
        for (r in 0..rings) for (s in 0..segments) {
            val theta = Math.PI * r / rings; val phi = 2 * Math.PI * s / segments
            positions += (kotlin.math.sin(theta) * kotlin.math.cos(phi)).toFloat()
            positions += kotlin.math.cos(theta).toFloat()
            positions += (kotlin.math.sin(theta) * kotlin.math.sin(phi)).toFloat()
        }
        val indices = ArrayList<Int>()
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * (segments + 1) + s; val b = a + segments + 1
            indices += listOf(a, b, a + 1, a + 1, b, b + 1)
        }
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(positions.toFloatArray(), "VEC3"), f.indices(indices.toIntArray(), 32)))
        return pipeline.load(f.build()).getOrThrow()
    }

    @Test
    fun `decimation brings a dense mesh under budget and keeps its shape`() {
        val dense = pipeline.normalize(sphere(60, 80), 2f)
        assertTrue(dense.triangleCount > 9000)

        val prop = pipeline.prop(dense, maxTriangles = 500)

        assertTrue(prop.triangleCount in 100..500, "decimated to ${prop.triangleCount}")
        assertEquals(2f, prop.height, 0.15f)
    }

    @Test
    fun `a mesh already under budget is left alone`() {
        val mesh = ColoredMesh.of(pipeline.normalize(tallBox(), 1f))
        assertTrue(ModelDecimator.decimate(mesh, 100) === mesh)
    }

    @Test
    fun `a cube three blocks tall voxelises solid, and hollow when asked`() {
        val f = GlbFixture()
        f.node(mesh = f.mesh(f.floats(CUBE_POSITIONS, "VEC3"), f.indices(CUBE_INDICES), material = f.material(floatArrayOf(0f, 0f, 1f, 1f))))
        // A little under three, so the faces fall inside the outer cells rather than on the next boundary.
        val centred = pipeline.normalize(pipeline.load(f.build()).getOrThrow(), 2.98f)
        // Moved to sit inside whole cells; centred on the origin it would straddle four.
        val normalized = centred.withPositions(FloatArray(centred.positions.size) { i -> centred.positions[i] + if (i % 3 == 2) 0f else 1.5f })

        val solid = pipeline.voxels(normalized)
        assertEquals(Triple(3, 3, 3), Triple(solid.sizeX, solid.sizeY, solid.sizeZ))
        assertEquals(27, solid.filledCount)
        assertTrue((0 until 27).all { Colors.b(solid.colors[it]) == 255 }, "every cell takes the blue surface")

        val hollow = pipeline.voxels(normalized, solid = false)
        assertEquals(26, hollow.filledCount, "only the middle cell is inside")
    }

    @Test
    fun `voxels map to the nearest block and the blueprint raises into the world`() {
        val red = BlockType("t:brick", "Brick", topColor = 0xFFC03020, sideColor = 0xFFA02818)
        val grey = BlockType("t:stone", "Stone", topColor = 0xFF8A8A8A, sideColor = 0xFF6A6A6A)
        val torch = BlockType("t:torch", "Torch", lightEmission = 12, topColor = 0xFFFF2010)
        val palette = BlockPalette.of(listOf(red, grey, torch, BlockType.AIR))!!
        assertEquals(listOf("t:brick", "t:stone"), palette.entries.map { it.blockId }, "lights and air are not building blocks")

        val blueprint = pipeline.blueprint(pipeline.normalize(tallBox(), 2f), palette, "bp:red", "Red box")
        assertEquals(listOf("t:brick"), blueprint.palette)
        assertTrue(blueprint.filledCount > 0)

        val world = FakeWorld(BlockRegistry.build(listOf(red, grey)))
        val stone = world.registry.indexOf("t:stone")
        world.blocks[BlockPos(10, 10, 5)] = stone // stone already there, not replaced
        val placed = blueprint.raise(world, BlockPos(10, 10, 5))

        assertEquals(blueprint.filledCount - 1, placed)
        assertEquals(stone, world.blocks[BlockPos(10, 10, 5)])
        assertTrue(world.blocks.keys.all { it.z >= 5 }, "nothing is buried below the ground it stands on")
        assertEquals(blueprint.bill().values.sum(), blueprint.filledCount)
    }

    @Test
    fun `a blueprint survives nothing it does not own`() {
        assertFailsWith<IllegalArgumentException> { VoxelBlueprint("x", "x", 2, 1, 1, listOf("a"), intArrayOf(1, 2)) }
        assertFailsWith<IllegalArgumentException> { VoxelBlueprint("x", "x", 2, 1, 1, listOf("a"), intArrayOf(1)) }
    }

    @Test
    fun `a model too large to be a structure is refused`() {
        val normalized = pipeline.normalize(tallBox(), 20f)
        assertFailsWith<ModelFormatException> { Voxelizer.voxelize(normalized, cellsPerBlock = 8) }
    }

    @Test
    fun `sprites are baked in eight directions at one scale`() {
        val prop = pipeline.prop(pipeline.normalize(tallBox(), 2f))
        val sprites = pipeline.sprites(prop, size = 64)

        assertEquals(BakeDirection.entries.toSet(), sprites.frames.keys)
        sprites.frames.values.forEach { frame ->
            assertEquals(64, frame.width)
            assertTrue(frame.argb.count { Colors.a(it) > 200 } > 200, "a frame is nearly empty")
            // The corners are background.
            assertEquals(0, Colors.a(frame.argb[0]))
        }
        // The front frame is red, lit: some faces brighter than others.
        val reds = sprites.front.argb.filter { Colors.a(it) == 255 && Colors.g(it) < 40 }.map { Colors.r(it) }.toSet()
        assertTrue(reds.size >= 2, "all faces lit the same: $reds")
        assertEquals(64 * 8, sprites.strip().width)
    }

    @Test
    fun `a tall model bakes to a tall sprite`() {
        val sprite = pipeline.sprites(pipeline.prop(pipeline.normalize(tallBox(), 3f)), size = 64).front
        var minX = 64; var maxX = -1; var minY = 64; var maxY = -1
        for (y in 0 until 64) for (x in 0 until 64) if (Colors.a(sprite.argb[y * 64 + x]) > 200) {
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        assertTrue(maxY - minY > maxX - minX, "sprite is ${maxX - minX} wide and ${maxY - minY} tall")
        assertTrue(abs((minX + maxX) / 2 - 32) <= 2, "the model is centred")
    }

    @Test
    fun `an OBJ file loads with vertex colours and negative indices`() {
        val obj = """
            # a quad
            v 0 0 0 1 0 0
            v 1 0 0 1 0 0
            v 1 1 0 1 0 0
            v 0 1 0 1 0 0
            f 1 2 3 4
            f -4 -2 -1
        """.trimIndent().toByteArray()
        val mesh = pipeline.load(obj).getOrThrow()

        assertEquals(3, mesh.triangleCount)
        assertEquals(255, Colors.r(mesh.colorAt(0, 1f, 0f, 0f)))
        assertTrue(pipeline.load("v 0 0 0\nf 1 2 3".toByteArray()).isFailure, "a face on missing vertices")
    }

    @Test
    fun `anything else is refused with a sentence`() {
        val failure = pipeline.load(ByteArray(64) { 7 }).exceptionOrNull()
        assertTrue(failure is ModelFormatException && "GLB" in failure.message.orEmpty())
    }

    private class FakeWorld(override val registry: BlockRegistry) : MutableWorld {
        val blocks = HashMap<BlockPos, Int>()
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos): Chunk? = null
        override fun blockAt(pos: BlockPos): BlockType = registry.typeOf(blocks[pos] ?: 0)
        override fun blockIndexAt(pos: BlockPos): Int = blocks[pos] ?: 0
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int = -1
        override fun isLoaded(pos: ChunkPos): Boolean = true
        override fun setBlock(pos: BlockPos, index: Int): Boolean = blocks.put(pos, index) != index
        override fun loadChunk(pos: ChunkPos): Chunk = error("not used")
        override fun unloadChunk(pos: ChunkPos) = Unit
    }
}
