package com.stratum.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme

/**
 * The one container the interface uses.
 *
 * A panel is a cut slab: chamfered corners, a hairline border and a lit top
 * edge. Every surface in the app is this component at a different size, which is
 * what keeps the interface reading as one object rather than a pile of cards.
 */
@Composable
fun StratumPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Cut.medium,
    raised: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(Space.large),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = StratumTheme.colors
    val fill = if (raised) colors.surfaceRaised else colors.surfaceSunken

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(Stroke.hairline, colors.hairline, shape),
    ) {
        // A one-pixel lit edge reads as light catching the top of a slab, which
        // a drop shadow cannot do on a dark surface.
        if (raised) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Stroke.bevel)
                    .background(colors.bevel),
            )
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A panel with a heading. Kept separate so a panel that needs no title does not
 * pay for one.
 */
@Composable
fun StratumSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    StratumPanel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(title)
                if (subtitle != null) {
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = StratumTheme.colors.inkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(Space.medium))
        content()
    }
}

/**
 * A heading with the accent tick that marks every section in the app. It is the
 * cheapest consistent signal available and costs no vertical space.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .height(12.dp)
                .width(Stroke.edge)
                .background(colors.accent),
        )
        Spacer(Modifier.width(Space.small))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colors.ink,
        )
    }
}

/** Hairline divider, matching the panel border rather than Material's default. */
@Composable
fun StratumDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Stroke.hairline)
            .background(StratumTheme.colors.hairline),
    )
}

/**
 * A recessed well, for anything the player reads rather than acts on: counts,
 * readouts, the block palette.
 */
@Composable
fun StratumWell(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Space.medium),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = StratumTheme.colors
    Column(
        modifier = modifier
            .clip(Cut.small)
            .background(colors.surfaceSunken)
            .border(Stroke.hairline, colors.bevel, Cut.small)
            .padding(contentPadding),
        content = content,
    )
}
