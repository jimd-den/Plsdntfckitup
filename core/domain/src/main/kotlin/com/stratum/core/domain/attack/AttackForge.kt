package com.stratum.core.domain.attack

import com.stratum.core.domain.actor.CombatRole
import java.math.BigInteger
import java.util.Base64
import kotlin.random.Random

/**
 * The pieces a player finds and slots into the Forge of Will.
 *
 * - A [Core] glyph is a delivery: how the energy moves.
 * - [Catalyst]s give it a shape, an element or a payload.
 * - [Resonator]s bend it with a modulator, or hang a further attack off one of
 *   its events.
 *
 * They drop from regional bosses, turn up while foraging and are cut from
 * crystal seams; the forge combines whatever is slotted.
 */
sealed interface ForgePiece {
    val label: String

    data class Core(val delivery: Delivery) : ForgePiece {
        override val label: String get() = delivery.kind.label
    }

    sealed interface Catalyst : ForgePiece {
        data class Shape(val emitter: Emitter) : Catalyst { override val label: String get() = emitter.shape.label }
        data class Essence(val element: Element) : Catalyst { override val label: String get() = element.label }
        data class Charge(val payload: Payload) : Catalyst { override val label: String get() = payload.label }
    }

    sealed interface Resonator : ForgePiece {
        data class Modulate(val modulator: Modulator) : Resonator { override val label: String get() = modulator.kind.label }
        data class Chain(val event: SkillEvent, val skill: ProceduralSkill) : Resonator { override val label: String get() = "${event.label}: ${skill.name}" }
    }
}

/**
 * Makes attacks: from forge pieces, from a seed, for a monster's role, and by
 * mutating one already made. Everything it returns has its name, cost and
 * cooldown read off its parts, so they can never disagree.
 */
object AttackForge {

    /** An attack from its [phases] and style [seed], named, costed and given its id. */
    fun build(phases: List<SkillPhase>, seed: Long): ProceduralSkill {
        val power = PowerBudget.power(phases)
        val draft = ProceduralSkill("", "", 0f, 0f, phases, seed)
        val code = AttackCode.encode(draft)
        return draft.copy(
            id = "forge:" + code.hashCode().toUInt().toString(16) + java.lang.Long.toHexString(seed and 0xFFFFFF),
            name = AttackNaming.name(draft),
            energyCost = PowerBudget.energyCost(power),
            cooldownSeconds = PowerBudget.cooldown(power),
        )
    }

    fun build(phase: SkillPhase, seed: Long): ProceduralSkill = build(listOf(phase), seed)

    /**
     * What the forge makes of its slots: the [core]'s delivery, shaped,
     * charged and coloured by the [catalysts], bent and chained by the
     * [resonators]. Anything left unslotted falls back to the plainest choice.
     */
    fun compose(core: ForgePiece.Core, catalysts: List<ForgePiece.Catalyst>, resonators: List<ForgePiece.Resonator>, seed: Long): ProceduralSkill {
        val shape = catalysts.filterIsInstance<ForgePiece.Catalyst.Shape>().firstOrNull()?.emitter ?: Emitter(EmitterShape.SINGLE)
        val charges = catalysts.filterIsInstance<ForgePiece.Catalyst.Charge>().map { it.payload }.distinct()
        val essence = catalysts.filterIsInstance<ForgePiece.Catalyst.Essence>().firstOrNull()?.element
        val payload = charges.firstOrNull() ?: essence?.let(::defaultPayload) ?: Payload.STAGGER
        val modulators = resonators.filterIsInstance<ForgePiece.Resonator.Modulate>().map { it.modulator }.distinctBy { it.kind }.take(AttackGrammar.MAX_MODULATORS)
        val chains = resonators.filterIsInstance<ForgePiece.Resonator.Chain>().associate { it.event to it.skill }.entries.take(AttackGrammar.MAX_SUB_TRIGGERS).associate { it.toPair() }
        val phase = SkillPhase(
            delivery = core.delivery, geometry = shape, modulators = modulators, payload = payload,
            element = essence ?: payload.element, secondary = charges.getOrNull(1), subTriggers = chains,
        )
        return build(phase, seed)
    }

