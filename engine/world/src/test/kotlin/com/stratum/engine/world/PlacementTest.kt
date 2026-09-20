package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placing a block used to be impossible. A tap resolves to a solid block — the
 * only thing the picker can find — and the placement was attempted *on* that
 * block, which is never air, so every placement was rejected as occupied. The
 * mining loop the same gesture had started kept running, so building read as
 * "it removes blocks".
 */
class PlacementTest {

    /** An empty world, so each test builds exactly the geometry its rule needs. */
    private object EmptyTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private lateinit var world: MutableWorld
    private lateinit var system: BlockInteractionSystem

    private val stone = TestContent.stone
    private val player = BlockPos(0, 0, 6)

    @BeforeTest
    fun setUp() {
        world = StreamingWorld(TestContent.registry, EmptyTerrain, WorldConfig(simulationRadius = 1))
            .also { it.focusOn(ChunkPos(0, 0)) }
        system = BlockInteractionSystem(world)
    }

    private fun put(pos: BlockPos, type: BlockType = stone) {
        world.setBlock(pos, TestContent.registry.indexOf(type.id))
    }

    @Test
    fun `a tap on a solid block resolves to the face above it`() {
        val picked = BlockPos(2, 0, 5)
        put(picked)

        val target = system.placementCellFor(picked, player)

        assertEquals(picked.above(), target, "the visible top face was not the first choice")
    }

    @Test
    fun `it never resolves to the block that was tapped`() {
        val picked = BlockPos(2, 0, 5)
        put(picked)

        val target = system.placementCellFor(picked, player)

        assertNotNull(target)
        assertTrue(target != picked, "the placement targeted the solid block itself")
        assertTrue(world.blockAt(target).isAir, "the placement cell was not empty")
    }

    @Test
    fun `a covered block builds out to the side nearest the player`() {
        val picked = BlockPos(2, 0, 5)
        put(picked)
        put(picked.above())

        val target = system.placementCellFor(picked, player)

        assertNotNull(target)
        assertEquals(picked.z, target.z, "it went vertical when the top was covered")
        // The player is at x=0, so the free side towards them is x=1.
        assertEquals(BlockPos(1, 0, 5), target, "it did not build back towards the player")
    }

    @Test
    fun `a block buried in solid rock offers nowhere to place`() {
        // Buried means the neighbours' tops are covered too, or there is still a
        // ledge to build a step on — which is the behaviour above, not a bug.
        val picked = BlockPos(2, 2, 5)
        for (x in 1..3) {
            for (y in 1..3) {
                for (z in 4..6) put(BlockPos(x, y, z))
            }
        }

        assertNull(system.placementCellFor(picked, player), "a buried block offered a cell")
    }

    @Test
    fun `a cell something is standing in is skipped, not offered and refused`() {
        val picked = BlockPos(1, 0, 5)
        put(picked)
        // Standing directly on it: the top face is inside the player.
        val standing = setOf(picked.above(), picked.above(2))

        val target = system.placementCellFor(picked, player, blocked = standing)

        assertNotNull(target)
        assertTrue(target !in standing, "it offered a cell the player is standing in")
    }

    // ---- through the session --------------------------------------------

    private fun session() = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = 5L, simulationRadius = 1),
    )

    @Test
    fun `placing actually puts a block in the world and spends it`() {
        val session = session()
        val blockId = session.player.selectedBlockId
        assertNotNull(blockId)
        val held = session.player.countOf(blockId)
        val picked = session.player.blockPos.below()
        val target = session.placementPreviewFor(picked)
        assertNotNull(target, "nowhere to place next to the ground the player stands on")

        val result = session.place(picked)

        assertIs<PlaceResult.Placed>(result)
        assertTrue(!session.world.blockAt(target).isAir, "nothing was placed")
        assertEquals(held - 1, session.player.countOf(blockId), "the block was not spent")
    }

    @Test
    fun `placing under your own feet builds beside you rather than failing`() {
        val session = session()
        val underfoot = session.player.blockPos.below()

        val target = session.placementPreviewFor(underfoot)

        assertNotNull(target, "tapping the ground you stand on offered nowhere to build")
        assertTrue(target != session.player.feet, "it tried to place inside the player")
        assertIs<PlaceResult.Placed>(session.place(underfoot))
    }

    @Test
    fun `placing stops a dig already in progress`() {
        val session = session()
        val digging = session.player.blockPos.below()
        session.mine(digging, 0.01f)
        assertTrue(session.miningFraction > 0f, "the fixture did not start a dig")

        session.place(digging)

        assertEquals(0f, session.miningFraction, "the dig survived a placement")
    }

    @Test
    fun `a refused placement does not spend the block`() {
        val session = session()
        val blockId = session.player.selectedBlockId!!
        val held = session.player.countOf(blockId)

        // Far out of reach: resolvable, but the placement itself is refused.
        val distant = BlockPos(session.player.blockPos.x + 40, session.player.blockPos.y, 5)
        val result = session.place(distant)

        assertIs<PlaceResult.Rejected>(result)
        assertEquals(held, session.player.countOf(blockId), "a refused placement still spent a block")
    }
}
