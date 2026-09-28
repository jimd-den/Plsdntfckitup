package com.stratum.plugins.schema

import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.AffixTier
import com.stratum.core.domain.item.BaseTier
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemBase
import com.stratum.core.domain.item.ItemNamePool
import com.stratum.core.domain.item.ItemSetDefinition
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.ModifierRange
import com.stratum.core.domain.item.SetBonus
import com.stratum.core.domain.item.UniqueDefinition
import com.stratum.core.domain.item.WeaponBase
import com.stratum.core.domain.item.WeaponProfile
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import kotlinx.serialization.Serializable

// Gear: bases, the affixes and inserts that go on them, uniques, sets, and
// the words rare items are named from.

private fun slotOf(name: String, field: String): ItemSlot =
    ItemSlot.parse(name) ?: throw ImportException("$field: '$name' is not one of ${ItemSlot.entries.joinToString { it.name.lowercase() }}")

private fun flagsOf(names: List<String>, owner: String): Set<BuildFlag> = names.mapTo(LinkedHashSet()) { SchemaValues.enum<BuildFlag>(it, "$owner flag") }

private fun namesOf(flags: Set<BuildFlag>): List<String> = flags.map(SchemaValues::name)

/**
 * A rolled number: `{ "stat": "max_health", "kind": "flat", "min": 10, "max": 25 }`,
 * or `"value"` for one that is always the same. `kind` defaults to increased,
 * as every modifier in a pack does.
 */
@Serializable
internal data class ModifierRangeSchema(
    val stat: String,
    val kind: String = SchemaValues.name(ModifierKind.INCREASED),
    val min: Float? = null,
    val max: Float? = null,
    val value: Float? = null,
    val damageType: String? = null,
) {
    fun toDomain(owner: String): ModifierRange {
        val low = min ?: value ?: throw ImportException("$owner: a modifier needs a min and max, or a value")
        return ModifierRange(
            SchemaValues.enum<Stat>(stat, "$owner stat"), SchemaValues.enum<ModifierKind>(kind, "$owner modifier kind"),
            low, max ?: low, damageType,
        )
    }

    companion object {
        fun of(r: ModifierRange) = ModifierRangeSchema(
            SchemaValues.name(r.stat), SchemaValues.name(r.kind),
            min = r.min.takeIf { r.min != r.max }, max = r.max.takeIf { r.min != r.max }, value = r.min.takeIf { r.min == r.max },
            damageType = r.damageTypeId,
        )
    }
}

/** A base's flat defence: `{ "stat": "armour", "value": 30 }`. */
@Serializable
internal data class DefenceSchema(val stat: String, val value: Float, val damageType: String? = null) {
    fun toDomain(owner: String) = StatModifier(SchemaValues.enum<Stat>(stat, "$owner defence stat"), ModifierKind.FLAT, value, damageType)

    companion object {
        fun of(m: StatModifier) = DefenceSchema(SchemaValues.name(m.stat), m.value, m.damageTypeId)
    }
}

private val PROFILE = WeaponProfile(damageTypeId = "")

/**
 * Any gear. A base with a `damageType` is a weapon and reads the weapon
 * fields; anything else is worn for its defences and implicits.
 */
@Serializable
internal data class ItemBaseSchema(
    val id: String,
    val name: String,
    val slot: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val defences: List<DefenceSchema> = emptyList(),
    val implicits: List<ModifierRangeSchema> = emptyList(),
    val damageType: String? = null,
    val minDamage: Int = PROFILE.minDamage,
    val maxDamage: Int = PROFILE.maxDamage,
    val attackSpeed: Float = PROFILE.attackSpeed,
    val attackRange: Int = PROFILE.attackRange,
    val toolTier: Int = PROFILE.toolTier,
    val twoHanded: Boolean = PROFILE.twoHanded,
    val requiredLevel: Int = 1,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    val glyph: String? = null,
    val grows: Boolean = true,
) {
    fun toDomain(): ItemBase {
        val owner = "item base '$id'"
        val kind = slotOf(slot, "$owner slot")
        return ItemBase(
            id, name, kind, description, tags.toSet(), defences.map { it.toDomain(owner) }, implicits.map { it.toDomain(owner) },
            damageType?.let { WeaponProfile(it, minDamage, maxDamage, attackSpeed, attackRange, toolTier, twoHanded) },
            requiredLevel, minItemLevel, weight, glyph ?: kind.glyph, grows,
        )
    }

    companion object {
        fun of(b: ItemBase): ItemBaseSchema {
            val w = b.weapon ?: PROFILE
            return ItemBaseSchema(
                b.id, b.name, SchemaValues.name(b.slot), b.description, b.tags.toList(), b.defences.map(DefenceSchema::of),
                b.implicits.map(ModifierRangeSchema::of), b.weapon?.damageTypeId, w.minDamage, w.maxDamage, w.attackSpeed,
                w.attackRange, w.toolTier, w.twoHanded, b.requiredLevel, b.minItemLevel, b.weight, b.glyph.takeIf { it != b.slot.glyph }, b.grows,
            )
        }
    }
}

