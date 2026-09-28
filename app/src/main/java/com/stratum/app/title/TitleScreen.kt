package com.stratum.app.title

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stratum.app.world.WorldFormat
import com.stratum.core.designsystem.component.DoorCard
import com.stratum.core.designsystem.component.DoorEmphasis
import com.stratum.core.designsystem.component.RoundIconButton
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.session.WorldSummary

/** What the title screen can be asked to do. */
data class TitleActions(
    val onContinue: (WorldSummary) -> Unit = {},
    val onPlay: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/**
 * The first thing the game shows: its name, its look, and three doors --
 * Play, Create, Import & Share -- with settings as a small gear, not a door.
 *
 * When there is a world to go back to, "Continue" is the one filled button,
 * so returning to the game is a single tap from launch. Without one, Play is.
 */
@Composable
fun TitleScreen(
    lastWorld: WorldSummary?,
    actions: TitleActions,
    modifier: Modifier = Modifier,
    /** What is loaded, in one quiet line at the foot of the screen. */
    packLine: String = "",
    jobsTray: @Composable () -> Unit = {},
) {
    val colors = StratumTheme.colors
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.surfaceSunken, colors.surface, colors.surface)))
            .safeContent(),
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = Space.wide, vertical = Space.large)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TitleArt(Modifier.fillMaxWidth().weight(1f, fill = false).height(ART_HEIGHT_LANDSCAPE))
                    Spacer(Modifier.height(Space.small))
                    Wordmark()
                    PackLine(packLine)
                }
                Spacer(Modifier.padding(Space.large))
                Column(
                    Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.medium, Alignment.CenterVertically),
                ) {
                    Doors(lastWorld, actions)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.large, vertical = Space.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(TOP_CLEARANCE))
                TitleArt(Modifier.fillMaxWidth().height(ART_HEIGHT_PORTRAIT))
                Spacer(Modifier.height(Space.medium))
                Wordmark()
                Spacer(Modifier.height(Space.huge))
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.medium)) {
                    Doors(lastWorld, actions)
                }
                Spacer(Modifier.height(Space.large))
                PackLine(packLine)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(Space.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            jobsTray()
            RoundIconButton(glyph = "⚙", description = "Settings", onClick = actions.onSettings)
        }
    }
}

@Composable
private fun ColumnScope.Doors(lastWorld: WorldSummary?, actions: TitleActions) {
    if (lastWorld != null) {
        DoorCard(
            glyph = "▶",
            title = "Continue",
            promise = WorldFormat.continueLine(lastWorld),
            onClick = { actions.onContinue(lastWorld) },
            emphasis = DoorEmphasis.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    DoorCard(
        glyph = "⚔",
        title = "Play",
        promise = if (lastWorld != null) "Your worlds, or a new one" else "Pick a hero and a world, and go",
        onClick = actions.onPlay,
        emphasis = if (lastWorld == null) DoorEmphasis.PRIMARY else DoorEmphasis.NORMAL,
        modifier = Modifier.fillMaxWidth(),
    )
    DoorCard(
        glyph = "✎",
        title = "Create",
        promise = "Heroes, worlds, lore, gear and art",
        onClick = actions.onCreate,
        modifier = Modifier.fillMaxWidth(),
        tint = StratumTheme.colors.accentAlt,
    )
    DoorCard(
        glyph = "⇄",
        title = "Import & Share",
        promise = "Plugins, Tiled and Flame games, your creations",
        onClick = actions.onShare,
        modifier = Modifier.fillMaxWidth(),
        tint = StratumTheme.colors.ink,
    )
}

@Composable
private fun Wordmark() {
    val colors = StratumTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("STRATUM", style = MaterialTheme.typography.displayLarge, color = colors.ink, textAlign = TextAlign.Center)
        Text(
            "DIG · BUILD · FIGHT · MAKE IT YOURS",
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PackLine(text: String) {
    if (text.isBlank()) return
    Box(Modifier.padding(top = Space.small)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted, textAlign = TextAlign.Center)
    }
}

private val TOP_CLEARANCE = 40.dp
private val ART_HEIGHT_PORTRAIT = 200.dp
private val ART_HEIGHT_LANDSCAPE = 180.dp
