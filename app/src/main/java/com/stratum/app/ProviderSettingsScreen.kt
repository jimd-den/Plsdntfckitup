package com.stratum.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.designsystem.theme.StratumTheme

/**
 * Where the player points the game at a model provider.
 *
 * The key is their own credential for their own account. It is stored on the
 * device and sent only to the endpoint they configured here, which the screen
 * says plainly rather than burying in a policy.
 */
@Composable
fun ProviderSettingsScreen(
    initial: ProviderConfig,
    onSave: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var imageModel by remember { mutableStateOf(initial.imageModel) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("Model provider")
            StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.medium))

        Text(
            text = "Stratum works with any OpenAI-compatible endpoint: OpenRouter, a hosted " +
                "provider, or a model running on your own machine. Your key is kept on this " +
                "device and sent only to the endpoint below.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(Space.medium))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Text model") },
                placeholder = { Text("google/gemini-2.0-flash-exp:free") },
                supportingText = { Text("Writes content packs and lore.") },
                singleLine = true,
            )
            Spacer(Modifier.height(Space.medium))
            // Its own field, because the model that writes a pack is almost
            // never the one that can draw a sprite sheet. One field shared
            // between the two jobs meant whichever you set broke the other.
            OutlinedTextField(
                value = imageModel,
                onValueChange = { imageModel = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Image model") },
                placeholder = { Text("meta/muse-image") },
                // The pose forge hands the model a picture and asks for it
                // back in a new pose, so the model must accept an image as
                // input -- a text-to-image model fails on every one of forty
                // frames. Named here rather than left to be discovered one
                // generation at a time. Both of these were measured against a
                // real character: they hold the camera angle, keep the costume
                // across an edit, and key out cleanly.
                supportingText = {
                    Text(
                        "Draws sprite sheets and poses. It must accept an image as input. " +
                            "meta/muse-image is the default (square 1600px, ~$0.01 an image, " +
                            "true green backdrop, ~20s); google/gemini-2.5-flash-image is " +
                            "smaller, dearer and faster (1024px, ~$0.039, ~9s).",
                    )
                },
                singleLine = true,
            )
            Spacer(Modifier.height(Space.medium))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Endpoint") },
                singleLine = true,
            )

            Spacer(Modifier.height(Space.large))

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
