package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.strategy.Affordability
import com.stratum.core.domain.strategy.Colony
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.strategy.RaidOutcome
import com.stratum.core.domain.strategy.StandardStrategy
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/** What happened when the player tried to found, build, recruit or deposit. */
sealed interface RealmResult {
    data class Founded(val outpost: Outpost) : RealmResult
    data class Built(val outpost: Outpost, val structureId: String) : RealmResult
    data class Recruited(val outpost: Outpost, val unitId: String) : RealmResult
    data class Deposited(val outpost: Outpost, val gained: Map<String, Float>) : RealmResult
    data class Mustered(val count: Int) : RealmResult
    data class Ordered(val order: FollowerOrder) : RealmResult
    data class CannotAfford(val why: Affordability) : RealmResult
    data object NotHere : RealmResult
    data object TooClose : RealmResult
    data object NotEnoughBlocks : RealmResult
    data object NoStrategy : RealmResult
    data object NoneToMuster : RealmResult
}

/** Things the realm did on its own, reported with the tick they happened in. */
sealed interface RealmEvent {
    /** A raid is coming at an outpost the player is standing in: fight it. */
    data class RaidArrived(val outpost: Outpost, val attackers: Int) : RealmEvent

    /** A raid on an outpost the player was away from, settled by numbers. */
    data class RaidResolved(val outpost: Outpost, val outcome: RaidOutcome) : RealmEvent

    /** Every raider in a fought raid is dead. */
    data class RaidRepelled(val outpost: Outpost) : RealmEvent
}

/**
 * The player's holdings: outposts that produce, build, recruit and get
 * raided, and the followers the player takes into the field.
 *
 * Outposts belong to this world, like its terrain: a new world starts a new
 * realm, while the hero -- level, gear, reputation -- carries over.
 */
internal class RealmSystem(private val content: AssembledContent, private val raids: Boolean) {

    private val book = content.strategyBook

    var outposts: List<Outpost> = emptyList()
        private set

    var order: FollowerOrder = FollowerOrder.FOLLOW
        private set

    /** Where followers hold, when told to. */
    var holdAt: WorldPoint? = null
        private set

    /** Outposts whose raid is being fought right now, by id. */
    private val fighting = HashSet<String>()

    val active: Boolean get() = !book.isEmpty

    fun outpost(id: String): Outpost? = outposts.firstOrNull { it.id == id }

    /** The outpost the player stands in, if any. */
    fun outpostAt(x: Int, y: Int): Outpost? = outposts.firstOrNull { distance(it, x, y) <= it.radius }

    /**
     * Plants a new outpost where the player stands, paid for in blocks from
     * the bag -- whatever they have most of, first. Not inside a town, and
     * not on top of another outpost.
     */
    fun found(player: PlayerState, towns: List<SettlementPlan>, name: String): Pair<PlayerState, RealmResult> {
        if (!active) return player to RealmResult.NoStrategy
        val x = player.blockPos.x
        val y = player.blockPos.y
        if (towns.any { it.contains(x, y, margin = Outpost.DEFAULT_RADIUS) } || outposts.any { distance(it, x, y) < it.radius * 2 + Outpost.DEFAULT_RADIUS }) {
            return player to RealmResult.TooClose
        }
        val paid = payInBlocks(player, StandardStrategy.FOUNDING_BLOCKS) ?: return player to RealmResult.NotEnoughBlocks
        val outpost = Outpost(
            id = "outpost_${outposts.size + 1}_${x}_$y",
            name = name,
            centerX = x,
            centerY = y,
            structures = listOfNotNull(book.structures.firstOrNull { it.housing > 0 }?.id).associateWith { 1 },
            raidIn = if (raids) Outpost.FIRST_RAID_SECONDS else -1f,
        )
        outposts = outposts + outpost
        return paid to RealmResult.Founded(outpost)
    }

    /** A liberated town becomes an outpost, its buildings turned into the structures nearest to what they were. */
    fun adopt(town: SettlementPlan): Outpost? {
        if (!active || outposts.any { it.id == town.id }) return null
        val structures = StandardStrategy.structuresFor(town.buildings.map { it.template.role }).filterKeys { book.structure(it) != null }
        val outpost = Outpost(
            id = town.id, name = town.name, centerX = town.centerX, centerY = town.centerY, radius = town.radius,
            structures = structures, raidIn = if (raids) Outpost.FIRST_RAID_SECONDS else -1f,
        )
        outposts = outposts + outpost
        return outpost
    }

    fun build(outpostId: String, structureId: String): RealmResult = change(outpostId) { outpost ->
        val built = Colony.built(outpost, book, structureId) ?: return@change null to RealmResult.CannotAfford(Colony.canBuild(outpost, book, structureId))
        built to RealmResult.Built(built, structureId)
    }

    fun recruit(outpostId: String, unitId: String): RealmResult = change(outpostId) { outpost ->
        val recruited = Colony.recruited(outpost, book, unitId) ?: return@change null to RealmResult.CannotAfford(Colony.canRecruit(outpost, book, unitId))
        recruited to RealmResult.Recruited(recruited, unitId)
    }

