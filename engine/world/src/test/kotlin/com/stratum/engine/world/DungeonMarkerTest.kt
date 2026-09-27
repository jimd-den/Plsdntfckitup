package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.world.BlockRegistry
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.DungeonLayout
import com.stratum.core.domain.world.MarkedWorld
import com.stratum.core.domain.world.StructureTemplate
import com.stratum.core.domain.world.TerrainGenerator
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DungeonMarkerTest {

    private val floor = CombatCoreFixtures.FLOOR
    private val stand = floor + 1

    /** Flat stone with one small hand-built "dungeon": a few markers a short walk east of the spawn. */
    private class MarkedArena(private val markers: List<WorldMarker>) : TerrainGenerator, MarkedWorld {
        var asked = 0
        override fun generate(pos: ChunkPos, registry: BlockRegistry): Chunk = CombatCoreFixtures.FlatTerrain.generate(pos, registry)
        override fun markersIn(pos: ChunkPos): List<WorldMarker> {
            asked++
            return markers.filter { ChunkPos.containing(it.x, it.y) == pos }
        }
    }

    private val crypt = StructureTemplate(
        id = "test:crypt", name = "Crypt",
        dungeon = DungeonLayout(
            floorBlockId = TestContent.stone.id, wallBlockId = TestContent.stone.id,
            enemyIds = listOf(CombatCoreFixtures.brute.id), bossId = CombatCoreFixtures.overlord.id,
        ),
    )

    private fun session(markers: List<WorldMarker>, seed: Long = 5L): Pair<WorldSession, MarkedArena> {
        val arena = MarkedArena(markers)
        val content = ContentPackAssembler().assemble(listOf(CombatCoreFixtures.pack().copy(structureTemplates = listOf(crypt))))
        val session = WorldSession(content, WorldConfig(seed = seed, simulationRadius = 1), terrainGenerator = arena)
        session.enemies = emptyList()
        return session to arena
    }

    private fun marker(kind: WorldMarkerKind, x: Int, y: Int = 0, ref: String? = null, z: Int = stand) = WorldMarker(kind, x, y, z, crypt.id, ref)

    @Test
    fun `a boss marker wakes its structure's boss at boss rank`() {
        val (session, _) = session(listOf(marker(WorldMarkerKind.BOSS, 6)))
        session.tick(0.01f)
        val boss = session.enemies.single()
        assertEquals(CombatCoreFixtures.overlord.id, boss.definitionId)
        assertEquals(EnemyRank.BOSS, boss.rank)
        assertEquals(6.5f, boss.position.x, 0.05f)
    }

    @Test
    fun `a spawn marker takes its own monster, and otherwise its structure's list`() {
        val (session, _) = session(listOf(marker(WorldMarkerKind.ENEMY_SPAWN, 5, ref = "archer"), marker(WorldMarkerKind.ENEMY_SPAWN, 7, 2)))
        session.tick(0.01f)
        val ids = session.enemies.map { it.definitionId }.toSet()
        assertEquals(setOf(CombatCoreFixtures.archer.id, CombatCoreFixtures.brute.id), ids)
        // Placed on purpose, so the rank is the data's, not the director's dice.
        assertTrue(session.enemies.all { it.rank == EnemyRank.MINION })
    }

    @Test
    fun `a boss marker's phases are live`() {
        val (session, _) = session(listOf(marker(WorldMarkerKind.BOSS, 3)))
        session.tick(0.01f)
        val boss = session.enemies.single()
        session.enemies = listOf(boss.copy(health = boss.stats.maxHealth / 3))
        val events = session.tick(0.05f)
        assertTrue(events.any { it is CombatEvent.BossPhaseBegan }, "the first phase should begin below half health")
    }

    @Test
    fun `a loot marker drops a chest roll at the chest floor`() {
        val (session, _) = session(listOf(marker(WorldMarkerKind.LOOT, 8), marker(WorldMarkerKind.LOOT, 9, 3)))
        session.tick(0.01f)
        assertEquals(2, session.groundLoot.size)
        assertTrue(session.groundLoot.all { it.item.rarity >= ItemRarity.RARE || !it.item.rarity.isRolled })
    }

    @Test
    fun `markers wait until the player is near, in height as well as across`() {
        val below = marker(WorldMarkerKind.ENEMY_SPAWN, 4, ref = "dummy", z = stand - 10)
        val far = marker(WorldMarkerKind.ENEMY_SPAWN, 28, ref = "dummy")
        val (session, _) = session(listOf(below, far))
        session.tick(0.01f)
        assertTrue(session.enemies.isEmpty(), "nothing is near enough yet")

        session.player = session.player.copy(position = WorldPoint(22.5f, 0.5f, stand.toFloat()))
        session.tick(0.01f)
        assertEquals(1, session.enemies.size, "walking east wakes the far marker, and the deep one stays asleep")
    }

    @Test
    fun `a marker is peopled once, even when its chunk streams out and back`() {
        val (session, arena) = session(listOf(marker(WorldMarkerKind.ENEMY_SPAWN, 6, ref = "dummy"), marker(WorldMarkerKind.LOOT, 7)))
        session.tick(0.01f)
        assertEquals(1, session.enemies.size)
        assertEquals(1, session.groundLoot.size)
        session.enemies = emptyList()
        session.groundLoot = emptyList()
        val askedBefore = arena.asked

        val away = 6 * Chunk.SIZE + 0.5f
        session.player = session.player.copy(position = WorldPoint(away, 0.5f, stand.toFloat()))
        session.tick(0.01f)
        session.player = session.player.copy(position = WorldPoint(0.5f, 0.5f, stand.toFloat()))
        session.tick(0.01f)

        assertTrue(arena.asked > askedBefore, "the chunk was streamed back in and asked again")
        assertTrue(session.enemies.isEmpty(), "a cleared marker does not refill")
        assertTrue(session.groundLoot.isEmpty(), "an opened chest does not refill")
    }

    @Test
    fun `the same seed peoples the same markers the same way`() {
        val markers = (0 until 6).map { marker(WorldMarkerKind.ENEMY_SPAWN, 3 + it, it % 3) } + marker(WorldMarkerKind.LOOT, 10)
        fun people(): List<Any> {
            val (session, _) = session(markers)
            session.tick(0.01f)
            return session.enemies.map { it.definitionId to it.position } + session.groundLoot.map { it.item.name }
        }
        assertEquals(people(), people())
    }

    @Test
    fun `a placed monster keeps its definition's rank`() {
        val (session, _) = session(emptyList())
        val placed = session.spawn(CombatCoreFixtures.overlord.copy(rank = EnemyRank.CHAMPION), session.player.position)
        assertEquals(EnemyRank.CHAMPION, placed.rank)
        assertNotNull(session.enemies.firstOrNull { it.instanceId == placed.instanceId })
    }
}
