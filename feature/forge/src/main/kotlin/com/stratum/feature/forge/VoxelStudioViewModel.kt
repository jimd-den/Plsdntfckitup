package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.micro.MicroModel
import com.stratum.engine.model.ArgbImage
import com.stratum.engine.model.ImageVoxelizer
import com.stratum.engine.model.MicroModelOps
import com.stratum.engine.model.MicroModelOps.Axis
import com.stratum.engine.model.mask.IgboMaskGenerator
import com.stratum.engine.model.mask.MaskCodec
import com.stratum.engine.model.mask.MaskGenome
import com.stratum.engine.model.mask.MaskTradition
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the studio keeps models; implemented over the device's model library. */
interface VoxelStudioStorage {
    fun all(): List<MicroModel>
    fun save(model: MicroModel)
    fun delete(id: String)
}

/** What a tap or drag on the layer does. */
enum class StudioTool(val label: String, val glyph: String) {
    PENCIL("Pencil", "✎"),
    ERASE("Erase", "✕"),
    FILL("Fill", "▦"),
    LINE("Line", "━"),
    BOX("Box", "▭"),
    BALL("Ball", "●"),
    PICK("Pick", "◉"),
}

data class VoxelStudioUiState(
    val model: MicroModel = VoxelStudioViewModel.blank(),
    val library: List<MicroModel> = emptyList(),
    val layer: Int = 0,
    val tool: StudioTool = StudioTool.PENCIL,
    val colour: String = VoxelStudioViewModel.SWATCHES.first(),
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val ball: Float = 3f,
    val anchor: Triple<Int, Int, Int>? = null,
    val turn: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val working: Boolean = false,
    val imageMode: ImageVoxelizer.Mode = ImageVoxelizer.Mode.INFLATE,
    val imageHeight: Int = 48,
    val tradition: String? = null,
    /**
     * The dials of the mask being edited, or null when the model is not a
     * generated mask (or has been hand-edited since). While set, the studio
     * shows the mask dials and every dial regenerates the model.
     */
    val mask: MaskGenome? = null,
    /** The masquerade tradition 🎲 Randomize rolls in; null for any. */
    val maskTradition: MaskTradition? = null,
    /** The mask the hero wears in play, as a genome code; null for the default. */
    val wornMask: String? = null,
    val message: String? = null,
    /** Bumped on every change, so the preview redraws. */
    val revision: Int = 0,
) {
    val filled: Int get() = model.filledCount
    val sizeLabel: String get() = "${model.sizeX}×${model.sizeY}×${model.sizeZ} (${"%.1f".format(model.sizeX / 4f)}×${"%.1f".format(model.sizeY / 4f)}×${"%.1f".format(model.sizeZ / 4f)} blocks)"
}

/**
 * The model studio: make microvoxel models by hand, from a picture, or from
 * the world's own building generator, then keep them to place in the world.
 *
 * Every stroke replaces the model with a new one ([MicroModelOps] is pure), so
 * undo is a stack of models and never loses a step.
 */
