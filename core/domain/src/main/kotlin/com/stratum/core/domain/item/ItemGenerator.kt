package com.stratum.core.domain.item

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Makes items: a base, a rarity, affixes by tier, a name -- or, now and then,
 * a unique.
 *
 * Takes its [Random] rather than owning one, so a drop can be replayed from a
 * seed and a test can assert on an exact item instead of a distribution.
 * Drops and crafting both roll through here, so a crafted affix is drawn
 * from exactly the pool, weights and item-level gates a dropped one is.
 */
class ItemGenerator(val catalogue: ItemCatalogue) {

    /**
     * Rolls one item, or null when nothing is eligible at this level. Item
     * level gates the base, the affix tiers and the uniques, which is what
     * makes descending worth doing rather than farming the surface.
     */
    fun roll(
        itemLevel: Int,
        random: Random,
        /** Improves the rarity roll: elites and bosses pass a bonus here. */
        rarityBonus: Float = 0f,
        /** Keeps the roll to one kind of item, for a drop that should be a ring. */
        slot: ItemSlot? = null,
        /** The least a rolled item may be: a chest is never a common. Uniques are unaffected. */
        floor: ItemRarity = ItemRarity.COMMON,
    ): ItemInstance? {
        val bases = eligibleBases(itemLevel, slot)
        if (bases.isEmpty()) return null

        val uniques = catalogue.uniques.filter { unique ->
            unique.minItemLevel <= itemLevel && catalogue.base(unique.baseId)?.let { slot == null || it.slot == slot } == true
        }
        // Only drawn when there is a unique to find, so a pack without any
        // rolls exactly the sequence it always did.
        if (uniques.isNotEmpty() && random.nextFloat() < UNIQUE_CHANCE * (1f + rarityBonus.coerceIn(0f, 1f) * UNIQUE_BONUS_SHARE)) {
            pickWeighted(uniques, random) { it.weight }?.let { unique -> return unique(unique, itemLevel, random) }
        }

        val base = pickWeighted(bases, random) { it.weight } ?: return null
        return craft(base, itemLevel, rollRarity(random, rarityBonus, floor), random)
    }

    /**
     * The bases that can drop at [itemLevel]. Of each family only its two
     * deepest eligible rungs, so a deep drop is a deep base rather than the
     * level-one blade most of the time.
     */
    fun eligibleBases(itemLevel: Int, slot: ItemSlot? = null): List<ItemBase> =
        catalogue.bases
            .filter { it.minItemLevel <= itemLevel && (slot == null || it.slot == slot) }
            .groupBy { it.family ?: it.id }
            .values
            .flatMap { family -> family.sortedByDescending { it.minItemLevel }.take(RUNGS_IN_PLAY) }

    /**
     * A specific item from a named base, for starting gear and for any drop
     * that is supposed to be a particular thing rather than a roll.
     */
    fun craft(base: ItemBase, itemLevel: Int, rarity: ItemRarity, random: Random): ItemInstance {
        require(rarity.isRolled) { "A ${rarity.name.lowercase()} item is made from its definition, not crafted from a base" }
        val blank = blank(base, itemLevel, rarity, random)
        val affixes = rollAffixes(blank, rarity.affixCount, random)
        return blank.copy(
            affixes = affixes,
            name = name(blank, rarity, affixes, random),
            // Sockets drop empty. The item is the frame; what it does is the
            // player's to decide, and that decision should survive the drop.
            sockets = SocketSet.of(SocketSet.rolledFor(rarity)),
        )
    }

    /** A unique or set piece at [itemLevel], or null when its base is not loaded. */
    fun unique(unique: UniqueDefinition, itemLevel: Int, random: Random): ItemInstance? {
        val base = catalogue.base(unique.baseId) ?: return null
        val rarity = if (unique.setId != null) ItemRarity.SET else ItemRarity.UNIQUE
        val rolls = listOfNotNull(
            uniqueRoll(unique, local = false, random),
            uniqueRoll(unique, local = true, random),
        )
        return blank(base, maxOf(itemLevel, unique.minItemLevel), rarity, random).copy(
            name = unique.name,
            affixes = rolls,
            sockets = SocketSet.of(unique.sockets ?: SocketSet.rolledFor(rarity)),
            uniqueId = unique.id,
            setId = unique.setId,
            setBonuses = unique.setId?.let(catalogue::set)?.bonuses.orEmpty(),
            flags = unique.flags,
            flavour = unique.flavour,
            glyph = unique.glyph ?: base.glyph,
        )
    }

    private fun uniqueRoll(unique: UniqueDefinition, local: Boolean, random: Random): AffixRoll? {
        val ranges = if (local) unique.localModifiers else unique.modifiers
        if (ranges.isEmpty()) return null
        return AffixRoll(unique.id, unique.name, AffixKind.UNIQUE, ranges.map { it.roll(random.nextFloat()) }, group = unique.id, local = local)
    }

