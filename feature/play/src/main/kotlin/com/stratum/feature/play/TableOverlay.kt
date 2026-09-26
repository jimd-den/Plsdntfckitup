package com.stratum.feature.play

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.tabletop.ActiveBoon
import com.stratum.core.domain.tabletop.SkillCheck

/**
 * The tabletop: the checks the loaded plugins offer, and what is running
 * from earlier rolls. Each check says its dice and difficulty up front,
 * because at a table you know the odds before you roll.
 */
@Composable
fun TableOverlay(state: PlayUiState, onRoll: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        StratumPanel(modifier = Modifier.fillMaxWidth().safeContent().padding(Space.medium).widthIn(max = PANEL_MAX_WIDTH).verticalScroll(rememberScrollState())) {
            SectionLabel(text = "The table")
            Spacer(Modifier.height(Space.small))
            state.checks.forEach { check -> CheckRow(check, state.checkCooldowns[check.id] ?: 0f, onRoll) }
            if (state.activeBoons.isNotEmpty()) {
                Spacer(Modifier.height(Space.medium))
                SectionLabel(text = "In effect")
                state.activeBoons.forEach { BoonLine(it) }
            }
            Spacer(Modifier.height(Space.medium))
            StratumAction(label = "Close", onClick = onClose, emphasis = ActionEmphasis.QUIET)
        }
    }
}

@Composable
private fun CheckRow(check: SkillCheck, cooldown: Float, onRoll: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.small), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(check.name, style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
            Text(
                "${check.dice} + ${check.attribute.name.lowercase()} vs ${check.difficulty}" + (check.boon?.let { " · ${it.name}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = StratumTheme.colors.inkMuted,
            )
        }
        StratumAction(
            label = if (cooldown > 0f) "${cooldown.toInt() + 1}s" else "Roll",
            onClick = { onRoll(check.id) },
            enabled = cooldown <= 0f,
            emphasis = ActionEmphasis.PRIMARY,
        )
    }
}

@Composable
private fun BoonLine(active: ActiveBoon) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        Text(active.boon.name, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.ink)
        Text("${active.remainingSeconds.toInt()}s", style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
    }
}
