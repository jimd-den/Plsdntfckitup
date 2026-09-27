package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyPackDefinition
import com.stratum.core.domain.actor.PackMember
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.faction.FactionDefinition
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.settlement.BuildingRole
import com.stratum.core.domain.settlement.BuildingTemplate
import com.stratum.core.domain.settlement.SettlementRecipe
import com.stratum.core.domain.world.WorldConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val guild = FactionDefinition("t:guild", "Guild", relations = mapOf("t:raiders" to Stance.HOSTILE), startingStanding = 150)
private val raiders = FactionDefinition("t:raiders", "Raiders", defaultStance = Stance.HOSTILE, startingStanding = -300)

private val guard = EnemyDefinition(
    "t:guard", "Guard", baseStats = CombatStats(maxHealth = 60, attackPower = 9, attackSpeed = 1f, attackRange = 1),
    damageTypeId = TestContent.physical.id, factionId = guild.id, spawnWeight = 0,
)
private val raider = EnemyDefinition(
    "t:raider", "Raider", baseStats = CombatStats(maxHealth = 20, attackPower = 5, attackSpeed = 1f, attackRange = 1),
    damageTypeId = TestContent.physical.id, factionId = raiders.id, spawnWeight = 0,
)
private val hut = BuildingTemplate("t:hut", "Hut", BuildingRole.HOUSE, 5, 5, 3, TestContent.stone.id, roofBlockId = TestContent.stone.id)

private fun town(id: String, faction: String?, garrison: List<PackMember> = emptyList()) = SettlementRecipe(
    id = id, name = id, layoutId = SettlementRecipe.CAMP, factionId = faction, minRadius = 20, maxRadius = 22, chance = 1f,
    roadBlockId = TestContent.stone.id, foundationBlockId = TestContent.soil.id, groundBlockId = TestContent.soil.id,
    wallBlockId = TestContent.wall.id, buildings = listOf(hut), garrison = garrison,
)

private fun session(vararg towns: SettlementRecipe, packs: List<EnemyPackDefinition> = emptyList(), wild: Boolean = true): WorldSession {
    // Without the wilds, nothing but what a test plants is in the world.
    val wildlife = if (wild) TestContent.enemies else TestContent.enemies.map { it.copy(spawnBiomeIds = listOf("t:nowhere")) }
    val pack = TestContent.pack.copy(
        factions = listOf(guild, raiders),
        enemies = wildlife + guard + raider,
        settlements = towns.toList(),
        enemyPacks = packs,
    )
    return WorldSession(ContentPackAssembler().assemble(listOf(pack)), WorldConfig(seed = 4L, simulationRadius = 2))
}

class FactionSessionTest {

    private fun WorldSession.plant(definition: EnemyDefinition, health: Int = definition.baseStats.maxHealth) =
        spawn(definition, player.position.translated(1f, 0f, 0f)).also { planted ->
            enemies = listOf(planted.copy(health = health))
        }

    @Test
    fun `allies neither strike the player nor can be struck`() {
        val session = session(wild = false)
        session.plant(guard)
        val before = session.player.health

        repeat(20) { session.tick(0.1f) }
        assertEquals(before, session.player.health)
        assertEquals(AttackReport.Missed, session.attack().let { if (it is AttackReport.Landed) it else AttackReport.Missed })
        assertEquals(guard.baseStats.maxHealth, session.enemies.single { it.definitionId == guard.id }.health)
    }

    @Test
    fun `hostile factions attack on sight`() {
        val session = session(wild = false)
        session.plant(raider)
        val before = session.player.health

        repeat(30) { session.tick(0.1f) }
        assertTrue(session.player.health < before)
    }

    @Test
    fun `killing a faction's people costs standing with them and earns it with their enemies`() {
        val session = session()
        session.plant(raider, health = 1)
        val book = session.content.factionBook

        assertIs<AttackReport.Landed>(session.attack())
        assertTrue(session.player.reputation.of(book.faction(raiders.id)!!) < raiders.startingStanding)
        assertTrue(session.player.reputation.of(book.faction(guild.id)!!) > guild.startingStanding)
    }
}

class StrongholdTest {

    private val camp = town("t:raider-camp", raiders.id, listOf(PackMember(raider.id, 3)))

    @Test
    fun `a hostile town's garrison musters at its posts when the player arrives`() {
        val session = session(camp)
        session.tick(0.05f)

        val here = assertNotNull(session.currentSettlement)
        assertTrue(session.isHostileTown(here))
        val garrison = session.enemies.filter { it.squadId == here.id }
        assertEquals(3, garrison.size)
        assertTrue(garrison.all { it.home != null })
        assertEquals(1, garrison.count { it.isLeader })
    }

    @Test
    fun `felling the whole garrison liberates the town`() {
        val session = session(camp)
        session.tick(0.05f)
        val here = session.currentSettlement!!
        val events = mutableListOf<CombatEvent>()

        repeat(3) {
            val next = session.enemies.first { it.squadId == here.id && it.isAlive }
            session.enemies = session.enemies.map { if (it.instanceId == next.instanceId) it.copy(health = 1, position = session.player.position.translated(1f, 0f, 0f)) else it }
            session.player = session.player.copy(attackCooldown = 0f)
            session.attack()
            events += session.tick(0.01f)
        }

        assertTrue(events.any { it is CombatEvent.TownLiberated && it.town.id == here.id })
        assertTrue(!session.isHostileTown(here))
        assertTrue(session.enemies.none { it.squadId == here.id }, "a freed town does not muster again")
    }

    @Test
    fun `friendly towns keep wild monsters from spawning inside`() {
        val village = town("t:village", guild.id)
        val session = session(village)
        session.tick(0.01f)
        val here = session.currentSettlement!!

        val wild = session.enemies.filter { it.home == null }
        assertTrue(wild.isNotEmpty(), "the wilds still spawn")
        assertTrue(wild.none { here.contains(it.blockPos.x, it.blockPos.y, margin = -2) }, "nothing wild inside the walls")
    }
}

class PackSpawnTest {

    @Test
    fun `a pack arrives together, around its leader, sharing a squad`() {
        val content = ContentPackAssembler().assemble(listOf(TestContent.pack))
        val director = EnemyDirector(WorldSession(content, WorldConfig(seed = 2L, simulationRadius = 1)).world, content.enemies)
        val pack = EnemyPackDefinition("t:pack", "Rat pack", leaderId = TestContent.emberling.id, members = listOf(PackMember(TestContent.rat.id, 4)))

        val spawned = director.spawnPack(pack, com.stratum.core.domain.world.WorldPoint(0.5f, 0.5f, 12f), 1, Random(1))

        assertEquals(5, spawned.size)
        assertEquals(1, spawned.map { it.squadId }.distinct().size)
        assertEquals(TestContent.emberling.id, spawned.single { it.isLeader }.definitionId)
    }

    @Test
    fun `the director spawns packs from the loaded packs`() {
        val pack = EnemyPackDefinition("t:rats", "Rats", members = listOf(PackMember(TestContent.rat.id, 3)))
        val session = session(packs = listOf(pack))
        repeat(10) { session.tick(0.1f) }

        assertTrue(session.enemies.groupBy { it.squadId }.any { (squad, members) -> squad != null && members.size >= 2 })
    }
}
