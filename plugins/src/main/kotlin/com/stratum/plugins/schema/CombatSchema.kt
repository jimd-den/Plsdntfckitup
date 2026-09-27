package com.stratum.plugins.schema

import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillShape
import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.combat.DamageTypeDefinition
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.RarityStyle
import kotlinx.serialization.Serializable

// The fight half of a pack: damage, stats, monsters and powers. Gear is in ItemSchema.

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
    val faction: String? = null,
    val role: String = SchemaValues.name(ENEMY.role),
) {
    fun toDomain() = EnemyDefinition(
        id, name, description, SchemaValues.enum<EnemyRank>(rank, "enemy '$id' rank"), stats.toDomain(), damageType, moveSpeed,
        aggroRange, fleeBelowHealth, canFlee, experience, spawnBiomes, spawnWeight, bonusDropChance,
        SchemaValues.color(bodyColor, "enemy '$id' bodyColor"), spriteSet, faction, SchemaValues.enum<CombatRole>(role, "enemy '$id' role"),
    )

    companion object {
        fun of(e: EnemyDefinition) = EnemySchema(
            e.id, e.name, e.description, SchemaValues.name(e.rank), StatsSchema.of(e.baseStats), e.damageTypeId, e.moveSpeed, e.aggroRange,
            e.fleeBelowHealth, e.canFlee, e.experience, e.spawnBiomeIds, e.spawnWeight, e.bonusDropChance, SchemaValues.color(e.bodyColor), e.spriteSetId,
            e.factionId, SchemaValues.name(e.role),
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
