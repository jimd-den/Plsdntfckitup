package com.stratum.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme

/**
 * One option among several, picked by tapping it: a hero, a world preset.
 * The chosen one is outlined in the accent and ticked, so which is picked
 * reads from across the room.
 */
@Composable
fun ChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    /** A short fact on the right: "Lv 12", "New". */
    tag: String? = null,
) {
    val colors = StratumTheme.colors
    Row(
        modifier = modifier
            .clip(Cut.medium)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.surfaceRaised)
            .border(if (selected) Stroke.edge else Stroke.hairline, if (selected) colors.accent else colors.hairline, Cut.medium)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.accent.copy(alpha = if (selected) 0.3f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = colors.accent, fontSize = 18.sp, maxLines = 1)
            }
            Spacer(Modifier.width(Space.medium))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (tag != null) {
            Spacer(Modifier.width(Space.small))
            Text(tag, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.accent else colors.inkMuted, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(Space.small))
            Box(Modifier.size(24.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Text("✓", color = colors.surface, fontSize = 14.sp)
            }
        }
    }
}
