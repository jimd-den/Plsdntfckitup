package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import com.stratum.core.domain.attack.AttackCode
import com.stratum.core.domain.attack.AttackForge
import com.stratum.core.domain.attack.Delivery
import com.stratum.core.domain.attack.DeliveryKind
import com.stratum.core.domain.attack.Element
import com.stratum.core.domain.attack.Emitter
import com.stratum.core.domain.attack.EmitterShape
import com.stratum.core.domain.attack.ForgePiece
import com.stratum.core.domain.attack.Modulator
import com.stratum.core.domain.attack.ModulatorKind
import com.stratum.core.domain.attack.Payload
import com.stratum.core.domain.attack.PowerBudget
import com.stratum.core.domain.attack.ProceduralSkill
import com.stratum.core.domain.attack.SkillEvent
import com.stratum.core.domain.attack.AttackGrammar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the forge's kept attacks live, as attack codes, and which are carried into play. */
interface AttackForgeStorage {
    fun kept(): List<String>
    fun equipped(): List<String>
    fun keep(code: String)
    fun forget(code: String)
    fun toggleEquipped(code: String)
}

/** The forge's slots: one core, the catalysts that shape, colour and charge it, and the resonators that bend and chain it. */
data class ForgeSlots(
    val core: DeliveryKind = DeliveryKind.BALLISTIC,
    val shape: EmitterShape = EmitterShape.SINGLE,
    val count: Int = 3,
    val essence: Element? = null,
    val charge: Payload? = null,
    val secondCharge: Payload? = null,
    val modulators: List<ModulatorKind> = emptyList(),
    /** Chained attacks, by the event that fires them. */
    val chains: Map<SkillEvent, ProceduralSkill> = emptyMap(),
) {
    fun compose(seed: Long): ProceduralSkill = AttackForge.compose(
        ForgePiece.Core(Delivery(core)),
        listOfNotNull(
            ForgePiece.Catalyst.Shape(Emitter(shape, count)),
            essence?.let(ForgePiece.Catalyst::Essence),
            charge?.let(ForgePiece.Catalyst::Charge),
            secondCharge?.let(ForgePiece.Catalyst::Charge),
        ),
        modulators.map { ForgePiece.Resonator.Modulate(Modulator(it)) } + chains.map { (e, s) -> ForgePiece.Resonator.Chain(e, s) },
        seed,
    )

    companion object {
        /** The slots an attack would be forged from: how a kept or rolled attack is opened back up on the anvil. */
        fun of(skill: ProceduralSkill): ForgeSlots {
            val lead = skill.lead
            return ForgeSlots(
                core = lead.delivery.kind, shape = lead.geometry.shape, count = lead.geometry.count,
                essence = lead.element.takeIf { it != lead.payload.element }, charge = lead.payload, secondCharge = lead.secondary,
                modulators = lead.modulators.map { it.kind }, chains = lead.subTriggers,
            )
        }
    }
}

data class AttackForgeUiState(
    val slots: ForgeSlots,
    val seed: Long,
    val attack: ProceduralSkill,
    val variations: List<ProceduralSkill> = emptyList(),
    val kept: List<ProceduralSkill> = emptyList(),
    val equipped: Set<String> = emptySet(),
    val message: String? = null,
) {
    val code: String get() = AttackCode.encode(attack)
    val power: Float get() = PowerBudget.power(attack)
    val stable: Boolean get() = power <= PowerBudget.LIMIT
    val isKept: Boolean get() = kept.any { it.id == attack.id }
    val isEquipped: Boolean get() = code in equipped
}

/**
 * The Forge of Will: slot a core, catalysts and resonators and see the
 * attack they make, named, costed and playing in its own look. Restyle it
 * without changing what it does, browse variations one part apart, keep
 * the good ones, carry up to three into play, and share any as a code.
 */
