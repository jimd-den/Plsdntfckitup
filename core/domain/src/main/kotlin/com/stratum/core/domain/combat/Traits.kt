package com.stratum.core.domain.combat

import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.status.StatusBook
import com.stratum.core.domain.status.StatusSet
import kotlin.math.floor

/**
 * A rule change, not a number: the thing a build is named after.
 *
 * Each is a deliberate break in one of the engine's assumptions, paired in
 * content with a price ("cannot crit" with "much more damage"). The engine
 * knows what each one means; a pack decides where they are found and what
 * they cost.
 */
enum class Keystone(val label: String) {
    /** Leech lands at once instead of trickling in at the world's leech rate. */
    INSTANT_LEECH("Life leech is instant"),

    /** Never crits, whatever the chance says. */
    CANNOT_CRIT("Cannot deal critical strikes"),

    /** Skills cost life instead of the class's resource. */
    LIFE_PAYS_COSTS("Skills cost life instead of resource"),

    /** Every critical strike inflicts the ailments its damage types carry. */
    CRITS_INFLICT_AILMENTS("Critical strikes always inflict ailments"),

    /** Cannot be stunned or slowed. */
    UNSHAKEABLE("Cannot be stunned or slowed"),

    /** No life regeneration of any kind: what a leech build pays with. */
    NO_REGENERATION("Cannot regenerate life"),
}

/** The attributes a "per" condition can count. Classes carry the first three; level is the character's. */
enum class Attribute { STRENGTH, AGILITY, INSIGHT, LEVEL }

/** When a [ConditionalModifier] applies. */
sealed interface Condition {
    /** At full life. Glass-cannon territory: the first hit switches it off. */
    data object FullLife : Condition

    /** At or below [threshold] of maximum life. */
    data class LowLife(val threshold: Float = LOW_LIFE) : Condition

    /** The target carries a status with this id or tag: "against burning enemies". */
    data class TargetHas(val statusOrTag: String) : Condition

    /** The one acting carries a status with this id or tag: "while frenzied". */
    data class SelfHas(val statusOrTag: String) : Condition

    /** The skill in use has this tag: "with projectile skills". */
    data class SkillTagged(val tag: String) : Condition

    /** Scales rather than switches: the modifier counts once for every [per] points of [attribute]. */
    data class Per(val attribute: Attribute, val per: Int) : Condition {
        init {
            require(per >= 1) { "a 'per' condition needs a step of at least 1, not $per" }
        }
    }

    companion object {
        const val LOW_LIFE = 0.35f
    }
}

/** Everything a condition can ask about the moment it is checked. */
data class ConditionContext(
    val lifeFraction: Float = 1f,
    val selfStatuses: StatusSet = StatusSet.EMPTY,
    val targetStatuses: StatusSet? = null,
    val attributes: Map<Attribute, Int> = emptyMap(),
    val skillTags: Set<String> = emptySet(),
    val book: StatusBook = StatusBook.EMPTY,
)

data class ConditionalModifier(val condition: Condition, val modifier: StatModifier) {

    /** The modifier as it applies in [context], scaled for a "per", or null when the condition is not met. */
    fun resolve(context: ConditionContext): StatModifier? = when (val c = condition) {
        Condition.FullLife -> modifier.takeIf { context.lifeFraction >= FULL }
        is Condition.LowLife -> modifier.takeIf { context.lifeFraction <= c.threshold }
        is Condition.TargetHas -> modifier.takeIf { context.targetStatuses?.hasTag(c.statusOrTag, context.book) == true }
        is Condition.SelfHas -> modifier.takeIf { context.selfStatuses.hasTag(c.statusOrTag, context.book) }
        is Condition.SkillTagged -> modifier.takeIf { c.tag in context.skillTags }
        is Condition.Per -> {
            val times = floor((context.attributes[c.attribute] ?: 0).toFloat() / c.per)
            if (times <= 0f) null else modifier.copy(value = modifier.value * times)
        }
    }

    private companion object {
        /** Rounding in health maths should not switch "full life" off at 99.99%. */
        const val FULL = 0.999f
    }
}

/**
 * A named bundle of build-breaking parts a pack can hand out: from a class, a
 * passive node or a support. Numbers, numbers that depend on the moment, rule
 * changes, damage conversion and triggers, all in one place so a keystone is
 * one thing to write and one thing to read.
 */
data class TraitDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val modifiers: List<StatModifier> = emptyList(),
    val conditional: List<ConditionalModifier> = emptyList(),
    val conversions: List<DamageConversion> = emptyList(),
    val extraDamage: List<ExtraDamage> = emptyList(),
    val triggers: List<TriggerDefinition> = emptyList(),
    val keystones: Set<Keystone> = emptySet(),
)

/** A trigger and the key its internal cooldown is tracked under. */
data class KeyedTrigger(val key: String, val trigger: TriggerDefinition)

/** Every trait a character has, merged: what the combat systems actually consult. */
data class CombatTraits(
    val modifiers: List<StatModifier> = emptyList(),
    val conditional: List<ConditionalModifier> = emptyList(),
    val conversions: List<DamageConversion> = emptyList(),
    val extraDamage: List<ExtraDamage> = emptyList(),
    val triggers: List<KeyedTrigger> = emptyList(),
    val keystones: Set<Keystone> = emptySet(),
) {
    fun has(keystone: Keystone): Boolean = keystone in keystones

    /** The conditional modifiers that hold in [context], resolved to plain modifiers. */
    fun conditionalIn(context: ConditionContext): List<StatModifier> = conditional.mapNotNull { it.resolve(context) }

    operator fun plus(other: CombatTraits): CombatTraits = CombatTraits(
        modifiers + other.modifiers, conditional + other.conditional, conversions + other.conversions,
        extraDamage + other.extraDamage, triggers + other.triggers, keystones + other.keystones,
    )

    companion object {
        val NONE = CombatTraits()

        fun of(traits: List<TraitDefinition>): CombatTraits = CombatTraits(
            modifiers = traits.flatMap { it.modifiers },
            conditional = traits.flatMap { it.conditional },
            conversions = traits.flatMap { it.conversions },
            extraDamage = traits.flatMap { it.extraDamage },
            triggers = traits.flatMap { trait -> trait.triggers.mapIndexed { i, t -> KeyedTrigger("${trait.id}#$i", t) } },
            keystones = traits.flatMapTo(LinkedHashSet()) { it.keystones },
        )
    }
}