    /** A random attack. With a [role], one suited to how that monster fights. */
    fun roll(seed: Long, role: CombatRole? = null, depth: Int = 0): ProceduralSkill {
        val r = Random(seed)
        val phases = List(if (depth == 0 && r.nextFloat() < 0.15f) 2 else 1) { rollPhase(r, role, depth) }
        return build(phases, seed)
    }

    private fun rollPhase(r: Random, role: CombatRole?, depth: Int): SkillPhase {
        val delivery = Delivery(pick(r, deliveriesFor(role)), r.nextInt(AttackGrammar.STEPS), r.nextInt(AttackGrammar.STEPS))
        val shape = pick(r, shapesFor(role, delivery.kind))
        val count = when (shape) {
            EmitterShape.SINGLE, EmitterShape.PIERCING_LINE -> 1 + if (r.nextFloat() < 0.2f) r.nextInt(3) else 0
            EmitterShape.NOVA, EmitterShape.CONE -> 1
            else -> 2 + r.nextInt(Emitter.MAX_COUNT - 1)
        }
        val payload = pick(r, payloadsFor(role))
        // Usually the payload's own element; a third of the time an unexpected pairing.
        val element = if (r.nextFloat() < 0.33f) Element.entries[r.nextInt(Element.entries.size)] else payload.element
        val secondary = if (r.nextFloat() < 0.3f) Payload.entries.filter { it != payload }.let { it[r.nextInt(it.size)] } else null
        val modulators = ModulatorKind.entries.shuffled(r).take(r.nextInt(AttackGrammar.MAX_MODULATORS + 1)).map { Modulator(it, r.nextInt(AttackGrammar.STEPS)) }
        val chains = if (depth + 1 < AttackGrammar.MAX_DEPTH && r.nextFloat() < (if (depth == 0) 0.35f else 0.12f)) {
            val event = SkillEvent.entries[r.nextInt(SkillEvent.entries.size)]
            mapOf(event to roll(r.nextLong(), role, depth + 1))
        } else emptyMap()
        return SkillPhase(delivery, Emitter(shape, count, r.nextInt(AttackGrammar.STEPS)), modulators, payload, element, secondary, chains)
    }

    /** [skill] with one part changed: the forge's mutation. */
    fun mutate(skill: ProceduralSkill, seed: Long): ProceduralSkill {
        val r = Random(seed)
        val p = skill.lead
        // Always a different value than the one it replaces, so a mutation is never a no-op.
        fun <T> other(xs: List<T>, now: T): T = xs.filter { it != now }.let { it[r.nextInt(it.size)] }
        val steps = (0 until AttackGrammar.STEPS).toList()
        val next = when (r.nextInt(7)) {
            0 -> p.copy(delivery = p.delivery.copy(kind = other(DeliveryKind.entries, p.delivery.kind)))
            1 -> p.copy(geometry = p.geometry.copy(shape = other(EmitterShape.entries, p.geometry.shape)))
            2 -> p.copy(element = other(Element.entries, p.element))
            3 -> Payload.entries.filter { it != p.payload && it != p.secondary }.let { p.copy(payload = it[r.nextInt(it.size)]) }
            4 -> {
                val kind = ModulatorKind.entries.filter { !p.has(it) }[r.nextInt(ModulatorKind.entries.size - p.modulators.size)]
                p.copy(modulators = (p.modulators.drop(if (p.modulators.size >= AttackGrammar.MAX_MODULATORS) 1 else 0) + Modulator(kind, r.nextInt(AttackGrammar.STEPS))))
            }
            5 -> p.copy(delivery = p.delivery.copy(a = other(steps, p.delivery.a)))
            else -> p.copy(geometry = p.geometry.copy(count = other((1..Emitter.MAX_COUNT).toList(), p.geometry.count)))
        }
        return build(listOf(next) + skill.rootSequence.drop(1), skill.seed xor (seed * 0x9E3779B97F4A7C15uL.toLong()))
    }

