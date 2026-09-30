package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.GeneratedImage
import com.stratum.core.domain.ai.GenerationObserver
import com.stratum.core.domain.ai.WeaponRequest
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.WeaponFit
import com.stratum.core.domain.sprite.WeaponKind
import com.stratum.core.domain.sprite.WeaponSprite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Draws weapons nobody owns yet.
 *
 * Short, because a weapon is a single drawing with no animation of its own —
 * the swing lives in the rig, not in the art. That asymmetry is the point of
 * the whole mechanism: one generation buys a sword that every character in the
 * game can pick up, swing and drop, instead of one sword welded to one hero.
 */
class WeaponForgeViewModel(
    private val drawWeapon: suspend (WeaponRequest, GenerationObserver) -> Result<GeneratedImage>,
    /** Keys, trims and stores it; answers null when there was nothing on the canvas. */
    private val storeWeapon: (WeaponRequest, ByteArray) -> WeaponSprite?,
    private val loadWeapons: () -> List<WeaponSprite>,
    private val deleteWeapon: (String) -> Unit,
    /** Characters a weapon can be fitted to. */
    private val loadSheets: () -> List<SpriteSheet>,
    private val fitFor: (String) -> WeaponFit,
    private val saveFit: (String, WeaponFit) -> Unit,
    private val isProviderConfigured: () -> Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(
        WeaponForgeUiState(
            weapons = loadWeapons(),
            sheets = loadSheets(),
            providerConfigured = isProviderConfigured(),
        ),
    )
    val state: StateFlow<WeaponForgeUiState> = _state.asStateFlow()

    fun refresh() {
        val sheets = loadSheets()
        val current = _state.value
        // Falls onto the first character there is, so the fit controls are
        // usable the moment a weapon and a character both exist.
        val fitting = current.fittingSheetId ?: sheets.firstOrNull()?.id
        _state.value = current.copy(
            weapons = loadWeapons(),
            sheets = sheets,
            providerConfigured = isProviderConfigured(),
            fittingSheetId = fitting,
            fit = fitting?.let(fitFor) ?: WeaponFit.none,
        )
    }

    // ---- fitting a weapon to a character ---------------------------------

    fun selectFittingSheet(sheetId: String) {
        _state.value = _state.value.copy(
            fittingSheetId = sheetId,
            fit = fitFor(sheetId),
            previewFrame = 0,
        )
    }

    /**
     * Steps through the frames of the animation being fitted.
     *
     * An attack is where a weapon is most obviously right or wrong, so the
     * whole arc is walkable rather than showing one representative pose: a
     * weapon that sits perfectly in the wind-up can still be a hand's width
     * adrift at the follow-through.
     */
    fun stepPreview(forward: Boolean) {
        val frames = _state.value.previewFrameCount
        if (frames <= 0) return
        val next = (_state.value.previewFrame + if (forward) 1 else -1 + frames) % frames
        _state.value = _state.value.copy(previewFrame = next)
    }

    /**
     * Nudges where the weapon sits on this character.
     *
     * The arcs stay as authored and the character supplies the correction,
     * because the shape of a swing is universal and a figure's proportions are
     * not. Authoring every anchor per character is work nobody does twice.
     */
    fun nudgeFit(dx: Float = 0f, dy: Float = 0f, scale: Float = 0f) {
        val sheetId = _state.value.fittingSheetId ?: return
        val current = _state.value.fit
        val next = WeaponFit(
            offsetX = (current.offsetX + dx).coerceIn(-WeaponFit.MAX_OFFSET, WeaponFit.MAX_OFFSET),
            offsetY = (current.offsetY + dy).coerceIn(-WeaponFit.MAX_OFFSET, WeaponFit.MAX_OFFSET),
            scale = (current.scale + scale).coerceIn(WeaponFit.MIN_SCALE, WeaponFit.MAX_SCALE),
        )
        saveFit(sheetId, next)
        _state.value = _state.value.copy(fit = next)
    }

    /**
     * Flips the rig, because the drawing came back mirrored from the guide.
     *
     * Its own control rather than a wider offset range: measured, the hand was
     * at 0.86 of the frame where the rig expected 0.2, and halfway between
     * those is the character's navel.
     */
    fun toggleMirror() {
        val sheetId = _state.value.fittingSheetId ?: return
        val next = _state.value.fit.copy(mirrored = !_state.value.fit.mirrored)
        saveFit(sheetId, next)
        _state.value = _state.value.copy(fit = next)
    }

    fun resetFit() {
        val sheetId = _state.value.fittingSheetId ?: return
        saveFit(sheetId, WeaponFit.none)
        _state.value = _state.value.copy(fit = WeaponFit.none)
    }

    fun updateSubject(subject: String) {
        _state.value = _state.value.copy(subject = subject)
    }

    fun selectKind(kind: WeaponKind) {
        _state.value = _state.value.copy(kind = kind)
    }

    fun updateStyle(style: String) {
        _state.value = _state.value.copy(style = style)
    }

    fun generate() {
        val current = _state.value
        val subject = current.subject.trim()
        if (subject.isEmpty()) {
            _state.value = current.copy(error = "Describe the weapon first.")
            return
        }
        if (!isProviderConfigured()) {
            _state.value = current.copy(error = "Add a provider API key in settings first.")
            return
        }

        val request = WeaponRequest(
            subject = subject,
            kind = current.kind,
            styleDirection = current.style,
        )
        _state.value = current.copy(busy = true, error = null, message = null, lastDrawn = null)

        viewModelScope.launch {
            val result = drawWeapon(request, GenerationObserver.None)
            _state.value = result.fold(
                onSuccess = { image ->
                    val stored = storeWeapon(request, image.bytes)
                    if (stored == null) {
                        _state.value.copy(
                            busy = false,
                            // The specific failure: everything keyed away, so
                            // there is no weapon left. Worth saying, because a
                            // silently absent weapon is indistinguishable from
                            // a bug in the renderer.
                            error = "Nothing was left after the background was removed. The " +
                                "model probably drew the weapon in the background colour.",
                        )
                    } else {
                        _state.value.copy(
                            busy = false,
                            weapons = loadWeapons(),
                            lastDrawn = stored,
                            message = "${stored.name} drawn. Any character can carry it.",
                        )
                    }
                },
                onFailure = { cause ->
                    _state.value.copy(busy = false, error = cause.message ?: "Generation failed.")
                },
            )
        }
    }

    fun delete(weaponId: String) {
        deleteWeapon(weaponId)
        _state.value = _state.value.copy(
            weapons = loadWeapons(),
            lastDrawn = _state.value.lastDrawn?.takeIf { it.id != weaponId },
        )
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    companion object {
        fun factory(
            drawWeapon: suspend (WeaponRequest, GenerationObserver) -> Result<GeneratedImage>,
            storeWeapon: (WeaponRequest, ByteArray) -> WeaponSprite?,
            loadWeapons: () -> List<WeaponSprite>,
            deleteWeapon: (String) -> Unit,
            loadSheets: () -> List<SpriteSheet>,
            fitFor: (String) -> WeaponFit,
            saveFit: (String, WeaponFit) -> Unit,
            isProviderConfigured: () -> Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WeaponForgeViewModel(
                drawWeapon, storeWeapon, loadWeapons, deleteWeapon, loadSheets, fitFor, saveFit,
                isProviderConfigured,
            ) as T
        }
    }
}

data class WeaponForgeUiState(
    val subject: String = "",
    val kind: WeaponKind = WeaponKind.SWORD,
    val style: String = "",
    val busy: Boolean = false,
    val weapons: List<WeaponSprite> = emptyList(),
    val lastDrawn: WeaponSprite? = null,
    val equippedId: String? = null,
    val providerConfigured: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Characters available to fit a weapon to. */
    val sheets: List<SpriteSheet> = emptyList(),
    val fittingSheetId: String? = null,
    val fit: WeaponFit = WeaponFit.none,
    val previewFrame: Int = 0,
) {
    val fittingSheet: SpriteSheet? get() = sheets.firstOrNull { it.id == fittingSheetId }

    /**
     * The clip the fit is judged against.
     *
     * An attack when there is one, because that is where a weapon is most
     * obviously right or wrong; otherwise whatever the sheet has.
     */
    val previewClip get() = fittingSheet?.let { sheet ->
        sheet.clip(AnimationState.ATTACK) ?: sheet.clips.firstOrNull()
    }

    val previewFrameCount: Int get() = previewClip?.frameCount ?: 0

    /** The frame index into the sheet, not into the clip. */
    val previewSheetFrame: Int
        get() = previewClip?.let { it.firstFrame + previewFrame.coerceIn(0, it.frameCount - 1) } ?: 0
}
