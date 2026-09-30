package com.stratum.engine.scene

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.art.StyleSheetArtDirector
import com.stratum.core.domain.art.WorldTime
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microvoxel.MicroChunkPos
import com.stratum.engine.scene.quality.QualityTier
import com.stratum.engine.scene.quality.RenderSettings
import com.stratum.engine.world.StratumTerrain
import com.stratum.engine.world.StreamingWorld
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voxel splats: what they pack, which voxels they keep, how they light, and that both draw modes agree on faces. */
class SplatTest {

    private val content = ContentPackAssembler().assemble(listOf(IgboContentPack.pack)).copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL))
    private val config = WorldConfig(seed = 20260928L, simulationRadius = 2)
    private val generator = (StratumTerrain.create(content.terrainContext(config)) as com.stratum.engine.microbridge.HotTerrain).current
    private val pos = ChunkPos.containing(200, 200)

    private fun world() = StreamingWorld(content.registry, generator, config).also { it.focusOn(BlockPos(200, 200, 0)) }
    private fun splatter(light: Boolean = true) = MicroDetailMesher(generator, splatMode = SplatMode.FAST, voxelLight = light)

    @Test
    fun `packing round-trips every field`() {
        val out = IntArray(VoxelSplat.INTS)
        VoxelSplat.pack(out, 0, 63, 17, 191, 2, 0b101101, 11, 0xA0B0C0, 200, bounce = 0x102030, sky = 77, lamp = 0x405060)
        assertEquals(63, VoxelSplat.x(out[0])); assertEquals(17, VoxelSplat.y(out[0])); assertEquals(191, VoxelSplat.z(out[0]))
        assertEquals(2, VoxelSplat.lod(out[0])); assertEquals(4, VoxelSplat.size(out[0]))
        assertEquals(0b101101, VoxelSplat.faces(out[0])); assertEquals(11, VoxelSplat.ao(out[0]))
        assertEquals(0xA0B0C0, VoxelSplat.rgb(out[1])); assertEquals(200, VoxelSplat.emission(out[1]))
        assertEquals(0x102030, VoxelSplat.bounce(out[2])); assertEquals(77, VoxelSplat.sky(out[2]))
        assertEquals(0x405060, VoxelSplat.lamp(out[3]))
    }

    @Test
    fun `only surface voxels become splats, and every one of them does`() {
        val world = world()
        val layers = assertNotNull(splatter(light = false).meshLayers(BlockSnapshot.of(world, pos), pos))
        val palette = generator.palette
        for ((cz, result) in layers.withIndex()) {
            val grid = generator.microChunk(MicroChunkPos(pos.x, pos.y, cz))
            val kept = HashSet<Int>()
            result.splats?.let { b ->
                for (i in 0 until b.count) {
                    val a = b.data[i * VoxelSplat.INTS]
                    val x = VoxelSplat.x(a); val y = VoxelSplat.y(a); val z = VoxelSplat.z(a) - cz * 64
                    assertTrue(palette.isOpaque(grid[x, y, z]), "splat at $x,$y,$z of layer $cz is not solid")
                    assertTrue(VoxelSplat.faces(a) and VoxelSplat.FACE_NZ.inv() != 0, "splat at $x,$y,$z shows only its underside")
                    kept += x + y * 64 + z * 4096
                }
            }
            // Inside the layer, where no neighbour is needed, a solid voxel with an open face other than its underside is a splat.
            for (z in 1 until 63) for (y in 1 until 63) for (x in 1 until 63) {
                if (!palette.isOpaque(grid[x, y, z])) continue
                fun air(dx: Int, dy: Int, dz: Int) = !palette.isOpaque(grid[x + dx, y + dy, z + dz])
                val visible = air(1, 0, 0) || air(-1, 0, 0) || air(0, 1, 0) || air(0, -1, 0) || air(0, 0, 1)
                assertEquals(visible, (x + y * 64 + z * 4096) in kept, "voxel $x,$y,$z of layer $cz")
            }
        }
    }

    @Test
    fun `the same chunk gives the same splats whole or layer by layer, on any thread`() {
        val world = world()
        val snapshot = BlockSnapshot.of(world, pos)
        val whole = assertNotNull(assertNotNull(splatter().mesh(snapshot, pos)).splats)
        val layers = assertNotNull(splatter().meshLayers(snapshot, pos)).mapNotNull { it.splats }
        assertContentEquals(whole.data, layers.flatMap { it.data.toList() }.toIntArray())
        val other = java.util.concurrent.Executors.newSingleThreadExecutor().let { pool ->
            try { pool.submit<SplatBatch?> { splatter().mesh(snapshot, pos)?.splats }.get() } finally { pool.shutdown() }
        }
        assertContentEquals(whole.data, assertNotNull(other).data)
    }

    @Test
    fun `an edit remakes only the layer it touched`() {
        val world = world()
        val m = splatter()
        val before = assertNotNull(m.meshLayers(BlockSnapshot.of(world, pos), pos))
        val only = BooleanArray(m.layers).also { it[1] = true }
        val after = assertNotNull(m.meshLayers(BlockSnapshot.of(world, pos), pos, 1, only, before))
        for (cz in before.indices) if (cz != 1) assertTrue(after[cz] === before[cz], "layer $cz was remade")
    }

    @Test
    fun `voxel light darkens what the sky cannot reach and costs nothing where it is off`() {
        val world = world()
        val lit = assertNotNull(splatter().mesh(world, pos)?.splats)
        val flat = assertNotNull(splatter(light = false).mesh(world, pos)?.splats)
        assertEquals(lit.count, flat.count)
        var shaded = 0; var open = 0
        for (i in 0 until lit.count) {
            val c = lit.data[i * VoxelSplat.INTS + 2]
            if (VoxelSplat.sky(c) < 128) shaded++
            if (VoxelSplat.sky(c) == 255) open++
            assertEquals(255, VoxelSplat.sky(flat.data[i * VoxelSplat.INTS + 2]))
            assertEquals(0, VoxelSplat.lamp(flat.data[i * VoxelSplat.INTS + 3]))
        }
        assertTrue(shaded > 0, "no splat is in shade")
        assertTrue(open > 0, "no splat sees the whole sky")
    }

    @Test
    fun `the fast outline test finds the top, both sides, and nothing outside`() {
        val camera = SceneCamera(target = Vec3(10f, 10f, 5f))
        val vp = camera.viewProjection
        val clip = FloatArray(4); val probe = FloatArray(4)
        val half = 0.125f
        val cx = 10.125f; val cy = 10.125f; val cz = 5.125f
        Mat4.transform(vp, cx, cy, cz, clip)
        val eye = camera.eye
        val sx = if (eye.x >= cx) 1f else -1f; val sy = if (eye.y >= cy) 1f else -1f
        val w = 960f; val h = 540f
        val lines = FloatArray(SplatFaces.FLOATS)
        SplatFaces.lines(vp, clip, w / 2f, h / 2f, half, sx, sy, lines)
        fun faceAt(x: Float, y: Float, z: Float): Int {
            Mat4.transform(vp, x, y, z, probe)
            val dx = (probe[0] / probe[3] - clip[0] / clip[3]) * w / 2f
            val dy = (probe[1] / probe[3] - clip[1] / clip[3]) * h / 2f
            return SplatFaces.face(lines, dx, dy)
        }
        assertEquals(SplatFaces.TOP, faceAt(cx, cy, cz + half))
        assertEquals(SplatFaces.X_SIDE, faceAt(cx + sx * half, cy, cz))
        assertEquals(SplatFaces.Y_SIDE, faceAt(cx, cy + sy * half, cz))
        assertEquals(SplatFaces.OUTSIDE, faceAt(cx + 3 * half, cy - 3 * sy * half, cz + 3 * half))
    }

    @Test
    fun `fast splats turn exact under a finish that reads depth, and every tier draws splats`() {
        for (tier in QualityTier.entries) {
            val s = RenderSettings.of(tier)
            assertTrue(s.splats.splats, "$tier draws the mesh")
            if (s.diorama.readsDepth) assertEquals(SplatMode.EXACT, s.copy(splats = SplatMode.FAST).splatDraw)
        }
        assertEquals(SplatMode.FAST, RenderSettings.of(QualityTier.LOW).splatDraw)
        assertEquals(SplatMode.MESH, RenderSettings.of(QualityTier.LOW).withTerrain(SplatMode.MESH).splats)
    }

    @Test
    fun `a frame of splats holds far less than the mesh, and the mesh stays one setting away`() {
        val director = StyleSheetArtDirector(StyleLexicon.interpret("house").direction)
        fun frame(mode: SplatMode): SceneFrame {
            val world = world()
            val builder = SceneBuilder(director, TextureLibrary(), settings = RenderSettings.of(QualityTier.LOW).withTerrain(mode), microTerrain = generator)
            val camera = SceneCamera(target = Vec3(200.5f, 200.5f, world.surfaceAt(200, 200) + 1f))
            var f = builder.build(world, camera, emptyList(), WorldTime(dayFraction = 0.4f))
            var waited = 0
            while (builder.detailPending > 0 && waited < 30_000) { Thread.sleep(5); waited += 5; f = builder.build(world, camera, emptyList(), WorldTime(dayFraction = 0.4f)) }
            return f
        }
        val mesh = frame(SplatMode.MESH)
        val splats = frame(SplatMode.FAST)
        assertTrue(mesh.splats.isEmpty())
        assertTrue(splats.splats.isNotEmpty())
        val meshBytes = mesh.residentTerrain.sumOf { it.vertexFloats.toLong() * 4 + it.indexCount * 4L }
        val splatBytes = splats.residentTerrain.sumOf { it.vertexFloats.toLong() * 4 + it.indexCount * 4L } + splats.residentSplats.sumOf { it.bytes.toLong() }
        assertTrue(splatBytes * 4 < meshBytes, "splats hold $splatBytes bytes against the mesh's $meshBytes")
        assertNull(mesh.residentSplats.firstOrNull())
    }
}
