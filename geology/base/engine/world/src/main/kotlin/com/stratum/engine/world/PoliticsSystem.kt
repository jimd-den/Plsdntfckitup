package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyState
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.faction.Factions
import com.stratum.core.domain.faction.Stance
import com.stratum.core.domain.settlement.SettlementAtlas
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.session.RealmSave
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Sides, towns and holdings as a session offers them; see [PoliticsSystem]. */
interface SessionPolitics {
    /** The town the player is standing in, or null out in the wilds. */
    val currentSettlement: SettlementPlan?

    /** Whether the loaded packs give outposts something to build. */
    val realmActive: Boolean

    val outposts: List<Outpost>

    /** The outpost the player stands in. */
    val currentOutpost: Outpost?

    /** The player's followers in the field. */
    val followers: List<EnemyInstance>

    val followerOrder: FollowerOrder

    /** Whether [enemy] fights the player: its faction is hostile, or the player struck it. */
    fun isHostile(enemy: EnemyInstance): Boolean

    /** Whether [enemy] is on the player's side, and so is never a target. */
    fun isAllied(enemy: EnemyInstance): Boolean

    /** Whether [town] is held against the player: a hostile faction's, or an unaligned camp with a garrison, and not yet freed. */
    fun isHostileTown(town: SettlementPlan): Boolean

    /** Towns within [radius] blocks of the player, for a map or a compass. */
    fun settlementsNear(radius: Int): List<SettlementPlan>

    fun foundOutpost(name: String): RealmResult

    fun build(outpostId: String, structureId: String): RealmResult

    fun recruit(outpostId: String, unitId: String): RealmResult

    fun deposit(): RealmResult

    /** Takes soldiers from this outpost's garrison to follow the player, up to the follower limit. */
    fun muster(count: Int = RealmSystem.MAX_FOLLOWERS): RealmResult

    fun command(order: FollowerOrder): RealmResult
}

/**
 * Who is on whose side, and what the player holds: factions and standing,
 * towns and their garrisons, outposts, followers and raids.
 *
 * One place answers "is this body hostile to the player", so towns, guards,
 * raids, followers and the fight itself never keep opinions of their own.
 * With no strategy content loaded, or raids off by the world's rules, the
 * outpost clock does not run at all.
 */
