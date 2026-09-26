package com.stratum.engine.crowd

import com.stratum.core.domain.actor.CombatRole
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open ground at z = 5, with a wall along x = 3 from y = -5 to y = 5. */
private val walled = NavGrid { x, y, _ -> if (x == 3 && y in -5..5) null else 5 }
private val open = NavGrid { _, _, _ -> 5 }

class FlowFieldTest {

    @Test
    fun `the field leads round a wall rather than into it`() {
        val field = FlowField.build(goalX = 0, goalY = 0, goalZ = 5, radius = 12, grid = walled)

        assertNull(field.distanceAt(3, 0), "the wall is not walkable")
        val straightAcross = field.distanceAt(6, 0)!!
        assertTrue(straightAcross > 60 + 20, "has to go round: $straightAcross")
        val step = field.directionAt(4, 0)!!
        assertTrue(step.x >= 0f, "from just behind the wall the way is round it, not through: $step")
    }

    @Test
    fun `a body can drop off a ledge but not climb one taller than a step`() {
        // A plateau three blocks up for x >= 2.
        val plateau = NavGrid { x, _, _ -> if (x >= 2) 8 else 5 }

        assertNotNull(FlowField.build(0, 0, 5, 6, plateau).distanceAt(4, 0), "from the plateau a body drops down to a goal below")
        assertNull(FlowField.build(4, 0, 8, 6, plateau).distanceAt(0, 0), "from below a body cannot climb three blocks to a goal on top")
    }

    @Test
    fun `the cache rebuilds only when the target changes cell`() {
        val cache = FlowFieldCache(radius = 4, minRebuildSeconds = 0f)
        val first = cache.fieldFor(0, 0, 5, open, 0.1f)

        assertTrue(first === cache.fieldFor(0, 0, 5, open, 0.1f))
        assertTrue(first !== cache.fieldFor(1, 0, 5, open, 0.1f))
    }
}

class CrowdBrainTest {

