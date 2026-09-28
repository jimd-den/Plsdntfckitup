package com.stratum.engine.world

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.KeyedTrigger
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.World
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectileSystemTest {

    /** Open air except the cells listed. */
    private class Walls(private val solid: Set<BlockPos> = emptySet()) : World {
        override val registry: BlockRegistry = TestContent.registry
        override val loadedChunks: Collection<Chunk> = emptyList()
        override fun chunkAt(pos: ChunkPos): Chunk? = null
        override fun blockAt(pos: BlockPos): BlockType = if (pos in solid) TestContent.stone else BlockType.AIR
        override fun blockIndexAt(pos: BlockPos): Int = 0
        override fun lightAt(pos: BlockPos): Int = 15
        override fun surfaceAt(x: Int, y: Int): Int = 0
        override fun isLoaded(pos: ChunkPos): Boolean = true
    }

    private fun shot(pierce: Int = 0, chain: Int = 0, fork: Int = 0, collides: Boolean = true) = Projectile(
        id = 0, skillId = "s", casterId = "player", side = CombatSide.PLAYER, position = WorldPoint(0.5f, 0.5f, 5.9f),
        dx = 1f, dy = 0f, speed = 10f, range = 20f, radius = 0.3f, pierce = pierce, chain = chain, fork = fork, collidesWithBlocks = collides,
    )

    private fun body(id: String, x: Float, y: Float = 0.5f) = Candidate(id, WorldPoint(x, y, 5f))

    private fun ProjectileSystem.flyFor(seconds: Float, targets: List<Candidate>): List<ProjectileImpact> {
        val impacts = mutableListOf<ProjectileImpact>()
        repeat((seconds / 0.05f).toInt()) { impacts += advance(0.05f) { targets } }
        return impacts
    }

    @Test
    fun `a projectile hits the first body in its path and stops`() {
        val system = ProjectileSystem(Walls()).apply { launch(shot()) }
        val impacts = system.flyFor(2f, listOf(body("near", 4f), body("far", 8f)))
        assertEquals(listOf("near"), impacts.map { it.targetId })
        assertTrue(system.active.isEmpty())
    }

    @Test
    fun `pierce carries it through`() {
        val system = ProjectileSystem(Walls()).apply { launch(shot(pierce = 1)) }
        assertEquals(listOf("near", "far"), system.flyFor(2f, listOf(body("near", 4f), body("far", 8f))).map { it.targetId })
    }

    @Test
    fun `a wall stops it unless it is spectral`() {
        val wall = (0..8).map { BlockPos(2, it - 4, 5) }.toSet()
        assertTrue(ProjectileSystem(Walls(wall)).apply { launch(shot()) }.flyFor(2f, listOf(body("behind", 4f))).isEmpty())
        assertEquals(1, ProjectileSystem(Walls(wall)).apply { launch(shot(collides = false)) }.flyFor(2f, listOf(body("behind", 4f))).size)
    }

    @Test
    fun `a chain leaps to the nearest body it has not hit`() {
        val system = ProjectileSystem(Walls()).apply { launch(shot(chain = 2)) }
        val impacts = system.flyFor(3f, listOf(body("first", 3f), body("side", 3f, 4f), body("far", 3f, 20f)))
        assertEquals(listOf("first", "side"), impacts.map { it.targetId })
    }

    @Test
    fun `a fork splits in two and neither half returns to what it hit`() {
        val system = ProjectileSystem(Walls()).apply { launch(shot(fork = 1)) }
        val impacts = system.flyFor(0.4f, listOf(body("first", 3f)))
        assertEquals(listOf("first"), impacts.map { it.targetId })
        assertEquals(2, system.active.size)
    }

    @Test
    fun `projectiles are fanned evenly across the spread`() {
        val fan = ProjectileSystem.fan(Aim(1f, 0f), 3, 90f)
        assertEquals(3, fan.size)
        assertEquals(0f, fan[1].dy, 1e-4f)
        assertEquals(-fan[0].dy, fan[2].dy, 1e-4f)
    }

    // ---- the trigger guards, on their own -----------------------------------------

    private val onHit = KeyedTrigger("k", TriggerDefinition(TriggerEvent.ON_HIT, cooldownSeconds = 1f))

    @Test
    fun `a trigger waits out its cooldown`() {
        val engine = TriggerEngine(CombatRules())
        assertEquals(1, engine.fire(TriggerEvent.ON_HIT, listOf(onHit), 0, emptySet(), Random(1)).size)
        assertTrue(engine.fire(TriggerEvent.ON_HIT, listOf(onHit), 0, emptySet(), Random(1)).isEmpty())
        engine.advance(1f)
        assertEquals(1, engine.fire(TriggerEvent.ON_HIT, listOf(onHit), 0, emptySet(), Random(1)).size)
    }

    @Test
    fun `nothing triggers at the depth limit`() {
        val engine = TriggerEngine(CombatRules(triggerDepth = 2))
        assertTrue(engine.fire(TriggerEvent.ON_HIT, listOf(onHit), 2, emptySet(), Random(1)).isEmpty())
        assertTrue(!engine.allowChainedCast(2))
        assertTrue(engine.allowChainedCast(1))
    }

    @Test
    fun `the budget caps one action however wide it branches`() {
        val engine = TriggerEngine(CombatRules(triggerBudget = 3, triggerCooldownFloor = 0f))
        val free = List(10) { KeyedTrigger("k$it", TriggerDefinition(TriggerEvent.ON_HIT, cooldownSeconds = 0f)) }
        assertEquals(3, engine.fire(TriggerEvent.ON_HIT, free, 0, emptySet(), Random(1)).size)
        engine.beginAction()
        assertEquals(3, engine.fire(TriggerEvent.ON_HIT, free, 0, emptySet(), Random(1)).size)
    }

    @Test
    fun `a trigger that only answers some skills ignores the rest`() {
        val engine = TriggerEngine(CombatRules())
        val spellsOnly = KeyedTrigger("s", TriggerDefinition(TriggerEvent.ON_CRIT, requiresTags = setOf("spell")))
        assertTrue(engine.fire(TriggerEvent.ON_CRIT, listOf(spellsOnly), 0, setOf("attack"), Random(1)).isEmpty())
        assertEquals(1, engine.fire(TriggerEvent.ON_CRIT, listOf(spellsOnly), 0, setOf("spell"), Random(1)).size)
    }
}
