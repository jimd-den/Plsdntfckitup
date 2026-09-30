package com.stratum.engine.scene

import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PropModelTest {

    private val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL)
    private val idol = BlockType("t:idol", "Idol", BlockMaterial.STONE, glyph = "I", isOpaque = false)

    private val world = object : World {
        override val registry = BlockRegistry.build(listOf(grass, idol))
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos): Chunk? = null
        override fun blockAt(pos: BlockPos): BlockType = when {
            pos == BlockPos(5, 5, 4) -> idol
            pos.z in 0..3 -> grass
            else -> BlockType.AIR
        }
        override fun blockIndexAt(pos: BlockPos): Int = registry.indexOf(blockAt(pos).id)
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int = if (x == 5 && y == 5) 4 else 3
        override fun isLoaded(pos: ChunkPos): Boolean = true
    }

    /** One upward-facing red triangle, a block across, standing a block tall at its tip. */
    private val model = PropModel(
        positions = floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0f, 0.5f, 1f),
        normals = floatArrayOf(0f, -0.7f, 0.7f),
        colors = intArrayOf(0xFFFF0000.toInt()),
    )

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("dark").direction)

    @Test
    fun `a prop with a model is drawn as lit geometry instead of a sprite`() {
        val builder = SceneBuilder(director, TextureLibrary(), propModels = { if (it == "t:idol") model else null })
        val frame = builder.build(world, SceneCamera(Vec3(5f, 5f, 4f)))

        val batch = frame.models.single()
        assertEquals(1, batch.triangleCount)
        // Standing on its cell: base on top of the ground at z = 4, tip a block higher.
        val zs = (0 until 3).map { batch.vertices[it * Vertex.STRIDE + 2] }
        assertEquals(4f, zs.min(), 1e-4f)
        assertEquals(5f, zs.max(), 1e-4f)
        assertEquals(1f, batch.vertices[Vertex.R], 1e-4f)
        assertEquals(Vertex.FLAT, batch.vertices[Vertex.LAYER])
        assertTrue(frame.opaque.contains(batch), "models go through the lit, shadowed path")
        assertEquals(0, frame.cutout.triangleCount, "and no sprite is drawn for the same prop")

        val again = builder.build(world, SceneCamera(Vec3(5.2f, 5f, 4f)))
        assertSame(batch, again.models.single(), "the batch is kept until the terrain changes")
    }

    @Test
    fun `quarter turns keep the model on its cell`() {
        val out = MeshBuilder(MaterialKind.OPAQUE)
        model.emit(out, 10f, 10f, 0f, quarterTurns = 1)
        val batch = out.build()
        val xs = (0 until 3).map { batch.vertices[it * Vertex.STRIDE] }
        assertTrue(xs.all { it in 9.49f..10.51f })
        assertEquals(1f, model.height)
    }
}
