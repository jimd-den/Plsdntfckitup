package com.stratum.plugins.schema

import com.stratum.core.domain.combat.Attribute
import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.combat.Condition
import com.stratum.core.domain.combat.ConditionalModifier
import com.stratum.core.domain.combat.FlaskDefinition
import com.stratum.core.domain.combat.Keystone
import com.stratum.core.domain.combat.TraitDefinition
import com.stratum.core.domain.combat.TriggerDefinition
import com.stratum.core.domain.combat.TriggerEvent
import com.stratum.core.domain.importing.ImportException
import com.stratum.core.domain.stats.ModifierKind
import com.stratum.core.domain.stats.Stat
import com.stratum.core.domain.stats.StatModifier
import com.stratum.core.domain.status.StackingRule
import com.stratum.core.domain.status.StatusBehaviour
import com.stratum.core.domain.status.StatusDefinition
import kotlinx.serialization.Serializable

// The build-breaking half of a pack: statuses, traits (keystones, triggers,
// conditional modifiers, conversion), flasks, and the combat caps a world's
// rules may lift.

private val STATUS = StatusDefinition(id = "", name = "")

@Serializable
internal data class StatusSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val behaviours: List<BehaviourSchema> = emptyList(),
    val durationSeconds: Float = STATUS.durationSeconds,
    val stacking: String = SchemaValues.name(STATUS.stacking),
    val maxStacks: Int = STATUS.maxStacks,
    val debuff: Boolean = STATUS.isDebuff,
    val color: String = SchemaValues.color(STATUS.color),
    val symbol: String = STATUS.symbol,
    val tags: List<String> = emptyList(),
) {
    fun toDomain() = StatusDefinition(
        id, name, description, behaviours.map { it.toDomain("status '$id'") }, durationSeconds,
        SchemaValues.enum<StackingRule>(stacking, "status '$id' stacking"), maxStacks, debuff, SchemaValues.color(color, "status '$id' color"),
        symbol, tags.toSet(),
    )

    companion object {
        fun of(s: StatusDefinition) = StatusSchema(
            s.id, s.name, s.description, s.behaviours.map(BehaviourSchema::of), s.durationSeconds, SchemaValues.name(s.stacking), s.maxStacks,
            s.isDebuff, SchemaValues.color(s.color), s.symbol, s.tags.toList(),
        )
    }
}

/** A behaviour, flat with a `type`: `dot`, `slow`, `stun`, `damage_taken`, `modifiers` or `recover`. */
@Serializable
internal data class BehaviourSchema(
    val type: String,
    val damageType: String? = null,
    val perSecond: Float = 0f,
    val hitShare: Float = 0f,
    val amount: Float = 0f,
    val maxShare: Float = 0f,
    val modifiers: List<ModifierSchema> = emptyList(),
) {
    fun toDomain(owner: String): StatusBehaviour = when (type.trim().lowercase()) {
        "dot" -> StatusBehaviour.DamageOverTime(damageType ?: throw ImportException("$owner dot needs 'damageType'"), perSecond, hitShare)
        "slow" -> StatusBehaviour.Slow(amount)
        "stun" -> StatusBehaviour.Stun
        "damage_taken" -> StatusBehaviour.DamageTaken(amount, damageType)
        "modifiers" -> StatusBehaviour.Modifiers(modifiers.map { it.toDomain(owner) })
        "recover" -> StatusBehaviour.Recover(perSecond, maxShare)
        else -> throw ImportException("$owner behaviour: '$type' is not one of dot, slow, stun, damage_taken, modifiers, recover")
    }

    companion object {
        fun of(b: StatusBehaviour): BehaviourSchema = when (b) {
            is StatusBehaviour.DamageOverTime -> BehaviourSchema("dot", damageType = b.damageTypeId, perSecond = b.perSecond, hitShare = b.hitShare)
            is StatusBehaviour.Slow -> BehaviourSchema("slow", amount = b.amount)
            StatusBehaviour.Stun -> BehaviourSchema("stun")
            is StatusBehaviour.DamageTaken -> BehaviourSchema("damage_taken", damageType = b.damageTypeId, amount = b.amount)
            is StatusBehaviour.Modifiers -> BehaviourSchema("modifiers", modifiers = b.modifiers.map(ModifierSchema::of))
            is StatusBehaviour.Recover -> BehaviourSchema("recover", perSecond = b.perSecond, maxShare = b.maxShare)
        }
    }
}

