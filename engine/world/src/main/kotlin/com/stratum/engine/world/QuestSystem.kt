package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.quest.ActiveQuest
import com.stratum.core.domain.quest.ObjectiveKind
import com.stratum.core.domain.quest.Quest
import com.stratum.core.domain.quest.QuestEvent
import com.stratum.core.domain.quest.QuestGenerator
import com.stratum.core.domain.quest.QuestRules
import com.stratum.core.domain.quest.QuestStatus
import com.stratum.core.domain.quest.QuestTarget
import com.stratum.core.domain.quest.QuestWorld
import com.stratum.core.domain.quest.RewardKind
import com.stratum.core.domain.quest.Twist
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.BlockMaterial
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.hypot

/** What happened when the player asked something of a quest. */
sealed interface QuestResult {
    data class Accepted(val quest: Quest) : QuestResult
    data class Rewarded(val quest: Quest, val experience: Int, val next: Quest?) : QuestResult
    data class Abandoned(val quest: Quest) : QuestResult
    data object NotHere : QuestResult
    data object NotReady : QuestResult
    data object LogFull : QuestResult
    data object Unknown : QuestResult
}

/**
 * Quests in play: each friendly town's board, the player's log, and the
 * counting that moves a quest along.
 *
 * Boards come from the town's residents and the world day, so a board is the
 * same on every visit that day and new the next. Quests that need a fight
 * where none is -- a hunt, a clearing, a slaying at a place -- bring their
 * monsters when the player draws near, once.
 */
