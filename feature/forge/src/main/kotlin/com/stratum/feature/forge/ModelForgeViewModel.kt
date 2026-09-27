package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.GeneratedModel
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.ai.ImageReference
import com.stratum.core.domain.ai.ModelAsset
import com.stratum.core.domain.ai.ModelBrief
import com.stratum.core.domain.ai.ModelGenerationObserver
import com.stratum.core.domain.ai.ModelJobProgress
import com.stratum.core.domain.ai.ModelSubjectKind
import com.stratum.core.domain.content.VoxelBlueprint
import com.stratum.core.domain.world.BlockType
import com.stratum.engine.model.BlockPalette
import com.stratum.engine.model.ModelPipeline
import com.stratum.engine.scene.Texture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the model forge keeps what it makes; implemented over the device's model store. */
interface ModelForgeStorage {
    fun all(): List<ModelAsset>
    fun save(asset: ModelAsset, bytes: ByteArray, previewPng: ByteArray?)
    fun update(asset: ModelAsset)
    fun delete(id: String)
    fun bytesFor(id: String): ByteArray?
    fun previewFor(id: String): ByteArray?
    fun saveBlueprint(blueprint: VoxelBlueprint)
}

/**
 * The model forge: describe a thing, get a 3D model, put it in the world.
 *
 * Thin on purpose. Generation is the domain's use case behind the port;
 * parsing, decimating, voxelising and baking are [ModelPipeline]'s pure code;
 * storage is the device's store. What is left here is order — generate, then
 * prepare off the main thread, then save — and what the screen shows while it
 * happens.
 */
