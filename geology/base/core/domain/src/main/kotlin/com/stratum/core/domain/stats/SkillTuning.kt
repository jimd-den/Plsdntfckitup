package com.stratum.core.domain.stats

import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.combat.CombatRules
import kotlin.math.roundToInt

/**
 * A skill as this build casts it: its damage, cost, cooldown and reach with
 * every relevant modifier applied.
 *
 * Returned as an ordinary [SkillDefinition], so combat, cooldowns and the
 * skill bar all read the tuned numbers without knowing modifiers exist, and a
 * tooltip can never disagree with the cast.
 */
fun StatSheet.tune(skill: SkillDefinition, rules: CombatRules = CombatRules()): SkillDefinition {
    if (modifiers.isEmpty()) return skill
    val areaScale = multiplier(Stat.AREA)
    val projectile = skill.projectile
    return skill.copy(
        powerMultiplier = apply(Stat.SKILL_DAMAGE, skill.powerMultiplier, skill.damageTypeId),
        resourceCost = apply(Stat.RESOURCE_COST, skill.resourceCost.toFloat()).roundToInt(),
        lifeCost = apply(Stat.RESOURCE_COST, skill.lifeCost.toFloat()).roundToInt(),
        cooldownSeconds = recovered(skill.cooldownSeconds, rules),
        range = apply(Stat.AREA, skill.range.toFloat()).roundToInt().coerceAtLeast(1),
        area = skill.area.copy(radius = skill.area.radius * areaScale),
        // Cast speed divides the wind-up, the way recovery divides the wait.
        castTime = skill.castTime / multiplier(Stat.CAST_SPEED).coerceAtLeast(MIN_RECOVERY),
        projectile = projectile.copy(
            count = (projectile.count + flat(Stat.PROJECTILES).roundToInt()).coerceAtLeast(1),
            pierce = (projectile.pierce + flat(Stat.PIERCE).roundToInt()).coerceAtLeast(0),
            chain = (projectile.chain + flat(Stat.CHAIN).roundToInt()).coerceAtLeast(0),
            fork = (projectile.fork + flat(Stat.FORK).roundToInt()).coerceAtLeast(0),
            speed = (projectile.speed * multiplier(Stat.PROJECTILE_SPEED)).coerceAtLeast(MIN_PROJECTILE_SPEED),
        ),
        zone = skill.zone.copy(durationSeconds = (skill.zone.durationSeconds * multiplier(Stat.DURATION)).coerceAtLeast(MIN_DURATION)),
    )
}

/**
 * Recovery speed divides: 100% increased recovery halves the wait. It never
 * brings a cooldown under the world's floor, unless the skill was authored
 * shorter than the floor to begin with -- the floor stops builds from
 * reaching zero, it does not slow down skills designed to be fast.
 */
private fun StatSheet.recovered(base: Float, rules: CombatRules): Float {
    if (base <= 0f) return base
    val recovered = base / multiplier(Stat.COOLDOWN_RECOVERY).coerceAtLeast(MIN_RECOVERY)
    return recovered.coerceAtLeast(minOf(base, rules.cooldownFloor))
}

/**
 * Loot odds for this build. Quantity scales the chance a kill drops anything;
 * rarity thins out the commons, so what does drop is better.
 */
data class LootFind(val quantity: Float = 1f, val rarity: Float = 1f) {
    fun dropChance(base: Float): Float = (base * quantity).coerceIn(0f, 1f)

    /** A rarity bonus as the roller takes it: the share of commons re-rolled into something better. */
    fun rarityBonus(base: Float): Float = (1f - (1f - base.coerceIn(0f, 1f)) / rarity.coerceAtLeast(MIN_RECOVERY)).coerceIn(0f, 1f)

    companion object {
        val NONE = LootFind()
    }
}

val StatSheet.lootFind: LootFind get() = LootFind(multiplier(Stat.ITEM_QUANTITY), multiplier(Stat.ITEM_RARITY))

/** A floor under anything that divides, so a stack of "reduced" cannot divide by zero. */
private const val MIN_RECOVERY = 0.1f
private const val MIN_PROJECTILE_SPEED = 1f
private const val MIN_DURATION = 0.1f
