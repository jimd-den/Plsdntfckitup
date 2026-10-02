package com.stratum.core.domain.attack

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One phase of an attack: a delivery, spread by an emitter, bent by its
 * modulators, carrying its payload, with further attacks hung off its events.
 *
 * [element] is what it is made of (its damage and its colours); the payload's
 * own element is only where it usually comes from, so a frozen knockback or a
 * burning vacuum is as writable as the obvious pairings. [secondary] is an
 * optional second payload, landing alongside the first.
 */
data class SkillPhase(
    val delivery: Delivery,
    val geometry: Emitter,
    val modulators: List<Modulator> = emptyList(),
    val payload: Payload,
    val element: Element = payload.element,
    val secondary: Payload? = null,
    /** Chained attacks: what launches on each of this phase's events. */
    val subTriggers: Map<SkillEvent, ProceduralSkill> = emptyMap(),
) {
    init {
        require(modulators.size <= AttackGrammar.MAX_MODULATORS) { "a phase takes at most ${AttackGrammar.MAX_MODULATORS} modulators" }
        require(modulators.map { it.kind }.toSet().size == modulators.size) { "a modulator is listed twice" }
        require(subTriggers.size <= AttackGrammar.MAX_SUB_TRIGGERS) { "a phase launches at most ${AttackGrammar.MAX_SUB_TRIGGERS} chained attacks" }
        require(secondary != payload) { "the second payload repeats the first" }
    }

    fun has(kind: ModulatorKind): Boolean = modulators.any { it.kind == kind }

    fun level(kind: ModulatorKind): Int = modulators.firstOrNull { it.kind == kind }?.level ?: -1

    /** How deep its chains run: 1 for a phase with none. */
    val depth: Int get() = 1 + (subTriggers.values.maxOfOrNull { s -> s.rootSequence.maxOf { it.depth } } ?: 0)
}

/**
 * An attack, assembled: a sequence of phases cast one after another (a combo),
 * its cost and cooldown set by what it can do ([PowerBudget]), named from what
 * it is made of ([AttackNaming]) and looked at through its [look].
 *
 * Built by [AttackForge], which keeps the name, cost and look in step with the
 * parts; the data class itself only holds them.
 */
data class ProceduralSkill(
    val id: String,
    val name: String,
    val energyCost: Float,
    val cooldownSeconds: Float,
    val rootSequence: List<SkillPhase>,
    /** The style roll its look is drawn from, on top of what its parts dictate. */
    val seed: Long = 0L,
) {
    init {
        require(rootSequence.isNotEmpty()) { "an attack needs at least one phase" }
        require(rootSequence.size <= MAX_PHASES) { "an attack chains at most $MAX_PHASES phases in sequence" }
        require(depth <= AttackGrammar.MAX_DEPTH) { "chained attacks nest at most ${AttackGrammar.MAX_DEPTH} deep" }
    }

    val depth: Int get() = rootSequence.maxOf { it.depth }

    /** The first phase: what the attack is, at a glance. */
    val lead: SkillPhase get() = rootSequence.first()

    /** How it looks, from its parts and its [seed]. */
    val look: AttackLook get() = AttackLook.of(this)

    /** Every phase in it, chained ones included, depth first. */
    val allPhases: List<SkillPhase>
        get() = buildList {
            fun walk(p: SkillPhase) { add(p); p.subTriggers.values.forEach { s -> s.rootSequence.forEach(::walk) } }
            rootSequence.forEach(::walk)
        }

    companion object { const val MAX_PHASES = 3 }
}

/**
 * What an attack costs, from what it can do: the guardrail that keeps a
 * forked, echoing, chain-critting monster from being free.
 *
 * Every part has a weight; a phase's power is their product, and a chained
 * attack adds its own power scaled by how often its event happens. Cost and
 * cooldown grow faster than power, so stacking everything onto one button
 * costs more than spreading it across several.
 */