    private fun deliveriesFor(role: CombatRole?): List<Pair<DeliveryKind, Int>> = when (role) {
        CombatRole.RANGED -> listOf(DeliveryKind.BALLISTIC to 4, DeliveryKind.INSTANT_RAY to 3, DeliveryKind.TETHER to 2, DeliveryKind.SURFACE_WAVE to 1)
        CombatRole.SUPPORT -> listOf(DeliveryKind.IMPACT_FIELD to 4, DeliveryKind.ORBITAL to 2, DeliveryKind.TETHER to 2)
        CombatRole.BRUTE -> listOf(DeliveryKind.SURFACE_WAVE to 4, DeliveryKind.IMPACT_FIELD to 2, DeliveryKind.BALLISTIC to 1)
        CombatRole.SWARMER -> listOf(DeliveryKind.ORBITAL to 3, DeliveryKind.BALLISTIC to 2, DeliveryKind.INSTANT_RAY to 1)
        CombatRole.MELEE -> listOf(DeliveryKind.SURFACE_WAVE to 2, DeliveryKind.ORBITAL to 2, DeliveryKind.IMPACT_FIELD to 1, DeliveryKind.INSTANT_RAY to 1)
        null -> DeliveryKind.entries.map { it to 1 }
    }

    private fun shapesFor(role: CombatRole?, delivery: DeliveryKind): List<Pair<EmitterShape, Int>> = when {
        role == CombatRole.BRUTE -> listOf(EmitterShape.NOVA to 3, EmitterShape.CONE to 3, EmitterShape.RUPTURE_GRID to 2, EmitterShape.PIERCING_LINE to 1)
        role == CombatRole.RANGED -> listOf(EmitterShape.SINGLE to 3, EmitterShape.FAN to 3, EmitterShape.PIERCING_LINE to 2, EmitterShape.CHAIN to 1, EmitterShape.HELIX to 1)
        delivery == DeliveryKind.IMPACT_FIELD -> listOf(EmitterShape.NOVA to 2, EmitterShape.RUPTURE_GRID to 3, EmitterShape.SINGLE to 1)
        else -> EmitterShape.entries.map { it to 1 }
    }

    private fun payloadsFor(role: CombatRole?): List<Pair<Payload, Int>> = when (role) {
        CombatRole.BRUTE -> listOf(Payload.KNOCKBACK to 3, Payload.STAGGER to 3, Payload.CRATER to 3, Payload.RAISE_WALL to 1)
        CombatRole.SUPPORT -> listOf(Payload.BRITTLE to 3, Payload.PULL to 2, Payload.FLASH_FREEZE to 2, Payload.RAISE_WALL to 2, Payload.FREEZE_WATER to 1)
        CombatRole.SWARMER -> listOf(Payload.BURN to 2, Payload.CONDUCTIVE to 2, Payload.STAGGER to 1, Payload.VACUUM to 1)
        else -> Payload.entries.map { it to 1 }
    }

    private fun <T> pick(r: Random, xs: List<Pair<T, Int>>): T {
        var n = r.nextInt(xs.sumOf { it.second })
        for ((v, w) in xs) { if (n < w) return v; n -= w }
        return xs.first().first
    }

    private fun defaultPayload(e: Element): Payload = when (e) {
        Element.PHYSICAL -> Payload.STAGGER
        Element.FIRE -> Payload.BURN
        Element.FROST -> Payload.FLASH_FREEZE
        Element.STORM -> Payload.CONDUCTIVE
        Element.VENOM -> Payload.BURN
        Element.SPIRIT -> Payload.PULL
        Element.EARTH -> Payload.CRATER
        Element.SHADOW -> Payload.VACUUM
    }

    // ---- counting --------------------------------------------------------------------------------

