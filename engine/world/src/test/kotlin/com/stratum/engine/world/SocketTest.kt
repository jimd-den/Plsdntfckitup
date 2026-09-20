package com.stratum.engine.world

import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketResolver
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.world.WorldConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SocketTest {

    private fun session(seed: Long = 5L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    )

    // ---- the value type --------------------------------------------------

    @Test
    fun `sockets fill the first free slot and empty back out`() {
        val two = SocketSet.of(2)
        val one = two.slotting("a")!!
        assertEquals(listOf("a", null), one.filled)

        val both = one.slotting("b")!!
        assertEquals(listOf("a", "b"), both.filled)
        assertNull(both.slotting("c"), "a full set accepted a third insert")

        val (emptied, removed) = both.unslotting(0)!!
        assertEquals("a", removed)
        assertEquals(listOf(null, "b"), emptied.filled)
    }

    @Test
    fun `unslotting an empty socket yields nothing`() {
        assertNull(SocketSet.of(2).unslotting(0))
        assertNull(SocketSet.of(2).unslotting(9), "an out-of-range socket was treated as real")
    }

    @Test
    fun `rarity decides socket count so a tier is never outclassed by a lesser one`() {
        val counts = ItemRarity.ordered.map { SocketSet.rolledFor(it) }
        assertEquals(counts.sorted(), counts, "a lower rarity rolled more sockets than a higher one")
        assertEquals(0, SocketSet.rolledFor(ItemRarity.COMMON))
    }

    @Test
    fun `the last converting insert decides the damage type`() {
        val base = TestContent.physical.id
        assertEquals(base, SocketResolver.damageTypeFor(base, emptyList()))
        assertEquals(
            TestContent.fire.id,
            SocketResolver.damageTypeFor(base, listOf(TestContent.sharpBead, TestContent.emberShard)),
        )
        // A non-converting insert slotted afterwards does not take the element
        // back; only another converter can.
        assertEquals(
            TestContent.fire.id,
            SocketResolver.damageTypeFor(base, listOf(TestContent.emberShard, TestContent.sharpBead)),
        )
    }

    // ---- the roller ------------------------------------------------------

    @Test
    fun `dropped items carry the sockets their rarity allows, empty`() {
        val roller = LootRoller(TestContent.weapons, TestContent.affixes, TestContent.inserts)
        ItemRarity.ordered.forEach { rarity ->
            val item = roller.craft(TestContent.club, itemLevel = 20, rarity = rarity, random = Random(3))
            assertEquals(SocketSet.rolledFor(rarity), item.socketCount, "${rarity.name} socket count")
            assertTrue(item.sockets.isEmpty, "${rarity.name} dropped with something already in it")
        }
    }

    @Test
    fun `an insert above the item level never rolls`() {
        val roller = LootRoller(TestContent.weapons, TestContent.affixes, TestContent.inserts)
        val shallow = (1..60).mapNotNull { roller.rollInsert(itemLevel = 1, random = Random(it.toLong())) }
        assertTrue(shallow.isNotEmpty())
        assertTrue(
            shallow.none { it.id == TestContent.deepBead.id },
            "a level 20 insert rolled at item level 1",
        )

        val deep = (1..120).mapNotNull { roller.rollInsert(itemLevel = 30, random = Random(it.toLong())) }
        assertTrue(deep.any { it.id == TestContent.deepBead.id }, "the gated insert never became available")
    }

    @Test
    fun `a pack with no inserts rolls none rather than failing`() {
        val roller = LootRoller(TestContent.weapons, TestContent.affixes)
        assertNull(roller.rollInsert(itemLevel = 50, random = Random(1)))
    }

    // ---- the forge -------------------------------------------------------

    /** Arms the player with a socketed weapon and the insert to fill it. */
    private fun WorldSession.prepare(sockets: Int = 2, insertId: String = TestContent.sharpBead.id) {
        val weapon = player.equippedWeapon!!.copy(sockets = SocketSet.of(sockets))
        player = player.copy(equippedWeapon = weapon).withInsert(insertId)
    }

    @Test
    fun `slotting spends the insert and strengthens the swing`() {
        val session = session()
        session.prepare()
        val before = session.playerStats.attackPower

        val result = session.slotInsert(session.player.equippedWeapon!!.instanceId, TestContent.sharpBead.id)

        assertIs<SocketResult.Slotted>(result)
        assertEquals(0, session.player.insertCount(TestContent.sharpBead.id), "the insert was not spent")
        assertEquals(
            listOf(TestContent.sharpBead.id),
            session.player.equippedWeapon!!.sockets.insertIds,
        )
        assertTrue(
            session.playerStats.attackPower > before,
            "slotting a +7 attack bead changed nothing",
        )
    }

    @Test
    fun `unslotting returns the insert intact and gives the stat back`() {
        val session = session()
        session.prepare()
        val id = session.player.equippedWeapon!!.instanceId
        val armed = session.playerStats.attackPower
        session.slotInsert(id, TestContent.sharpBead.id)

        val result = session.unslotInsert(id, 0)

        assertIs<SocketResult.Unslotted>(result)
        assertEquals(TestContent.sharpBead.id, result.insertId)
        assertEquals(1, session.player.insertCount(TestContent.sharpBead.id), "the insert did not come back")
        assertTrue(session.player.equippedWeapon!!.sockets.isEmpty)
        assertEquals(armed, session.playerStats.attackPower, "the stat survived the unslot")
    }

    @Test
    fun `slotting a converter changes what the weapon deals, and unslotting changes it back`() {
        val session = session()
        session.prepare(insertId = TestContent.emberShard.id)
        val id = session.player.equippedWeapon!!.instanceId
        val base = session.player.equippedWeapon!!.damageTypeId

        session.slotInsert(id, TestContent.emberShard.id)
        val converted = session.player.equippedWeapon!!.damageTypeWithSockets(session::insertOrNull)
        assertEquals(TestContent.fire.id, converted)
        assertNotEquals(base, converted)

        session.unslotInsert(id, 0)
        assertEquals(base, session.player.equippedWeapon!!.damageTypeWithSockets(session::insertOrNull))
    }

    @Test
    fun `the forge refuses what it cannot do`() {
        val session = session()
        session.prepare(sockets = 1)
        val id = session.player.equippedWeapon!!.instanceId

        assertIs<SocketResult.NoSuchItem>(session.slotInsert("nothing", TestContent.sharpBead.id))
        assertIs<SocketResult.NoSuchInsert>(session.slotInsert(id, "test:not_a_rune"))
        assertIs<SocketResult.NoneHeld>(session.slotInsert(id, TestContent.deepBead.id))
        assertIs<SocketResult.EmptySocket>(session.unslotInsert(id, 0))

        session.slotInsert(id, TestContent.sharpBead.id)
        session.player = session.player.withInsert(TestContent.sharpBead.id)
        assertIs<SocketResult.NoFreeSocket>(session.slotInsert(id, TestContent.sharpBead.id))
        // A refused slot must not eat the insert on the way out.
        assertEquals(1, session.player.insertCount(TestContent.sharpBead.id))
    }

    @Test
    fun `a bagged weapon can be socketed without equipping it`() {
        val session = session()
        val spare = session.player.equippedWeapon!!
            .copy(instanceId = "spare", sockets = SocketSet.of(1))
        session.player = session.player.collecting(spare).withInsert(TestContent.sharpBead.id)

        assertIs<SocketResult.Slotted>(session.slotInsert("spare", TestContent.sharpBead.id))
        assertEquals(
            listOf(TestContent.sharpBead.id),
            session.player.bag.first { it.instanceId == "spare" }.sockets.insertIds,
        )
        assertTrue(session.player.equippedWeapon!!.sockets.isEmpty, "the equipped weapon was socketed instead")
    }

    @Test
    fun `inserts on the ground are picked up into the pouch`() {
        val session = session()
        session.dropInsert(TestContent.sharpBead.id, session.player.position)

        val events = session.tick(0.1f)

        assertTrue(session.groundInserts.isEmpty(), "the insert stayed on the ground")
        assertEquals(1, session.player.insertCount(TestContent.sharpBead.id))
        assertTrue(events.any { it is CombatEvent.InsertTaken })
        assertEquals(
            listOf(TestContent.sharpBead.id),
            session.heldInserts.map { it.definition.id },
        )
    }

    @Test
    fun `an insert out of reach is left where it lies`() {
        val session = session()
        session.dropInsert(TestContent.sharpBead.id, session.player.position.translated(20f, 0f, 0f))

        session.tick(0.1f)

        assertEquals(1, session.groundInserts.size)
        assertEquals(0, session.player.insertCount(TestContent.sharpBead.id))
    }
}
