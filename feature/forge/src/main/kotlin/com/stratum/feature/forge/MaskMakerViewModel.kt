package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stratum.engine.model.mask.EmojiMask
import com.stratum.engine.model.mask.EmojiMask.Look
import com.stratum.engine.model.mask.MaskMaker
import com.stratum.engine.model.mask.MaskMaker.Trait
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the mask maker keeps the masks a player chose to keep, as share codes. */
interface MaskMakerStorage {
    fun kept(): List<String>
    fun save(codes: List<String>)
}

/** The mask maker's pages. */
enum class MakerPage(val label: String) {
    FACE("Face"), CREST("Coiffure & crest"), FEATURES("Eyes & mouth"), MARKS("Marks & paint"), COLOURS("Wood & colour"), KEPT("Kept"), SHARE("Share"),
}

data class MaskMakerUiState(
    val look: Look,
    /** The feeling the preview shows, by [EmojiMask.expressions] name. */
    val feeling: String = "Serene",
    val locked: Set<Trait> = emptySet(),
    val variations: List<Look> = emptyList(),
    val page: MakerPage = MakerPage.FACE,
    val kept: List<Look> = emptyList(),
    val wornCode: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
) {
    val code: String get() = MaskMaker.encode(look)
    val worn: Boolean get() = wornCode == code
}

/**
 * The mask maker: an Igbo mask generator with every choice open. Roll a new
 * mask (keeping what is locked), browse variations of the one in hand, set
 * each trait by hand, see it feel all twenty feelings, keep masks, share
 * them as codes, and wear one as the hero.
 */
class MaskMakerViewModel(
    private val storage: MaskMakerStorage,
    private val onWear: (String) -> Unit,
    wornCode: String?,
    private var seed: Long = System.nanoTime(),
) : ViewModel() {
    private val undo = ArrayDeque<Look>()
    private val redo = ArrayDeque<Look>()

    private val _state = MutableStateFlow(
        MaskMakerUiState(
            look = wornCode?.let { MaskMaker.decode(it, "My mask") } ?: MaskMaker.normalised(EmojiMask.presets[0].copy(name = "My mask")),
            kept = storage.kept().mapNotNull { MaskMaker.decode(it) },
            wornCode = wornCode,
        ),
    )
    val state: StateFlow<MaskMakerUiState> = _state.asStateFlow()

    init { vary() }

    private fun nextSeed(): Long { seed = seed * 6364136223846793005L + 1442695040888963407L; return seed }

    /** Changes the mask; [record] makes it an undo step (a slider records once, when it starts). */
    fun edit(record: Boolean = true, change: (Look) -> Look) {
        val before = _state.value.look
        val after = MaskMaker.normalised(change(before))
        if (after == before) return
        if (record) { undo.addLast(before); if (undo.size > HISTORY) undo.removeFirst(); redo.clear() }
        _state.update { it.copy(look = after, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), message = null) }
    }

    fun beginSlide() {
        undo.addLast(_state.value.look); if (undo.size > HISTORY) undo.removeFirst(); redo.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.look)
        _state.update { it.copy(look = prev, canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.look)
        _state.update { it.copy(look = next, canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    /** 🎲 A new mask; locked traits stay. */
    fun roll() {
        val locked = _state.value.locked
        edit { MaskMaker.reroll(it, nextSeed(), locked) }
        vary()
    }

    /** New variations of the mask in hand, each changing one or two unlocked traits. */
    fun vary() {
        val s = _state.value
        _state.update { it.copy(variations = MaskMaker.variations(s.look, nextSeed(), VARIATIONS, s.locked)) }
    }

    fun pick(variation: Look) { edit { variation.copy(name = it.name) }; vary() }

    fun startFrom(preset: Look) { edit { preset.copy(name = it.name) }; vary() }

    fun toggleLock(trait: Trait) = _state.update { it.copy(locked = if (trait in it.locked) it.locked - trait else it.locked + trait) }

    fun setPage(page: MakerPage) = _state.update { it.copy(page = page) }

    fun setFeeling(name: String) { if (name in EmojiMask.expressions) _state.update { it.copy(feeling = name) } }

    fun setName(name: String) = edit(record = false) { it.copy(name = name.take(40)) }

    fun keep() {
        val s = _state.value
        val code = s.code
        val kept = listOf(s.look) + s.kept.filter { MaskMaker.encode(it) != code }
        storage.save(kept.map(MaskMaker::encode))
        _state.update { it.copy(kept = kept, message = "Kept \"${s.look.name}\"") }
    }

    fun forget(look: Look) {
        val code = MaskMaker.encode(look)
        val kept = _state.value.kept.filter { MaskMaker.encode(it) != code }
        storage.save(kept.map(MaskMaker::encode))
        _state.update { it.copy(kept = kept) }
    }

    fun load(look: Look) { edit { look }; vary() }

    /** Opens a pasted share code; false, with a message, when it isn't one. */
    fun openCode(text: String): Boolean {
        val look = MaskMaker.decode(text.trim())
        if (look == null) { _state.update { it.copy(message = "That isn't a mask code — mask codes start with ${MaskMaker.PREFIX}") }; return false }
        load(look)
        _state.update { it.copy(message = "Opened \"${look.name}\"") }
        return true
    }

    fun wear() {
        val s = _state.value
        onWear(s.code)
        _state.update { it.copy(wornCode = s.code, message = "Your hero wears \"${s.look.name}\" in play") }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val VARIATIONS = 6
        private const val HISTORY = 60

        fun factory(storage: MaskMakerStorage, onWear: (String) -> Unit, wornCode: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MaskMakerViewModel(storage, onWear, wornCode) as T
        }
    }
}