internal class QuestSystem(
    private val content: AssembledContent,
    private val settings: GameSettings,
    private val seed: Long,
    private val town: TownLife,
) {
    private val log = ArrayList<ActiveQuest>()
    private val spawned = HashSet<String>()
    private val finished = HashSet<String>()
    private val boards = HashMap<String, Pair<Int, List<Quest>>>()

    val active: List<ActiveQuest> get() = log.toList()

    /** [town]'s board on [day], less what the player already took or finished. */
    fun board(town: SettlementPlan, day: Int, playerLevel: Int, nearby: List<SettlementPlan>): List<Quest> {
        val cached = boards[town.id]
        val quests = if (cached != null && cached.first == day) cached.second else QuestGenerator.board(
            givers = this.town.residentsOf(town), world = worldFor(town, nearby), seed = seed, day = day,
            count = settings.questsPerTown, difficulty = settings.questDifficulty, playerLevel = playerLevel,
        ).also { boards[town.id] = day to it }
        val taken = log.mapTo(HashSet()) { it.quest.id }
        return quests.filter { it.id !in taken && it.id !in finished }
    }

    fun accept(quest: Quest, origin: SettlementPlan): QuestResult {
        if (log.any { it.quest.id == quest.id }) return QuestResult.Accepted(quest)
        if (log.count { it.status != QuestStatus.FAILED } >= MAX_ACTIVE) return QuestResult.LogFull
        log += ActiveQuest(quest, origin.centerX, origin.centerY)
        return QuestResult.Accepted(quest)
    }

    fun abandon(questId: String): QuestResult {
        val a = log.firstOrNull { it.quest.id == questId } ?: return QuestResult.Unknown
        log.remove(a)
        return QuestResult.Abandoned(a.quest)
    }

    /**
     * Hands in a quest that is ready, to its giver: in the giver's town, or for
     * a delivery in the town it was carried to. Returns the reward, and the
     * next in the chain when there is one.
     */
    fun turnIn(questId: String, here: SettlementPlan?, playerLevel: Int, nearby: List<SettlementPlan>, take: (String, Int) -> Unit): QuestResult {
        val a = log.firstOrNull { it.quest.id == questId } ?: return QuestResult.Unknown
        if (a.status != QuestStatus.READY) return QuestResult.NotReady
        val o = a.quest.objective
        val rightTown = if (o.kind == ObjectiveKind.DELIVER) here?.id == o.toTownId else here?.id == a.quest.giver.townId
        if (!rightTown) return QuestResult.NotHere
        if (o.kind == ObjectiveKind.DELIVER) take(o.targetId ?: "", a.required)
        log.remove(a)
        finished += a.quest.id
        val next = if (settings.questChains && here != null) {
            QuestGenerator.followUp(a.quest, worldFor(here, nearby), settings.questDifficulty, playerLevel)?.also { log += ActiveQuest(it, here.centerX, here.centerY) }
        } else null
        return QuestResult.Rewarded(a.quest, a.quest.reward.experience, next)
    }

    /** Counts [event] against every quest in the log. */
    fun on(event: QuestEvent) {
        if (log.isEmpty()) return
        for (i in log.indices) log[i] = QuestRules.on(log[i], event)
    }

    /**
     * Monsters a quest needs where none roam: the hunted beast and its guards,
     * a clearing's occupants, a slaying's quarry. Brought once, when the player
     * comes within [SPAWN_RANGE] of the place.
     */
    fun encounters(player: WorldPoint, ground: (Int, Int) -> WorldPoint?, make: (String, WorldPoint, EnemyRank) -> EnemyInstance?): List<EnemyInstance> {
        val out = ArrayList<EnemyInstance>()
        for (a in log) {
            if (a.status != QuestStatus.ACTIVE || a.quest.id in spawned) continue
            val o = a.quest.objective
            val px = a.placeX ?: continue; val py = a.placeY ?: continue
            if (hypot(player.x - px, player.y - py) > SPAWN_RANGE) continue
            val target = o.targetId ?: content.enemies.firstOrNull { it.spawnWeight > 0 && (o.role == null || it.role == o.role) }?.id ?: continue
            spawned += a.quest.id
            val guards = if (a.quest.twist == Twist.GUARDED) 3 else 0
            val count = when (o.kind) {
                ObjectiveKind.HUNT -> 1
                ObjectiveKind.CLEAR -> 4 + settings.questDifficulty * 2
                ObjectiveKind.SLAY -> a.required
                else -> 0
            }
            val rank = when {
                o.kind == ObjectiveKind.HUNT -> o.minRank
                a.quest.twist == Twist.ELITE -> EnemyRank.ELITE
                else -> EnemyRank.MINION
            }
            repeat(count + guards) { k ->
                val angle = k * 2.399f
                val r = 1.5f + (k % 5) * 1.2f
                val spot = ground((px + kotlin.math.cos(angle) * r).toInt(), (py + kotlin.math.sin(angle) * r).toInt()) ?: return@repeat
                val definition = if (k < count) target else content.enemies.filter { it.spawnWeight > 0 }.randomOrNull()?.id ?: target
                make(definition, spot, if (k < count) rank else EnemyRank.ELITE)?.let { out += it }
            }
        }
        return out
    }

    /** What this region offers quests to aim at. */
    fun worldFor(town: SettlementPlan, nearby: List<SettlementPlan>): QuestWorld {
        val monsters = content.enemies.filter { it.spawnWeight > 0 && it.factionId == null }.map { QuestTarget(it.id, plural(it.name), it.role) }
        val blocks = content.registry.all.filter { !it.isAir }
        fun named(m: Set<BlockMaterial>) = blocks.filter { it.material in m && it.isBreakable }.map { QuestTarget(it.id, it.displayName.lowercase()) }
        return QuestWorld(
            monsters = monsters,
            gatherables = named(setOf(BlockMaterial.FOLIAGE, BlockMaterial.SOIL)),
            mineables = named(setOf(BlockMaterial.STONE, BlockMaterial.ORE)),
            buildables = named(setOf(BlockMaterial.WOOD, BlockMaterial.STONE)).take(12),
            towns = nearby.filter { it.id != town.id }.map { QuestTarget(it.id, it.name) },
        )
    }

    /** Quests that just became ready or failed since the last call, for a toast. */
    fun news(): List<ActiveQuest> {
        val now = log.filter { it.status == QuestStatus.READY || it.status == QuestStatus.FAILED }.filter { it.quest.id !in announced }
        announced += now.map { it.quest.id }
        return now
    }

    private val announced = HashSet<String>()

    private fun plural(name: String): String = name.lowercase().let {
        when {
            it.endsWith("s") || it.endsWith("x") -> it
            it.endsWith("f") -> it.dropLast(1) + "ves"
            it.endsWith("y") && it.length > 2 && it[it.length - 2] !in "aeiou" -> it.dropLast(1) + "ies"
            else -> "${it}s"
        }
    }

    companion object {
        const val MAX_ACTIVE = 6
        const val SPAWN_RANGE = 36f
    }
}
