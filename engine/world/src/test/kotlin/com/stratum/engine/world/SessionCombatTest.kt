package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionCombatTest {

    private fun session(seed: Long = 11L) = WorldSession(
        content = TestContent.assembled,
        config = WorldConfig(seed = seed, simulationRadius = 1),
    )

    /** Drops a monster next to the player without waiting for the director. */
    private fun WorldSession.placeEnemy(health: Int = 40, offsetX: Float = 1f): EnemyInstance {
        val enemy = EnemyInstance(
            instanceId = "planted",
            definitionId = TestContent.rat.id,
            name = "Rat",
            rank = EnemyRank.MINION,
            position = player.position.translated(offsetX, 0f, 0f),
            health = health,
            stats = CombatStats(maxHealth = health, attackPower = 6, attackSpeed = 1f, attackRange = 1),
            damageTypeId = TestContent.physical.id,
            experience = 500,
        )
        // The session owns its population; reach in through the same path the
        // director would use.
        enemies = listOf(enemy)
        return enemy
    }

    @Test
    fun `the player starts armed so the first fight is winnable`() {
        val session = session()
        assertNotNull(session.player.equippedWeapon, "the class spawned with no weapon")
        assertEquals(TestContent.club.id, session.player.equippedWeapon!!.baseId)
        assertTrue(session.player.combatStats.attackPower > session.player.baseStats.attackPower)
    }

    @Test
    fun `health starts at the maximum the gear allows`() {
        val session = session()
        assertEquals(session.player.maxHealthWithGear, session.player.health)
    }

    @Test
    fun `a swing damages an adjacent enemy`() {
        val session = session()
        session.placeEnemy(health = 500)

        val report = assertIs<AttackReport.Landed>(session.attack())
        assertTrue(report.totalDamage > 0)
        assertTrue(session.enemies.single().health < 500)
    }

    @Test
    fun `attacks respect the weapon's recovery time`() {
        val session = session()
        session.placeEnemy(health = 500)

        assertIs<AttackReport.Landed>(session.attack())
        assertIs<AttackReport.NotReady>(session.attack())

        session.tick(10f)
        assertIs<AttackReport.Landed>(session.attack())
    }

    @Test
    fun `a charging monster stops at its reach instead of running through the player`() {
        // A long frame used to carry a monster clean past the player and out the
        // far side, which reads as the enemy losing interest mid-charge.
        val session = session()
        session.placeEnemy(health = 500, offsetX = 4f)

        session.tick(10f)

        val hunter = session.enemies.single { it.instanceId == "planted" }
        val distance = hunter.position.horizontalDistanceTo(session.player.position)
        assertTrue(
            distance in 0.5f..1.5f,
            "the monster ended up $distance away, having overshot its target",
        )
    }

    @Test
    fun `a swing at empty air reports a miss and still costs recovery`() {
        val session = session()
        session.enemies = emptyList()
        assertIs<AttackReport.Missed>(session.attack())
        assertIs<AttackReport.NotReady>(session.attack())
    }

    @Test
    fun `killing an enemy grants experience and can level the player`() {
        val session = session()
        val startLevel = session.player.level
        session.placeEnemy(health = 1)

        val report = assertIs<AttackReport.Landed>(session.attack())
        assertEquals(1, report.slain.size)
        assertTrue(session.player.level > startLevel, "500 experience did not level a fresh character")
        assertTrue(session.enemies.none { it.instanceId == "planted" }, "the corpse stayed in the world")
    }

    @Test
    fun `levelling restores health and resource`() {
        val session = session()
        session.placeEnemy(health = 1)
        session.player = session.player.damaged(session.player.health / 2)
        val wounded = session.player.health

        session.attack()
        assertTrue(session.player.health > wounded, "a level up did not restore health")
        assertEquals(session.player.maxHealthWithGear, session.player.health)
    }

    @Test
    fun `a skill spends resource and starts its cooldown`() {
        val session = session()
        session.placeEnemy(health = 1000)
        val before = session.player.resource

        assertIs<AttackReport.Landed>(session.castSkill(TestContent.strike.id))
        assertEquals(before - TestContent.strike.resourceCost, session.player.resource)
        assertIs<AttackReport.OnCooldown>(session.castSkill(TestContent.strike.id))
    }

    @Test
    fun `a skill with no resource left is refused`() {
        val session = session()
        session.placeEnemy(health = 1000)
        session.player = session.player.copy(resource = 0)
        assertIs<AttackReport.NotEnoughResource>(session.castSkill(TestContent.strike.id))
    }

    @Test
    fun `an unknown skill is refused rather than crashing`() {
        val session = session()
        assertIs<AttackReport.UnknownSkill>(session.castSkill("test:nonexistent"))
    }

    @Test
    fun `a skill that reaches nothing still pays its cost`() {
        val session = session()
        session.enemies = emptyList()
        val before = session.player.resource

        assertIs<AttackReport.Missed>(session.castSkill(TestContent.strike.id))
        assertEquals(
            before - TestContent.strike.resourceCost,
            session.player.resource,
            "a whiffed skill was free, so it can be used to scout",
        )
    }

    @Test
    fun `cooldowns tick down over time`() {
        val session = session()
        session.placeEnemy(health = 1000)
        session.castSkill(TestContent.strike.id)
        assertFalse(session.player.cooldowns.isReady(TestContent.strike.id))

        session.tick(TestContent.strike.cooldownSeconds + 0.1f)
        assertTrue(session.player.cooldowns.isReady(TestContent.strike.id))
    }

    @Test
    fun `enemies hurt the player on tick`() {
        val session = session()
        session.placeEnemy(health = 1000)
        val before = session.player.health

        val events = session.tick(0.1f)
        assertTrue(session.player.health < before, "the monster next to the player did nothing")
        assertTrue(events.any { it is CombatEvent.PlayerHurt })
    }

    @Test
    fun `a dead player stops the simulation rather than being hit forever`() {
        val session = session()
        session.placeEnemy(health = 1000)
        session.player = session.player.damaged(session.player.health)
        assertFalse(session.player.isAlive)

        assertTrue(session.tick(1f).isEmpty(), "the world kept running after death")
    }

    @Test
    fun `the director populates the world as the player explores`() {
        val session = session()
        repeat(4) { session.tick(0.2f) }
        assertTrue(session.enemies.isNotEmpty(), "nothing ever spawned")
    }

    @Test
    fun `spawns never land on top of the player`() {
        val session = session()
        repeat(20) { session.tick(0.2f) }
        session.enemies.forEach { enemy ->
            assertTrue(
                enemy.position.horizontalDistanceTo(session.player.position) > 1f,
                "${enemy.name} spawned on the player",
            )
        }
    }

    @Test
    fun `the population is capped so the world does not fill up`() {
        val session = session()
        repeat(200) { session.tick(0.2f) }
        assertTrue(session.enemies.size <= DirectorConfig().maxAlive, "spawned ${session.enemies.size}")
    }

    @Test
    fun `loot is picked up by walking over it and an upgrade equips itself`() {
        val session = session()
        val weak = session.player.equippedWeapon!!.copy(minDamage = 1, maxDamage = 1)
        session.player = session.player.copy(equippedWeapon = weak)

        val strong = LootRoller(TestContent.weapons, TestContent.affixes)
            .craft(TestContent.greatsword, itemLevel = 30, rarity = com.stratum.core.domain.item.ItemRarity.RARE, random = kotlin.random.Random(3))
        session.groundLoot = listOf(GroundLoot(strong, session.player.position))

        val events = session.tick(0.1f)
        val taken = events.filterIsInstance<CombatEvent.LootTaken>().single()
        assertTrue(taken.equipped, "a clear upgrade was not equipped")
        assertEquals(strong.instanceId, session.player.equippedWeapon!!.instanceId)
    }

    @Test
    fun `a worse item goes to the bag rather than replacing what is held`() {
        val session = session()
        val held = session.player.equippedWeapon!!
        val junk = held.copy(instanceId = "junk", minDamage = 1, maxDamage = 1, affixes = emptyList())
        session.groundLoot = listOf(GroundLoot(junk, session.player.position))

        session.tick(0.1f)
        assertEquals(held.instanceId, session.player.equippedWeapon!!.instanceId, "junk replaced a better weapon")
        assertTrue(session.player.bag.any { it.instanceId == "junk" })
    }

    @Test
    fun `loot out of reach stays on the ground`() {
        val session = session()
        val item = session.player.equippedWeapon!!.copy(instanceId = "distant")
        session.groundLoot = listOf(GroundLoot(item, session.player.position.translated(20f, 0f, 0f)))

        session.tick(0.1f)
        assertEquals(1, session.groundLoot.size, "loot teleported into the bag")
    }

    @Test
    fun `equipping moves the previous weapon to the bag rather than destroying it`() {
        val session = session()
        val original = session.player.equippedWeapon!!
        val replacement = original.copy(instanceId = "replacement")

        session.player = session.player.equipping(replacement)
        assertEquals("replacement", session.player.equippedWeapon!!.instanceId)
        assertTrue(session.player.bag.any { it.instanceId == original.instanceId })
    }

    @Test
    fun `the snapshot carries the fight for the renderer`() {
        val session = session()
        session.placeEnemy(health = 100)
        val snapshot = session.snapshot()

        assertEquals(1, snapshot.enemies.size)
        assertEquals(session.player, snapshot.player)
        assertEquals(TestContent.skills.size, snapshot.skills.size)
    }

    @Test
    fun `a run replays identically from the same seed`() {
        fun run(): List<Int> {
            val session = session(seed = 777L)
            val healths = mutableListOf<Int>()
            repeat(30) {
                session.tick(0.15f)
                session.attack()
                healths += session.player.health
            }
            return healths
        }
        assertEquals(run(), run(), "the same seed produced a different fight")
    }

    @Test
    fun `item level rises as the player descends`() {
        assertTrue(
            Progression.itemLevelFor(5, depthBelowSurface = 24) > Progression.itemLevelFor(5, 0),
        )
    }
}
