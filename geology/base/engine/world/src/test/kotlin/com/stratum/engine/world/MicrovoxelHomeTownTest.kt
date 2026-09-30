package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microvoxel.M
import com.stratum.engine.microvoxel.MicroChunkPos
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The home town, and every pack town, is built in microvoxels and plays exactly as planned. */
class MicrovoxelHomeTownTest {

    private val igbo = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
    private val session = WorldSession(
        content = igbo.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)),
        config = WorldConfig(seed = 20260928L, simulationRadius = 2),
    )
    private val home = assertNotNull(
        (session.microTerrain as SettlementAtlas).settlementsNear(0, 0, 0).firstOrNull { it.contains(0, 0) },
        "no home town at the origin",
    )

    @Test
    fun `the hero starts in the home town`() {
        val p = session.player.blockPos
        assertTrue(home.contains(p.x, p.y), "spawned at $p, outside ${home.name}")
        // The game's own town systems see the same town: names, factions and garrisons come from it.
        assertTrue(session.settlementsNear(8).any { it.id == home.id }, "the session does not know the home town")
    }

    @Test
    fun `every door is open and every wall stands where the plan says`() {
        val world = session.world
        var walls = 0
        for (b in home.buildings) {
            for (z in home.groundZ + 1..home.groundZ + 2)
                assertTrue(!world.isSolid(BlockPos(b.doorX, b.doorY, z)), "${b.template.id}: door at ${b.doorX},${b.doorY},$z is blocked")
            // A mid-wall cell (not a corner, not the door, below the windows) is the pack's wall block.
            val x = b.x + b.width / 2; val y = b.y
            if (x == b.doorX && y == b.doorY) continue
            val cell = world.blockAt(BlockPos(x, y, home.groundZ + 1))
            if (cell.isSolid) walls++
            assertTrue(cell.isAir || cell.id == b.template.wallBlockId || cell.id == b.template.windowBlockId,
                "${b.template.id}: wall at $x,$y is ${cell.id}, expected ${b.template.wallBlockId}")
        }
        assertTrue(walls >= home.buildings.size / 2, "only $walls of ${home.buildings.size} buildings have their front wall")
    }

    @Test
    fun `the home town is drawn from microvoxels, with mud, nzu and thatch`() {
        val micro = assertNotNull(session.microTerrain)
        val p = palette@{ name: String -> micro.palette.id(name) }
        val earthen = setOf(p(M.NZU), p(M.THATCH), p(M.THATCH_DARK), p(M.MUD_DARK))
        var found = 0
        val b = home.buildings.first()
        val cz = (home.groundZ * 4) / 64
        for (dz in 0..1) {
            val mc = micro.microChunk(MicroChunkPos(Math.floorDiv(b.x * 4, 64), Math.floorDiv(b.y * 4, 64), cz + dz))
            for (z in 0 until 64) for (y in 0 until 64) for (x in 0 until 64) if (mc[x, y, z] in earthen) found++
        }
        assertTrue(found > 50, "the home town has no earthen detail ($found voxels)")
    }

    @Test
    fun `the land is the pack's own soil`() {
        val ids = HashSet<String>()
        for (chunk in session.world.loadedChunks) for (x in 0 until 16 step 3) for (y in 0 until 16 step 3) {
            val wx = chunk.pos.originX + x; val wy = chunk.pos.originY + y
            if (home.contains(wx, wy, 10)) continue
            ids += session.world.blockAt(BlockPos(wx, wy, session.world.surfaceAt(wx, wy))).id
        }
        val biomeSurfaces = igbo.biomes.map { it.surfaceBlockId }.toSet()
        assertTrue(ids.any { it in biomeSurfaces }, "no region surface blocks outside town: $ids")
    }
}
