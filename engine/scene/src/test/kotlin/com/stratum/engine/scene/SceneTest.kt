package com.stratum.engine.scene

import com.stratum.core.domain.art.ActorPresentation
import com.stratum.core.domain.art.ActorRole
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockShape
import com.stratum.core.domain.world.BlockShapes
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneTest {

    private val grass = BlockType("t:grass", "Grass", BlockMaterial.SOIL, topColor = 0xFF4E8B45, sideColor = 0xFF6B4A2A)
    private val wall = BlockType("t:wall", "Wall", shape = BlockShape.WALL, isOpaque = false)
    private val tree = BlockType("t:tree", "Tree", BlockMaterial.FOLIAGE, glyph = "T", isOpaque = false)
    private val paving = BlockType("t:paving", "Paving", BlockMaterial.STONE, shape = BlockShape.FLOOR, isSolid = false, isOpaque = false)

    /** A flat plain at z = 3, with optional extra blocks on top. */
    private inner class FlatWorld(private val extra: Map<BlockPos, BlockType> = emptyMap()) : World {
        override val registry = BlockRegistry.build(listOf(grass, wall, tree, paving))
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos): Chunk? = null
        override fun blockAt(pos: BlockPos): BlockType = extra[pos] ?: if (pos.z in 0..3) grass else BlockType.AIR
        override fun blockIndexAt(pos: BlockPos): Int = registry.indexOf(blockAt(pos).id)
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int = (3..8).lastOrNull { extra[BlockPos(x, y, it)] != null } ?: 3
        override fun isLoaded(pos: ChunkPos): Boolean = true
    }

    private val director = StyleSheetArtDirector(StyleLexicon.interpret("dark").direction)

    @Test
    fun `a flat plain meshes to one visible face per column`() {
        // Every buried face is culled; only the lids remain.
        val result = TerrainMesher(director, TextureLibrary()).mesh(FlatWorld(), 0, 9, 0, 9)
        val quads = result.mesh.triangleCount / 2
        assertTrue(quads in 100..140, "expected about 100 lids plus edges, meshed $quads quads")
    }

    @Test
    fun `a thin wall is thinner than a block in the mesh`() {
        val world = FlatWorld(mapOf(BlockPos(5, 5, 4) to wall))
        val mesh = TerrainMesher(director, TextureLibrary()).mesh(world, 5, 5, 5, 5).mesh
        val ys = (0 until mesh.vertexCount).map { mesh.vertices[it * Vertex.STRIDE + 1] }.filter { it > 5f && it < 6f }
        assertTrue(ys.isNotEmpty(), "the wall has vertices inside its cell")
        val thickness = ys.max() - ys.min()
        assertEquals(1f / 3f, thickness, 0.01f)
    }

    @Test
    fun `a floor tile is a thin slab on the ground, joined to its neighbours`() {
        val world = FlatWorld(mapOf(BlockPos(5, 5, 4) to paving, BlockPos(6, 5, 4) to paving))
        val mesh = TerrainMesher(director, TextureLibrary()).mesh(world, 5, 6, 5, 5).mesh
        val zs = (0 until mesh.vertexCount).map { mesh.vertices[it * Vertex.STRIDE + 2] }.filter { it > 4f }
        assertTrue(zs.isNotEmpty(), "the paving has a lid above the ground")
        assertEquals(4f + BlockShapes.FLOOR_THICKNESS, zs.max(), 0.001f)
        val seam = (0 until mesh.vertexCount).filter {
            abs(mesh.vertices[it * Vertex.STRIDE + Vertex.NX]) == 1f && mesh.vertices[it * Vertex.STRIDE] == 6f
        }
        assertTrue(seam.isEmpty(), "the edge two tiles share is not drawn")
    }

    @Test
    fun `ground tops carry their sister paintings and walls do not`() {
        val textures = TextureLibrary()
        val key = assertNotNull(director.surfaceFor(grass, com.stratum.core.domain.art.SurfaceFace.TOP, null).texture)
        val pixel = Texture(1, 1, intArrayOf(-1))
        textures.put(key, pixel)
        val a = textures.put("$key#1", pixel)
        val b = textures.put("$key#2", pixel)
        val mesh = TerrainMesher(director, textures).mesh(FlatWorld(), 0, 0, 0, 0).mesh
        val lids = (0 until mesh.vertexCount).filter { mesh.vertices[it * Vertex.STRIDE + 5] == 1f }
        assertTrue(lids.isNotEmpty())
        lids.forEach {
            assertEquals(a.toFloat(), mesh.vertices[it * Vertex.STRIDE + Vertex.VARIANT_A])
            assertEquals(b.toFloat(), mesh.vertices[it * Vertex.STRIDE + Vertex.VARIANT_B])
        }
        assertEquals(listOf(0, a, b), textures.variantsOf(key).toList())
    }

    @Test
    fun `ground litter scatters over the region's own surface, only where it was painted`() {
        val biome = com.stratum.core.domain.content.BiomeDefinition(
            "t:plain", "Plain", "", surfaceBlockId = grass.id, subsurfaceBlockId = grass.id, bedrockFillerBlockId = grass.id,
        )
        val bare = TerrainMesher(director, TextureLibrary()) { _, _ -> biome }.mesh(FlatWorld(), 0, 29, 0, 29)
        assertTrue(bare.details.isEmpty(), "no painted litter, no litter")
        val textures = TextureLibrary().apply { put("detail:t:plain", Texture(2, 1, IntArray(2) { -1 })) }
        val littered = TerrainMesher(director, textures) { _, _ -> biome }.mesh(FlatWorld(), 0, 29, 0, 29)
        val share = littered.details.size / 900f
        assertTrue(share in 0.02f..0.1f, "a little litter, not a carpet of it: got $share")
        assertTrue(littered.details.all { it.z == 4f })
    }

    @Test
    fun `props become sprites rather than cubes`() {
        val world = FlatWorld(mapOf(BlockPos(2, 2, 4) to tree))
        val result = TerrainMesher(director, TextureLibrary()).mesh(world, 0, 4, 0, 4)
        assertEquals(1, result.props.size)
    }

    @Test
    fun `the camera looks down at its target from the south east`() {
        val camera = SceneCamera(Vec3(10f, 10f, 3f))
        val eye = camera.eye
        assertTrue(eye.x > 10f && eye.y > 10f && eye.z > 3f)
        val pitch = Math.toDegrees(kotlin.math.asin(((eye - camera.target).normalized().z).toDouble()))
        assertEquals(camera.pitch.toDouble(), pitch, 0.5)
    }

    @Test
    fun `the centre of the screen picks the block under the target`() {
        val camera = SceneCamera(Vec3(5.5f, 5.5f, 4f))
        val hit = assertNotNull(ScenePicker.pick(FlatWorld(), camera, 640f, 360f, 1280f, 720f))
        assertEquals(BlockPos(5, 5, 3), hit.block)
        assertEquals(1, hit.faceZ, "came in through the top face")
    }

    @Test
    fun `a wall in front of the ground is hit first`() {
        val camera = SceneCamera(Vec3(5.5f, 5.5f, 4f))
        val (x, y) = assertNotNull(ScenePicker.project(camera, 6.5f, 6.5f, 4.9f, 1280f, 720f))
        val world = FlatWorld(mapOf(BlockPos(6, 6, 4) to wall))
        assertEquals(BlockPos(6, 6, 4), ScenePicker.pick(world, camera, x, y, 1280f, 720f)?.block)
    }

    @Test
    fun `picking inverts projection`() {
        val camera = SceneCamera(Vec3(0f, 0f, 0f))
        val (sx, sy) = assertNotNull(ScenePicker.project(camera, 3f, -2f, 1f, 1000f, 1000f))
        val (origin, dir) = assertNotNull(ScenePicker.ray(camera, sx, sy, 1000f, 1000f))
        // The ray passes through the original point.
        val t = (Vec3(3f, -2f, 1f) - origin).dot(dir)
        val closest = origin + dir * t
        assertTrue((closest - Vec3(3f, -2f, 1f)).length() < 0.05f)
    }

    @Test
    fun `terrain is meshed once and reused until the world changes`() {
        val builder = SceneBuilder(director, TextureLibrary())
        val world = FlatWorld()
        // Both inside the same cache region, so walking a few steps is free.
        val camera = SceneCamera(Vec3(7f, 7f, 4f))
        val first = builder.build(world, camera, worldRevision = 1)
        val again = builder.build(world, camera.copy(target = Vec3(9.5f, 8f, 4f)), worldRevision = 1)
        assertSame(first.opaque.first(), again.opaque.first())
        val edited = builder.build(world, camera, worldRevision = 2)
        assertTrue(first.opaque.first() !== edited.opaque.first())
    }

    @Test
    fun `actors get a body, a contact shadow, and an elite a ring`() {
        val builder = SceneBuilder(director, TextureLibrary())
        val frame = builder.build(
            FlatWorld(),
            SceneCamera(Vec3(5f, 5f, 4f)),
            actors = listOf(
                SceneActor(5f, 5f, 4f, ActorPresentation("p", ActorRole.PLAYER)),
                SceneActor(7f, 5f, 4f, ActorPresentation("e", ActorRole.ENEMY, com.stratum.core.domain.actor.EnemyRank.ELITE)),
            ),
        )
        assertEquals(2, frame.opaque.size, "terrain plus actor bodies")
        // Two shadows and one ring.
        assertEquals(3, frame.decals.triangleCount / 2)
    }

    @Test
    fun `a prop in front of the player fades and one behind does not`() {
        val camera = SceneCamera(Vec3(5.5f, 5.5f, 4f))
        // The camera is to the south east, so +x,+y is in front of the player.
        val world = FlatWorld(mapOf(BlockPos(6, 6, 4) to tree, BlockPos(3, 3, 4) to tree))
        val frame = SceneBuilder(director, TextureLibrary()).build(
            world, camera, actors = listOf(SceneActor(5.5f, 5.5f, 4f, ActorPresentation("p", ActorRole.PLAYER))),
        )
        val opacities = (0 until frame.cutout.vertexCount).map { frame.cutout.vertices[it * Vertex.STRIDE + Vertex.AO] }.toSet()
        assertTrue(opacities.any { it < 0.5f }, "the tree in front was not faded")
        assertTrue(opacities.any { abs(it - 1f) < 1e-4f }, "the tree behind was faded too")
    }

    @Test
    fun `an actor drawn by the platform keeps its footing but loses its stand-in body`() {
        val frame = SceneBuilder(director, TextureLibrary()).build(
            FlatWorld(),
            SceneCamera(Vec3(5f, 5f, 4f)),
            actors = listOf(SceneActor(5f, 5f, 4f, ActorPresentation("p", ActorRole.PLAYER), drawnElsewhere = true)),
        )
        assertEquals(1, frame.opaque.size, "only terrain: no body under the sprite")
        assertEquals(1, frame.decals.triangleCount / 2, "the contact shadow stays")
    }

    @Test
    fun `a forged character sprite stands in for the body and turns to face its way`() {
        val library = TextureLibrary().apply { put("actor:hero", Texture(2, 4, IntArray(8) { -1 })) }
        val camera = SceneCamera(Vec3(5f, 5f, 4f))
        fun us(facingX: Float, facingY: Float): List<Float> {
            val frame = SceneBuilder(director, library).build(
                FlatWorld(), camera,
                actors = listOf(SceneActor(5f, 5f, 4f, ActorPresentation("p", ActorRole.PLAYER), facingX, facingY, spriteKey = "actor:hero")),
            )
            assertEquals(1, frame.opaque.size, "a sprite replaces the stand-in body")
            return (0 until 4).map { frame.cutout.vertices[it * Vertex.STRIDE + Vertex.U] }
        }
        // Screen-right and screen-left on this camera are the two diagonals.
        val right = camera.right
        assertEquals(listOf(0f, 1f, 1f, 0f), us(right.x, right.y))
        assertEquals(listOf(1f, 0f, 0f, 1f), us(-right.x, -right.y))
    }

    @Test
    fun `lighting puts the sun where the 2D style said it was`() {
        // The house style lights from the upper left of the screen, which on
        // the ground is the west.
        val lighting = StyleSheetArtDirector().lightingFor(null, com.stratum.core.domain.art.WorldTime())
        assertTrue(lighting.sunX < -0.3f, "sun should come from the west, was ${lighting.sunX}")
        assertTrue(abs(lighting.sunY) < 0.2f)
        assertTrue(lighting.sunZ > 0.5f)
    }
}
