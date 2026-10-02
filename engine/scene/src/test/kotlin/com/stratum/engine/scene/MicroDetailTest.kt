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
    fun `with the far ring off, chunks near the camera are drawn from microvoxels, the rest from blocks`() {
        val world = world()
        val builder = SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM).copy(microFarRadius = 0, splats = SplatMode.MESH), microTerrain = generator)
        val frame = settle(builder, world)
        assertTrue(builder.detailedChunksLastFrame in 1 until frame.terrain.size, "detailed ${builder.detailedChunksLastFrame} of ${frame.terrain.size}")
        val plain = settle(SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM)), world)
        assertTrue(flatVertices(frame) > flatVertices(plain), "no microvoxel geometry appeared")
        assertTrue(frame.terrain.sumOf { it.triangleCount } > plain.terrain.sumOf { it.triangleCount })
    }

    @Test
    fun `detail stays off when the settings turn it off`() {
        val world = world()
        val builder = SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.MEDIUM).copy(microDetailRadius = 0, microFarRadius = 0), microTerrain = generator)
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

    @Test
    fun `half-block detail draws the same ground with far fewer quads`() {
        val world = world()
        val mesher = MicroDetailMesher(generator)
        val pos = ChunkPos.containing(200, 200)
        val full = assertNotNull(mesher.mesh(world, pos, 1)).mesh
        val half = assertNotNull(mesher.mesh(world, pos, 2)).mesh
        kotlin.test.assertTrue(half.triangleCount > 0, "the half-block mesh is empty")
        kotlin.test.assertTrue(half.triangleCount * 2 < full.triangleCount, "half-block ${half.triangleCount} triangles against ${full.triangleCount}")
    }

    /** A cache drawing every chunk within 20 blocks in full detail, the rest as blocks. */
    private fun detailCache(editLayersPerCall: Int = 0) =
        ChunkMeshCache(TerrainMesher(director, TextureLibrary()), MicroDetailMesher(generator), detailRadius = 20, editLayersPerCall = editLayersPerCall)

    private fun settle(cache: ChunkMeshCache, world: StreamingWorld, revision: Int): List<TerrainMesher.Result> {
        var out = cache.around(world, 200, 200, 24, revision)
        var waited = 0
        while (cache.detailPending > 0 && waited < 20_000) { Thread.sleep(5); waited += 5; out = cache.around(world, 200, 200, 24, revision) }
        return out
    }

    private fun revision(world: StreamingWorld) = world.loadedChunks.sumOf { it.revision }

    /** Every vertex of every mesh, for comparing two caches' pictures of the same world. */
    private fun vertices(results: List<TerrainMesher.Result>): Int = results.sumOf { it.mesh.vertexCount }

    @Test
    fun `remeshing only the layers an edit touched gives the same mesh as remeshing the chunk`() {
        val world = world()
        val mesher = MicroDetailMesher(generator)
        val pos = ChunkPos.containing(200, 200)
        val before = BlockSnapshot.of(world, pos)
        val layers = assertNotNull(mesher.meshLayers(before, pos))
        assertEquals(mesher.layers, layers.size)
        val top = world.surfaceAt(203, 205)
        world.setBlock(BlockPos(203, 205, top + 1), content.registry.indexOf("igbo:red_earth"))
        val after = BlockSnapshot.of(world, pos)
        val dirty = assertNotNull(after.changedLayers(before, mesher.layers, mesher.blocksPerLayer))
        assertTrue(dirty.count { it } in 1..2, "one block dirtied ${dirty.toList()}")
        val patched = assertNotNull(mesher.meshLayers(after, pos, only = dirty, previous = layers))
        val whole = assertNotNull(mesher.mesh(after, pos))
        assertEquals(whole.mesh.vertexCount, vertices(patched))
        assertEquals(whole.mesh.triangleCount, patched.sumOf { it.mesh.triangleCount })
        (0 until mesher.layers).filterNot { dirty[it] }.forEach { assertTrue(patched[it] === layers[it], "untouched layer $it was remade") }
    }

    @Test
    fun `with a budget for it, each edit is remeshed in detail on its own frame`() {
        val world = world()
        val cache = detailCache(editLayersPerCall = ChunkMeshCache.EDIT_LAYERS_PER_CALL)
        settle(cache, world, revision(world))
        val detailed = cache.detailedLastCall
        assertTrue(detailed > 0)
        val block = content.registry.indexOf("igbo:red_earth")
        // A wall crossing the border between chunks 12 and 13 (x 192..207 | 208..223), laid one block a frame.
        for (i in 0 until 24) {
            val x = 202 + i % 12; val y = 200
            val z = world.surfaceAt(x, y) + 1
            world.setBlock(BlockPos(x, y, z), block)
            val generation = cache.generation
            cache.around(world, 200, 200, 24, revision(world))
            assertEquals(0, cache.poppedLastCall, "block $i popped a chunk back to blocks")
            assertEquals(detailed, cache.detailedLastCall, "block $i")
            assertTrue(cache.generation > generation, "block $i was not drawn on the frame it was laid")
            assertTrue(cache.editLayersLastCall in 1..ChunkMeshCache.EDIT_LAYERS_PER_CALL, "block $i remeshed ${cache.editLayersLastCall} layers")
        }
    }

    @Test
    fun `placing blocks never drops a detailed chunk back to blocks, and the edit worker catches up within a few frames`() {
        val world = world()
        val cache = detailCache()
        settle(cache, world, revision(world))
        val block = content.registry.indexOf("igbo:red_earth")
        val cell = BlockPos(203, 203, world.surfaceAt(203, 203) + 1)
        world.setBlock(cell, block)
        cache.around(world, 200, 200, 24, revision(world))
        assertEquals(0, cache.poppedLastCall)
        assertEquals(0, cache.meshedLastCall, "nothing was block-meshed for an edit to a detailed chunk")
        assertTrue(cell.chunkPos in cache.awaiting, "the edited chunk is not marked as catching up")
        var frames = 0
        while (cell.chunkPos in cache.awaiting && frames < 400) { Thread.sleep(5); frames++; cache.around(world, 200, 200, 24, revision(world)) }
        assertTrue(cell.chunkPos !in cache.awaiting, "the edit never landed")
        assertEquals(0, cache.poppedLastCall)
    }

    @Test
    fun `ground dug every frame by a fight keeps showing newer detail, and ends where a fresh mesh does`() {
        val world = world()
        val cache = detailCache()
        settle(cache, world, revision(world))
        val block = content.registry.indexOf("igbo:red_earth")
        // Craters and walls landing in one chunk, a change every frame for two seconds of frames.
        val landed = ArrayList<Long>()
        var last = cache.generation
        for (frame in 0 until 240) {
            val x = 194 + frame % 12; val y = 194 + (frame / 12) % 12
            val top = world.surfaceAt(x, y)
            world.setBlock(BlockPos(x, y, if (frame % 3 == 0) top else top + 1), if (frame % 3 == 0) BlockRegistry.AIR_INDEX else block)
            cache.around(world, 200, 200, 24, revision(world))
            assertEquals(0, cache.poppedLastCall, "frame $frame dropped a chunk to blocks")
            if (cache.generation != last) { landed += cache.generation; last = cache.generation }
            Thread.sleep(4)
        }
        // Each finished mesh goes up even though the ground has moved on since; the old way threw it away
        // and nothing new was drawn until the digging stopped.
        println("newer meshes drawn while digging: ${landed.size} in 240 frames")
        assertTrue(landed.size >= 10, "only ${landed.size} newer meshes were drawn while the ground kept changing")
        val settled = settle(cache, world, revision(world))
        val fresh = settle(detailCache(), world, revision(world))
        assertEquals(vertices(fresh), vertices(settled), "the dug cache and a fresh one disagree about the world")
    }

    @Test
    fun `rapid place, undo and redo at chunk borders ends where a fresh mesh of the same world does`() {
        val world = world()
        val cache = detailCache()
        settle(cache, world, revision(world))
        val block = content.registry.indexOf("igbo:red_earth")
        val placed = ArrayList<BlockPos>()
        // Corners and sides of the chunk holding (200, 200): 192..207 each way.
        val cells = listOf(192 to 192, 207 to 192, 192 to 207, 207 to 207, 200 to 192, 192 to 200, 207 to 200, 200 to 207, 208 to 200, 191 to 200)
        repeat(3) { round ->
            for ((x, y) in cells) {
                val p = BlockPos(x, y, world.surfaceAt(x, y) + 1)
                world.setBlock(p, block); placed += p
                cache.around(world, 200, 200, 24, revision(world))
                assertEquals(0, cache.poppedLastCall, "round $round placing at $x,$y")
            }
            // Undo half, as fast as the thumb goes, then redo it.
            val undone = placed.takeLast(placed.size / 2)
            undone.reversed().forEach { world.setBlock(it, BlockRegistry.AIR_INDEX); cache.around(world, 200, 200, 24, revision(world)) }
            undone.forEach { world.setBlock(it, block); cache.around(world, 200, 200, 24, revision(world)) }
            assertEquals(0, cache.poppedLastCall)
        }
        val settled = settle(cache, world, revision(world))
        val fresh = settle(detailCache(), world, revision(world))
        assertEquals(vertices(fresh), vertices(settled), "the edited cache and a fresh one disagree about the world")
        assertEquals(fresh.sumOf { it.mesh.triangleCount }, settled.sumOf { it.mesh.triangleCount })
    }

    @Test
    fun `with the far ring on, the whole view is drawn from microvoxels`() {
        val world = world()
        val settings = RenderSettings.of(QualityTier.MEDIUM)
        val all = SceneBuilder(director, TextureLibrary(), settings = settings, microTerrain = generator)
        settle(all, world)
        val near = SceneBuilder(director, TextureLibrary(), settings = settings.copy(microFarRadius = 0), microTerrain = generator)
        settle(near, world)
        kotlin.test.assertTrue(all.detailedChunksLastFrame > near.detailedChunksLastFrame,
            "far ring drew ${all.detailedChunksLastFrame} chunks in microvoxels, near only ${near.detailedChunksLastFrame}")
    }
}
