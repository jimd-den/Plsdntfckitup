package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.TerrainChange
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.settings.VoxelDestruction
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Thrown ground, fallen bodies, and monsters that never share a move. */
class PhysicsAndVarietyTest {

    private object Empty : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private val stone = TestContent.registry.indexOf(TestContent.stone.id)
    private val soil = TestContent.registry.indexOf(TestContent.soil.id)

    /** Stone floor with its top at z = 5, three blocks of soil on it in the middle. */
    private fun world(): MutableWorld {
        val w = StreamingWorld(TestContent.registry, Empty, WorldConfig(simulationRadius = 1)).also { it.focusOn(ChunkPos(0, 0)) }
        for (x in -12 until 28) for (y in -12 until 28) for (z in 0..5) w.setBlock(BlockPos(x, y, z), stone)
        for (x in 6..10) for (y in 6..10) for (z in 6..8) w.setBlock(BlockPos(x, y, z), soil)
        return w
    }

    @Test
    fun `thrown blocks fly, fall and settle back into the world`() {
        val w = world()
        val debris = DebrisField(w, max = 50)
        debris.launch(BlockPos(2, 2, 6), soil, 4f, 0f, 8f)
        var t = 0f
        var highest = 0f
        while (debris.count > 0 && t < 10f) { debris.advance(1f / 30f); t += 1f / 30f; debris.pieces().firstOrNull()?.let { highest = maxOf(highest, it.z) } }
        assertEquals(0, debris.count, "the block never came to rest")
        assertTrue(highest > 7.5f, "it never flew: highest $highest")
        assertEquals(1, debris.settled)
        // It lies on the floor, somewhere east of where it left.
        val landed = (3..20).flatMap { x -> (0..4).map { y -> BlockPos(x, y, 6) } }.filter { w.blockIndexAt(it) == soil }
        assertEquals(1, landed.size, "it did not land as one block on the floor")
    }

    @Test
    fun `past the cap the oldest piece settles at once`() {
        val w = world()
        val debris = DebrisField(w, max = 3)
        repeat(5) { debris.launch(BlockPos(0, it * 2, 12), soil, 0f, 0f, 5f) }
        assertEquals(3, debris.count)
        assertEquals(2, debris.settled, "the two oldest were laid down early")
    }

    @Test
    fun `a crater in physics mode throws its blocks instead of deleting them`() {
        val w = world()
        val physics = TerrainImpacts(w, VoxelDestruction.PHYSICS, DebrisField(w, 200))
        physics.apply(SkillEffect.Terrain(TerrainChange.CRATER, radius = 2f), WorldPoint(8.5f, 8.5f, 9f), Aim(1f, 0f))
        val flying = physics.debris!!.count
        assertTrue(flying >= 10, "only $flying blocks flew")
        repeat(400) { physics.advance(1f / 30f) }
        assertEquals(0, physics.debris!!.count)
        assertTrue(physics.debris!!.settled >= flying / 2, "most thrown blocks should settle: ${physics.debris!!.settled} of $flying")

        val w2 = world()
        val off = TerrainImpacts(w2, VoxelDestruction.OFF, null)
        off.apply(SkillEffect.Terrain(TerrainChange.CRATER, radius = 2f), WorldPoint(8.5f, 8.5f, 9f), Aim(1f, 0f))
        assertEquals(soil, w2.blockIndexAt(BlockPos(8, 8, 8)), "destruction off still dug")
    }

    @Test
    fun `a fallen body tumbles away from the blow, lies on the ground, keeps its shape and fades`() {
        val w = world()
        val dolls = Ragdolls(w)
        dolls.spawn("m", WorldPoint(0.5f, 0.5f, 6f), 0f, 1f, 6f, 0f, 0xFF884422, 1f)
        repeat(90) { dolls.advance(1f / 30f) }
        val pose = dolls.poses().single()
        val p = pose.points
        // Lying down: the head is near the floor, not a person's height above it.
        assertTrue(p[2] < 6.8f, "still standing: head at ${p[2]}")
        assertTrue((0 until Ragdolls.POINTS).all { p[it * 3 + 2] >= 5.99f }, "a joint sank into the floor")
        assertTrue(p[3 * 1] > 0.6f, "it did not move with the blow: chest x ${p[3]}")
        // Bones keep their length within a tenth.
        fun d(a: Int, b: Int) = sqrt((p[a * 3] - p[b * 3]).let { it * it } + (p[a * 3 + 1] - p[b * 3 + 1]).let { it * it } + (p[a * 3 + 2] - p[b * 3 + 2]).let { it * it })
        assertTrue(kotlin.math.abs(d(1, 2) - 0.4f) < 0.06f, "the spine stretched to ${d(1, 2)}")
        repeat(200) { dolls.advance(1f / 30f) }
        assertEquals(0, dolls.count, "the body never faded")
    }

    @Test
    fun `a body thrown at a wall stays out of it`() {
        val w = world()
        for (y in -4..4) for (z in 6..9) w.setBlock(BlockPos(2, y, z), stone)
        val dolls = Ragdolls(w)
        dolls.spawn("m", WorldPoint(0.5f, 0.5f, 6f), 1f, 0f, 14f, 0f, 0xFF884422, 1f)
        repeat(120) { dolls.advance(1f / 30f) }
        val p = dolls.poses().single().points
        assertTrue((0 until Ragdolls.POINTS).none { j -> w.isSolid(BlockPos(kotlin.math.floor(p[j * 3]).toInt(), kotlin.math.floor(p[j * 3 + 1]).toInt(), kotlin.math.floor(p[j * 3 + 2]).toInt())) }, "a joint went into the wall")
    }

    @Test
    fun `monsters of one kind fighting together never share an attack while the pool allows`() {
        val pool = (0 until 6).map { MonsterSkill("forge:a$it", weight = 60) } + MonsterSkill("t:bite")
        val kind = EnemyDefinition("t:jackal", "Jackal", baseStats = CombatStats(), damageTypeId = TestContent.physical.id, skills = pool)
        fun body(i: Int) = EnemyInstance("j$i", kind.id, "Jackal", EnemyRank.MINION, WorldPoint(i.toFloat(), 0f, 6f), 10, CombatStats(), TestContent.physical.id)
        val spread = AttackSpread({ kind.takeIf { k -> k.id == it } }, perMonster = 2, distinct = true)
        val dealt = spread.deal(List(3) { body(it) })
        val hands = dealt.map { it.skillPool!! }
        assertTrue(hands.all { it.size == 2 })
        assertEquals(6, hands.flatten().toSet().size, "three jackals shared an attack: $hands")
        assertEquals(dealt, spread.deal(dealt), "a body was dealt twice")
        // Its kind's own skills stay its own; only the forged pool is narrowed.
        val abilities = MonsterAbilities(TestContent.assembled) { id -> kind.takeIf { it.id == id } }
        val usable = abilities.currentSkills(dealt[0]).map { it.skillId }
        assertTrue("t:bite" in usable && usable.count { it.startsWith("forge:") } == 2, "$usable")
    }
}
