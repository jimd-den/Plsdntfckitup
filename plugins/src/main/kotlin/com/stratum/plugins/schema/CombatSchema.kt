package com.stratum.plugins.schema

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillShape
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.AffixDefinition
import com.stratum.core.domain.item.AffixKind
import com.stratum.core.domain.item.AffixStat
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import com.stratum.core.domain.item.WeaponBase
import kotlinx.serialization.Serializable

// The fight half of a pack: damage, stats, gear, monsters and powers.

private val DAMAGE = DamageTypeDefinition(id = "", name = "")
private val STATS = CombatStats()

@Serializable
internal data class DamageTypeSchema(val id: String, val name: String, val color: String = SchemaValues.color(DAMAGE.color), val symbol: String = DAMAGE.symbol) {
    fun toDomain() = DamageTypeDefinition(id, name, SchemaValues.color(color, "damage type '$id' color"), symbol)

    companion object {
        fun of(d: DamageTypeDefinition) = DamageTypeSchema(d.id, d.name, SchemaValues.color(d.color), d.symbol)
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
) {
    fun toDomain() = CombatStats(maxHealth, attackPower, armour, critChance, critMultiplier, attackSpeed, attackRange, resistances, lifeSteal)

    companion object {
        fun of(s: CombatStats) = StatsSchema(s.maxHealth, s.attackPower, s.armour, s.critChance, s.critMultiplier, s.attackSpeed, s.attackRange, s.resistances, s.lifeSteal)
    }
}

private val WEAPON = WeaponBase(id = "", name = "", damageTypeId = "")

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
) {
    fun toDomain() = WeaponBase(
        id, name, description, SchemaValues.enum<EquipmentSlot>(slot, "weapon '$id' slot"), minDamage, maxDamage, attackSpeed,
        attackRange, damageType, toolTier, armour, glyph, minItemLevel, weight,
    )

    companion object {
        fun of(w: WeaponBase) = WeaponSchema(
            w.id, w.name, w.description, SchemaValues.name(w.slot), w.minDamage, w.maxDamage, w.attackSpeed, w.attackRange,
            w.damageTypeId, w.toolTier, w.armour, w.glyph, w.minItemLevel, w.weight,
        )
    }
}

@Serializable
internal data class AffixSchema(
    val id: String,
    val name: String,
    val kind: String,
    val stat: String,
    val min: Float,
    val max: Float,
    val damageType: String? = null,
    val minItemLevel: Int = 1,
    val weight: Int = 100,
) {
    fun toDomain() = AffixDefinition(
        id, name, SchemaValues.enum<AffixKind>(kind, "affix '$id' kind"), SchemaValues.enum<AffixStat>(stat, "affix '$id' stat"),
        min, max, damageType, minItemLevel, weight,
    )

    companion object {
        fun of(a: AffixDefinition) = AffixSchema(a.id, a.name, SchemaValues.name(a.kind), SchemaValues.name(a.stat), a.minValue, a.maxValue, a.damageTypeId, a.minItemLevel, a.weight)
    }
}

private val INSERT = InsertDefinition(id = "", name = "", stat = AffixStat.ATTACK_POWER, value = 0f)

@Serializable
internal data class InsertSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val stat: String,
    val value: Float,
    val damageType: String? = null,
    val convertsDamageType: Boolean = INSERT.convertsDamageType,
    val tier: Int = INSERT.tier,
    val glyph: String = INSERT.glyph,
    val color: String = SchemaValues.color(INSERT.color),
    val minItemLevel: Int = INSERT.minItemLevel,
    val weight: Int = INSERT.weight,
) {
    fun toDomain() = InsertDefinition(
        id, name, description, SchemaValues.enum<AffixStat>(stat, "insert '$id' stat"), value, damageType, convertsDamageType, tier,
        glyph, SchemaValues.color(color, "insert '$id' color"), minItemLevel, weight,
    )

    companion object {
        fun of(i: InsertDefinition) = InsertSchema(
            i.id, i.name, i.description, SchemaValues.name(i.stat), i.value, i.damageTypeId, i.convertsDamageType, i.tier, i.glyph,
            SchemaValues.color(i.color), i.minItemLevel, i.weight,
        )
    }
}

private val ENEMY = EnemyDefinition(id = "", name = "", damageTypeId = "")

@Serializable
internal data class EnemySchema(
    val id: String,
    val name: String,
    val description: String = "",
    val rank: String = SchemaValues.name(ENEMY.rank),
    val stats: StatsSchema = StatsSchema(),
    val damageType: String,
    val moveSpeed: Float = ENEMY.moveSpeed,
    val aggroRange: Int = ENEMY.aggroRange,
    val fleeBelowHealth: Float = ENEMY.fleeBelowHealth,
    val canFlee: Boolean = ENEMY.canFlee,
    val experience: Int = ENEMY.experience,
    val spawnBiomes: List<String> = emptyList(),
    val spawnWeight: Int = ENEMY.spawnWeight,
    val bonusDropChance: Float = ENEMY.bonusDropChance,
    val bodyColor: String = SchemaValues.color(ENEMY.bodyColor),
    val spriteSet: String? = null,
) {
    fun toDomain() = EnemyDefinition(
        id, name, description, SchemaValues.enum<EnemyRank>(rank, "enemy '$id' rank"), stats.toDomain(), damageType, moveSpeed,
        aggroRange, fleeBelowHealth, canFlee, experience, spawnBiomes, spawnWeight, bonusDropChance,
        SchemaValues.color(bodyColor, "enemy '$id' bodyColor"), spriteSet,
    )

    companion object {
        fun of(e: EnemyDefinition) = EnemySchema(
            e.id, e.name, e.description, SchemaValues.name(e.rank), StatsSchema.of(e.baseStats), e.damageTypeId, e.moveSpeed, e.aggroRange,
            e.fleeBelowHealth, e.canFlee, e.experience, e.spawnBiomeIds, e.spawnWeight, e.bonusDropChance, SchemaValues.color(e.bodyColor), e.spriteSetId,
        )
    }
}

private val SKILL = SkillDefinition(id = "", name = "", damageTypeId = "")

@Serializable
internal data class SkillSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val damageType: String,
    val powerMultiplier: Float = SKILL.powerMultiplier,
    val resourceCost: Int = SKILL.resourceCost,
    val cooldownSeconds: Float = SKILL.cooldownSeconds,
    val shape: String = SchemaValues.name(SKILL.shape),
    val range: Int = SKILL.range,
    val color: String = SchemaValues.color(SKILL.color),
) {
    fun toDomain() = SkillDefinition(
        id, name, description, damageType, powerMultiplier, resourceCost, cooldownSeconds,
        SchemaValues.enum<SkillShape>(shape, "skill '$id' shape"), range, SchemaValues.color(color, "skill '$id' color"),
    )

    companion object {
        fun of(s: SkillDefinition) = SkillSchema(
            s.id, s.name, s.description, s.damageTypeId, s.powerMultiplier, s.resourceCost, s.cooldownSeconds, SchemaValues.name(s.shape), s.range, SchemaValues.color(s.color),
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
