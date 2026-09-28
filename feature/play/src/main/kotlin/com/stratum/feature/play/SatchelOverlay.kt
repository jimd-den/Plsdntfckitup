package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.world.World
import kotlin.math.roundToInt

/** What the satchel can ask for. One object, so the play screen passes one parameter. */
data class GearActions(
    val onSelectSlot: (EquipmentSlot?) -> Unit = {},
    val onInspect: (String?) -> Unit = {},
    /** Rolls a held item again as the same thing; only offered in a sandbox. */
    val onReroll: (String) -> Unit = {},
)

/**
 * The satchel: a paper doll of the ten places gear is worn, the piece in the
 * chosen place read in full, and the bag sorted against it.
 *
 * Every bagged item is shown against the whole character rather than on its
 * own. "+4 attack, -12 life" is a decision; "attack 31" is homework the
 * player has to do in their head while something is chewing on them.
 */
@Composable
fun SatchelOverlay(
    state: PlayUiState,
    world: World,
    onEquip: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onOpenAnvil: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onUnequip: (EquipmentSlot) -> Unit = {},
    actions: GearActions = GearActions(),
) {
    val colors = StratumTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(Modifier.safeContent().padding(Space.small)) {
            val landscape = maxWidth > maxHeight
            StratumPanel(
                modifier = Modifier
                    .fillMaxWidth(if (landscape) 0.98f else 0.96f)
                    .widthIn(max = if (landscape) WIDE_PANEL else PANEL_MAX_WIDTH)
                    .clickable(enabled = false, onClick = {}),
                shape = Cut.large,
                contentPadding = PaddingValues(Space.large),
            ) {
                Header(onOpenAnvil, onClose)
                Spacer(Modifier.height(Space.small))
                if (landscape) {
                    // Landscape: the doll stays in view on the left, the reading scrolls on the right.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.large)) {
                        Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState())) {
                            PaperDoll(state, actions)
                            Figures(state)
                        }
                        Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState())) {
                            Reading(state, world, onEquip, onDiscard, onUnequip, actions)
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PaperDoll(state, actions)
                        Figures(state)
                        Spacer(Modifier.height(Space.medium))
                        Reading(state, world, onEquip, onDiscard, onUnequip, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(onOpenAnvil: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Satchel")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumAction(label = "Anvil", onClick = onOpenAnvil, emphasis = ActionEmphasis.SECONDARY)
            StratumAction(label = "Close", onClick = onClose, emphasis = ActionEmphasis.QUIET)
        }
    }
}

/**
 * The ten places, laid out like a body: head and neck, hands and chest,
 * rings either side of the waist, feet. Each cell carries its piece's glyph
 * in its rarity's colour; tapping one reads it and sorts the bag against it.
 */
@Composable
private fun PaperDoll(state: PlayUiState, actions: GearActions) {
    val colors = StratumTheme.colors
    val gear = state.gear
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        GearPanelBuilder.doll.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                row.forEach { slot ->
                    if (slot == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val item = state.player.equipment[slot]
                        val selected = gear.selectedSlot == slot
                        val tint = item?.let { Color(state.rarityColor(it)) } ?: colors.hairline
                        Column(
                            Modifier
                                .weight(1f)
                                .aspectRatio(DOLL_CELL_RATIO)
                                .background(if (selected) tint.copy(alpha = 0.22f) else colors.surfaceSunken, Cut.small)
                                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else tint, Cut.small)
                                .clickable { actions.onSelectSlot(slot) }
                                .padding(Space.tight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(item?.glyph ?: slot.emptyGlyph, style = MaterialTheme.typography.titleMedium, color = if (item != null) tint else colors.inkMuted)
                            Text(
                                item?.name ?: slot.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item != null) tint else colors.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        gear.sets.forEach { set ->
            Text(
                "${set.name}: ${set.worn}/${set.pieces} worn" + (set.nextBonusAt?.let { " · next bonus at $it" } ?: " · complete"),
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
            )
        }
    }
}

/** The fought-with numbers, at a glance, under the doll. */
@Composable
private fun Figures(state: PlayUiState) {
    val stats = state.player.combatStatsWith(state::insertOrNull)
    Spacer(Modifier.height(Space.small))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        Figure("ATK", stats.attackPower.toString(), Modifier.weight(1f))
        Figure("LIFE", stats.maxHealth.toString(), Modifier.weight(1f))
        Figure("ARM", stats.armour.toString(), Modifier.weight(1f))
        Figure("CRIT", "${(stats.critChance * 100).roundToInt()}%", Modifier.weight(1f))
    }
}

/** The chosen piece, then the bag against it, then what is counted rather than rolled. */
@Composable
private fun Reading(
    state: PlayUiState,
    world: World,
    onEquip: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onUnequip: (EquipmentSlot) -> Unit,
    actions: GearActions,
) {
    val gear = state.gear
    val slot = gear.selectedSlot
    val sandbox = state.sandbox.active
    if (slot != null) {
        val worn = gear.worn[slot]
        SectionLabel(slot.label)
        Spacer(Modifier.height(Space.tight))
        if (worn == null) {
            Muted("Nothing worn here.")
        } else {
            ItemCardView(state, worn) {
                StratumAction(label = "Take off", onClick = { onUnequip(slot) }, emphasis = ActionEmphasis.QUIET)
                if (sandbox) StratumAction(label = "Reroll", onClick = { actions.onReroll(worn.item.instanceId) }, emphasis = ActionEmphasis.QUIET)
            }
        }
        Spacer(Modifier.height(Space.medium))
    }
    gear.inspected?.takeIf { it.item.instanceId !in gear.worn.values.map { card -> card.item.instanceId } }?.let { card ->
        SectionLabel("Reading")
        Spacer(Modifier.height(Space.tight))
        ItemCardView(state, card) {
            StratumAction(label = "Equip", onClick = { onEquip(card.item.instanceId) }, emphasis = ActionEmphasis.PRIMARY, enabled = card.canWear)
            if (sandbox) StratumAction(label = "Reroll", onClick = { actions.onReroll(card.item.instanceId) }, emphasis = ActionEmphasis.QUIET)
            StratumAction(label = "Close", onClick = { actions.onInspect(null) }, emphasis = ActionEmphasis.QUIET)
        }
        Spacer(Modifier.height(Space.medium))
    }
    SectionLabel(if (slot != null) "Carrying for ${slot.label.lowercase()}" else "Carrying")
    Spacer(Modifier.height(Space.tight))
    if (gear.candidates.isEmpty()) {
        Muted(if (slot != null) "Nothing in the bag goes here." else "Nothing but what you are wearing.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        gear.candidates.forEach { card ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { actions.onInspect(card.item.instanceId) }) {
                    Text("${card.item.glyph} ${card.item.name}", style = MaterialTheme.typography.bodySmall, color = Color(state.rarityColor(card.item)))
                    if (card.canWear) DeltaLine(card.deltas) else Muted("Needs level ${card.item.requiredLevel}")
                }
                StratumAction(label = "Equip", onClick = { onEquip(card.item.instanceId) }, emphasis = ActionEmphasis.SECONDARY, enabled = card.canWear)
                StratumAction(label = "Drop", onClick = { onDiscard(card.item.instanceId) }, emphasis = ActionEmphasis.QUIET)
            }
        }
    }
    Spacer(Modifier.height(Space.medium))
    StratumDivider()
    Spacer(Modifier.height(Space.medium))
    SectionLabel("Materials")
    Spacer(Modifier.height(Space.small))
    MaterialsRow(state = state, world = world)
}

