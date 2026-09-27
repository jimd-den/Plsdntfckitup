package com.stratum.plugins.schema

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import kotlinx.serialization.Serializable

// The fight half of a pack: damage, stats, monsters and powers. Gear is in ItemSchema.

private val DAMAGE = DamageTypeDefinition(id = "", name = "")
private val STATS = CombatStats()

@Serializable
internal data class DamageTypeSchema(
    val id: String,
    val name: String,
    val color: String = SchemaValues.color(DAMAGE.color),
    val symbol: String = DAMAGE.symbol,
    val armour: Boolean = DAMAGE.mitigatedByArmour,
    val ailment: String? = null,
    val ailmentChance: Float = DAMAGE.ailmentChance,
) {
    fun toDomain() = DamageTypeDefinition(id, name, SchemaValues.color(color, "damage type '$id' color"), symbol, armour, ailment, ailmentChance)

    companion object {
        fun of(d: DamageTypeDefinition) = DamageTypeSchema(d.id, d.name, SchemaValues.color(d.color), d.symbol, d.mitigatedByArmour, d.ailmentStatusId, d.ailmentChance)
    }
}

@Serializable
internal data class StatsSchema(
    val maxHealth: Int = STATS.maxHealth,
    val attackPower: Int = STATS.attackPower,
    val armour: Int = STATS.armour,
    val critChance: Float = STATS.critChance,
    val critMultiplier: Float = STATS.critMultiplier,
    val attackSpeed: Float = STATS.attackSpeed,
    val attackRange: Int = STATS.attackRange,
    val resistances: Map<String, Float> = emptyMap(),
    val lifeSteal: Float = STATS.lifeSteal,
    val evasion: Int = STATS.evasion,
    val blockChance: Float = STATS.blockChance,
    val accuracy: Int = STATS.accuracy,
) {
    fun toDomain() = CombatStats(maxHealth, attackPower, armour, critChance, critMultiplier, attackSpeed, attackRange, resistances, lifeSteal, evasion, blockChance, accuracy)

    companion object {
        fun of(s: CombatStats) = StatsSchema(
            s.maxHealth, s.attackPower, s.armour, s.critChance, s.critMultiplier, s.attackSpeed, s.attackRange, s.resistances, s.lifeSteal,
            s.evasion, s.blockChance, s.accuracy,
        )
    }
}

@Serializable
internal data class RaritySchema(val rarity: String, val name: String, val color: String) {
    fun toDomain() = RarityStyle(SchemaValues.enum<ItemRarity>(rarity, "rarity"), name, SchemaValues.color(color, "rarity '$rarity' color"))

    companion object {
        fun of(r: RarityStyle) = RaritySchema(SchemaValues.name(r.rarity), r.name, SchemaValues.color(r.color))
    }
}
