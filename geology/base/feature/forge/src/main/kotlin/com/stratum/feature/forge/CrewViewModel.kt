package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.agents.ApprovalGate
import com.stratum.agents.CrewJobReport
import com.stratum.agents.CrewPreset
import com.stratum.agents.CrewPresets
import com.stratum.agents.Review
import com.stratum.agents.StudioBrief
import com.stratum.agents.StudioJournal
import com.stratum.agents.StudioPipeline
import com.stratum.agents.StudioStep
import com.stratum.core.domain.ai.AgentRoleDefinition
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.creation.CreationJob
import com.stratum.core.domain.creation.InMemoryJobCenter
import com.stratum.core.domain.creation.InstantWorld
import com.stratum.core.domain.creation.JobLauncher
import com.stratum.core.domain.creation.PackDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** The run as a job: its steps, its time, its outcome in a line. */
    val job: CreationJob? = null,
    /**
     * The world being played while the crew writes, once "Play now" was
     * pressed. Its pack joins it when the crew finishes.
     */
    val instantWorld: InstantWorld? = null,
    /** What happened to the pack when it arrived, for a world played meanwhile. */
    val delivery: PackDelivery? = null,
) {
    val running: Boolean get() = journal != null && !journal.finished && result == null && error == null

    /** A world can be played from the brief now, before the crew has written anything. */
    val offersInstantWorld: Boolean get() = presetId == CrewPresets.WORLD && prompt.isNotBlank()
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
    /** Where the run goes: the app's own centre, so it keeps going when this screen is left. */
    jobs: JobLauncher? = null,
    private val modelName: () -> String? = { null },
    /** Plays [InstantWorld] now; the crew's pack follows it. */
    private val onPlayNow: (InstantWorld) -> Unit = {},
    /**
     * Hands a finished pack to the game for a world that is being played:
     * installed at once if play has not started, held until the next entry if it has.
     */
    private val deliver: (ContentPack) -> PackDelivery = { onInstall(it); PackDelivery.INSTALLED },
) : ViewModel() {

    private val jobs: JobLauncher = jobs ?: InMemoryJobCenter(viewModelScope)

    private val _state = MutableStateFlow(
        (presets.firstOrNull { it.id == initialPreset } ?: presets.firstOrNull()).let { preset ->
            CrewUiState(presets = presets, presetId = preset?.id, crew = preset?.roles.orEmpty(), providerConfigured = isProviderConfigured())
        },
    )
    val state: StateFlow<CrewUiState> = _state.asStateFlow()

    private var decision: CompletableDeferred<Review>? = null
    private var runId: String? = null

    init {
        viewModelScope.launch {
            this@CrewViewModel.jobs.jobs.collect { all -> all.firstOrNull { it.id == runId }?.let { job -> update { copy(job = job) } } }
        }
    }

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
        update { copy(journal = null, result = null, installed = false, error = null, review = null, job = null, instantWorld = null, delivery = null) }
        val packs = base()
        val waitingFor = modelName()?.takeIf { it.isNotBlank() }
        runId = jobs.launch(
            kind = if (current.presetId == CrewPresets.WORLD) "world" else "pack",
            title = name,
            steps = CrewJobReport.plannedSteps(crew) + CHECKING,
        ) {
            val report = CrewJobReport(this, waitingFor)
            val outcome = try {
                StudioPipeline(model, packs).run(brief, crew, gate, report.observer) { journal ->
                    report.onJournal(journal)
                    update { copy(journal = journal) }
                }
            } catch (stopped: CancellationException) {
                decision?.cancel()
                update { copy(review = null, error = "Stopped.") }
                throw stopped
            }
            begin(CHECKING)
            val pack = outcome.pack
            if (pack == null) {
                val why = outcome.journal.problems.firstOrNull() ?: "a role did not finish"
                update { copy(journal = outcome.journal, review = null, error = "The crew could not finish a pack that loads. The record shows why.") }
                fail("The crew could not finish a pack that loads: $why.")
            }
            update { copy(journal = outcome.journal, result = pack, review = null) }
            done("${pack.biomes.size} regions, ${pack.enemies.size} monsters, ${pack.factions.size} factions")
            summaryFor(pack)
        }
        update { copy(job = jobs.jobs.value.firstOrNull { it.id == runId } ?: job) }
    }

    /**
     * Plays the brief now, in the look the lexicon reads from it, while the
     * crew keeps writing. Starts the crew first if it was not running.
     */
    fun playNow() {
        val current = _state.value
        if (current.prompt.isBlank()) return update { copy(error = "Describe the world first.") }
        val world = InstantWorld.from(current.prompt, current.packName)
        if (!current.running && current.result == null && isProviderConfigured()) start()
        update { copy(instantWorld = world, error = null) }
        // A crew that finished before play was pressed joins the world as it is built.
        _state.value.result?.takeIf { !_state.value.installed }?.let(::deliverToWorld)
        onPlayNow(world)
    }

    private fun summaryFor(pack: ContentPack): String {
        if (_state.value.instantWorld == null) return "${pack.name} is ready to install."
        return when (deliverToWorld(pack)) {
            PackDelivery.INSTALLED -> "${pack.name} is installed; your world is built with it."
            PackDelivery.HELD -> "${pack.name} is ready. It joins your world the next time you enter it."
        }
    }

    private fun deliverToWorld(pack: ContentPack): PackDelivery {
        val delivery = deliver(pack)
        update { copy(installed = true, delivery = delivery) }
        return delivery
    }

    fun approve() = decide(Review.Approve)

    fun revise() = _state.value.reviewNote.trim().takeIf { it.isNotEmpty() }?.let { decide(Review.Revise(it)) }

    fun skip() = decide(Review.Skip)

    fun cancel() {
        runId?.let(jobs::cancel)
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

    private inline fun update(crossinline change: CrewUiState.() -> CrewUiState) {
        _state.update { it.change() }
    }

    companion object {
        private val NON_ID = Regex("[^a-z0-9]+")
        private const val CHECKING = "Checking the whole pack"

        fun namespaceOf(name: String): String = name.lowercase().replace(NON_ID, "_").trim('_').take(16).ifBlank { "studio" }

        fun factory(
            model: LanguageModelPort,
            base: () -> List<ContentPack>,
            presets: List<CrewPreset>,
            isProviderConfigured: () -> Boolean,
            onInstall: (ContentPack) -> Unit,
            initialPreset: String? = null,
            jobs: JobLauncher? = null,
            modelName: () -> String? = { null },
            onPlayNow: (InstantWorld) -> Unit = {},
            deliver: ((ContentPack) -> PackDelivery)? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CrewViewModel(
                model, base, presets, isProviderConfigured, onInstall, initialPreset, jobs, modelName, onPlayNow,
                deliver ?: { pack -> onInstall(pack); PackDelivery.INSTALLED },
            ) as T
        }
    }
}
