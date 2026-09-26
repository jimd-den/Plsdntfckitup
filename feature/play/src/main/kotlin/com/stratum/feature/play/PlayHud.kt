package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.GameButton
import com.stratum.core.designsystem.component.GameEmphasis
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumMeter
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumProgressSliver
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.world.World
import com.stratum.engine.world.BuildTool
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Health, resource, level and what is in hand: everything about the hero a
 * glance should answer, in the corner a glance goes to first.
 */
@Composable
internal fun VitalsCard(state: PlayUiState, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    // On a panel rather than straight on the world: terrain changes colour
    // with the region, and text over pale ground stops being readable.
    StratumPanel(
        modifier = modifier.width(VITALS_WIDTH),
        shape = Cut.small,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.small),
    ) {
        StratumMeter(label = "♥ Vitality", value = state.player.health, max = state.player.maxHealthWithGear, tint = colors.danger)
        Spacer(Modifier.height(Space.hair))
        StratumMeter(label = "✦ ${state.player.resourceName}", value = state.player.resource, max = state.player.resourceCeiling, tint = colors.accentAlt)
        Spacer(Modifier.height(Space.tight))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LV ${state.player.level}" + if (state.hero.tier > 0) " · T${state.hero.tier}" else "", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            Text("${(state.player.experienceFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        Spacer(Modifier.height(Space.hair))
        StratumProgressSliver(fraction = state.player.experienceFraction, tint = colors.accent)
        Spacer(Modifier.height(Space.tight))
        Text(
            text = state.player.equippedWeapon?.let { "${it.glyph} ${it.name}" } ?: "Bare hands",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One entry in the feature dock. */
internal data class DockEntry(
    val glyph: String,
    val label: String,
    val onClick: () -> Unit,
    val badge: String? = null,
    val active: Boolean = false,
    /** Lit when something inside wants doing, such as points to spend. */
    val calling: Boolean = false,
)

/**
 * The game's systems, each one tap away and each labelled: bag, anvil, hero,
 * table, style. A badge says when something inside is waiting, so a new
 * feature is found by the number on it rather than by reading a manual.
 */
@Composable
internal fun FeatureDock(entries: List<DockEntry>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        entries.forEach { entry ->
            GameButton(
                glyph = entry.glyph,
                label = entry.label,
                onClick = entry.onClick,
                size = DOCK_BUTTON,
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

/**
 * Where the player is and what the game just said. The hint is a teacher,
 * not a label: it says what the next gesture does.
 */
@Composable
internal fun RegionBanner(state: PlayUiState, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val line = state.message ?: if (state.settlementHostile) {
        "Defeat the garrison to liberate it"
    } else if (state.buildMode) {
        "Drag on the ground to build · pick a shape and a block below"
    } else {
        "Stick to move · tap ground to dig · hold to place"
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .padding(horizontal = Space.medium, vertical = Space.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // In a town, the town is the place; out in the wilds, the region is.
        val place = state.settlementName?.let { if (state.settlementHostile) "$it · stronghold" else it } ?: state.biomeName.ifBlank { "Uncharted" }
        Text(place.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (state.settlementHostile) colors.danger else colors.accent, maxLines = 1)
        Text(line, style = MaterialTheme.typography.labelSmall, color = colors.ink, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** A button placed around the big one: where it sits and what it is. */
internal data class ArcButton(
    val glyph: String,
    val label: String,
    val onClick: () -> Unit,
    val tint: Color? = null,
    val cooldown: Float = 0f,
    val enabled: Boolean = true,
    val active: Boolean = false,
)

/**
 * The right thumb's cluster: one big button, the thing the current mode is
 * for, with the rest fanned around it in reach of the same thumb -- the way
 * a handheld puts A under the thumb and B, X and Y around it.
 *
 * In a fight the big button strikes and the fan is roll and skills; in build
 * mode it is done and the fan is the build tools. Same place, same gesture,
 * so the thumb never relearns where things are.
 */
@Composable
internal fun ActionCluster(
    primaryGlyph: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    around: List<ArcButton>,
    modifier: Modifier = Modifier,
    primaryTint: Color = StratumTheme.colors.danger,
) {
    val placed = clusterOffsets(around.size)
    val reach = placed.maxOfOrNull { (dx, dy) -> maxOf(-dx, -dy) } ?: 0f
    Box(modifier.size(reach.dp + PRIMARY_BUTTON + Space.large), contentAlignment = Alignment.BottomEnd) {
        placed.forEachIndexed { index, (dx, dy) ->
            val button = around[index]
            GameButton(
                glyph = button.glyph,
                label = button.label,
                onClick = button.onClick,
                size = ARC_BUTTON,
                tint = button.tint ?: StratumTheme.colors.accent,
                cooldown = button.cooldown,
                enabled = button.enabled,
                emphasis = if (button.active) GameEmphasis.ACTIVE else GameEmphasis.NORMAL,
                // Centred on the big button's centre, then pushed out along the arc.
                modifier = Modifier.offset(
                    x = (dx.dp - (PRIMARY_BUTTON - ARC_BUTTON) / 2),
                    y = (dy.dp - (PRIMARY_BUTTON - ARC_BUTTON) / 2),
                ),
            )
        }
        GameButton(
            glyph = primaryGlyph,
            label = primaryLabel,
            onClick = onPrimary,
            size = PRIMARY_BUTTON,
            tint = primaryTint,
            emphasis = GameEmphasis.PRIMARY,
        )
    }
}

/**
 * Where [count] buttons sit around the big one: rings of at most
 * [PER_RING], each ring as far out as it needs to be for its buttons and
 * their labels not to touch, the next ring beyond it.
 */
internal fun clusterOffsets(count: Int): List<Pair<Float, Float>> {
    val offsets = mutableListOf<Pair<Float, Float>>()
    var radius = 0f
    var left = count
    while (left > 0) {
        val ring = minOf(left, PER_RING)
        radius = maxOf(radius + RING_GAP, ringRadius(ring))
        offsets += arcOffsets(ring, radius)
        left -= ring
    }
    return offsets
}

/** The radius at which [count] buttons fanned over a quarter turn keep [CLEARANCE] between centres. */
internal fun ringRadius(count: Int): Float {
    if (count <= 1) return MIN_RING
    val halfStep = (PI / 2.0) / (count - 1) / 2.0
    return maxOf(MIN_RING, (CLEARANCE / (2.0 * kotlin.math.sin(halfStep))).toFloat())
}

/**
 * Where each of [count] buttons sits around the big one, as offsets in dp
 * from its centre: fanned from straight left to straight up, so every one is
 * a short arc of the thumb away and none sits under the screen edge.
 */
internal fun arcOffsets(count: Int, radius: Float, fromDegrees: Float = 180f, toDegrees: Float = 270f): List<Pair<Float, Float>> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(polar(radius, (fromDegrees + toDegrees) / 2f))
    val step = (toDegrees - fromDegrees) / (count - 1)
    return List(count) { polar(radius, fromDegrees + step * it) }
}

private fun polar(radius: Float, degrees: Float): Pair<Float, Float> {
    val radians = degrees * PI / 180.0
    return (cos(radians) * radius).toFloat() to (sin(radians) * radius).toFloat()
}

/**
 * Everything building needs in one tray: the shapes a drag can make, and
 * the blocks to make them from. Opens with build mode and closes with it.
 */
@Composable
internal fun BuildTray(
    state: PlayUiState,
    world: World,
    onSelectBuildTool: (BuildTool) -> Unit,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = 0.78f))
            .padding(Space.small),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            items(BuildTool.entries.size) { index ->
                val tool = BuildTool.entries[index]
                GameButton(
                    glyph = tool.glyph(),
                    label = tool.label,
                    onClick = { onSelectBuildTool(tool) },
                    size = TOOL_BUTTON,
                    tint = if (tool.removes) colors.danger else colors.accent,
                    emphasis = if (state.buildTool == tool) GameEmphasis.ACTIVE else GameEmphasis.NORMAL,
                )
            }
        }
        BlockStrip(state, world, onSelectSlot, Modifier.fillMaxWidth())
    }
}

/** The blocks the player can place, shown while building. */
@Composable
internal fun BlockStrip(state: PlayUiState, world: World, onSelectSlot: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (state.player.hotbar.isEmpty()) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        itemsIndexed(state.player.hotbar) { index, blockId ->
            val type = world.registry.indexOrNull(blockId)?.let(world.registry::typeOf)
            StratumChip(
                label = "${type?.glyph?.let { "$it " }.orEmpty()}${type?.displayName ?: blockId} ${state.player.countOf(blockId)}",
                selected = index == state.player.selectedSlot,
                onClick = { onSelectSlot(index) },
                swatch = type?.let { Color(it.topColor) },
            )
        }
    }
}

/** A glyph per build tool, so the fan reads without its labels. */
internal fun BuildTool.glyph(): String = when (this) {
    BuildTool.SINGLE -> "▪"
    BuildTool.LINE -> "━"
    BuildTool.FLOOR -> "▭"
    BuildTool.WALLS -> "▥"
    BuildTool.ROOM -> "⌂"
    BuildTool.ERASE -> "✕"
}

/** Two small buttons for the camera, out of the thumbs' way. */
@Composable
internal fun ZoomPair(onZoom: (Float) -> Unit, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val zoomIn = @Composable { GameButton(glyph = "+", onClick = { onZoom(ZOOM_STEP) }, size = ZOOM_BUTTON) }
    val zoomOut = @Composable { GameButton(glyph = "−", onClick = { onZoom(-ZOOM_STEP) }, size = ZOOM_BUTTON) }
    if (horizontal) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.small)) { zoomOut(); zoomIn() }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) { zoomIn(); zoomOut() }
    }
}

/** A thin bar over the controls while a block is being dug. */
@Composable
internal fun MiningBar(name: String, fraction: Float, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(
        modifier
            .width(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .clickable(enabled = false) {}
            .padding(Space.small),
    ) {
        Text("⛏ $name", style = MaterialTheme.typography.labelSmall, color = colors.ink, maxLines = 1)
        Spacer(Modifier.height(Space.tight))
        StratumProgressSliver(fraction = fraction)
    }
}

internal val VITALS_WIDTH: Dp = 176.dp

/** The widest a panel over the world gets, so a landscape phone or a tablet reads a column, not a banner. */
internal val PANEL_MAX_WIDTH: Dp = 720.dp
internal val DOCK_BUTTON: Dp = 48.dp
internal val PRIMARY_BUTTON: Dp = 80.dp
internal val ARC_BUTTON: Dp = 56.dp
private val ZOOM_BUTTON: Dp = 36.dp
private val TOOL_BUTTON: Dp = 44.dp
private const val PER_RING = 4
/** Centre to centre, in dp: a button, its label and a finger's slack. */
private const val CLEARANCE = 80f
private const val MIN_RING = 112f
private const val RING_GAP = 80f
internal val STICK_SIZE: Dp = 132.dp
internal const val ZOOM_STEP = 0.2f
