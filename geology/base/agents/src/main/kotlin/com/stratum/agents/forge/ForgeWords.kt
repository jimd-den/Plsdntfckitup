package com.stratum.agents.forge

import com.stratum.core.domain.content.LoreCategory
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.stats.BuildFlag
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat

/**
 * The engine's words and the near misses models write for them. Every
 * alias here is something a model was seen, or can be expected, to write:
 * "life" for health, "spell damage" for skill damage, "fire resistance" for
 * a resistance scoped to fire.
 */
internal object ForgeWords {

    /** A stat, and the damage type it was scoped to by its own name. */
    data class StatWord(val stat: Stat, val damageTypeId: String? = null)

    private val statAliases: Map<String, Stat> = mapOf(
        "health" to Stat.MAX_HEALTH, "life" to Stat.MAX_HEALTH, "hp" to Stat.MAX_HEALTH, "max_life" to Stat.MAX_HEALTH,
        "maximum_health" to Stat.MAX_HEALTH, "maximum_life" to Stat.MAX_HEALTH, "vitality" to Stat.MAX_HEALTH,
        "attack_power" to Stat.DAMAGE, "attack_damage" to Stat.DAMAGE, "physical_damage" to Stat.DAMAGE, "weapon_damage" to Stat.DAMAGE,
        "defence" to Stat.ARMOUR, "defense" to Stat.ARMOUR, "armor" to Stat.ARMOUR,
        "crit" to Stat.CRIT_CHANCE, "critical_chance" to Stat.CRIT_CHANCE, "critical_strike_chance" to Stat.CRIT_CHANCE, "crit_rate" to Stat.CRIT_CHANCE,
        "crit_multi" to Stat.CRIT_MULTIPLIER, "crit_damage" to Stat.CRIT_MULTIPLIER, "critical_damage" to Stat.CRIT_MULTIPLIER,
        "critical_strike_multiplier" to Stat.CRIT_MULTIPLIER, "critical_multiplier" to Stat.CRIT_MULTIPLIER,
        "speed" to Stat.ATTACK_SPEED, "cast_speed" to Stat.ATTACK_SPEED, "attack_rate" to Stat.ATTACK_SPEED,
        "lifesteal" to Stat.LIFE_STEAL, "life_leech" to Stat.LIFE_STEAL, "leech" to Stat.LIFE_STEAL, "vampirism" to Stat.LIFE_STEAL,
        "resist" to Stat.RESISTANCE, "resistances" to Stat.RESISTANCE, "all_resistance" to Stat.RESISTANCE, "all_resistances" to Stat.RESISTANCE,
        "spell_damage" to Stat.SKILL_DAMAGE, "ability_damage" to Stat.SKILL_DAMAGE, "magic_damage" to Stat.SKILL_DAMAGE,
        "area_of_effect" to Stat.AREA, "aoe" to Stat.AREA, "radius" to Stat.AREA,
        "cooldown" to Stat.COOLDOWN_RECOVERY, "cooldown_reduction" to Stat.COOLDOWN_RECOVERY, "cdr" to Stat.COOLDOWN_RECOVERY,
        "cost" to Stat.RESOURCE_COST, "mana_cost" to Stat.RESOURCE_COST, "skill_cost" to Stat.RESOURCE_COST,
        "movement_speed" to Stat.MOVE_SPEED, "move" to Stat.MOVE_SPEED, "movement" to Stat.MOVE_SPEED,
        "mana" to Stat.MAX_RESOURCE, "resource" to Stat.MAX_RESOURCE, "energy" to Stat.MAX_RESOURCE, "max_mana" to Stat.MAX_RESOURCE,
        "focus" to Stat.MAX_RESOURCE, "maximum_resource" to Stat.MAX_RESOURCE,
        "experience" to Stat.EXPERIENCE_GAIN, "xp" to Stat.EXPERIENCE_GAIN, "exp" to Stat.EXPERIENCE_GAIN,
        "rarity" to Stat.ITEM_RARITY, "magic_find" to Stat.ITEM_RARITY, "quantity" to Stat.ITEM_QUANTITY,
        "mining" to Stat.MINING_SPEED, "dig_speed" to Stat.MINING_SPEED,
    )

