package com.stratum.plugins.schema

import com.stratum.core.domain.actor.BossPhase
import com.stratum.core.domain.actor.CombatRole
import com.stratum.core.domain.actor.EffectTarget
import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.actor.MonsterSkill
import com.stratum.core.domain.actor.ProjectileSpec
import com.stratum.core.domain.actor.SkillArea
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.actor.SkillDelivery
import com.stratum.core.domain.actor.SkillEffect
import com.stratum.core.domain.actor.SummonSpec
import com.stratum.core.domain.actor.ZoneSpec
import com.stratum.core.domain.combat.DamageConversion
import com.stratum.core.domain.combat.ExtraDamage
import com.stratum.core.domain.importing.ImportException
import kotlinx.serialization.Serializable

// Skills and the monsters that use them: delivery, area, effects, tags and
// costs; monster skill lists and boss phases.

private val SKILL = SkillDefinition(id = "", name = "", damageTypeId = "")
private val AREA = SkillArea()
private val PROJECTILE = ProjectileSpec()
private val ZONE = ZoneSpec()

/**
 * A skill. `shape` is the old way of saying `delivery` -- strike, nova or
 * lance -- and still loads, so plugins written before skills were
 * composable play exactly as they did.
 */
@Serializable
internal data class SkillSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val damageType: String,
    val powerMultiplier: Float = SKILL.powerMultiplier,
    val resourceCost: Int = SKILL.resourceCost,
    val cooldownSeconds: Float = SKILL.cooldownSeconds,
    val shape: String? = null,
    val delivery: String? = null,
    val range: Int = SKILL.range,
    val color: String = SchemaValues.color(SKILL.color),
    val area: AreaSchema? = null,
    val effects: List<EffectSchema> = emptyList(),
    val tags: List<String> = emptyList(),
    val castTime: Float = SKILL.castTime,
    val charges: Int = SKILL.charges,
    val lifeCost: Int = SKILL.lifeCost,
    val projectile: ProjectileSchema? = null,
    val zone: ZoneSchema? = null,
    val summon: SummonSchema? = null,
    val conversions: List<ConversionSchema> = emptyList(),
) {
    fun toDomain(): SkillDefinition {
        val owner = "skill '$id'"
        return SkillDefinition(
            id, name, description, damageType, powerMultiplier, resourceCost, cooldownSeconds, delivery(owner), range,
            SchemaValues.color(color, "$owner color"), area?.toDomain() ?: AREA, effects.map { it.toDomain(owner) }, tags.toSet(),
            castTime, charges, lifeCost, projectile?.toDomain() ?: PROJECTILE, zone?.toDomain() ?: ZONE, summon?.toDomain(),
            conversions.map { it.toConversion() },
        )
    }

    private fun delivery(owner: String): SkillDelivery = when {
        delivery != null -> SchemaValues.enum<SkillDelivery>(delivery, "$owner delivery")
        shape != null -> SkillDelivery.fromLegacyShape(shape) ?: throw ImportException("$owner shape: '$shape' is not one of strike, nova, lance")
        else -> SKILL.delivery
    }

    companion object {
        fun of(s: SkillDefinition) = SkillSchema(
            s.id, s.name, s.description, s.damageTypeId, s.powerMultiplier, s.resourceCost, s.cooldownSeconds, null, SchemaValues.name(s.delivery),
            s.range, SchemaValues.color(s.color), s.area.takeIf { it != AREA }?.let(AreaSchema::of), s.effects.map(EffectSchema::of),
            s.tags.toList(), s.castTime, s.charges, s.lifeCost, s.projectile.takeIf { it != PROJECTILE }?.let(ProjectileSchema::of),
            s.zone.takeIf { it != ZONE }?.let(ZoneSchema::of), s.summon?.let(SummonSchema::of), s.conversions.map(ConversionSchema::of),
        )
    }
}

@Serializable
internal data class AreaSchema(val radius: Float = AREA.radius, val angle: Float = AREA.angleDegrees, val halfWidth: Float = AREA.halfWidth) {
    fun toDomain() = SkillArea(radius, angle, halfWidth)