private val TRIGGER = TriggerDefinition(event = TriggerEvent.ON_HIT)

@Serializable
internal data class TriggerSchema(
    val on: String,
    val chance: Float = TRIGGER.chance,
    val cooldownSeconds: Float = TRIGGER.cooldownSeconds,
    val cast: String? = null,
    val status: String? = null,
    val statusOnSelf: Boolean = TRIGGER.statusOnSelf,
    val requiresTags: List<String> = emptyList(),
    val lowLife: Float = TRIGGER.lowLifeThreshold,
) {
    fun toDomain(owner: String) = TriggerDefinition(
        SchemaValues.enum<TriggerEvent>(normalise(on), "$owner trigger 'on'"), chance, cooldownSeconds, cast, status, statusOnSelf,
        requiresTags.toSet(), lowLife,
    )

    companion object {
        fun of(t: TriggerDefinition) = TriggerSchema(
            SchemaValues.name(t.event).removePrefix("on_"), t.chance, t.cooldownSeconds, t.castSkillId, t.applyStatusId, t.statusOnSelf,
            t.requiresTags.toList(), t.lowLifeThreshold,
        )

        /** Authors write `hit`, `crit`, `kill`; the enum says `on_hit`. Both are accepted. */
        private fun normalise(on: String): String = on.trim().lowercase().let { if (it.startsWith("on_")) it else "on_$it" }
    }
}

/**
 * A modifier with a condition, flat: `when` is `full_life`, `low_life`,
 * `target_has`, `self_has`, `skill_tag` or `per`, beside the modifier's own
 * `stat`, `kind` and `value`.
 */
@Serializable
internal data class ConditionalSchema(
    val `when`: String,
    val threshold: Float = Condition.LOW_LIFE,
    val status: String? = null,
    val tag: String? = null,
    val attribute: String? = null,
    val per: Int = 1,
    val stat: String,
    val kind: String = SchemaValues.name(ModifierKind.INCREASED),
    val value: Float,
    val damageType: String? = null,
) {
    fun toDomain(owner: String): ConditionalModifier {
        fun need(v: String?, field: String) = v ?: throw ImportException("$owner condition '$`when`' needs '$field'")
        val condition = when (`when`.trim().lowercase()) {
            "full_life" -> Condition.FullLife
            "low_life" -> Condition.LowLife(threshold)
            "target_has" -> Condition.TargetHas(need(status, "status"))
            "self_has" -> Condition.SelfHas(need(status, "status"))
            "skill_tag" -> Condition.SkillTagged(need(tag, "tag"))
            "per" -> Condition.Per(SchemaValues.enum<Attribute>(need(attribute, "attribute"), "$owner condition attribute"), per)
            else -> throw ImportException("$owner condition: '$`when`' is not one of full_life, low_life, target_has, self_has, skill_tag, per")
        }
        val modifier = StatModifier(SchemaValues.enum<Stat>(stat, "$owner stat"), SchemaValues.enum<ModifierKind>(kind, "$owner modifier kind"), value, damageType)
        return ConditionalModifier(condition, modifier)
    }

    companion object {
        fun of(c: ConditionalModifier): ConditionalSchema {
            val m = c.modifier
            val base = ConditionalSchema("full_life", stat = SchemaValues.name(m.stat), kind = SchemaValues.name(m.kind), value = m.value, damageType = m.damageTypeId)
            return when (val condition = c.condition) {
                Condition.FullLife -> base
                is Condition.LowLife -> base.copy(`when` = "low_life", threshold = condition.threshold)
                is Condition.TargetHas -> base.copy(`when` = "target_has", status = condition.statusOrTag)
                is Condition.SelfHas -> base.copy(`when` = "self_has", status = condition.statusOrTag)
                is Condition.SkillTagged -> base.copy(`when` = "skill_tag", tag = condition.tag)
                is Condition.Per -> base.copy(`when` = "per", attribute = SchemaValues.name(condition.attribute), per = condition.per)
            }
        }
    }
}

