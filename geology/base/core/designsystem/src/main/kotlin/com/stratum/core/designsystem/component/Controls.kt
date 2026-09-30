package com.stratum.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme

/** How much weight a control carries on screen. */
enum class ActionEmphasis { PRIMARY, SECONDARY, QUIET, DESTRUCTIVE }

/**
 * The app's button.
 *
 * Filled for the one action a screen is actually for, outlined for everything
 * else. Deliberately only four emphases, so a screen cannot invent a fifth kind
 * of importance.
 */
@Composable
fun StratumAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: ActionEmphasis = ActionEmphasis.SECONDARY,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = StratumTheme.colors
    val accent = when (emphasis) {
        ActionEmphasis.DESTRUCTIVE -> colors.danger
        ActionEmphasis.QUIET -> colors.inkMuted
        else -> colors.accent
    }
    val filled = emphasis == ActionEmphasis.PRIMARY
    val contentColor = when {
        !enabled -> colors.inkMuted.copy(alpha = 0.5f)
        filled -> colors.surface
        else -> accent
    }

    Row(
        modifier = modifier
            .clip(Cut.small)
            .background(if (filled && enabled) accent else Color.Transparent)
            .border(
                BorderStroke(
                    Stroke.hairline,
                    if (enabled) accent.copy(alpha = if (filled) 1f else 0.55f) else colors.hairline,
                ),
                Cut.small,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = Space.large, vertical = Space.medium),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Space.small))
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A stratum meter: the game's replacement for the twin orbs.
 *
 * Health and resource are read at a glance during combat, so the bar is wide,
 * flat and labelled with the actual numbers rather than being a globe whose fill
 * level has to be estimated.
 */
@Composable
fun StratumMeter(
    label: String,
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    tint: Color = StratumTheme.colors.accent,
) {
    val colors = StratumTheme.colors
    val safeMax = max.coerceAtLeast(1)
    val target = (value.toFloat() / safeMax).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(targetValue = target, label = "meter-$label")

    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(METER_HEIGHT)
                .clip(Cut.bar)
                .background(colors.surfaceSunken)
                .border(Stroke.hairline, colors.hairline, Cut.bar),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(METER_HEIGHT)
                .clip(Cut.bar)
                .background(tint),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(METER_HEIGHT)
                .padding(horizontal = Space.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
            )
            Text(
                text = "$value/$safeMax",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
            )
        }
    }
}

/**
 * A progress sliver, used for mining a block. Thin because it sits under the
 * player's thumb and must not hide the block being dug.
 */
@Composable
fun StratumProgressSliver(
    fraction: Float,
    modifier: Modifier = Modifier,
    tint: Color = StratumTheme.colors.accentAlt,
) {
    val colors = StratumTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(SLIVER_HEIGHT)
            .clip(Cut.bar)
            .background(colors.surfaceSunken),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(SLIVER_HEIGHT)
                .background(tint),
        )
    }
}

/** A small selectable token: a block in the hotbar, a filter, a pack. */
@Composable
fun StratumChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
) {
    val colors = StratumTheme.colors
    val border = if (selected) colors.accent else colors.hairline

    Row(
        modifier = modifier
            .clip(Cut.tiny)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.surfaceSunken)
            .border(if (selected) Stroke.edge else Stroke.hairline, border, Cut.tiny)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = Space.medium, vertical = Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(Cut.tiny)
                    .background(swatch)
                    .border(Stroke.hairline, colors.bevel, Cut.tiny),
            )
            Spacer(Modifier.width(Space.small))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.ink else colors.inkMuted,
        )
    }
}

private val METER_HEIGHT = 22.dp
private val SLIVER_HEIGHT = 4.dp