private val WEAPON = WeaponBase(id = "", name = "", damageTypeId = "")

/** The short way to write a weapon, and the only way before other gear existed. */
@Serializable
internal data class WeaponSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val slot: String = SchemaValues.name(WEAPON.slot),
    val minDamage: Int = WEAPON.minDamage,
    val maxDamage: Int = WEAPON.maxDamage,
    val attackSpeed: Float = WEAPON.attackSpeed,
    val attackRange: Int = WEAPON.attackRange,
    val damageType: String,
    val toolTier: Int = WEAPON.toolTier,
    val armour: Int = WEAPON.armour,
    val glyph: String = WEAPON.glyph,
    val minItemLevel: Int = WEAPON.minItemLevel,
    val weight: Int = WEAPON.weight,
    val twoHanded: Boolean = WEAPON.twoHanded,
    val tags: List<String> = emptyList(),
    val requiredLevel: Int = WEAPON.requiredLevel,
) {
    fun toDomain() = WeaponBase(
        id, name, description, slotOf(slot, "weapon '$id' slot"), minDamage, maxDamage, attackSpeed,
        attackRange, damageType, toolTier, armour, glyph, minItemLevel, weight, twoHanded, tags.toSet(), requiredLevel,
    )

    companion object {
        fun of(w: WeaponBase) = WeaponSchema(
            w.id, w.name, w.description, SchemaValues.name(w.slot), w.minDamage, w.maxDamage, w.attackSpeed, w.attackRange,
            w.damageTypeId, w.toolTier, w.armour, w.glyph, w.minItemLevel, w.weight, w.twoHanded, w.tags.toList(), w.requiredLevel,
        )
    }
}

@Serializable
internal data class AffixTierSchema(
    val modifiers: List<ModifierRangeSchema>,
    val minItemLevel: Int = 1,
    val name: String? = null,
    val weight: Int = 100,
) {
    fun toDomain(owner: String) = AffixTier(modifiers.map { it.toDomain(owner) }, minItemLevel, name, weight)

    companion object {
        fun of(t: AffixTier) = AffixTierSchema(t.modifiers.map(ModifierRangeSchema::of), t.minItemLevel, t.name, t.weight)
    }
}

/**
 * An affix, written either with `tiers`, or in one line as a single range:
 * `stat`, `min`, `max`, and `modifierKind`. A one-line affix with no
 * `modifierKind` whose stat is one of the nine the format started with --
 * attack_power, max_health, armour, crit_chance, crit_multiplier,
 * attack_speed, resistance, life_steal, mining_speed -- means what it always
 * did, so packs written before tiers existed load unchanged.
 */
@Serializable
internal data class AffixSchema(
    val id: String,
    val name: String,
    val kind: String,
    val stat: String? = null,
    val modifierKind: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val damageType: String? = null,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    val tiers: List<AffixTierSchema> = emptyList(),
    val group: String? = null,
    val tags: List<String> = emptyList(),
    val slots: List<String> = emptyList(),
    val local: Boolean = false,
) {
    fun toDomain(): AffixDefinition {
        val owner = "affix '$id'"
        val affixKind = SchemaValues.enum<AffixKind>(kind, "$owner kind")
        val tiers = tiers.map { it.toDomain(owner) }.ifEmpty { listOf(AffixTier(listOf(singleRange(owner)), minItemLevel)) }
        return AffixDefinition(
            id, name, affixKind, tiers, group ?: id, tags.toSet(), slots.mapTo(LinkedHashSet()) { slotOf(it, "$owner slot") }, local, weight,
        )
    }

    private fun singleRange(owner: String): ModifierRange {
        val statName = stat ?: throw ImportException("$owner: give it tiers, or a stat with min and max")
        val low = min ?: throw ImportException("$owner: min is missing")
        val legacy = AffixStat.entries.firstOrNull { it.name.equals(statName.trim(), ignoreCase = true) }
        if (modifierKind == null && legacy != null) return legacy.range(low, max ?: low, damageType)
        return ModifierRange(
            SchemaValues.enum<Stat>(statName, "$owner stat"),
            SchemaValues.enum<ModifierKind>(modifierKind ?: SchemaValues.name(ModifierKind.INCREASED), "$owner modifier kind"),
            low, max ?: low, damageType,
        )
    }

    companion object {
        fun of(a: AffixDefinition): AffixSchema {
            val group = a.group.takeIf { it != a.id }
            val slots = a.slots.map(SchemaValues::name)
            val tier = a.tiers.singleOrNull()
            val range = tier?.modifiers?.singleOrNull()
            return if (tier != null && range != null && tier.name == null && tier.weight == 100) {
                AffixSchema(
                    a.id, a.name, SchemaValues.name(a.kind), SchemaValues.name(range.stat), SchemaValues.name(range.kind), range.min, range.max,
                    range.damageTypeId, tier.minItemLevel, a.weight, group = group, tags = a.tags.toList(), slots = slots, local = a.local,
                )
            } else {
                AffixSchema(
                    a.id, a.name, SchemaValues.name(a.kind), weight = a.weight, tiers = a.tiers.map(AffixTierSchema::of),
                    group = group, tags = a.tags.toList(), slots = slots, local = a.local,
                )
            }
        }
    }
}