/** One item in full, its name in its rarity's colour, and the actions that go with it. */
@Composable
private fun ItemCardView(state: PlayUiState, card: GearCard, actions: @Composable () -> Unit) {
    StratumWell(Modifier.fillMaxWidth()) {
        Text("${card.item.glyph} ${card.item.name}", style = MaterialTheme.typography.titleSmall, color = Color(state.rarityColor(card.item)))
        if (card.item.baseName.isNotBlank() && card.item.baseName != card.item.name) {
            Text(card.item.baseName, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
        }
        Spacer(Modifier.height(Space.tight))
        TooltipLines(card.lines)
        if (card.deltas.isNotEmpty()) {
            Spacer(Modifier.height(Space.tight))
            DeltaLine(card.deltas)
        }
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) { actions() }
    }
}

/** Block stacks and loose inserts: everything that is counted rather than rolled. */
@Composable
private fun MaterialsRow(state: PlayUiState, world: World) {
    val blocks = state.player.inventory.entries.filter { it.value > 0 }
    if (blocks.isEmpty() && state.heldInserts.isEmpty()) {
        Muted("Nothing gathered yet.")
        return
    }
    LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        items(blocks, key = { it.key }) { (blockId, count) ->
            val type = world.registry.indexOrNull(blockId)?.let(world.registry::typeOf)
            StratumChip(
                label = "${type?.glyph?.let { "$it " }.orEmpty()}${type?.displayName ?: blockId} $count",
                selected = false,
                onClick = {},
                swatch = type?.let { Color(it.topColor) },
            )
        }
        items(state.heldInserts, key = { it.definition.id }) { held ->
            StratumChip(
                label = "${held.definition.glyph} ${held.definition.name} ×${held.count}",
                selected = false,
                onClick = {},
                swatch = Color(held.definition.color),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    StratumWell(modifier = modifier, contentPadding = PaddingValues(Space.small)) {
        Text(text = value, style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.accent)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
}

/** What an empty place on the doll shows: its kind of gear, faintly. */
private val EquipmentSlot.emptyGlyph: String
    get() = when (this) {
        EquipmentSlot.WEAPON -> "⚔"
        EquipmentSlot.OFFHAND -> "🛡"
        EquipmentSlot.HELM -> "⛑"
        EquipmentSlot.CHEST -> "🥋"
        EquipmentSlot.GLOVES -> "🧤"
        EquipmentSlot.BOOTS -> "🥾"
        EquipmentSlot.BELT -> "🎗"
        EquipmentSlot.AMULET -> "📿"
        EquipmentSlot.RING_LEFT, EquipmentSlot.RING_RIGHT -> "💍"
    }

private const val DOLL_CELL_RATIO = 1.25f
private val WIDE_PANEL = 980.dp
