package com.stratum.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.creation.CreationJob
import com.stratum.core.domain.creation.JobStatus
import com.stratum.core.domain.creation.formatElapsed

/** Everything the tray can ask the job centre to do. */
data class JobsTrayActions(
    val onCancel: (String) -> Unit = {},
    val onDismiss: (String) -> Unit = {},
    val onClearFinished: () -> Unit = {},
)

/**
 * The jobs tray: a small pill saying how many creations are running, which
 * opens into a sheet of every job; tapping a job shows its steps.
 *
 * It lives in the shell, not on any one screen, because a job outlives the
 * screen that started it: ask for lore, go and play, and the tray is where the
 * lore turns up. With nothing running or recently finished it draws nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsTray(
    jobs: List<CreationJob>,
    actions: JobsTrayActions,
    modifier: Modifier = Modifier,
    /** Opens with the sheet showing, for previews and screenshots. */
    startExpanded: Boolean = false,
) {
    if (jobs.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    JobsPill(jobs, onClick = { expanded = true }, modifier = modifier)
    if (expanded) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val colors = StratumTheme.colors
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = sheetState,
            containerColor = colors.surface,
            contentColor = colors.ink,
            shape = Cut.large,
            dragHandle = null,
        ) {
            JobsSheet(jobs, actions, onClose = { expanded = false })
        }
    }
}

/** The collapsed tray: how many are running, or how the last ones ended. */
@Composable
fun JobsPill(jobs: List<CreationJob>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val running = jobs.count { it.isActive }
    val failed = jobs.count { it.status == JobStatus.FAILED }
    val (label, tint) = when {
        running > 0 -> "$running running" to colors.accent
        failed > 0 -> "$failed failed" to colors.danger
        else -> "${jobs.count { it.status == JobStatus.DONE }} ready" to colors.accentAlt
    }
    Row(
        modifier
            .clip(Cut.small)
            .background(colors.surfaceRaised)
            .border(Stroke.hairline, tint.copy(alpha = 0.6f), Cut.small)
            .clickable(role = Role.Button, onClickLabel = "Show creation jobs", onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running > 0) JobSpinner(size = 16.dp) else JobStatusMark(if (failed > 0) JobStatus.FAILED else JobStatus.DONE, size = 16.dp)
        Spacer(Modifier.width(Space.small))
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = colors.ink)
    }
}

/**
 * The open tray: every job, newest first, with what each is doing now. Tapping
 * one shows its whole timeline in place; back returns to the list.
 */
@Composable
fun JobsSheet(
    jobs: List<CreationJob>,
    actions: JobsTrayActions,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    now: Long = rememberNow(jobs.any { it.isActive }),
    initiallyOpen: String? = null,
) {
    val colors = StratumTheme.colors
    var open by remember { mutableStateOf(initiallyOpen) }
    val shown = jobs.firstOrNull { it.id == open }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.large)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (shown != null) {
                StratumAction(label = "All jobs", onClick = { open = null }, emphasis = ActionEmphasis.QUIET)
                Spacer(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Creating")
                    val running = jobs.count { it.isActive }
                    Text(
                        if (running == 0) "Nothing running. Finished jobs stay here for a while." else "$running running · they keep going when you leave",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
            }
            onClose?.let { StratumAction(label = "Close", onClick = it, emphasis = ActionEmphasis.QUIET) }
        }
        Spacer(Modifier.height(Space.medium))
        if (shown != null) {
            JobProgress(
                shown, now = now,
                onCancel = { actions.onCancel(shown.id) },
                onDismiss = { actions.onDismiss(shown.id); open = null },
            )
        } else {
            jobs.forEach { job ->
                JobRow(job, now, onClick = { open = job.id })
                Spacer(Modifier.height(Space.small))
            }
            if (jobs.any { !it.isActive }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    StratumAction(label = "Clear finished", onClick = actions.onClearFinished, emphasis = ActionEmphasis.QUIET)
                }
            }
        }
    }
}

/** One job in the list: its mark, its title, the step it is on and how long it has run. */
@Composable
fun JobRow(job: CreationJob, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val step = job.currentStep
    val line = when {
        !job.isActive -> job.summary
        step != null -> listOfNotNull(step.label, step.detail).joinToString(" · ")
        else -> "Waiting to start"
    }
    StratumPanel(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Show steps", onClick = onClick),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStatusMark(job.status, size = 20.dp)
            Spacer(Modifier.width(Space.medium))
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.status == JobStatus.FAILED) colors.danger else colors.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Space.small))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatElapsed(job.elapsedMillis(now)), style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
                val done = job.steps.count { it.status == JobStatus.DONE }
                if (job.steps.isNotEmpty()) {
                    Text("$done/${job.steps.size}", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
            }
        }
    }
}
