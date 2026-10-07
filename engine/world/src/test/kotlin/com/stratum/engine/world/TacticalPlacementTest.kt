package com.stratum.engine.world

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MutableWorld
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Squads read the ground: heights for shooters, narrows for brutes, cover for swarmers. */
class TacticalPlacementTest {

    private object EmptyTerrain : TerrainGenerator {
        override fun generate(pos: ChunkPos, registry: BlockRegistry) = Chunk(pos)
    }

    private val stone = TestContent.registry.indexOf(TestContent.stone.id)

    /** A flat stone floor whose top is z = 5, so a body stands at z = 6. */
    private fun flat(): MutableWorld {
        val world = StreamingWorld(TestContent.registry, EmptyTerrain, WorldConfig(simulationRadius = 2)).also { it.focusOn(ChunkPos(0, 0)) }
        for (x in -32 until 48) for (y in -32 until 48) for (z in 0..5) world.setBlock(BlockPos(x, y, z), stone)
        return world
    }

    private fun MutableWorld.box(x: IntRange, y: IntRange, z: IntRange) {
        for (a in x) for (b in y) for (c in z) setBlock(BlockPos(a, b, c), stone)
    }

    private val player = WorldPoint(8.5f, 8.5f, 6f)
    private val inner = 9f
    private val outer = 22f

    @Test
    fun `high ground three blocks up with a line to the player is a vantage`() {
        val world = flat().apply { box(20..24, 0..16, 6..9) }
        val spots = TacticalTerrain(world).profile(player, inner, outer)
        val heights = spots.filter { it.has(TerrainFeature.VANTAGE) }
        assertTrue(heights.isNotEmpty(), "no vantage on a four-block plateau")
        assertTrue(heights.all { it.rise >= TacticalTerrain.VANTAGE_RISE })
        // Flat ground beside the player is nobody's high ground.
        assertTrue(spots.filter { it.rise == 0 }.none { it.has(TerrainFeature.VANTAGE) })
    }

    @Test
    fun `a passage two wide is a choke and open ground is not`() {
        val world = flat().apply {
            box(0..30, 20..20, 6..12)
            box(0..30, 23..23, 6..12)
        }
        val terrain = TacticalTerrain(world)
        assertTrue(terrain.spotAt(player, 15, 21)!!.has(TerrainFeature.CHOKE), "the corridor is not a choke")
        assertTrue(!terrain.spotAt(player, 15, 0)!!.has(TerrainFeature.CHOKE), "an open field is a choke")
    }

    @Test
    fun `behind a wall the player cannot see is concealed, and behind them is a flank`() {
        val world = flat().apply { box(-2..-2, -4..20, 6..10) }
        val terrain = TacticalTerrain(world)
        assertTrue(terrain.spotAt(player, -6, 8)!!.has(TerrainFeature.CONCEALED))
        assertTrue(!terrain.spotAt(player, 20, 8)!!.has(TerrainFeature.CONCEALED))
        // Looking along +x, the ground at -x is over the shoulder.
        assertTrue(terrain.spotAt(player, -6, 8, facing = 0f)!!.has(TerrainFeature.FLANK))
        assertTrue(!terrain.spotAt(player, 20, 8, facing = 0f)!!.has(TerrainFeature.FLANK))
    }

    @Test
    fun `squads take the formation their roles suit`() {
        fun first(vararg roles: CombatRole) = EncounterCell.of(roles.toList()).formation
        assertEquals(FormationPattern.AMBUSH_FLANK, first(CombatRole.SWARMER, CombatRole.SWARMER, CombatRole.SWARMER))
        assertEquals(FormationPattern.BUNKER, first(CombatRole.BRUTE, CombatRole.RANGED, CombatRole.RANGED))
        assertEquals(FormationPattern.ESCORT, first(CombatRole.SUPPORT, CombatRole.MELEE, CombatRole.MELEE))
        assertEquals(FormationPattern.PINCER, first(CombatRole.MELEE, CombatRole.MELEE))
        assertEquals(4, EncounterCell.of(List(4) { CombatRole.SWARMER }).size)
    }