class ModelForgeViewModel(
    private val generate: suspend (ModelBrief, ModelGenerationObserver) -> Result<GeneratedModel>,
    private val pipeline: ModelPipeline,
    private val storage: ModelForgeStorage,
    /** Prop blocks a model can stand in for, from the loaded packs. */
    private val propBlocks: () -> List<BlockType>,
    /** Every block, of which the plain building blocks become the voxel palette. */
    private val allBlocks: () -> List<BlockType>,
    private val providerLabel: () -> String,
    private val isProviderConfigured: () -> Boolean,
    private val encodePng: (Texture) -> ByteArray,
    /** Told when a binding changes, so the world can pick the model up. */
    private val onContentChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(
        ModelForgeUiState(
            assets = storage.all(),
            propBlocks = propBlocks(),
            provider = providerLabel(),
            providerConfigured = isProviderConfigured(),
        ),
    )
    val state: StateFlow<ModelForgeUiState> = _state.asStateFlow()

    fun refresh() = _state.update {
        it.copy(assets = storage.all(), propBlocks = propBlocks(), provider = providerLabel(), providerConfigured = isProviderConfigured())
    }

    fun setSubject(text: String) = _state.update { it.copy(subject = text) }
    fun setStyle(text: String) = _state.update { it.copy(style = text) }
    fun setKind(kind: ModelSubjectKind) = _state.update { it.copy(kind = kind) }
    fun setHeight(blocks: Float) = _state.update { it.copy(heightBlocks = blocks.coerceIn(MIN_HEIGHT, MAX_HEIGHT)) }
    fun setTextured(textured: Boolean) = _state.update { it.copy(textured = textured) }
    fun setReference(bytes: ByteArray?, mimeType: String = "image/png") =
        _state.update { it.copy(reference = bytes?.let { b -> ImageReference(b, mimeType) }) }

    fun select(id: String) = _state.update { it.copy(selectedId = id, message = null, error = null) }

    fun generate() {
        val current = _state.value
        if (current.generating) return
        val brief = ModelBrief(
            subject = current.subject.trim(), kind = current.kind, style = current.style,
            reference = current.reference, textured = current.textured,
        )
        _state.update { it.copy(generating = true, error = null, message = null, progress = null, stage = GenerationStage.PREPARING) }
        viewModelScope.launch {
            val observer = object : ModelGenerationObserver {
                override fun onStage(stage: GenerationStage) = _state.update { it.copy(stage = stage) }
                override fun onProgress(progress: ModelJobProgress) = _state.update { it.copy(progress = progress) }
            }
            val outcome = generate(brief, observer).mapCatching { model -> withContext(Dispatchers.Default) { prepare(brief, model, current.heightBlocks) } }
            outcome
                .onSuccess { asset ->
                    _state.update { it.copy(generating = false, assets = storage.all(), selectedId = asset.id, message = "Saved ${asset.name}: ${asset.triangleCount} triangles") }
                }
                .onFailure { failure ->
                    _state.update { it.copy(generating = false, error = failure.message ?: "The model could not be made") }
                }
        }
    }

    /** Checks the model parses, bakes its preview and keeps it. Throws with a sentence when the file is bad. */
    private fun prepare(brief: ModelBrief, model: GeneratedModel, height: Float): ModelAsset {
        val mesh = pipeline.load(model.bytes).getOrThrow()
        val prop = pipeline.prop(pipeline.normalize(mesh, height))
        val preview = encodePng(pipeline.sprites(prop, PREVIEW_SIZE).front)
        val asset = ModelAsset(
            id = brief.slug(),
            name = brief.subject.ifBlank { "Model" },
            prompt = brief.subject,
            kind = brief.kind,
            format = model.format,
            provider = providerLabel(),
            heightBlocks = height,
            triangleCount = mesh.triangleCount,
        )
        storage.save(asset, model.bytes, preview)
        return asset
    }

    /** Makes the selected model the body of every [blockId] prop in the world, or frees it with null. */
    fun useAsProp(blockId: String?) {
        val asset = selected() ?: return
        // One model per block: whichever held it before lets go.
        storage.all().filter { it.propBlockId == blockId && it.id != asset.id && blockId != null }
            .forEach { storage.update(it.copy(propBlockId = null)) }
        storage.update(asset.copy(propBlockId = blockId))
        _state.update { it.copy(assets = storage.all(), message = blockId?.let { id -> "${asset.name} now stands in for $id" } ?: "${asset.name} is no longer a prop") }
        onContentChanged()
    }

    /** Rebuilds the selected model from the loaded packs' blocks and keeps the blueprint for building. */
    fun voxelize() {
        val asset = selected() ?: return
        _state.update { it.copy(working = true, error = null, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val palette = BlockPalette.of(allBlocks()) ?: error("The loaded packs have no plain building blocks to build with")
                    val bytes = storage.bytesFor(asset.id) ?: error("The model's file is missing")
                    val mesh = pipeline.normalize(pipeline.load(bytes).getOrThrow(), asset.heightBlocks)
                    pipeline.blueprint(mesh, palette, id = "blueprint:${asset.id}", name = asset.name).also(storage::saveBlueprint)
                }
            }
            result
                .onSuccess { blueprint ->
                    storage.update(asset.copy(blueprintId = blueprint.id))
                    _state.update {
                        it.copy(
                            working = false, assets = storage.all(),
                            message = "${blueprint.filledCount} blocks of ${blueprint.palette.size} kinds, " +
                                "${blueprint.sizeX}x${blueprint.sizeY}x${blueprint.sizeZ}. Raise it from build mode.",
                        )
                    }
                    onContentChanged()
                }
                .onFailure { failure -> _state.update { it.copy(working = false, error = failure.message) } }
        }
    }

    fun delete(id: String) {
        storage.delete(id)
        _state.update { it.copy(assets = storage.all(), selectedId = if (it.selectedId == id) null else it.selectedId) }
        onContentChanged()
    }

    fun previewFor(id: String): ByteArray? = storage.previewFor(id)

    private fun selected(): ModelAsset? = _state.value.selectedId?.let { id -> storage.all().firstOrNull { it.id == id } }

    companion object {
        const val MIN_HEIGHT = 0.5f
        const val MAX_HEIGHT = 12f
        private const val PREVIEW_SIZE = 256

        fun factory(
            generate: suspend (ModelBrief, ModelGenerationObserver) -> Result<GeneratedModel>,
            pipeline: ModelPipeline,
            storage: ModelForgeStorage,
            propBlocks: () -> List<BlockType>,
            allBlocks: () -> List<BlockType>,
            providerLabel: () -> String,
            isProviderConfigured: () -> Boolean,
            encodePng: (Texture) -> ByteArray,
            onContentChanged: () -> Unit = {},
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ModelForgeViewModel(
                generate, pipeline, storage, propBlocks, allBlocks, providerLabel, isProviderConfigured, encodePng, onContentChanged,
            ) as T
        }
    }
}

/** Everything the model forge shows. */
data class ModelForgeUiState(
    val subject: String = "",
    val style: String = "",
    val kind: ModelSubjectKind = ModelSubjectKind.PROP,
    val heightBlocks: Float = 1.5f,
    val textured: Boolean = true,
    val reference: ImageReference? = null,
    val generating: Boolean = false,
    /** Voxelising, which is quick but not instant on a large model. */
    val working: Boolean = false,
    val stage: GenerationStage? = null,
    val progress: ModelJobProgress? = null,
    val assets: List<ModelAsset> = emptyList(),
    val selectedId: String? = null,
    val propBlocks: List<BlockType> = emptyList(),
    val provider: String = "",
    val providerConfigured: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val selected: ModelAsset? get() = assets.firstOrNull { it.id == selectedId }
    val canGenerate: Boolean get() = !generating && providerConfigured && (subject.isNotBlank() || reference != null)

    /** What the bar says: the job's own words when it has any, else the stage. */
    val progressLabel: String
        get() = progress?.let { p -> p.phase.label + (p.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "") + p.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() }
            ?: stage?.label.orEmpty()
}
