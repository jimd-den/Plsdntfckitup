package com.stratum.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.stratum.core.data.ai.ProviderConfig
import com.stratum.core.data.ai.model3d.ModelProvider
import com.stratum.core.data.ai.model3d.ModelProviderConfig
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionHeader
import com.stratum.core.designsystem.component.StatusChip
import com.stratum.core.designsystem.component.StatusTone
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.engine.scene.quality.QualityTier

/**
 * Settings, reached from the gear: the AI that writes and paints, the 3D
 * provider, and how hard the renderer works.
 *
 * The key is the player's own credential for their own account. It is stored
 * on the device and sent only to the endpoint they configure here, which the
 * screen says plainly rather than burying in a policy.
 */
@Composable
fun ProviderSettingsScreen(
    initial: ProviderConfig,
    onSave: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The 3D model provider's settings for a provider; null hides the section. */
    modelProviderFor: ((ModelProvider?) -> ModelProviderConfig)? = null,
    onSaveModelProvider: (ModelProviderConfig) -> Unit = {},
    /** The chosen graphics tier; null is Auto. Hidden when [onChooseGraphics] is null. */
    graphics: QualityTier? = null,
    onChooseGraphics: ((QualityTier?) -> Unit)? = null,
) {
    StratumScreen(title = "Settings", onBack = onBack, modifier = modifier) {
        ProviderSection(initial, onSave)
        if (modelProviderFor != null) ModelProviderSection(modelProviderFor, onSaveModelProvider)
        if (onChooseGraphics != null) GraphicsSection(graphics, onChooseGraphics)
    }
}

@Composable
private fun ProviderSection(initial: ProviderConfig, onSave: (ProviderConfig) -> Unit) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var imageModel by remember { mutableStateOf(initial.imageModel) }
    var videoModel by remember { mutableStateOf(initial.videoModel) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var saved by remember { mutableStateOf(false) }
    val connected = apiKey.isNotBlank()

    Column {
        SectionHeader("AI models")
        Text(
            "Any OpenAI-compatible endpoint: OpenRouter, a hosted provider, or your own machine. " +
                "Your key stays on this device and goes only to the endpoint below.",
            style = MaterialTheme.typography.bodySmall,
            color = StratumTheme.colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))
        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            StatusChip(if (connected) "Connected" else "Not set up", tone = if (connected) StatusTone.READY else StatusTone.NEEDS)
            Spacer(Modifier.height(Space.medium))
            Field(apiKey, { apiKey = it; saved = false }, "API key", secret = true)
            Field(model, { model = it; saved = false }, "Text model", hint = "Writes packs, lore and gear.", placeholder = "google/gemini-2.0-flash-exp:free")
            // Its own field, because the model that writes a pack is almost
            // never the one that can draw a sprite sheet. The pose forge
            // hands it a picture to redraw, so it must take one as input.
            Field(
                imageModel, { imageModel = it; saved = false }, "Image model",
                hint = "Draws sprites and poses; must accept an image as input. meta/muse-image is the default.",
                placeholder = "meta/muse-image",
            )
            // Video models are not in /models at all, so the image model's
            // setting could not name one even if you wanted it to.
            Field(
                videoModel, { videoModel = it; saved = false }, "Video model",
                hint = "Draws the clip an animation is cut from, pinned to a first and last drawing.",
                placeholder = "bytedance/seedance-1-5-pro",
            )
            Field(baseUrl, { baseUrl = it; saved = false }, "Endpoint")
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (saved) "Saved" else "Save",
                onClick = {
                    // Copied from what was loaded rather than built fresh, so a
                    // field this screen does not show is carried through instead
                    // of being silently reset to its default on every save.
                    onSave(
                        initial.copy(
                            apiKey = apiKey.trim(),
                            model = model.trim().ifBlank { initial.model },
                            imageModel = imageModel.trim().ifBlank { initial.imageModel },
                            videoModel = videoModel.trim().ifBlank { initial.videoModel },
                            baseUrl = baseUrl.trim().ifBlank { initial.baseUrl },
                        ),
                    )
                    saved = true
                },
                emphasis = ActionEmphasis.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The 3D provider: a separate account, because no mesh service sits behind
 * the text and image endpoint. Each provider keeps its own key, endpoint and
 * model, so switching to try one does not lose another.
 */
@Composable
private fun ModelProviderSection(
    load: (ModelProvider?) -> ModelProviderConfig,
    onSave: (ModelProviderConfig) -> Unit,
) {
    var config by remember { mutableStateOf(load(null)) }
    var saved by remember { mutableStateOf(false) }
    Column {
        SectionHeader("3D models")
        Spacer(Modifier.height(Space.small))
        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                ModelProvider.entries.forEach { provider ->
                    StratumChip(provider.displayName, selected = config.provider == provider, onClick = { config = load(provider); saved = false })
                }
            }
            Spacer(Modifier.height(Space.medium))
            Field(config.apiKey, { config = config.copy(apiKey = it); saved = false }, "${config.provider.displayName} API key", secret = true)
            Field(
                config.modelId, { config = config.copy(modelId = it); saved = false }, "Model",
                placeholder = config.provider.defaultModel,
                hint = when (config.provider) {
                    ModelProvider.MESHY -> "Meshy's ai_model, e.g. latest."
                    ModelProvider.TRIPO -> "Tripo's model_version."
                    ModelProvider.FAL -> "A fal model path, e.g. fal-ai/trellis."
                    ModelProvider.REPLICATE -> "owner/name, or owner/name:version."
                },
            )
            Field(config.baseUrl, { config = config.copy(baseUrl = it); saved = false }, "Endpoint")
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (saved) "Saved" else "Save 3D provider",
                onClick = {
                    onSave(config.copy(apiKey = config.apiKey.trim(), modelId = config.modelId.trim(), baseUrl = config.baseUrl.trim()))
                    saved = true
                },
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** How hard the renderer works. Auto reads the device and is right for almost everyone. */
@Composable
private fun GraphicsSection(chosen: QualityTier?, onChoose: (QualityTier?) -> Unit) {
    Column {
        SectionHeader("Graphics")
        Spacer(Modifier.height(Space.small))
        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip("Auto", selected = chosen == null, onClick = { onChoose(null) })
                QualityTier.entries.forEach { tier ->
                    StratumChip(tier.name.lowercase().replaceFirstChar { it.uppercase() }, selected = chosen == tier, onClick = { onChoose(tier) })
                }
            }
            Spacer(Modifier.height(Space.small))
            Text(
                "Lower tiers draw less far and at a lower resolution, for a steadier frame rate and a cooler phone.",
                style = MaterialTheme.typography.bodySmall,
                color = StratumTheme.colors.inkMuted,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    placeholder: String? = null,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
    Spacer(Modifier.height(Space.small))
}
