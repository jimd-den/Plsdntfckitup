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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewCullingTest {

    private val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL, topColor = 0xFF4E8B45, sideColor = 0xFF6B4A2A)
    private val tree = BlockType("t:tree", "Tree", BlockMaterial.FOLIAGE, glyph = "T", isOpaque = false)

    /** A plain at z = 3 with a tree on every fourth column: a forest far wider than any screen. */
    private inner class Forest(private val onlyAtOrigin: Boolean = false) : World {
        override val registry = BlockRegistry.build(listOf(grass, tree))
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos): Chunk? = null
        override fun blockAt(pos: BlockPos): BlockType = when {
            pos.z in 0..3 -> grass
            pos.z == 4 && isTree(pos.x, pos.y) -> tree
            else -> BlockType.AIR
        }
        override fun blockIndexAt(pos: BlockPos): Int = registry.indexOf(blockAt(pos).id)
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int = if (isTree(x, y)) 4 else 3

        private fun isTree(x: Int, y: Int): Boolean =
            if (onlyAtOrigin) x == 0 && y == 0 else Math.floorMod(x, 4) == 0 && Math.floorMod(y, 4) == 0
        override fun isLoaded(pos: ChunkPos): Boolean = true
    }

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("dark").direction)
    private val camera = SceneCamera(Vec3(0.5f, 0.5f, 4f), aspect = 1080f / 2340f)

    @Test
    fun `the target is inside the view, and the ground behind the camera is not`() {
        val volume = ViewVolume.of(camera)
        assertTrue(volume.intersects(0f, 0f, 3f, 1f, 1f, 4f))
        // The eye sits to the south east; well past it is behind the lens.
        assertFalse(volume.intersects(80f, 80f, 3f, 81f, 81f, 4f))
        // Far to one side of a portrait screen.
        assertFalse(volume.intersects(-40f, 40f, 3f, -39f, 41f, 4f))
    }

    @Test
    fun `a box that straddles the edge of the view counts as visible`() {
        val volume = ViewVolume.of(camera)
        assertTrue(volume.intersects(-200f, -200f, 0f, 200f, 200f, 10f))
    }

    @Test
    fun `props out of sight are not built, and terrain out of sight is not drawn but stays resident`() {
        val perTree = SceneBuilder(director, TextureLibrary()).build(Forest(onlyAtOrigin = true), camera).cutout.vertexCount
        assertTrue(perTree > 0, "the tree in front of the camera was culled")
        val frame = SceneBuilder(director, TextureLibrary()).build(Forest(), camera)
        val trees = (-56..56 step 4).count() * (-56..56 step 4).count()
        val built = frame.cutout.vertexCount / perTree
        // A portrait screen sees a few per cent of the meshed square.
        assertTrue(built < trees / 5, "built $built of a forest of $trees trees; the view was not culled")
        assertTrue(built > 1, "the trees in front of the camera were culled too")
        assertTrue(frame.terrain.size < frame.residentTerrain.size, "every chunk was drawn")
        assertTrue(frame.residentTerrain.containsAll(frame.terrain))
    }

    @Test
    fun `turning away and back reuses the same resident chunks`() {
        val builder = SceneBuilder(director, TextureLibrary())
        val world = Forest()
        val first = builder.build(world, camera)
        val again = builder.build(world, camera.copy(target = Vec3(2.5f, 1.5f, 4f)))
        assertEquals(first.residentTerrain.size, again.residentTerrain.size)
        first.residentTerrain.zip(again.residentTerrain).forEach { (a, b) -> assertTrue(a === b) }
    }
}