    /**
     * How many different single phases there are. [steps] false counts only
     * the named choices (delivery, shape, element, payload pair, modulator
     * set); true also counts every stepped parameter.
     */
    fun phases(steps: Boolean = false): BigInteger {
        fun n(x: Int) = BigInteger.valueOf(x.toLong())
        val s = n(AttackGrammar.STEPS)
        val delivery = n(DeliveryKind.entries.size) * (if (steps) s * s else BigInteger.ONE)
        val emitter = n(EmitterShape.entries.size) * (if (steps) n(Emitter.MAX_COUNT) * s else BigInteger.ONE)
        val payloads = n(Payload.entries.size) * n(Payload.entries.size) // a first payload, then none or one of the other eleven
        val modulators = (0..AttackGrammar.MAX_MODULATORS).fold(BigInteger.ZERO) { sum, k ->
            sum + choose(ModulatorKind.entries.size, k) * (if (steps) s.pow(k) else BigInteger.ONE)
        }
        return delivery * emitter * n(Element.entries.size) * payloads * modulators
    }

    /**
     * How many attacks of one phase with at most one chained attack hung off
     * one of its events: phases x (1 + events x phases).
     */
    fun attacks(steps: Boolean = false): BigInteger {
        val p = phases(steps)
        return p * (BigInteger.ONE + BigInteger.valueOf(SkillEvent.entries.size.toLong()) * p)
    }

    private fun choose(n: Int, k: Int): BigInteger {
        var r = BigInteger.ONE
        for (i in 0 until k) r = r * BigInteger.valueOf((n - i).toLong()) / BigInteger.valueOf((i + 1).toLong())
        return r
    }
}

/**
 * An attack written down exactly, to share or keep: `ak1:` and its parts,
 * chained attacks and all.
 */
object AttackCode {
    const val PREFIX = "ak1:"

    fun isCode(text: String?): Boolean = text != null && text.trim().startsWith(PREFIX)

    fun encode(skill: ProceduralSkill): String {
        val out = java.io.ByteArrayOutputStream()
        writeSkill(out, skill)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** The attack in a code, rebuilt through the forge (so its name and cost are today's), or null. */
    fun decode(code: String): ProceduralSkill? = runCatching {
        val text = code.trim()
        if (!text.startsWith(PREFIX)) return null
        val bytes = Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
        Reader(bytes).readSkill()
    }.getOrNull()

    private fun writeSkill(out: java.io.ByteArrayOutputStream, s: ProceduralSkill) {
        for (k in 7 downTo 0) out.write((s.seed shr (k * 8)).toInt() and 0xFF)
        out.write(s.rootSequence.size)
        s.rootSequence.forEach { writePhase(out, it) }
    }

    private fun writePhase(out: java.io.ByteArrayOutputStream, p: SkillPhase) {
        out.write(p.delivery.kind.ordinal); out.write(p.delivery.a); out.write(p.delivery.b)
        out.write(p.geometry.shape.ordinal); out.write(p.geometry.count); out.write(p.geometry.spread)
        out.write(p.element.ordinal); out.write(p.payload.ordinal); out.write(p.secondary?.ordinal ?: 255)
        out.write(p.modulators.size); p.modulators.forEach { out.write(it.kind.ordinal); out.write(it.level) }
        out.write(p.subTriggers.size); p.subTriggers.forEach { (e, s) -> out.write(e.ordinal); writeSkill(out, s) }
    }

    private class Reader(val b: ByteArray) {
        var at = 0
        fun u(): Int = b[at++].toInt() and 0xFF

        fun readSkill(): ProceduralSkill {
            var seed = 0L
            repeat(8) { seed = (seed shl 8) or u().toLong() }
            val n = u()
            require(n in 1..ProceduralSkill.MAX_PHASES)
            return AttackForge.build(List(n) { readPhase() }, seed)
        }

        fun readPhase(): SkillPhase {
            val d = Delivery(DeliveryKind.entries[u()], u(), u())
            val g = Emitter(EmitterShape.entries[u()], u(), u())
            val element = Element.entries[u()]
            val payload = Payload.entries[u()]
            val second = u().let { if (it == 255) null else Payload.entries[it] }
            val mods = List(u()) { Modulator(ModulatorKind.entries[u()], u()) }
            val subs = List(u()) { SkillEvent.entries[u()] to readSkill() }.toMap()
            return SkillPhase(d, g, mods, payload, element, second, subs)
        }
    }
}
