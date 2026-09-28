package com.stratum.feature.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import kotlinx.coroutines.delay

/** One thing the game teaches, the first time it matters. */
internal data class PlayHint(val id: String, val text: String)

/**
 * The hint for this moment, or null when there is nothing new to teach.
 *
 * Hints are taught in play, not in paragraphs on a menu: each appears when
 * the thing it explains first becomes relevant, is shown once, and is never
 * shown again. Ordered so the basics come before the systems built on them.
 */
internal fun nextHint(state: PlayUiState, seen: Set<String>): PlayHint? {
    val candidates = buildList {
        if (state.buildMode) add(PlayHint(Hints.BUILD, "Drag on the ground to build. Pick a shape and a block below."))
        if (state.settlementHostile) add(PlayHint(Hints.STRONGHOLD, "A stronghold. Defeat the garrison to liberate it."))
        add(PlayHint(Hints.MOVE, "Drag the stick to move. Tap the ground to dig."))
        add(PlayHint(Hints.STRIKE, "Strike with the big button. Your skills and roll sit around it."))
        add(PlayHint(Hints.PLACE, "Hold on the ground to place a block."))
        if (state.player.unspentPassivePoints > 0) add(PlayHint(Hints.POINTS, "Points to spend. Open the menu, then Hero."))
        add(PlayHint(Hints.MENU, "Bag, Hero, Anvil and the rest live in the menu, top right."))
        add(PlayHint(Hints.PINCH, "Pinch to zoom."))
    }
    return candidates.firstOrNull { it.id !in seen }
}

/** Hint ids, stable across versions so a hint once seen stays seen. */
object Hints {
    const val MOVE = "move"
    const val STRIKE = "strike"
    const val PLACE = "place"
    const val BUILD = "build"
    const val POINTS = "points"
    const val MENU = "menu"
    const val PINCH = "pinch"
    const val STRONGHOLD = "stronghold"

    /** Every hint, for a test or a caller that wants to start with none shown. */
    val all: Set<String> = setOf(MOVE, STRIKE, PLACE, BUILD, POINTS, MENU, PINCH, STRONGHOLD)
}

/**
 * What the game is saying right now: a one-time hint, or the last thing that
 * happened. Both fade on their own -- nothing here is a permanent caption
 * sitting over the world.
 */
@Composable
internal fun HintAndToast(
    state: PlayUiState,
    seenHints: Set<String>,
    onHintSeen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = nextHint(state, seenHints)
    // A hint stays long enough to read twice, then is marked seen, which is what fades it.
    LaunchedEffect(hint?.id) {
        val shown = hint ?: return@LaunchedEffect
        delay(HINT_MILLIS)
        onHintSeen(shown.id)
    }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.message) {
        toast = state.message
        delay(TOAST_MILLIS)
        toast = null
    }
    Column(modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        AnimatedVisibility(visible = toast != null, enter = fadeIn(), exit = fadeOut()) {
            Bubble(toast.orEmpty(), StratumTheme.colors.ink, StratumTheme.colors.hairline)
        }
        AnimatedVisibility(visible = hint != null, enter = fadeIn(), exit = fadeOut()) {
            Bubble(hint?.text.orEmpty(), StratumTheme.colors.ink, StratumTheme.colors.accent)
        }
    }
}

@Composable
private fun Bubble(text: String, ink: Color, edge: Color) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = ink,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier
            .clip(shape)
            .background(StratumTheme.colors.surface.copy(alpha = 0.84f))
            .border(1.dp, edge, shape)
            .padding(horizontal = Space.medium, vertical = Space.small),
    )
}

private const val HINT_MILLIS = 5_000L
private const val TOAST_MILLIS = 3_000L
