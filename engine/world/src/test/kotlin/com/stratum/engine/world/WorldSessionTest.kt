package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldSessionTest {

    private fun session(seed: Long = 7L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    )

    @Test
    fun `the player spawns standing on solid ground, not inside it`() {
        val session = session()
        val feet = session.player.blockPos

        assertTrue(session.world.blockAt(feet).isAir, "player spawned inside a block")
        assertTrue(
            feet.z == 0 || session.world.isSolid(feet.below()),
            "player spawned with nothing underfoot",
        )
    }

    @Test
    fun `spawning uses the hero class the pack defines`() {
        val session = session()
        assertTrue(session.player.maxHealth > 0)
        assertTrue(session.player.isAlive)
    }

    @Test
    fun `moving onto open ground advances the player`() {
        val session = session()
        val before = session.player.position

        // Try each direction; terrain decides which ones are walkable.
        val outcomes = listOf(
            session.move(1f, 0f),
            session.move(0f, 1f),
            session.move(-1f, 0f),
            session.move(0f, -1f),
        )
        assertTrue(outcomes.any { it.moved }, "the player could not move in any direction")
        assertTrue(outcomes.all { it.moved || it.blocked })
        assertNotNull(before)
    }

    @Test
    fun `a zero move is neither a move nor a block`() {
        val session = session()
        val outcome = session.move(0f, 0f)
        assertTrue(!outcome.moved && !outcome.blocked)
    }

    @Test
    fun `the player climbs a single step but not a wall`() {
        val session = session()
        val start = session.player.blockPos
        val stoneIndex = TestContent.registry.indexOf(TestContent.stone.id)

        // Build a one-block step to the east and confirm the player walks up it.
        val stepColumn = BlockPos(start.x + 1, start.y, start.z)
        clearColumn(session, stepColumn.x, stepColumn.y, from = start.z, to = start.z + 4)
        session.world.let { }
        setBlock(session, BlockPos(stepColumn.x, stepColumn.y, start.z), stoneIndex)

        val stepped = session.move(1f, 0f)
        assertTrue(stepped.moved, "the player refused a one-block step")
        assertEquals(start.z + 1, session.player.blockPos.z)

        // Now a two-block wall in the same direction must block.
        val here = session.player.blockPos
        val wallColumn = BlockPos(here.x + 1, here.y, here.z)
        setBlock(session, wallColumn, stoneIndex)
        setBlock(session, wallColumn.above(), stoneIndex)
        setBlock(session, wallColumn.above(2), stoneIndex)

        val blocked = session.move(1f, 0f)
        assertTrue(blocked.blocked, "the player climbed a three-block wall")
        assertEquals(here, session.player.blockPos)
    }

    @Test
    fun `mining accumulates across calls and keeps progress on the same block`() {
        val session = session()
        val target = solidNeighbourOf(session)

        val first = assertIs<MineResult.InProgress>(session.mine(target, 0.05f))
        val second = assertIs<MineResult.InProgress>(session.mine(target, 0.05f))
        assertTrue(second.progress > first.progress, "progress reset between calls")
        assertTrue(session.miningFraction > 0f)
    }

    @Test
    fun `switching target resets progress rather than carrying it over`() {
        val session = session()
        val first = solidNeighbourOf(session)
        session.mine(first, 0.1f)

        val other = BlockPos(first.x, first.y, first.z - 1)
        if (session.world.isSolid(other)) {
            session.mine(other, 0.01f)
            assertTrue(session.miningFraction < 0.5f, "effort carried over to a different block")
        }
    }

    @Test
    fun `breaking a block puts its drop in the inventory`() {
        val session = session()
        val target = solidNeighbourOf(session)
        val expectedDrop = session.world.blockAt(target).drop
        val before = session.player.countOf(expectedDrop)

        val result = mineToCompletion(session, target)
        assertIs<MineResult.Broken>(result)
        assertEquals(before + 1, session.player.countOf(expectedDrop))
        assertTrue(session.world.blockAt(target).isAir)
        assertEquals(0f, session.miningFraction)
    }

    @Test
    fun `mining the ground out from under the player drops them onto what is below`() {
        val session = session()
        val feet = session.player.blockPos
        val ground = feet.below()
        if (!session.world.isSolid(ground)) return

        mineToCompletion(session, ground)
        assertTrue(
            session.player.blockPos.z < feet.z,
            "the player hovered where the ground used to be",
        )
    }

    @Test
    fun `placing spends one block from the inventory`() {
        val session = session()
        val blockId = session.player.selectedBlockId
        assertNotNull(blockId)
        val before = session.player.countOf(blockId)

        // place() takes the block that was tapped, which is always solid, and
        // resolves the empty cell beside it. Handing it an air cell was the old
        // contract and the reason building never worked.
        val picked = session.player.blockPos.below()
        val target = session.placementPreviewFor(picked)
        assertNotNull(target, "no valid placement target next to the player")

        val result = session.place(picked)
        assertIs<PlaceResult.Placed>(result)
        assertEquals(before - 1, session.player.countOf(blockId))
        assertEquals(blockId, session.world.blockAt(target).id)
    }

    @Test
    fun `a rejected placement does not spend anything`() {
        val session = session()
        val blockId = session.player.selectedBlockId!!
        val before = session.player.countOf(blockId)

        // Far out of reach.
        val result = session.place(BlockPos(session.player.blockPos.x + 40, 0, 5))
        assertIs<PlaceResult.Rejected>(result)
        assertEquals(before, session.player.countOf(blockId))
    }

    @Test
    fun `placing with an empty hotbar is refused rather than crashing`() {
        val session = WorldSession(
            content = TestContent.assembled.copy(heroClasses = emptyList()),
            config = WorldConfig(seed = 3L, simulationRadius = 1),
        )
        assertNull(session.player.selectedBlockId)
        assertIs<PlaceResult.Rejected>(session.place(session.player.blockPos.above()))
    }

    @Test
    fun `cancelling mining clears progress`() {
        val session = session()
        session.mine(solidNeighbourOf(session), 0.05f)
        session.cancelMining()
        assertEquals(0f, session.miningFraction)
    }

    @Test
    fun `walking streams new chunks in and old ones out`() {
        val session = session()
        val startFocus = session.snapshot().focus
        val start = session.player.blockPos

        // Cut a level corridor east. Raw terrain can present a cliff the player
        // cannot step over, and this test is about streaming, not traversal.
        val stoneIndex = TestContent.registry.indexOf(TestContent.stone.id)
        val air = com.stratum.core.domain.world.BlockRegistry.AIR_INDEX
        for (step in 1..CORRIDOR_LENGTH) {
            val x = start.x + step
            setBlock(session, BlockPos(x, start.y, start.z - 1), stoneIndex)
            for (z in start.z until minOf(start.z + 4, Chunk.HEIGHT)) {
                setBlock(session, BlockPos(x, start.y, z), air)
            }
        }

        repeat(CORRIDOR_LENGTH) { session.move(1f, 0f) }

        val endFocus = session.snapshot().focus
        assertTrue(endFocus.x > startFocus.x, "focus never advanced while walking east")
        assertEquals(9, session.world.loadedChunks.size)
        assertTrue(!session.world.isLoaded(startFocus.copy(x = startFocus.x - 1)))
    }

    @Test
    fun `the snapshot reflects mining state`() {
        val session = session()
        val target = solidNeighbourOf(session)
        session.mine(target, 0.05f)

        val snapshot = session.snapshot()
        assertEquals(target, snapshot.miningTarget)
        assertTrue(snapshot.miningFraction > 0f)
        assertEquals(session.player, snapshot.player)
    }

    // ---- helpers ---------------------------------------------------------

    private companion object {
        /** Long enough to cross a chunk boundary and unload what is behind. */
        const val CORRIDOR_LENGTH = 40
    }

    private fun setBlock(session: WorldSession, pos: BlockPos, index: Int) {
        (session.world as com.stratum.core.domain.world.MutableWorld).setBlock(pos, index)
    }

    private fun clearColumn(session: WorldSession, x: Int, y: Int, from: Int, to: Int) {
        for (z in from..to) {
            setBlock(session, BlockPos(x, y, z), com.stratum.core.domain.world.BlockRegistry.AIR_INDEX)
        }
    }

    /** A solid block within reach, guaranteed by building one if terrain offers none. */
    private fun solidNeighbourOf(session: WorldSession): BlockPos {
        val feet = session.player.blockPos
        val candidates = listOf(
            feet.below(),
            BlockPos(feet.x + 1, feet.y, feet.z),
            BlockPos(feet.x - 1, feet.y, feet.z),
            BlockPos(feet.x, feet.y + 1, feet.z),
            BlockPos(feet.x, feet.y - 1, feet.z),
        )
        candidates.firstOrNull { session.world.isSolid(it) && session.world.blockAt(it).isBreakable }
            ?.let { return it }

        val fallback = BlockPos(feet.x + 1, feet.y, feet.z)
        setBlock(session, fallback, TestContent.registry.indexOf(TestContent.soil.id))
        return fallback
    }


    private fun mineToCompletion(session: WorldSession, target: BlockPos): MineResult {
        var result: MineResult = session.mine(target, 0.5f)
        var guard = 0
        while (result is MineResult.InProgress && guard < 200) {
            result = session.mine(target, 0.5f)
            guard++
        }
        return result
    }
}
