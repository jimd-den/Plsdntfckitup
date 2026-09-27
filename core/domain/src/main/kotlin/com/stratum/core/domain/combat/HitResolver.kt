package com.stratum.core.domain.combat

import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatSheet
import com.stratum.core.domain.status.StatusApplication
import com.stratum.core.domain.status.StatusBook
import com.stratum.core.domain.status.StatusSet
import kotlin.math.roundToInt

/**
 * The one hitting, as a hit needs it: resolved stats, plus the parts of a
 * build that are not stats -- conversion, the keystones, and the modifier
 * sheet the extended stats (penetration, ailment chance, typed damage) are
 * read from.
 */
data class HitAttacker(
    val stats: CombatStats,
    val sheet: StatSheet = StatSheet.EMPTY,
    val conversions: List<DamageConversion> = emptyList(),
    val extraDamage: List<ExtraDamage> = emptyList(),
    val keystones: Set<Keystone> = emptySet(),
    /** Attacks can be evaded; spells cannot. */
    val evadable: Boolean = true,
    val id: String? = null,
)

/** The one being hit, with what it is carrying. */
data class HitDefender(
    val stats: CombatStats,
    val sheet: StatSheet = StatSheet.EMPTY,
    val statuses: StatusSet = StatusSet.EMPTY,
)

/**
 * Every roll a hit needs, supplied by the caller so the resolver stays a pure
 * function. A roll below a chance succeeds; the defaults succeed at nothing,
 * which is what a test wants when it pins one roll and not the others.
 */
data class HitRolls(
    val crit: Float = 1f,
    val evade: Float = 1f,
    val block: Float = 1f,
    /** One roll per damage type in the hit, in type-id order. */
    val ailments: List<Float> = emptyList(),
) {
    fun ailment(index: Int): Float = ailments.getOrElse(index) { 1f }

    companion object {
        /** Everything drawn from [random], in a fixed order so a seed replays a fight exactly. */
        fun from(random: kotlin.random.Random): HitRolls =
            HitRolls(random.nextFloat(), random.nextFloat(), random.nextFloat(), List(AILMENT_ROLLS) { random.nextFloat() })

        private const val AILMENT_ROLLS = 6
    }
}

/**
 * Resolves one hit: evasion, block, conversion, crit, typed damage,
 * penetration, resistance within the world's caps, damage taken, armour,
 * leech and ailments -- in that order, and the same for everyone.
 *
 * Pure: inputs plus explicit rolls in, a [DamageResult] out. The engine
 * decides who hits whom; this decides only how much and with what.
 */
object HitResolver {

    fun resolve(
        attacker: HitAttacker,
        defender: HitDefender,
        /** Base damage by type, before anything else touches it. */
        damage: Map<String, Float>,
        rolls: HitRolls = HitRolls(),
        rules: CombatRules = CombatRules(),
        damageTypes: (String) -> DamageTypeDefinition? = { null },
        statuses: StatusBook = StatusBook.EMPTY,
    ): DamageResult {
        val primary = damage.maxByOrNull { it.value }?.key.orEmpty()
        if (attacker.evadable && rolls.evade < evadeChance(attacker, defender, rules)) {
            return DamageResult(0, primary, wasCritical = false, wasBlocked = false, wasEvaded = true)
        }
        if (rolls.block < blockChance(defender, rules)) {
            return DamageResult(0, primary, wasCritical = false, wasBlocked = true)
        }

        val converted = DamageConversions.apply(damage, attacker.conversions, attacker.extraDamage)
        val critical = Keystone.CANNOT_CRIT !in attacker.keystones &&
            rolls.crit < attacker.stats.critChance.coerceAtMost(rules.critChanceCap)
        val critScale = if (critical) attacker.stats.critMultiplier else 1f

        val afterResistance = converted.mapValues { (type, amount) ->
            val typed = amount * critScale * typedDamage(attacker.sheet, type)
            val resistance = effectiveResistance(type, attacker, defender, rules)
            typed * (1f - resistance) * damageTakenMultiplier(defender, type, statuses)
        }

        // Armour sees the armour-affected part of the hit as one blow, because
        // a hit is one impact however many elements it carries.
        val armoured = afterResistance.filterKeys { damageTypes(it)?.mitigatedByArmour ?: true }
        val reduction = armourReduction(defender.stats.armour, armoured.values.sum(), rules)
        val packets = afterResistance.mapValues { (type, amount) ->
            val mitigated = if (type in armoured) amount * (1f - reduction) else amount
            mitigated.coerceAtLeast(0f).roundToInt()
        }.filterValues { it > 0 }
        val total = packets.values.sum()
        val swallowed = total == 0 && converted.values.sum() > 0f

        return DamageResult(
            amount = total,
            damageTypeId = packets.maxByOrNull { it.value }?.key ?: primary,
            wasCritical = critical,
            wasBlocked = swallowed,
            healedAttacker = (total * attacker.stats.lifeSteal).roundToInt().coerceAtLeast(0),
            packets = packets,
            inflicted = ailments(attacker, packets, critical, rolls, damageTypes),
        )
    }

    /**
     * Damage over time as it reaches the defender: resisted and amplified,
     * never evaded, blocked, armoured or critical. Returned unrounded; the
     * caller accumulates fractions so a slow burn is not rounded away.
     */
    fun dot(
        amount: Float,
        damageTypeId: String,
        defender: HitDefender,
        rules: CombatRules = CombatRules(),
        statuses: StatusBook = StatusBook.EMPTY,
    ): Float {
        val resistance = cappedResistance(damageTypeId, defender, rules)
        return (amount * (1f - resistance) * damageTakenMultiplier(defender, damageTypeId, statuses)).coerceAtLeast(0f)
    }

