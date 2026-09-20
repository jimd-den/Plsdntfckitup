package com.stratum.core.domain.combat

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A kind of damage. Packs define these, so a pack can ship "Thunder" and
 * "Rot" without the engine knowing either name.
 */
data class DamageTypeDefinition(
    val id: String,
    val name: String,
    val color: Long = 0xFFE0E0E0,
    /** Shown on floating combat text and resistance readouts. */
    val symbol: String = "",
)

/**
 * The numbers that decide a fight. Shared by the player and by monsters,
 * because there is no reason for a monster to compute damage differently from
 * the thing hitting it.
 */
data class CombatStats(
    val maxHealth: Int = 100,
    val attackPower: Int = 10,
    /** Flat reduction applied after resistances, floored so armour never heals. */
    val armour: Int = 0,
    /** 0..1 */
    val critChance: Float = 0.05f,
    val critMultiplier: Float = 1.5f,
    /** Attacks per second. */
    val attackSpeed: Float = 1f,
    /** Blocks of reach for a melee swing. */
    val attackRange: Int = 1,
    /** Per damage type id, 0..1 fraction of that damage ignored. */
    val resistances: Map<String, Float> = emptyMap(),
    /** Fraction of damage dealt returned as health. */
    val lifeSteal: Float = 0f,
) {
    val secondsBetweenAttacks: Float get() = if (attackSpeed <= 0f) Float.MAX_VALUE else 1f / attackSpeed

    fun resistanceTo(damageTypeId: String): Float =
        (resistances[damageTypeId] ?: 0f).coerceIn(MIN_RESISTANCE, MAX_RESISTANCE)

    operator fun plus(other: CombatStats): CombatStats = CombatStats(
        maxHealth = maxHealth + other.maxHealth,
        attackPower = attackPower + other.attackPower,
        armour = armour + other.armour,
        critChance = critChance + other.critChance,
        critMultiplier = critMultiplier + other.critMultiplier,
        attackSpeed = attackSpeed + other.attackSpeed,
        attackRange = max(attackRange, other.attackRange),
        resistances = (resistances.keys + other.resistances.keys).associateWith {
            (resistances[it] ?: 0f) + (other.resistances[it] ?: 0f)
        },
        lifeSteal = lifeSteal + other.lifeSteal,
    )

    companion object {
        /** Negative resistance is a vulnerability, capped so it cannot spiral. */
        const val MIN_RESISTANCE = -1f
        /** Full immunity would make a fight unwinnable, so resistance stops short. */
        const val MAX_RESISTANCE = 0.85f
    }
}

/** One resolved hit. Everything the UI needs to draw it, and nothing more. */
data class DamageResult(
    val amount: Int,
    val damageTypeId: String,
    val wasCritical: Boolean,
    val wasBlocked: Boolean,
    val healedAttacker: Int = 0,
) {
    val landed: Boolean get() = amount > 0
}

/**
 * Resolves one attack.
 *
 * Deliberately a pure function of its inputs plus an explicit roll, so a fight
 * can be replayed exactly and a test can pin the crit instead of hoping for one.
 */
object DamageCalculator {

    fun resolve(
        attacker: CombatStats,
        defender: CombatStats,
        damageTypeId: String,
        /** 0..1, supplied by the caller's RNG so this stays deterministic. */
        critRoll: Float,
        /** Multiplies base attack power, e.g. a skill that hits for 250%. */
        powerMultiplier: Float = 1f,
    ): DamageResult {
        val isCritical = critRoll < attacker.critChance
        val base = attacker.attackPower * powerMultiplier
        val afterCrit = if (isCritical) base * attacker.critMultiplier else base

        val resistance = defender.resistanceTo(damageTypeId)
        val afterResistance = afterCrit * (1f - resistance)

        // Armour is flat and applied last, so stacking it is strong against many
        // small hits and weak against one large one. That is the trade-off that
        // makes attack speed and armour meaningfully different choices.
        val afterArmour = afterResistance - defender.armour
        val finalAmount = max(0f, afterArmour).roundToInt()

        // A hit that armour swallows entirely still reads as a hit, so the
        // player can tell "my damage is being absorbed" from "I missed".
        val blocked = afterResistance > 0f && finalAmount == 0

        return DamageResult(
            amount = finalAmount,
            damageTypeId = damageTypeId,
            wasCritical = isCritical,
            wasBlocked = blocked,
            healedAttacker = (finalAmount * attacker.lifeSteal).roundToInt(),
        )
    }
}
