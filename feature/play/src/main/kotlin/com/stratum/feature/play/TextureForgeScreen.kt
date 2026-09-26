package com.stratum.feature.play

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.GameButton
import com.stratum.core.designsystem.component.GameProgress
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent

/** What the texture forge screen can ask for. */
data class TextureForgeActions(
    val onBack: () -> Unit = {},
    val onPrompt: (String) -> Unit = {},
    val onToggleRegion: (String) -> Unit = {},
    val onAllRegions: () -> Unit = {},
    val onToggleActors: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** Plays the world in the style just painted. */
    val onPlay: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun TextureForgeScreen(viewModel: TextureForgeViewModel, actions: TextureForgeActions, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TextureForgeContent(
        state = state,
        actions = actions.copy(
            onPrompt = viewModel::setPrompt,
            onToggleRegion = viewModel::toggleRegion,
            onAllRegions = viewModel::paintAllRegions,
            onToggleActors = viewModel::toggleActors,
            onStart = viewModel::start,
            onStop = viewModel::stop,
        ),
        modifier = modifier,
    )
}

/**
 * The texture forge, laid out as the three steps it is -- describe, choose,
 * paint -- with the progress and the gallery beside them in landscape and
 * under them in portrait, so the run can be watched while it happens.
 */
@Composable
fun TextureForgeContent(state: TextureForgeUiState, actions: TextureForgeActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(colors.surface).safeContent().padding(Space.large)) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Header(actions.onBack)
            Spacer(Modifier.height(Space.medium))
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.large)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Steps(state, actions) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Results(state, actions) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Steps(state, actions)
                    Spacer(Modifier.height(Space.large))
                    Results(state, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    val colors = StratumTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameButton(glyph = "‹", onClick = onBack, size = 44.dp)
        Spacer(Modifier.width(Space.medium))
        Column {
            Text("Texture forge", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Text("Paint your world's ground, walls and props with AI.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Steps(state: TextureForgeUiState, actions: TextureForgeActions) {
    val colors = StratumTheme.colors
    if (!state.hasModel) {
        StratumPanel(Modifier.fillMaxWidth()) {
            Text("The forge paints with an image model.", style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Text("Add an OpenRouter key in Model provider, then come back.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            Spacer(Modifier.height(Space.small))
            StratumAction(label = "Open Model provider", onClick = actions.onOpenSettings, emphasis = ActionEmphasis.PRIMARY)
        }
        Spacer(Modifier.height(Space.medium))
    }

    Step(1, "Describe a look") {
        OutlinedTextField(
            value = state.prompt,
            onValueChange = actions.onPrompt,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.running,
            placeholder = { Text("a moss-choked ruin at dusk, or a candy-coloured toy town") },
        )
        if (state.summary.isNotBlank()) {
            Spacer(Modifier.height(Space.tight))
            Text("Understood as: ${state.summary}", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
        Spacer(Modifier.height(Space.small))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            STYLE_SUGGESTIONS.forEach { suggestion ->
                StratumChip(label = suggestion, selected = state.prompt.equals(suggestion, ignoreCase = true), onClick = { actions.onPrompt(suggestion) })
            }
        }
    }

    Step(2, "Choose where") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumChip(label = "Every region", selected = state.selectedRegions.isEmpty(), onClick = actions.onAllRegions)
            state.regions.forEach { region ->
                StratumChip(label = region.name, selected = region.id in state.selectedRegions, onClick = { actions.onToggleRegion(region.id) })
            }
            StratumChip(label = "Heroes and monsters too", selected = state.includeActors, onClick = actions.onToggleActors)
        }
    }

    Step(3, "Paint") {
        Text(
            "${state.planned} textures to paint" + (if (state.planDescription.isNotBlank()) " · ${state.planDescription}" else "") +
                (if (state.alreadyMade > 0) " · ${state.alreadyMade} already painted" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.small))
        if (state.running) {
            StratumAction(label = "Stop", onClick = actions.onStop, emphasis = ActionEmphasis.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
        } else {
            StratumAction(
                label = if (state.planned == 0) "Nothing left to paint" else "Paint ${state.planned} textures",
                onClick = actions.onStart,
                enabled = state.hasModel && state.planned > 0,
                emphasis = ActionEmphasis.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A numbered step, so the screen reads as something to do in order. */
@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    val colors = StratumTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = colors.surface,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.accent).padding(horizontal = Space.small, vertical = Space.hair),
        )
        Spacer(Modifier.width(Space.small))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
    }
    Spacer(Modifier.height(Space.small))
    content()
    Spacer(Modifier.height(Space.large))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Results(state: TextureForgeUiState, actions: TextureForgeActions) {
    val colors = StratumTheme.colors
    val progress = state.progress
    SectionLabel("Progress")
    Spacer(Modifier.height(Space.small))
    if (progress == null) {
        Text(
            "Textures appear here as they are painted, a few at a time. A run takes a few minutes and costs a few cents; " +
                "stopping keeps whatever is done, and painting again only makes what is missing.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
    } else {
        GameProgress(
            fraction = progress.fraction,
            label = when {
                progress.cancelled -> "Stopped"
                progress.isFinished -> "Finished"
                else -> "Painting…"
            },
            detail = progress.summary,
        )
    }
    state.message?.let {
        Spacer(Modifier.height(Space.small))
        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.accent)
    }
    if (state.finished || state.alreadyMade > 0) {
        Spacer(Modifier.height(Space.medium))
        StratumAction(label = "Play in this style", onClick = { actions.onPlay(state.prompt) }, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth())
    }
    if (state.gallery.isNotEmpty()) {
        Spacer(Modifier.height(Space.medium))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            state.gallery.forEach { thumb -> Thumb(thumb) }
        }
    }
    progress?.failed?.takeIf { it.isNotEmpty() }?.let { failures ->
        Spacer(Modifier.height(Space.medium))
        Text("${failures.size} could not be painted and keep their old look:", style = MaterialTheme.typography.labelSmall, color = colors.danger)
        failures.take(MAX_FAILURES_SHOWN).forEach { (order, reason) ->
            Text("· ${order.subject}: $reason", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thumb(thumb: ForgedThumb) {
    val colors = StratumTheme.colors
    Column(Modifier.width(THUMB), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = thumb.image,
            contentDescription = thumb.label,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.size(THUMB).clip(RoundedCornerShape(8.dp)).border(1.dp, colors.hairline, RoundedCornerShape(8.dp)),
        )
        Text(thumb.label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val THUMB = 72.dp
private const val MAX_FAILURES_SHOWN = 4
