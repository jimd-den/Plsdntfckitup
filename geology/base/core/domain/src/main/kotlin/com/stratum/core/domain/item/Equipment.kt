package com.stratum.core.domain.item

import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.StatModifier

/** The places on a body gear is worn. Two for rings, which is why a ring is an [ItemSlot] and not one of these. */
enum class EquipmentSlot(val label: String) {
    WEAPON("Main hand"),
    OFFHAND("Off hand"),
    HELM("Head"),
    CHEST("Body"),
    GLOVES("Hands"),
    BOOTS("Feet"),
    BELT("Waist"),
    AMULET("Neck"),
    RING_LEFT("Left ring"),
    RING_RIGHT("Right ring"),
}

/**
 * What a character is wearing, by where it is worn.
 *
 * A value like the rest of the player: equipping returns a new one along
 * with whatever came off, so nothing is ever destroyed by a swap and the
 * caller decides where the removed pieces go.
 */
data class Equipment(val items: Map<EquipmentSlot, ItemInstance> = emptyMap()) {

    init {
        items.forEach { (slot, item) -> require(slot in item.slot.fits) { "${item.name} cannot be worn in ${slot.label}" } }
    }

    /** The result of a change: the new equipment and what had to come off to make it. */
    data class Change(val equipment: Equipment, val removed: List<ItemInstance>)

    operator fun get(slot: EquipmentSlot): ItemInstance? = items[slot]

    val weapon: ItemInstance? get() = items[EquipmentSlot.WEAPON]

    /** Everything worn, in slot order. */
    val all: List<ItemInstance> get() = EquipmentSlot.entries.mapNotNull(items::get)

    val isEmpty: Boolean get() = items.isEmpty()

    fun slotOf(instanceId: String): EquipmentSlot? = items.entries.firstOrNull { it.value.instanceId == instanceId }?.key

    fun itemById(instanceId: String): ItemInstance? = items.values.firstOrNull { it.instanceId == instanceId }

    /** Where [item] goes when no slot is named: the first free place it fits, or the first place it fits at all. */
    fun targetFor(item: ItemInstance): EquipmentSlot = item.slot.fits.firstOrNull { it !in items } ?: item.slot.fits.first()

    /**
     * Wears [item] in [into], or wherever it goes when that is null. Returns
     * null when it cannot be worn there. A two-handed weapon takes the off
     * hand's item off with it, and an off-hand item takes a two-handed weapon
     * off, so the pair is never both worn.
     */
    fun equipping(item: ItemInstance, into: EquipmentSlot? = null): Change? {
        val slot = into ?: targetFor(item)
        if (slot !in item.slot.fits) return null
        val removed = listOfNotNull(items[slot]).toMutableList()
        val next = (items - slot).toMutableMap()
        if (slot == EquipmentSlot.WEAPON && item.twoHanded) next.remove(EquipmentSlot.OFFHAND)?.let(removed::add)
        if (slot == EquipmentSlot.OFFHAND && next[EquipmentSlot.WEAPON]?.twoHanded == true) next.remove(EquipmentSlot.WEAPON)?.let(removed::add)
        next[slot] = item
        return Change(Equipment(next), removed)
    }

    /** Takes off whatever is in [slot]. */
    fun removing(slot: EquipmentSlot): Change = Change(Equipment(items - slot), listOfNotNull(items[slot]))

    /** Puts [item] in [slot] with no rules applied, or empties it; for restoring a save or setting up a test. */
    fun with(slot: EquipmentSlot, item: ItemInstance?): Equipment = Equipment(if (item == null) items - slot else items + (slot to item))

    /** The same equipment with [item] written over the worn item of the same id, or null when it is not worn. */
    fun replacing(item: ItemInstance): Equipment? = slotOf(item.instanceId)?.let { with(it, item) }

    /** How many distinct pieces of each set are worn. */
    val setPieces: Map<String, Int>
        get() = all.filter { it.setId != null }.groupBy { it.setId!! }.mapValues { (_, pieces) -> pieces.map { it.uniqueId ?: it.baseId }.distinct().size }

    /**
     * Every set bonus the worn pieces unlock. Each piece carries its set's
     * bonus table, so this needs nothing but what is worn.
     */
    val setBonuses: List<SetBonus>
        get() = setPieces.flatMap { (setId, worn) ->
            all.first { it.setId == setId }.setBonuses.filter { it.pieces <= worn }
        }

    /** Everything the gear does to its wearer, set bonuses included. */
    fun modifiers(inserts: (String) -> InsertDefinition? = { null }): List<StatModifier> =
        all.flatMap { it.modifiers(inserts) } + setBonuses.flatMap { it.modifiers }

    /** The rules the gear breaks, for combat to read. */
    val flags: Set<BuildFlag>
        get() = all.flatMapTo(HashSet()) { it.flags } + setBonuses.flatMap { it.flags }

    companion object {
        val EMPTY = Equipment()
    }
}
