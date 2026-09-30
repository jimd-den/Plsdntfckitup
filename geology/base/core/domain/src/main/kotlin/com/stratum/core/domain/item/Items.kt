package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.stats.StatSheet
import kotlin.math.roundToInt

/**
 * Item tiers. Structural rather than pack data: the whole loot loop is built on
 * the idea that better items carry more affixes, and a pack redefining that
 * would be redefining the game rather than reskinning it.
 *
 * Packs control the names and colours through [RarityStyle].
 */
enum class ItemRarity(val affixCount: Int, val weight: Int, val isRolled: Boolean = true) {
    COMMON(0, 1000),
    UNCOMMON(2, 380),
    RARE(4, 90),
    EPIC(5, 18),
    RELIC(6, 3),

    /** A named item with fixed modifiers. Never rolled from the table: it drops as itself. */
    UNIQUE(0, 0, isRolled = false),

    /** A unique that belongs to a set. */
    SET(0, 0, isRolled = false),
    ;

    /** Rare enough to earn a name of its own rather than its affixes read aloud. */
    val isNamed: Boolean get() = isRolled && affixCount >= RARE.affixCount

    companion object {
        /** The tiers the rarity table rolls and currency climbs, weakest first. */
        val ordered = entries.filter { it.isRolled }

        /** Total weight, used to turn a single 0..1 roll into a tier. */
        val totalWeight = ordered.sumOf { it.weight }
    }
}

/** A pack's naming and colour for one tier. */
data class RarityStyle(
    val rarity: ItemRarity,
    val name: String,
    val color: Long,
)

/**
 * A specific item that exists in the world or in a bag.
 *
 * Holds its rolls and a copy of its base's numbers rather than its computed
 * stats, so the same item read by a stronger character still shows what it
 * actually rolled, and a pack rebalancing a base does not quietly rewrite
 * gear already found.
 */
data class ItemInstance(
    val instanceId: String,
    val baseId: String,
    val name: String,
    val rarity: ItemRarity,
    val itemLevel: Int,
    val slot: ItemSlot,
    /** What it hits with; null for anything that is not a weapon. */
    val damageTypeId: String? = null,
    val minDamage: Int = 0,
    val maxDamage: Int = 0,
    val baseAttackSpeed: Float = 0f,
    val attackRange: Int = 0,
    val toolTier: Int = 0,
    /** Its base's defences at the depth it dropped, before its own affixes. */
    val defences: List<StatModifier> = emptyList(),
    val affixes: List<AffixRoll> = emptyList(),
    val sockets: SocketSet = SocketSet.NONE,
    /** Carried from the base so a drop on the ground looks like what it is. */
    val glyph: String = slot.glyph,
    val implicits: List<StatModifier> = emptyList(),
    /** The base's name, for an item whose own name no longer says what it is: "Storm Bite", a Bronze Blade. */
    val baseName: String = "",
    val tags: Set<String> = emptySet(),
    val twoHanded: Boolean = false,
    val requiredLevel: Int = 1,
    /** The unique or set piece it is, when it is one. */
    val uniqueId: String? = null,
    val setId: String? = null,
    val flags: Set<BuildFlag> = emptySet(),
    val flavour: String = "",
    /**
     * Its set's bonuses, carried the way its base's numbers are, so a set
     * piece means the same thing in every world it is worn in.
     */
    val setBonuses: List<SetBonus> = emptyList(),
) {
    val isWeapon: Boolean get() = slot == ItemSlot.WEAPON

    /** A unique or set piece: its modifiers are its identity, so currency may temper them but never replace them. */
    val isFixed: Boolean get() = uniqueId != null || !rarity.isRolled

    private val localModifiers: List<StatModifier> get() = affixes.filter { it.local }.flatMap { it.modifiers }

    private val localSheet: StatSheet get() = StatSheet(localModifiers)

    /** Damage after its own local affixes, before anything its wearer has. */
    val localMinDamage: Int get() = localSheet.apply(Stat.DAMAGE, minDamage.toFloat()).roundToInt()

    val localMaxDamage: Int get() = localSheet.apply(Stat.DAMAGE, maxDamage.toFloat()).roundToInt()

    val averageDamage: Int get() = (localMinDamage + localMaxDamage) / 2

    /** Attacks per second after its own local affixes. */
    val attackSpeed: Float get() = if (isWeapon) localSheet.apply(Stat.ATTACK_SPEED, baseAttackSpeed) else 0f

    /** Its defences after its own local affixes: "40% increased armour" on a helm scales that helm. */
    val resolvedDefences: List<StatModifier>
        get() {
            val sheet = localSheet
            return defences.map { it.copy(value = sheet.apply(it.stat, it.value, it.damageTypeId)) }
        }

    val armour: Int get() = resolvedDefences.filter { it.stat == Stat.ARMOUR }.sumOf { it.value.toDouble() }.roundToInt()

    val socketCount: Int get() = sockets.capacity

    val hasFreeSocket: Boolean get() = sockets.hasSpace

    /**
     * Everything this item does to its wearer, as modifiers for the one
     * [StatSheet]: implicits, affixes, defences, and whatever is slotted into
     * it. Inserts are resolved through the lookup rather than stored, so a
     * pack changing an insert's numbers changes every item carrying one.
     *
     * A weapon's hit is not in here: damage and speed are the base the sheet
     * multiplies, and the wearer reads them from [averageDamage] and
     * [attackSpeed].
     */
    fun modifiers(inserts: (String) -> InsertDefinition? = { null }): List<StatModifier> =
        implicits +
            affixes.filterNot { it.local }.flatMap { it.modifiers } +
            localModifiers.filterNot(::appliesLocally) +
            resolvedDefences +
            SocketResolver.modifiersFor(sockets.insertIds.mapNotNull(inserts))

    /** A local modifier needs something of the item's own to change; one with nothing to change reaches the wearer instead. */
    private fun appliesLocally(modifier: StatModifier): Boolean =
        (isWeapon && (modifier.stat == Stat.DAMAGE || modifier.stat == Stat.ATTACK_SPEED)) ||
            defences.any { it.stat == modifier.stat && it.damageTypeId == modifier.damageTypeId }

    fun damageTypeWithSockets(inserts: (String) -> InsertDefinition?): String? =
        damageTypeId?.let { SocketResolver.damageTypeFor(it, sockets.insertIds.mapNotNull(inserts)) }

    /** The base's line in a tooltip: its hit for a weapon, its defences for anything else. */
    val baseLine: String
        get() = if (isWeapon) {
            "$localMinDamage–$localMaxDamage damage · ${"%.2f".format(attackSpeed)}/s"
        } else {
            resolvedDefences.joinToString(" · ") { it.describe() }.ifEmpty { slot.name.lowercase() }
        }
}
