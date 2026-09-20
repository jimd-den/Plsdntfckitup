package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.engine.world.HeldInsert

/**
 * The anvil: pick an item, fill its sockets, pull things back out.
 *
 * Laid out as three stacked rows — what you are working on, what is in it, what
 * you are holding — so the whole operation reads top to bottom in one glance
 * and every tap is a single gesture. A drag-and-drop gem bench looks better in a
 * screenshot and is miserable with a thumb on a phone.
 */
@Composable
fun AnvilOverlay(
    state: PlayUiState,
    onSelectItem: (String) -> Unit,
    onSlot: (String, String) -> Unit,
    onUnslot: (String, Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    val item = state.anvilItem

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = 0.9f))
            // Swallows taps so a stray press does not mine the block behind the
            // panel while the player is reading it.
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        StratumPanel(
            modifier = Modifier
                .safeContent()
                .fillMaxWidth(0.92f)
                .clickable(enabled = false, onClick = {}),
            shape = Cut.large,
            contentPadding = PaddingValues(Space.large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Anvil")
                StratumAction(label = "Close", onClick = onClose, emphasis = ActionEmphasis.QUIET)
            }

            if (item == null) {
                Spacer(Modifier.height(Space.medium))
                Text(
                    text = "Nothing you carry has sockets yet. Better weapons come with them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                )
                return@StratumPanel
            }

            Spacer(Modifier.height(Space.medium))
            AnvilItemPicker(state = state, selected = item, onSelectItem = onSelectItem)

            Spacer(Modifier.height(Space.medium))
            Text(
                text = "${item.glyph} ${item.name}",
                style = MaterialTheme.typography.titleSmall,
                color = Color(state.rarityColor(item)),
            )
            Text(
                text = "${item.minDamage}–${item.maxDamage} damage · ${item.sockets.used}/${item.socketCount} sockets",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )

            Spacer(Modifier.height(Space.small))
            SocketRow(
                item = item,
                insertFor = state::insertOrNull,
                onUnslot = { index -> onUnslot(item.instanceId, index) },
            )

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Pouch")
            Spacer(Modifier.height(Space.small))
            PouchRow(
                held = state.heldInserts,
                canSlot = item.hasFreeSocket,
                onSlot = { insertId -> onSlot(item.instanceId, insertId) },
            )
        }
    }
}

/** Which item the anvil is working on. Hidden when there is only one choice. */
@Composable
private fun AnvilItemPicker(
    state: PlayUiState,
    selected: ItemInstance,
    onSelectItem: (String) -> Unit,
) {
    val items = state.anvilItems
    if (items.size <= 1) return

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(items, key = ItemInstance::instanceId) { candidate ->
            StratumChip(
                label = "${candidate.glyph} ${candidate.name} ${candidate.sockets.used}/${candidate.socketCount}",
                selected = candidate.instanceId == selected.instanceId,
                onClick = { onSelectItem(candidate.instanceId) },
                swatch = Color(state.rarityColor(candidate)),
            )
        }
    }
}

/**
 * The item's sockets, in order. A filled socket shows its insert's colour and
 * empties on tap; an empty one is drawn as a hole rather than omitted, so the
 * player can see what the weapon is still capable of.
 */
@Composable
private fun SocketRow(
    item: ItemInstance,
    insertFor: (String) -> InsertDefinition?,
    onUnslot: (Int) -> Unit,
) {
    val colors = StratumTheme.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        itemsIndexed(item.sockets.filled) { index, insertId ->
            val insert = insertId?.let(insertFor)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            // An empty socket is lit rather than left black: on
                            // a dark panel an unlit hole is indistinguishable
                            // from the panel, and the player cannot count them.
                            color = insert?.let { Color(it.color) }
                                ?: colors.ink.copy(alpha = 0.14f),
                            shape = CircleShape,
                        )
                        .border(
                            width = if (insert == null) 2.dp else 1.dp,
                            color = colors.inkMuted.copy(alpha = if (insert == null) 0.9f else 0.6f),
                            shape = CircleShape,
                        )
                        .clickable(enabled = insert != null) { onUnslot(index) },
                )
                Spacer(Modifier.height(Space.hair))
                Text(
                    text = insert?.name ?: "empty",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (insert == null) colors.inkMuted else colors.ink,
                )
            }
        }
    }
}

/** What the player is holding, and what each one would add. */
@Composable
private fun PouchRow(
    held: List<HeldInsert>,
    canSlot: Boolean,
    onSlot: (String) -> Unit,
) {
    val colors = StratumTheme.colors
    if (held.isEmpty()) {
        Text(
            text = "No inserts yet. They drop from what you kill.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        return
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(held, key = { it.definition.id }) { entry ->
            Column(
                modifier = Modifier.padding(end = Space.tight),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StratumChip(
                    label = "${entry.definition.glyph} ${entry.definition.name} ×${entry.count}",
                    selected = false,
                    onClick = { if (canSlot) onSlot(entry.definition.id) },
                    swatch = Color(entry.definition.color),
                )
                Spacer(Modifier.height(Space.hair))
                Text(
                    text = entry.definition.statLine,
                    style = MaterialTheme.typography.labelSmall,
                    // The full socket is the reason a tap does nothing; dim the
                    // whole row rather than leaving the player tapping at it.
                    color = if (canSlot) colors.inkMuted else colors.inkMuted.copy(alpha = 0.4f),
                )
            }
        }
    }
}
