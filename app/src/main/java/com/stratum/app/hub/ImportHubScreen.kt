package com.stratum.app.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stratum.core.designsystem.component.HubCard
import com.stratum.core.designsystem.component.LocalJobsTray
import com.stratum.core.designsystem.component.SectionHeader
import com.stratum.core.designsystem.component.StatusChip
import com.stratum.core.designsystem.component.StatusTone
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.feature.library.ImportStatus

/** What the Import & Share hub shows about what is installed and what could be shared. */
data class ImportHubState(
    val installed: Int = 0,
    val active: Int = 0,
    /** Classes the player built plus things kept from the content forge. */
    val shareable: Int = 0,
    val status: ImportStatus = ImportStatus.Idle,
)

data class ImportHubActions(
    val onBack: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onImportProject: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onManage: () -> Unit = {},
    val onDismissStatus: () -> Unit = {},
)

/**
 * Behind the Import & Share door: bring worlds in, send yours out, and see
 * what is installed. The last install's outcome sits at the top until it is
 * read, so a failed import is never silent.
 */
@Composable
fun ImportHubScreen(
    state: ImportHubState,
    actions: ImportHubActions,
    modifier: Modifier = Modifier,
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
) {
    val working = state.status == ImportStatus.Working
    StratumScreen(title = "Import & Share", onBack = actions.onBack, modifier = modifier, jobsTray = jobsTray) {
        StatusBanner(state.status, actions.onDismissStatus)
        HubCard(
            glyph = "⬇", title = "Install a plugin", promise = "A .stratum file: worlds, classes, rules",
            onClick = actions.onInstall,
            status = if (working) "Installing…" else null, statusTone = StatusTone.BUSY,
            modifier = Modifier.fillMaxWidth(),
        )
        HubCard(
            glyph = "🗺", title = "Import a game", promise = "A Tiled map or Flame game, made playable",
            onClick = actions.onImportProject,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accentAlt,
        )
        HubCard(
            glyph = "⇪", title = "Share my creations", promise = "Your classes, gear and lore as one plugin",
            onClick = actions.onShare,
            status = if (state.shareable > 0) "${state.shareable} to share" else "Make something first",
            statusTone = if (state.shareable > 0) StatusTone.READY else StatusTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionHeader("Installed")
        HubCard(
            glyph = "🧩", title = "Manage plugins", promise = "On, off, and load order",
            onClick = actions.onManage,
            status = when {
                state.installed == 0 -> "Only the built-in pack"
                state.active < state.installed -> "${state.active} of ${state.installed} loaded"
                else -> "${state.installed} loaded"
            },
            statusTone = if (state.active < state.installed) StatusTone.NEEDS else StatusTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.inkMuted,
        )
    }
}

@Composable
private fun StatusBanner(status: ImportStatus, onDismiss: () -> Unit) {
    val (title, detail, tone) = when (status) {
        ImportStatus.Idle, ImportStatus.Working -> return
        is ImportStatus.Imported -> Triple("Installed ${status.packName}", status.warnings.firstOrNull() ?: "As a ${status.importerName}. It loads with the next world.", StatusTone.READY)
        is ImportStatus.Failed -> Triple("Could not install that", status.reason, StatusTone.NEEDS)
    }
    StratumPanel(Modifier.fillMaxWidth().clickable(onClick = onDismiss)) {
        StatusChip(if (tone == StatusTone.READY) "Done" else "Problem", tone = tone)
        Spacer(Modifier.height(Space.small))
        Text(title, style = MaterialTheme.typography.titleMedium, color = StratumTheme.colors.ink)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        Spacer(Modifier.height(Space.tight))
        Text("Tap to dismiss", style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
    }
}
