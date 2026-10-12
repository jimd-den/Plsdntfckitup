package com.stratum.engine.world

import com.stratum.content.igbo.IgboContentPack
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.PassSpec
import com.stratum.core.domain.world.WorldConfig
import com.stratum.engine.microbridge.MicrovoxelTerrainGenerator
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Walking and digging the microvoxels themselves. */
class QuarterBlockPlayTest {

    private val igbo = ContentPackAssembler().assemble(listOf(IgboContentPack.pack))
    private val passes = MicrovoxelTerrainGenerator.newWorldPasses(igbo.terrain).map { PassSpec(it.id, it.options) }

    private fun session(quarter: Boolean, seed: Long = 11L) = WorldSession(
        content = igbo,
        config = WorldConfig(seed = seed, simulationRadius = 2, terrainPasses = passes),
        settings = GameSettings(quarterBlockPlay = quarter, safeTowns = false),
    ).also { it.enemies = emptyList() }

    /** Walks east then north for a while, keeping every height the body stood at. */
    private fun WorldSession.walk(): List<Float> {
        val heights = ArrayList<Float>()
        for ((dx, dy) in listOf(1f to 0f, 0f to 1f, -1f to 0f)) {
            setMoveInput(dx, dy)
            repeat(80) { tick(0.05f); enemies = emptyList(); heights += player.position.z }
        }
        setMoveInput(0f, 0f)
        return heights
    }

    @Test
    fun `on quarter-block ground a body stands at quarter heights, never inside the land`() {
        val s = session(quarter = true)
        s.tick(0.05f)
        val ground = assertNotNull(s.microGround, "a microvoxel world has quarter-block ground")
        val heights = s.walk()
        assertTrue(heights.all { (it * 4f) == floor(it * 4f) }, "a height off the quarter grid")
        assertTrue(heights.any { it != floor(it) }, "the body only ever stood on whole blocks: ${heights.distinct().take(12)}")
        val p = s.player.position
        assertTrue(ground.clear(p.x, p.y, p.z, PlayerMotion.BODY_HEIGHT), "the body stands inside the land at $p")
        // It went somewhere.
        assertTrue(heights.distinct().size > 1)
    }

    @Test
    fun `on block ground, as before, a body stands on whole blocks`() {
        val s = session(quarter = false)
        s.tick(0.05f)
        val heights = s.walk()
        assertTrue(heights.all { it == floor(it) }, "a fractional height on block ground")
    }

    @Test
    fun `digging carves a rounded hollow from the microvoxels, and pockets the block`() {
        val s = session(quarter = true)
        s.tick(0.05f)
        val ground = s.microGround!!
        val feet = s.player.blockPos
        // The block of land just east of the player, under the surface.
        val x = feet.x + 2; val y = feet.y
        val top = s.world.surfaceAt(x, y)
        val target = BlockPos(x, y, top)
        val stampsBefore = s.microStamps.size
        val bagBefore = s.player.inventory.values.sum()
        // Microvoxels just outside the dug block, inside the carve's reach.
        val beside = (0 until 4).flatMap { k -> listOf(intArrayOf(x * 4 - 1, y * 4 + k, top * 4 + 2), intArrayOf(x * 4 + 4, y * 4 + k, top * 4 + 2)) }
        val solidBefore = beside.count { ground.solid(it[0], it[1], it[2]) }
        var result: MineResult
        var guard = 0
        do { result = s.mine(target, 0.5f); guard++ } while (result is MineResult.InProgress && guard < 200)
        assertIs<MineResult.Broken>(result, "the block would not dig: $result")
        assertTrue(s.microStamps.size == stampsBefore + 1, "no carve was laid")
        assertTrue(s.player.inventory.values.sum() == bagBefore + 1, "the dig did not pocket exactly one block")
        assertTrue(s.world.blockAt(target).isAir, "the dug block still stands")
        val solidAfter = beside.count { ground.solid(it[0], it[1], it[2]) }
        assertTrue(solidBefore > 0, "nothing solid beside the dug block to begin with")
        assertTrue(solidAfter < solidBefore, "the hollow is cut square: $solidBefore beside before, $solidAfter after")
    }
}
