package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.difficulty.Waystone
import com.stratum.core.domain.passive.PassiveKind
import com.stratum.core.domain.passive.PassiveNode

/** What the hero panel can ask for. One object, so the play screen passes one parameter. */
data class HeroActions(
    val onClose: () -> Unit = {},
    val onSelectTab: (HeroTab) -> Unit = {},
    val onSelectNode: (String) -> Unit = {},
    val onAllocate: () -> Unit = {},
    val onRefund: () -> Unit = {},
    val onSelectSkill: (String) -> Unit = {},
    val onLinkSupport: (String) -> Unit = {},
    val onUnlinkSupport: (String, String) -> Unit = { _, _ -> },
    val onEnterTier: (Int) -> Unit = {},
    val onOpenWaystone: (String) -> Unit = {},
)

/**
 * The hero: the passive tree, the skills and their supports, and the worlds
 * this character can go to next. Full screen, because the tree needs every
 * pixel a phone has; the fight keeps running behind it, as the bag does.
 */
@Composable
fun HeroOverlay(state: PlayUiState, actions: HeroActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val panel = state.hero
    Column(modifier = modifier.fillMaxSize().background(colors.surface).safeContent().padding(Space.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            HeroTab.entries.forEach { tab -> StratumChip(label = tab.label, selected = panel.tab == tab, onClick = { actions.onSelectTab(tab) }) }
            Spacer(Modifier.weight(1f))
            StratumAction(label = "Close", onClick = actions.onClose, emphasis = ActionEmphasis.QUIET)
        }
        Spacer(Modifier.height(Space.small))
        when (panel.tab) {
            HeroTab.TREE -> TreePage(state, actions, Modifier.weight(1f))
            HeroTab.SKILLS -> SkillsPage(state, actions, Modifier.weight(1f))
            HeroTab.WORLDS -> WorldsPage(state, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TreePage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val panel = state.hero
    val tree = panel.tree
    if (tree == null) {
        Muted("This world has no passive tree.", modifier)
        return
    }
    Column(modifier) {
        Text(
            "${state.player.unspentPassivePoints} points to spend · ${state.player.passives.size} taken",
            style = MaterialTheme.typography.labelMedium,
            color = StratumTheme.colors.accent,
        )
        PassiveTreeView(
            tree = tree,
            startId = panel.startId,
            allocated = state.player.passives,
            selected = panel.selectedNode,
            path = panel.path,
            onSelect = actions.onSelectNode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        panel.selectedNode?.let(tree::node)?.let { node -> NodeCard(node, state, actions) }
            ?: Muted("Drag to look around, pinch to zoom, tap a node to see it. Tap a far one and the whole path lights up.")
    }
}

@Composable
private fun NodeCard(node: PassiveNode, state: PlayUiState, actions: HeroActions) {
    val colors = StratumTheme.colors
    val taken = node.id in state.player.passives
    val cost = state.hero.path.size
    StratumPanel(Modifier.fillMaxWidth()) {
        Text(node.name, style = MaterialTheme.typography.titleSmall, color = if (node.kind == PassiveKind.KEYSTONE) colors.danger else colors.ink)
        Text(node.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        node.modifiers.forEach { Text(it.describe(), style = MaterialTheme.typography.bodySmall, color = colors.ink) }
        if (node.description.isNotBlank()) Text(node.description, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            when {
                node.kind == PassiveKind.START -> Muted("Where you begin.")
                taken -> StratumAction(label = "Refund", onClick = actions.onRefund, emphasis = ActionEmphasis.SECONDARY)
                cost > 0 -> StratumAction(
                    label = if (cost == 1) "Take (1 point)" else "Take path ($cost points)",
                    onClick = actions.onAllocate,
                    enabled = cost <= state.player.unspentPassivePoints,
                    emphasis = ActionEmphasis.PRIMARY,
                )
                else -> Muted("Out of reach.")
            }
        }
    }
}

@Composable
private fun SkillsPage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val panel = state.hero
    val selected = panel.selectedSkill ?: state.skills.firstOrNull()?.id
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        items(state.skills, key = SkillDefinition::id) { skill ->
            SkillRow(skill, skill.id == selected, panel, actions)
        }
        item {
            Spacer(Modifier.height(Space.small))
            SectionLabel("Supports held")
            if (panel.heldSupports.isEmpty()) {
                Muted("Supports drop from monsters. Each changes one skill: harder, wider, faster or cheaper, at a price.")
            } else {
                Muted("Tap one to link it to the selected skill.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    items(panel.heldSupports, key = { it.definition.id }) { held ->
                        StratumChip(
                            label = "${held.definition.glyph} ${held.definition.name} ×${held.count}",
                            selected = false,
                            onClick = { actions.onLinkSupport(held.definition.id) },
                            swatch = Color(held.definition.color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillDefinition, selected: Boolean, panel: HeroPanelState, actions: HeroActions) {
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                Text(
                    "×%.2f power · %d cost · %.1fs · reach %d".format(skill.powerMultiplier, skill.resourceCost, skill.cooldownSeconds, skill.range),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
            StratumChip(label = if (selected) "Selected" else "Select", selected = selected, onClick = { actions.onSelectSkill(skill.id) })
        }
        val linked = panel.supportsBySkill[skill.id].orEmpty()
        if (linked.isNotEmpty()) {
            Spacer(Modifier.height(Space.tight))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items(linked, key = { it.id }) { support ->
                    StratumChip(
                        label = "${support.glyph} ${support.name} ✕",
                        selected = true,
                        onClick = { actions.onUnlinkSupport(skill.id, support.id) },
                        swatch = Color(support.color),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldsPage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val colors = StratumTheme.colors
    val panel = state.hero
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        item {
            SectionLabel("This world")
            Text("World tier ${panel.tier}", style = MaterialTheme.typography.titleSmall, color = colors.ink)
            panel.worldMods.forEach { mod -> Text(mod.name + ": " + mod.describe().replace("\n", " · "), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) }
            Spacer(Modifier.height(Space.small))
            SectionLabel("World tiers")
            Muted("Each tier is tougher and pays better. Fell a champion at your highest tier to open the next. There is no last one.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items((0..state.player.highestTier).toList()) { tier ->
                    StratumChip(label = "Tier $tier", selected = tier == panel.tier, onClick = { actions.onEnterTier(tier) })
                }
            }
            Spacer(Modifier.height(Space.small))
            SectionLabel("Waystones")
            if (state.player.waystones.isEmpty()) Muted("Waystones drop from elites and champions: a harder world, with mods that raise the stakes and the rewards.")
        }
        items(state.player.waystones, key = Waystone::id) { waystone -> WaystoneRow(waystone, actions) }
    }
}

@Composable
private fun WaystoneRow(waystone: Waystone, actions: HeroActions) {
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(waystone.name, style = MaterialTheme.typography.titleSmall, color = colors.accent)
                waystone.mods.forEach { mod -> Text(mod.name + ": " + mod.describe().replace("\n", " · "), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) }
            }
            StratumAction(label = "Open", onClick = { actions.onOpenWaystone(waystone.id) }, emphasis = ActionEmphasis.PRIMARY)
        }
    }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = Space.tight)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
    }
}
