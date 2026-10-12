package com.stratum.core.domain.quest

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyRank
import kotlin.math.hypot

/** Where a quest stands. */
enum class QuestStatus { ACTIVE, READY, DONE, FAILED }

/**
 * A quest the player took, counted as they play. [originX], [originY] is the
 * giver's town centre: quest places are measured from it.
 */
data class ActiveQuest(
    val quest: Quest,
    val originX: Int,
    val originY: Int,
    val progress: Int = 0,
    val elapsed: Float = 0f,
    /** Seconds spent standing at a VISIT place. */
    val dwell: Float = 0f,
    val status: QuestStatus = QuestStatus.ACTIVE,
    /** Why it failed, for the log. */
    val failure: String? = null,
) {
    val required: Int get() = QuestRules.required(quest)
    val placeX: Int? get() = quest.objective.place?.let { originX + it.dx }
    val placeY: Int? get() = quest.objective.place?.let { originY + it.dy }
    val fraction: Float get() = if (status == QuestStatus.READY || status == QuestStatus.DONE) 1f else (progress.toFloat() / required).coerceIn(0f, 1f)
}

/** Something that happened in play that a quest may count. */
sealed interface QuestEvent {
    data class Killed(val definitionId: String, val role: CombatRole, val rank: EnemyRank, val x: Float, val y: Float, val night: Boolean = false) : QuestEvent
    data class Mined(val blockId: String, val x: Int, val y: Int) : QuestEvent
    data class Placed(val blockId: String, val x: Int, val y: Int) : QuestEvent
    data class Collected(val blockId: String, val count: Int) : QuestEvent
    /** The player took damage. */
    data object Hurt : QuestEvent
    /** The player dealt damage of [damageTypeId]. */
    data class Dealt(val damageTypeId: String) : QuestEvent
    /** An attack tore up the ground. */
    data object Blasted : QuestEvent

    /** Time passing, with what the rules need to know about the world right now. */
    data class Tick(
        val seconds: Float,
        val playerX: Float,
        val playerY: Float,
        val night: Boolean,
        val followers: Int,
        /** Living hostiles within a radius of a point. */
        val hostilesWithin: (x: Int, y: Int, radius: Int) -> Int,
        /** The town the player stands in, if any. */
        val townId: String?,
        /** How many of a block the player carries. */
        val carrying: (blockId: String) -> Int,
    ) : QuestEvent
}

/**
 * How quests count. Pure: an [ActiveQuest] and an event in, the updated
 * quest out. Twists and constraints change the counting, not just the words.
 */
object QuestRules {

    /** How many the objective needs, with the twist applied: a swarm is half as many again. */
    fun required(q: Quest): Int = when (q.twist) {
        Twist.SWARM -> (q.objective.count * 3 + 1) / 2
        else -> q.objective.count
    }

    /** Seconds before a rival finishes the job first. */
    const val RIVAL_SECONDS = 420f

    /** Seconds to stand at a place for a visit to count. */
    const val DWELL_SECONDS = 3f

    /** Ground-breaking blasts a quiet quest tolerates. */
    const val QUIET_BLASTS = 0

    fun on(a: ActiveQuest, e: QuestEvent): ActiveQuest {
        if (a.status != QuestStatus.ACTIVE && !(a.status == QuestStatus.READY && e is QuestEvent.Tick)) return a
        val q = a.quest
        val o = q.objective
        return when (e) {
            is QuestEvent.Hurt -> if (q.constraint == Constraint.UNTOUCHED && a.status == QuestStatus.ACTIVE) fail(a, "You were struck.") else a
            is QuestEvent.Dealt -> if (q.constraint == Constraint.NO_FIRE && "fire" in e.damageTypeId.lowercase() && a.status == QuestStatus.ACTIVE) fail(a, "You used fire.") else a
            is QuestEvent.Blasted -> if (q.constraint == Constraint.QUIET && a.status == QuestStatus.ACTIVE) fail(a, "The whole valley heard you.") else a
            is QuestEvent.Killed -> {
                val counts = when (o.kind) {
                    ObjectiveKind.SLAY, ObjectiveKind.HUNT -> matches(o, e.definitionId, e.role) && e.rank.ordinal >= minRank(q).ordinal && near(a, e.x, e.y)
                    else -> false
                }
                if (!counts || (q.twist == Twist.NIGHT && !e.night)) a else advance(a, 1)
            }
            is QuestEvent.Mined -> if (o.kind == ObjectiveKind.MINE && e.blockId == o.targetId) advance(a, 1) else a
            is QuestEvent.Collected -> if (o.kind == ObjectiveKind.GATHER && e.blockId == o.targetId) advance(a, e.count) else a
            is QuestEvent.Placed -> if (o.kind == ObjectiveKind.BUILD && e.blockId == o.targetId && near(a, e.x + 0.5f, e.y + 0.5f)) advance(a, 1) else a
            is QuestEvent.Tick -> tick(a, e)
        }
    }

