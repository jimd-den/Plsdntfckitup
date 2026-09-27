package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumProgressSliver
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.actor.Progression
import com.stratum.core.domain.item.ItemRarity
import com.stratum.core.domain.item.ItemSlot
import com.stratum.core.domain.sandbox.DummySpec
import com.stratum.core.domain.sandbox.MeterReport
import com.stratum.core.domain.sandbox.StatQuery
import kotlin.math.roundToInt

/** What the sandbox panel can ask for. One object, so the play screen passes one parameter. */
data class SandboxActions(
    val onToggle: () -> Unit = {},
    val onSelectTab: (SandboxTab) -> Unit = {},
    val onFilterSlot: (ItemSlot?) -> Unit = {},
    val onItemLevel: (Int) -> Unit = {},
    val onRarity: (ItemRarity) -> Unit = {},
    val onSpawnBase: (String) -> Unit = {},
    val onSpawnUnique: (String) -> Unit = {},
    val onLevel: (Int) -> Unit = {},
    val onRespec: () -> Unit = {},
    val onGrantCurrency: (String) -> Unit = {},
    val onGrantSupport: (String) -> Unit = {},
    val onGrantEverything: () -> Unit = {},
    val onToggleCaps: () -> Unit = {},
    val onDummy: (DummySpec) -> Unit = {},
    val onSpawnDummy: () -> Unit = {},
    val onHealDummies: () -> Unit = {},
    val onClearDummies: () -> Unit = {},
    val onSpawnMonster: (String) -> Unit = {},
    val onResetMeter: () -> Unit = {},
    val onExplain: (StatQuery) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: (String) -> Unit = {},
)

/**
 * The build sandbox: conjure gear, reshape the hero, stand up targets, and
 * read what the build does -- the meter while it fights, the breakdown of any
 * number, and a code to share it. Full screen like the hero panel, and the
 * world keeps running behind it, so a dummy keeps taking a trigger build's
 * damage while the player reads the meter.
 */
@Composable
fun SandboxOverlay(state: PlayUiState, actions: SandboxActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    val panel = state.sandbox
    Column(modifier.fillMaxSize().background(colors.surface.copy(alpha = 0.94f)).safeContent().padding(Space.medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Sandbox")
            Spacer(Modifier.weight(1f))
            StratumAction(
                label = if (panel.capsLifted) "Caps: lifted" else "Caps: bounded",
                onClick = actions.onToggleCaps,
                emphasis = if (panel.capsLifted) ActionEmphasis.PRIMARY else ActionEmphasis.QUIET,
            )
            Spacer(Modifier.padding(Space.tight))
            StratumAction(label = "Close", onClick = actions.onToggle, emphasis = ActionEmphasis.QUIET)
        }
        Spacer(Modifier.height(Space.small))
        ChipRow {
            SandboxTab.entries.forEach { tab -> StratumChip(label = tab.label, selected = panel.tab == tab, onClick = { actions.onSelectTab(tab) }) }
        }
        Spacer(Modifier.height(Space.small))
        Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = PANEL_MAX_WIDTH).verticalScroll(rememberScrollState())) {
            when (panel.tab) {
                SandboxTab.ITEMS -> ItemsPage(panel, actions)
                SandboxTab.HERO -> HeroPage(state, actions)
                SandboxTab.TARGETS -> TargetsPage(panel, actions)
                SandboxTab.METER -> MeterPage(panel, actions)
                SandboxTab.BREAKDOWN -> {
                    StatPicker(panel.query, panel.damageTypes, state.skills, actions.onExplain)
                    Spacer(Modifier.height(Space.medium))
                    BreakdownView(panel.breakdown)
                }
                SandboxTab.BUILD -> BuildPage(panel, actions)
            }
        }
    }
}

@Composable
private fun ItemsPage(panel: SandboxPanelState, actions: SandboxActions) {
    Label("Where it is worn")
    ChipRow {
        StratumChip(label = "All", selected = panel.slotFilter == null, onClick = { actions.onFilterSlot(null) })
        ItemSlot.entries.forEach { slot -> StratumChip(label = "${slot.glyph} ${slot.name.lowercase()}", selected = panel.slotFilter == slot, onClick = { actions.onFilterSlot(slot) }) }
    }
    Label("Item level ${panel.itemLevel}")
    ChipRow { ITEM_LEVELS.forEach { level -> StratumChip(label = "$level", selected = panel.itemLevel == level, onClick = { actions.onItemLevel(level) }) } }
    Label("Rarity")
    ChipRow { ItemRarity.ordered.forEach { rarity -> StratumChip(label = rarity.name.lowercase(), selected = panel.rarity == rarity, onClick = { actions.onRarity(rarity) }) } }
    Spacer(Modifier.height(Space.small))
    SectionLabel("Uniques and set pieces")
    if (panel.uniques.isEmpty()) Muted("The loaded packs have none here.")
    panel.uniques.forEach { choice -> ChoiceRow(choice, "Make") { actions.onSpawnUnique(choice.id) } }
    Spacer(Modifier.height(Space.small))
    SectionLabel("Bases, rolled at ${panel.rarity.name.lowercase()}")
    panel.bases.forEach { choice -> ChoiceRow(choice, "Make") { actions.onSpawnBase(choice.id) } }
}

