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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.agents.AgentAttempt
import com.stratum.agents.StepStatus
import com.stratum.agents.StudioStep
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.GameProgress
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.ai.AgentRoleDefinition

/** Everything the studio screen can ask for. */
data class CrewActions(
    val onPrompt: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onToggleRole: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenStep: (String) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onApprove: () -> Unit = {},
    val onRevise: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun CrewScreen(viewModel: CrewViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        CrewActions(
            onPrompt = viewModel::updatePrompt, onName = viewModel::updateName, onToggleRole = viewModel::toggleRole,
            onStart = viewModel::start, onCancel = viewModel::cancel, onOpenStep = viewModel::openStep,
            onNote = viewModel::updateNote, onApprove = viewModel::approve, onRevise = { viewModel.revise() }, onSkip = viewModel::skip,
            onInstall = viewModel::install, onBack = onBack, onOpenSettings = onOpenSettings,
        )
    }
    CrewScreenContent(state, actions, modifier)
}

/**
 * The agent studio: a brief, a crew, and the whole run in the open. Each
 * role's step can be opened to read exactly what it was asked, what it
 * wrote and why anything was sent back.
 */
@Composable
fun CrewScreenContent(state: CrewUiState, actions: CrewActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(modifier.fillMaxSize().safeContent().verticalScroll(rememberScrollState()).padding(Space.large)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Agent studio", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Spacer(Modifier.weight(1f))
            StratumAction(label = "Back", onClick = actions.onBack, emphasis = ActionEmphasis.QUIET)
        }
        Text(
            "Describe a world. A crew of agents writes it the way a small team would -- lore, land, monsters, towns, " +
                "food, outposts, rules -- each checked like a plugin before the next begins. Everything they are asked and " +
                "everything they answer is on the record below.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.large))
        Brief(state, actions)
        state.review?.let {
            Spacer(Modifier.height(Space.large))
            ReviewCard(state, actions)
        }
        state.result?.let {
            Spacer(Modifier.height(Space.large))
            ResultCard(state, actions)
        }
        state.error?.let {
            Spacer(Modifier.height(Space.small))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
        }
        state.journal?.let { journal ->
            Spacer(Modifier.height(Space.large))
            SectionLabel("The run")
            Spacer(Modifier.height(Space.small))
            GameProgress(journal.fraction, label = "${journal.steps.count { it.status == StepStatus.DONE }} of ${journal.steps.size} roles done")
            journal.problems.forEach { Text("✗ $it", style = MaterialTheme.typography.labelSmall, color = colors.danger) }
            Spacer(Modifier.height(Space.small))
            journal.steps.forEach { step -> StepCard(step, open = state.openStep == step.role.id, onOpen = { actions.onOpenStep(step.role.id) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Brief(state: CrewUiState, actions: CrewActions) {
    val colors = StratumTheme.colors
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.prompt, onValueChange = actions.onPrompt, modifier = Modifier.fillMaxWidth(),
            label = { Text("The world") }, placeholder = { Text("A hive city under siege by rot cults; faith is currency") },
            enabled = !state.running, minLines = 2,
        )
        Spacer(Modifier.height(Space.small))
        OutlinedTextField(
            value = state.packName, onValueChange = actions.onName, modifier = Modifier.fillMaxWidth(),
            label = { Text("Pack name (optional)") }, enabled = !state.running, singleLine = true,
        )
        Spacer(Modifier.height(Space.medium))
        Text("The crew -- tap to bench a role", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(Space.tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            state.crew.forEach { role ->
                StratumChip(
                    label = "${role.glyph} ${role.name}" + if (role.requiresApproval) " ✋" else "",
                    selected = role.id !in state.benched,
                    onClick = { if (!state.running) actions.onToggleRole(role.id) },
                )
            }
        }
        Spacer(Modifier.height(Space.medium))
        when {
            !state.providerConfigured -> StratumAction(label = "Connect a model first", onClick = actions.onOpenSettings, emphasis = ActionEmphasis.SECONDARY, modifier = Modifier.fillMaxWidth())
            state.running -> StratumAction(label = "Stop the crew", onClick = actions.onCancel, emphasis = ActionEmphasis.SECONDARY, modifier = Modifier.fillMaxWidth())
            else -> StratumAction(label = "Start the crew", onClick = actions.onStart, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
        Text("✋ marks a role that waits for your approval.", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
    }
}

@Composable
private fun ReviewCard(state: CrewUiState, actions: CrewActions) {
    val colors = StratumTheme.colors
    val review = state.review ?: return
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("${review.step.role.glyph} ${review.step.role.name} asks for your approval")
        review.step.latest?.added?.forEach { (section, ids) ->
            Text("$section: ${ids.joinToString { it.substringAfter(':') }}", style = MaterialTheme.typography.bodySmall, color = colors.ink)
        }
        Spacer(Modifier.height(Space.small))
        Code(review.fragment, limit = REVIEW_LIMIT)
        Spacer(Modifier.height(Space.small))
        OutlinedTextField(
            value = state.reviewNote, onValueChange = actions.onNote, modifier = Modifier.fillMaxWidth(),
            label = { Text("What should change? (to revise)") }, minLines = 1,
        )
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumAction(label = "Approve", onClick = actions.onApprove, emphasis = ActionEmphasis.PRIMARY)
            StratumAction(label = "Revise", onClick = actions.onRevise, emphasis = ActionEmphasis.SECONDARY, enabled = state.reviewNote.isNotBlank())
            StratumAction(label = "Skip", onClick = actions.onSkip, emphasis = ActionEmphasis.QUIET)
        }
    }
}

@Composable
private fun ResultCard(state: CrewUiState, actions: CrewActions) {
    val colors = StratumTheme.colors
    val pack = state.result ?: return
    StratumPanel(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(pack.name)
        Text(
            listOf(
                "factions" to pack.factions.size, "regions" to pack.biomes.size, "blocks" to pack.blocks.size,
                "monsters" to pack.enemies.size, "towns" to pack.settlements.size, "foods" to pack.consumables.size,
                "structures" to pack.structures.size, "lore" to pack.loreEntries.size,
            ).filter { it.second > 0 }.joinToString(" · ") { "${it.second} ${it.first}" },
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
        )
        Spacer(Modifier.height(Space.small))
        StratumAction(
            label = if (state.installed) "Installed -- it loads with your next world" else "Install as a plugin",
            onClick = actions.onInstall,
            enabled = !state.installed,
            emphasis = ActionEmphasis.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepCard(step: StudioStep, open: Boolean, onOpen: () -> Unit) {
    val colors = StratumTheme.colors
    val tint = when (step.status) {
        StepStatus.DONE -> colors.accent
        StepStatus.FAILED -> colors.danger
        StepStatus.WORKING, StepStatus.REVIEW -> colors.ink
        else -> colors.inkMuted
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = Space.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(step.role.glyph, fontSize = 20.sp, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(step.role.name, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                Text(role(step.role), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            }
            Text(
                step.status.label + if (step.attempts.size > 1) " · ${step.attempts.size} tries" else "",
                style = MaterialTheme.typography.labelSmall, color = tint,
            )
        }
        step.reason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.padding(start = 32.dp)) }
        if (open) step.attempts.forEach { Attempt(it) }
    }
}

private fun role(role: AgentRoleDefinition): String = "writes ${role.sections.joinToString()}"

@Composable
private fun Attempt(attempt: AgentAttempt) {
    val colors = StratumTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, top = Space.small)) {
        Text("Attempt ${attempt.number} · ${attempt.durationMillis} ms", style = MaterialTheme.typography.labelSmall, color = colors.accent)
        attempt.added.forEach { (section, ids) -> Text("+ $section: ${ids.joinToString { it.substringAfter(':') }}", style = MaterialTheme.typography.labelSmall, color = colors.ink) }
        attempt.problems.forEach { Text("✗ $it", style = MaterialTheme.typography.labelSmall, color = colors.danger) }
        attempt.reviewNote?.let { Text("↺ you asked: $it", style = MaterialTheme.typography.labelSmall, color = colors.ink) }
        Text("Asked", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Code(attempt.userPrompt, limit = PROMPT_LIMIT)
        attempt.reply?.let {
            Text("Replied", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Code(it, limit = PROMPT_LIMIT)
        }
    }
}

@Composable
private fun Code(text: String, limit: Int) {
    StratumWell(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (text.length > limit) text.take(limit) + "\n… ${text.length - limit} more characters" else text,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StratumTheme.colors.ink, lineHeight = 14.sp,
        )
    }
}

private const val PROMPT_LIMIT = 1600
private const val REVIEW_LIMIT = 2400
