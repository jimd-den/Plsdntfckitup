package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hit that changes only a number is a hit the player has to read rather than
 * feel: a heavy blow and a glancing one look identical while they land.
 */
class ImpactFieldTest {

    private object EmptyTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private lateinit var world: MutableWorld
    private lateinit var impacts: ImpactField

    private val attacker = WorldPoint(4f, 4f, 5f)
    private val target = WorldPoint(6f, 4f, 5f)

    @BeforeTest
    fun setUp() {
        world = StreamingWorld(TestContent.registry, EmptyTerrain, WorldConfig(simulationRadius = 1))
            .also { it.focusOn(ChunkPos(0, 0)) }
        impacts = ImpactField(world)
    }

    private fun put(pos: BlockPos) =
        world.setBlock(pos, TestContent.registry.indexOf(TestContent.stone.id))

    @Test
    fun `a hit shoves the target away from whoever landed it`() {
        impacts.strike("rat", from = attacker, to = target, force = 20f)

        val moved = impacts.advance(0.05f, mapOf("rat" to target))

        val after = moved.getValue("rat")
        assertTrue(after.x > target.x, "the target was not pushed away")
        assertEquals(target.y, after.y, absoluteTolerance = 0.001f, message = "it drifted sideways")
    }

    @Test
    fun `a heavier blow throws further`() {
        val light = ImpactField(world).apply { strike("a", attacker, target, force = 5f) }
        val heavy = ImpactField(world).apply { strike("a", attacker, target, force = 40f) }

        val lightAfter = light.advance(0.05f, mapOf("a" to target)).getValue("a")
        val heavyAfter = heavy.advance(0.05f, mapOf("a" to target)).getValue("a")

        assertTrue(
            heavyAfter.x > lightAfter.x,
            "a heavy hit shoved no further than a light one",
        )
    }

    @Test
    fun `however big the hit, nothing flies across the screen`() {
        impacts.strike("rat", attacker, target, force = 100_000f)

        val after = impacts.advance(0.05f, mapOf("rat" to target)).getValue("rat")

        assertTrue(after.x - target.x < 1f, "a huge hit teleported the target")
    }

    @Test
    fun `the shove decays instead of resolving in one frame`() {
        impacts.strike("rat", attacker, target, force = 30f)

        var position = target
        val steps = mutableListOf<Float>()
        repeat(6) {
            position = impacts.advance(0.05f, mapOf("rat" to position))["rat"] ?: position
            steps += position.x
        }

        val first = steps[1] - steps[0]
        val last = steps[steps.lastIndex] - steps[steps.lastIndex - 1]
        assertTrue(last < first, "the shove did not slow down: $steps")
    }

    @Test
    fun `it ends, rather than sliding forever`() {
        impacts.strike("rat", attacker, target, force = 30f)

        var position = target
        repeat(60) {
            position = impacts.advance(0.05f, mapOf("rat" to position))["rat"] ?: position
        }

        assertTrue(!impacts.isReeling("rat"), "the knockback never came to rest")
    }

    @Test
    fun `knockback respects walls`() {
        // A wall immediately east of the target, which is the way it is shoved.
        put(BlockPos(7, 4, 5))
        impacts.strike("rat", attacker, target, force = 60f)

        var position = target
        repeat(10) {
            position = impacts.advance(0.05f, mapOf("rat" to position))["rat"] ?: position
        }

        assertTrue(position.x < 7f, "the target was shoved through a wall")
    }

    @Test
    fun `two hits in a moment throw harder than one`() {
        val once = ImpactField(world).apply { strike("a", attacker, target, force = 20f) }
        val twice = ImpactField(world).apply {
            strike("a", attacker, target, force = 20f)
            strike("a", attacker, target, force = 20f)
        }

        val onceAfter = once.advance(0.05f, mapOf("a" to target)).getValue("a")
        val twiceAfter = twice.advance(0.05f, mapOf("a" to target)).getValue("a")

        assertTrue(twiceAfter.x > onceAfter.x, "a second hit added nothing")
    }

    @Test
    fun `a hit landed from exactly on top of the target is ignored`() {
        impacts.strike("rat", from = target, to = target, force = 20f)

        assertTrue(!impacts.isReeling("rat"), "a zero-length shove was accepted")
    }

    @Test
    fun `intensity reports how hard, so a renderer can scale the recoil`() {
        assertEquals(0f, impacts.intensity("rat"))

        impacts.strike("rat", attacker, target, force = 40f)
        val hard = impacts.intensity("rat")

        assertTrue(hard > 0f && hard <= 1f, "intensity was $hard")
    }

    @Test
    fun `an actor that no longer exists stops being pushed`() {
        impacts.strike("rat", attacker, target, force = 20f)

        // Nothing at that id any more, as after a death.
        val moved = impacts.advance(0.05f, emptyMap())

        assertTrue(moved.isEmpty())
        assertTrue(!impacts.isReeling("rat"), "a corpse kept its knockback")
    }
}
