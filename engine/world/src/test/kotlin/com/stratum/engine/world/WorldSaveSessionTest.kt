package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.session.WorldSave
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.StandardStrategy
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.BlockType
import com.stratum.core.domain.world.Chunk
import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.core.domain.world.WorldPoint
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldSaveSessionTest {

    private val log = BlockType("t:log", "Log", BlockMaterial.WOOD)

    /** The test pack with nothing wandering: what is in the world is only what the test put there. */
    private val content: AssembledContent = ContentPackAssembler().assemble(
        listOf(TestContent.pack.copy(enemies = TestContent.enemies.map { it.copy(spawnBiomeIds = listOf("t:nowhere")) }, blocks = TestContent.pack.blocks + log)),
    )

    private val identity = WorldIdentity("w1", "Test world", createdAt = 1L, presetName = "Adventure", packIds = listOf("test"), heroName = "Digger")

    private fun session(seed: Long = 11L) = WorldSession(content, WorldConfig(seed = seed, simulationRadius = 1, rules = WorldRules(raids = false)))

    private fun WorldSession.stone() = content.registry.indexOf(TestContent.stone.id)

    /** Digs the block under the player's east neighbour and builds a pillar to the west: two edits a player would make. */
    private fun WorldSession.digAndBuild(): Pair<BlockPos, BlockPos> {
        val feet = player.blockPos
        val dug = BlockPos(feet.x + 1, feet.y, feet.z - 1)
        val built = BlockPos(feet.x - 2, feet.y, feet.z + 1)
        editableWorld.setBlock(dug, 0)
        editableWorld.setBlock(built, stone())
        return dug to built
    }

    private fun WorldSession.walk(seconds: Float, dx: Float = 1f) {
        setMoveInput(dx, 0f)
        repeat((seconds / 0.05f).toInt()) { tick(0.05f) }
        setMoveInput(0f, 0f)
        tick(0.05f)
    }

    @Test
    fun `a restored session stands in the world the save left, dug, built, walked and aged`() {
        val session = session()
        val (dug, built) = session.digAndBuild()
        session.walk(2f)
        session.player = session.player.withItem(TestContent.stone.id, 7).copy(health = session.player.health - 5, needs = mapOf("stratum:hunger" to 42f))
        val save = session.worldSave(identity, savedAt = 500L)

        val restored = WorldSession.restore(content, save)

        assertEquals(0, restored.world.blockIndexAt(dug), "the dug block stays dug")
        assertEquals(restored.stone(), restored.world.blockIndexAt(built), "the built block stays built")
        assertEquals(session.player.position, restored.player.position)
        assertEquals(session.player.health, restored.player.health)
        assertEquals(session.player.inventory, restored.player.inventory)
        assertEquals(session.player.needs, restored.player.needs)
        assertEquals(session.player.level, restored.player.level)
        assertEquals(session.clock.elapsedSeconds, restored.clock.elapsedSeconds)
        assertEquals(session.clock.day, restored.clock.day)
        assertEquals(save.playSeconds, restored.playSeconds.toLong())
        assertEquals(save, restored.worldSave(identity, savedAt = 500L), "saving the restored world gives the same save back")
    }

    @Test
    fun `the save stamps its identity and the world's seed and rules`() {
        val session = session(seed = 99L)
        val save = session.worldSave(identity, savedAt = 42L)

        assertEquals("w1", save.id)
        assertEquals(42L, save.lastPlayedAt)
        assertEquals(99L, save.seed)
        assertEquals(session.rules, save.rules)
        assertEquals(session.player.heroClassId, save.heroClassId)
        assertEquals(content.registry.all.map { it.id }, save.blockIds)
        assertTrue(save.chunks.isEmpty(), "an untouched world keeps no chunks: the seed makes them again")
    }

    @Test
    fun `an edit in a chunk that has streamed out is kept, and waits for the player to come back`() {
        val session = session()
        val (dug, _) = session.digAndBuild()
        // Walk the window far enough east that the edited chunk streams out.
        session.player = session.player.copy(position = WorldPoint(6f * Chunk.SIZE, session.player.position.y, session.player.position.z))
        session.tick(0.05f)
        assertTrue(!session.world.isLoaded(dug.chunkPos))

        val save = session.worldSave(identity)
        assertTrue(save.chunks.any { it.pos == dug.chunkPos })

        val restored = WorldSession.restore(content, save)
        assertTrue(!restored.world.isLoaded(dug.chunkPos))
        restored.player = restored.player.copy(position = session.player.position.copy(x = dug.x + 0.5f))
        restored.tick(0.05f)
        assertEquals(0, restored.world.blockIndexAt(dug))
    }

    @Test
    fun `a save taken before an edit does not contain it`() {
        val session = session()
        val (dug, built) = session.digAndBuild()
        val before = session.worldSave(identity)
        val further = BlockPos(built.x, built.y, built.z + 1)
        session.editableWorld.setBlock(further, session.stone())
        session.editableWorld.setBlock(dug, session.stone())

        val restored = WorldSession.restore(content, before)

        assertEquals(0, restored.world.blockIndexAt(dug))
        assertEquals(0, restored.world.blockIndexAt(further))
        assertNotEquals(before, session.worldSave(identity))
    }

    @Test
    fun `the same play saved twice gives the same save, and restores the same way`() {
        fun play(): WorldSave {
            val session = session()
            session.digAndBuild()
            session.walk(1.5f)
            return session.worldSave(identity, savedAt = 7L)
        }
        val save = play()
        assertEquals(save, play())

        fun resumed(): List<Any> {
            val restored = WorldSession.restore(content, save)
            restored.walk(1f, dx = -1f)
            return listOf(restored.player, restored.worldSave(identity, savedAt = 8L))
        }
        assertEquals(resumed(), resumed())
    }

    @Test
    fun `outposts, followers and their orders come back with the world`() {
        val session = session()
        assertIs<RealmResult.Founded>(session.foundOutpost("Hold"))
        val id = session.outposts.single().id
        repeat(6) {
            session.player = session.player.withItem(TestContent.stone.id, 40).withItem(log.id, 40).withItem(TestContent.ore.id, 10).withItem("stratum:raw_meat", 20)
            session.deposit()
        }
        assertIs<RealmResult.Built>(session.build(id, StandardStrategy.barracks.id))
        repeat(2) { assertIs<RealmResult.Recruited>(session.recruit(id, StandardStrategy.militia.id)) }
        session.muster(1)
        session.command(FollowerOrder.HOLD)
        assertEquals(1, session.followers.size)

        val save = session.worldSave(identity)
        val restored = WorldSession.restore(content, save)

        assertEquals(session.outposts, restored.outposts)
        assertEquals(1, restored.outposts.single().garrison.values.sum(), "the soldier left home stays home")
        assertEquals(FollowerOrder.HOLD, restored.followerOrder)
        assertEquals(1, restored.followers.size)
        assertTrue(restored.followers.all { it.factionId == Factions.PLAYER })
        assertTrue(restored.followers.all { it.position.horizontalDistanceTo(restored.player.position) < 3f }, "followers are raised beside the player")
    }

    @Test
    fun `a dungeon marker cleared before the save stays cleared after it`() {
        val floor = CombatCoreFixtures.FLOOR
        val marker = WorldMarker(WorldMarkerKind.ENEMY_SPAWN, 6, 0, floor + 1, "test:crypt", "dummy")
        val arena = object : com.stratum.core.domain.world.TerrainGenerator, com.stratum.core.domain.world.MarkedWorld {
            override fun generate(pos: ChunkPos, registry: com.stratum.core.domain.world.BlockRegistry) = CombatCoreFixtures.FlatTerrain.generate(pos, registry)
            override fun markersIn(pos: ChunkPos) = listOf(marker).filter { ChunkPos.containing(it.x, it.y) == pos }
        }
        val combat = ContentPackAssembler().assemble(listOf(CombatCoreFixtures.pack()))
        val session = WorldSession(combat, WorldConfig(seed = 5L, simulationRadius = 1), terrainGenerator = arena)
        session.enemies = emptyList()
        session.tick(0.01f)
        assertEquals(1, session.enemies.size, "the marker woke")

        val save = session.worldSave(identity)
        assertEquals(1, save.consumedMarkers.size)

        val restored = WorldSession.restore(combat, save, terrainGenerator = arena)
        restored.enemies = emptyList()
        restored.tick(0.01f)
        assertTrue(restored.enemies.isEmpty(), "a cleared marker does not refill after a reload")

        val fresh = WorldSession(combat, WorldConfig(seed = 5L, simulationRadius = 1), terrainGenerator = arena)
        fresh.enemies = emptyList()
        fresh.tick(0.01f)
        assertEquals(1, fresh.enemies.size, "a new world of the same seed has its marker waiting")
    }

    @Test
    fun `a block from a pack no longer loaded comes back as air rather than failing the load`() {
        val session = session()
        val feet = session.player.blockPos
        val logAt = BlockPos(feet.x + 2, feet.y + 2, feet.z)
        session.editableWorld.setBlock(logAt, content.registry.indexOf(log.id))
        val save = session.worldSave(identity)

        val without = ContentPackAssembler().assemble(listOf(TestContent.pack.copy(enemies = TestContent.enemies.map { it.copy(spawnBiomeIds = listOf("t:nowhere")) })))
        val restored = WorldSession.restore(without, save)

        assertEquals(0, restored.world.blockIndexAt(logAt))
    }
}