@Composable
private fun HeroPage(state: PlayUiState, actions: SandboxActions) {
    val panel = state.sandbox
    Label("Level ${state.player.level} · ${state.player.unspentPassivePoints} points unspent")
    ChipRow { HERO_LEVELS.forEach { level -> StratumChip(label = "$level", selected = state.player.level == level, onClick = { actions.onLevel(level) }) } }
    Spacer(Modifier.height(Space.small))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(label = "Respec all", onClick = actions.onRespec, emphasis = ActionEmphasis.SECONDARY)
        StratumAction(label = "Grant everything", onClick = actions.onGrantEverything, emphasis = ActionEmphasis.SECONDARY)
    }
    Spacer(Modifier.height(Space.small))
    SectionLabel("Currency")
    Muted("Tap for a stack of ${com.stratum.engine.world.SandboxTools.GRANT}.")
    ChipRow {
        panel.currencies.forEach { choice ->
            StratumChip(label = "${choice.name} ${choice.detail}", selected = false, onClick = { actions.onGrantCurrency(choice.id) }, swatch = choice.color?.let(::Color))
        }
    }
    Spacer(Modifier.height(Space.small))
    SectionLabel("Support gems")
    Muted("Tap for one; link it on the hero's skills page.")
    ChipRow {
        panel.supports.forEach { choice ->
            StratumChip(label = "${choice.name} ${choice.detail}", selected = false, onClick = { actions.onGrantSupport(choice.id) }, swatch = choice.color?.let(::Color))
        }
    }
}

@Composable
private fun TargetsPage(panel: SandboxPanelState, actions: SandboxActions) {
    val spec = panel.dummy
    SectionLabel("Training dummy")
    Muted("Stands where it is put, never swings, gives nothing. ${spec.summary}")
    Label("Life")
    ChipRow { DummyDials.life.forEach { (label, value) -> StratumChip(label = label, selected = spec.life == value, onClick = { actions.onDummy(spec.copy(life = value)) }) } }
    Label("Armour")
    ChipRow { DummyDials.armour.forEach { (label, value) -> StratumChip(label = label, selected = spec.armour == value, onClick = { actions.onDummy(spec.copy(armour = value)) }) } }
    Label("Evasion")
    ChipRow { DummyDials.evasion.forEach { (label, value) -> StratumChip(label = label, selected = spec.evasion == value, onClick = { actions.onDummy(spec.copy(evasion = value)) }) } }
    Label("Block")
    ChipRow { DummyDials.block.forEach { (label, value) -> StratumChip(label = label, selected = spec.blockChance == value, onClick = { actions.onDummy(spec.copy(blockChance = value)) }) } }
    Label("Resistance to everything")
    ChipRow { DummyDials.resistance.forEach { (label, value) -> StratumChip(label = label, selected = spec.resistance == value, onClick = { actions.onDummy(spec.copy(resistance = value)) }) } }
    Spacer(Modifier.height(Space.small))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(label = "Stand one up", onClick = actions.onSpawnDummy, emphasis = ActionEmphasis.PRIMARY)
        StratumAction(label = "Heal (${panel.dummies})", onClick = actions.onHealDummies, emphasis = ActionEmphasis.SECONDARY, enabled = panel.dummies > 0)
        StratumAction(label = "Clear", onClick = actions.onClearDummies, emphasis = ActionEmphasis.QUIET, enabled = panel.dummies > 0)
    }
    Spacer(Modifier.height(Space.medium))
    SectionLabel("Monsters and bosses")
    Muted("Called in front of you, and they fight back.")
    panel.monsters.forEach { choice -> ChoiceRow(choice, "Call") { actions.onSpawnMonster(choice.id) } }
}

