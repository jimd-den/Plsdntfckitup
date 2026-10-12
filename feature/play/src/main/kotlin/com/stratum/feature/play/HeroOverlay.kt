package com.stratum.feature.play

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.stratum.core.designsystem.component.LookRow
import com.stratum.core.designsystem.component.RoundIconButton
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
    val onExplain: (com.stratum.core.domain.sandbox.StatQuery) -> Unit = {},
)

/**
 * The hero: the passive tree, the skills and their supports, and the worlds
 * this character can go to next. Full screen, because the tree needs every
 * pixel a phone has; the fight keeps running behind it, as the bag does.
 */
@Composable
fun HeroOverlay(state: PlayUiState, actions: HeroActions, modifier: Modifier = Modifier, looks: HeroLooks = HeroLooks()) {
    val colors = StratumTheme.colors
    val panel = state.hero
    Column(modifier = modifier.fillMaxSize().background(colors.surface).safeContent().padding(Space.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            // The tabs scroll sideways on a narrow phone rather than squeezing Close.
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                HeroTab.entries.forEach { tab -> StratumChip(label = tab.label, selected = panel.tab == tab, onClick = { actions.onSelectTab(tab) }) }
            }
            // A round ✕, as the pause menu closes, so five tabs fit a phone held upright.
            RoundIconButton(glyph = "✕", description = "Close", onClick = actions.onClose)
        }
        Spacer(Modifier.height(Space.small))
        when (panel.tab) {
            HeroTab.TREE -> TreePage(state, actions, Modifier.weight(1f))
            HeroTab.SKILLS -> SkillsPage(state, actions, Modifier.weight(1f))
            HeroTab.STATS -> StatsPage(state, actions, Modifier.weight(1f))
            HeroTab.WORLDS -> WorldsPage(state, actions, Modifier.weight(1f))
            HeroTab.LOOK -> LookPage(looks, Modifier.weight(1f))
        }
    }
}

/**
 * How the hero looks, changed mid-fight: the class's own art first, then every
 * look the player drew or imported. The world behind redraws them at once.
 */
@Composable
private fun LookPage(looks: HeroLooks, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        SectionLabel("Wearing")
        Text(
            looks.looks.firstOrNull { it.id == looks.wornId }?.name ?: "Class art",
            style = MaterialTheme.typography.titleMedium,
            color = StratumTheme.colors.accent,
        )
        LookRow(looks.looks, looks.wornId, looks.onPick, Modifier.fillMaxWidth())
        if (looks.looks.isEmpty()) {
            Muted("Draw your hero in the sprite forge and they will stand here to be worn, in this world and every other.")
        } else {
            Muted("Your look goes with you: every world you enter shows the one you wear.")
        }
        looks.onMake?.let { make ->
            StratumAction(label = "Make a look", onClick = make, emphasis = ActionEmphasis.SECONDARY)
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
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight) {
            // Landscape: the tree gets the width, the card sits beside it.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                PassiveTreeView(
                    tree = tree,
                    startId = panel.startId,
                    allocated = state.player.passives,
                    selected = panel.selectedNode,
                    path = panel.path,
                    onSelect = actions.onSelectNode,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
                Column(Modifier.width(SIDE_CARD).verticalScroll(rememberScrollState())) {
                    PointsLine(state)
                    panel.selectedNode?.let(tree::node)?.let { node -> NodeCard(node, state, actions) } ?: Muted(TREE_HINT)
                }
            }
        } else {
            TreeColumn(state, actions, tree)
        }
    }
}

@Composable
private fun PointsLine(state: PlayUiState) {
    Text(
        "${state.player.unspentPassivePoints} points to spend · ${state.player.passives.size} taken",
        style = MaterialTheme.typography.labelMedium,
        color = StratumTheme.colors.accent,
    )
}

