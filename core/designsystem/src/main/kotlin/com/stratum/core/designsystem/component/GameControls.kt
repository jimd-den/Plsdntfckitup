package com.stratum.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme

/** How loudly a game button asks to be pressed. */
enum class GameEmphasis {
    /** An ordinary control: outlined, readable over any terrain. */
    NORMAL,

    /** The one thing the thumb should find without looking: filled. */
    PRIMARY,

    /** A toggle that is currently on, or a panel that is open. */
    ACTIVE,
}

/**
 * A round game button: a glyph big enough to recognise at a glance, a one-word
 * label under it, and room for a badge and a cooldown sweep.
 *
 * Round and chunky rather than cut and text-led, because this is the language
 * handheld games teach: the shape says "press me", the glyph says what, the
 * word is there for the first ten minutes and after that the thumb knows.
 */
@Composable
fun GameButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = 56.dp,
    tint: Color = StratumTheme.colors.accent,
    emphasis: GameEmphasis = GameEmphasis.NORMAL,
    enabled: Boolean = true,
    /** A count or a "!" in the corner: something here wants attention. */
    badge: String? = null,
    /** Share of a cooldown still to run, 0..1; drawn as a shadow sweeping away. */
    cooldown: Float = 0f,
) {
    val colors = StratumTheme.colors
    val fill = when (emphasis) {
        GameEmphasis.PRIMARY -> tint
        GameEmphasis.ACTIVE -> tint.copy(alpha = 0.32f)
        GameEmphasis.NORMAL -> colors.surfaceRaised.copy(alpha = 0.86f)
    }
    val glyphColour = if (emphasis == GameEmphasis.PRIMARY) colors.surface else tint
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { contentDescription = label ?: glyph },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(fill)
                    .border(if (emphasis == GameEmphasis.NORMAL) 2.dp else 3.dp, tint.copy(alpha = 0.9f), CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    color = glyphColour,
                    fontSize = (size.value * GLYPH_SCALE).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (cooldown > 0f) CooldownSweep(cooldown.coerceIn(0f, 1f), Modifier.fillMaxSize())
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopEnd))
        }
        if (label != null) {
            Spacer(Modifier.height(Space.hair))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surface.copy(alpha = 0.62f))
                    .padding(horizontal = Space.tight),
            )
        }
    }
}

/** The dark wedge of a cooldown, shrinking clockwise as the skill comes back. */
@Composable
private fun CooldownSweep(remaining: Float, modifier: Modifier) {
    Canvas(modifier) {
        drawArc(
            color = Color.Black.copy(alpha = 0.55f),
            startAngle = -90f,
            sweepAngle = 360f * remaining,
            useCenter = true,
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(CircleShape)
            .background(colors.danger)
            .border(1.5.dp, colors.surface, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * A progress bar that says what it is measuring and how far along it is, in
 * words and a number, for jobs that take minutes rather than a frame.
 */
@Composable
fun GameProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    detail: String? = null,
    tint: Color = StratumTheme.colors.accent,
) {
    val colors = StratumTheme.colors
    val shown = fraction.coerceIn(0f, 1f)
    Column(modifier) {
        if (label != null || detail != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label.orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.ink)
                Text(detail ?: "${(shown * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = colors.inkMuted)
            }
            Spacer(Modifier.height(Space.tight))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surfaceSunken)
                .border(1.dp, colors.hairline, RoundedCornerShape(7.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(tint),
            )
        }
    }
}

/**
 * A large, friendly entry point on a menu: a glyph, what it is called, and one
 * line of what it does -- so a player learns the feature from the tile itself
 * rather than from pressing it to find out.
 */
@Composable
fun GameTile(
    glyph: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = StratumTheme.colors.accent,
    /** A short state line under the description: "3 characters", "Not set up". */
    status: String? = null,
    badge: String? = null,
    highlighted: Boolean = false,
) {
    val colors = StratumTheme.colors
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Cut.medium)
                .background(if (highlighted) tint.copy(alpha = 0.16f) else colors.surfaceRaised)
                .border(if (highlighted) 2.dp else 1.dp, if (highlighted) tint else colors.hairline, Cut.medium)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(Space.medium),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(2.dp, tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = tint, fontSize = 22.sp)
            }
            Spacer(Modifier.height(Space.small))
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.hair))
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, minLines = 2, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (status != null) {
                Spacer(Modifier.height(Space.tight))
                Text(status, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
        if (badge != null) Badge(badge, Modifier.align(Alignment.TopEnd).padding(Space.small))
    }
}

private const val GLYPH_SCALE = 0.42f
