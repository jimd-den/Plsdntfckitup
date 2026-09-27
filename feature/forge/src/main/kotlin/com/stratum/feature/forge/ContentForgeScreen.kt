package com.stratum.feature.forge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.agents.forge.ForgeCard
import com.stratum.agents.forge.ForgeField
import com.stratum.agents.forge.ForgeKind
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.GameProgress
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.item.PowerTier

/** Everything the content forge screen can ask for. */
data class ContentForgeActions(
    val onKind: (ForgeKind) -> Unit = {},
    val onPrompt: (String) -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onBudget: (PowerTier) -> Unit = {},
    val onSlot: (ItemSlot?) -> Unit = {},
    val onLadder: () -> Unit = {},
    val onLevels: (Int, Int) -> Unit = { _, _ -> },
    val onForge: () -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onEdit: (String, ForgeField) -> Unit = { _, _ -> },
    val onEditText: (String) -> Unit = {},
    val onCommitEdit: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRecord: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun ContentForgeScreen(
    viewModel: ContentForgeViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        ContentForgeActions(
            onKind = viewModel::selectKind, onPrompt = viewModel::updatePrompt, onTheme = viewModel::updateTheme,
            onBudget = viewModel::selectBudget, onSlot = viewModel::selectSlot, onLadder = viewModel::toggleLadder,
            onLevels = viewModel::setLevels, onForge = viewModel::forge, onRegenerate = viewModel::regenerate, onCancel = viewModel::cancel,
            onEdit = viewModel::startEdit, onEditText = viewModel::updateEdit, onCommitEdit = viewModel::commitEdit,
            onCancelEdit = viewModel::cancelEdit, onAdd = viewModel::addToCreations, onDiscard = viewModel::discard,
            onRecord = viewModel::toggleRecord, onShare = onShare, onDismissError = viewModel::dismissError,
            onBack = onBack, onOpenSettings = onOpenSettings,
        )
    }
    ContentForgeContent(state, actions, modifier)
}

/**
 * The armoury and the chronicle: pick what to make, say it in a sentence,
 * choose how strong, and read what comes back as the game will show it.
 *
 * A screen of its own rather than a mode of the agent studio: the studio is
 * a long run of many roles with approvals along the way, and this is a
 * short loop -- ask, read, tweak, keep, ask again -- on the same pipeline.
 * The run's record is one tap away for anyone who wants to see what the
 * model was asked and what the repair changed.
 */