    private fun tick(a: ActiveQuest, e: QuestEvent.Tick): ActiveQuest {
        val q = a.quest
        val o = q.objective
        var next = a.copy(elapsed = a.elapsed + e.seconds)
        if (next.status == QuestStatus.ACTIVE) {
            when {
                q.timeLimit > 0f && next.elapsed > q.timeLimit -> return fail(next, if (q.constraint == Constraint.BEFORE_NIGHT) "Night fell first." else "Too slow.")
                q.constraint == Constraint.BEFORE_NIGHT && e.night && next.elapsed > 5f -> return fail(next, "Night fell first.")
                q.twist == Twist.RIVAL && next.elapsed > RIVAL_SECONDS -> return fail(next, "A rival got there first.")
                q.constraint == Constraint.ALONE && e.followers > 0 && next.progress > a.progress -> return fail(next, "You did not go alone.")
            }
        }
        when (o.kind) {
            ObjectiveKind.VISIT -> if (next.status == QuestStatus.ACTIVE) {
                next = if (within(next, e.playerX, e.playerY)) next.copy(dwell = next.dwell + e.seconds) else next.copy(dwell = 0f)
                if (next.dwell >= DWELL_SECONDS) next = next.copy(progress = 1, status = QuestStatus.READY)
            }
            ObjectiveKind.CLEAR -> if (next.status == QuestStatus.ACTIVE && within(next, e.playerX, e.playerY)) {
                val px = next.placeX ?: return next; val py = next.placeY ?: return next
                if (e.hostilesWithin(px, py, o.place!!.radius) == 0) next = next.copy(progress = 1, status = QuestStatus.READY)
            }
            ObjectiveKind.DELIVER -> if (next.status == QuestStatus.ACTIVE) {
                val have = e.carrying(o.targetId ?: "")
                next = next.copy(progress = have.coerceAtMost(next.required))
                if (have >= next.required && e.townId == o.toTownId) next = next.copy(status = QuestStatus.READY)
            }
            else -> Unit
        }
        return next
    }

    private fun advance(a: ActiveQuest, by: Int): ActiveQuest {
        val p = (a.progress + by).coerceAtMost(a.required)
        return a.copy(progress = p, status = if (p >= a.required) QuestStatus.READY else a.status)
    }

    private fun fail(a: ActiveQuest, why: String) = a.copy(status = QuestStatus.FAILED, failure = why)

    private fun minRank(q: Quest): EnemyRank = if (q.twist == Twist.ELITE) maxOf(q.objective.minRank, EnemyRank.ELITE) else q.objective.minRank

    private fun matches(o: QuestObjective, definitionId: String, role: CombatRole): Boolean =
        if (o.targetId != null) o.targetId == definitionId else o.role == role

    /** Within the quest's place, or anywhere when it has none. Kills count with some slack beyond the place. */
    private fun near(a: ActiveQuest, x: Float, y: Float): Boolean {
        val px = a.placeX ?: return true; val py = a.placeY ?: return true
        return hypot(x - px, y - py) <= a.quest.objective.place!!.radius * 2.5f
    }

    private fun within(a: ActiveQuest, x: Float, y: Float): Boolean {
        val px = a.placeX ?: return false; val py = a.placeY ?: return false
        return hypot(x - px, y - py) <= a.quest.objective.place!!.radius
    }
}
