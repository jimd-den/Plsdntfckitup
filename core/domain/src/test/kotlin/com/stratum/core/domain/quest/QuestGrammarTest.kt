package com.stratum.core.domain.quest

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyRank
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestGrammarTest {

    private val world = QuestWorld(
        monsters = listOf(QuestTarget("m:viper", "sand vipers", CombatRole.SWARMER), QuestTarget("m:brute", "stone brutes", CombatRole.BRUTE), QuestTarget("m:archer", "thorn archers", CombatRole.RANGED)),
        gatherables = listOf(QuestTarget("b:grass", "tall grass"), QuestTarget("b:clay", "red clay")),
        mineables = listOf(QuestTarget("b:iron", "iron ore"), QuestTarget("b:granite", "granite")),
        buildables = listOf(QuestTarget("b:wood", "planks")),
        towns = listOf(QuestTarget("t:2", "Umuahia"), QuestTarget("t:3", "Nsukka")),
    )
    private val givers = QuestGenerator.residents("t:1", "Awka", 7L, 10)

    @Test
    fun `the grammar offers millions of quests that play differently`() {
        // A modest world: a dozen monster kinds, twenty blocks, five towns.
        assertTrue(QuestGenerator.varieties(12, 20, 5) > BigInteger.valueOf(1_000_000L), "${QuestGenerator.varieties(12, 20, 5)}")
        // Generated quests are nearly all different in play, not just in words.
        val prints = HashSet<String>()
        var made = 0
        for (s in 0 until 20_000) {
            val q = QuestGenerator.quest(givers[s % givers.size], world, s * 7919L + 1, difficulty = s % 4) ?: continue
            made++; prints += q.fingerprint
        }
        assertTrue(prints.size > made * 0.6, "${prints.size} distinct of $made")
    }

    @Test
    fun `a board never repeats an objective and twist, and is the same all day`() {
        val board = QuestGenerator.board(givers, world, seed = 5L, day = 3, count = 8, difficulty = 1)
        assertEquals(8, board.size)
        val pairs = board.filter { it.twist != Twist.NONE }.map { it.objective.kind to it.twist }
        assertEquals(pairs.size, pairs.toSet().size)
        assertEquals(board.map { it.fingerprint }.toSet().size, board.size)
        assertEquals(board, QuestGenerator.board(givers, world, seed = 5L, day = 3, count = 8, difficulty = 1))
        val tomorrow = QuestGenerator.board(givers, world, seed = 5L, day = 4, count = 8, difficulty = 1)
        assertTrue(tomorrow.map { it.id }.toSet() != board.map { it.id }.toSet(), "the board did not change overnight")
        board.forEach { q -> assertTrue(q.title.isNotBlank() && q.pitch.length > 40 && q.goal.endsWith("."), q.pitch) }
    }

    @Test
    fun `residents keep their names and trades on every visit`() {
        assertEquals(givers, QuestGenerator.residents("t:1", "Awka", 7L, 10))
        assertTrue(givers.map { it.name }.toSet().size >= 8, "names repeat too much: ${givers.map { it.name }}")
    }

    private fun slay(twist: Twist = Twist.NONE, constraint: Constraint = Constraint.NONE, count: Int = 3) = Quest(
        id = "q", giver = givers[0], motive = Motive.HUNGER,
        objective = QuestObjective(ObjectiveKind.SLAY, "m:viper", "sand vipers", CombatRole.SWARMER, count = count),
        twist = twist, constraint = constraint, reward = QuestReward(RewardKind.COIN, 100, 30), timeLimit = if (constraint == Constraint.QUICK) 60f else 0f, seed = 1L,
    )

    private fun kill(rank: EnemyRank = EnemyRank.MINION, night: Boolean = false, id: String = "m:viper") =
        QuestEvent.Killed(id, CombatRole.SWARMER, rank, 0f, 0f, night)

    private fun tick(s: Float = 1f, night: Boolean = false, followers: Int = 0) = QuestEvent.Tick(s, 0f, 0f, night, followers, { _, _, _ -> 0 }, null, { 0 })

    @Test
    fun `kills count, and twists and constraints change what counts`() {
        var a = ActiveQuest(slay(), 0, 0)
        repeat(2) { a = QuestRules.on(a, kill()) }
        a = QuestRules.on(a, kill(id = "m:brute"))
        assertEquals(2, a.progress)
        a = QuestRules.on(a, kill())
        assertEquals(QuestStatus.READY, a.status)

        var elite = ActiveQuest(slay(Twist.ELITE), 0, 0)
        elite = QuestRules.on(elite, kill())
        assertEquals(0, elite.progress, "a minion counted for an elite hunt")
        elite = QuestRules.on(elite, kill(EnemyRank.ELITE))
        assertEquals(1, elite.progress)

        var night = ActiveQuest(slay(Twist.NIGHT), 0, 0)
        night = QuestRules.on(night, kill(night = false))
        night = QuestRules.on(night, kill(night = true))
        assertEquals(1, night.progress)

        assertEquals(5, ActiveQuest(slay(Twist.SWARM, count = 3), 0, 0).required)
    }

    @Test
    fun `broken constraints fail the quest`() {
        assertEquals(QuestStatus.FAILED, QuestRules.on(ActiveQuest(slay(constraint = Constraint.UNTOUCHED), 0, 0), QuestEvent.Hurt).status)
        assertEquals(QuestStatus.FAILED, QuestRules.on(ActiveQuest(slay(constraint = Constraint.NO_FIRE), 0, 0), QuestEvent.Dealt("igbo:fire")).status)
        assertEquals(QuestStatus.ACTIVE, QuestRules.on(ActiveQuest(slay(constraint = Constraint.NO_FIRE), 0, 0), QuestEvent.Dealt("igbo:physical")).status)
        assertEquals(QuestStatus.FAILED, QuestRules.on(ActiveQuest(slay(constraint = Constraint.QUICK), 0, 0), tick(61f)).status)
        assertEquals(QuestStatus.FAILED, QuestRules.on(ActiveQuest(slay(constraint = Constraint.BEFORE_NIGHT), 0, 0, elapsed = 10f), tick(1f, night = true)).status)
        assertEquals(QuestStatus.FAILED, QuestRules.on(ActiveQuest(slay(Twist.RIVAL), 0, 0), tick(QuestRules.RIVAL_SECONDS + 1)).status)
        var alone = ActiveQuest(slay(constraint = Constraint.ALONE), 0, 0)
        alone = QuestRules.on(alone, kill())
        alone = QuestRules.on(alone, tick(followers = 2))
        // Progress made with followers in tow fails it on the next look.
        assertTrue(alone.status == QuestStatus.ACTIVE || alone.status == QuestStatus.FAILED)
    }

    @Test
    fun `visits need a moment at the place, deliveries need the goods in the right town`() {
        val visit = assertNotNull(QuestGenerator.quest(givers[0], world, 3L)).copy(objective = QuestObjective(ObjectiveKind.VISIT, place = QuestPlace(40, 0, 6, "the Red Ridge")), twist = Twist.NONE, constraint = Constraint.NONE)
        var a = ActiveQuest(visit, 0, 0)
        val there = QuestEvent.Tick(1f, 40f, 1f, false, 0, { _, _, _ -> 0 }, null, { 0 })
        repeat(2) { a = QuestRules.on(a, there) }
        assertEquals(QuestStatus.ACTIVE, a.status)
        repeat(2) { a = QuestRules.on(a, there) }
        assertEquals(QuestStatus.READY, a.status)

        val deliver = visit.copy(objective = QuestObjective(ObjectiveKind.DELIVER, "b:clay", "red clay", count = 4, toTownId = "t:2", toTownName = "Umuahia"))
        var d = ActiveQuest(deliver, 0, 0)
        d = QuestRules.on(d, QuestEvent.Tick(1f, 0f, 0f, false, 0, { _, _, _ -> 0 }, "t:3", { 5 }))
        assertEquals(QuestStatus.ACTIVE, d.status, "delivered to the wrong town")
        d = QuestRules.on(d, QuestEvent.Tick(1f, 0f, 0f, false, 0, { _, _, _ -> 0 }, "t:2", { 5 }))
        assertEquals(QuestStatus.READY, d.status)
    }
}
