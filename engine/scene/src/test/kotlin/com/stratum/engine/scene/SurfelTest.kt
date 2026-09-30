package com.stratum.engine.scene

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.scene.quality.DioramaLook
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Surfels: deterministic, bounded per chunk and per frame, faded by distance, and off where the tier says so. */
class SurfelTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
    private val config = WorldConfig(seed = 20260928L, simulationRadius = 2)
    private val generator = (StratumTerrain.create(content.terrainContext(config)) as com.stratum.engine.microbridge.HotTerrain).current
    private val director = StyleSheetArtDirector(StyleLexicon.interpret("house").direction)
    private val pos = ChunkPos.containing(200, 200)

    private fun world() = StreamingWorld(content.registry, generator, config).also { it.focusOn(BlockPos(200, 200, 0)) }

    @Test
    fun `the same chunk always grows the same surfels, on any mesher and any thread`() {
        val world = world()
        val a = assertNotNull(assertNotNull(MicroDetailMesher(generator, scatterSurfels = true).mesh(world, pos)).surfels)
        val snapshot = BlockSnapshot.of(world, pos)
        val b = java.util.concurrent.Executors.newSingleThreadExecutor().let { pool ->
            try { pool.submit<SurfelBatch?> { MicroDetailMesher(generator, scatterSurfels = true).mesh(snapshot, pos)?.surfels }.get() } finally { pool.shutdown() }
        }
        assertNotNull(b)
        assertTrue(a.count > 500, "a chunk of ground grew only ${a.count} surfels")
        assertEquals(a.count, b.count)
        assertContentEquals(a.data, b.data)
        assertEquals(pos.originX, a.originX)
    }

    @Test
    fun `surfels are sorted by rank, stay within their chunk, and sit on the ground`() {
        val world = world()
        val batch = assertNotNull(MicroDetailMesher(generator, scatterSurfels = true).mesh(world, pos)?.surfels)
        assertTrue(batch.count <= SurfelScatter.MAX_PER_CHUNK)
        val n = FloatArray(3)
        for (i in 0 until batch.count) {
            if (i > 0) assertTrue(Surfel.rank(batch, i) >= Surfel.rank(batch, i - 1), "rank order broken at $i")
            val x = Surfel.x(batch, i); val y = Surfel.y(batch, i)
            assertTrue(x >= pos.originX - 0.5f && x <= pos.originX + 16.5f && y >= pos.originY - 0.5f && y <= pos.originY + 16.5f, "surfel $i at $x,$y left its chunk")
            assertTrue(Surfel.z(batch, i) in batch.minZ..batch.maxZ)
            assertTrue(Surfel.radius(batch, i) in 0f..Surfel.MAX_RADIUS)
            Surfel.normal(batch, i, n)
            assertTrue(abs(n[0] * n[0] + n[1] * n[1] + n[2] * n[2] - 1f) < 1e-3f)
            assertTrue(n[2] > -0.5f, "no surfel grows on an underside")
        }
    }

    @Test
    fun `packing round-trips within its quantisation`() {
        val out = IntArray(Surfel.INTS)
        Surfel.pack(out, 0, 3.3f, 12.7f, 41.2f, 0x80C040, 0.06f, 0.3f, -0.2f, 0.93f, 0.7f, 0.5f)
        val b = SurfelBatch(32, 48, out, 1, 41.2f, 41.2f)
        assertEquals(35.3f, Surfel.x(b, 0), Surfel.POSITION_STEP)
        assertEquals(60.7f, Surfel.y(b, 0), Surfel.POSITION_STEP)
        assertEquals(41.2f, Surfel.z(b, 0), Surfel.POSITION_STEP)
        assertEquals(0x80 / 255f, Surfel.red(b, 0), 1e-3f)
        assertEquals(0.06f, Surfel.radius(b, 0), Surfel.MAX_RADIUS / 255f)
        assertEquals(0.7f, Surfel.occlusion(b, 0), 1f / 255f)
        val n = FloatArray(3)
        Surfel.normal(b, 0, n)
        val l = kotlin.math.sqrt(0.3f * 0.3f + 0.2f * 0.2f + 0.93f * 0.93f)
        assertEquals(0.3f / l, n[0], 0.02f); assertEquals(-0.2f / l, n[1], 0.02f); assertEquals(0.93f / l, n[2], 0.02f)
    }

    @Test
    fun `glass, water and glowing materials grow nothing, and grass grows tufts`() {
        for (m in generator.palette.all) {
            val kind = SurfelScatter.classify(m)
            if (!m.opaque || m.emission > 0f) assertEquals(SurfelScatter.Kind.NONE, kind, m.name)
            if (m.name == "grass") assertEquals(SurfelScatter.Kind.TUFT, kind)
        }
    }

    @Test
    fun `the frame's surfels never pass the budget, and thin with distance`() {
        val batch = assertNotNull(MicroDetailMesher(generator, scatterSurfels = true).mesh(world(), pos)?.surfels)
        val many = List(20) { batch }
        val budget = 10_000
        val draws = SurfelLod.select(many, pos.originX + 8f, pos.originY + 8f, radius = 18, budget = budget) { true }
        // Each draw is a prefix; what can show is its rank share times keep, plus the fading band.
        val shown = draws.sumOf { d -> (0 until d.count).count { SurfelLod.size(Surfel.rank(d.batch, it), d.keep) > 0f } }
        assertTrue(shown <= budget * 1.05f + many.size * SurfelLod.FADE * batch.count, "showed $shown for a budget of $budget")
        assertTrue(draws.all { it.keep < 1f }, "over budget, every chunk is thinned")

        assertEquals(1f, SurfelLod.share(0f, 18f))
        assertEquals(0f, SurfelLod.share(18f, 18f))
        assertTrue(SurfelLod.share(13f, 18f) in 0.01f..0.99f)
        assertTrue(SurfelLod.select(listOf(batch), pos.originX + 200f, pos.originY + 8f, 18, budget) { true }.isEmpty(), "far chunks draw none")
        assertTrue(SurfelLod.select(listOf(batch), pos.originX + 8f, pos.originY + 8f, 18, 0) { true }.isEmpty())
    }

    @Test
    fun `an edit rescatters only the layer it touched`() {
        val world = world()
        val mesher = MicroDetailMesher(generator, scatterSurfels = true)
        val snap = BlockSnapshot.of(world, pos)
        val layers = assertNotNull(mesher.meshLayers(snap, pos))
        assertTrue(layers.any { it.surfels != null })
        val top = world.surfaceAt(200, 200)
        world.setBlock(BlockPos(200, 200, top), BlockRegistry.AIR_INDEX)
        val touched = BooleanArray(mesher.layers).also { it[top / mesher.blocksPerLayer] = true }
        val again = assertNotNull(mesher.meshLayers(BlockSnapshot.of(world, pos), pos, only = touched, previous = layers))
        for (i in layers.indices) if (!touched[i]) assertTrue(again[i] === layers[i], "untouched layer $i was remade")
    }

    @Test
    fun `LOW and MEDIUM neither scatter nor draw surfels, and OFF is every ingredient off`() {
        for (tier in listOf(QualityTier.LOW, QualityTier.MEDIUM)) assertTrue(!RenderSettings.of(tier).diorama.surfels, "$tier draws surfels")
        for (tier in listOf(QualityTier.LOW, QualityTier.MEDIUM)) assertTrue(!RenderSettings.of(tier).diorama.readsDepth, "$tier needs a depth texture")
        assertTrue(RenderSettings.of(QualityTier.HIGH).diorama.surfels)
        assertEquals(0f, RenderSettings.of(QualityTier.HIGH).diorama.tiltShift, "tilt-shift is ULTRA's")
        assertTrue(RenderSettings.of(QualityTier.ULTRA).diorama.tiltShift > 0f)
        val off = DioramaLook.OFF
        assertTrue(!off.surfels && !off.readsDepth && off.grain == 0f && off.bevel == 0f && off.nightGlow == 0f && !off.aerialHaze)
        assertEquals(null, MicroDetailMesher(generator).mesh(world(), pos)?.surfels, "a mesher not asked for surfels scatters none")
    }

    @Test
    fun `a HIGH frame carries surfels near the focus, within budget, and a night frame glows`() {
        val world = world()
        val settings = RenderSettings.of(QualityTier.HIGH).withTerrain(SplatMode.MESH) // surfels grow on mesh quads
        val builder = SceneBuilder(director, TextureLibrary(), settings = settings, microTerrain = generator)
        val camera = SceneCamera(target = Vec3(200.5f, 200.5f, world.surfaceAt(200, 200) + 1f))
        val night = WorldTime(dayFraction = 0.02f, elapsedSeconds = 1f)
        var frame = builder.build(world, camera, time = night)
        var waited = 0
        while (builder.detailPending > 0 && waited < 20_000) { Thread.sleep(5); waited += 5; frame = builder.build(world, camera, time = night) }
        assertTrue(frame.surfels.isNotEmpty(), "no surfels drawn near the hero")
        assertTrue(frame.surfels.sumOf { it.count } <= settings.diorama.surfelBudget + frame.surfels.sumOf { (it.batch.count * SurfelLod.FADE).toInt() + 1 })
        assertTrue(frame.residentSurfels.size >= frame.surfels.size)
        assertEquals(settings.diorama, frame.look)
        assertTrue(frame.night > 0.9f, "midnight is night: ${frame.night}")
        assertTrue(ShadingModel.nightEmissive(1f, frame.look.nightGlow, frame.night) > 3f)
    }

    @Test
    fun `grain is deterministic per voxel and averages out to the material's colour`() {
        val rgb = FloatArray(3)
        var sum = 0f
        var n = 0
        for (x in 0 until 40) for (y in 0 until 40) {
            ShadingModel.voxelGrain(0.07f, x * 0.25f + 0.1f, y * 0.25f + 0.1f, 10f, 0f, 0f, 1f, rgb)
            assertTrue(rgb[1] in 0.93f..1.07f)
            sum += rgb[1]; n++
        }
        assertEquals(1f, sum / n, 0.01f)
        // Every pixel of one voxel face agrees.
        ShadingModel.voxelGrain(0.07f, 5.01f, 5.01f, 10f, 0f, 0f, 1f, rgb); val a = rgb[1]
        ShadingModel.voxelGrain(0.07f, 5.24f, 5.24f, 10f, 0f, 0f, 1f, rgb)
        assertEquals(a, rgb[1])
        ShadingModel.voxelGrain(0f, 5.01f, 5.01f, 10f, 0f, 0f, 1f, rgb)
        assertContentEquals(floatArrayOf(1f, 1f, 1f), rgb)
    }
}
