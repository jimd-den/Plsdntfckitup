package com.stratum.core.domain.stats

import com.stratum.core.domain.combat.CombatStats
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every number a character's build can change. Each one is read somewhere in
 * the engine; a stat nothing reads would be a line on a tooltip that lies.
 */
enum class Stat(val label: String, val isPercent: Boolean = false) {
    MAX_HEALTH("maximum health"),
    DAMAGE("damage"),
    ARMOUR("armour"),
    CRIT_CHANCE("critical strike chance", isPercent = true),
    CRIT_MULTIPLIER("critical strike multiplier", isPercent = true),
    ATTACK_SPEED("attack speed"),
    LIFE_STEAL("life steal", isPercent = true),
    /** Scoped to one damage type by [StatModifier.damageTypeId], or to all of them when that is null. */
    RESISTANCE("resistance", isPercent = true),
    SKILL_DAMAGE("skill damage"),
    AREA("area of effect"),
    COOLDOWN_RECOVERY("cooldown recovery speed"),
    RESOURCE_COST("skill cost"),
    MOVE_SPEED("movement speed"),
    MAX_RESOURCE("maximum resource"),
    EXPERIENCE_GAIN("experience gained"),
    ITEM_RARITY("rarity of items found"),
    ITEM_QUANTITY("quantity of items found"),
}

/**
 * How a modifier combines, the way Path of Exile teaches players to read
 * them: flat values add to the base; every "increased" adds into one sum
 * that multiplies once; every "more" multiplies on its own. So two 20%
 * increased make 40% increased, and two 20% more make 44% more -- which is
 * why a rare "more" is worth chasing and a pile of "increased" is not.
 */
enum class ModifierKind { FLAT, INCREASED, MORE }

data class StatModifier(
    val stat: Stat,
    val kind: ModifierKind,
    /** Flat: the amount, a share for percent stats (0.05 is 5%). Increased and more: a share (0.2 is 20%). */
    val value: Float,
    /** For [Stat.RESISTANCE]: the damage type, or null for all of them. */
    val damageTypeId: String? = null,
) {
    /** How a tooltip says it: "+12 maximum health", "15% increased damage", "30% less area of effect". */
    fun describe(): String {
        val scope = damageTypeId?.substringAfter(':')?.let { " to $it" }.orEmpty()
        return when (kind) {
            ModifierKind.FLAT -> {
                val amount = if (stat.isPercent) "${percent(value)}%" else number(value)
                "${if (value < 0) "-" else "+"}$amount ${stat.label}$scope"
            }
            ModifierKind.INCREASED -> "${percent(value)}% ${if (value < 0) "reduced" else "increased"} ${stat.label}$scope"
            ModifierKind.MORE -> "${percent(value)}% ${if (value < 0) "less" else "more"} ${stat.label}$scope"
        }
    }

    private fun percent(share: Float): String = number(abs(share) * 100f)

    private fun number(value: Float): String {
        // Rounded to tenths first: 0.15f * 100 is 15.000001, and a tooltip should say 15.
        val tenths = (abs(value) * 10f).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * Every modifier a character has, from every source, resolved into numbers.
 *
 * `(base + flat) * (1 + sum of increased) * product of (1 + each more)`.
 * The one formula for everything, so passives, gear, supports and difficulty
 * all stack the same way and a player can reason about all of them at once.
 */
class StatSheet(val modifiers: List<StatModifier>) {

    private val byStat = modifiers.groupBy { it.stat }

    fun flat(stat: Stat, damageTypeId: String? = null): Float = matching(stat, damageTypeId, ModifierKind.FLAT).sumOf { it.value.toDouble() }.toFloat()

    fun increased(stat: Stat, damageTypeId: String? = null): Float = matching(stat, damageTypeId, ModifierKind.INCREASED).sumOf { it.value.toDouble() }.toFloat()

    fun more(stat: Stat, damageTypeId: String? = null): Float =
        matching(stat, damageTypeId, ModifierKind.MORE).fold(1f) { product, modifier -> product * (1f + modifier.value) }

    /** [base] with every modifier of [stat] applied. Never negative. */
    fun apply(stat: Stat, base: Float, damageTypeId: String? = null): Float =
        ((base + flat(stat, damageTypeId)) * (1f + increased(stat, damageTypeId)) * more(stat, damageTypeId)).coerceAtLeast(0f)

    /** For stats that scale something else, such as item rarity: 1 with nothing, 1.3 with 30% increased. */
    fun multiplier(stat: Stat): Float = apply(stat, 1f)

    /** Combat stats with these modifiers applied on top; [damageTypeIds] are the types an unscoped resistance covers. */
    fun applyTo(stats: CombatStats, damageTypeIds: Collection<String> = emptyList()): CombatStats {
        if (modifiers.isEmpty()) return stats
        val resistanceTypes = stats.resistances.keys + damageTypeIds + modifiers.mapNotNull { it.damageTypeId }
        return stats.copy(
            maxHealth = apply(Stat.MAX_HEALTH, stats.maxHealth.toFloat()).roundToInt().coerceAtLeast(1),
            attackPower = apply(Stat.DAMAGE, stats.attackPower.toFloat()).roundToInt(),
            armour = apply(Stat.ARMOUR, stats.armour.toFloat()).roundToInt(),
            critChance = apply(Stat.CRIT_CHANCE, stats.critChance).coerceAtMost(1f),
            critMultiplier = apply(Stat.CRIT_MULTIPLIER, stats.critMultiplier),
            attackSpeed = apply(Stat.ATTACK_SPEED, stats.attackSpeed).coerceAtLeast(MIN_ATTACK_SPEED),
            lifeSteal = apply(Stat.LIFE_STEAL, stats.lifeSteal).coerceAtMost(1f),
            resistances = resistanceTypes.associateWith { type -> resistanceFor(stats.resistances[type] ?: 0f, type) }.filterValues { it != 0f },
        )
    }

    /** Resistance flats can be negative, so this one is not floored at zero. */
    private fun resistanceFor(base: Float, type: String): Float =
        (base + flat(Stat.RESISTANCE, type)) * (1f + increased(Stat.RESISTANCE, type)) * more(Stat.RESISTANCE, type)

    /** A typed query matches modifiers for that type and unscoped ones; an untyped query matches only unscoped ones. */
    private fun matching(stat: Stat, damageTypeId: String?, kind: ModifierKind): List<StatModifier> =
        byStat[stat].orEmpty().filter { it.kind == kind && (it.damageTypeId == null || it.damageTypeId == damageTypeId) }

    operator fun plus(other: List<StatModifier>): StatSheet = StatSheet(modifiers + other)

    companion object {
        val EMPTY = StatSheet(emptyList())
        private const val MIN_ATTACK_SPEED = 0.1f
    }
}
