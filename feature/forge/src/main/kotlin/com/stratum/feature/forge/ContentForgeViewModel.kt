package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.agents.ForgeJobReport
import com.stratum.agents.StudioJournal
import com.stratum.agents.forge.ContentForge
import com.stratum.agents.forge.Creations
import com.stratum.agents.forge.ForgeCard
import com.stratum.agents.forge.ForgeCards
import com.stratum.agents.forge.ForgeContext
import com.stratum.agents.forge.ForgeField
import com.stratum.agents.forge.ForgeKind
import com.stratum.agents.forge.ForgeOrder
import com.stratum.agents.forge.ForgeOutcome
import com.stratum.agents.forge.ForgePlaceholder
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.creation.CreationJob
import com.stratum.core.domain.creation.InMemoryJobCenter
import com.stratum.core.domain.creation.JobFailure
import com.stratum.core.domain.creation.JobLauncher
import com.stratum.core.domain.creation.JobReporter
import com.stratum.core.domain.creation.observer
import com.stratum.core.domain.item.ItemCategory
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One field of one card, open for rewriting. */
data class CardEdit(val id: String, val field: ForgeField, val text: String)

/**
 * One request on the anvil: the placeholder shown the moment it was asked
 * for, the job writing it, and -- once the reply lands -- what came back.
 *
 * Several can be in flight at once. Each is its own job in the app's job
 * centre, so leaving the screen does not stop them; this keeps only what the
 * screen needs to show each one.
 */
data class ForgeDraft(
    /** This screen's own key, known before the job has an id. */
    val key: String,
    val kind: ForgeKind,
    val order: ForgeOrder,
    val context: ForgeContext,
    /** Shown until the reply lands: the name and kind, taken from the prompt. */
    val placeholder: ForgeCard,
    val jobId: String? = null,
    val job: CreationJob? = null,
    val journal: StudioJournal? = null,
    val result: ContentPack? = null,
    val cards: List<ForgeCard> = emptyList(),
    val repairs: List<String> = emptyList(),
    /** Why nothing came of it, when nothing did. */
    val error: String? = null,
    val added: Boolean = false,
) {
    val running: Boolean get() = result == null && error == null
    val levelFrom: Int get() = context.levels.first
}

data class ContentForgeUiState(
    val kind: ForgeKind = ForgeKind.UNIQUE,
    val prompt: String = "",
    val theme: String = "",
    val budget: PowerTier = PowerTier.BALANCED,
    val levelFrom: Int = 1,
    val levelTo: Int = 20,
    /** Null lets the model choose, where the kind allows it. */
    val slot: ItemSlot? = null,
    val ladder: Boolean = false,
    /** Everything asked for this session, newest first. */
    val drafts: List<ForgeDraft> = emptyList(),
    /** The draft being read; null means the newest. */
    val openKey: String? = null,
    val editing: CardEdit? = null,
    val showRecord: Boolean = false,
    /** How many things the player's own plugin holds. */
    val kept: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val providerConfigured: Boolean = false,
) {
    /** The slots worth offering for the kind being made. */
    val slots: List<ItemSlot>
        get() = when (kind) {
            ForgeKind.ARMOUR -> ItemSlot.entries.filter { it.category != ItemCategory.WEAPON }
            ForgeKind.UNIQUE, ForgeKind.AFFIXES -> ItemSlot.entries
            else -> emptyList()
        }

    val offersLadder: Boolean get() = kind == ForgeKind.WEAPON || kind == ForgeKind.ARMOUR

    val open: ForgeDraft? get() = drafts.firstOrNull { it.key == openKey } ?: drafts.firstOrNull()

    /** Whether anything is still being written. */
    val running: Boolean get() = drafts.any { it.running }
    val runningCount: Int get() = drafts.count { it.running }

    val result: ContentPack? get() = open?.result
    val cards: List<ForgeCard> get() = open?.cards.orEmpty()
    val repairs: List<String> get() = open?.repairs.orEmpty()
    val journal: StudioJournal? get() = open?.journal
    val added: Boolean get() = open?.added == true
}

/**
 * Drives the content forge: what to make, how strong, at what depth; the
 * runs on the agent pipeline, each a job of its own; the results as cards to
 * read, rename and reword; and keeping them in the player's own plugin.
 *
 * Asking is instant: a request appears at once as a placeholder named from
 * the prompt, and fills in when the reply lands. Holds no rules of its own.
 * Generation, repair, budget, cards, edits and the growing plugin are all in
 * `:agents`, and tested there; this only sequences them and keeps the
 * screen's state.
 */
