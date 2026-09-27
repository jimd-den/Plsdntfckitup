package com.stratum.core.domain.stats

import com.stratum.core.domain.actor.SkillDefinition
import kotlin.math.roundToInt

/**
 * A skill as this build casts it: its damage, cost, cooldown and reach with
 * every relevant modifier applied.
 *
 * Returned as an ordinary [SkillDefinition], so combat, cooldowns and the
 * skill bar all read the tuned numbers without knowing modifiers exist, and a
 * tooltip can never disagree with the cast.
 */
fun StatSheet.tune(skill: SkillDefinition): SkillDefinition {
    if (modifiers.isEmpty()) return skill
    return skill.copy(
        powerMultiplier = apply(Stat.SKILL_DAMAGE, skill.powerMultiplier, skill.damageTypeId),
        resourceCost = apply(Stat.RESOURCE_COST, skill.resourceCost.toFloat()).roundToInt(),
        // Recovery speed divides: 100% increased recovery halves the wait.
        cooldownSeconds = skill.cooldownSeconds / multiplier(Stat.COOLDOWN_RECOVERY).coerceAtLeast(MIN_RECOVERY),
        range = apply(Stat.AREA, skill.range.toFloat()).roundToInt().coerceAtLeast(1),
    )
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
