package com.stratum.engine.world

import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Movement against geometry built by hand.
 *
 * This is why it was worth pulling out of the session: a movement rule is a
 * function of input and terrain, and testing one should not require spawning a
 * world, a loot table and a monster director first.
 */
class PlayerMotionTest {

    private object EmptyTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private lateinit var world: MutableWorld
    private lateinit var motion: PlayerMotion

    @BeforeTest
    fun setUp() {
        world = StreamingWorld(TestContent.registry, EmptyTerrain, WorldConfig(simulationRadius = 1))
            .also { it.focusOn(ChunkPos(0, 0)) }
        motion = PlayerMotion(world)
        // A floor to stand on.
        for (y in 0 until 16) {
            for (x in 0 until 16) put(BlockPos(x, y, 4))
        }
    }

    private fun put(pos: BlockPos) =
        world.setBlock(pos, TestContent.registry.indexOf(TestContent.stone.id))

    private fun player(at: WorldPoint = WorldPoint(4.5f, 4.5f, 5f)) =
        PlayerState(heroClassId = "test", position = at)

    @Test
    fun `a centred stick is treated as no input`() {
        assertEquals(null, motion.aim(0.05f, -0.03f))
        assertEquals(WorldPoint.ZERO, motion.input)
    }

    @Test
    fun `a diagonal is not faster than a cardinal`() {
        motion.aim(1f, 1f)
        val diagonal = motion.input

        val length = kotlin.math.sqrt(diagonal.x * diagonal.x + diagonal.y * diagonal.y)
        assertEquals(1f, length, absoluteTolerance = 0.001f)
    }

    @Test
    fun `aiming reports the way the player should face`() {
        assertEquals(Direction.EAST, motion.aim(1f, 0f))
        assertEquals(Direction.WEST, motion.aim(-1f, 0f))
        assertEquals(Direction.SOUTH, motion.aim(0f, 1f))
        assertEquals(Direction.NORTH, motion.aim(0f, -1f))
    }

    @Test
    fun `speed is blocks per second, not per call`() {
        motion.aim(1f, 0f)
        val start = player()

        val oneBigStep = motion.advance(start, 1f)
        val manySmall = (1..10).fold(start) { acc, _ -> motion.advance(acc, 0.1f) }

        assertEquals(
            oneBigStep.position.x,
            manySmall.position.x,
            absoluteTolerance = 0.2f,
            message = "movement depended on how often it was ticked",
        )
    }

    @Test
    fun `walking into a wall at an angle slides along it`() {
        // A wall along the whole eastern edge of the floor.
        for (y in 0 until 16) put(BlockPos(6, y, 5))
        for (y in 0 until 16) put(BlockPos(6, y, 6))

        motion.aim(1f, 1f)
        val moved = motion.advance(player(WorldPoint(5.5f, 4.5f, 5f)), 0.3f)

        assertTrue(moved.position.x < 6f, "the player walked through the wall")
        assertTrue(
            moved.position.y > 4.5f,
            "the player stuck on the wall instead of sliding along it",
        )
    }

    @Test
    fun `a single step up is free and a taller wall is not`() {
        put(BlockPos(5, 4, 5))       // one step
        put(BlockPos(7, 4, 5))       // two steps
        put(BlockPos(7, 4, 6))

        motion.aim(1f, 0f)
        val climbed = motion.advance(player(WorldPoint(4.5f, 4.5f, 5f)), 0.2f)
        assertEquals(6f, climbed.position.z, "a single step was not climbed")

        val blocked = motion.step(player(WorldPoint(6.5f, 4.5f, 5f)), 1f, 0f)
        assertTrue(blocked.blocked, "a two-block wall was walked through")
    }

    @Test
    fun `nothing underfoot means falling`() {
        val floating = player(WorldPoint(4.5f, 4.5f, 9f))

        val settled = motion.advance(floating, 0.016f)

        assertEquals(5f, settled.position.z, "the player hovered instead of falling")
    }

    // ---- the roll --------------------------------------------------------

    @Test
    fun `a roll runs, grants i-frames, and the i-frames end first`() {
        assertIs<DodgeResult.Rolling>(motion.dodge(Direction.EAST, isAlive = true))
        assertTrue(motion.isRolling)
        assertTrue(motion.isInvulnerable)

        var p = player()
        // Past the invulnerability window but not past the roll.
        repeat(11) { p = motion.advance(p, 0.02f) }

        assertTrue(motion.isRolling, "the roll ended at the same time as the i-frames")
        assertTrue(!motion.isInvulnerable, "the i-frames outlasted their window")
    }

    @Test
    fun `rolling mid-roll says so rather than blaming the cooldown`() {
        motion.dodge(Direction.EAST, isAlive = true)
        assertIs<DodgeResult.AlreadyRolling>(motion.dodge(Direction.EAST, isAlive = true))
    }

    @Test
    fun `a second roll waits for the cooldown`() {
        motion.dodge(Direction.EAST, isAlive = true)
        var p = player()
        repeat(20) { p = motion.advance(p, 0.02f) }
        assertTrue(!motion.isRolling)

        assertIs<DodgeResult.OnCooldown>(motion.dodge(Direction.EAST, isAlive = true))
    }

    @Test
    fun `the dead do not roll`() {
        assertIs<DodgeResult.Rejected>(motion.dodge(Direction.EAST, isAlive = false))
    }

    @Test
    fun `a roll with a neutral stick goes where the player faces`() {
        motion.aim(0f, 0f)
        motion.dodge(Direction.EAST, isAlive = true)

        val rolled = motion.advance(player(), 0.1f)

        assertTrue(rolled.position.x > 4.5f, "a neutral-stick roll went nowhere")
    }

    @Test
    fun `a roll outruns a walk`() {
        motion.aim(1f, 0f)
        val walked = motion.advance(player(), 0.1f).position.x

        val fresh = PlayerMotion(world)
        fresh.aim(1f, 0f)
        fresh.dodge(Direction.EAST, isAlive = true)
        val rolled = fresh.advance(player(), 0.1f).position.x

        assertTrue(rolled > walked, "the roll was no faster than walking")
    }

    @Test
    fun `reset clears the roll and the input`() {
        motion.aim(1f, 1f)
        motion.dodge(Direction.EAST, isAlive = true)

        motion.reset()

        assertTrue(!motion.isRolling)
        assertTrue(!motion.isInvulnerable)
        assertEquals(WorldPoint.ZERO, motion.input)
        assertEquals(0f, motion.rollCooldownFraction)
    }
}
