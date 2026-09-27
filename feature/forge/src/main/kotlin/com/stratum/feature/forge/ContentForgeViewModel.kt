package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.agents.StudioJournal
import com.stratum.agents.forge.ContentForge
import com.stratum.agents.forge.Creations
import com.stratum.agents.forge.ForgeCard
import com.stratum.agents.forge.ForgeCards
import com.stratum.agents.forge.ForgeContext
import com.stratum.agents.forge.ForgeField
import com.stratum.agents.forge.ForgeKind
import com.stratum.agents.forge.ForgeOutcome
import com.stratum.core.domain.ai.LanguageModelPort
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.item.ItemCategory
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One field of one card, open for rewriting. */
data class CardEdit(val id: String, val field: ForgeField, val text: String)

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
    val running: Boolean = false,
    val journal: StudioJournal? = null,
    /** What was made, with any edits, before it is kept. */
    val result: ContentPack? = null,
    val cards: List<ForgeCard> = emptyList(),
    val repairs: List<String> = emptyList(),
    val editing: CardEdit? = null,
    val showRecord: Boolean = false,
    val added: Boolean = false,
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
}

/**
 * Drives the content forge: what to make, how strong, at what depth; the
 * run on the agent pipeline; the result as cards to read, rename and
 * reword; and keeping it in the player's own plugin.
 *
 * Holds no rules of its own. Generation, repair, budget, cards, edits and
 * the growing plugin are all in `:agents`, and tested there; this only
 * sequences them and keeps the screen's state.
 */
class ContentForgeViewModel(
    private val model: LanguageModelPort,
    private val base: () -> List<ContentPack>,
    private val isProviderConfigured: () -> Boolean,
    /** The player's own plugin as installed now, or null before anything was kept. */
    private val creations: () -> ContentPack?,
    private val saveCreations: suspend (ContentPack) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(ContentForgeUiState(providerConfigured = isProviderConfigured(), kept = countOf(creations())))
    val state: StateFlow<ContentForgeUiState> = _state.asStateFlow()

    private var run: Job? = null

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

    fun forge() {
        val current = _state.value
        if (current.running) return
        if (current.prompt.isBlank()) return update { copy(error = "Describe what to make first.") }
        if (!isProviderConfigured()) return update { copy(error = "Connect a model provider first.", providerConfigured = false) }
        val packs = base()
        if (packs.isEmpty()) return update { copy(error = "No packs are loaded to build on.") }
        val order = current.kind.order(current.prompt.trim(), current.slot, current.ladder)
        val context = ForgeContext(current.theme.trim(), current.levelFrom..current.levelTo, current.budget)
        update { copy(running = true, journal = null, result = null, cards = emptyList(), repairs = emptyList(), editing = null, added = false, error = null, message = null) }
        run = viewModelScope.launch {
            val outcome = ContentForge(model, packs, clock).forge(order, context) { journal -> update { copy(journal = journal) } }
            when (outcome) {
                is ForgeOutcome.Forged -> update {
                    copy(running = false, journal = outcome.journal, result = outcome.fragment, cards = ForgeCards.of(outcome.fragment, packs, current.levelFrom), repairs = outcome.repairs)
                }
                is ForgeOutcome.Rejected -> update { copy(running = false, journal = outcome.journal, error = outcome.reason) }
            }
        }
    }

    /** The same request again: a model asked twice answers twice. */
    fun regenerate() = forge()

    fun cancel() {
        run?.cancel()
        update { copy(running = false, error = "Stopped.") }
    }

    fun startEdit(id: String, field: ForgeField) {
        val card = _state.value.cards.firstOrNull { it.id == id } ?: return
        update { copy(editing = CardEdit(id, field, if (field == ForgeField.NAME) card.title else card.text)) }
    }

    fun updateEdit(text: String) = update { copy(editing = editing?.copy(text = text)) }

    fun cancelEdit() = update { copy(editing = null) }

    fun commitEdit() {
        val current = _state.value
        val edit = current.editing ?: return
        val result = current.result ?: return
        if (edit.field == ForgeField.NAME && edit.text.isBlank()) return update { copy(error = "A name cannot be empty.") }
        val edited = ForgeCards.edit(result, edit.id, edit.field, edit.text.trim())
        update { copy(result = edited, cards = ForgeCards.of(edited, base(), levelFrom), editing = null, added = false) }
    }

    /** Keeps the result in the player's own plugin, which loads with the next world and shares with the rest. */
    fun addToCreations() {
        val current = _state.value
        val result = current.result ?: return
        if (current.added) return
        Creations.append(creations(), result, base()).fold(
            onSuccess = { appended ->
                update { copy(added = true, kept = countOf(appended.pack), message = "Added ${appended.added.size} to ${Creations.NAME}. It loads with your next world.") }
                viewModelScope.launch {
                    runCatching { saveCreations(appended.pack) }.onFailure { failure ->
                        update { copy(added = false, error = "Could not save: ${failure.message}") }
                    }
                }
            },
            onFailure = { failure -> update { copy(error = "It would not load beside your other creations: ${failure.message}") } },
        )
    }

    fun discard() = update { copy(result = null, cards = emptyList(), repairs = emptyList(), journal = null, editing = null, added = false) }

    fun dismissError() = update { copy(error = null) }

    fun dismissMessage() = update { copy(message = null) }

    private inline fun update(change: ContentForgeUiState.() -> ContentForgeUiState) {
        _state.value = _state.value.change()
    }

    companion object {
        private const val MAX_LEVEL = 100

        private fun countOf(pack: ContentPack?): Int =
            pack?.let { it.itemBases.size + it.affixes.size + it.uniques.size + it.itemSets.size + it.loreEntries.size } ?: 0

        fun factory(
            model: LanguageModelPort,
            base: () -> List<ContentPack>,
            isProviderConfigured: () -> Boolean,
            creations: () -> ContentPack?,
            saveCreations: suspend (ContentPack) -> Unit,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContentForgeViewModel(model, base, isProviderConfigured, creations, saveCreations) as T
        }
    }
}
