package com.stratum.core.domain.tabletop

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.content.HeroClassDefinition
import kotlin.random.Random

/** The three attributes every class has. A check names the one it is rolled with. */
enum class Attribute { STRENGTH, AGILITY, INSIGHT }

/**
 * A roll against a difficulty, the core of pen-and-paper play, that pays out
 * in the action game: succeed and a [Boon] changes your real combat stats for
 * a while, fail badly and a bane does.
 *
 * A check is pack data like a monster or a sword, so a plugin can bring a
 * whole tabletop system -- its dice, its checks, what each one is worth in a
 * fight -- without anyone writing code.
 */
data class SkillCheck(
    val id: String,
    val name: String,
    val description: String = "",
    /** Dice notation; see [DiceExpression]. */
    val dice: String = "1d20",
    val attribute: Attribute = Attribute.STRENGTH,
    /** The total to meet or beat. */
    val difficulty: Int = 12,
    /** What success grants. */
    val boon: Boon? = null,
    /** What a natural minimum inflicts, if anything. */
    val bane: Boon? = null,
    /** How long before the check can be tried again, so it is a decision rather than a button to mash. */
    val cooldownSeconds: Float = 60f,
) {
    val parsedDice: DiceExpression? get() = DiceExpression.parse(dice)
}

/**
 * A timed change to combat stats: a blessing, a war cry, a curse. Fractions
 * are shares of the current value (0.25 is +25%); flat values are added.
 */
data class Boon(
    val name: String,
    val durationSeconds: Float = 60f,
    val attackPowerFraction: Float = 0f,
    val attackSpeedFraction: Float = 0f,
    val armour: Int = 0,
    val maxHealth: Int = 0,
    val critChance: Float = 0f,
    val lifeSteal: Float = 0f,
) {
    fun applyTo(stats: CombatStats): CombatStats = stats.copy(
        attackPower = (stats.attackPower * (1f + attackPowerFraction)).toInt().coerceAtLeast(0),
        attackSpeed = (stats.attackSpeed * (1f + attackSpeedFraction)).coerceAtLeast(MIN_ATTACK_SPEED),
        armour = (stats.armour + armour).coerceAtLeast(0),
        maxHealth = (stats.maxHealth + maxHealth).coerceAtLeast(1),
        critChance = (stats.critChance + critChance).coerceIn(0f, 1f),
        lifeSteal = (stats.lifeSteal + lifeSteal).coerceIn(0f, 1f),
    )

    private companion object {
        const val MIN_ATTACK_SPEED = 0.1f
    }
}

/** A boon with the time it has left. */
data class ActiveBoon(val boon: Boon, val remainingSeconds: Float, val fromCheckId: String) {
    fun aged(seconds: Float): ActiveBoon? = (remainingSeconds - seconds).takeIf { it > 0f }?.let { copy(remainingSeconds = it) }
}

enum class CheckOutcome(val succeeded: Boolean) {
    CRITICAL_SUCCESS(true), SUCCESS(true), FAILURE(false), CRITICAL_FAILURE(false)
}

data class CheckResult(
    val check: SkillCheck,
    val roll: DiceRoll,
    val modifier: Int,
    val total: Int,
    val outcome: CheckOutcome,
    /** What the outcome grants or inflicts, if anything. */
    val effect: ActiveBoon?,
) {
    /** How a table would read it out: `14 + 2 = 16 vs 12`. */
    val summary: String get() = "${roll.total} ${if (modifier < 0) "-" else "+"} ${kotlin.math.abs(modifier)} = $total vs ${check.difficulty}"
}

/**
 * Resolves checks the way d20 tables do: dice plus the attribute's modifier
 * plus a proficiency that grows with level, against the difficulty. A lone
 * die showing its highest face always succeeds, and critically; showing a one
 * always fails, and critically.
 */
object Tabletop {

    fun attempt(check: SkillCheck, hero: HeroClassDefinition?, level: Int, random: Random): CheckResult {
        val dice = check.parsedDice ?: throw IllegalArgumentException("Check '${check.id}' has dice '${check.dice}' that do not parse")
        val roll = dice.roll(random)
        val modifier = modifierFor(hero, check.attribute) + proficiency(level)
        val total = roll.total + modifier
        val outcome = when {
            roll.isMaxNatural -> CheckOutcome.CRITICAL_SUCCESS
            roll.isMinNatural -> CheckOutcome.CRITICAL_FAILURE
            total >= check.difficulty -> CheckOutcome.SUCCESS
            else -> CheckOutcome.FAILURE
        }
        return CheckResult(check, roll, modifier, total, outcome, effectOf(check, outcome))
    }

    /** The d20 convention: every two points above ten is +1, below ten -1. */
    fun modifierFor(hero: HeroClassDefinition?, attribute: Attribute): Int {
        val score = when (attribute) {
            Attribute.STRENGTH -> hero?.strength
            Attribute.AGILITY -> hero?.agility
            Attribute.INSIGHT -> hero?.insight
        } ?: DEFAULT_SCORE
        return Math.floorDiv(score - DEFAULT_SCORE, 2)
    }

    /** +2 at level 1, one more every four levels. */
    fun proficiency(level: Int): Int = 2 + (level.coerceAtLeast(1) - 1) / 4

    /** A critical success lasts half as long again. */
    private fun effectOf(check: SkillCheck, outcome: CheckOutcome): ActiveBoon? = when (outcome) {
        CheckOutcome.CRITICAL_SUCCESS -> check.boon?.let { ActiveBoon(it, it.durationSeconds * CRITICAL_DURATION, check.id) }
        CheckOutcome.SUCCESS -> check.boon?.let { ActiveBoon(it, it.durationSeconds, check.id) }
        CheckOutcome.CRITICAL_FAILURE -> check.bane?.let { ActiveBoon(it, it.durationSeconds, check.id) }
        CheckOutcome.FAILURE -> null
    }

    private const val DEFAULT_SCORE = 10
    private const val CRITICAL_DURATION = 1.5f
}
