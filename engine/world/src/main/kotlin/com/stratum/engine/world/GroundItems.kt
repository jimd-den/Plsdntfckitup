package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.world.WorldPoint
import kotlin.random.Random

/**
 * What lies on the ground waiting to be walked over: items, and the inserts
 * that go into them. Only placement and pickup range live here; what picking
 * something up does to the player is the session's business.
 */
internal class GroundItems {

    var loot: List<GroundLoot> = emptyList()
    var inserts: List<GroundInsert> = emptyList()

    fun drop(loot: GroundLoot) {
        this.loot = this.loot + loot
    }

    fun drop(insert: GroundInsert) {
        inserts = inserts + insert
    }

    /** Removes and returns the loot within reach of [position]. */
    fun takeLootNear(position: WorldPoint): List<GroundLoot> {
        val (reached, remaining) = loot.partition { it.position.horizontalDistanceTo(position) <= PICKUP_RADIUS }
        loot = remaining
        return reached
    }

    /** Removes and returns the inserts within reach of [position]. */
    fun takeInsertsNear(position: WorldPoint): List<GroundInsert> {
        val (reached, remaining) = inserts.partition { it.position.horizontalDistanceTo(position) <= PICKUP_RADIUS }
        inserts = remaining
        return reached
    }

    companion object {
        const val PICKUP_RADIUS = 1.6f
    }
}

/**
 * What a slain monster leaves behind.
 *
 * Gear and inserts are rolled independently: a fight that yields no weapon can
 * still yield something to put in one, so the socket economy does not stall
 * behind the rarity table. Rank feeds both rolls, so an elite is worth walking
 * over to rather than just being tougher.
 */
internal class LootDrops(
    private val content: AssembledContent,
    private val roller: LootRoller,
    private val seaLevel: Int,
) {
    fun gearFor(enemy: EnemyInstance, playerLevel: Int, random: Random): GroundLoot? {
        val definition = content.enemies.firstOrNull { it.id == enemy.definitionId }
        val chance = BASE_DROP_CHANCE + (definition?.bonusDropChance ?: 0f) + enemy.rank.extraAffixChance
        if (random.nextFloat() > chance) return null
        val item = roller.roll(itemLevelFor(enemy, playerLevel), random, rarityBonus = enemy.rank.extraAffixChance) ?: return null
        return GroundLoot(item, enemy.position)
    }

    fun insertFor(enemy: EnemyInstance, playerLevel: Int, random: Random): GroundInsert? {
        if (content.inserts.isEmpty()) return null
        if (random.nextFloat() > INSERT_DROP_CHANCE + enemy.rank.extraAffixChance) return null
        val insert: InsertDefinition = roller.rollInsert(itemLevelFor(enemy, playerLevel), random) ?: return null
        return GroundInsert(insert.id, enemy.position)
    }

    /** Deeper kills drop better gear. */
    private fun itemLevelFor(enemy: EnemyInstance, playerLevel: Int): Int =
        Progression.itemLevelFor(playerLevel, (seaLevel - enemy.blockPos.z).coerceAtLeast(0))

    companion object {
        const val BASE_DROP_CHANCE = 0.35f

        /** Rolled separately from gear, so a socket always has something to fill it. */
        const val INSERT_DROP_CHANCE = 0.22f
    }
}