    /**
     * The stat [raw] names. "fire_resistance" is a resistance scoped to
     * whichever loaded damage type is called fire; "thunder_damage" is
     * damage, since damage is not scoped by type.
     */
    fun stat(raw: String, damageTypes: Collection<String>): StatWord? {
        val key = Lenient.key(raw)
        Stat.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return StatWord(it) }
        statAliases[key]?.let { return StatWord(it) }
        val scoped = listOf("_resistance", "_resist", "_res").firstOrNull { key.endsWith(it) }
        if (scoped != null) {
            val type = key.removeSuffix(scoped)
            return StatWord(Stat.RESISTANCE, damageTypes.firstOrNull { Lenient.slug(it.substringAfterLast(':')) == type })
        }
        if (key.endsWith("_damage")) return StatWord(Stat.DAMAGE)
        return null
    }

    /** The kind [raw] names, and whether the word itself meant a reduction: "reduced", "less". */
    fun kind(raw: String): Pair<ModifierKind, Boolean>? = when (Lenient.key(raw)) {
        "flat", "added", "add", "plus", "+", "base" -> ModifierKind.FLAT to false
        "increased", "increase", "inc", "percent", "%" -> ModifierKind.INCREASED to false
        "reduced", "reduce", "decreased" -> ModifierKind.INCREASED to true
        "more", "multiplier", "multiplicative", "x" -> ModifierKind.MORE to false
        "less" -> ModifierKind.MORE to true
        else -> null
    }

    fun slot(raw: String): ItemSlot? {
        ItemSlot.parse(raw)?.let { return it }
        return when (Lenient.key(raw)) {
            "helmet", "head", "hat", "crown", "hood", "cap", "mask" -> ItemSlot.HELM
            "chest", "body_armour", "body_armor", "robe", "breastplate", "cuirass", "coat", "torso" -> ItemSlot.CHEST
            "gauntlets", "hands", "bracers", "glove" -> ItemSlot.GLOVES
            "boot", "feet", "greaves", "sandals", "shoes" -> ItemSlot.BOOTS
            "sash", "girdle", "waist" -> ItemSlot.BELT
            "necklace", "pendant", "talisman" -> ItemSlot.AMULET
            "band", "finger" -> ItemSlot.RING
            "sword", "axe", "mace", "spear", "staff", "bow", "wand", "dagger", "hammer", "blade", "weapon_1h", "weapon_2h" -> ItemSlot.WEAPON
            "off_hand", "buckler", "orb", "tome", "quiver" -> ItemSlot.OFFHAND
            else -> null
        }
    }

    fun flag(raw: String): BuildFlag? {
        val key = Lenient.key(raw)
        return BuildFlag.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: BuildFlag.entries.firstOrNull { Lenient.key(it.label) == key }
    }

    fun affixKind(raw: String?): AffixKind? = when (raw?.let(Lenient::key)) {
        "prefix", "pre" -> AffixKind.PREFIX
        "suffix", "suf" -> AffixKind.SUFFIX
        else -> null
    }

    fun loreCategory(raw: String?): LoreCategory? {
        val key = raw?.let(Lenient::key) ?: return null
        LoreCategory.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it }
        return when (key) {
            "god", "gods", "deities", "spirit", "divinity", "religion" -> LoreCategory.DEITY
            "item", "relic", "weapon", "armour", "armor", "artefact", "artifacts" -> LoreCategory.ARTIFACT
            "monster", "creature", "beast", "bestiary_entry", "monsters" -> LoreCategory.BESTIARY
            "location", "region", "city", "town", "places" -> LoreCategory.PLACE
            "rite", "ceremony", "custom", "rituals" -> LoreCategory.RITUAL
            "event", "war", "legend", "myth", "past" -> LoreCategory.HISTORY
            else -> null
        }
    }
}
