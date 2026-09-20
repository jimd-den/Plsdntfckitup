package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.world.Direction
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CombatResolverTest {

    private val resolver = CombatResolver()
    private val origin = WorldPoint(0f, 0f, 5f)

    private val attacker = CombatStats(
        attackPower = 50,
        critChance = 0f,
        attackSpeed = 1f,
        attackRange = 1,
    )

    private fun enemy(id: String, x: Float, y: Float, health: Int = 100) = EnemyInstance(
        instanceId = id,
        definitionId = TestContent.rat.id,
        name = "Rat",
        rank = EnemyRank.MINION,
        position = WorldPoint(x, y, 5f),
        health = health,
        stats = CombatStats(maxHealth = health, attackPower = 5, attackSpeed = 1f, attackRange = 1),
        damageTypeId = TestContent.physical.id,
    )

    // ---- basic attacks ---------------------------------------------------

    @Test
    fun `a swing with nothing in reach reports no target`() {
        val far = listOf(enemy("a", 20f, 20f))
        assertIs<AttackOutcome.NoTarget>(
            resolver.playerAttack(attacker, origin, Direction.EAST, far, TestContent.physical.id, Random(1)),
        )
    }

    @Test
    fun `a swing hits the nearest enemy only`() {
        val enemies = listOf(enemy("near", 1f, 0f), enemy("also_near", 0f, 1f))
        val outcome = resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1))

        val hits = assertIs<AttackOutcome.Hits>(outcome)
        assertEquals(1, hits.hits.size, "a basic swing hit more than one target")
    }

    @Test
    fun `an adjacent enemy counts as in reach`() {
        // Reach is measured between continuous positions, so without slack an
        // enemy standing in the next block would never be hittable.
        val enemies = listOf(enemy("adjacent", 1f, 0f))
        assertIs<AttackOutcome.Hits>(
            resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1)),
        )
    }

    @Test
    fun `a dead enemy is not a valid target`() {
        val enemies = listOf(enemy("corpse", 1f, 0f, health = 0))
        assertIs<AttackOutcome.NoTarget>(
            resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1)),
        )
    }

    @Test
    fun `damage is applied to the returned enemy`() {
        val enemies = listOf(enemy("target", 1f, 0f, health = 100))
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1)),
        )
        assertEquals(50, hits.totalDamage)
        assertEquals(50, hits.hits.single().enemy.health)
    }

    @Test
    fun `lethal damage marks the enemy dead`() {
        val enemies = listOf(enemy("weak", 1f, 0f, health = 10))
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1)),
        )
        assertEquals(1, hits.killed.size)
        assertTrue(!hits.killed.single().isAlive)
    }

    // ---- skill shapes ----------------------------------------------------

    @Test
    fun `a nova hits everything within its radius and nothing outside it`() {
        val enemies = listOf(
            enemy("in1", 1f, 0f),
            enemy("in2", 0f, 3f),
            enemy("in3", -2f, -2f),
            enemy("out", 10f, 10f),
        )
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, enemies, TestContent.nova, Random(1)),
        )
        assertEquals(3, hits.hits.size)
        assertTrue(hits.hits.none { it.enemyId == "out" })
    }

    @Test
    fun `a strike hits only the closest target even with a crowd in range`() {
        val enemies = listOf(enemy("near", 1f, 0f), enemy("mid", 2f, 0f), enemy("far", 3f, 0f))
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, enemies, TestContent.strike, Random(1)),
        )
        assertEquals(1, hits.hits.size)
        assertEquals("near", hits.hits.single().enemyId)
    }

    @Test
    fun `a lance hits along the facing direction only`() {
        val enemies = listOf(
            enemy("ahead", 3f, 0f),
            enemy("further_ahead", 5f, 0f),
            enemy("behind", -3f, 0f),
            enemy("aside", 0f, 4f),
        )
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, enemies, TestContent.lance, Random(1)),
        )
        val ids = hits.hits.map { it.enemyId }.toSet()
        assertEquals(setOf("ahead", "further_ahead"), ids)
    }

    @Test
    fun `a lance fired the other way hits the other target`() {
        val enemies = listOf(enemy("east", 3f, 0f), enemy("west", -3f, 0f))
        val hits = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.WEST, enemies, TestContent.lance, Random(1)),
        )
        assertEquals("west", hits.hits.single().enemyId)
    }

    @Test
    fun `a lane is forgiving enough to be usable with a four way pad`() {
        // Slightly off-axis must still land, or the skill is an input test.
        val enemies = listOf(enemy("slightly_off", 3f, 0.8f))
        assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, enemies, TestContent.lance, Random(1)),
        )
    }

    @Test
    fun `a skill multiplier increases damage over a basic swing`() {
        val enemies = listOf(enemy("target", 1f, 0f, health = 1000))
        val basic = assertIs<AttackOutcome.Hits>(
            resolver.playerAttack(attacker, origin, Direction.EAST, enemies, TestContent.physical.id, Random(1)),
        )
        val skilled = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, enemies, TestContent.strike, Random(1)),
        )
        assertTrue(skilled.totalDamage > basic.totalDamage)
        assertEquals((basic.totalDamage * TestContent.strike.powerMultiplier).toInt(), skilled.totalDamage)
    }

    @Test
    fun `resistance reduces a skill of the type it resists`() {
        val resistant = enemy("warded", 1f, 0f, health = 1000).let {
            it.copy(stats = it.stats.copy(resistances = mapOf(TestContent.fire.id to 0.5f)))
        }
        val plain = enemy("plain", 1f, 0f, health = 1000)

        val warded = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, listOf(resistant), TestContent.nova, Random(1)),
        )
        val unwarded = assertIs<AttackOutcome.Hits>(
            resolver.castSkill(attacker, origin, Direction.EAST, listOf(plain), TestContent.nova, Random(1)),
        )
        assertTrue(warded.totalDamage < unwarded.totalDamage)
    }

    // ---- enemies striking back -------------------------------------------

    @Test
    fun `enemies in range hit the player and go on cooldown`() {
        val enemies = listOf(enemy("a", 1f, 0f), enemy("b", 0f, 1f))
        val outcome = resolver.enemyAttacks(
            enemies = enemies,
            defender = CombatStats(maxHealth = 200),
            defenderPosition = origin,
            cooldownFor = { 1f },
            random = Random(1),
        )
        assertEquals(10, outcome.totalDamage)
        assertTrue(outcome.enemies.all { it.attackCooldown > 0f }, "an attacker did not go on cooldown")
    }

    @Test
    fun `an enemy still on cooldown does not attack`() {
        val enemies = listOf(enemy("a", 1f, 0f).copy(attackCooldown = 0.5f))
        val outcome = resolver.enemyAttacks(
            enemies, CombatStats(maxHealth = 200), origin, { 1f }, Random(1),
        )
        assertEquals(0, outcome.totalDamage)
    }

    @Test
    fun `an enemy out of range does not attack`() {
        val enemies = listOf(enemy("far", 12f, 0f))
        val outcome = resolver.enemyAttacks(
            enemies, CombatStats(maxHealth = 200), origin, { 1f }, Random(1),
        )
        assertEquals(0, outcome.totalDamage)
    }

    @Test
    fun `player armour reduces incoming damage`() {
        val enemies = listOf(enemy("a", 1f, 0f))
        val soft = resolver.enemyAttacks(enemies, CombatStats(maxHealth = 200), origin, { 1f }, Random(1))
        val armoured = resolver.enemyAttacks(
            enemies, CombatStats(maxHealth = 200, armour = 3), origin, { 1f }, Random(1),
        )
        assertTrue(armoured.totalDamage < soft.totalDamage)
    }
}
