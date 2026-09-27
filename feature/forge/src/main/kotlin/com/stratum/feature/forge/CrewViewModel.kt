package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.agents.ApprovalGate
import com.stratum.agents.CrewPreset
import com.stratum.agents.Review
import com.stratum.agents.StudioBrief
import com.stratum.agents.StudioJournal
import com.stratum.agents.StudioPipeline
import com.stratum.agents.StudioStep
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A step waiting on the person, and what the agent wrote for it. */
data class PendingReview(val step: StudioStep, val fragment: String)

data class CrewUiState(
    val prompt: String = "",
    val packName: String = "",
    /** Ready-made crews to start from: the loaded packs' own, and a whole world. */
    val presets: List<CrewPreset> = emptyList(),
    val presetId: String? = null,
    val crew: List<AgentRoleDefinition> = emptyList(),
    /** Roles the person has switched off for this run. */
    val benched: Set<String> = emptySet(),
    val journal: StudioJournal? = null,
    val review: PendingReview? = null,
    val reviewNote: String = "",
    /** The step whose record is open. */
    val openStep: String? = null,
    val result: ContentPack? = null,
    val installed: Boolean = false,
    val providerConfigured: Boolean = false,
    val error: String? = null,
) {
    val running: Boolean get() = journal != null && !journal.finished && result == null && error == null
}

/**
 * Drives the agent studio: collects a brief, runs the crew, and stands in
 * for the person at every approval gate by waiting for them.
 */
class CrewViewModel(
    private val model: LanguageModelPort,
    private val base: () -> List<ContentPack>,
    presets: List<CrewPreset>,
    private val isProviderConfigured: () -> Boolean,
    private val onInstall: (ContentPack) -> Unit,
    initialPreset: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        (presets.firstOrNull { it.id == initialPreset } ?: presets.firstOrNull()).let { preset ->
            CrewUiState(presets = presets, presetId = preset?.id, crew = preset?.roles.orEmpty(), providerConfigured = isProviderConfigured())
        },
    )
    val state: StateFlow<CrewUiState> = _state.asStateFlow()

    private var decision: CompletableDeferred<Review>? = null
    private var run: Job? = null

    fun updatePrompt(prompt: String) = update { copy(prompt = prompt) }

    fun updateName(name: String) = update { copy(packName = name) }

    /** Starts from a ready-made crew, every role back on the bench's other side. */
    fun selectPreset(presetId: String) {
        if (_state.value.running) return
        val preset = _state.value.presets.firstOrNull { it.id == presetId } ?: return
        update { copy(presetId = preset.id, crew = preset.roles, benched = emptySet()) }
    }

    fun toggleRole(roleId: String) = update { copy(benched = if (roleId in benched) benched - roleId else benched + roleId) }

    fun openStep(roleId: String) = update { copy(openStep = if (openStep == roleId) null else roleId) }

    fun updateNote(note: String) = update { copy(reviewNote = note) }

    fun start() {
        val current = _state.value
        val prompt = current.prompt.trim()
        if (prompt.isEmpty()) return update { copy(error = "Describe the world first.") }
        if (!isProviderConfigured()) return update { copy(error = "Connect a model provider first.", providerConfigured = false) }
        val name = current.packName.trim().ifBlank { prompt.split(' ').take(3).joinToString(" ").replaceFirstChar { it.uppercase() } }
        val brief = StudioBrief(prompt = prompt, packId = namespaceOf(name), packName = name)
        val crew = current.crew.filter { it.id !in current.benched }
        update { copy(journal = null, result = null, installed = false, error = null, review = null) }
        run = viewModelScope.launch {
            val outcome = StudioPipeline(model, base()).run(brief, crew, gate) { journal -> update { copy(journal = journal) } }
            update { copy(journal = outcome.journal, result = outcome.pack, review = null, error = if (outcome.pack == null) "The crew could not finish a pack that loads. The record shows why." else null) }
        }
    }

    fun approve() = decide(Review.Approve)

    fun revise() = _state.value.reviewNote.trim().takeIf { it.isNotEmpty() }?.let { decide(Review.Revise(it)) }

    fun skip() = decide(Review.Skip)

    fun cancel() {
        run?.cancel()
        decision?.cancel()
        update { copy(review = null, error = "Stopped.") }
    }

    fun install() {
        val pack = _state.value.result ?: return
        onInstall(pack)
        update { copy(installed = true) }
    }

    fun dismissError() = update { copy(error = null) }

    private val gate = ApprovalGate { step, fragment ->
        val pending = CompletableDeferred<Review>()
        decision = pending
        update { copy(review = PendingReview(step, fragment), reviewNote = "", openStep = step.role.id) }
        pending.await()
    }

    private fun decide(review: Review) {
        update { copy(review = null) }
        decision?.complete(review)
    }

    private inline fun update(change: CrewUiState.() -> CrewUiState) {
        _state.value = _state.value.change()
    }

    companion object {
        private val NON_ID = Regex("[^a-z0-9]+")

        fun namespaceOf(name: String): String = name.lowercase().replace(NON_ID, "_").trim('_').take(16).ifBlank { "studio" }

        fun factory(
            model: LanguageModelPort,
            base: () -> List<ContentPack>,
            presets: List<CrewPreset>,
            isProviderConfigured: () -> Boolean,
            onInstall: (ContentPack) -> Unit,
            initialPreset: String? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CrewViewModel(model, base, presets, isProviderConfigured, onInstall, initialPreset) as T
        }
    }
}