@Composable
private fun MeterPage(panel: SandboxPanelState, actions: SandboxActions) {
    val colors = StratumTheme.colors
    val report = panel.meter
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        Figure("DPS (10s)", whole(report.windowDps), Modifier.weight(1f))
        Figure("Average", whole(report.averageDps), Modifier.weight(1f))
        Figure("Total", whole(report.total), Modifier.weight(1f))
    }
    Spacer(Modifier.height(Space.small))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        Figure("Biggest hit", whole(report.biggestHit), Modifier.weight(1f), report.biggestSource?.name)
        Figure("Crit rate", "${(report.critRate * 100).roundToInt()}%", Modifier.weight(1f), "${report.hits} hits")
        Figure("Missed", "${report.evaded + report.blocked}", Modifier.weight(1f), "${report.evaded} evaded · ${report.blocked} blocked")
    }
    Spacer(Modifier.height(Space.small))
    StratumAction(label = "Reset", onClick = actions.onResetMeter, emphasis = ActionEmphasis.SECONDARY)
    if (report.isEmpty) {
        Spacer(Modifier.height(Space.small))
        Muted("Hit something -- a dummy from Targets is best -- and the meter counts every hit, trigger and burn.")
        return
    }
    Spacer(Modifier.height(Space.medium))
    SectionLabel("By source")
    report.bySource.forEach { share ->
        Share(
            "${share.source.name} · ${share.source.kind.label.lowercase()}",
            "${whole(share.total)} · ${(share.share * 100).roundToInt()}%" + if (share.hits > 0) " · ${share.hits} hits, ${(share.critRate * 100).roundToInt()}% crit" else "",
            share.share, colors.accent,
        )
    }
    Spacer(Modifier.height(Space.medium))
    SectionLabel("By damage type")
    report.byType.forEach { share ->
        val type = panel.damageTypes.firstOrNull { it.id == share.damageTypeId }
        Share(panel.damageTypeName(share.damageTypeId), "${whole(share.total)} · ${(share.share * 100).roundToInt()}%", share.share, type?.color?.let(::Color) ?: colors.accentAlt)
    }
}

@Composable
private fun BuildPage(panel: SandboxPanelState, actions: SandboxActions) {
    val clipboard = LocalClipboardManager.current
    var pasted by remember { mutableStateOf("") }
    SectionLabel("Share this build")
    Muted("Class, level, passives, what is worn and what is linked -- as one line to paste anywhere.")
    Spacer(Modifier.height(Space.small))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(label = "Make code", onClick = actions.onExport, emphasis = ActionEmphasis.PRIMARY)
        panel.exported?.let { code -> StratumAction(label = "Copy", onClick = { clipboard.setText(AnnotatedString(code)) }, emphasis = ActionEmphasis.SECONDARY) }
    }
    panel.exported?.let { code ->
        Spacer(Modifier.height(Space.small))
        StratumWell(Modifier.fillMaxWidth()) {
            SelectionContainer { Text(code, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.ink) }
        }
    }
    Spacer(Modifier.height(Space.medium))
    SectionLabel("Try on a build")
    Muted("Paste a code or the build's JSON. It arrives in a new sandbox world, since a class is chosen when a world is made.")
    Spacer(Modifier.height(Space.small))
    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Build code") }, maxLines = 4)
    Spacer(Modifier.height(Space.small))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(label = "Paste", onClick = { pasted = clipboard.getText()?.text.orEmpty() }, emphasis = ActionEmphasis.QUIET)
        StratumAction(label = "Try it on", onClick = { actions.onImport(pasted) }, emphasis = ActionEmphasis.PRIMARY, enabled = pasted.isNotBlank())
    }
}

/** The meter's number as the HUD shows it, when the sandbox has counted something. */
@Composable
internal fun MeterChip(report: MeterReport, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (report.isEmpty) return
    StratumPanel(modifier.clickable(onClick = onClick), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.small, vertical = Space.tight)) {
        Text("DPS ${whole(report.windowDps)}", style = MaterialTheme.typography.labelMedium, color = StratumTheme.colors.accent)
        Text("max ${whole(report.biggestHit)} · ${(report.critRate * 100).roundToInt()}% crit", style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
    }
}

@Composable
private fun ChoiceRow(choice: NamedChoice, action: String, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.hair), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.bodySmall, color = choice.color?.let(::Color) ?: colors.ink)
            if (choice.detail.isNotBlank()) Text(choice.detail, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
        StratumAction(label = action, onClick = onClick, emphasis = ActionEmphasis.QUIET)
    }
}

@Composable
private fun Share(label: String, value: String, share: Float, tint: Color) {
    val colors = StratumTheme.colors
    Spacer(Modifier.height(Space.tight))
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, textAlign = TextAlign.End)
    }
    StratumProgressSliver(fraction = share, tint = tint)
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    StratumWell(modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted, maxLines = 1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.tight)) { content() }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(Space.small))
    Text(text, style = MaterialTheme.typography.labelSmall, color = StratumTheme.colors.inkMuted)
    Spacer(Modifier.height(Space.tight))
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
}

/** Big numbers the way a meter shows them: 1234, 12.3k, 4.5m, 1.2b. */
internal fun whole(value: Float): String = when {
    value >= 1e9f -> "%.1fb".format(value / 1e9f)
    value >= 1e6f -> "%.1fm".format(value / 1e6f)
    value >= 1e4f -> "%.1fk".format(value / 1e3f)
    else -> value.roundToInt().toString()
}

private val ITEM_LEVELS = listOf(1, 10, 20, 40, 60, 80, 100)
private val HERO_LEVELS = listOf(1, 10, 20, 30, 40, 50, Progression.MAX_LEVEL)