    private val target = CrowdTarget(Vec2(0f, 0f), 5)
    private val brain = CrowdBrain(CrowdConfig(maxAttackers = 4))
    private fun ring(count: Int, radius: Float, role: CombatRole = CombatRole.MELEE, squad: String? = null) = List(count) { i ->
        val a = i * 2 * Math.PI / count
        CrowdAgent("a$i", Vec2((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat()), 5, role, squad, reach = 1.2f)
    }

    @Test
    fun `only a few melee bodies attack at once and the rest circle their turn`() {
        val agents = ring(10, 4f)
        val intents = brain.think(agents, target, FlowField.build(0, 0, 5, 8, open), CrowdMemory(), 0.1f)

        val attacking = intents.values.count { it.stance == CrowdStance.ADVANCE || it.stance == CrowdStance.ATTACK }
        assertEquals(4, attacking)
        assertEquals(6, intents.values.count { it.stance == CrowdStance.CIRCLE })
    }

    @Test
    fun `the attackers keep their tokens from tick to tick`() {
        val memory = CrowdMemory()
        val agents = ring(8, 4f)
        brain.think(agents, target, null, memory, 0.1f)
        val first = memory.attackers
        brain.think(agents.map { it.copy(position = it.position * 1.01f) }, target, null, memory, 0.1f)

        assertEquals(first, memory.attackers)
    }

    @Test
    fun `swarmers ignore the queue and all rush`() {
        val intents = brain.think(ring(10, 4f, CombatRole.SWARMER), target, null, CrowdMemory(), 0.1f)

        assertTrue(intents.values.all { it.stance == CrowdStance.ADVANCE })
    }

    @Test
    fun `ranged bodies close to their reach and back off when crowded`() {
        val far = CrowdAgent("far", Vec2(12f, 0f), 5, CombatRole.RANGED, reach = 7f, aggroRange = 20f)
        val close = CrowdAgent("close", Vec2(0f, 2f), 5, CombatRole.RANGED, reach = 7f)
        val intents = brain.think(listOf(far, close), target, null, CrowdMemory(), 0.1f)

        assertEquals(CrowdStance.ADVANCE, intents.getValue("far").stance)
        assertTrue(intents.getValue("far").direction.x < 0f)
        assertEquals(CrowdStance.KITE, intents.getValue("close").stance)
        assertTrue(intents.getValue("close").direction.y > 0f, "backs away from the target")
    }

    @Test
    fun `when one of a pack sees the target the whole pack joins in`() {
        val scout = CrowdAgent("scout", Vec2(5f, 0f), 5, squadId = "p", aggroRange = 8f)
        val straggler = CrowdAgent("straggler", Vec2(30f, 0f), 5, squadId = "p", aggroRange = 8f)
        val loner = CrowdAgent("loner", Vec2(-30f, 0f), 5, aggroRange = 8f)
        val intents = brain.think(listOf(scout, straggler, loner), target, null, CrowdMemory(), 0.1f)

        assertEquals(CrowdStance.ADVANCE, intents.getValue("straggler").stance)
        assertEquals(CrowdStance.IDLE, intents.getValue("loner").stance)
    }

    @Test
    fun `a pack that loses its leader breaks and runs, once`() {
        val memory = CrowdMemory()
        val leader = CrowdAgent("boss", Vec2(3f, 0f), 5, squadId = "p", isLeader = true)
        val grunts = listOf(CrowdAgent("g1", Vec2(3f, 2f), 5, squadId = "p"), CrowdAgent("g2", Vec2(3f, -2f), 5, squadId = "p"))
        brain.think(listOf(leader) + grunts, target, null, memory, 0.1f)

        val broken = brain.think(grunts, target, null, memory, 0.1f)
        assertTrue(broken.values.all { it.stance == CrowdStance.FLEE })
        assertTrue(broken.values.all { it.direction.x > 0f }, "away from the target")

        val rallied = brain.think(grunts, target, null, memory, CrowdConfig().brokenSeconds + 1f)
        assertTrue(rallied.values.none { it.stance == CrowdStance.FLEE }, "a pack breaks once, then fights to the end")
    }

    @Test
    fun `wounded bodies that can flee do`() {
        val hurt = CrowdAgent("hurt", Vec2(2f, 0f), 5, healthFraction = 0.2f, fleeBelow = 0.3f)
        assertEquals(CrowdStance.FLEE, brain.think(listOf(hurt), target, null, CrowdMemory(), 0.1f).getValue("hurt").stance)
    }

    @Test
    fun `support stays behind its pack, away from the target`() {
        val front = listOf(CrowdAgent("m1", Vec2(2f, 1f), 5, squadId = "p"), CrowdAgent("m2", Vec2(2f, -1f), 5, squadId = "p"))
        val healer = CrowdAgent("healer", Vec2(2f, 0f), 5, CombatRole.SUPPORT, squadId = "p")
        val intent = brain.think(front + healer, target, null, CrowdMemory(), 0.1f).getValue("healer")

        assertEquals(CrowdStance.SUPPORT, intent.stance)
        assertTrue(intent.direction.x > 0f, "moves back from the line: ${intent.direction}")
    }

    @Test
    fun `bodies on the same spot are pushed apart, not left stacked`() {
        val a = CrowdAgent("a", Vec2(10f, 10f), 5, aggroRange = 1f)
        val b = CrowdAgent("b", Vec2(10f, 10f), 5, aggroRange = 1f)
        val intents = brain.think(listOf(a, b), target, null, CrowdMemory(), 0.1f)

        val da = intents.getValue("a").direction
        val db = intents.getValue("b").direction
        assertTrue(da.x * db.x + da.y * db.y < 0f, "they move apart: $da vs $db")
        assertTrue(intents.values.all { it.speedFactor > 0f })
    }

    @Test
    fun `a garrison with nothing to fight walks back to its post`() {
        val guard = CrowdAgent("guard", Vec2(20f, 0f), 5, home = Vec2(25f, 0f))
        val intent = brain.think(listOf(guard), target, null, CrowdMemory(), 0.1f).getValue("guard")

        assertEquals(CrowdStance.RETURN, intent.stance)
        assertTrue(intent.direction.x > 0f)
    }
}
