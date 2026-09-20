package com.stratum.engine.world

import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.SocketSet
import com.stratum.core.domain.item.WeaponBase
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Rolls loot: a base, a rarity, then affixes.
 *
 * Takes its [Random] rather than owning one, so a drop can be replayed from a
 * seed and a test can assert on an exact item instead of a distribution.
 */
class LootRoller(
    private val weapons: List<WeaponBase>,
    private val affixes: List<AffixDefinition>,
    private val inserts: List<InsertDefinition> = emptyList(),
) {

    /**
     * Rolls one item, or null when no base is eligible at this level. Item level
     * gates both the base and the affix pool, which is what makes descending
     * worth doing rather than farming the surface.
     */
    fun roll(
        itemLevel: Int,
        random: Random,
        /** Improves the rarity roll: elites and bosses pass a bonus here. */
        rarityBonus: Float = 0f,
    ): ItemInstance? {
        val eligible = weapons.filter { it.minItemLevel <= itemLevel }
        if (eligible.isEmpty()) return null

        val base = pickWeighted(eligible, random) { it.weight } ?: return null
        val rarity = rollRarity(random, rarityBonus)
        val rolled = rollAffixes(rarity, itemLevel, random)

        // Base damage grows with item level so a level 40 blade of the same type
        // is genuinely an upgrade rather than just carrying better affixes.
        val scale = 1f + (itemLevel - 1) * DAMAGE_PER_ITEM_LEVEL

        return ItemInstance(
            instanceId = "item_${random.nextLong().toULong().toString(16)}",
            baseId = base.id,
            name = composeName(base, rolled),
            rarity = rarity,
            itemLevel = itemLevel,
            slot = base.slot,
            damageTypeId = base.damageTypeId,
            minDamage = (base.minDamage * scale).roundToInt(),
            maxDamage = (base.maxDamage * scale).roundToInt(),
            baseAttackSpeed = base.attackSpeed,
            attackRange = base.attackRange,
            toolTier = base.toolTier,
            baseArmour = base.armour,
            affixes = rolled,
            // Sockets drop empty. The weapon is the frame; what it does is the
            // player's to decide, and that decision should survive the drop.
            sockets = SocketSet.of(SocketSet.rolledFor(rarity)),
            glyph = base.glyph,
        )
    }

    /**
     * Builds a specific item from a named base, for starting gear and for any
     * drop that is supposed to be a particular thing rather than a roll.
     */
    fun craft(
        base: WeaponBase,
        itemLevel: Int,
        rarity: ItemRarity,
        random: Random,
    ): ItemInstance {
        val rolled = rollAffixes(rarity, itemLevel, random)
        val scale = 1f + (itemLevel - 1) * DAMAGE_PER_ITEM_LEVEL
        return ItemInstance(
            instanceId = "item_${random.nextLong().toULong().toString(16)}",
            baseId = base.id,
            name = composeName(base, rolled),
            rarity = rarity,
            itemLevel = itemLevel,
            slot = base.slot,
            damageTypeId = base.damageTypeId,
            minDamage = (base.minDamage * scale).roundToInt(),
            maxDamage = (base.maxDamage * scale).roundToInt(),
            baseAttackSpeed = base.attackSpeed,
            attackRange = base.attackRange,
            toolTier = base.toolTier,
            baseArmour = base.armour,
            affixes = rolled,
            sockets = SocketSet.of(SocketSet.rolledFor(rarity)),
            glyph = base.glyph,
        )
    }

    /**
     * Rolls one insert, or null when the pack ships none this item level can
     * reach. Kept separate from [roll] because an insert is not a weapon with
     * fewer fields: it drops on its own schedule and stacks rather than being
     * an instance.
     */
    fun rollInsert(itemLevel: Int, random: Random): InsertDefinition? {
        val eligible = inserts.filter { it.minItemLevel <= itemLevel }
        if (eligible.isEmpty()) return null
        return pickWeighted(eligible, random) { it.weight }
    }

    /**
     * Picks a tier by weight. Common is overwhelmingly likely by design: the
     * loop only works if a relic is rare enough to be worth stopping for.
     */
    fun rollRarity(random: Random, rarityBonus: Float = 0f): ItemRarity {
        val boosted = ItemRarity.ordered.associateWith { rarity ->
            if (rarity == ItemRarity.COMMON) {
                // The bonus takes weight away from common rather than inflating
                // every tier, so a bonus of 1 guarantees something better.
                (rarity.weight * (1f - rarityBonus.coerceIn(0f, 1f))).roundToInt()
            } else {
                rarity.weight
            }
        }
        val total = boosted.values.sum().coerceAtLeast(1)
        var roll = random.nextInt(total)
        for (rarity in ItemRarity.ordered) {
            roll -= boosted.getValue(rarity)
            if (roll < 0) return rarity
        }
        return ItemRarity.COMMON
    }

    private fun rollAffixes(rarity: ItemRarity, itemLevel: Int, random: Random): List<AffixRoll> {
        if (rarity.affixCount == 0) return emptyList()

        val pool = affixes.filter { it.minItemLevel <= itemLevel }.toMutableList()
        val chosen = mutableListOf<AffixRoll>()

        repeat(rarity.affixCount) {
            if (pool.isEmpty()) return@repeat
            val definition = pickWeighted(pool, random) { it.weight } ?: return@repeat
            // An item never rolls the same affix twice; two "+5 attack" lines
            // read as a bug even when the maths works out.
            pool.remove(definition)
            chosen += AffixRoll(
                definitionId = definition.id,
                name = definition.name,
                kind = definition.kind,
                stat = definition.stat,
                value = definition.roll(random.nextFloat()),
                damageTypeId = definition.damageTypeId,
            )
        }
        return chosen
    }

    /** "Roped Bronze Blade of Storms": first prefix, base, first suffix. */
    private fun composeName(base: WeaponBase, affixes: List<AffixRoll>): String {
        val prefix = affixes.firstOrNull { it.kind == AffixKind.PREFIX }?.name
        val suffix = affixes.firstOrNull { it.kind == AffixKind.SUFFIX }?.name
        return buildString {
            if (prefix != null) append("$prefix ")
            append(base.name)
            if (suffix != null) append(" $suffix")
        }
    }

    private fun <T> pickWeighted(items: List<T>, random: Random, weight: (T) -> Int): T? {
        if (items.isEmpty()) return null
        val total = items.sumOf { weight(it).coerceAtLeast(0) }
        if (total <= 0) return items[random.nextInt(items.size)]
        var roll = random.nextInt(total)
        for (item in items) {
            roll -= weight(item).coerceAtLeast(0)
            if (roll < 0) return item
        }
        return items.last()
    }

    private companion object {
        const val DAMAGE_PER_ITEM_LEVEL = 0.08f
    }
}