object PowerBudget {
    /** Relative power of one phase and everything chained from it. */
    fun power(phase: SkillPhase): Float {
        val delivery = when (phase.delivery.kind) {
            DeliveryKind.INSTANT_RAY -> 1.1f
            DeliveryKind.BALLISTIC -> 1f
            DeliveryKind.SURFACE_WAVE -> 1.15f
            DeliveryKind.IMPACT_FIELD -> 1.2f + 0.05f * phase.delivery.b
            DeliveryKind.TETHER -> 1.25f + 0.05f * phase.delivery.b
            DeliveryKind.ORBITAL -> 1.2f
        }
        val shape = when (phase.geometry.shape) {
            EmitterShape.SINGLE -> 1f
            EmitterShape.PIERCING_LINE -> 1.2f
            EmitterShape.NOVA, EmitterShape.RUPTURE_GRID -> 1.35f
            EmitterShape.CONE -> 1.25f
            EmitterShape.FAN, EmitterShape.HELIX -> 1.2f
            EmitterShape.CHAIN -> 1.3f
        }
        // More shots hit more, but each shares the attack's power: grows like the square root.
        val count = sqrt(phase.geometry.count.toFloat())
        val payload = payloadWeight(phase.payload) * (phase.secondary?.let { 1f + 0.6f * (payloadWeight(it) - 0.6f) } ?: 1f)
        val modulators = phase.modulators.fold(1f) { acc, m -> acc * (1f + modulatorWeight(m.kind) * (0.6f + 0.08f * m.level)) }
        val chained = phase.subTriggers.entries.sumOf { (event, skill) ->
            (eventRate(event) * skill.rootSequence.sumOf { power(it).toDouble() }).toDouble()
        }.toFloat()
        return delivery * shape * count * payload * modulators + chained
    }

    /** The whole attack's power: its phases in sequence. */
    fun power(skill: ProceduralSkill): Float = power(skill.rootSequence)

    fun power(sequence: List<SkillPhase>): Float = sequence.sumOf { power(it).toDouble() }.toFloat()

    /** Energy per cast. */
    fun energyCost(power: Float): Float = (6f + 7f * power.pow(1.15f)).let { (it * 2f).toInt() / 2f }

    /** Seconds between casts. Never below a beat, never past half a minute. */
    fun cooldown(power: Float): Float = min(30f, max(0.5f, 0.25f + 0.9f * power.pow(1.25f))).let { (it * 10f).toInt() / 10f }

    /** How many hits one cast is worth: what a compiled skill's damage is shared across. */
    fun shareOfPower(phase: SkillPhase): Float = 1.6f / sqrt(phase.geometry.count.toFloat()) / (1f + 0.15f * phase.modulators.size)

    private fun payloadWeight(p: Payload): Float = when (p) {
        Payload.STAGGER -> 0.8f
        Payload.KNOCKBACK -> 0.9f
        Payload.VACUUM, Payload.PULL -> 1.05f
        Payload.BURN, Payload.BRITTLE, Payload.CONDUCTIVE -> 1.1f
        Payload.FLASH_FREEZE -> 1.3f
        Payload.CRATER -> 1.15f
        Payload.RAISE_WALL -> 1.1f
        Payload.IGNITE_BRUSH -> 1.05f
        Payload.FREEZE_WATER -> 0.9f
    }

    private fun modulatorWeight(k: ModulatorKind): Float = when (k) {
        ModulatorKind.FORK, ModulatorKind.SPLIT, ModulatorKind.BLOOM -> 0.55f
        ModulatorKind.PIERCE, ModulatorKind.RICOCHET, ModulatorKind.BOOMERANG -> 0.4f
        ModulatorKind.MULTICAST -> 0.8f
        ModulatorKind.ECHO -> 0.6f
        ModulatorKind.HOMING -> 0.3f
        ModulatorKind.SIPHON, ModulatorKind.SHATTER, ModulatorKind.LINGER -> 0.35f
        ModulatorKind.VOXEL_DISRUPTION, ModulatorKind.GRAVITATE -> 0.35f
        ModulatorKind.ACCELERATE, ModulatorKind.SINE_WAVE, ModulatorKind.SPIRAL -> 0.12f
        // A delay buys the target time: it makes an attack weaker, not stronger.
        ModulatorKind.DELAY -> -0.1f
    }

    /** About how often an event happens per cast. */
    private fun eventRate(e: SkillEvent): Float = when (e) {
        SkillEvent.ON_CAST, SkillEvent.ON_EXPIRE -> 1f
        SkillEvent.ON_HIT -> 0.9f
        SkillEvent.ON_VOXEL_HIT -> 0.6f
        SkillEvent.ON_DASH -> 0.5f
        SkillEvent.ON_CRIT -> 0.3f
        SkillEvent.ON_KILL -> 0.25f
        SkillEvent.ON_BLOCK -> 0.2f
    }
}
