package com.stratum.feature.play

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.item.EquipmentSlot
import com.stratum.core.domain.item.InsertDefinition
import com.stratum.core.domain.item.ItemInstance
import com.stratum.core.domain.session.PlayerState
import com.stratum.core.domain.world.World
import kotlin.math.roundToInt

/**
 * The satchel: what you are wearing, what you are carrying, and what it is worth.
 *
 * Every bagged item is shown against the equipped one rather than on its own.
 * "+4 attack, -12 health" is a decision; "attack 31" is homework the player has
 * to do in their head while something is chewing on them.
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
) {
    val colors = StratumTheme.colors
    val equipped = state.player.equippedWeapon

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = 0.9f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        StratumPanel(
            modifier = Modifier
                .safeContent()
                .fillMaxWidth(0.94f)
                // Capped and scrolling, so a landscape phone gets a readable
                // column rather than a stretched one cut off at the bottom.
                .widthIn(max = PANEL_MAX_WIDTH)
                .verticalScroll(rememberScrollState())
                .clickable(enabled = false, onClick = {}),
            shape = Cut.large,
            contentPadding = PaddingValues(Space.large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Satchel")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    StratumAction(
                        label = "Anvil",
                        onClick = onOpenAnvil,
                        emphasis = ActionEmphasis.SECONDARY,
                    )
                    StratumAction(label = "Close", onClick = onClose, emphasis = ActionEmphasis.QUIET)
                }
            }

            Spacer(Modifier.height(Space.medium))
            EquippedPanel(state = state, equipped = equipped)

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Worn")
            Spacer(Modifier.height(Space.small))
            WornList(state = state, onUnequip = onUnequip)

            Spacer(Modifier.height(Space.medium))
            StratumDivider()
            Spacer(Modifier.height(Space.medium))

            SectionLabel("Carrying")
            Spacer(Modifier.height(Space.small))
            BagList(
                state = state,
                onEquip = onEquip,
                onDiscard = onDiscard,
            )

            Spacer(Modifier.height(Space.medium))
            SectionLabel("Materials")
            Spacer(Modifier.height(Space.small))
            MaterialsRow(state = state, world = world)
        }
    }
}

/** What is in hand, spelled out: base damage, every affix, every insert. */
@Composable
private fun EquippedPanel(state: PlayUiState, equipped: ItemInstance?) {
    val colors = StratumTheme.colors

    if (equipped == null) {
        Text(
            text = "Bare hands. Anything you pick up is an upgrade.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        return
    }

    Text(
        text = "${equipped.glyph} ${equipped.name}",
        style = MaterialTheme.typography.titleSmall,
        color = Color(state.rarityColor(equipped)),
    )
    Text(
        text = "${equipped.baseLine} · item level ${equipped.itemLevel}" +
            if (equipped.socketCount > 0) " · ${equipped.sockets.used}/${equipped.socketCount} sockets" else "",
        style = MaterialTheme.typography.labelSmall,
        color = colors.inkMuted,
    )

    Spacer(Modifier.height(Space.small))
    equipped.affixes.forEach { affix ->
        Text(
            text = affix.description,
            style = MaterialTheme.typography.labelSmall,
            color = colors.accentAlt,
        )
    }
    equipped.sockets.insertIds.mapNotNull(state::insertOrNull).forEach { insert ->
        Text(
            text = "${insert.glyph} ${insert.name} — ${insert.statLine}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(insert.color),
        )
    }

    Spacer(Modifier.height(Space.small))
    val stats = state.player.combatStatsWith(state::insertOrNull)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        Figure("ATK", stats.attackPower.toString(), Modifier.weight(1f))
        Figure("ARM", stats.armour.toString(), Modifier.weight(1f))
        Figure("CRIT", "${(stats.critChance * 100).roundToInt()}%", Modifier.weight(1f))
        Figure("SPD", String.format("%.2f", stats.attackSpeed), Modifier.weight(1f))
    }
}

/**
 * The bag. Each row carries its own compare line and its own two actions, so
 * nothing needs a long press or a second screen to act on.
 */
/** Every place gear is worn, with what is there and a way to take it off. */
@Composable
private fun WornList(state: PlayUiState, onUnequip: (EquipmentSlot) -> Unit) {
    val colors = StratumTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.tight)) {
        EquipmentSlot.entries.forEach { slot ->
            val item = state.player.equipment[slot]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = slot.label, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.weight(0.35f))
                Text(
                    text = item?.let { "${it.glyph} ${it.name}" } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = item?.let { Color(state.rarityColor(it)) } ?: colors.inkMuted,
                    modifier = Modifier.weight(0.65f),
                )
                if (item != null) {
                    StratumAction(label = "Off", onClick = { onUnequip(slot) }, emphasis = ActionEmphasis.QUIET)
                }
            }
        }
    }
}

@Composable
private fun BagList(
    state: PlayUiState,
    onEquip: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val colors = StratumTheme.colors
    val bag = state.player.bag

    if (bag.isEmpty()) {
        Text(
            text = "Nothing but what you are holding.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(bag, key = ItemInstance::instanceId) { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.glyph} ${item.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(state.rarityColor(item)),
                    )
                    Text(
                        text = compareLine(state.player, item, state::insertOrNull),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
                StratumAction(
                    label = "Equip",
                    onClick = { onEquip(item.instanceId) },
                    emphasis = ActionEmphasis.SECONDARY,
                )
                StratumAction(
                    label = "Drop",
                    onClick = { onDiscard(item.instanceId) },
                    emphasis = ActionEmphasis.QUIET,
                )
            }
        }
    }
}

/**
 * How the character would change wearing a bagged item in place of whatever
 * it would replace -- the whole character, so a ring's "increased damage"
 * shows as the damage it really adds.
 *
 * Only the lines that actually moved are printed: a list of six stats where five
 * read "0" is a list the player learns to skip.
 */
private fun compareLine(
    player: PlayerState,
    item: ItemInstance,
    inserts: (String) -> InsertDefinition?,
): String {
    if (!player.canWear(item)) return "${item.slot.name.lowercase()} · needs level ${item.requiredLevel}"
    val theirs = player.combatStatsWith(inserts)
    val mine = player.collecting(item).equipping(item).combatStatsWith(inserts)

    val parts = buildList {
        delta("attack", mine.attackPower - theirs.attackPower)?.let(::add)
        delta("armour", mine.armour - theirs.armour)?.let(::add)
        delta("health", mine.maxHealth - theirs.maxHealth)?.let(::add)
        val crit = ((mine.critChance - theirs.critChance) * 100).roundToInt()
        delta("crit", crit, suffix = "%")?.let(::add)
        if (item.socketCount > 0) add("${item.socketCount} sockets")
    }
    return (listOf(item.slot.name.lowercase()) + parts.ifEmpty { listOf("same as what you wear") }).joinToString(" · ")
}

private fun delta(label: String, amount: Int, suffix: String = ""): String? =
    if (amount == 0) null else "${if (amount > 0) "+" else ""}$amount$suffix $label"

/** Block stacks and loose inserts: everything that is counted rather than rolled. */
@Composable
private fun MaterialsRow(state: PlayUiState, world: World) {
    val colors = StratumTheme.colors
    val blocks = state.player.inventory.entries.filter { it.value > 0 }

    if (blocks.isEmpty() && state.heldInserts.isEmpty()) {
        Text(
            text = "Nothing gathered yet.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        return
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
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
    StratumWell(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = StratumTheme.colors.accent,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
        )
    }
}