@Serializable
internal data class TraitSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val modifiers: List<ModifierSchema> = emptyList(),
    val conditional: List<ConditionalSchema> = emptyList(),
    /** Conversions and, with `extra: true`, "gain as extra". */
    val conversions: List<ConversionSchema> = emptyList(),
    val triggers: List<TriggerSchema> = emptyList(),
    val keystones: List<String> = emptyList(),
) {
    fun toDomain(): TraitDefinition {
        val owner = "trait '$id'"
        return TraitDefinition(
            id, name, description, modifiers.map { it.toDomain(owner) }, conditional.map { it.toDomain(owner) },
            conversions.filterNot { it.extra }.map { it.toConversion() }, conversions.filter { it.extra }.map { it.toExtra() },
            triggers.map { it.toDomain(owner) }, keystones.mapTo(LinkedHashSet()) { SchemaValues.enum<Keystone>(it, "$owner keystone") },
        )
    }

    companion object {
        fun of(t: TraitDefinition) = TraitSchema(
            t.id, t.name, t.description, t.modifiers.map(ModifierSchema::of), t.conditional.map(ConditionalSchema::of),
            t.conversions.map(ConversionSchema::of) + t.extraDamage.map(ConversionSchema::of), t.triggers.map(TriggerSchema::of),
            t.keystones.map(SchemaValues::name),
        )
    }
}

private val FLASK = FlaskDefinition(id = "", name = "")

@Serializable
internal data class FlaskSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val maxCharges: Int = FLASK.maxCharges,
    val chargesPerUse: Int = FLASK.chargesPerUse,
    val chargesPerKill: Int = FLASK.chargesPerKill,
    val life: Int = FLASK.life,
    val lifeShare: Float = FLASK.lifeShare,
    val resource: Int = FLASK.resource,
    val recoverySeconds: Float = FLASK.recoverySeconds,
    val status: String? = null,
    val cleanses: Boolean = FLASK.cleanses,
    val color: String = SchemaValues.color(FLASK.color),
    val glyph: String = FLASK.glyph,
) {
    fun toDomain() = FlaskDefinition(
        id, name, description, maxCharges, chargesPerUse, chargesPerKill, life, lifeShare, resource, recoverySeconds, status, cleanses,
        SchemaValues.color(color, "flask '$id' color"), glyph,
    )

    companion object {
        fun of(f: FlaskDefinition) = FlaskSchema(
            f.id, f.name, f.description, f.maxCharges, f.chargesPerUse, f.chargesPerKill, f.life, f.lifeShare, f.resource, f.recoverySeconds,
            f.statusId, f.cleanses, SchemaValues.color(f.color), f.glyph,
        )
    }
}

private val CAPS = CombatRules()

/** The combat caps a pack's suggested rules may lift, for a world built to be broken. */
@Serializable
internal data class CombatRulesSchema(
    val resistanceCap: Float = CAPS.resistanceCap,
    val resistanceHardCap: Float = CAPS.resistanceHardCap,
    val minResistance: Float = CAPS.minResistance,
    val maxEvadeChance: Float = CAPS.maxEvadeChance,
    val maxBlockChance: Float = CAPS.maxBlockChance,
    val maxArmourReduction: Float = CAPS.maxArmourReduction,
    val cooldownFloor: Float = CAPS.cooldownFloor,
    val maxLeechRate: Float = CAPS.maxLeechRate,
    val critChanceCap: Float = CAPS.critChanceCap,
    val triggerDepth: Int = CAPS.triggerDepth,
    val triggerCooldownFloor: Float = CAPS.triggerCooldownFloor,
    val triggerBudget: Int = CAPS.triggerBudget,
    val maxStatusStacks: Int = CAPS.maxStatusStacks,
) {
    fun toDomain() = CombatRules(
        resistanceCap, resistanceHardCap, minResistance, maxEvadeChance, maxBlockChance, maxArmourReduction, cooldownFloor, maxLeechRate,
        critChanceCap, triggerDepth, triggerCooldownFloor, triggerBudget, maxStatusStacks,
    )

    companion object {
        fun of(r: CombatRules) = CombatRulesSchema(
            r.resistanceCap, r.resistanceHardCap, r.minResistance, r.maxEvadeChance, r.maxBlockChance, r.maxArmourReduction, r.cooldownFloor,
            r.maxLeechRate, r.critChanceCap, r.triggerDepth, r.triggerCooldownFloor, r.triggerBudget, r.maxStatusStacks,
        )
    }
}
