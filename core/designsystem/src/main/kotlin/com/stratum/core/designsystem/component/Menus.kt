package com.stratum.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent

/**
 * The creation-jobs tray, provided once by the app shell.
 *
 * A composition local rather than a parameter threaded through every screen,
 * because the tray belongs in every top bar -- the forges' as much as the
 * hubs' -- and a tool screen should not have to know the jobs system exists
 * to show it. Empty until the shell provides one.
 */
val LocalJobsTray = staticCompositionLocalOf<@Composable () -> Unit> { {} }

/**
 * The one header every menu and tool screen wears: a round back button where
 * the thumb expects it, what this place is called, and the jobs tray on the
 * right. One shape for "up a level" across the whole app is what makes the
 * separate tools read as one game.
 */
@Composable
fun StratumTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
) {
    val colors = StratumTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TOP_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoundIconButton(glyph = "‹", description = "Back", onClick = onBack)
            Spacer(Modifier.width(Space.medium))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            jobsTray()
            actions()
        }
    }
}

/**
 * A round, glyph-only button for chrome: back, settings, close. Always 48dp,
 * the smallest target a thumb finds without looking.
 */
@Composable
fun RoundIconButton(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ICON_BUTTON,
    tint: Color = StratumTheme.colors.ink,
) {
    val colors = StratumTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .border(Stroke.hairline, colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = tint, fontSize = (size.value * 0.46f).sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * A whole menu screen: the pack's surface edge to edge, the safe area kept
 * clear, the top bar pinned, and the body scrolling under it. Content is
 * held to a readable width and centred, so a landscape phone or a tablet
 * shows a column rather than cards stretched into banners.
 */
@Composable
fun StratumScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    maxContentWidth: Dp = SCREEN_MAX_WIDTH,
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
    /** Pinned under the scrolling body: the step's Next, a form's Save. */
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().background(StratumTheme.colors.surface).safeContent(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
            StratumTopBar(
                title = title,
                onBack = onBack,
                subtitle = subtitle,
                actions = actions,
                jobsTray = jobsTray,
                modifier = Modifier.padding(horizontal = Space.large, vertical = Space.small),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = Space.large, end = Space.large, top = Space.small, bottom = Space.huge),
                verticalArrangement = Arrangement.spacedBy(Space.large),
                content = content,
            )
            if (bottomBar != null) {
                StratumDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.large, vertical = Space.medium),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                    verticalAlignment = Alignment.CenterVertically,
                    content = bottomBar,
                )
            }
        }
    }
}

/** How a door looks: the one the screen is for, or one of the others. */
enum class DoorEmphasis { PRIMARY, NORMAL }

/**
 * One of the title screen's big doors: a large glyph, one word, one line of
 * promise. The whole card is the button -- nothing on it needs reading
 * before pressing.
 */
@Composable
fun DoorCard(
    glyph: String,
    title: String,
    promise: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasis: DoorEmphasis = DoorEmphasis.NORMAL,
    tint: Color = StratumTheme.colors.accent,
) {
    val colors = StratumTheme.colors
    val primary = emphasis == DoorEmphasis.PRIMARY
    val fill = if (primary) tint else colors.surfaceRaised
    val ink = if (primary) colors.surface else colors.ink
    val muted = if (primary) colors.surface.copy(alpha = 0.78f) else colors.inkMuted
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = DOOR_MIN_HEIGHT)
            .clip(Cut.large)
            .background(fill)
            .border(if (primary) Stroke.edge else Stroke.hairline, if (primary) tint else colors.hairline, Cut.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.large, vertical = Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DOOR_GLYPH)
                .clip(CircleShape)
                .background(if (primary) colors.surface.copy(alpha = 0.16f) else tint.copy(alpha = 0.16f))
                .border(Stroke.edge, if (primary) colors.surface.copy(alpha = 0.5f) else tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (primary) colors.surface else tint, fontSize = 26.sp, maxLines = 1)
        }
        Spacer(Modifier.width(Space.large))
        Column(Modifier.weight(1f)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.hair))
            Text(promise, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = muted, fontSize = 28.sp, modifier = Modifier.padding(start = Space.small))
    }
}

/** A secondary way in on a hub card: "Weapons", "Sprite mapper". */
data class HubLink(val label: String, val onClick: () -> Unit)

