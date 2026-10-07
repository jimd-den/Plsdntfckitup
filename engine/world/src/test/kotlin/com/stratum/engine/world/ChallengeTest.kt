package com.stratum.engine.world

import com.stratum.core.domain.content.ContentPackAssembler
import com.stratum.core.domain.settings.Challenge
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.world.WorldConfig
import com.stratum.core.domain.world.WorldRules
import com.stratum.engine.world.CombatCoreFixtures.place
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Difficulty: what monsters' blows cost, how often they come, and that a crowd never stun-locks the player. */
class ChallengeTest {

    private val fixtures = CombatCoreFixtures

    /** A clubber that hits like a landslide: its own numbers would fell any hero in one blow. */
    private val crusher = fixtures.clubber.copy(id = "test:crusher", name = "Crusher", baseStats = fixtures.clubber.baseStats.copy(attackPower = 5000))

    private fun session(challenge: Challenge, stunLocks: Boolean = false) = WorldSession(
        ContentPackAssembler().assemble(listOf(fixtures.pack().let { it.copy(enemies = it.enemies + crusher) })),
        WorldConfig(seed = 5L, simulationRadius = 1, rules = WorldRules()),
        terrainGenerator = CombatCoreFixtures.FlatTerrain,
        settings = GameSettings(challenge = challenge, stunLocks = stunLocks),
    ).also { it.enemies = emptyList() }

    /** [count] clubbers -- every blow dazes for a second -- in a ring at arm's length. */
    private fun WorldSession.ring(count: Int) = repeat(count) { i ->
        val a = i * 2.0 * Math.PI / count
        place(fixtures.clubber, dx = cos(a).toFloat(), dy = sin(a).toFloat(), id = "club$i")
    }

    /** Runs [seconds] in frame steps, keeping the player alive, and reports what happened. */
    private class Fight(val stunnedShare: Float, val longestStun: Float, val damage: Int, val swings: Int)

    private fun WorldSession.fight(seconds: Float, step: Float = 0.05f): Fight {
        var stunned = 0; var ticks = 0; var run = 0f; var longest = 0f; var damage = 0; var swings = 0
        var t = 0f
        while (t < seconds) {
            val before = enemies.associate { it.instanceId to it.attackCooldown }
            val health = player.health
            tick(step)
            damage += (health - player.health).coerceAtLeast(0)
            player = player.copy(health = health.coerceAtLeast(player.health))
            swings += enemies.count { e -> e.attackCooldown > (before[e.instanceId] ?: 0f) + 1e-4f }
            val held = statusesOf(WorldSession.PLAYER_ACTOR_ID).has(fixtures.daze.id)
            ticks++
            if (held) { stunned++; run += step; if (run > longest) longest = run } else run = 0f
            t += step
        }
        return Fight(stunned.toFloat() / ticks, longest, damage, swings)
    }

    @Test
    fun `a crowd of dazing monsters never stun-locks the player, at any difficulty`() {
        for (challenge in Challenge.entries) {
            val s = session(challenge)
            s.ring(6)
            val f = s.fight(8f)
            assertTrue(f.stunnedShare > 0.04f, "$challenge: the crowd never dazed the player (${f.stunnedShare})")
            // A daze lasts a second at full length; the guard after it always leaves the player time to act.
            assertTrue(f.longestStun <= 1f * challenge.controlTime + 0.11f, "$challenge: held ${f.longestStun}s in one stun")
            val most = challenge.controlTime / (challenge.controlTime + challenge.stunGuard)
            assertTrue(f.stunnedShare <= most + 0.05f, "$challenge: stunned ${f.stunnedShare} of the fight, at most $most expected")
        }
    }

    @Test
    fun `only so many monsters attack at once, and the rest wait their turn`() {
        val story = session(Challenge.STORY).apply { ring(6) }.fight(6f)
        val hard = session(Challenge.HARD).apply { ring(6) }.fight(6f)
        // One clubber at a time on Story, swinging every 1.6 s; four at a time on Hard, every second.
        assertTrue(story.swings in 3..5, "story: ${story.swings} swings in 6 s")
        assertTrue(hard.swings >= 20, "hard: only ${hard.swings} swings in 6 s")
    }

    @Test
    fun `easier difficulties take less from every blow`() {
        val taken = Challenge.entries.associateWith { c -> session(c).apply { ring(1) }.fight(6f).damage }
        val order = Challenge.entries.map { taken.getValue(it) }
        assertTrue(order.zipWithNext().all { (a, b) -> a <= b }, "damage taken by difficulty: $taken")
        assertTrue(taken.getValue(Challenge.STORY) * 2 < taken.getValue(Challenge.HARD), "story is not much gentler: $taken")
    }

    @Test
    fun `with stun-locks allowed, a crowd's stuns chain with no gap`() {
        val s = session(Challenge.HARD, stunLocks = true)
        s.ring(6)
        val f = s.fight(8f)
        assertTrue(f.longestStun > 2f, "stuns did not chain: longest ${f.longestStun}s")
    }

    @Test
    fun `no blow takes more than the player's level allows`() {
        for (challenge in listOf(Challenge.NORMAL, Challenge.BRUTAL)) {
            val s = session(challenge)
            s.place(crusher, dx = 1f, id = "crusher")
            val life = s.maxHealth
            val ceiling = com.stratum.core.domain.difficulty.BlowCeiling.of(life, com.stratum.core.domain.actor.EnemyRank.MINION, 0, 0, challenge.damageTaken)
            var worst = 0
            repeat(60) {
                val before = s.player.health
                s.tick(0.05f)
                worst = maxOf(worst, before - s.player.health)
                s.player = s.player.copy(health = life)
            }
            assertTrue(worst in 1..ceiling, "$challenge: a blow took $worst of $life life, ceiling $ceiling")
        }
    }

    @Test
    fun `the ceiling grows with the player's level, so blows keep pace with the hero`() {
        val low = com.stratum.core.domain.difficulty.BlowCeiling.of(100, com.stratum.core.domain.actor.EnemyRank.ELITE, 0, 0)
        val high = com.stratum.core.domain.difficulty.BlowCeiling.of(800, com.stratum.core.domain.actor.EnemyRank.ELITE, 0, 0)
        assertTrue(high == low * 8 || kotlin.math.abs(high - low * 8) <= 8, "$low at 100 life, $high at 800")
        // A monster the player has outgrown hits softer; one above them, harder; a higher tier, harder still.
        val share = { gap: Int, tier: Int -> com.stratum.core.domain.difficulty.BlowCeiling.share(com.stratum.core.domain.actor.EnemyRank.MINION, gap, tier) }
        assertTrue(share(-5, 0) < share(0, 0) && share(0, 0) < share(5, 0) && share(0, 0) < share(0, 16) && share(0, 16) < share(0, 30))
    }
}
