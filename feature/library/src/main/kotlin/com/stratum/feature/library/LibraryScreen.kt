package com.stratum.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installs plugins -- Stratum plugins, Flame games, Tiled maps -- and manages what is installed. */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shares the player's own creations as a plugin; null hides the action. Supplied by the app. */
    onShareCreations: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PickedArchive.read(resolver, uri) } }
                .onSuccess { picked -> viewModel.install(picked.name, picked.bytes) }
                .onFailure { viewModel.reportUnreadable(it.message ?: "That file could not be read") }
        }
    }
    LibraryContent(
        state = state,
        actions = LibraryActions(
            onImport = { picker.launch(ARCHIVE_TYPES) },
            onToggle = viewModel::setEnabled,
            onMoveEarlier = viewModel::moveEarlier,
            onMoveLater = viewModel::moveLater,
            onRemove = viewModel::uninstall,
            onDismiss = viewModel::dismissStatus,
            onBack = onBack,
            onShareCreations = onShareCreations,
        ),
        modifier = modifier,
    )
}

/** Everything the library can be asked to do, so the content stays stateless and previewable. */
data class LibraryActions(
    val onImport: () -> Unit = {},
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onMoveEarlier: (String) -> Unit = {},
    val onMoveLater: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onShareCreations: (() -> Unit)? = null,
)

/** Stateless, so it can be previewed and screenshot-tested without a picker. */
@Composable
fun LibraryContent(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StratumTheme.colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
        verticalArrangement = Arrangement.spacedBy(Space.large),
    ) {
        Header(actions.onBack)
        InstallPanel(importing = state.status == ImportStatus.Working, actions = actions)
        StatusPanel(state.status, actions.onDismiss)
        PluginList(state.plugins, actions)
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Plugins", style = MaterialTheme.typography.headlineSmall, color = StratumTheme.colors.ink)
            Text(
                "Worlds, rules and characters made by anyone, loaded in the order you choose.",
                style = MaterialTheme.typography.bodyMedium,
                color = StratumTheme.colors.inkMuted,
            )
        }
        StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
    }
}

@Composable
private fun InstallPanel(importing: Boolean, actions: LibraryActions) {
    StratumPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Install")
        Spacer(Modifier.height(Space.small))
        Text(
            "A .stratum plugin, or a .zip of a Flame game or Tiled maps. Later plugins override earlier ones; " +
                "a plugin that needs another loads after it.",
            style = MaterialTheme.typography.bodySmall,
            color = StratumTheme.colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))
        StratumAction(
            label = if (importing) "Installing…" else "Install a plugin or game",
            onClick = actions.onImport,
            enabled = !importing,
            emphasis = ActionEmphasis.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
        actions.onShareCreations?.let { share ->
            Spacer(Modifier.height(Space.small))
            StratumAction(label = "Share your creations as a plugin", onClick = share, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusPanel(status: ImportStatus, onDismiss: () -> Unit) {
    val (title, lines) = when (status) {
        ImportStatus.Idle, ImportStatus.Working -> return
        is ImportStatus.Imported -> "Installed ${status.packName} as a ${status.importerName}" to status.warnings
        is ImportStatus.Failed -> "Could not install" to listOf(status.reason)
    }
    StratumSection(
        title = title,
        modifier = Modifier.fillMaxWidth(),
        trailing = { StratumAction(label = "Dismiss", onClick = onDismiss, emphasis = ActionEmphasis.QUIET) },
    ) {
        lines.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        }
    }
}

@Composable
private fun PluginList(plugins: List<PluginSummary>, actions: LibraryActions) {
    StratumPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Installed, in load order")
        Spacer(Modifier.height(Space.small))
        if (plugins.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        }
        plugins.forEachIndexed { index, plugin ->
            if (index > 0) StratumDivider(Modifier.padding(vertical = Space.small))
            PluginRow(plugin, actions)
        }
    }
}

@Composable
private fun PluginRow(plugin: PluginSummary, actions: LibraryActions) {
    Column(Modifier.fillMaxWidth()) {
        Text("${plugin.name} ${plugin.version}", style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
        Text(byline(plugin), style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        Text(describe(plugin), style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        plugin.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.danger) }
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumChip(label = if (plugin.enabled) "On" else "Off", selected = plugin.enabled, onClick = { actions.onToggle(plugin.id, !plugin.enabled) })
            StratumAction(label = "↑", onClick = { actions.onMoveEarlier(plugin.id) }, enabled = !plugin.isFirst, emphasis = ActionEmphasis.QUIET)
            StratumAction(label = "↓", onClick = { actions.onMoveLater(plugin.id) }, enabled = !plugin.isLast, emphasis = ActionEmphasis.QUIET)
            StratumAction(label = "Remove", onClick = { actions.onRemove(plugin.id) }, emphasis = ActionEmphasis.DESTRUCTIVE)
        }
    }
}

internal fun byline(plugin: PluginSummary): String = listOfNotNull(
    plugin.author.takeIf(String::isNotBlank)?.let { "by $it" },
    plugin.license.takeIf(String::isNotBlank),
    when {
        plugin.active -> "loaded"
        plugin.enabled -> "not loaded"
        else -> "off"
    },
).joinToString(" · ")

internal fun describe(plugin: PluginSummary): String = listOfNotNull(
    plural(plugin.maps, "level"),
    plural(plugin.characters, "character"),
    plural(plugin.classes, "class", "classes"),
    plural(plugin.checks, "tabletop check"),
    "plays on its own level".takeIf { plugin.replacesWorld },
).joinToString(" · ").ifEmpty { "Content only" }

private fun plural(count: Int, one: String, many: String = one + "s"): String? =
    when (count) {
        0 -> null
        1 -> "1 $one"
        else -> "$count $many"
    }

// A .stratum plugin, a zip, and any type at all: some file providers report a zip as generic binary.
private val ARCHIVE_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