    companion object {
        fun of(a: SkillArea) = AreaSchema(a.radius, a.angleDegrees, a.halfWidth)
    }
}

@Serializable
internal data class ProjectileSchema(
    val count: Int = PROJECTILE.count,
    val speed: Float = PROJECTILE.speed,
    val pierce: Int = PROJECTILE.pierce,
    val chain: Int = PROJECTILE.chain,
    val fork: Int = PROJECTILE.fork,
    val spread: Float = PROJECTILE.spreadDegrees,
    val radius: Float = PROJECTILE.radius,
    val collides: Boolean = PROJECTILE.collidesWithBlocks,
) {
    fun toDomain() = ProjectileSpec(count, speed, pierce, chain, fork, spread, radius, collides)

    companion object {
        fun of(p: ProjectileSpec) = ProjectileSchema(p.count, p.speed, p.pierce, p.chain, p.fork, p.spreadDegrees, p.radius, p.collidesWithBlocks)
    }
}

@Serializable
internal data class ZoneSchema(val duration: Float = ZONE.durationSeconds, val pulse: Float = ZONE.pulseSeconds, val trap: Boolean = ZONE.isTrap) {
    fun toDomain() = ZoneSpec(duration, pulse, trap)

    companion object {
        fun of(z: ZoneSpec) = ZoneSchema(z.durationSeconds, z.pulseSeconds, z.isTrap)
    }
}

private val SUMMON = SummonSpec(enemyId = "")

@Serializable
internal data class SummonSchema(val enemy: String, val count: Int = SUMMON.count, val duration: Float = SUMMON.durationSeconds, val limit: Int = SUMMON.limit) {
    fun toDomain() = SummonSpec(enemy, count, duration, limit)

    companion object {
        fun of(s: SummonSpec) = SummonSchema(s.enemyId, s.count, s.durationSeconds, s.limit)
    }
}

/**
 * One effect, written flat with a `type`: `damage`, `status`, `heal`,
 * `resource`, `knockback` or `cast`. Flat rather than nested so a hand-written
 * effect is one line.
 */
@Serializable
internal data class EffectSchema(
    val type: String,
    val damageType: String? = null,
    val share: Float = 1f,
    val status: String? = null,
    val chance: Float = 1f,
    val stacks: Int = 1,
    val amount: Int = 0,
    val maxShare: Float = 0f,
    val force: Float = 0f,
    val skill: String? = null,
    val target: String? = null,
) {
    fun toDomain(owner: String): SkillEffect {
        fun need(value: String?, field: String) = value ?: throw ImportException("$owner effect '$type' needs '$field'")
        fun target(default: EffectTarget) = target?.let { SchemaValues.enum<EffectTarget>(it, "$owner effect target") } ?: default
        return when (type.trim().lowercase()) {
            "damage" -> SkillEffect.Damage(need(damageType, "damageType"), share)
            "status" -> SkillEffect.ApplyStatus(need(status, "status"), chance, target(EffectTarget.TARGET), stacks)
            "heal" -> SkillEffect.Heal(amount, maxShare, target(EffectTarget.SELF))
            "resource" -> SkillEffect.RestoreResource(amount)
            "knockback" -> SkillEffect.Knockback(force)
            "cast" -> SkillEffect.CastSkill(need(skill, "skill"), target(EffectTarget.TARGET))
            else -> throw ImportException("$owner effect: '$type' is not one of damage, status, heal, resource, knockback, cast")
        }
    }

    companion object {
        fun of(e: SkillEffect): EffectSchema = when (e) {
            is SkillEffect.Damage -> EffectSchema("damage", damageType = e.damageTypeId, share = e.share)
            is SkillEffect.ApplyStatus -> EffectSchema("status", status = e.statusId, chance = e.chance, stacks = e.stacks, target = SchemaValues.name(e.target))
            is SkillEffect.Heal -> EffectSchema("heal", amount = e.amount, maxShare = e.maxShare, target = SchemaValues.name(e.target))
            is SkillEffect.RestoreResource -> EffectSchema("resource", amount = e.amount)
            is SkillEffect.Knockback -> EffectSchema("knockback", force = e.force)
            is SkillEffect.CastSkill -> EffectSchema("cast", skill = e.skillId, target = SchemaValues.name(e.target))
        }
    }
}

