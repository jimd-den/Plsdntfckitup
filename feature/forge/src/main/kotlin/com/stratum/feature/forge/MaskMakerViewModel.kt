package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.stratum.engine.model.mask.AfricanMaskArt
import com.stratum.engine.model.mask.AfricanMaskArt.Design
import com.stratum.engine.model.mask.AfricanMaskArt.Dial
import com.stratum.engine.model.mask.AfricanMaskArt.Nudge
import com.stratum.engine.model.mask.AfricanMaskArt.Part
import com.stratum.engine.model.mask.AfricanMaskArt.Trait
import com.stratum.engine.model.mask.AfricanMaskCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the mask maker keeps the masks a player chose to keep, as share codes. */
interface MaskMakerStorage {
    fun kept(): List<String>
    fun save(codes: List<String>)
}

/** The mask maker's pages of dials. */
enum class MakerPage(val label: String) {
    SHAPE("Shape"), FACE("Face"), MARKS("Marks & paint"), CROWN("Crown & hangings"), COLOURS("Colours"), PARTS("Pieces"), KEPT("Kept"),
}

data class MaskMakerUiState(
    val design: Design,
    val locked: Set<Trait> = emptySet(),
    val variations: List<Design> = emptyList(),
    val page: MakerPage = MakerPage.SHAPE,
    /** The piece being moved by hand on the Pieces page; drags on the preview move it. */
    val part: Part? = null,
    /** The colour slot a pigment tap paints. */
    val slot: Int = 0,
    val kept: List<Design> = emptyList(),
    val wornCode: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
) {
    val code: String get() = AfricanMaskCodec.encode(design)
    val worn: Boolean get() = wornCode == code
}

/**
 * The mask maker: a procedural African mask generator with every choice
 * open. Roll a fresh mask (keeping what is locked), browse variations of
 * the current one, set each trait by hand, paint any slot any pigment, move,
 * scale and turn each floating piece, keep masks, share them as codes and
 * wear one as the hero.
 */