    @Test
    fun `shooters take the heights and the brute holds the narrows`() {
        val world = flat().apply {
            // A ridge behind a two-wide pass, both on the far side from the player.
            box(18..30, -10..30, 6..9)
            for (x in 18..30) for (y in 7..8) for (z in 6..9) setBlock(BlockPos(x, y, z), BlockRegistry.AIR_INDEX)
        }
        val terrain = TacticalTerrain(world)
        val placement = TacticalPlacer(terrain).place(
            listOf(CombatRole.BRUTE, CombatRole.RANGED, CombatRole.RANGED), player, WorldPoint(22f, 8f, 6f), inner, outer, Random(3),
        )
        assertEquals(FormationPattern.BUNKER, placement.formation)
        val brute = placement.positions[0]
        assertTrue(terrain.spotAt(player, floor(brute.x).toInt(), floor(brute.y).toInt())!!.has(TerrainFeature.CHOKE), "brute at $brute")
        placement.positions.drop(1).forEach { p ->
            assertTrue(p.z - player.z >= TacticalTerrain.VANTAGE_RISE, "a shooter stood low at $p")
        }
    }

    @Test
    fun `swarmers wait out of sight on the player's flanks`() {
        val world = flat().apply {
            // Walls on either side and behind: plenty of cover.
            box(-4..-4, -10..26, 6..10)
            box(-10..26, -4..-4, 6..10)
            box(-10..26, 21..21, 6..10)
        }
        val terrain = TacticalTerrain(world)
        val placement = TacticalPlacer(terrain).place(List(4) { CombatRole.SWARMER }, player, WorldPoint(25f, 8f, 6f), inner, outer, Random(5), facing = 0f)
        assertEquals(FormationPattern.AMBUSH_FLANK, placement.formation)
        val hidden = placement.positions.count { p ->
            val s = terrain.spotAt(player, floor(p.x).toInt(), floor(p.y).toInt(), facing = 0f)!!
            s.has(TerrainFeature.CONCEALED) || s.has(TerrainFeature.FLANK)
        }
        assertTrue(hidden >= 3, "only $hidden of 4 swarmers hid")
    }

    @Test
    fun `with no cover an ambush closes as a pincer from both sides`() {
        val terrain = TacticalTerrain(flat())
        val anchor = WorldPoint(25f, 8.5f, 6f)
        val placement = TacticalPlacer(terrain).place(List(4) { CombatRole.SWARMER }, player, anchor, inner, outer, Random(7))
        assertEquals(FormationPattern.PINCER, placement.formation)
        val sides = placement.positions.map { p -> TacticalTerrain.angleBetween(0f, kotlin.math.atan2(p.y - player.y, p.x - player.x)) > 0f }
        assertTrue(sides.any { it } && sides.any { !it }, "the pincer had one jaw: $sides")
    }

    @Test
    fun `the director stands a hunting pack tactically, the same way from the same seed`() {
        val world = flat().apply { box(20..24, 0..16, 6..9) }
        val content = TestContent.assembled
        val ranged = TestContent.rat.copy(id = "t:archer", role = CombatRole.RANGED)
        val brute = TestContent.rat.copy(id = "t:brute", role = CombatRole.BRUTE)
        val director = EnemyDirector(world, content.enemies + ranged + brute)
        val pack = EnemyPackDefinition("t:band", "Band", leaderId = brute.id, members = listOf(PackMember(ranged.id, 2)))
        fun spawn() = director.spawnPack(pack, WorldPoint(18f, 8f, 6f), 1, Random(11), focus = player)
        val band = spawn()
        assertEquals(3, band.size)
        assertEquals(1, band.count { it.isLeader })
        assertEquals(band.map { it.position }, spawn().map { it.position })
        band.forEach { e ->
            val d = e.position.horizontalDistanceTo(player)
            assertTrue(d >= inner - 1f && d <= outer + 1f, "${e.definitionId} at $d blocks")
            assertTrue(!world.isSolid(BlockPos(floor(e.position.x).toInt(), floor(e.position.y).toInt(), floor(e.position.z).toInt())), "buried")
        }
        assertTrue(band.filter { it.definitionId == ranged.id }.all { it.position.z - player.z >= 3f }, "archers stayed low")
    }
}
