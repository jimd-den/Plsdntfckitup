package com.stratum.engine.world

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixRoll
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemCatalogue
import com.stratum.core.domain.item.ItemGenerator
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.WeaponBase
import kotlin.random.Random

/**
 * Rolls loot: gear of every kind through the [ItemGenerator], and the
 * inserts that go into it.
 *
 * Takes its [Random] rather than owning one, so a drop can be replayed from a
 * seed and a test can assert on an exact item instead of a distribution.
 */
class LootRoller(
    val generator: ItemGenerator,
    private val inserts: List<InsertDefinition> = emptyList(),
) {

    /** A roller over bare lists, for a pack that is only weapons and affixes. */
    constructor(
        weapons: List<WeaponBase>,
        affixes: List<AffixDefinition>,
        inserts: List<InsertDefinition> = emptyList(),
    ) : this(ItemGenerator(ItemCatalogue.of(weapons.map(WeaponBase::toItemBase), affixes)), inserts)

    /**
     * Rolls one item of any kind, or null when no base is eligible at this
     * level. Item level gates bases, affix tiers and uniques alike.
     */
    fun roll(
        itemLevel: Int,
        random: Random,
        /** Improves the rarity roll: elites and bosses pass a bonus here. */
        rarityBonus: Float = 0f,
        slot: ItemSlot? = null,
        /** The least a rolled item may be; see [ItemGenerator.roll]. */
        floor: ItemRarity = ItemRarity.COMMON,
    ): ItemInstance? = generator.roll(itemLevel, random, rarityBonus, slot, floor)

    /** A specific item from a named base, for starting gear and for drops that should be a particular thing. */
    fun craft(base: ItemBase, itemLevel: Int, rarity: ItemRarity, random: Random): ItemInstance =
        generator.craft(base, itemLevel, rarity, random)

    fun craft(base: WeaponBase, itemLevel: Int, rarity: ItemRarity, random: Random): ItemInstance =
        craft(generator.catalogue.base(base.id) ?: base.toItemBase(), itemLevel, rarity, random)

    /**
     * Rolls one insert, or null when the pack ships none this item level can
     * reach. Kept apart from [roll] because an insert is not gear with fewer
     * fields: it drops on its own schedule and stacks rather than being an instance.
     */
    fun rollInsert(itemLevel: Int, random: Random): InsertDefinition? {
        val eligible = inserts.filter { it.minItemLevel <= itemLevel }
        return ItemGenerator.pickWeighted(eligible, random) { it.weight }
    }

    fun rollRarity(random: Random, rarityBonus: Float = 0f): ItemRarity = generator.rollRarity(random, rarityBonus)

    /** [count] new affixes for [item], none of a group it already carries. */
    internal fun rollAffixes(item: ItemInstance, count: Int, random: Random, rarity: ItemRarity = item.rarity): List<AffixRoll> =
        generator.rollAffixes(item, count, random, rarity)

    internal fun rerolled(roll: AffixRoll, random: Random): AffixRoll = generator.rerolled(roll, random)

    internal fun renamed(item: ItemInstance, rarity: ItemRarity, affixes: List<AffixRoll>, random: Random): String =
        generator.name(item, rarity, affixes, random)

    companion object {
        /** A roller over everything the loaded packs define. */
        fun of(content: AssembledContent) = LootRoller(ItemGenerator(content.itemCatalogue), content.inserts)
    }
}
