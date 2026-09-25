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
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports Flame games and Tiled maps, and lists what has been imported. */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PickedArchive.read(resolver, uri) } }
                .onSuccess { picked -> viewModel.importArchive(picked.name, picked.bytes) }
                .onFailure { viewModel.reportUnreadable(it.message ?: "That file could not be read") }
        }
    }
    LibraryContent(
        state = state,
        onImport = { picker.launch(ARCHIVE_TYPES) },
        onDelete = viewModel::delete,
        onDismiss = viewModel::dismissStatus,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless, so it can be previewed and screenshot-tested without a picker. */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StratumTheme.colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
        verticalArrangement = Arrangement.spacedBy(Space.large),
    ) {
        Header(onBack)
        ImportPanel(importing = state.status == ImportStatus.Working, onImport = onImport)
        StatusPanel(state.status, onDismiss)
        ImportedList(state.packs, onDelete)
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Library", style = MaterialTheme.typography.headlineSmall, color = StratumTheme.colors.ink)
            Text(
                "Bring in levels and characters from other games.",
                style = MaterialTheme.typography.bodyMedium,
                color = StratumTheme.colors.inkMuted,
            )
        }
        StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
    }
}

@Composable
private fun ImportPanel(importing: Boolean, onImport: () -> Unit) {
    StratumPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Import")
        Spacer(Modifier.height(Space.small))
        Text(
            "Pick a .zip of a Flame game or of Tiled maps. Its levels become worlds you can walk, " +
                "its animated characters become art, and its player becomes a class.",
            style = MaterialTheme.typography.bodySmall,
            color = StratumTheme.colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))
        StratumAction(
            label = if (importing) "Importing…" else "Import a game",
            onClick = onImport,
            enabled = !importing,
            emphasis = ActionEmphasis.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusPanel(status: ImportStatus, onDismiss: () -> Unit) {
    val (title, lines) = when (status) {
        ImportStatus.Idle, ImportStatus.Working -> return
        is ImportStatus.Imported -> "Imported ${status.packName} as a ${status.importerName}" to status.warnings
        is ImportStatus.Failed -> "Could not import" to listOf(status.reason)
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
private fun ImportedList(packs: List<ImportedPackSummary>, onDelete: (String) -> Unit) {
    StratumPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Imported")
        Spacer(Modifier.height(Space.small))
        if (packs.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        }
        packs.forEachIndexed { index, pack ->
            if (index > 0) StratumDivider(Modifier.padding(vertical = Space.small))
            ImportedRow(pack, onDelete)
        }
    }
}

@Composable
private fun ImportedRow(pack: ImportedPackSummary, onDelete: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${pack.name} ${pack.version}", style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
            Text(describe(pack), style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
        }
        StratumAction(label = "Remove", onClick = { onDelete(pack.id) }, emphasis = ActionEmphasis.DESTRUCTIVE)
    }
}

internal fun describe(pack: ImportedPackSummary): String = listOfNotNull(
    plural(pack.maps, "level"),
    plural(pack.characters, "character"),
    plural(pack.classes, "class", "classes"),
    "plays on its own level".takeIf { pack.replacesWorld },
).joinToString(" · ").ifEmpty { "Nothing playable" }

private fun plural(count: Int, one: String, many: String = one + "s"): String? =
    when (count) {
        0 -> null
        1 -> "1 $one"
        else -> "$count $many"
    }

// Zip archives, and any type at all: some file providers report a zip as generic binary.
private val ARCHIVE_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
