package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyInstance
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.SupportDefinition
import com.stratum.core.domain.difficulty.Difficulty
import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.difficulty.WaystoneMods
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.stats.LootFind
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

/** Something a kill pays straight into the pouch, with no walking over it. */
sealed interface Valuable {
    val name: String

    data class Currency(val definition: CurrencyDefinition) : Valuable {
        override val name: String get() = definition.name
    }

    data class Support(val definition: SupportDefinition) : Valuable {
        override val name: String get() = definition.name
    }

    data class Key(val waystone: Waystone) : Valuable {
        override val name: String get() = waystone.name
    }
}

/**
 * What a slain monster leaves behind.
 *
 * Gear and inserts are rolled independently: a fight that yields no weapon can
 * still yield something to put in one, so the socket economy does not stall
 * behind the rarity table. Rank feeds every roll, so an elite is worth walking
 * over to rather than just being tougher.
 *
 * Currency, supports and waystones go straight into the pouch. On a phone,
 * stopping to tap a pebble-sized orb in a crowd is not a decision, it is a
 * chore; gear still lands on the ground, because choosing it is.
 */
internal class LootDrops(
    private val content: AssembledContent,
    private val roller: LootRoller,
    private val seaLevel: Int,
    private val difficulty: Difficulty = Difficulty.BASE,
) {
    fun gearFor(enemy: EnemyInstance, playerLevel: Int, random: Random, find: LootFind = LootFind.NONE): GroundLoot? {
        val definition = content.enemies.firstOrNull { it.id == enemy.definitionId }
        val chance = find.dropChance(BASE_DROP_CHANCE + (definition?.bonusDropChance ?: 0f) + enemy.rank.extraAffixChance)
        if (random.nextFloat() > chance) return null
        val item = roller.roll(itemLevelFor(enemy, playerLevel), random, rarityBonus = find.rarityBonus(enemy.rank.extraAffixChance)) ?: return null
        return GroundLoot(item, enemy.position)
    }

    fun insertFor(enemy: EnemyInstance, playerLevel: Int, random: Random): GroundInsert? {
        if (content.inserts.isEmpty()) return null
        if (random.nextFloat() > INSERT_DROP_CHANCE + enemy.rank.extraAffixChance) return null
        val insert: InsertDefinition = roller.rollInsert(itemLevelFor(enemy, playerLevel), random) ?: return null
        return GroundInsert(insert.id, enemy.position)
    }

    /** Currency, supports and waystones: each rolled on its own, each more likely from something ranked. */
    fun valuablesFor(enemy: EnemyInstance, playerLevel: Int, random: Random, find: LootFind = LootFind.NONE): List<Valuable> {
        val itemLevel = itemLevelFor(enemy, playerLevel)
        val bonus = enemy.rank.extraAffixChance
        return listOfNotNull(
            rolled(find.dropChance(CURRENCY_DROP_CHANCE + bonus), random) {
                pick(content.currencies.filter { it.minItemLevel <= itemLevel }, random) { it.weight }?.let(Valuable::Currency)
            },
            rolled(find.dropChance(SUPPORT_DROP_CHANCE + bonus * RANKED_SHARE), random) {
                pick(content.supports.filter { it.minItemLevel <= itemLevel }, random) { it.weight }?.let(Valuable::Support)
            },
            rolled(if (enemy.rank == EnemyRank.MINION) 0f else WAYSTONE_DROP_CHANCE + bonus * RANKED_SHARE, random) {
                waystone(random)
            },
        )
    }

    /** One tier above this world, sometimes two: the way on is always found by going. */
    private fun waystone(random: Random): Valuable? {
        if (content.waystoneMods.isEmpty()) return null
        val tier = difficulty.tier + 1 + if (random.nextFloat() < JUMP_CHANCE) 1 else 0
        return Valuable.Key(WaystoneMods.roll(tier, content.waystoneMods, random))
    }

    private inline fun rolled(chance: Float, random: Random, make: () -> Valuable?): Valuable? =
        if (random.nextFloat() < chance) make() else null

    private fun <T> pick(options: List<T>, random: Random, weight: (T) -> Int): T? {
        val total = options.sumOf { weight(it).coerceAtLeast(0) }
        if (total <= 0) return options.randomOrNull(random)
        var roll = random.nextInt(total)
        return options.firstOrNull { roll -= weight(it).coerceAtLeast(0); roll < 0 }
    }

    /** Deeper kills and harder worlds drop better gear. */
    private fun itemLevelFor(enemy: EnemyInstance, playerLevel: Int): Int =
        Progression.itemLevelFor(playerLevel + difficulty.monsterLevelBonus, (seaLevel - enemy.blockPos.z).coerceAtLeast(0))

    companion object {
        const val BASE_DROP_CHANCE = 0.35f

        /** Rolled separately from gear, so a socket always has something to fill it. */
        const val INSERT_DROP_CHANCE = 0.22f
        const val CURRENCY_DROP_CHANCE = 0.16f
        const val SUPPORT_DROP_CHANCE = 0.04f
        const val WAYSTONE_DROP_CHANCE = 0.08f

        /** How much of a rank's affix bonus the rarer drops get. */
        const val RANKED_SHARE = 0.3f
        const val JUMP_CHANCE = 0.15f
    }
}