internal class PoliticsSystem(
    private val state: SessionState,
    private val content: AssembledContent,
    private val atlas: SettlementAtlas?,
    private val director: EnemyDirector,
    private val realm: RealmSystem,
    private val cues: SessionCues,
    private val random: Random,
) : SessionPolitics {
    private val garrisons = Garrisons(director)

    /** Neutral people the player has struck: they fight back until they are dead or the player leaves. */
    private val provoked = HashSet<String>()

    /** Seconds each follower has spent failing to close on the player. */
    private val lagging = HashMap<String, Float>()

    private val player: PlayerState get() = state.player

    override fun isHostile(enemy: EnemyInstance): Boolean =
        enemy.instanceId in provoked || content.factionBook.stanceToPlayer(enemy.factionId, player.reputation) == Stance.HOSTILE

    override fun isAllied(enemy: EnemyInstance): Boolean =
        enemy.instanceId !in provoked && content.factionBook.stanceToPlayer(enemy.factionId, player.reputation) == Stance.ALLIED

    override fun isHostileTown(town: SettlementPlan): Boolean {
        if (garrisons.isLiberated(town.id)) return false
        return if (town.factionId != null) content.factionBook.stanceToPlayer(town.factionId, player.reputation) == Stance.HOSTILE
        else town.recipe.garrison.isNotEmpty()
    }

    fun provoke(ids: Collection<String>) {
        provoked += ids
    }

    override val currentSettlement: SettlementPlan? get() = atlas?.settlementAt(player.blockPos.x, player.blockPos.y)

    override fun settlementsNear(radius: Int): List<SettlementPlan> = atlas?.settlementsNear(player.blockPos.x, player.blockPos.y, radius).orEmpty()

    /** Towns whose people the player should be able to see or meet. */
    fun townsInSight(): List<SettlementPlan> = if (atlas == null) emptyList() else settlementsNear(TOWN_SIGHT)

    /** Friendly streets are safe: nothing wild spawns inside their walls. */
    fun spawnAllowed(towns: List<SettlementPlan>): (WorldPoint) -> Boolean =
        if (towns.isEmpty()) { _ -> true }
        else { spot -> towns.none { it.contains(floor(spot.x).toInt(), floor(spot.y).toInt()) && !isHostileTown(it) } }

    /** Town guards turned out for the towns in sight. */
    fun musterGarrisons(towns: List<SettlementPlan>): List<EnemyInstance> =
        if (towns.isEmpty()) emptyList() else garrisons.muster(towns, state.enemies, player.level, random)

    /** The dead's politics: standing with their faction, and a town freed when its last guard falls. */
    fun onSlain(slain: List<EnemyInstance>) {
        slain.forEach { enemy ->
            enemy.factionId?.let { state.player = player.copy(reputation = player.reputation.afterKilling(it, content.factionBook)) }
            provoked -= enemy.instanceId
        }
        garrisons.onSlain(slain, townsInSight()).forEach(::liberate)
    }

    override val realmActive: Boolean get() = realm.active

    override val outposts: List<Outpost> get() = realm.outposts

    override val currentOutpost: Outpost? get() = realm.outpostAt(player.blockPos.x, player.blockPos.y)

    override val followers: List<EnemyInstance> get() = state.enemies.filter { it.factionId == Factions.PLAYER && it.home == null && it.isAlive }

    override val followerOrder: FollowerOrder get() = realm.order

    override fun foundOutpost(name: String): RealmResult = realm.found(player, townsInSight(), name).also { state.player = it.first }.second

    override fun build(outpostId: String, structureId: String): RealmResult = realm.build(outpostId, structureId)

    override fun recruit(outpostId: String, unitId: String): RealmResult = realm.recruit(outpostId, unitId)

    override fun deposit(): RealmResult = realm.deposit(player).also { state.player = it.first }.second

    override fun muster(count: Int): RealmResult {
        val room = (RealmSystem.MAX_FOLLOWERS - followers.size).coerceAtLeast(0)
        val (unitIds, result) = realm.muster(player, minOf(count, room))
        state.enemies = state.enemies + unitIds.mapNotNull { spawnSoldier(it, near = player.position, home = null) }
        return result
    }

    override fun command(order: FollowerOrder): RealmResult = realm.command(order, player.position)

    fun allyOrders() = AllyOrders(realm.order, player.position, realm.holdAt, realm.returnPoint(player.position))

    /** Production, and raids falling due. Nothing to do, and nothing done, while the player holds no outpost. */
    fun advance(deltaSeconds: Float): List<CombatEvent> {
        if (!realm.active || realm.outposts.isEmpty()) return emptyList()
        return realm.advance(deltaSeconds, player.position, random).onEach { event ->
            if (event is RealmEvent.RaidArrived) raid(event.outpost, event.attackers)
        }.map(CombatEvent::Realm)
    }

    /** After the fight: followers sent home arrive, and stragglers catch up. */
    fun afterFight(deltaSeconds: Float) {
        if (state.enemies.none { it.factionId == Factions.PLAYER }) return lagging.clear()
        homeComing()
        rally(deltaSeconds)
    }

    /** Fought raids whose raiders are all gone are over; the garrison that turned out goes home. */
    fun endRaids(): List<CombatEvent> {
        if (realm.outposts.isEmpty()) return emptyList()
        return realm.raidsOver { id -> state.enemies.count { it.squadId == RAID_SQUAD + id && it.isAlive } }.map { event ->
            if (event is RealmEvent.RaidRepelled) {
                val defenders = state.enemies.filter { it.factionId == Factions.PLAYER && it.home != null }
                realm.garrison(defenders.mapNotNull { it.squadId?.removePrefix(UNIT_SQUAD) }, WorldPoint(event.outpost.centerX.toFloat(), event.outpost.centerY.toFloat(), 0f))
                state.enemies = state.enemies - defenders.toSet()
            }
            CombatEvent.Realm(event)
        }
    }

    /**
     * The realm as a save keeps it. Followers are kept by unit, to be raised
     * again beside the player; a garrison that turned out for a raid is put
     * back in its outpost, since the raid itself is not kept.
     */
    fun realmSave(): RealmSave {
        val soldiers = state.enemies.filter { it.factionId == Factions.PLAYER && it.isAlive && it.squadId?.startsWith(UNIT_SQUAD) == true }
        val (defending, following) = soldiers.partition { it.home != null }
        val outposts = defending.groupBy { it.home!! }.entries.fold(realm.outposts) { held, (home, units) ->
            realm.garrisoned(held, units.map { it.squadId!!.removePrefix(UNIT_SQUAD) }, home)
        }
        return RealmSave(
            outposts = outposts,
            followerOrder = realm.order,
            holdAt = realm.holdAt,
            followers = following.map { it.squadId!!.removePrefix(UNIT_SQUAD) },
            liberatedTowns = garrisons.liberatedIds.toSet(),
        )
    }

    /** Puts a saved realm back, raising its followers beside wherever the player now stands. */
    fun restore(saved: RealmSave) {
        realm.restore(saved.outposts, saved.followerOrder, saved.holdAt)
        garrisons.restore(saved.liberatedTowns)
        state.enemies = state.enemies + saved.followers.mapNotNull { spawnSoldier(it, near = player.position, home = null) }
    }

    private fun spawnSoldier(unitId: String, near: WorldPoint, home: WorldPoint?): EnemyInstance? {
        val unit = content.strategyBook.unit(unitId) ?: return null
        val body = director.definition(unit.actorId) ?: return null
        val spot = director.grounded(near.translated(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f, 0f)) ?: near
        return director.instantiate(body, spot, player.level, random).copy(squadId = UNIT_SQUAD + unitId, home = home)
    }

    /**
     * A follower stuck under a ledge the player climbed, or left far behind,
     * catches up: it reappears beside the player. Every ARPG with followers
     * does this, because a follower stuck on a rock is not a follower.
     */
    private fun rally(deltaSeconds: Float) {
        if (realm.order != FollowerOrder.FOLLOW && realm.order != FollowerOrder.FIGHT) return lagging.clear()
        val present = followers
        lagging.keys.retainAll(present.map { it.instanceId }.toSet())
        state.enemies = state.enemies.map { enemy ->
            if (enemy !in present) return@map enemy
            val distance = enemy.position.horizontalDistanceTo(player.position)
            val stuck = distance > RALLY_NEAR && enemy.state != EnemyState.ATTACKING
            lagging[enemy.instanceId] = if (stuck) (lagging[enemy.instanceId] ?: 0f) + deltaSeconds else 0f
            if (distance > RALLY_FAR || (lagging[enemy.instanceId] ?: 0f) > RALLY_AFTER) {
                lagging[enemy.instanceId] = 0f
                director.grounded(player.position.translated(-1f, -1f, 0f))?.let { enemy.copy(position = it) } ?: enemy
            } else {
                enemy
            }
        }
    }

    /** Followers sent home go back into the garrison when they arrive. */
    private fun homeComing() {
        if (realm.order != FollowerOrder.RETURN) return
        val home = realm.returnPoint(player.position) ?: return
        val arrived = followers.filter { it.position.horizontalDistanceTo(home) <= ARRIVED_HOME }
        if (arrived.isEmpty()) return
        realm.garrison(arrived.mapNotNull { it.squadId?.removePrefix(UNIT_SQUAD) }, home)
        state.enemies = state.enemies - arrived.toSet()
    }

    /**
     * A raid the player is there to fight: raiders from a hostile faction --
     * or the wilds, when no faction wants the land -- close in from outside
     * the walls, and the garrison turns out to meet them at the square.
     */
    private fun raid(outpost: Outpost, attackers: Int) {
        val raiders = raiderDefinitions()
        if (raiders.isEmpty()) return
        val squad = RAID_SQUAD + outpost.id
        val wave = List(attackers) { i ->
            val angle = i * 2 * Math.PI / attackers + random.nextFloat()
            val at = WorldPoint(
                outpost.centerX + (cos(angle) * (outpost.radius + RAID_DISTANCE)).toFloat(),
                outpost.centerY + (sin(angle) * (outpost.radius + RAID_DISTANCE)).toFloat(),
                player.position.z,
            )
            director.grounded(at)?.let { spot -> director.instantiate(raiders[i % raiders.size], spot, player.level, random).copy(squadId = squad, isLeader = i == 0) }
        }.filterNotNull()
        val square = WorldPoint(outpost.centerX + 0.5f, outpost.centerY + 0.5f, player.position.z)
        val (turnedOut, _) = realm.muster(player.copy(position = square), RealmSystem.MAX_RAIDERS)
        val defenders = turnedOut.mapNotNull { spawnSoldier(it, near = square, home = square) }
        state.enemies = state.enemies + wave + defenders
        // Raiders are hostile to the player whatever their faction thinks: they came to burn it.
        provoked += wave.map { it.instanceId }
    }

    private fun raiderDefinitions() = content.enemies.filter { enemy ->
        enemy.factionId != null && enemy.factionId != Factions.PLAYER && content.factionBook.stanceToPlayer(enemy.factionId, player.reputation) == Stance.HOSTILE
    }.ifEmpty { content.enemies.filter { it.factionId == null && it.spawnWeight > 0 } }

    private fun liberate(town: SettlementPlan) {
        realm.adopt(town)
        cues.townFreed(town.name, player.position)
        town.factionId?.let { owner ->
            // Freeing a town from a faction is a blow against it and a gift to its enemies.
            state.player = player.copy(reputation = player.reputation.adjusted(owner, -LIBERATION_STANDING, content.factionBook))
        }
        state.pending += CombatEvent.TownLiberated(town)
    }

    private companion object {
        /** How far away a town's people muster, in blocks. */
        const val TOWN_SIGHT = 40
        const val LIBERATION_STANDING = 25
        const val UNIT_SQUAD = "unit:"
        const val RAID_SQUAD = "raid:"
        const val RAID_DISTANCE = 8
        const val ARRIVED_HOME = 3f
        const val RALLY_NEAR = 4.5f
        const val RALLY_FAR = 16f
        const val RALLY_AFTER = 2f
    }
}