/** A conversion, or with `extra` set, "gain as extra". A missing `from` means all damage. */
@Serializable
internal data class ConversionSchema(val from: String? = null, val to: String, val share: Float, val extra: Boolean = false) {
    fun toConversion() = DamageConversion(from, to, share)

    fun toExtra() = ExtraDamage(from, to, share)

    companion object {
        fun of(c: DamageConversion) = ConversionSchema(c.fromDamageTypeId, c.toDamageTypeId, c.share)

        fun of(e: ExtraDamage) = ConversionSchema(e.fromDamageTypeId, e.toDamageTypeId, e.share, extra = true)
    }
}

private val MONSTER_SKILL = MonsterSkill(skillId = "")

@Serializable
internal data class MonsterSkillSchema(
    val skill: String,
    val weight: Int = MONSTER_SKILL.weight,
    val healthBelow: Float = MONSTER_SKILL.healthBelow,
    val cooldownSeconds: Float? = null,
) {
    fun toDomain() = MonsterSkill(skill, weight, healthBelow, cooldownSeconds)

    companion object {
        fun of(m: MonsterSkill) = MonsterSkillSchema(m.skillId, m.weight, m.healthBelow, m.cooldownSeconds)
    }
}

@Serializable
internal data class BossPhaseSchema(
    val name: String,
    val healthBelow: Float,
    val skills: List<MonsterSkillSchema> = emptyList(),
    val adds: List<MemberSchema> = emptyList(),
    val enrage: List<ModifierSchema> = emptyList(),
    val status: String? = null,
    val announcement: String = "",
) {
    fun toDomain(owner: String) = BossPhase(
        name, healthBelow, skills.map { it.toDomain() }, adds.map { it.toDomain() },
        enrage.map { it.toDomain("$owner phase '$name' enrage") }, status, announcement,
    )

    companion object {
        fun of(p: BossPhase) = BossPhaseSchema(
            p.name, p.healthBelow, p.skills.map(MonsterSkillSchema::of), p.adds.map(MemberSchema::of),
            p.enrage.map(ModifierSchema::of), p.statusId, p.announcement,
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
    val faction: String? = null,
    val role: String = SchemaValues.name(ENEMY.role),
    val skills: List<MonsterSkillSchema> = emptyList(),
    val phases: List<BossPhaseSchema> = emptyList(),
) {
    fun toDomain() = EnemyDefinition(
        id, name, description, SchemaValues.enum<EnemyRank>(rank, "enemy '$id' rank"), stats.toDomain(), damageType, moveSpeed,
        aggroRange, fleeBelowHealth, canFlee, experience, spawnBiomes, spawnWeight, bonusDropChance,
        SchemaValues.color(bodyColor, "enemy '$id' bodyColor"), spriteSet, faction, SchemaValues.enum<CombatRole>(role, "enemy '$id' role"),
        skills.map { it.toDomain() }, phases.map { it.toDomain("enemy '$id'") },
    )

    companion object {
        fun of(e: EnemyDefinition) = EnemySchema(
            e.id, e.name, e.description, SchemaValues.name(e.rank), StatsSchema.of(e.baseStats), e.damageTypeId, e.moveSpeed, e.aggroRange,
            e.fleeBelowHealth, e.canFlee, e.experience, e.spawnBiomeIds, e.spawnWeight, e.bonusDropChance, SchemaValues.color(e.bodyColor), e.spriteSetId,
            e.factionId, SchemaValues.name(e.role), e.skills.map(MonsterSkillSchema::of), e.phases.map(BossPhaseSchema::of),
        )
    }
}
