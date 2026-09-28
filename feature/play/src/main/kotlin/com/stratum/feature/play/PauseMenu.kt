package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.GameButton
import com.stratum.core.designsystem.component.GameEmphasis
import com.stratum.core.designsystem.component.RoundIconButton
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent

/**
 * Everything that is not fighting or moving, behind one button: the bag, the
 * hero, the anvil, style, realm, table, camp, the view, and the way out.
 *
 * One menu rather than a dock of seven because a handheld keeps the screen
 * for the world and puts the systems behind Start. Each entry still wears its
 * badge, and the menu button carries the loudest of them, so "points to
 * spend" is not hidden by being tidied away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PauseMenu(
    state: PlayUiState,
    entries: List<DockEntry>,
    onZoom: (Float) -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = 0.72f))
            // A tap on the dimmed world resumes, the way a sheet is dismissed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume),
    ) {
        val landscape = maxWidth > maxHeight
        StratumPanel(
            modifier = Modifier
                .align(if (landscape) Alignment.Center else Alignment.BottomCenter)
                .safeContent()
                .padding(Space.medium)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Taps on the panel stay on the panel.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = Cut.large,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MENU", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                        Text(
                            listOfNotNull(
                                "Level ${state.player.level}",
                                state.settlementName ?: state.biomeName.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                    RoundIconButton(glyph = "✕", description = "Resume", onClick = onResume)
                }
                Spacer(Modifier.height(Space.large))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.medium, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(Space.medium),
                    maxItemsInEachRow = if (landscape) 6 else 4,
                ) {
                    entries.forEach { entry ->
                        Box(Modifier.width(MENU_CELL), contentAlignment = Alignment.Center) {
                            GameButton(
                                glyph = entry.glyph,
                                label = entry.label,
                                onClick = entry.onClick,
                                size = MENU_BUTTON,
                                badge = entry.badge,
                                emphasis = when {
                                    entry.active -> GameEmphasis.ACTIVE
                                    entry.calling -> GameEmphasis.PRIMARY
                                    else -> GameEmphasis.NORMAL
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.large))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ZOOM", style = MaterialTheme.typography.labelLarge, color = colors.inkMuted, modifier = Modifier.weight(1f))
                    RoundIconButton(glyph = "−", description = "Zoom out", onClick = { onZoom(-ZOOM_STEP) })
                    Spacer(Modifier.width(Space.small))
                    RoundIconButton(glyph = "+", description = "Zoom in", onClick = { onZoom(ZOOM_STEP) })
                }
                Spacer(Modifier.height(Space.large))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    StratumAction(label = "Save & quit", onClick = onQuit, emphasis = ActionEmphasis.SECONDARY, modifier = Modifier.weight(1f))
                    StratumAction(label = "Resume", onClick = onResume, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The single loudest thing waiting inside the menu, for the menu button's own
 * badge: points to spend first, since they change how the next fight goes.
 */
internal fun menuBadge(entries: List<DockEntry>): String? =
    entries.firstOrNull { it.calling }?.badge ?: entries.firstOrNull { it.calling }?.let { "!" }

private val MENU_BUTTON = 56.dp
private val MENU_CELL = 72.dp
