package com.stratum.core.domain.item

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier

/** The broad families gear comes in, for affixes and tooltips that care about the family rather than the slot. */
enum class ItemCategory { WEAPON, OFFHAND, ARMOUR, JEWELLERY }

/**
 * What kind of gear an item is, which decides where on the body it can go.
 * A ring is one kind that fits two places.
 */
enum class ItemSlot(val category: ItemCategory, val glyph: String) {
    WEAPON(ItemCategory.WEAPON, "⚔"),
    OFFHAND(ItemCategory.OFFHAND, "🛡"),
    HELM(ItemCategory.ARMOUR, "⛑"),
    CHEST(ItemCategory.ARMOUR, "🥋"),
    GLOVES(ItemCategory.ARMOUR, "🧤"),
    BOOTS(ItemCategory.ARMOUR, "🥾"),
    BELT(ItemCategory.ARMOUR, "🎗"),
    AMULET(ItemCategory.JEWELLERY, "📿"),
    RING(ItemCategory.JEWELLERY, "💍"),
    ;

    /** The places on the body this kind of item can be worn, in the order an empty one is filled. */
    val fits: List<EquipmentSlot>
        get() = when (this) {
            WEAPON -> listOf(EquipmentSlot.WEAPON)
            OFFHAND -> listOf(EquipmentSlot.OFFHAND)
            HELM -> listOf(EquipmentSlot.HELM)
            CHEST -> listOf(EquipmentSlot.CHEST)
            GLOVES -> listOf(EquipmentSlot.GLOVES)
            BOOTS -> listOf(EquipmentSlot.BOOTS)
            BELT -> listOf(EquipmentSlot.BELT)
            AMULET -> listOf(EquipmentSlot.AMULET)
            RING -> listOf(EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT)
        }

    companion object {
        /**
         * Reads a slot by name, case ignored, including the two names the
         * single-slot game used: its "armour" was body armour and its
         * "charm" was worn at the neck.
         */
        fun parse(name: String): ItemSlot? {
            val wanted = name.trim()
            entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
            return when (wanted.lowercase()) {
                "armour", "armor", "body" -> CHEST
                "charm", "neck" -> AMULET
                "shield", "focus" -> OFFHAND
                "main_hand", "mainhand" -> WEAPON
                else -> null
            }
        }
    }
}

/** How a weapon hits. Only weapons have one; its absence is what makes armour armour. */
data class WeaponProfile(
    val damageTypeId: String,
    val minDamage: Int = 8,
    val maxDamage: Int = 14,
    val attackSpeed: Float = 1.2f,
    val attackRange: Int = 1,
    /** Mining tier this weapon grants, so a pick is a weapon and a weapon is a pick. */
    val toolTier: Int = 1,
    /** Takes both hands, so nothing can be held in the off hand while it is. */
    val twoHanded: Boolean = false,
) {
    init {
        require(maxDamage >= minDamage) { "A weapon's damage range runs backwards: $minDamage..$maxDamage" }
    }
}

/**
 * Any kind of gear a pack defines: a weapon, a shield, a helm, a ring.
 *
 * A base is the frame an item rolls on. It fixes what cannot change -- where
 * it is worn, how it hits, the defence it gives before anything rolls -- and
 * its [tags] decide which affixes can roll on it, so a pack can keep
 * spell affixes off an axe without the engine knowing what either is.
 */
data class ItemBase(
    val id: String,
    val name: String,
    val slot: ItemSlot,
    val description: String = "",
    /** Pack vocabulary such as "sword", "heavy" or "caster", read only by affix eligibility. */
    val tags: Set<String> = emptySet(),
    /**
     * What it gives before it rolls anything: flat amounts of any stat,
     * armour on a chest, health on a belt, resistance on a charm. Data rather
     * than fixed fields, so a pack can build evasion or ward bases the moment
     * the stat exists without anything here changing.
     */
    val defences: List<StatModifier> = emptyList(),
    /** Rolled once when the item drops and never rerolled by crafting, the way a base's nature should be. */
    val implicits: List<ModifierRange> = emptyList(),
    val weapon: WeaponProfile? = null,
    /** The character level needed to wear it. */
    val requiredLevel: Int = 1,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    val glyph: String = slot.glyph,
    /**
     * Whether the generator may grow stronger versions of this base for deeper
     * item levels. Off for a base that is meant to stay exactly what it is.
     */
    val grows: Boolean = true,
    /** The hand-authored base this one was grown from, or null for one a pack wrote. */
    val family: String? = null,
) {
    init {
        require(slot != ItemSlot.WEAPON || weapon != null) { "Base '$id' is a weapon with no damage" }
        require(defences.all { it.kind == ModifierKind.FLAT }) { "Base '$id': defences are flat amounts" }
    }

    val category: ItemCategory get() = slot.category

    val twoHanded: Boolean get() = weapon?.twoHanded == true

    /**
     * The tags affixes are matched against: the pack's own, plus the slot,
     * the category and the handedness, so an affix can ask for "ring" or
     * "armour" or "two_handed" without every base having to say so.
     */
    val allTags: Set<String>
        get() = tags + slot.name.lowercase() + category.name.lowercase() +
            listOfNotNull(weapon?.let { if (it.twoHanded) "two_handed" else "one_handed" })

    fun defence(stat: Stat): Float = defences.filter { it.stat == stat }.sumOf { it.value.toDouble() }.toFloat()
}

/**
 * A weapon archetype defined by a pack: the shape packs used before any other
 * gear existed, and still the shortest way to write a weapon. Every one
 * becomes an [ItemBase] through [toItemBase].
 */
data class WeaponBase(
    val id: String,
    val name: String,
    val description: String = "",
    val slot: ItemSlot = ItemSlot.WEAPON,
    val minDamage: Int = 8,
    val maxDamage: Int = 14,
    val attackSpeed: Float = 1.2f,
    val attackRange: Int = 1,
    val damageTypeId: String,
    /** Mining tier this weapon grants, so a pick is a weapon and a weapon is a pick. */
    val toolTier: Int = 1,
    val armour: Int = 0,
    /** How this weapon reads at a glance, in the world and in the bag alike. */
    val glyph: String = "⚔",
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    val twoHanded: Boolean = false,
    val tags: Set<String> = emptySet(),
    val requiredLevel: Int = 1,
) {
    fun toItemBase(): ItemBase = ItemBase(
        id = id,
        name = name,
        slot = slot,
        description = description,
        tags = tags,
        defences = if (armour != 0) listOf(StatModifier(Stat.ARMOUR, ModifierKind.FLAT, armour.toFloat())) else emptyList(),
        weapon = WeaponProfile(damageTypeId, minDamage, maxDamage, attackSpeed, attackRange, toolTier, twoHanded),
        requiredLevel = requiredLevel,
        minItemLevel = minItemLevel,
        weight = weight,
        glyph = glyph,
    )
}