class MaskMakerViewModel(
    private val storage: MaskMakerStorage,
    private val onWear: (String) -> Unit,
    wornCode: String?,
    private var seed: Long = System.nanoTime(),
) : ViewModel() {
    private val undo = ArrayDeque<Design>()
    private val redo = ArrayDeque<Design>()

    private val _state = MutableStateFlow(
        MaskMakerUiState(
            design = wornCode?.let { AfricanMaskCodec.decode(it, "My mask") } ?: AfricanMaskArt.generate(seed, "My mask"),
            kept = storage.kept().mapNotNull { AfricanMaskCodec.decode(it) },
            wornCode = wornCode,
        ),
    )
    val state: StateFlow<MaskMakerUiState> = _state.asStateFlow()

    init { vary() }

    private fun nextSeed(): Long { seed = seed * 6364136223846793005L + 1442695040888963407L; return seed }

    /** Changes the mask; [record] makes it an undo step (a slider drag records once, at its start). */
    fun edit(record: Boolean = true, change: (Design) -> Design) {
        val before = _state.value.design
        val after = change(before)
        if (after == before) return
        if (record) { undo.addLast(before); if (undo.size > HISTORY) undo.removeFirst(); redo.clear() }
        _state.update { it.copy(design = after, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), message = null) }
    }

    /** Marks the start of a drag: one undo step for the whole gesture. */
    fun beginGesture() {
        undo.addLast(_state.value.design); if (undo.size > HISTORY) undo.removeFirst(); redo.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.design)
        _state.update { it.copy(design = prev, canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.design)
        _state.update { it.copy(design = next, canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    /** 🎲 A fresh mask; locked traits stay. */
    fun roll() {
        val locked = _state.value.locked
        edit { AfricanMaskArt.reroll(it, nextSeed(), locked) }
        vary()
    }

    /** New variations of the current mask, each changing one or two unlocked traits. */
    fun vary() {
        val s = _state.value
        _state.update { it.copy(variations = AfricanMaskArt.variations(s.design, nextSeed(), VARIATIONS, s.locked)) }
    }

    fun pick(variation: Design) {
        edit { variation.copy(name = it.name, nudges = it.nudges) }
        vary()
    }

    fun toggleLock(trait: Trait) = _state.update { it.copy(locked = if (trait in it.locked) it.locked - trait else it.locked + trait) }

    fun setPage(page: MakerPage) = _state.update { it.copy(page = page, part = if (page == MakerPage.PARTS) it.part ?: Part.CROWN else null) }

    fun setName(name: String) = edit(record = false) { it.copy(name = name.take(40)) }

    fun setDial(dial: Dial, value: Float, record: Boolean = false) =
        edit(record) { d -> d.copy(dials = (d.dials + (dial to AfricanMaskArt.quantise(value))).filterValues { it != AfricanMaskArt.quantise(0.5f) }) }

    // ---- colours --------------------------------------------------------------------

    fun setScheme(index: Int) = edit { it.copy(scheme = index, pigments = null) }

    fun setSlot(slot: Int) = _state.update { it.copy(slot = slot.coerceIn(0, 4)) }

    /** Paints the chosen slot [pigment]; a named scheme becomes the player's own pigments first. */
    fun paint(pigment: Int) = edit { d ->
        val own = (d.pigments ?: nearestPigments(d)).toMutableList()
        own[_state.value.slot] = pigment
        d.copy(pigments = own)
    }

    private fun nearestPigments(d: Design): List<Int> {
        val c = d.colours
        return listOf(c.base, c.ink, c.a1, c.a2, c.a3).map { argb ->
            AfricanMaskArt.PIGMENTS.indices.minBy { i -> distance(AfricanMaskArt.PIGMENTS[i].rgb, argb) }
        }
    }

    private fun distance(a: Int, b: Int): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 255
        val dr = ch(a, 16) - ch(b, 16); val dg = ch(a, 8) - ch(b, 8); val db = ch(a, 0) - ch(b, 0)
        return dr * dr + dg * dg + db * db
    }

    // ---- pieces by hand -------------------------------------------------------------------

    fun selectPart(part: Part) = _state.update { it.copy(part = part) }

    fun nudge(part: Part, record: Boolean = false, change: (Nudge) -> Nudge) = edit(record) { d ->
        val n = change(d.nudges[part] ?: Nudge()).let {
            it.copy(dx = it.dx.coerceIn(-0.5f, 0.5f), dy = it.dy.coerceIn(-0.5f, 0.5f), scale = it.scale.coerceIn(0.4f, 2f), angle = it.angle.coerceIn(-1.5f, 1.5f))
        }
        d.copy(nudges = if (n == Nudge()) d.nudges - part else d.nudges + (part to n))
    }

    /** A drag on the preview: moves the chosen piece by ([du], [dv]) face units. */
    fun drag(du: Float, dv: Float) {
        val part = _state.value.part ?: return
        nudge(part) { it.copy(dx = it.dx + du, dy = it.dy + dv) }
    }

    fun resetPart(part: Part) = edit { it.copy(nudges = it.nudges - part) }

    fun resetAllParts() = edit { it.copy(nudges = emptyMap()) }

    // ---- keeping, sharing, wearing --------------------------------------------------------

    fun keep() {
        val s = _state.value
        val code = s.code
        val kept = listOf(s.design) + s.kept.filter { AfricanMaskCodec.encode(it) != code }
        storage.save(kept.map(AfricanMaskCodec::encode))
        _state.update { it.copy(kept = kept, message = "Kept \"${s.design.name}\"") }
    }

    fun forget(design: Design) {
        val code = AfricanMaskCodec.encode(design)
        val kept = _state.value.kept.filter { AfricanMaskCodec.encode(it) != code }
        storage.save(kept.map(AfricanMaskCodec::encode))
        _state.update { it.copy(kept = kept) }
    }

    fun load(design: Design) { edit { design }; vary() }

    /** Opens a pasted share code; false (with a message) when it isn't one. */
    fun openCode(text: String): Boolean {
        val d = AfricanMaskCodec.decode(text.trim())
        if (d == null) { _state.update { it.copy(message = "That isn't a mask code — they start with ${AfricanMaskCodec.PREFIX}") }; return false }
        load(d)
        _state.update { it.copy(message = "Opened \"${d.name}\"") }
        return true
    }

    fun wear() {
        val s = _state.value
        val code = s.code
        onWear(code)
        _state.update { it.copy(wornCode = code, message = "You wear \"${s.design.name}\" — your hero is this mask in play") }
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
