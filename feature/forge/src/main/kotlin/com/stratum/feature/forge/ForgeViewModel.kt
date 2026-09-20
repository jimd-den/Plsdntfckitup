package com.stratum.feature.forge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stratum.core.domain.ai.GenerateContentPackUseCase
import com.stratum.core.domain.ai.GenerateLoreUseCase
import com.stratum.core.domain.ai.LoreGenerationRequest
import com.stratum.core.domain.ai.LoreSubject
import com.stratum.core.domain.ai.PackGenerationRequest
import com.stratum.core.domain.content.ContentPack
import com.stratum.core.domain.content.LoreEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the pack forge.
 *
 * All it does is collect a prompt, hand it to a domain use case and report the
 * outcome. Everything that decides whether a generated pack is usable lives in
 * `:core:domain`, where it is tested without a network.
 */
class ForgeViewModel(
    private val generatePack: GenerateContentPackUseCase,
    private val generateLore: GenerateLoreUseCase,
    private val isProviderConfigured: () -> Boolean,
    private val onPackAccepted: (ContentPack) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ForgeUiState(providerConfigured = isProviderConfigured()),
    )
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    fun updateTheme(theme: String) {
        _state.value = _state.value.copy(theme = theme)
    }

    fun refreshProviderStatus() {
        _state.value = _state.value.copy(providerConfigured = isProviderConfigured())
    }

    fun forgePack() {
        val theme = _state.value.theme.trim()
        if (theme.isEmpty()) {
            _state.value = _state.value.copy(error = "Describe the world you want first.")
            return
        }
        if (!isProviderConfigured()) {
            _state.value = _state.value.copy(error = "Add a provider API key in settings first.")
            return
        }

        _state.value = _state.value.copy(status = ForgeStatus.GENERATING, error = null, result = null)

        viewModelScope.launch {
            val result = generatePack(
                PackGenerationRequest(
                    theme = theme,
                    suggestedId = theme.toNamespace(),
                ),
            )
            _state.value = result.fold(
                onSuccess = { pack ->
                    _state.value.copy(status = ForgeStatus.READY, result = pack, error = null)
                },
                onFailure = { cause ->
                    _state.value.copy(
                        status = ForgeStatus.IDLE,
                        error = cause.message ?: "Generation failed.",
                    )
                },
            )
        }
    }

    /** Adds lore to a pack that already generated, without regenerating the world. */
    fun forgeLore() {
        val pack = _state.value.result ?: return
        _state.value = _state.value.copy(status = ForgeStatus.GENERATING, error = null)

        viewModelScope.launch {
            val result = generateLore(
                LoreGenerationRequest(
                    worldSummary = "${pack.name}: ${pack.description}",
                    namespace = pack.id,
                    subjects = pack.biomes.map { LoreSubject(it.id, it.name) } +
                        pack.blocks.take(MAX_LORE_SUBJECTS).map { LoreSubject(it.id, it.displayName) },
                ),
            )
            _state.value = result.fold(
                onSuccess = { entries ->
                    _state.value.copy(
                        status = ForgeStatus.READY,
                        result = pack.copy(loreEntries = pack.loreEntries + entries),
                    )
                },
                onFailure = { cause ->
                    _state.value.copy(
                        status = ForgeStatus.READY,
                        error = cause.message ?: "Lore generation failed.",
                    )
                },
            )
        }
    }

    fun acceptPack() {
        val pack = _state.value.result ?: return
        onPackAccepted(pack)
        _state.value = _state.value.copy(status = ForgeStatus.ACCEPTED)
    }

    fun discard() {
        _state.value = _state.value.copy(status = ForgeStatus.IDLE, result = null, error = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun String.toNamespace(): String =
        lowercase().replace(NON_ID, "_").trim('_').take(MAX_NAMESPACE).ifBlank { "generated" }

    companion object {
        private val NON_ID = Regex("[^a-z0-9]+")
        private const val MAX_NAMESPACE = 16
        private const val MAX_LORE_SUBJECTS = 4

        fun factory(
            generatePack: GenerateContentPackUseCase,
            generateLore: GenerateLoreUseCase,
            isProviderConfigured: () -> Boolean,
            onPackAccepted: (ContentPack) -> Unit,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ForgeViewModel(generatePack, generateLore, isProviderConfigured, onPackAccepted) as T
        }
    }
}

enum class ForgeStatus { IDLE, GENERATING, READY, ACCEPTED }

data class ForgeUiState(
    val theme: String = "",
    val status: ForgeStatus = ForgeStatus.IDLE,
    val result: ContentPack? = null,
    val error: String? = null,
    val providerConfigured: Boolean = false,
) {
    val isBusy: Boolean get() = status == ForgeStatus.GENERATING

    val loreCount: Int get() = result?.loreEntries?.size ?: 0
}