    /** Hands over everything in the bag an outpost can use. Only inside the outpost. */
    fun deposit(player: PlayerState): Pair<PlayerState, RealmResult> {
        val outpost = outpostAt(player.blockPos.x, player.blockPos.y) ?: return player to RealmResult.NotHere
        val (gained, used) = Colony.depositValue(player.inventory, book) { id -> content.registry.indexOrNull(id)?.let { content.registry.typeOf(it).material } }
        val emptied = player.copy(
            inventory = player.inventory - used.keys,
            hotbar = player.hotbar.filterNot { it in used },
            selectedSlot = 0,
        )
        val stocked = Colony.deposited(outpost, book, gained)
        replace(stocked)
        return emptied to RealmResult.Deposited(stocked, gained)
    }

    /**
     * Takes up to [count] soldiers from the garrison of the outpost the
     * player stands in, to follow them. Returns the unit ids to spawn.
     */
    fun muster(player: PlayerState, count: Int): Pair<List<String>, RealmResult> {
        val outpost = outpostAt(player.blockPos.x, player.blockPos.y) ?: return emptyList<String>() to RealmResult.NotHere
        val taken = outpost.garrison.flatMap { (id, n) -> List(n) { id } }.take(count)
        if (taken.isEmpty()) return taken to RealmResult.NoneToMuster
        val left = taken.groupingBy { it }.eachCount().let { out -> outpost.garrison.mapValues { (id, n) -> n - (out[id] ?: 0) }.filterValues { it > 0 } }
        replace(outpost.copy(garrison = left))
        return taken to RealmResult.Mustered(taken.size)
    }

    /** Followers home again: back into the garrison of the nearest outpost. */
    fun garrison(unitIds: List<String>, near: WorldPoint) {
        val home = outposts.minByOrNull { distance(it, floor(near.x).toInt(), floor(near.y).toInt()) } ?: return
        replace(home.copy(garrison = unitIds.fold(home.garrison) { g, id -> g + (id to (g[id] ?: 0) + 1) }))
    }

    fun command(order: FollowerOrder, at: WorldPoint): RealmResult {
        this.order = order
        holdAt = at.takeIf { order == FollowerOrder.HOLD }
        return RealmResult.Ordered(order)
    }

    /** Where followers go home to when told to return: the nearest outpost's square. */
    fun returnPoint(from: WorldPoint): WorldPoint? =
        outposts.minByOrNull { distance(it, floor(from.x).toInt(), floor(from.y).toInt()) }?.let { WorldPoint(it.centerX + 0.5f, it.centerY + 0.5f, from.z) }

    /** Production, and raids falling due: fought if the player is there, settled by numbers if not. */
    fun advance(deltaSeconds: Float, player: WorldPoint, random: Random): List<RealmEvent> {
        val events = mutableListOf<RealmEvent>()
        outposts = outposts.map { outpost ->
            val produced = Colony.produced(outpost, book, deltaSeconds / SECONDS_PER_MINUTE)
            if (outpost.raidIn < 0f || outpost.id in fighting) return@map produced
            val due = produced.copy(raidIn = produced.raidIn - deltaSeconds)
            if (due.raidIn > 0f) return@map due
            if (distance(due, floor(player.x).toInt(), floor(player.y).toInt()) <= due.radius + PRESENCE) {
                fighting += due.id
                events += RealmEvent.RaidArrived(due, Colony.raidStrength(due).coerceAtMost(MAX_RAIDERS))
                due.copy(raidIn = Colony.raidInterval(due))
            } else {
                val (after, outcome) = Colony.resolveRaid(due, book, random)
                events += RealmEvent.RaidResolved(after, outcome)
                after
            }
        }
        return events
    }

    /** A fought raid is over when its raiders are gone. */
    fun raidsOver(raidersLeft: (String) -> Int): List<RealmEvent> =
        fighting.filter { raidersLeft(it) == 0 }.mapNotNull { id ->
            fighting -= id
            outpost(id)?.let { held -> held.copy(raidsSurvived = held.raidsSurvived + 1).also(::replace) }?.let { RealmEvent.RaidRepelled(it) }
        }

    fun restore(saved: List<Outpost>) {
        outposts = saved
    }

    private fun change(outpostId: String, action: (Outpost) -> Pair<Outpost?, RealmResult>): RealmResult {
        val outpost = outpost(outpostId) ?: return RealmResult.NotHere
        val (updated, result) = action(outpost)
        updated?.let(::replace)
        return result
    }

    private fun replace(outpost: Outpost) {
        outposts = outposts.map { if (it.id == outpost.id) outpost else it }
    }

    /** Spends [count] placeable blocks, largest stacks first, or null when the bag holds fewer. */
    private fun payInBlocks(player: PlayerState, count: Int): PlayerState? {
        val blocks = player.inventory.filterKeys(content.registry::contains).entries.sortedByDescending { it.value }
        if (blocks.sumOf { it.value } < count) return null
        var owed = count
        var paid = player
        blocks.forEach { (id, held) ->
            val take = minOf(owed, held)
            repeat(take) { paid = paid.consuming(id) ?: paid }
            owed -= take
        }
        return paid.copy(hotbar = paid.hotbar.filter { paid.countOf(it) > 0 }, selectedSlot = 0)
    }

    private fun distance(outpost: Outpost, x: Int, y: Int): Float {
        val dx = (x - outpost.centerX).toFloat()
        val dy = (y - outpost.centerY).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val SECONDS_PER_MINUTE = 60f
        /** How near the player must be for a raid to be fought rather than settled. */
        const val PRESENCE = 30
        const val MAX_RAIDERS = 10
        const val MAX_FOLLOWERS = 6
    }
}
