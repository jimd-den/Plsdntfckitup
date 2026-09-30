package com.stratum.engine.world

import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.world.WorldConfig
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SatchelTest {

    private fun session(seed: Long = 21L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    )

    private val roller = LootRoller(TestContent.weapons, TestContent.affixes, TestContent.inserts)

    /**
     * Puts something in the bag the way a player would: drop it, walk over it.
     *
     * A plain level 1 pick averages 8 damage against the starting club's 10, so
     * it is never an upgrade and never auto-equips. The assertion is there so
     * that a change to the starting gear fails this fixture loudly instead of
     * quietly turning every test below into a no-op.
     */
    private fun WorldSession.bagJunk(): ItemInstance {
        val junk = roller.craft(TestContent.pick, itemLevel = 1, rarity = ItemRarity.COMMON, random = Random(9))
        dropLoot(junk, player.position)
        tick(0.05f)
        assertTrue(
            player.bag.any { it.instanceId == junk.instanceId },
            "the fixture item auto-equipped; it is no longer junk relative to the starting weapon",
        )
        return junk
    }

    /** Drops a relic good enough that the player picks it up and wears it. */
    private fun WorldSession.wearRelic(): ItemInstance {
        val relic = roller.craft(TestContent.greatsword, itemLevel = 30, rarity = ItemRarity.RELIC, random = Random(4))
        dropLoot(relic, player.position)
        tick(0.05f)
        assertEquals(relic.instanceId, player.equippedWeapon?.instanceId, "the relic was not worn")
        return relic
    }

    @Test
    fun `equipping from the bag puts what was held back in the bag`() {
        val session = session()
        val held = session.player.equippedWeapon
        assertNotNull(held)
        val junk = session.bagJunk()

        val result = session.equip(junk.instanceId)

        assertIs<EquipResult.Equipped>(result)
        assertEquals(junk.instanceId, session.player.equippedWeapon!!.instanceId)
        assertTrue(
            session.player.bag.any { it.instanceId == held.instanceId },
            "the previous weapon was destroyed rather than bagged",
        )
        assertTrue(
            session.player.bag.none { it.instanceId == junk.instanceId },
            "the equipped item is still listed in the bag as well",
        )
    }

    @Test
    fun `equipping something not in the bag is refused`() {
        val session = session()
        assertIs<EquipResult.NotInBag>(session.equip("nothing"))
        // What is equipped is not in the bag either, so re-equipping it is a
        // refusal rather than a way to duplicate it.
        assertIs<EquipResult.NotInBag>(session.equip(session.player.equippedWeapon!!.instanceId))
    }

    @Test
    fun `discarding drops the item into the world rather than deleting it`() {
        val session = session()
        val junk = session.bagJunk()

        val result = session.discard(junk.instanceId)

        assertIs<EquipResult.Discarded>(result)
        assertTrue(session.player.bag.none { it.instanceId == junk.instanceId })
        assertTrue(
            session.groundLoot.any { it.item.instanceId == junk.instanceId },
            "the discarded item vanished instead of landing",
        )
    }

    @Test
    fun `a discarded item lands out of reach so it is not picked straight back up`() {
        val session = session()
        val junk = session.bagJunk()
        session.discard(junk.instanceId)

        session.tick(0.05f)

        assertTrue(
            session.groundLoot.any { it.item.instanceId == junk.instanceId },
            "the discard was undone by the very next tick",
        )
    }

    @Test
    fun `discarding something not in the bag is refused`() {
        assertIs<EquipResult.NotInBag>(session().discard("nothing"))
    }

    @Test
    fun `swapping to weaker gear clamps health to the new ceiling`() {
        val session = session()
        session.wearRelic()
        val junk = session.bagJunk()
        val ceilingWithRelic = session.player.maxHealthWith(session::insertOrNull)

        session.equip(junk.instanceId)

        val ceiling = session.player.maxHealthWith(session::insertOrNull)
        assertTrue(ceiling < ceilingWithRelic, "the fixture relic carried no health, so nothing was clamped")
        assertTrue(
            session.player.health <= ceiling,
            "health ${session.player.health} exceeded the new ceiling $ceiling",
        )
    }
}
