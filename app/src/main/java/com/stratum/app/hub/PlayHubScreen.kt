package com.stratum.app.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.app.world.WorldFormat
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.DoorCard
import com.stratum.core.designsystem.component.DoorEmphasis
import com.stratum.core.designsystem.component.EmptyState
import com.stratum.core.designsystem.component.LocalJobsTray
import com.stratum.core.designsystem.component.SectionHeader
import com.stratum.core.designsystem.component.StatusChip
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.session.WorldSummary

/** What the play hub can be asked to do. */
data class PlayHubActions(
    val onBack: () -> Unit = {},
    val onContinue: (WorldSummary) -> Unit = {},
    val onNewWorld: () -> Unit = {},
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
)

/**
 * Behind the Play door: the last world to continue, a new one in three steps,
 * and every saved world as a card that says who is in it, how far along, and
 * when it was last played.
 */
@Composable
fun PlayHubScreen(
    worlds: List<WorldSummary>,
    actions: PlayHubActions,
    modifier: Modifier = Modifier,
    /** The clock, handed in so cards read the same in a screenshot every time. */
    now: Long = System.currentTimeMillis(),
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val latest = worlds.firstOrNull()

    StratumScreen(title = "Play", onBack = actions.onBack, modifier = modifier, jobsTray = jobsTray) {
        if (latest != null) {
            DoorCard(
                glyph = "▶",
                title = "Continue",
                promise = WorldFormat.continueLine(latest),
                onClick = { actions.onContinue(latest) },
                emphasis = DoorEmphasis.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DoorCard(
            glyph = "+",
            title = "New world",
            promise = "Hero, world, go -- three quick steps",
            onClick = actions.onNewWorld,
            emphasis = if (latest == null) DoorEmphasis.PRIMARY else DoorEmphasis.NORMAL,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Space.medium)) {
            SectionHeader(if (worlds.isEmpty()) "Saved worlds" else "Saved worlds · ${worlds.size}")
            if (worlds.isEmpty()) {
                EmptyState(
                    glyph = "🗺",
                    title = "No saved worlds yet",
                    body = "Every world you play is kept here, with your hero and how far you got.",
                )
            } else {
                worlds.forEach { world ->
                    WorldCard(
                        world = world,
                        now = now,
                        onContinue = { actions.onContinue(world) },
                        onRename = { renaming = world.id },
                        onDelete = { deleting = world.id },
                    )
                }
            }
        }
    }

    worlds.firstOrNull { it.id == renaming }?.let { world ->
        RenameDialog(
            current = world.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                actions.onRename(world.id, name)
                renaming = null
            },
        )
    }
    worlds.firstOrNull { it.id == deleting }?.let { world ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${world.name}?") },
            text = { Text("The world is gone for good. ${world.heroName} keeps their level and gear for the next world.") },
            confirmButton = {
                StratumAction(label = "Delete", emphasis = ActionEmphasis.DESTRUCTIVE, onClick = {
                    actions.onDelete(world.id)
                    deleting = null
                })
            },
            dismissButton = { StratumAction(label = "Keep", emphasis = ActionEmphasis.QUIET, onClick = { deleting = null }) },
            containerColor = StratumTheme.colors.surfaceRaised,
            titleContentColor = StratumTheme.colors.ink,
            textContentColor = StratumTheme.colors.inkMuted,
        )
    }
}

/** One saved world: its name, its hero, and the three things to do with it. */
@Composable
internal fun WorldCard(
    world: WorldSummary,
    now: Long,
    onContinue: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    StratumPanel(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)).border(Stroke.edge, colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(world.name.firstOrNull()?.uppercase() ?: "?", color = colors.accent, fontSize = 22.sp)
            }
            Spacer(Modifier.width(Space.medium))
            Column(Modifier.weight(1f)) {
                Text(world.name, style = MaterialTheme.typography.titleLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(WorldFormat.heroLine(world), style = MaterialTheme.typography.bodyMedium, color = colors.accent, maxLines = 1)
            }
        }
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(world.presetName)
            StatusChip("${WorldFormat.playTime(world.playSeconds)} played")
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            "Last played ${WorldFormat.lastPlayed(world.lastPlayedAt, now)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumAction(label = "Continue", onClick = onContinue, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1f))
            StratumAction(label = "Rename", onClick = onRename, emphasis = ActionEmphasis.QUIET)
            StratumAction(label = "Delete", onClick = onDelete, emphasis = ActionEmphasis.DESTRUCTIVE)
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename world") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            StratumAction(label = "Save", emphasis = ActionEmphasis.PRIMARY, enabled = name.isNotBlank(), onClick = { onConfirm(name) })
        },
        dismissButton = { StratumAction(label = "Cancel", emphasis = ActionEmphasis.QUIET, onClick = onDismiss) },
        containerColor = StratumTheme.colors.surfaceRaised,
        titleContentColor = StratumTheme.colors.ink,
    )
}