    /** The base's numbers at [itemLevel], with its implicits rolled and nothing else. */
    private fun blank(base: ItemBase, itemLevel: Int, rarity: ItemRarity, random: Random): ItemInstance {
        val scale = BaseFamilies.scale(base, itemLevel)
        val weapon = base.weapon
        return ItemInstance(
            instanceId = "item_${random.nextLong().toULong().toString(16)}",
            baseId = base.id,
            name = base.name,
            rarity = rarity,
            itemLevel = itemLevel,
            slot = base.slot,
            damageTypeId = weapon?.damageTypeId,
            minDamage = weapon?.let { (it.minDamage * scale).roundToInt() } ?: 0,
            maxDamage = weapon?.let { (it.maxDamage * scale).roundToInt() } ?: 0,
            baseAttackSpeed = weapon?.attackSpeed ?: 0f,
            attackRange = weapon?.attackRange ?: 0,
            toolTier = weapon?.toolTier ?: 0,
            defences = base.defences.map { it.copy(value = BaseFamilies.grownValue(it.value, scale, it.stat.isPercent)) },
            glyph = base.glyph,
            implicits = base.implicits.map { it.roll(random.nextFloat()) },
            baseName = base.name,
            tags = base.allTags,
            twoHanded = base.twoHanded,
            requiredLevel = base.requiredLevel,
        )
    }

    /**
     * [count] new affixes for [item], none of a group it already carries.
     *
     * Prefixes and suffixes are each held to half of what [rarity] carries,
     * rounded up, so a rare reads as a balanced thing rather than six prefixes
     * -- unless one kind has run out, when the other fills the space rather
     * than leaving the item short.
     */
    fun rollAffixes(item: ItemInstance, count: Int, random: Random, rarity: ItemRarity = item.rarity): List<AffixRoll> {
        if (count <= 0) return emptyList()
        val taken = item.affixes.mapTo(HashSet()) { it.group }
        val pool = catalogue.affixes
            .filter { it.minItemLevel <= item.itemLevel && it.group !in taken && it.fits(item.slot, item.tags) }
            .toMutableList()
        val cap = (rarity.affixCount + 1) / 2
        val chosen = mutableListOf<AffixRoll>()

        repeat(count) {
            val held = item.affixes + chosen
            val balanced = pool.filter { candidate -> held.count { it.kind == candidate.kind } < cap }.ifEmpty { pool }
            val definition = pickWeighted(balanced, random) { it.weight } ?: return chosen
            // An item never rolls two of one group; two "+health" lines read as
            // a bug even when the maths works out.
            pool.removeAll { it.group == definition.group }
            val tier = pickWeighted(definition.tiersAt(item.itemLevel), random) { definition.tier(it)!!.weight } ?: return@repeat
            chosen += definition.roll(tier) { random.nextFloat() }
        }
        return chosen
    }

    /**
     * The same affix with its values rolled again within its tier, or
     * unchanged when its definition is no longer loaded. A unique's
     * modifiers reroll within the unique's own ranges.
     */
    fun rerolled(roll: AffixRoll, random: Random): AffixRoll {
        if (roll.kind == AffixKind.UNIQUE) {
            val unique = catalogue.unique(roll.definitionId) ?: return roll
            val ranges = if (roll.local) unique.localModifiers else unique.modifiers
            if (ranges.size != roll.modifiers.size) return roll
            return roll.copy(modifiers = ranges.map { it.roll(random.nextFloat()) })
        }
        val tier = catalogue.affix(roll.definitionId)?.tier(roll.tier) ?: return roll
        return roll.copy(modifiers = tier.modifiers.map { it.roll(random.nextFloat()) })
    }

    /** [item]'s name for a new set of affixes and a new rarity. */
    fun name(item: ItemInstance, rarity: ItemRarity, affixes: List<AffixRoll>, random: Random): String {
        if (item.isFixed) return item.name
        val baseName = item.baseName.ifEmpty { catalogue.base(item.baseId)?.name ?: item.name }
        if (rarity.isNamed) ItemNamer.rareName(catalogue.namePools, item.slot, item.tags, random)?.let { return it }
        return ItemNamer.compose(baseName, affixes)
    }

    /**
     * Picks a tier by weight. Common is overwhelmingly likely by design: the
     * loop only works if a relic is rare enough to be worth stopping for.
     * Tiers below [floor] are struck from the table, so the tiers above keep
     * their proportions to each other.
     */
    fun rollRarity(random: Random, rarityBonus: Float = 0f, floor: ItemRarity = ItemRarity.COMMON): ItemRarity {
        val boosted = ItemRarity.ordered.associateWith { rarity ->
            when {
                rarity < floor -> 0
                // The bonus takes weight away from common rather than inflating
                // every tier, so a bonus of 1 guarantees something better.
                rarity == ItemRarity.COMMON -> (rarity.weight * (1f - rarityBonus.coerceIn(0f, 1f))).roundToInt()
                else -> rarity.weight
            }
        }
        val total = boosted.values.sum().coerceAtLeast(1)
        var roll = random.nextInt(total)
        for (rarity in ItemRarity.ordered) {
            roll -= boosted.getValue(rarity)
            if (roll < 0) return rarity
        }
        return ItemRarity.ordered.firstOrNull { it >= floor } ?: ItemRarity.ordered.last()
    }

    companion object {
        /** How often a drop that could be a unique is one. */
        const val UNIQUE_CHANCE = 0.02f

        /** How much a full rarity bonus multiplies that chance by, on top of one. */
        const val UNIQUE_BONUS_SHARE = 3f

        /** How many of a family's deepest rungs drop at once. */
        const val RUNGS_IN_PLAY = 2

        /** Picks by weight; a pool whose weights are all zero picks evenly. */
        fun <T> pickWeighted(items: List<T>, random: Random, weight: (T) -> Int): T? {
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
    }
}