@Composable
private fun TreeColumn(state: PlayUiState, actions: HeroActions, tree: com.stratum.core.domain.passive.PassiveTree) {
    val panel = state.hero
    Column(Modifier.fillMaxSize()) {
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
            ?: Muted(TREE_HINT)
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

/**
 * The skills and their links: each skill's cost, timing and tags, the
 * supports linked to it and what each does, and -- for the selected skill --
 * every held support, the ones that fit first and the rest saying what they
 * would need.
 */
@Composable
private fun SkillsPage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val panel = state.hero
    val selected = state.skills.firstOrNull { it.id == panel.selectedSkill } ?: state.skills.firstOrNull()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        items(state.skills, key = SkillDefinition::id) { skill ->
            SkillRow(skill, skill.id == selected?.id, state, actions)
        }
        item {
            Spacer(Modifier.height(Space.small))
            SectionLabel(selected?.let { "Supports for ${it.name}" } ?: "Supports held")
            if (panel.heldSupports.isEmpty()) {
                Muted("Supports drop from monsters. Each changes one skill: harder, wider, faster or cheaper, at a price.")
            } else if (selected != null) {
                Muted("Tap one that fits to link it. A skill holds ${com.stratum.core.domain.crafting.StandardCrafting.MAX_SUPPORTS_PER_SKILL}.")
            }
        }
        if (selected != null) {
            items(SkillFacts.options(selected, panel.heldSupports), key = { "held:" + it.support.id }) { option ->
                SupportOptionRow(option, actions)
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillDefinition, selected: Boolean, state: PlayUiState, actions: HeroActions) {
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, color = Color(skill.color))
                Text(
                    SkillFacts.facts(skill, state.costOf(skill), state.player.resourceName).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
                Text(SkillFacts.tags(skill).joinToString(" "), style = MaterialTheme.typography.labelSmall, color = colors.accentAlt)
            }
            StratumChip(label = if (selected) "Selected" else "Select", selected = selected, onClick = { actions.onSelectSkill(skill.id) })
        }
        val linked = state.hero.supportsBySkill[skill.id].orEmpty()
        linked.forEach { support ->
            Spacer(Modifier.height(Space.tight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${support.glyph} ${support.name}", style = MaterialTheme.typography.bodySmall, color = Color(support.color))
                    Text(SkillFacts.changes(support).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                }
                StratumAction(label = "Unlink", onClick = { actions.onUnlinkSupport(skill.id, support.id) }, emphasis = ActionEmphasis.QUIET)
            }
        }
    }
}

@Composable
private fun SupportOptionRow(option: SupportOption, actions: HeroActions) {
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth(), raised = option.fits) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${option.support.glyph} ${option.support.name} ×${option.held}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (option.fits) Color(option.support.color) else colors.inkMuted,
                )
                Text(option.changes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                option.needs?.let { Text("Needs a $it skill", style = MaterialTheme.typography.labelSmall, color = colors.danger) }
            }
            StratumAction(label = "Link", onClick = { actions.onLinkSupport(option.support.id) }, emphasis = ActionEmphasis.SECONDARY, enabled = option.fits)
        }
    }
}

/** Every number the character fights with, one at a time, broken down to where each part came from. */
@Composable
private fun StatsPage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val panel = state.hero
    Column(modifier.verticalScroll(rememberScrollState())) {
        StatPicker(panel.query, panel.damageTypes, state.skills, actions.onExplain)
        Spacer(Modifier.height(Space.medium))
        BreakdownView(panel.breakdown)
    }
}

@Composable
private fun WorldsPage(state: PlayUiState, actions: HeroActions, modifier: Modifier) {
    val colors = StratumTheme.colors
    val panel = state.hero
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        item {
            SectionLabel("This world")
            val rung = com.stratum.core.domain.difficulty.TierLadder.rung(panel.tier)
            Text("World tier ${panel.tier} · ${rung.name}", style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Muted("${rung.meaning}. Monsters stand ${panel.tier * com.stratum.core.domain.difficulty.Difficulty.LEVELS_PER_TIER} levels above you here, and each blow presses ${"%.1f".format(com.stratum.core.domain.difficulty.TierLadder.pressure(panel.tier))}× as hard as on the road.")
            panel.worldMods.forEach { mod -> Text(mod.name + ": " + mod.describe().replace("\n", " · "), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) }
            Spacer(Modifier.height(Space.small))
            SectionLabel("World tiers")
            Muted("Each rung is tougher and pays better. Fell a champion at your highest to open the next. Past Chi the ladder runs on without end.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items((0..state.player.highestTier).toList()) { tier ->
                    StratumChip(label = "$tier · ${com.stratum.core.domain.difficulty.TierLadder.rung(tier).name}", selected = tier == panel.tier, onClick = { actions.onEnterTier(tier) })
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

private const val TREE_HINT = "Drag to look around, pinch to zoom, tap a node to see it. Tap a far one and the whole path lights up."
private val SIDE_CARD = 300.dp

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = Space.tight)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
    }
}