class VoxelStudioViewModel(
    private val storage: VoxelStudioStorage,
    /** Rolls a building from the world's generator: seed, tradition (null for any) -> model. */
    private val generateBuilding: (Long, String?) -> MicroModel,
    /** The traditions a building can be rolled in, id to name. */
    val traditions: List<Pair<String, String>> = emptyList(),
    /** Told when a model is saved or deleted, so the play screen's list follows. */
    private val onLibraryChanged: () -> Unit = {},
    /** Makes a mask the hero's own face in play, by genome code. */
    private val onWearMask: (String) -> Unit = {},
    /** The mask the hero wears now, as a genome code. */
    wornMask: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(VoxelStudioUiState(library = storage.all(), wornMask = wornMask))
    val state: StateFlow<VoxelStudioUiState> = _state.asStateFlow()

    private val undo = ArrayDeque<MicroModel>()
    private val redo = ArrayDeque<MicroModel>()

    fun refresh() = _state.update { it.copy(library = storage.all()) }

    fun setTool(tool: StudioTool) = _state.update { it.copy(tool = tool, anchor = null) }
    fun setColour(colour: String) = _state.update { it.copy(colour = colour, tool = if (it.tool == StudioTool.ERASE || it.tool == StudioTool.PICK) StudioTool.PENCIL else it.tool) }
    fun setLayer(z: Int) = _state.update { it.copy(layer = z.coerceIn(0, it.model.sizeZ - 1)) }
    fun toggleMirrorX() = _state.update { it.copy(mirrorX = !it.mirrorX) }
    fun toggleMirrorY() = _state.update { it.copy(mirrorY = !it.mirrorY) }
    fun setBall(radius: Float) = _state.update { it.copy(ball = radius.coerceIn(1f, 16f)) }
    fun rotateView() = _state.update { it.copy(turn = (it.turn + 1) % 4, revision = it.revision + 1) }
    fun setName(name: String) = change(record = false) { it.copy(name = name.take(40)) }
    fun setImageMode(mode: ImageVoxelizer.Mode) = _state.update { it.copy(imageMode = mode) }
    fun setImageHeight(h: Int) = _state.update { it.copy(imageHeight = h.coerceIn(8, MicroModel.MAX_SIDE)) }
    fun setTradition(id: String?) = _state.update { it.copy(tradition = id) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /**
     * Wears the mask being edited as the hero: the characters are masks, and
     * this is the player's. In play the mask floats and glows as a spirit.
     */
    fun wearMask() {
        val mask = _state.value.mask ?: return
        val code = com.stratum.engine.model.mask.MaskCodec.encode(mask.normalised())
        onWearMask(code)
        _state.update { it.copy(wornMask = code, message = "You wear \"${mask.name}\" — your hero floats as this mask in play") }
    }

    /** A tap on cell (x, y) of the current layer. */
    fun tapCell(x: Int, y: Int) {
        val s = _state.value
        val z = s.layer
        if (!s.model.contains(x, y, z)) return
        val colour = if (s.tool == StudioTool.ERASE) null else s.colour
        val mirror = mirrorAxes(s)
        when (s.tool) {
            StudioTool.PENCIL, StudioTool.ERASE -> change { MicroModelOps.set(it, x, y, z, colour, mirror) }
            StudioTool.FILL -> change { MicroModelOps.fill(it, x, y, z, s.colour) }
            StudioTool.BALL -> change { MicroModelOps.sphere(it, x, y, z, s.ball, s.colour, mirror) }
            StudioTool.PICK -> s.model.at(x, y, z)?.let { picked -> _state.update { it.copy(colour = picked, tool = StudioTool.PENCIL) } }
            StudioTool.LINE, StudioTool.BOX -> {
                val anchor = s.anchor
                if (anchor == null) _state.update { it.copy(anchor = Triple(x, y, z), message = "Now tap the other end — change layer first to span layers") }
                else {
                    _state.update { it.copy(anchor = null, message = null) }
                    change {
                        if (s.tool == StudioTool.LINE) MicroModelOps.line(it, anchor, Triple(x, y, z), s.colour, mirror)
                        else MicroModelOps.box(it, anchor, Triple(x, y, z), s.colour, mirror = mirror)
                    }
                }
            }
        }
    }

    /** A drag across cells paints each one: how a player scribbles a wall. */
    fun dragCell(x: Int, y: Int) {
        val s = _state.value
        if (s.tool != StudioTool.PENCIL && s.tool != StudioTool.ERASE) return
        val z = s.layer
        if (!s.model.contains(x, y, z)) return
        val colour = if (s.tool == StudioTool.ERASE) null else s.colour
        if (s.model.at(x, y, z) == colour) return
        // A drag is one stroke: it records its first cell only, so one undo takes back the whole scribble.
        change(record = !stroking) { MicroModelOps.set(it, x, y, z, colour, mirrorAxes(s)) }
        stroking = true
    }

    private var stroking = false
    fun endStroke() { stroking = false }

    fun turnModel() = change { MicroModelOps.turn(it) }
    fun flipX() = change { MicroModelOps.flip(it, Axis.X) }
    fun flipY() = change { MicroModelOps.flip(it, Axis.Y) }
    fun trim() = change { MicroModelOps.trim(it) }
    fun doubleSize() = change { MicroModelOps.scale(it, 2f) }
    fun halveSize() = change { MicroModelOps.scale(it, 0.5f) }
    fun weather() = change { MicroModelOps.weather(it, System.nanoTime()) }
    fun hollow() = change { ImageVoxelizer.hollowed(it) }
    fun grow(dx: Int, dy: Int, dz: Int) = change { MicroModelOps.resize(it, it.sizeX + dx, it.sizeY + dy, it.sizeZ + dz) }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(_state.value.model)
        show(previous)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(_state.value.model)
        show(next)
    }

    fun newModel(sx: Int, sy: Int, sz: Int) {
        undo.addLast(_state.value.model); redo.clear()
        show(MicroModel.empty(newId(), "New model", sx, sy, sz), layer = 0)
    }

    fun open(model: MicroModel) {
        undo.addLast(_state.value.model); redo.clear()
        _state.update { it.copy(turn = previewTurn(model)) }
        show(model, layer = 0)
    }

    // ---- Masks ------------------------------------------------------------

    fun setMaskTradition(tradition: MaskTradition?) = _state.update { it.copy(maskTradition = tradition) }

    /** Starts from a mask: a preset, a roll, or anything else with dials. A fresh id, so keeping it never overwrites another. */
    fun startMask(genome: MaskGenome) {
        regenerate(genome, id = newId(), record = true, face = true)
    }

    /** 🎲 A fresh mask in the chosen tradition. */
    fun randomMask() = startMask(MaskGenome.random(System.nanoTime(), _state.value.maskTradition))

    /** Another like this: the current mask with a few dials nudged, or a fresh one when there is none. */
    fun anotherMask() {
        val current = _state.value.mask ?: return randomMask()
        regenerate(current.mutate(System.nanoTime(), 0.3f), id = _state.value.model.id, record = true)
    }

    /**
     * Turns a dial. The mask is carved again off the main thread; a newer
     * turn cancels an older one still carving, so a dragged slider only ever
     * shows its latest value. [record] is false for slider drags, so undo
     * steps back over whole choices rather than every pixel of a drag.
     */
    fun editMask(record: Boolean = true, edit: (MaskGenome) -> MaskGenome) {
        val current = _state.value.mask ?: return
        regenerate(edit(current).normalised(), id = _state.value.model.id, record = record)
    }

    private var carving: Job? = null

    private fun regenerate(genome: MaskGenome, id: String, record: Boolean, face: Boolean = false) {
        _state.update { it.copy(mask = genome, turn = if (face) MASK_TURN else it.turn) }
        carving?.cancel()
        carving = viewModelScope.launch {
            val made = runCatching { withContext(Dispatchers.Default) { IgboMaskGenerator.generate(genome, id) } }
            made.onSuccess { m ->
                if (_state.value.mask != genome) return@onSuccess
                if (record) {
                    undo.addLast(_state.value.model)
                    while (undo.size > HISTORY) undo.removeFirst()
                    redo.clear()
                }
                show(m, layer = if (record) (m.sizeZ / 2) else null)
            }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(message = e.message ?: "That mask would not carve") } }
        }
    }

    fun save() {
        val model = _state.value.model
        if (model.filledCount == 0) { _state.update { it.copy(message = "Nothing to keep yet") }; return }
        storage.save(model.compacted())
        onLibraryChanged()
        _state.update { it.copy(library = storage.all(), message = "Kept \"${model.name}\" — place it from the build tray") }
    }

    /** Keeps a copy under a new id, so the original stays as it was. */
    fun saveCopy() {
        change(record = false) { it.copy(id = newId(), name = it.name + " copy") }
        save()
    }

    fun delete(id: String) {
        storage.delete(id)
        onLibraryChanged()
        _state.update { it.copy(library = storage.all()) }
    }

    /** A building from the world's own generator, in the chosen tradition's grammar or any. */
    fun rollBuilding() {
        val tradition = _state.value.tradition
        work("Raising a building…") {
            val m = generateBuilding(System.nanoTime(), tradition)
            m.copy(id = newId())
        }
    }

    /** A picture turned into a model; the image is decoded by the caller. */
    fun fromImage(image: ArgbImage, name: String = "From a picture") {
        val s = _state.value
        work("Reading the picture…") {
            ImageVoxelizer.voxelize(newId(), name, image, ImageVoxelizer.Options(mode = s.imageMode, height = s.imageHeight))
        }
    }

    private fun work(label: String, make: () -> MicroModel) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, message = label) }
        viewModelScope.launch {
            val made = runCatching { withContext(Dispatchers.Default) { make() } }
            made.onSuccess { m ->
                undo.addLast(_state.value.model); redo.clear()
                show(m, layer = (m.sizeZ / 2).coerceAtMost(m.sizeZ - 1))
                _state.update { it.copy(working = false, message = "${m.filledCount} voxels — edit, then Keep") }
            }.onFailure { e -> _state.update { it.copy(working = false, message = e.message ?: "That did not make a model") } }
        }
    }

    private fun change(record: Boolean = true, edit: (MicroModel) -> MicroModel) {
        val before = _state.value.model
        var after = runCatching { edit(before) }.getOrElse { e -> _state.update { it.copy(message = e.message) }; return }
        if (after == before) return
        // A hand edit makes a generated mask the player's own model: its
        // genome no longer describes its voxels, so the tag (and with it the
        // dials, which would undo the edit) goes.
        if (after.cells !== before.cells) after = after.copy(tags = after.tags.filterNot { it.startsWith(MaskCodec.TAG_PREFIX) })
        if (record) {
            undo.addLast(before)
            while (undo.size > HISTORY) undo.removeFirst()
            redo.clear()
        }
        show(after)
    }

    private fun show(model: MicroModel, layer: Int? = null) = _state.update {
        it.copy(
            model = model,
            // The dials follow the model: a generated mask carries its genome in its tags.
            mask = MaskCodec.fromTags(model.tags, model.name), layer = (layer ?: it.layer).coerceIn(0, model.sizeZ - 1),
            canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), revision = it.revision + 1,
        )
    }

    private fun mirrorAxes(s: VoxelStudioUiState): Set<Axis> = buildSet { if (s.mirrorX) add(Axis.X); if (s.mirrorY) add(Axis.Y) }

    private fun newId(): String = "model-" + System.currentTimeMillis().toString(36) + (0..999).random()

    companion object {
        const val HISTORY = 60

        /** The preview's quarter turn that shows a mask's face (masks face -Y). */
        const val MASK_TURN = 1

        /** The view a model's thumbnail is best seen from: masks face front, the rest as built. */
        fun previewTurn(model: MicroModel): Int = if ("mask" in model.tags) MASK_TURN else 0

        /** Colours to hand: earths, stones, woods, paints, leaves and water. */
        val SWATCHES = listOf(
            "#A0583A", "#6E3A25", "#B98A5C", "#D8C58E", "#E6D9C0", "#F1ECE0", "#8A8A86", "#5B5A58", "#2A2624",
            "#7B5534", "#5A3E28", "#B8914E", "#9C4A34", "#C0692A", "#E4B22C", "#B6322A", "#2458A6", "#3F7DB6",
            "#2A7E48", "#5E9B3A", "#3F7F2E", "#2F6FA0", "#8C3E2A", "#FFFFFF",
        )

        fun blank(): MicroModel = MicroModel.empty("model-new", "New model", 16, 16, 16)

        fun factory(
            storage: VoxelStudioStorage,
            generateBuilding: (Long, String?) -> MicroModel,
            traditions: List<Pair<String, String>>,
            onLibraryChanged: () -> Unit,
            onWearMask: (String) -> Unit = {},
            wornMask: String? = null,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                VoxelStudioViewModel(storage, generateBuilding, traditions, onLibraryChanged, onWearMask, wornMask) as T
        }
    }
}
