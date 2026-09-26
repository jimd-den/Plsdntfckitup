package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/**
 * The people who hold each town: guards in a friendly one, the enemy in a
 * stronghold.
 *
 * A garrison musters when the player comes near, standing at its posts --
 * the square, the doors -- and walks back to them when there is nothing to
 * fight. Killing every member of a hostile garrison liberates the town, the
 * Diablo IV stronghold loop: the enemy's fortress becomes a place the player
 * can use. A garrison that is merely left behind is not beaten; it musters
 * again, with whoever is still alive, when the player returns.
 */
internal class Garrisons(private val director: EnemyDirector) {

    /** Members still to be killed, by town. Set when a garrison first musters. */
    private val remaining = HashMap<String, Int>()
    private val liberated = HashSet<String>()

    val liberatedIds: Set<String> get() = liberated

    fun isLiberated(planId: String): Boolean = planId in liberated

    /** Every town near the player whose garrison is not already standing gets one. */
    fun muster(towns: List<SettlementPlan>, present: List<EnemyInstance>, playerLevel: Int, random: Random): List<EnemyInstance> {
        val standing = present.mapNotNullTo(HashSet()) { it.squadId }
        return towns.filter { it.recipe.garrison.isNotEmpty() && it.id !in liberated && it.id !in standing }
            .flatMap { town -> muster(town, playerLevel, random) }
    }

    /** Notes who fell; returns the towns whose garrison is now gone entirely. */
    fun onSlain(slain: List<EnemyInstance>, towns: List<SettlementPlan>): List<SettlementPlan> {
        slain.mapNotNull { it.squadId }.filter { it in remaining }.forEach { remaining[it] = remaining.getValue(it) - 1 }
        val freed = remaining.filter { (_, left) -> left <= 0 }.keys.filter { it !in liberated }
        liberated += freed
        freed.forEach(remaining::remove)
        return towns.filter { it.id in freed }
    }

    fun restore(ids: Set<String>) {
        liberated += ids
    }

    private fun muster(town: SettlementPlan, playerLevel: Int, random: Random): List<EnemyInstance> {
        val roster = town.recipe.garrison.flatMap { member -> List(member.count) { member.enemyId } }
        val left = remaining.getOrPut(town.id) { roster.size }
        val posts = posts(town)
        return roster.take(left).mapIndexedNotNull { i, enemyId ->
            val definition = director.definition(enemyId) ?: return@mapIndexedNotNull null
            val post = director.grounded(posts[i % posts.size]) ?: return@mapIndexedNotNull null
            director.instantiate(definition, post, playerLevel, random)
                .copy(squadId = town.id, isLeader = i == 0, home = post)
        }
    }

    /** Where guards stand: one step out from each door, then round the square. */
    private fun posts(town: SettlementPlan): List<WorldPoint> {
        val doors = town.buildings.map { b -> WorldPoint(b.doorX + b.door.dx * 1.5f + 0.5f, b.doorY + b.door.dy * 1.5f + 0.5f, town.groundZ + 1f) }
        val square = List(SQUARE_POSTS) { i ->
            val angle = i * 2 * Math.PI / SQUARE_POSTS
            WorldPoint(town.centerX + 0.5f + (kotlin.math.cos(angle) * SQUARE_RADIUS).toFloat(), town.centerY + 0.5f + (kotlin.math.sin(angle) * SQUARE_RADIUS).toFloat(), town.groundZ + 1f)
        }
        return (square + doors).ifEmpty { listOf(WorldPoint(town.centerX + 0.5f, town.centerY + 0.5f, town.groundZ + 1f)) }
    }

    private companion object {
        const val SQUARE_POSTS = 6
        const val SQUARE_RADIUS = 3.0
    }
}