private val INSERT = InsertDefinition(id = "", name = "")

/**
 * A socketable. Current packs write `modifiers` and `convertsTo`; the older
 * `stat` and `value`, with `damageType` and `convertsDamageType`, still read.
 */
@Serializable
internal data class InsertSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val modifiers: List<ModifierSchema> = emptyList(),
    val convertsTo: String? = null,
    val stat: String? = null,
    val value: Float = 0f,
    val damageType: String? = null,
    val convertsDamageType: Boolean = false,
    val tier: Int = INSERT.tier,
    val glyph: String = INSERT.glyph,
    val color: String = SchemaValues.color(INSERT.color),
    val minItemLevel: Int = INSERT.minItemLevel,
    val weight: Int = INSERT.weight,
) {
    fun toDomain(): InsertDefinition {
        val owner = "insert '$id'"
        val legacy = stat?.let { SchemaValues.enum<AffixStat>(it, "$owner stat").modifier(value, damageType) }
        return InsertDefinition(
            id, name, description, modifiers.map { it.toDomain(owner) } + listOfNotNull(legacy),
            convertsTo ?: damageType.takeIf { convertsDamageType }, tier, glyph, SchemaValues.color(color, "$owner color"), minItemLevel, weight,
        )
    }

    companion object {
        fun of(i: InsertDefinition) = InsertSchema(
            i.id, i.name, i.description, i.modifiers.map(ModifierSchema::of), i.convertsToDamageTypeId, tier = i.tier, glyph = i.glyph,
            color = SchemaValues.color(i.color), minItemLevel = i.minItemLevel, weight = i.weight,
        )
    }
}

@Serializable
internal data class UniqueSchema(
    val id: String,
    val name: String,
    val base: String,
    val modifiers: List<ModifierRangeSchema> = emptyList(),
    val localModifiers: List<ModifierRangeSchema> = emptyList(),
    val flags: List<String> = emptyList(),
    val flavour: String = "",
    val set: String? = null,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
    val sockets: Int? = null,
    val glyph: String? = null,
) {
    fun toDomain(): UniqueDefinition {
        val owner = "unique '$id'"
        return UniqueDefinition(
            id, name, base, modifiers.map { it.toDomain(owner) }, localModifiers.map { it.toDomain(owner) }, flagsOf(flags, owner),
            flavour, set, minItemLevel, weight, sockets, glyph,
        )
    }

    companion object {
        fun of(u: UniqueDefinition) = UniqueSchema(
            u.id, u.name, u.baseId, u.modifiers.map(ModifierRangeSchema::of), u.localModifiers.map(ModifierRangeSchema::of),
            namesOf(u.flags), u.flavour, u.setId, u.minItemLevel, u.weight, u.sockets, u.glyph,
        )
    }
}

@Serializable
internal data class SetBonusSchema(val pieces: Int, val modifiers: List<ModifierSchema> = emptyList(), val flags: List<String> = emptyList()) {
    fun toDomain(owner: String) = SetBonus(pieces, modifiers.map { it.toDomain(owner) }, flagsOf(flags, owner))

    companion object {
        fun of(b: SetBonus) = SetBonusSchema(b.pieces, b.modifiers.map(ModifierSchema::of), namesOf(b.flags))
    }
}

@Serializable
internal data class ItemSetSchema(val id: String, val name: String, val description: String = "", val bonuses: List<SetBonusSchema> = emptyList()) {
    fun toDomain() = ItemSetDefinition(id, name, bonuses.map { it.toDomain("set '$id'") }, description)

    companion object {
        fun of(s: ItemSetDefinition) = ItemSetSchema(s.id, s.name, s.description, s.bonuses.map(SetBonusSchema::of))
    }
}

@Serializable
internal data class NamePoolSchema(
    val id: String,
    val first: List<String> = emptyList(),
    val second: List<String> = emptyList(),
    val slots: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    fun toDomain() = ItemNamePool(id, first, second, slots.mapTo(LinkedHashSet()) { slotOf(it, "name pool '$id' slot") }, tags.toSet())

    companion object {
        fun of(p: ItemNamePool) = NamePoolSchema(p.id, p.first, p.second, p.slots.map(SchemaValues::name), p.tags.toList())
    }
}

@Serializable
internal data class BaseTierSchema(val name: String, val levelsAbove: Int, val bonus: Float = 1.1f) {
    fun toDomain() = BaseTier(name, levelsAbove, bonus)

    companion object {
        fun of(t: BaseTier) = BaseTierSchema(t.name, t.levelsAbove, t.bonus)
    }
}