class AttackForgeViewModel(
    private val storage: AttackForgeStorage,
    private var seed: Long = System.nanoTime(),
) : ViewModel() {

    private val _state = MutableStateFlow(fresh(ForgeSlots(essence = Element.FIRE, modulators = listOf(ModulatorKind.SINE_WAVE)), nextSeed()))
    val state: StateFlow<AttackForgeUiState> = _state.asStateFlow()

    init { vary() }

    private fun nextSeed(): Long { seed = seed * 6364136223846793005L + 1442695040888963407L; return seed ushr 1 }

    private fun shelf() = storage.kept().mapNotNull(AttackCode::decode)

    private fun fresh(slots: ForgeSlots, style: Long) = AttackForgeUiState(
        slots = slots, seed = style, attack = slots.compose(style), kept = shelf(), equipped = storage.equipped().toSet(),
    )

    private fun set(slots: ForgeSlots, style: Long = _state.value.seed) {
        _state.update { it.copy(slots = slots, seed = style, attack = slots.compose(style), message = null) }
    }

    fun slot(change: (ForgeSlots) -> ForgeSlots) = set(change(_state.value.slots))

    fun core(kind: DeliveryKind) = slot { it.copy(core = kind) }
    fun shape(shape: EmitterShape) = slot { it.copy(shape = shape) }
    fun count(count: Int) = slot { it.copy(count = count.coerceIn(1, 8)) }
    fun essence(element: Element?) = slot { it.copy(essence = element) }
    fun charge(payload: Payload?) = slot { s -> s.copy(charge = payload, secondCharge = s.secondCharge?.takeIf { it != payload }) }
    fun secondCharge(payload: Payload?) = slot { it.copy(secondCharge = payload?.takeIf { p -> p != it.charge }) }

    /** Slots or unslots a modulator; with every resonator slot full, the oldest comes out. */
    fun toggleModulator(kind: ModulatorKind) = slot { s ->
        s.copy(modulators = if (kind in s.modulators) s.modulators - kind else (s.modulators + kind).takeLast(AttackGrammar.MAX_MODULATORS))
    }

    /** Chains a fresh attack to [event], or takes the chain off if one is there. */
    fun toggleChain(event: SkillEvent) = slot { s ->
        if (event in s.chains) s.copy(chains = s.chains - event)
        else s.copy(chains = (s.chains + (event to AttackForge.roll(nextSeed(), depth = 1))).entries.toList().takeLast(AttackGrammar.MAX_SUB_TRIGGERS).associate { it.toPair() })
    }

    /** A new look for the same attack: everything it does stays, everything it shows changes. */
    fun restyle() = set(_state.value.slots, nextSeed())

    /** A whole new attack, every slot rolled. */
    /** A whole new attack, every slot rolled; one the forge can hold, if a few tries find one. */
    fun roll() = pick(generateSequence { AttackForge.roll(nextSeed()) }.take(ROLL_TRIES).firstOrNull(PowerBudget::stable) ?: AttackForge.roll(nextSeed()))

    /** Attacks one part away from this one. */
    fun vary() {
        val now = _state.value.attack
        _state.update { it.copy(variations = List(VARIATIONS) { AttackForge.mutate(now, nextSeed()) }.sortedByDescending(PowerBudget::stable)) }
    }

    /** Puts [skill] on the anvil exactly as it is; slotting anything after recomposes it from its slots. */
    fun pick(skill: ProceduralSkill) {
        _state.update { it.copy(slots = ForgeSlots.of(skill), seed = skill.seed, attack = skill, message = null) }
        vary()
    }

    fun keep() {
        val s = _state.value
        if (!s.stable) return say("Too much power to hold: unslot a resonator or a chain.")
        storage.keep(s.code)
        refresh("Kept ${s.attack.name}.")
    }

    /** Carries this attack into play, or puts it down; it is kept first if it was not. */
    fun equip() {
        val s = _state.value
        if (!s.stable) return say("Too much power to hold: unslot a resonator or a chain.")
        if (!s.isKept) storage.keep(s.code)
        storage.toggleEquipped(s.code)
        refresh(if (s.isEquipped) "${s.attack.name} put down." else "${s.attack.name} carried into play.")
    }

    fun forget(skill: ProceduralSkill) {
        storage.forget(AttackCode.encode(skill))
        refresh("Forgot ${skill.name}.")
    }

    /** Opens an attack code on the anvil. */
    fun openCode(code: String): Boolean {
        val skill = AttackCode.decode(code.trim()) ?: return false.also { say("That is not an attack code.") }
        pick(skill)
        return true
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    private fun refresh(message: String) = _state.update { it.copy(kept = shelf(), equipped = storage.equipped().toSet(), message = message) }

    private companion object {
        const val VARIATIONS = 4
        const val ROLL_TRIES = 12
    }
}