    fun evadeChance(attacker: HitAttacker, defender: HitDefender, rules: CombatRules): Float {
        val evasion = defender.sheet.apply(Stat.EVASION, defender.stats.evasion.toFloat())
        if (evasion <= 0f) return 0f
        val accuracy = attacker.sheet.apply(Stat.ACCURACY, attacker.stats.accuracy.toFloat()).coerceAtLeast(1f)
        return (evasion / (evasion + accuracy)).coerceIn(0f, rules.maxEvadeChance)
    }

    fun blockChance(defender: HitDefender, rules: CombatRules): Float =
        defender.sheet.apply(Stat.BLOCK_CHANCE, defender.stats.blockChance).coerceIn(0f, rules.maxBlockChance)

    /**
     * The share of a hit armour takes off: `armour / (armour + 5 x hit)`.
     *
     * Flat armour, which this replaced, made a big enough number immune to
     * every small hit in the game -- a wall no sandbox cap could reason about,
     * since it was not a share of anything. The curve keeps the trade-off that
     * made flat armour interesting (it shrugs off a swarm's pecks and barely
     * dents a boss's slam) while never reaching immunity on its own, so it
     * can be capped like every other defence.
     */
    fun armourReduction(armour: Int, hit: Float, rules: CombatRules = CombatRules()): Float {
        if (armour <= 0 || hit <= 0f) return 0f
        return (armour / (armour + ARMOUR_FACTOR * hit)).coerceIn(0f, rules.maxArmourReduction)
    }

    /** The defender's resistance within the world's caps, raised by its own "maximum resistance". */
    fun cappedResistance(type: String, defender: HitDefender, rules: CombatRules): Float {
        // Signed: a negative "maximum resistance" is how a curse lowers the ceiling.
        val cap = (rules.resistanceCap + signed(defender.sheet, Stat.MAX_RESISTANCE, type)).coerceAtMost(rules.resistanceHardCap)
        return defender.stats.rawResistanceTo(type).coerceIn(rules.minResistance, cap.coerceAtLeast(rules.minResistance))
    }

    /** Resistance after the attacker's penetration, which can bring it to zero but not below. */
    private fun effectiveResistance(type: String, attacker: HitAttacker, defender: HitDefender, rules: CombatRules): Float {
        val resistance = cappedResistance(type, defender, rules)
        val penetration = signed(attacker.sheet, Stat.PENETRATION, type)
        if (penetration <= 0f || resistance <= 0f) return resistance
        return (resistance - penetration).coerceAtLeast(0f)
    }

    /** A stat with no base, applied without the floor at zero that [StatSheet.apply] puts under everything. */
    private fun signed(sheet: StatSheet, stat: Stat, type: String): Float =
        sheet.flat(stat, type) * (1f + sheet.increased(stat, type)) * sheet.more(stat, type)

    /** "Increased fire damage": modifiers scoped to exactly this type, on top of what attack power already holds. */
    private fun typedDamage(sheet: StatSheet, type: String): Float {
        val scoped = sheet.modifiers.filter { it.stat == Stat.DAMAGE && it.damageTypeId == type }
        if (scoped.isEmpty()) return 1f
        val increased = scoped.filter { it.kind == ModifierKind.INCREASED }.sumOf { it.value.toDouble() }.toFloat()
        val more = scoped.filter { it.kind == ModifierKind.MORE }.fold(1f) { product, m -> product * (1f + m.value) }
        return ((1f + increased) * more).coerceAtLeast(0f)
    }

    private fun damageTakenMultiplier(defender: HitDefender, type: String, statuses: StatusBook): Float {
        val increased = defender.sheet.increased(Stat.DAMAGE_TAKEN, type) + defender.statuses.damageTakenShare(type, statuses)
        return ((1f + increased) * defender.sheet.more(Stat.DAMAGE_TAKEN, type)).coerceAtLeast(0f)
    }

    private fun ailments(
        attacker: HitAttacker,
        packets: Map<String, Int>,
        critical: Boolean,
        rolls: HitRolls,
        damageTypes: (String) -> DamageTypeDefinition?,
    ): List<StatusApplication> {
        val guaranteed = critical && Keystone.CRITS_INFLICT_AILMENTS in attacker.keystones
        val bonus = attacker.sheet.apply(Stat.AILMENT_CHANCE, 0f)
        val dotScale = attacker.sheet.apply(Stat.DAMAGE_OVER_TIME, 1f)
        val durationScale = attacker.sheet.multiplier(Stat.DURATION)
        return packets.keys.sorted().mapIndexedNotNull { index, type ->
            val definition = damageTypes(type) ?: return@mapIndexedNotNull null
            val status = definition.ailmentStatusId ?: return@mapIndexedNotNull null
            val chance = (definition.ailmentChance + bonus).coerceIn(0f, 1f)
            if (!guaranteed && rolls.ailment(index) >= chance) return@mapIndexedNotNull null
            StatusApplication(status, potency = packets.getValue(type) * dotScale, durationScale = durationScale, sourceId = attacker.id)
        }
    }

    /** How many times a hit's size armour needs to halve it: 5 is Path of Exile's modern number. */
    const val ARMOUR_FACTOR = 5f
}
