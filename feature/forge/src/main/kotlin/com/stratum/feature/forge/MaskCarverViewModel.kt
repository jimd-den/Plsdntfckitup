package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stratum.engine.model.mask.sculpt.Anatomy.Part
import com.stratum.engine.model.mask.sculpt.MaskCarver
import com.stratum.engine.model.mask.sculpt.MaskCulture
import com.stratum.engine.model.mask.sculpt.MaskSpec
import com.stratum.engine.model.mask.sculpt.Tradition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The mask carver's pages: the peoples to carve after, each part of the mask, then kept masks and sharing. */
sealed class CarverPage(val label: String) {
    data object Tradition : CarverPage("Tradition")
    data class Of(val part: Part) : CarverPage(part.label)
    data object Kept : CarverPage("Kept")
    data object Share : CarverPage("Share")

    companion object {
        val all: List<CarverPage> by lazy { listOf(Tradition) + Part.entries.map { Of(it) } + listOf(Kept, Share) }
    }
}

data class MaskCarverUiState(
    val spec: MaskSpec,
    /** The people a roll carves after, or null for any (and now and then two at once). */
    val tradition: Tradition? = null,
    val locked: Set<Part> = emptySet(),
    val variations: List<MaskSpec> = emptyList(),
    val page: CarverPage = CarverPage.Tradition,
    val kept: List<MaskSpec> = emptyList(),
    val wornCode: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
) {
    val code: String get() = MaskCarver.encode(spec)
    val worn: Boolean get() = wornCode == code
}

/**
 * The mask carver: sculpted African masks with every part open. Roll a mask
 * after one people's carvers or any (keeping the locked parts), browse
 * variations, carve each part afresh or set every choice and proportion by
 * hand, keep masks, share them as codes, and wear one in play.
 */
class MaskCarverViewModel(
    private val storage: MaskMakerStorage,
    private val onWear: (String) -> Unit,
    wornCode: String?,
    private var seed: Long = System.nanoTime(),
) : ViewModel() {
    private val undo = ArrayDeque<MaskSpec>()
    private val redo = ArrayDeque<MaskSpec>()

    private val _state = MutableStateFlow(
        MaskCarverUiState(
            spec = wornCode?.let(MaskCarver::decode) ?: MaskCarver.roll(seed, MaskCulture.tradition("agbogho_mmuo"), name = "My mask"),
            kept = storage.kept().mapNotNull(MaskCarver::decode),
            wornCode = wornCode,
        ),
    )
    val state: StateFlow<MaskCarverUiState> = _state.asStateFlow()

    init { vary() }

    private fun nextSeed(): Long { seed = seed * 6364136223846793005L + 1442695040888963407L; return seed ushr 1 }

    /** Changes the mask; [record] makes it an undo step (a slider records once, when it starts). */
    fun edit(record: Boolean = true, change: (MaskSpec) -> MaskSpec) {
        val before = _state.value.spec
        val after = change(before)
        if (after == before) return
        if (record) { undo.addLast(before); if (undo.size > HISTORY) undo.removeFirst(); redo.clear() }
        _state.update { it.copy(spec = after, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), message = null) }
    }

    fun beginSlide() {
        undo.addLast(_state.value.spec); if (undo.size > HISTORY) undo.removeFirst(); redo.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.spec)
        _state.update { it.copy(spec = prev, canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.spec)
        _state.update { it.copy(spec = next, canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    /** 🎲 A new mask after the chosen people (or any); locked parts stay. */
    fun roll() {
        val s = _state.value
        edit { MaskCarver.roll(nextSeed(), s.tradition, s.locked, from = it, name = it.name) }
        vary()
    }

    /** One part carved afresh, the rest kept. */
    fun reroll(part: Part) = edit { MaskCarver.reroll(it, part, nextSeed()) }

    fun vary() {
        val s = _state.value
        _state.update { it.copy(variations = MaskCarver.variations(s.spec, nextSeed(), VARIATIONS, s.locked)) }
    }

    fun pick(variation: MaskSpec) { edit { variation.copy(name = it.name) }; vary() }

    /** Carves after [tradition] from now on, and rolls one of its masks. */
    fun chooseTradition(tradition: Tradition?) {
        _state.update { it.copy(tradition = tradition) }
        roll()
    }

    fun toggleLock(part: Part) = _state.update { it.copy(locked = if (part in it.locked) it.locked - part else it.locked + part) }

    fun setPage(page: CarverPage) = _state.update { it.copy(page = page) }

    fun setName(name: String) = edit(record = false) { it.copy(name = name.take(40)) }

    // ---- the catalogue's controls ----------------------------------------------------------------

    fun pick(control: MaskCarver.Control.Pick, index: Int) = edit { control.set(it, index) }

    fun colour(control: MaskCarver.Control.Colour, index: Int) = edit { control.set(it, index) }

    fun toggle(control: MaskCarver.Control.Flags, index: Int) = edit { s ->
        val on = control.get(s)
        control.set(s, if (index in on) on - index else on + index)
    }

    fun slide(control: MaskCarver.Control.Slider, value: Float) = edit(record = false) { control.set(it, value) }

    /** Every proportion of [part] back to where its tradition carves it. */
    fun resetDials(part: Part) = edit { s -> s.copy(dials = s.dials.filterKeys { it.part != part }) }

    // ---- keeping and sharing -----------------------------------------------------------------------

    fun keep() {
        val s = _state.value
        val code = s.code
        val kept = listOf(s.spec) + s.kept.filter { MaskCarver.encode(it) != code }
        storage.save(kept.map(MaskCarver::encode))
        _state.update { it.copy(kept = kept, message = "Kept \"${s.spec.name}\"") }
    }

    fun forget(spec: MaskSpec) {
        val code = MaskCarver.encode(spec)
        val kept = _state.value.kept.filter { MaskCarver.encode(it) != code }
        storage.save(kept.map(MaskCarver::encode))
        _state.update { it.copy(kept = kept) }
    }

    fun load(spec: MaskSpec) { edit { spec }; vary() }

    /** Opens a pasted share code; false, with a message, when it isn't one. */
    fun openCode(text: String): Boolean {
        val spec = MaskCarver.decode(text.trim())
        if (spec == null) { _state.update { it.copy(message = "That isn't a carved mask's code — they start with ${MaskCarver.PREFIX}") }; return false }
        load(spec)
        _state.update { it.copy(message = "Opened \"${spec.name}\"") }
        return true
    }

    fun wear() {
        val s = _state.value
        onWear(s.code)
        _state.update { it.copy(wornCode = s.code, message = "Your hero wears \"${s.spec.name}\" in play") }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val VARIATIONS = 6
        private const val HISTORY = 60

        fun factory(storage: MaskMakerStorage, onWear: (String) -> Unit, wornCode: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MaskCarverViewModel(storage, onWear, wornCode?.takeIf(MaskCarver::isCode)) as T
        }
    }
}