@Composable
fun ContentForgeContent(state: ContentForgeUiState, actions: ContentForgeActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(modifier.fillMaxSize().safeContent().verticalScroll(rememberScrollState()).padding(Space.large)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Content forge", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Spacer(Modifier.weight(1f))
            StratumAction(label = "Back", onClick = actions.onBack, emphasis = ActionEmphasis.QUIET)
        }
        Text(
            "Lore, weapons, armour, uniques, sets and affixes, written by a model from the words this world already uses. " +
                "Broken builds welcome -- every result says how strong it is.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.large))
        Request(state, actions)
        state.error?.let {
            Spacer(Modifier.height(Space.small))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.clickable(onClick = actions.onDismissError))
        }
        state.journal?.takeIf { state.running }?.let { journal ->
            Spacer(Modifier.height(Space.medium))
            val attempts = journal.steps.sumOf { it.attempts.size }
            GameProgress(journal.fraction, label = if (attempts > 1) "Attempt $attempts -- the last reply needed fixing" else "Writing…")
        }
        if (state.cards.isNotEmpty()) {
            Spacer(Modifier.height(Space.large))
            Results(state, actions)
        }
        Spacer(Modifier.height(Space.large))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.kept == 0) "Nothing kept yet." else "${state.kept} things in your creations.",
                style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.weight(1f),
            )
            StratumAction(label = "Share my creations", onClick = actions.onShare, emphasis = ActionEmphasis.QUIET, enabled = state.kept > 0)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Request(state: ContentForgeUiState, actions: ContentForgeActions) {
    val colors = StratumTheme.colors
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            ForgeKind.entries.forEach { kind ->
                StratumChip(label = "${kind.glyph} ${kind.label}", selected = kind == state.kind, onClick = { if (!state.running) actions.onKind(kind) })
            }
        }
        Spacer(Modifier.height(Space.medium))
        OutlinedTextField(
            value = state.prompt, onValueChange = actions.onPrompt, modifier = Modifier.fillMaxWidth(),
            label = { Text(promptLabel(state.kind)) }, placeholder = { Text(placeholder(state.kind)) },
            enabled = !state.running, minLines = 2,
        )
        Spacer(Modifier.height(Space.small))
        OutlinedTextField(
            value = state.theme, onValueChange = actions.onTheme, modifier = Modifier.fillMaxWidth(),
            label = { Text("Setting (optional)") }, enabled = !state.running, singleLine = true,
        )
        if (state.slots.isNotEmpty()) {
            Spacer(Modifier.height(Space.medium))
            Text(if (state.kind == ForgeKind.ARMOUR) "Worn on" else "For (optional)", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Spacer(Modifier.height(Space.tight))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                state.slots.forEach { slot ->
                    StratumChip(label = "${slot.glyph} ${slot.name.lowercase()}", selected = slot == state.slot, onClick = { actions.onSlot(slot) })
                }
            }
        }
        if (state.offersLadder) {
            Spacer(Modifier.height(Space.small))
            StratumChip(label = "A ladder: one family, climbing the levels", selected = state.ladder, onClick = actions.onLadder)
        }
        Spacer(Modifier.height(Space.medium))
        Text("How strong", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(Space.tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            PowerTier.entries.forEach { tier ->
                StratumChip(label = tier.label, selected = tier == state.budget, onClick = { actions.onBudget(tier) })
            }
        }
        Text(budgetNote(state.budget), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(Space.medium))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            Text("Item levels", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            OutlinedTextField(
                value = state.levelFrom.toString(), onValueChange = { text -> text.toIntOrNull()?.let { actions.onLevels(it, state.levelTo) } },
                modifier = Modifier.width(80.dp), singleLine = true, enabled = !state.running,
            )
            Text("to", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            OutlinedTextField(
                value = state.levelTo.toString(), onValueChange = { text -> text.toIntOrNull()?.let { actions.onLevels(state.levelFrom, it) } },
                modifier = Modifier.width(80.dp), singleLine = true, enabled = !state.running,
            )
        }
        Spacer(Modifier.height(Space.medium))
        when {
            !state.providerConfigured -> StratumAction(label = "Connect a model first", onClick = actions.onOpenSettings, emphasis = ActionEmphasis.SECONDARY, modifier = Modifier.fillMaxWidth())
            state.running -> StratumAction(label = "Stop", onClick = actions.onCancel, emphasis = ActionEmphasis.SECONDARY, modifier = Modifier.fillMaxWidth())
            else -> StratumAction(label = "Forge it", onClick = actions.onForge, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Results(state: ContentForgeUiState, actions: ContentForgeActions) {
    val colors = StratumTheme.colors
    SectionLabel("What came back")
    Spacer(Modifier.height(Space.small))
    state.cards.forEach { card ->
        Card(card, state.editing?.takeIf { it.id == card.id }, actions)
        Spacer(Modifier.height(Space.small))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(
            label = if (state.added) "Kept" else "Add to my plugin", onClick = actions.onAdd, enabled = !state.added && !state.running,
            emphasis = ActionEmphasis.PRIMARY,
        )
        StratumAction(label = "Regenerate", onClick = actions.onRegenerate, enabled = !state.running)
        StratumAction(label = "Discard", onClick = actions.onDiscard, emphasis = ActionEmphasis.QUIET, enabled = !state.running)
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.accent) }
    Spacer(Modifier.height(Space.small))
    Text(
        (if (state.showRecord) "▾ " else "▸ ") + "The record: ${state.repairs.size} repairs",
        style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.clickable(onClick = actions.onRecord),
    )
    if (state.showRecord) {
        state.repairs.forEach { Text("✎ $it", style = MaterialTheme.typography.labelSmall, color = colors.ink) }
        state.journal?.let { Code(it.toMarkdown(), limit = RECORD_LIMIT) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(card: ForgeCard, editing: CardEdit?, actions: ContentForgeActions) {
    val colors = StratumTheme.colors
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.glyph, fontSize = 20.sp, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.clickable { actions.onEdit(card.id, ForgeField.NAME) })
                Text(card.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            }
            card.power?.let { power ->
                val tint = when (power.tier) {
                    PowerTier.BALANCED -> colors.accentAlt
                    PowerTier.STRONG -> colors.accent
                    PowerTier.BROKEN -> colors.danger
                }
                Text(power.label, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
        Spacer(Modifier.height(Space.tight))
        card.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.ink) }
        if (card.text.isNotBlank()) {
            Spacer(Modifier.height(Space.tight))
            Text(
                card.text, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted,
                fontStyle = if (card.section == "lore") FontStyle.Normal else FontStyle.Italic,
                modifier = Modifier.clickable { actions.onEdit(card.id, ForgeField.TEXT) },
            )
        }
        if (editing != null) {
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(
                value = editing.text, onValueChange = actions.onEditText, modifier = Modifier.fillMaxWidth(),
                label = { Text(editing.field.label) }, minLines = if (editing.field == ForgeField.TEXT) 2 else 1,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(label = "Save", onClick = actions.onCommitEdit, emphasis = ActionEmphasis.PRIMARY)
                StratumAction(label = "Cancel", onClick = actions.onCancelEdit, emphasis = ActionEmphasis.QUIET)
            }
        } else {
            Text("Tap the name or the text to rewrite it.", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
    }
}

private fun promptLabel(kind: ForgeKind): String = when (kind) {
    ForgeKind.LORE -> "What should be written down?"
    ForgeKind.WEAPON -> "Describe the weapon"
    ForgeKind.ARMOUR -> "Describe the armour"
    ForgeKind.UNIQUE -> "Describe the unique, and the rule it bends"
    ForgeKind.SET -> "Describe the set and what binds it"
    ForgeKind.AFFIXES -> "What should the affixes do?"
}

private fun placeholder(kind: ForgeKind): String = when (kind) {
    ForgeKind.LORE -> "The river spirit who takes a toll from every ferry"
    ForgeKind.WEAPON -> "A heavy bronze cleaver cast for executions"
    ForgeKind.ARMOUR -> "Raffia armour woven by the masquerade societies"
    ForgeKind.UNIQUE -> "A ring that makes every skill cost blood but hits like thunder"
    ForgeKind.SET -> "The regalia of a drowned king"
    ForgeKind.AFFIXES -> "Affixes about storms and speed, for weapons and gloves"
}

private fun budgetNote(tier: PowerTier): String = when (tier) {
    PowerTier.BALANCED -> "Scaled to sit beside the gear this world already drops."
    PowerTier.STRONG -> "Clearly an upgrade; only scaled down past strong."
    PowerTier.BROKEN -> "Anything goes, as long as it loads. Labelled so you know."
}

private const val RECORD_LIMIT = 6000
