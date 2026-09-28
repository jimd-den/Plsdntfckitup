package com.stratum.core.domain.combat

import com.stratum.core.domain.status.StatusApplication
import kotlin.math.max

/**
 * A kind of damage. Packs define these, so a pack can ship "Thunder" and
 * "Rot" without the engine knowing either name.
 *
 * A type also says how it behaves when it lands, because that is what makes
 * elements feel different: whether armour stops it, and which status it
 * leaves behind. The status is pack data too -- the engine only knows the
 * behaviours a status can have, never that "fire burns".
 */
data class DamageTypeDefinition(
    val id: String,
    val name: String,
    val color: Long = 0xFFE0E0E0,
    /** Shown on floating combat text and resistance readouts. */
    val symbol: String = "",
    /** Whether armour reduces it. A pack's physical type says yes; its elements usually say no. */
    val mitigatedByArmour: Boolean = true,
    /** The status a hit of this type may inflict, such as a burn or a chill; null for none. */
    val ailmentStatusId: String? = null,
    /** Base chance, 0..1, that a hit carrying this type inflicts [ailmentStatusId]. */
    val ailmentChance: Float = 0f,
) {
    init {
        require(ailmentChance in 0f..1f) { "damage type '$id' ailment chance $ailmentChance is not a share" }
    }
}

/**
 * The numbers that decide a fight. Shared by the player and by monsters,
 * because there is no reason for a monster to compute damage differently from
 * the thing hitting it.
 */
data class CombatStats(
    val maxHealth: Int = 100,
    val attackPower: Int = 10,
    /**
     * Mitigates hits of armour-affected types, more against small hits than
     * big ones. See [HitResolver.armourReduction] for why it is a curve.
     */
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
    /** Fraction of damage dealt returned as health, through leech. */
    val lifeSteal: Float = 0f,
    /** Weighed against an attacker's [accuracy] for the chance an attack misses outright. */
    val evasion: Int = 0,
    /** Chance, 0..1, to block a hit entirely. */
    val blockChance: Float = 0f,
    val accuracy: Int = DEFAULT_ACCURACY,
) {
    val secondsBetweenAttacks: Float get() = if (attackSpeed <= 0f) Float.MAX_VALUE else 1f / attackSpeed

    /** Resistance within the default caps. The full resolver applies the world's caps instead. */
    fun resistanceTo(damageTypeId: String): Float =
        (resistances[damageTypeId] ?: 0f).coerceIn(MIN_RESISTANCE, MAX_RESISTANCE)

    /** Resistance as the stats say it, before any cap: what a sandbox with lifted caps reads. */
    fun rawResistanceTo(damageTypeId: String): Float = resistances[damageTypeId] ?: 0f

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
        evasion = evasion + other.evasion,
        blockChance = blockChance + other.blockChance,
        // Accuracy is a rating with a baseline, like reach: the better of the two, not two baselines summed.
        accuracy = max(accuracy, other.accuracy),
    )

    companion object {
        /** Negative resistance is a vulnerability, capped so it cannot spiral. */
        const val MIN_RESISTANCE = -1f

        /** Full immunity would make a fight unwinnable, so resistance stops short. */
        const val MAX_RESISTANCE = 0.85f
        const val DEFAULT_ACCURACY = 100
    }
}

/** One resolved hit. Everything the UI needs to draw it, and nothing more. */
data class DamageResult(
    val amount: Int,
    /** The type that did most of the damage: what colours the number. */
    val damageTypeId: String,
    val wasCritical: Boolean,
    val wasBlocked: Boolean,
    /** Life the attacker leeches from this hit. Recovered over time unless leech is instant. */
    val healedAttacker: Int = 0,
    /** Damage by type, after every mitigation. Sums to [amount]. */
    val packets: Map<String, Int> = emptyMap(),
    /** The attack missed: evaded, and nothing else happened. */
    val wasEvaded: Boolean = false,
    /** Statuses this hit inflicts on the defender, already rolled. */
    val inflicted: List<StatusApplication> = emptyList(),
) {
    val landed: Boolean get() = amount > 0
}

/**
 * Resolves one single-typed hit.
 *
 * Deliberately a pure function of its inputs plus an explicit roll, so a fight
 * can be replayed exactly and a test can pin the crit instead of hoping for one.
 * The quick path for code that has one damage type and no build to consult;
 * it runs the same [HitResolver] every other hit does.
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
    ): DamageResult = HitResolver.resolve(
        attacker = HitAttacker(attacker),
        defender = HitDefender(defender),
        damage = mapOf(damageTypeId to attacker.attackPower * powerMultiplier),
        rolls = HitRolls(crit = critRoll),
    )
}
