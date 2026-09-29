package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.micro.MicroModel
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.TerrainRecipe
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Building should feel safe to try: every shape can be taken back, and a microvoxel world can be sculpted and furnished. */
class CozyBuildTest {

    private val micro = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
        .let { it.copy(terrain = TerrainRecipe(generatorId = TerrainRecipe.MICROVOXEL)) }

    @Test
    fun `new shapes plan what they promise`() {
        val o = BlockPos(0, 0, 10)
        val stairs = BuildPlanner.plan(BuildTool.STAIRS, o, BlockPos(4, 0, 10))
        assertEquals(5, stairs.maxOf { it.z } - 10 + 1, "five steps rise five blocks")
        val roof = BuildPlanner.plan(BuildTool.ROOF, o, BlockPos(4, 6, 10))
        assertEquals(12, roof.maxOf { it.z }, "a 5-wide gable peaks two up")
        val dome = BuildPlanner.plan(BuildTool.DOME, o, BlockPos(8, 8, 10))
        assertTrue(dome.isNotEmpty() && dome.none { it == BlockPos(4, 4, 10) }, "a dome is a shell, open inside")
        val ring = BuildPlanner.plan(BuildTool.RING, o, BlockPos(8, 8, 10), height = 2)
        assertTrue(ring.none { it.x == 4 && it.y == 4 }, "a round wall has a middle")
        assertEquals(4, BuildPlanner.plan(BuildTool.PILLAR, o, o, height = 4).size)
        assertTrue(BuildTool.DIG.removes && BuildTool.PAINT.replaces)
    }

    @Test
    fun `a build is undone and redone, blocks and bag alike`() {
        val session = WorldSession(TestContent.assembled, WorldConfig(seed = 7L, simulationRadius = 1, rules = WorldRules(sandbox = true)))
        val block = TestContent.assembled.registry.all.first { !it.isAir && it.isSolid && it.glyph == null && it.isBreakable }.id
        session.player = session.player.withItem(block, 50).let { p -> p.copy(hotbar = listOf(block)) }
        val feet = session.player.feet
        val ground = session.world.surfaceAt(feet.x + 3, feet.y)
        val from = BlockPos(feet.x + 3, feet.y, ground); val to = BlockPos(feet.x + 5, feet.y + 2, ground)
        session.selectBuildTool(BuildTool.BOX)
        session.setBuildHeight(2)
        session.previewBuild(from, to)
        val built = assertIs<BuildResult.Built>(session.commitBuild())
        val held = session.player.countOf(block)
        assertTrue(session.canUndo)

        assertIs<UndoResult.Undone>(session.undo())
        assertEquals(held + built.placed, session.player.countOf(block), "undo did not hand the blocks back")
        assertTrue(session.world.blockAt(from.above()).isAir)

        assertIs<UndoResult.Redone>(session.redo())
        assertEquals(held, session.player.countOf(block))
        assertTrue(!session.world.blockAt(from.above()).isAir)
    }

    @Test
    fun `a model stands in a microvoxel world, is saved, and comes back`() {
        val session = WorldSession(micro, WorldConfig(seed = 11L, simulationRadius = 1))
        assertTrue(session.canSculpt)
        // A 2x2x3-block column of paint: plainly solid at block scale.
        val cells = IntArray(8 * 8 * 12) { 1 }
        val model = MicroModel("statue", "Statue", 8, 8, 12, listOf("#C03020"), cells)
        val before = session.snapshot().worldRevision
        val placed = assertIs<SculptResult.Shaped>(session.placeModel(model))
        assertTrue(placed.blocks >= 8, "only ${placed.blocks} blocks changed")
        assertTrue(session.snapshot().worldRevision != before)

        val save = session.worldSave(WorldIdentity("w", "W"))
        assertEquals(listOf("statue"), save.stamps.map { it.modelId })
        assertEquals(1, save.microModels.size)

        val resumed = WorldSession.restore(micro, save)
        assertEquals(save.stamps, resumed.microStamps)

        assertIs<UndoResult.Undone>(session.undo())
        assertTrue(session.worldSave(WorldIdentity("w", "W")).stamps.isEmpty(), "undo left the statue in the save")
    }

    @Test
    fun `a chisel digs a rounded hollow and pockets what it breaks`() {
        val session = WorldSession(micro, WorldConfig(seed = 11L, simulationRadius = 1))
        val feet = session.player.feet
        val x = feet.x + 4; val y = feet.y
        val z = session.world.surfaceAt(x, y)
        session.setSculptBrush(SculptBrush(radius = 6))
        val bag = session.player.inventory.values.sum()
        val dug = assertIs<SculptResult.Shaped>(session.sculpt(BlockPos(x, y, z), carve = true))
        assertTrue(dug.blocks > 0, "the chisel took nothing")
        assertTrue(session.world.blockAt(BlockPos(x, y, z)).isAir)
        assertTrue(session.player.inventory.values.sum() > bag, "nothing went in the bag")
        // Heaping it back up.
        assertIs<SculptResult.Shaped>(session.sculpt(BlockPos(x, y, z - 2), carve = false))
    }
}