class ContentForgeViewModel(
    private val model: LanguageModelPort,
    private val base: () -> List<ContentPack>,
    private val isProviderConfigured: () -> Boolean,
    /** The player's own plugin as installed now, or null before anything was kept. */
    private val creations: () -> ContentPack?,
    private val saveCreations: suspend (ContentPack) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where the runs go: the app's own centre, so they outlive this screen. */
    jobs: JobLauncher? = null,
    /** The configured model's name, for "Waiting for …" before the provider has named it. */
    private val modelName: () -> String? = { null },
) : ViewModel() {

    private val jobs: JobLauncher = jobs ?: InMemoryJobCenter(viewModelScope, clock)

    private val _state = MutableStateFlow(ContentForgeUiState(providerConfigured = isProviderConfigured(), kept = countOf(creations())))
    val state: StateFlow<ContentForgeUiState> = _state.asStateFlow()

    private var drafted = 0

    init {
        // Each draft carries its job, so the screen and its screenshots need
        // nothing but this state to draw the timeline.
        viewModelScope.launch {
            this@ContentForgeViewModel.jobs.jobs.collect { all ->
                update { copy(drafts = drafts.map { draft -> all.firstOrNull { it.id == draft.jobId }?.let { draft.copy(job = it) } ?: draft }) }
            }
        }
    }

    fun selectKind(kind: ForgeKind) = update { copy(kind = kind, slot = slot?.takeIf { it in copy(kind = kind).slots }, ladder = ladder && copy(kind = kind).offersLadder) }

    fun updatePrompt(prompt: String) = update { copy(prompt = prompt) }

    fun updateTheme(theme: String) = update { copy(theme = theme) }

    fun selectBudget(budget: PowerTier) = update { copy(budget = budget) }

    fun selectSlot(slot: ItemSlot?) = update { copy(slot = if (this.slot == slot) null else slot) }

    fun toggleLadder() = update { copy(ladder = !ladder && offersLadder) }

    /** Item levels the result is for; kept to a range that runs forwards and stays within the game's. */
    fun setLevels(from: Int, to: Int) {
        val low = from.coerceIn(1, MAX_LEVEL)
        update { copy(levelFrom = low, levelTo = to.coerceIn(low, MAX_LEVEL)) }
    }

    fun toggleRecord() = update { copy(showRecord = !showRecord) }

    fun refreshProvider() = update { copy(providerConfigured = isProviderConfigured()) }

    /** Puts the request on the anvil. Another may be asked for while it is written. */
    fun forge() {
        val current = _state.value
        if (current.prompt.isBlank()) return update { copy(error = "Describe what to make first.") }
        val order = current.kind.order(current.prompt.trim(), current.slot, current.ladder)
        val context = ForgeContext(current.theme.trim(), current.levelFrom..current.levelTo, current.budget)
        start(current.kind, order, context)
    }

    /** The open draft's request again: a model asked twice answers twice. */
    fun regenerate() {
        val draft = _state.value.open ?: return forge()
        start(draft.kind, draft.order, draft.context)
    }

    private fun start(kind: ForgeKind, order: ForgeOrder, context: ForgeContext) {
        if (!isProviderConfigured()) return update { copy(error = "Connect a model provider first.", providerConfigured = false) }
        val packs = base()
        if (packs.isEmpty()) return update { copy(error = "No packs are loaded to build on.") }
        val key = "draft-${++drafted}"
        val placeholder = ForgePlaceholder.of(kind, order.prompt)
        // In the state before the job starts: a job can finish before launch returns.
        update { copy(drafts = listOf(ForgeDraft(key, kind, order, context, placeholder)) + drafts, openKey = key, editing = null, error = null, message = null) }
        val name = modelName()?.takeIf { it.isNotBlank() }
        val jobId = jobs.launch(
            kind = if (kind == ForgeKind.LORE) "lore" else "gear",
            title = placeholder.title,
            steps = listOf(WRITING, "Waiting for ${name ?: "the model"}", READING, ForgeJobReport.CHECKING, READY),
        ) { write(key, packs, order, context, name) }
        // Its progress so far is attached now; the collector only sees what changes next.
        updateDraft(key) { copy(jobId = jobId, job = jobs.jobs.value.firstOrNull { it.id == jobId } ?: job) }
    }

    /** The job itself: ask, report every step, and leave the result on its draft. */
    private suspend fun JobReporter.write(key: String, packs: List<ContentPack>, order: ForgeOrder, context: ForgeContext, name: String?): String {
        try {
            val report = ForgeJobReport(this)
            val outcome = ContentForge(model, packs, clock).forge(order, context, observer = observer(name)) { journal ->
                report.onJournal(journal)
                updateDraft(key) { copy(journal = journal) }
            }
            when (outcome) {
                is ForgeOutcome.Forged -> {
                    begin(READY)
                    val cards = ForgeCards.of(outcome.fragment, packs, context.levels.first)
                    updateDraft(key) { copy(journal = outcome.journal, result = outcome.fragment, cards = cards, repairs = outcome.repairs) }
                    return "Made ${cards.joinToString(", ") { it.title }}."
                }
                is ForgeOutcome.Rejected -> {
                    updateDraft(key) { copy(journal = outcome.journal, error = outcome.reason) }
                    update { copy(error = outcome.reason) }
                    fail(outcome.reason)
                }
            }
        } catch (stopped: CancellationException) {
            updateDraft(key) { copy(error = error ?: "Stopped.") }
            throw stopped
        } catch (failure: JobFailure) {
            throw failure
        } catch (failure: Exception) {
            val reason = "The forge could not make that: ${failure.message}"
            updateDraft(key) { copy(error = reason) }
            update { copy(error = reason) }
            fail(reason)
        }
    }

    /** Stops the open draft, if it is still being written. */
    fun cancel() {
        _state.value.open?.let { cancel(it.key) }
    }

    fun cancel(key: String) {
        val draft = _state.value.drafts.firstOrNull { it.key == key } ?: return
        draft.jobId?.let(jobs::cancel)
        // Marked here too: a job cancelled before it started never runs its own cleanup.
        updateDraft(key) { if (running) copy(error = "Stopped.") else this }
    }

    fun openDraft(key: String) = update { copy(openKey = key, editing = null) }

    fun startEdit(id: String, field: ForgeField) {
        val card = _state.value.cards.firstOrNull { it.id == id } ?: return
        update { copy(editing = CardEdit(id, field, if (field == ForgeField.NAME) card.title else card.text)) }
    }

    fun updateEdit(text: String) = update { copy(editing = editing?.copy(text = text)) }

    fun cancelEdit() = update { copy(editing = null) }

    fun commitEdit() {
        val current = _state.value
        val edit = current.editing ?: return
        val draft = current.open ?: return
        val result = draft.result ?: return
        if (edit.field == ForgeField.NAME && edit.text.isBlank()) return update { copy(error = "A name cannot be empty.") }
        val edited = ForgeCards.edit(result, edit.id, edit.field, edit.text.trim())
        updateDraft(draft.key) { copy(result = edited, cards = ForgeCards.of(edited, base(), levelFrom), added = false) }
        update { copy(editing = null) }
    }

    /** Keeps the open result in the player's own plugin, which loads with the next world and shares with the rest. */
    fun addToCreations() {
        val draft = _state.value.open ?: return
        val result = draft.result ?: return
        if (draft.added) return
        Creations.append(creations(), result, base()).fold(
            onSuccess = { appended ->
                updateDraft(draft.key) { copy(added = true) }
                update { copy(kept = countOf(appended.pack), message = "Added ${appended.added.size} to ${Creations.NAME}. It loads with your next world.") }
                viewModelScope.launch {
                    runCatching { saveCreations(appended.pack) }.onFailure { failure ->
                        updateDraft(draft.key) { copy(added = false) }
                        update { copy(error = "Could not save: ${failure.message}") }
                    }
                }
            },
            onFailure = { failure -> update { copy(error = "It would not load beside your other creations: ${failure.message}") } },
        )
    }

    /** Puts the open draft down, stopping it first if it is still being written. */
    fun discard() {
        val draft = _state.value.open ?: return
        if (draft.running) draft.jobId?.let(jobs::cancel)
        update { copy(drafts = drafts.filterNot { it.key == draft.key }, openKey = null, editing = null) }
    }

    fun dismissError() = update { copy(error = null) }

    fun dismissMessage() = update { copy(message = null) }

    private fun updateDraft(key: String, change: ForgeDraft.() -> ForgeDraft) {
        _state.update { state -> state.copy(drafts = state.drafts.map { if (it.key == key) it.change() else it }) }
    }

    private inline fun update(crossinline change: ContentForgeUiState.() -> ContentForgeUiState) {
        _state.update { it.change() }
    }

    companion object {
        private const val MAX_LEVEL = 100
        private const val WRITING = "Writing the request"
        private const val READING = "Reading the reply"
        private const val READY = "Ready to read"

        private fun countOf(pack: ContentPack?): Int =
            pack?.let { it.itemBases.size + it.affixes.size + it.uniques.size + it.itemSets.size + it.loreEntries.size } ?: 0

        fun factory(
            model: LanguageModelPort,
            base: () -> List<ContentPack>,
            isProviderConfigured: () -> Boolean,
            creations: () -> ContentPack?,
            saveCreations: suspend (ContentPack) -> Unit,
            jobs: JobLauncher? = null,
            modelName: () -> String? = { null },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContentForgeViewModel(model, base, isProviderConfigured, creations, saveCreations, jobs = jobs, modelName = modelName) as T
        }
    }
}
