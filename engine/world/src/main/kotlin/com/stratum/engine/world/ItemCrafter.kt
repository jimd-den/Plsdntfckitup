package com.stratum.engine.world

import com.stratum.core.domain.crafting.CurrencyDefinition
import com.stratum.core.domain.crafting.CurrencyEffect
import com.stratum.core.domain.crafting.StandardCrafting
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketSet
import kotlin.random.Random

/** What happened when currency met an item. */
sealed interface CraftResult {
    data class Crafted(val before: ItemInstance, val after: ItemInstance, val currency: CurrencyDefinition) : CraftResult

    /** The currency does nothing to this item, such as ascending a relic. Nothing is spent. */
    data class NoEffect(val reason: String) : CraftResult

    data object NoSuchItem : CraftResult

    data object NoSuchCurrency : CraftResult

    data object NoneHeld : CraftResult
}

/**
 * Applies crafting currency to items: the verbs of [CurrencyEffect].
 *
 * Rolls through the same [LootRoller] as drops, so a crafted affix is drawn
 * from exactly the pool, weights and item-level gates a dropped one is: there
 * is no crafting-only power to discover, only a way to aim.
 */
internal class ItemCrafter(private val roller: LootRoller) {

    /** The crafted item, or why the currency would do nothing to it. */
    fun apply(item: ItemInstance, effect: CurrencyEffect, random: Random): Result<ItemInstance> = when (effect) {
        CurrencyEffect.IMBUE -> imbue(item, random)
        CurrencyEffect.REFORGE -> reforge(item, random)
        CurrencyEffect.ASCEND -> ascend(item, random)
        CurrencyEffect.TEMPER -> temper(item, random)
        CurrencyEffect.ANNUL -> annul(item, random)
        CurrencyEffect.SOCKET -> socket(item)
        CurrencyEffect.SCOUR -> scour(item)
    }

    private fun imbue(item: ItemInstance, random: Random): Result<ItemInstance> {
        if (item.rarity != ItemRarity.COMMON) return refused("Only a common item can be imbued")
        return ok(withAffixes(item, ItemRarity.RARE, roller.rollAffixes(ItemRarity.RARE.affixCount, item.itemLevel, random)))
    }

    private fun reforge(item: ItemInstance, random: Random): Result<ItemInstance> {
        if (item.rarity == ItemRarity.COMMON) return refused("A common item has nothing to reforge")
        return ok(withAffixes(item, item.rarity, roller.rollAffixes(item.rarity.affixCount, item.itemLevel, random)))
    }

    private fun ascend(item: ItemInstance, random: Random): Result<ItemInstance> {
        val next = ItemRarity.ordered.getOrNull(item.rarity.ordinal + 1) ?: return refused("Nothing is rarer than ${item.rarity.name.lowercase()}")
        val added = roller.rollAffixes(next.affixCount - item.affixes.size, item.itemLevel, random, item.affixes.mapTo(HashSet()) { it.definitionId })
        val sockets = item.sockets.grownTo(maxOf(item.sockets.capacity, SocketSet.rolledFor(next)))
        return ok(withAffixes(item, next, item.affixes + added).copy(sockets = sockets))
    }

    private fun temper(item: ItemInstance, random: Random): Result<ItemInstance> {
        if (item.affixes.isEmpty()) return refused("There are no affixes to temper")
        return ok(item.copy(affixes = item.affixes.map { roller.rerolled(it, random) }))
    }

    private fun annul(item: ItemInstance, random: Random): Result<ItemInstance> {
        if (item.affixes.isEmpty()) return refused("There are no affixes to remove")
        val lost = item.affixes[random.nextInt(item.affixes.size)]
        return ok(withAffixes(item, item.rarity, item.affixes - lost))
    }

    private fun socket(item: ItemInstance): Result<ItemInstance> {
        if (item.sockets.capacity >= StandardCrafting.MAX_SOCKETS) return refused("It holds as many sockets as anything can")
        return ok(item.copy(sockets = item.sockets.grownTo(item.sockets.capacity + 1)))
    }

    private fun scour(item: ItemInstance): Result<ItemInstance> {
        if (item.rarity == ItemRarity.COMMON && item.affixes.isEmpty()) return refused("It is already plain")
        return ok(withAffixes(item, ItemRarity.COMMON, emptyList()))
    }

    private fun withAffixes(item: ItemInstance, rarity: ItemRarity, affixes: List<com.stratum.core.domain.item.AffixRoll>) =
        item.copy(rarity = rarity, affixes = affixes, name = roller.renamed(item, affixes))

    /** Sockets only ever grow, and what is slotted stays slotted. */
    private fun SocketSet.grownTo(capacity: Int): SocketSet =
        if (capacity <= this.capacity) this else SocketSet(capacity, filled + List(capacity - this.capacity) { null })

    private fun ok(item: ItemInstance) = Result.success(item)

    private fun refused(reason: String) = Result.failure<ItemInstance>(CraftRefused(reason))

    class CraftRefused(reason: String) : IllegalStateException(reason)
}
