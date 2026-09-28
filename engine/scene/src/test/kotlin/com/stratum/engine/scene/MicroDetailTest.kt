package com.stratum.engine.scene

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Microvoxel detail near the camera, on a real world, through the scene builder. */
class MicroDetailTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
    private val config = WorldConfig(seed = 20260928L, simulationRadius = 2)
    private val generator = (StratumTerrain.create(content.terrainContext(config)) as com.stratum.engine.microbridge.HotTerrain).current
    private val director = StyleSheetArtDirector(StyleLexicon.interpret("house").direction)

    private fun world() = StreamingWorld(content.registry, generator, config).also { it.focusOn(BlockPos(200, 200, 0)) }

    private fun settle(builder: SceneBuilder, world: StreamingWorld, revision: Int = 0): SceneFrame {
        val camera = SceneCamera(target = Vec3(200.5f, 200.5f, world.surfaceAt(200, 200) + 1f))
        var frame = builder.build(world, camera, worldRevision = revision)
        var waited = 0
        while (builder.detailPending > 0 && waited < 20_000) { Thread.sleep(5); waited += 5; frame = builder.build(world, camera, worldRevision = revision) }
        return frame
    }

    /** Micro detail is untextured; block terrain is textured or tinted from the art director. */
    private fun flatVertices(frame: SceneFrame): Int = frame.terrain.sumOf { batch ->
        (0 until batch.vertexCount).count { batch.vertices[it * Vertex.STRIDE + Vertex.LAYER] == Vertex.FLAT }
    }

    @Test
    fun `chunks near the camera are drawn from microvoxels, the rest from blocks`() {
        val world = world()
        val builder = SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM), microTerrain = generator)
        val frame = settle(builder, world)
        assertTrue(builder.detailedChunksLastFrame in 1 until frame.terrain.size, "detailed ${builder.detailedChunksLastFrame} of ${frame.terrain.size}")
        val plain = settle(SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM)), world)
        assertTrue(flatVertices(frame) > flatVertices(plain), "no microvoxel geometry appeared")
        assertTrue(frame.terrain.sumOf { it.triangleCount } > plain.terrain.sumOf { it.triangleCount })
    }

    @Test
    fun `detail stays off when the settings turn it off`() {
        val world = world()
        val builder = SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM).copy(microDetailRadius = 0), microTerrain = generator)
        settle(builder, world)
        assertEquals(0, builder.detailedChunksLastFrame)
    }

    @Test
    fun `an edit is drawn at once, and the detail catches up with it`() {
        val world = world()
        val mesher = MicroDetailMesher(generator)
        val pos = ChunkPos.containing(200, 200)
        val before = assertNotNull(mesher.mesh(world, pos)).mesh.vertexCount
        val top = world.surfaceAt(200, 200)
        world.setBlock(BlockPos(200, 200, top), BlockRegistry.AIR_INDEX)
        world.setBlock(BlockPos(200, 200, top - 1), BlockRegistry.AIR_INDEX)
        val after = assertNotNull(mesher.mesh(world, pos)).mesh.vertexCount
        assertNotEquals(before, after, "digging a hole left the detail mesh unchanged")
    }

    @Test
    fun `a chunk changed out of recognition falls back to blocks`() {
        val world = world()
        val pos = ChunkPos.containing(200, 200)
        for (x in 0 until 16) for (y in 0 until 16) {
            val wx = pos.originX + x; val wy = pos.originY + y
            world.setBlock(BlockPos(wx, wy, world.surfaceAt(wx, wy)), BlockRegistry.AIR_INDEX)
        }
        assertNull(MicroDetailMesher(generator).mesh(world, pos))
    }
}
