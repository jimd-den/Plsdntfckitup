package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.quest.ActiveQuest
import com.stratum.core.domain.quest.Quest
import com.stratum.core.domain.quest.QuestStatus
import kotlin.math.atan2
import kotlin.math.hypot

/** What the quest panel shows: the board of the town the player stands in, and their own quests. */
data class QuestPanel(
    /** The friendly town the player stands in, or null out in the wild. */
    val townName: String? = null,
    /** Its people, by name and trade, for the board's heading. */
    val people: Int = 0,
    val board: List<Quest> = emptyList(),
    val active: List<ActiveQuest> = emptyList(),
    /** The player's position, for the way to each quest's place. */
    val playerX: Float = 0f,
    val playerY: Float = 0f,
    /** The ids of quests that can be handed in where the player stands. */
    val handInHere: Set<String> = emptySet(),
) {
    val ready: Int get() = active.count { it.status == QuestStatus.READY }
}

data class QuestActions(
    val onToggle: () -> Unit = {},
    val onAccept: (String) -> Unit = {},
    val onAbandon: (String) -> Unit = {},
    val onTurnIn: (String) -> Unit = {},
)

/**
 * The quest panel: in a friendly town, its board -- each quest from one of
 * the people walking its streets, in their words -- and always the player's
 * own quests with how far along they are and which way to go.
 */
@Composable
fun QuestOverlay(panel: QuestPanel, actions: QuestActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Box(modifier.fillMaxSize().background(colors.surface.copy(alpha = 0.9f)).clickable(onClick = actions.onToggle), contentAlignment = Alignment.Center) {
        StratumPanel(
            modifier = Modifier.safeContent().fillMaxWidth(0.94f).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).clickable(enabled = false) {},
            shape = Cut.large,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(panel.townName?.let { "Quests · $it" } ?: "Quests")
                Spacer(Modifier.weight(1f))
                StratumAction(label = "Close", onClick = actions.onToggle, emphasis = ActionEmphasis.QUIET)
            }
            if (panel.active.isNotEmpty()) {
                Spacer(Modifier.height(Space.small))
                Text("Yours", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                panel.active.forEach { a -> ActiveRow(a, panel, actions) }
            }
            Spacer(Modifier.height(Space.medium))
            if (panel.townName == null) {
                Text("Find a friendly town: its people have work for you.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            } else {
                Text("The board · ${panel.people} people live here", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                if (panel.board.isEmpty()) Text("Nobody needs anything today. Come back tomorrow.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                panel.board.forEach { q -> BoardRow(q, actions) }
            }
        }
    }
}

@Composable
private fun BoardRow(q: Quest, actions: QuestActions) {
    val colors = StratumTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.small).clip(RoundedCornerShape(10.dp)).background(colors.surfaceRaised).padding(Space.small)) {
        Text(q.title, style = MaterialTheme.typography.labelLarge, color = colors.ink)
        Text("${q.giver.name}, ${q.giver.trade.label}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
        Text("“${q.pitch}”", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, modifier = Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${q.reward.experience} xp · ${q.reward.kind.label}", style = MaterialTheme.typography.labelSmall, color = colors.ink, modifier = Modifier.weight(1f))
            StratumAction(label = "Accept", onClick = { actions.onAccept(q.id) }, emphasis = ActionEmphasis.PRIMARY)
        }
    }
}

@Composable
private fun ActiveRow(a: ActiveQuest, panel: QuestPanel, actions: QuestActions) {
    val colors = StratumTheme.colors
    val q = a.quest
    Column(Modifier.fillMaxWidth().padding(top = Space.small).clip(RoundedCornerShape(10.dp)).background(colors.surfaceRaised).padding(Space.small)) {
        Text(q.title, style = MaterialTheme.typography.labelLarge, color = colors.ink)
        Text(q.goal, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surface)) {
            Box(Modifier.fillMaxWidth(a.fraction).fillMaxHeight().background(if (a.status == QuestStatus.FAILED) colors.inkMuted else colors.accent))
        }
        val status = when (a.status) {
            QuestStatus.ACTIVE -> "${a.progress} of ${a.required}" + wayTo(a, panel).orEmpty() + (if (q.timeLimit > 0f) " · ${((q.timeLimit - a.elapsed).coerceAtLeast(0f)).toInt()} s left" else "")
            QuestStatus.READY -> if (q.id in panel.handInHere) "Done: hand it in" else "Done: return to ${if (q.objective.toTownName.isNotEmpty()) q.objective.toTownName else q.giver.name}"
            QuestStatus.FAILED -> "Failed: ${a.failure ?: ""}"
            QuestStatus.DONE -> "Done"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            Text(status, style = MaterialTheme.typography.labelSmall, color = colors.ink, modifier = Modifier.weight(1f))
            if (q.id in panel.handInHere) StratumAction(label = "Hand in", onClick = { actions.onTurnIn(q.id) }, emphasis = ActionEmphasis.PRIMARY)
            StratumAction(label = if (a.status == QuestStatus.FAILED) "Dismiss" else "Abandon", onClick = { actions.onAbandon(q.id) }, emphasis = ActionEmphasis.QUIET)
        }
    }
}

/** " · 120 blocks north-east", for a quest with a place. */
private fun wayTo(a: ActiveQuest, panel: QuestPanel): String? {
    val px = a.placeX ?: return null; val py = a.placeY ?: return null
    val dx = px + 0.5f - panel.playerX; val dy = py + 0.5f - panel.playerY
    val distance = hypot(dx, dy).toInt()
    if (distance < 4) return " · here"
    // World y grows southward on the map.
    val compass = listOf("east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east")
    val sector = ((Math.round(atan2(dy, dx) / (Math.PI / 4)).toInt() % 8) + 8) % 8
    return " · $distance blocks ${compass[sector]}"
}