/**
 * A place inside a hub: what it is, one line of what it will do for you, and
 * where it stands right now. Tapping the card goes to the main tool; the
 * links under it go straight to the others.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HubCard(
    glyph: String,
    title: String,
    promise: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusTone: StatusTone = StatusTone.NEUTRAL,
    links: List<HubLink> = emptyList(),
    tint: Color = StratumTheme.colors.accent,
) {
    val colors = StratumTheme.colors
    Column(
        modifier = modifier
            .clip(Cut.medium)
            .background(colors.surfaceRaised)
            .border(Stroke.hairline, colors.hairline, Cut.medium),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(Space.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(HUB_GLYPH).clip(CircleShape).background(tint.copy(alpha = 0.16f)).border(Stroke.edge, tint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = tint, fontSize = 22.sp, maxLines = 1)
            }
            Spacer(Modifier.width(Space.medium))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.hair))
                Text(promise, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (status != null) {
                    Spacer(Modifier.height(Space.small))
                    StatusChip(status, tone = statusTone)
                }
            }
            Text("›", color = colors.inkMuted, fontSize = 26.sp, modifier = Modifier.padding(start = Space.small))
        }
        if (links.isNotEmpty()) {
            StratumDivider()
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.medium, vertical = Space.small),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
                verticalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                links.forEach { link -> LinkPill(link.label, link.onClick) }
            }
        }
    }
}

/** A small tappable word with an arrow; 40dp tall inside a 48dp row. */
@Composable
fun LinkPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Row(
        modifier = modifier
            .clip(Cut.tiny)
            .background(colors.surfaceSunken)
            .border(Stroke.hairline, colors.hairline, Cut.tiny)
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink, maxLines = 1)
        Spacer(Modifier.width(Space.tight))
        Text("›", style = MaterialTheme.typography.titleSmall, color = colors.accent)
    }
}

/** What a status chip is saying, which decides its colour. */
enum class StatusTone {
    /** Just a fact: "12 kept". */
    NEUTRAL,

    /** Good to go: "Ready". */
    READY,

    /** Something is running: "2 running". */
    BUSY,

    /** Blocked on the player: "Needs a model key". */
    NEEDS,
}

/** A short state word or two, coloured by what it means rather than decorated. */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.NEUTRAL) {
    val colors = StratumTheme.colors
    val tint = when (tone) {
        StatusTone.NEUTRAL -> colors.inkMuted
        StatusTone.READY -> colors.accentAlt
        StatusTone.BUSY -> colors.accent
        StatusTone.NEEDS -> colors.danger
    }
    Row(
        modifier = modifier
            .clip(Cut.tiny)
            .background(tint.copy(alpha = 0.14f))
            .border(Stroke.hairline, tint.copy(alpha = 0.6f), Cut.tiny)
            .padding(horizontal = Space.small, vertical = Space.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(Space.tight + Space.hair))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/**
 * A section's heading with an optional action on the right ("See all",
 * "New"), for lists inside a hub.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        if (actionLabel != null) LinkPill(actionLabel, onAction)
    }
}

/**
 * Nothing here yet, said kindly and with the one thing to do about it. An
 * empty list with no way forward is a dead end; this never is.
 */
@Composable
fun EmptyState(
    glyph: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = StratumTheme.colors
    StratumWell(modifier = modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.large)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, fontSize = 32.sp, color = colors.accent)
            Spacer(Modifier.height(Space.small))
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.tight))
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Spacer(Modifier.height(Space.medium))
                StratumAction(label = actionLabel, onClick = onAction, emphasis = ActionEmphasis.PRIMARY)
            }
        }
    }
}

/**
 * Where a short flow is: numbered dots joined by a rule, the current one lit.
 * Done steps can be tapped to go back to them.
 */
@Composable
fun StepIndicator(
    steps: List<String>,
    current: Int,
    modifier: Modifier = Modifier,
    onStep: (Int) -> Unit = {},
) {
    val colors = StratumTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, label ->
            val reached = index <= current
            Row(
                Modifier
                    .clip(Cut.tiny)
                    .clickable(enabled = index < current, role = Role.Tab) { onStep(index) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = Space.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (index == current) colors.accent else if (reached) colors.accent.copy(alpha = 0.24f) else colors.surfaceSunken)
                        .border(Stroke.hairline, if (reached) colors.accent else colors.hairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (index < current) "✓" else "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index == current) colors.surface else if (reached) colors.accent else colors.inkMuted,
                    )
                }
                Spacer(Modifier.width(Space.small))
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == current) colors.ink else colors.inkMuted,
                    maxLines = 1,
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Space.tight)
                        .height(Stroke.edge)
                        .background(if (index < current) colors.accent else colors.hairline),
                )
            }
        }
    }
}

private val TOP_BAR_HEIGHT = 56.dp
private val ICON_BUTTON = 48.dp
private val DOOR_MIN_HEIGHT = 96.dp
private val DOOR_GLYPH = 56.dp
private val HUB_GLYPH = 48.dp
private val SCREEN_MAX_WIDTH = 760.dp
