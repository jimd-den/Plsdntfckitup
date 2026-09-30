package com.stratum.engine.world

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.strategy.StandardStrategy
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val log = com.stratum.core.domain.world.BlockType("t:log", "Log", com.stratum.core.domain.world.BlockMaterial.WOOD)

private fun session(raids: Boolean = true, wild: Boolean = false): WorldSession {
    val wildlife = if (wild) TestContent.enemies else TestContent.enemies.map { it.copy(spawnBiomeIds = listOf("t:nowhere")) }
    val content = ContentPackAssembler().assemble(listOf(TestContent.pack.copy(enemies = wildlife, blocks = TestContent.pack.blocks + log)))
    return WorldSession(content, WorldConfig(seed = 6L, simulationRadius = 1, rules = WorldRules(raids = raids)))
}

/** An outpost with a barracks and a well-stocked store, founded where the player stands. */
private fun WorldSession.foundRichOutpost(): Outpost {
    assertIs<RealmResult.Founded>(foundOutpost("Hold"))
    val id = outposts.single().id
    // Deposit an armful of stone and wood-like blocks until there is plenty to spend.
    repeat(6) {
        player = player.withItem(TestContent.stone.id, 40).withItem(log.id, 40).withItem(TestContent.ore.id, 10).withItem(StandardStrategy.rawMeat(), 20)
        deposit()
    }
    return outposts.single { it.id == id }
}

private fun StandardStrategy.rawMeat() = "stratum:raw_meat"

class RealmSessionTest {

    @Test
    fun `founding an outpost costs blocks from the bag, and not twice in the same place`() {
        val session = session()
        val before = session.player.inventory.values.sum()

        val founded = assertIs<RealmResult.Founded>(session.foundOutpost("Hold"))
        assertEquals(before - StandardStrategy.FOUNDING_BLOCKS, session.player.inventory.values.sum())
        assertEquals(1, founded.outpost.count(StandardStrategy.hearth.id))
        assertEquals(founded.outpost, session.currentOutpost)
        assertEquals(RealmResult.TooClose, session.foundOutpost("Again"))
    }

    @Test
    fun `depositing turns what the player carries into the outpost's stock`() {
        val session = session()
        session.foundOutpost("Hold")
        session.player = session.player.withItem(TestContent.stone.id, 10)

        val deposited = assertIs<RealmResult.Deposited>(session.deposit())
        assertTrue(deposited.gained.getValue(StandardStrategy.stone.id) >= 10f)
        assertEquals(0, session.player.countOf(TestContent.stone.id))
    }

    @Test
    fun `an outpost builds, produces, and recruits soldiers who follow the player`() {
        val session = session()
        val outpost = session.foundRichOutpost()

        assertIs<RealmResult.Built>(session.build(outpost.id, StandardStrategy.barracks.id))
        assertIs<RealmResult.Built>(session.build(outpost.id, StandardStrategy.farm.id))
        repeat(2) { assertIs<RealmResult.Recruited>(session.recruit(outpost.id, StandardStrategy.militia.id)) }
        assertIs<RealmResult.CannotAfford>(session.recruit(outpost.id, "nobody"))

        val mustered = assertIs<RealmResult.Mustered>(session.muster())
        assertEquals(2, mustered.count)
        assertEquals(2, session.followers.size)
        assertTrue(session.followers.all { it.factionId == Factions.PLAYER })
    }

    @Test
    fun `followers keep up with the player and fight what threatens them`() {
        val session = session()
        val outpost = session.foundRichOutpost()
        session.build(outpost.id, StandardStrategy.barracks.id)
        session.recruit(outpost.id, StandardStrategy.militia.id)
        session.muster()

        session.setMoveInput(1f, 0f)
        repeat(30) { session.tick(0.1f) }
        session.setMoveInput(0f, 0f)
        repeat(30) { session.tick(0.1f) }
        val follower = session.followers.single()
        assertTrue(follower.position.horizontalDistanceTo(session.player.position) < 5f, "kept up: ${follower.position} vs ${session.player.position} state ${follower.state}")

        val rat = session.spawn(TestContent.rat, follower.position.translated(1f, 0f, 0f))
        repeat(80) { session.tick(0.1f) }
        assertTrue(session.enemies.none { it.instanceId == rat.instanceId && it.isAlive }, "the follower fought it")
    }

    @Test
    fun `followers told to return go back into the garrison`() {
        val session = session()
        val outpost = session.foundRichOutpost()
        session.build(outpost.id, StandardStrategy.barracks.id)
        session.recruit(outpost.id, StandardStrategy.militia.id)
        session.muster()

        session.command(FollowerOrder.RETURN)
        repeat(60) { session.tick(0.1f) }
        assertEquals(0, session.followers.size)
        assertEquals(1, session.outposts.single().garrison[StandardStrategy.militia.id])
    }

    @Test
    fun `a raid on an outpost the player stands in is fought, and beaten off`() {
        val session = session()
        val outpost = session.foundRichOutpost()
        val events = mutableListOf<CombatEvent>()

        repeat((Outpost.FIRST_RAID_SECONDS / 1f).toInt() + 2) { events += session.tick(1f) }
        val arrived = events.filterIsInstance<CombatEvent.Realm>().map { it.event }.filterIsInstance<RealmEvent.RaidArrived>()
        assertEquals(1, arrived.size)
        assertTrue(session.enemies.any { it.squadId == "raid:${outpost.id}" })

        // The player cuts them down.
        session.enemies = session.enemies.map { if (it.squadId == "raid:${outpost.id}") it.copy(health = 0, state = com.stratum.core.domain.actor.EnemyState.DEAD) else it }
        events += session.tick(0.1f)
        assertTrue(events.any { it is CombatEvent.Realm && it.event is RealmEvent.RaidRepelled })
        assertEquals(1, session.outposts.single().raidsSurvived)
    }

    @Test
    fun `a raid on an outpost the player is away from is settled by its defences`() {
        val session = session()
        session.foundRichOutpost()
        session.player = session.player.copy(position = session.player.position.translated(200f, 0f, 0f))
        val events = mutableListOf<CombatEvent>()

        repeat((Outpost.FIRST_RAID_SECONDS / 2f).toInt() + 2) { events += session.tick(2f) }
        val resolved = events.filterIsInstance<CombatEvent.Realm>().map { it.event }.filterIsInstance<RealmEvent.RaidResolved>()
        assertNotNull(resolved.firstOrNull())
    }

    @Test
    fun `with raids off, nobody comes`() {
        val session = session(raids = false)
        session.foundRichOutpost()
        val events = mutableListOf<CombatEvent>()
        repeat(900) { events += session.tick(1f) }

        assertTrue(events.none { it is CombatEvent.Realm })
    }
}
