package com.stratum.feature.play

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.ImageModelPort
import com.stratum.core.domain.art.ArtDirection
import com.stratum.core.domain.art.StyleLexicon
import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.content.BiomeDefinition
import com.stratum.core.domain.content.ContentPack
import com.stratum.engine.scene.forge.ForgeProgress
import com.stratum.feature.play.gl.ForgedKits
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One finished texture, small enough to show in a grid. */
data class ForgedThumb(val key: String, val label: String, val image: ImageBitmap)

data class TextureForgeUiState(
    val prompt: String = "",
    /** What the game understood by the prompt, in its own words. */
    val summary: String = "",
    val regions: List<BiomeDefinition> = emptyList(),
    /** Regions to paint; empty means all of them. */
    val selectedRegions: Set<String> = emptySet(),
    val includeActors: Boolean = false,
    val planned: Int = 0,
    val planDescription: String = "",
    val alreadyMade: Int = 0,
    val progress: ForgeProgress? = null,
    val gallery: List<ForgedThumb> = emptyList(),
    val hasModel: Boolean = false,
    val message: String? = null,
) {
    val running: Boolean get() = progress?.isFinished == false
    val finished: Boolean get() = progress?.isFinished == true
}

/**
 * The texture forge: describe a look, choose where it applies, and watch the
 * image model paint it texture by texture.
 *
 * Everything it makes lands in the same per-style folder play reads, so the
 * world wears a painted style the next time that style is played -- there is
 * no export step to forget.
 */
class TextureForgeViewModel(
    private val content: AssembledContent,
    private val model: ImageModelPort?,
    private val root: File,
    initialPrompt: String,
) : ViewModel() {

    private val pack = mergedPack(content)
    private val runner = model?.let { TextureForgeRunner(it, root) }
    private var job: Job? = null

    private val _state = MutableStateFlow(TextureForgeUiState(regions = content.biomes, hasModel = model != null))
    val state: StateFlow<TextureForgeUiState> = _state.asStateFlow()

    init {
        setPrompt(initialPrompt.ifBlank { SUGGESTED_START })
    }

    fun setPrompt(prompt: String) {
        _state.update { it.copy(prompt = prompt, summary = direction(prompt).summary) }
        replan()
    }

    fun toggleRegion(biomeId: String) {
        _state.update { state ->
            val chosen = state.selectedRegions
            state.copy(selectedRegions = if (biomeId in chosen) chosen - biomeId else chosen + biomeId)
        }
        replan()
    }

    fun paintAllRegions() {
        _state.update { it.copy(selectedRegions = emptySet()) }
        replan()
    }

    fun toggleActors() {
        _state.update { it.copy(includeActors = !it.includeActors) }
        replan()
    }

    fun start() {
        val runner = runner ?: return _state.update { it.copy(message = "Add an OpenRouter key in Model provider first") }
        if (_state.value.running) return
        val state = _state.value
        val plan = runner.plan(direction(state.prompt), pack, state.selectedRegions, state.includeActors)
        if (plan.isEmpty) return _state.update { it.copy(message = "Everything here is already painted", progress = ForgeProgress(plan.alreadyMade, skipped = plan.alreadyMade)) }
        _state.update { it.copy(gallery = emptyList(), message = null) }
        job = viewModelScope.launch(Dispatchers.IO) {
            runner.run(plan) { progress ->
                val thumbs = thumbnails(plan.folder, progress)
                _state.update { it.copy(progress = progress, gallery = thumbs) }
            }
            _state.update { it.copy(message = "Done. Play in this style and the world wears it.") }
            replan()
        }
    }

    /** Stops between images; what has arrived is kept and used. */
    fun stop() {
        job?.cancel()
        _state.update { it.copy(progress = it.progress?.cancelling(), message = "Stopped. What was painted is kept.") }
        replan()
    }

    private fun replan() {
        val state = _state.value
        val direction = direction(state.prompt)
        val plan = runner?.plan(direction, pack, state.selectedRegions, state.includeActors)
        val orders = plan?.orders ?: com.stratum.core.domain.art.ForgePlanner.plan(direction, pack, state.selectedRegions, state.includeActors)
        _state.update {
            it.copy(planned = orders.size, planDescription = ForgeProgress.describe(orders), alreadyMade = plan?.alreadyMade ?: 0)
        }
    }

    /** The same reading of the prompt play makes, so both use the same folder. */
    private fun direction(prompt: String): ArtDirection =
        StyleLexicon.interpret(prompt, ArtDirection.HOUSE, prompt.lowercase().hashCode().toLong()).direction

    private val thumbCache = HashMap<String, ForgedThumb>()

    /** The newest textures first, decoded small, each only once. */
    private fun thumbnails(folder: File, progress: ForgeProgress): List<ForgedThumb> =
        progress.latest.take(GALLERY_SIZE).mapNotNull { order ->
            thumbCache.getOrPut(order.key) {
                val file = File(folder, ForgedKits.fileNameFor(order.key))
                val options = BitmapFactory.Options().apply { inSampleSize = THUMB_SAMPLE }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@mapNotNull null
                ForgedThumb(order.key, order.subject, bitmap.asImageBitmap())
            }
        }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        private const val GALLERY_SIZE = 24
        private const val THUMB_SAMPLE = 4
        private const val SUGGESTED_START = "painterly impasto"

        /** Every loaded pack's blocks, regions, classes and monsters, as one pack the planner can read. */
        fun mergedPack(content: AssembledContent): ContentPack = ContentPack(
            id = "stratum:texture-forge",
            name = "Loaded packs",
            author = "",
            blocks = content.packs.flatMap { it.blocks }.distinctBy { it.id },
            biomes = content.biomes,
            heroClasses = content.heroClasses,
            enemies = content.enemies,
        )

        fun factory(content: AssembledContent, model: ImageModelPort?, root: File, initialPrompt: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TextureForgeViewModel(content, model, root, initialPrompt) as T
            }
    }
}
