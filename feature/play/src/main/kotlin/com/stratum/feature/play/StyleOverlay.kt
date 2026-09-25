package com.stratum.feature.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.engine.scene.quality.QualityTier

/**
 * Where the player says what their world should look like.
 *
 * A text field rather than a list of presets, because the point of the art
 * layer is that it takes a description — and a preset list would quietly become
 * the real interface, with everything outside it unreachable.
 *
 * The suggestions underneath are not the options. They are the vocabulary: the
 * fastest way to learn that this field takes moods and manners rather than
 * colour names is to see six of them and then type a seventh of your own.
 */
@Composable
fun StyleOverlay(
    state: PlayUiState,
    onRestyle: (String) -> Unit,
    onReroll: () -> Unit,
    onClose: () -> Unit,
    onForge: () -> Unit = {},
    onChooseQuality: (QualityTier?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    var typed by remember(state.stylePrompt) { mutableStateOf(state.stylePrompt) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        StratumPanel(
            modifier = Modifier
                .fillMaxWidth()
                .safeContent()
                .padding(Space.medium),
        ) {
            SectionLabel(text = "World style")

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Describe it") },
                placeholder = { Text("dark grimdark, or kawaii pastel, or a weird old woodblock print") },
            )

            if (state.styleSummary.isNotBlank()) {
                Spacer(Modifier.height(Space.small))
                // What the game understood, in its own words. A player who can
                // see that "kawaii" became "high-key pastels, soft shadows"
                // learns what else they are allowed to ask for.
                Text(
                    text = state.styleSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
            }

            Spacer(Modifier.height(Space.medium))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items(SUGGESTIONS) { suggestion ->
                    StratumChip(
                        label = suggestion,
                        selected = typed.equals(suggestion, ignoreCase = true),
                        onClick = {
                            typed = suggestion
                            onRestyle(suggestion)
                        },
                    )
                }
            }

            Spacer(Modifier.height(Space.medium))
            GraphicsChooser(chosen = state.quality, onChoose = onChooseQuality)

            Spacer(Modifier.height(Space.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(
                    label = "Apply",
                    onClick = { onRestyle(typed) },
                    emphasis = ActionEmphasis.PRIMARY,
                )
                // Same words, different world. The seed is the whole of it.
                StratumAction(
                    label = "Reroll",
                    onClick = onReroll,
                    emphasis = ActionEmphasis.SECONDARY,
                )
                // Paints this style's own textures and props with the image
                // model. The lighting restyle above is instant and free; this
                // is the part that costs a few cents and a minute.
                StratumAction(
                    label = state.forging ?: "Forge art",
                    onClick = onForge,
                    emphasis = ActionEmphasis.SECONDARY,
                )
                StratumAction(
                    label = "Close",
                    onClick = onClose,
                    emphasis = ActionEmphasis.QUIET,
                )
            }
        }
    }
}

/**
 * How hard the renderer works. Auto lets the device decide and keeps
 * adjusting; a tier is a promise to hold that level, with only resolution
 * still traded for frame rate.
 */
@Composable
private fun GraphicsChooser(chosen: QualityTier?, onChoose: (QualityTier?) -> Unit) {
    SectionLabel(text = "Graphics")
    Spacer(Modifier.height(Space.small))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        items(listOf<QualityTier?>(null) + QualityTier.entries) { tier ->
            StratumChip(
                label = tier?.name?.lowercase()?.replaceFirstChar(Char::uppercaseChar) ?: "Auto",
                selected = tier == chosen,
                onClick = { onChoose(tier) },
            )
        }
    }
}

/**
 * Six words that are as unlike each other as the lexicon allows.
 *
 * Chosen to show the range rather than to be the best six: if these are the
 * only ones a player ever tries, they have still seen that the field takes a
 * mood, a genre and a way of drawing.
 */
private val SUGGESTIONS = listOf(
    "dark grimdark",
    "kawaii pastel",
    "woodblock print",
    "chiaroscuro candlelit",
    "toxic corrupted",
    "neon synthwave",
    "frozen arctic",
    "painterly impasto",
)
