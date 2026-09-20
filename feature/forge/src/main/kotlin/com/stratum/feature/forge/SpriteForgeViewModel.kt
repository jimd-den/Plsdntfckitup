package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.GenerateSpriteSheetUseCase
import com.stratum.core.domain.ai.SheetLayout
import com.stratum.core.domain.ai.SpriteSheetRequest
import com.stratum.core.domain.ai.GenerationAttempt
import com.stratum.core.domain.ai.GenerationJournal
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.GenerationStage
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.GridOutcome
import com.stratum.core.domain.sprite.KeyStrategy
import com.stratum.core.domain.sprite.SheetPreparation
import com.stratum.core.domain.sprite.SpriteSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives sprite sheet generation.
 *
 * Generated sheets are saved immediately and kept, however they turned out. A
 * mediocre sheet is still better than a coloured disc, and letting the player
 * regenerate is cheaper than making them judge one before it is stored.
 */
class SpriteForgeViewModel(
    private val generateSheet: GenerateSpriteSheetUseCase,
    /**
     * Saves the sheet and reports what had to be done to it: the background
     * keyed out, and the grid checked against what the model actually drew.
     */
    private val saveSheet: (SpriteSheet, ByteArray) -> SheetPreparation,
    private val loadSheets: () -> List<SpriteSheet>,
    private val deleteSheet: (String) -> Unit,
    private val isProviderConfigured: () -> Boolean,
    /** The last few provider calls, so a failure can be read rather than guessed at. */
    private val journal: GenerationJournal = GenerationJournal(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        SpriteForgeUiState(
            sheets = loadSheets(),
            providerConfigured = isProviderConfigured(),
        ),
    )
    val state: StateFlow<SpriteForgeUiState> = _state.asStateFlow()

    fun updateSubject(subject: String) {
        _state.value = _state.value.copy(subject = subject)
    }

    fun updateStyle(style: String) {
        _state.value = _state.value.copy(style = style)
    }

    /** Fills the style field from a preset, or clears it by tapping the chosen one again. */
    fun selectStyle(style: SpriteStyle) {
        val current = _state.value
        _state.value = if (current.style == style.direction) {
            current.copy(style = "")
        } else {
            current.copy(style = style.direction)
        }
    }

    fun selectTarget(target: SpriteTarget) {
        _state.value = _state.value.copy(target = target)
    }

    /** Which action a single-action generation should draw. */
    fun selectAction(action: AnimationState) {
        _state.value = _state.value.copy(action = action)
    }

    fun refresh() {
        _state.value = _state.value.copy(
            sheets = loadSheets(),
            providerConfigured = isProviderConfigured(),
        )
    }

    fun generate() {
        val current = _state.value
        val subject = current.subject.trim()
        if (subject.isEmpty()) {
            _state.value = current.copy(error = "Describe what to draw first.")
            return
        }
        if (!isProviderConfigured()) {
            _state.value = current.copy(error = "Add a provider API key in settings first.")
            return
        }

        _state.value = current.copy(
            busy = true,
            error = null,
            lastGenerated = null,
            stage = GenerationStage.PREPARING,
            attempt = null,
        )

        // Reports what the adapter is doing and exactly what it sent. An image
        // model can sit on a request for minutes; a spinner that says nothing
        // for minutes is indistinguishable from a hang, and a rejection with no
        // request to look at is indistinguishable from a bug.
        val observer = object : GenerationObserver {
            override fun onStage(stage: GenerationStage) {
                _state.value = _state.value.copy(stage = stage)
            }

            override fun onAttempt(attempt: GenerationAttempt) {
                journal.record(attempt)
                _state.value = _state.value.copy(attempt = attempt)
            }
        }

        viewModelScope.launch {
            val result = generateSheet(
                SpriteSheetRequest(
                    subject = subject,
                    namespace = current.target.namespace,
                    styleDirection = current.style,
                    layout = current.target.layoutFor(current.action),
                    // Without this, generating a walk block and then an attack
                    // block of the same warrior would put the second on top of
                    // the first, taking the mapping of the first with it.
                    variant = if (current.target.usesAction) current.action.name else "",
                ),
                observer,
            )
            _state.value = result.fold(
                onSuccess = { generated ->
                    _state.value = _state.value.copy(stage = GenerationStage.SAVING)
                    val prepared = saveSheet(generated.sheet, generated.image.bytes)
                    _state.value.copy(
                        busy = false,
                        stage = GenerationStage.DONE,
                        sheets = loadSheets(),
                        // The sheet as stored, which is not always the sheet as
                        // asked for: a model that drew a different grid is cut
                        // on the one it drew.
                        lastGenerated = prepared.sheet,
                        keyStrategy = prepared.keyStrategy,
                        gridNote = when {
                            // Said first, because an empty sheet makes every
                            // other observation about it beside the point.
                            prepared.looksEmpty ->
                                "The model returned an all but blank image — there is nothing " +
                                    "to draw, so the world will keep showing the fallback shape. " +
                                    "Try again, or a different model."
                            prepared.grid.outcome != GridOutcome.AS_ASKED -> prepared.grid.summary
                            else -> null
                        },
                        error = null,
                    )
                },
                onFailure = { cause ->
                    _state.value.copy(
                        busy = false,
                        stage = GenerationStage.FAILED,
                        error = cause.message ?: "Generation failed.",
                    )
                },
            )
        }
    }

    /** Opens or closes the panel showing exactly what was sent and what came back. */
    fun toggleDetails() {
        _state.value = _state.value.copy(detailsOpen = !_state.value.detailsOpen)
    }

    fun delete(sheetId: String) {
        deleteSheet(sheetId)
        _state.value = _state.value.copy(
            sheets = loadSheets(),
            lastGenerated = _state.value.lastGenerated?.takeIf { it.id != sheetId },
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        fun factory(
            generateSheet: GenerateSpriteSheetUseCase,
            saveSheet: (SpriteSheet, ByteArray) -> SheetPreparation,
            loadSheets: () -> List<SpriteSheet>,
            deleteSheet: (String) -> Unit,
            isProviderConfigured: () -> Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SpriteForgeViewModel(
                generateSheet, saveSheet, loadSheets, deleteSheet, isProviderConfigured,
            ) as T
        }
    }
}

/**
 * What the sheet is for. Decides how many rows to ask for: a monster that only
 * walks does not need an attack row, and asking a weak image model for fewer
 * frames is the single most effective way to get usable art out of it.
 */
/**
 * Ready-made style directions.
 *
 * "Style (optional)" left empty is the single biggest cause of a sheet that
 * comes back as one soft illustration: a model given no style draws a picture,
 * and a picture is not a sprite. These name the handful of styles that survive
 * being cut into cells and drawn at forty pixels tall.
 */
enum class SpriteStyle(val label: String, val direction: String) {
    PIXEL(
        "Pixel art",
        "Pixel art game sprites, limited palette, bold readable silhouette, dark outline.",
    ),
    CHUNKY(
        "Chunky",
        "Chunky low-resolution pixel art, very few colours, thick dark outline, no anti-aliasing.",
    ),
    INKED(
        "Inked",
        "Flat cel-shaded cartoon sprites, heavy black ink outline, simple shapes, no gradients.",
    ),
    GRIM(
        "Grim",
        "Dark fantasy game sprites, muted palette, hard rim light, heavy silhouette, no background.",
    ),
}

enum class SpriteTarget(
    val label: String,
    val namespace: String,
    /** Whether this target draws one named action, so the screen offers a choice of it. */
    val usesAction: Boolean = false,
) {
    /** The character you play. Worth the denser sheet and the extra frames. */
    HERO("Hero", "hero"),

    /** A monster fights and dies; it does not need a signature power. */
    MONSTER("Monster", "monster"),

    /**
     * Six frames of one action, and the most likely of these to come back
     * usable. A full sheet asks a model to hold a character consistent across
     * forty-two cells and seven activities; this asks for six cells of one.
     */
    ACTION("One action", "action", usesAction = true),

    /**
     * One drawing. What a weak model produces anyway -- asked for deliberately,
     * so it arrives composed and centred rather than as a grid-shaped accident.
     */
    POSE("One pose", "pose"),
    ;

    fun layoutFor(action: AnimationState): SheetLayout = when (this) {
        HERO -> SheetLayout.detailed()
        MONSTER -> SheetLayout.standard()
        ACTION -> SheetLayout.action(action)
        POSE -> SheetLayout.pose()
    }
}

data class SpriteForgeUiState(
    val subject: String = "",
    val style: String = "",
    val target: SpriteTarget = SpriteTarget.HERO,
    /**
     * Walk by default: it is the animation a player sees most and the one a
     * still reads worst as, so it is the first one worth spending a generation
     * on.
     */
    val action: AnimationState = AnimationState.WALK,
    val busy: Boolean = false,
    val sheets: List<SpriteSheet> = emptyList(),
    val lastGenerated: SpriteSheet? = null,
    val error: String? = null,
    val providerConfigured: Boolean = false,
    val stage: GenerationStage? = null,
    /** The provider call behind the current result, successful or not. */
    val attempt: GenerationAttempt? = null,
    val detailsOpen: Boolean = false,
    /** How the last sheet's background was dealt with, for the player to see. */
    val keyStrategy: KeyStrategy? = null,
    /** Set when the model did not draw the grid it was asked for. */
    val gridNote: String? = null,
) {
    val progress: Float get() = stage?.fraction ?: 0f

    val stageLabel: String? get() = stage?.label
}
