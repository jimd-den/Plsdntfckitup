package com.stratum.feature.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
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
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.strategy.Affordability
import com.stratum.core.domain.strategy.FollowerOrder
import com.stratum.core.domain.strategy.Outpost
import com.stratum.core.domain.strategy.ResourceDefinition
import com.stratum.core.domain.strategy.StructureDefinition
import com.stratum.core.domain.strategy.UnitDefinition

/** A structure or unit, and whether the outpost can have it now. */
data class RealmOption<T>(val definition: T, val affordability: Affordability, val owned: Int = 0)

/** Everything the realm panel shows. Options are only worked out while it is open. */
data class RealmPanel(
    val active: Boolean = false,
    val here: Outpost? = null,
    val outposts: List<Outpost> = emptyList(),
    val resources: List<ResourceDefinition> = emptyList(),
    val netPerMinute: Map<String, Float> = emptyMap(),
    val population: Int = 0,
    val workers: Int = 0,
    val defense: Int = 0,
    val structures: List<RealmOption<StructureDefinition>> = emptyList(),
    val units: List<RealmOption<UnitDefinition>> = emptyList(),
    val followers: Int = 0,
    val order: FollowerOrder = FollowerOrder.FOLLOW,
)

/** What the realm panel can ask for. */
data class RealmActions(
    val onToggle: () -> Unit = {},
    val onFound: () -> Unit = {},
    val onDeposit: () -> Unit = {},
    val onBuild: (String) -> Unit = {},
    val onRecruit: (String) -> Unit = {},
    val onMuster: () -> Unit = {},
    val onOrder: (FollowerOrder) -> Unit = {},
)

/**
 * The realm: the outpost the player stands in -- its stock, what it makes,
 * what it can build and who it can train -- and the followers in the field.
 * Out in the wilds, the button to plant a new one.
 */
@Composable
fun RealmOverlay(panel: RealmPanel, actions: RealmActions, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Box(modifier.fillMaxSize().background(colors.surface.copy(alpha = 0.9f)).clickable(onClick = actions.onToggle), contentAlignment = Alignment.Center) {
        StratumPanel(
            modifier = Modifier.safeContent().fillMaxWidth(0.94f).widthIn(max = PANEL_MAX_WIDTH).verticalScroll(rememberScrollState()).clickable(enabled = false) {},
            shape = Cut.large,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(panel.here?.name ?: "Realm")
                Spacer(Modifier.weight(1f))
                StratumAction(label = "Close", onClick = actions.onToggle, emphasis = ActionEmphasis.QUIET)
            }
            val here = panel.here
            if (here == null) Wilds(panel, actions) else Holding(here, panel, actions)
            Spacer(Modifier.height(Space.medium))
            Followers(panel, actions)
        }
    }
}

@Composable
private fun Wilds(panel: RealmPanel, actions: RealmActions) {
    val colors = StratumTheme.colors
    Text(
        "Plant an outpost here: it gathers what you bring, builds farms and forges, trains soldiers, and will be raided.",
        style = MaterialTheme.typography.bodySmall, color = colors.inkMuted,
    )
    Spacer(Modifier.height(Space.small))
    StratumAction(label = "Found an outpost (${com.stratum.core.domain.strategy.StandardStrategy.FOUNDING_BLOCKS} blocks)", onClick = actions.onFound, emphasis = ActionEmphasis.PRIMARY)
    if (panel.outposts.isNotEmpty()) {
        Spacer(Modifier.height(Space.medium))
        SectionLabel("Your outposts")
        panel.outposts.forEach { outpost ->
            Text("${outpost.name} · ${outpost.structures.values.sum()} buildings · ${outpost.garrison.values.sum()} soldiers", style = MaterialTheme.typography.bodySmall, color = colors.ink)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Holding(here: Outpost, panel: RealmPanel, actions: RealmActions) {
    val colors = StratumTheme.colors
    Text(
        "${panel.population} people · ${panel.workers} jobs · 🛡 ${panel.defense}" +
            if (here.raidIn >= 0f) " · next raid in ${(here.raidIn / 60).toInt()}:${"%02d".format((here.raidIn % 60).toInt())}" else "",
        style = MaterialTheme.typography.bodySmall,
        color = colors.inkMuted,
    )
    Spacer(Modifier.height(Space.small))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
        panel.resources.forEach { resource ->
            val net = panel.netPerMinute[resource.id] ?: 0f
            StratumChip(
                label = "${resource.glyph} ${here.has(resource.id).toInt()}" + if (net != 0f) " (${if (net > 0) "+" else ""}${"%.1f".format(net)}/m)" else "",
                selected = false,
                onClick = {},
                swatch = Color(resource.color),
            )
        }
    }
    Spacer(Modifier.height(Space.small))
    StratumAction(label = "Deposit what you carry", onClick = actions.onDeposit, emphasis = ActionEmphasis.SECONDARY)

    Spacer(Modifier.height(Space.medium))
    SectionLabel("Build")
    panel.structures.forEach { option ->
        OptionRow(
            title = "${option.definition.glyph} ${option.definition.name}" + if (option.owned > 0) " ×${option.owned}" else "",
            detail = option.definition.description.ifBlank { null },
            cost = option.definition.cost,
            affordability = option.affordability,
            action = "Build",
            onClick = { actions.onBuild(option.definition.id) },
            panel = panel,
        )
    }
    if (panel.units.isNotEmpty()) {
        Spacer(Modifier.height(Space.medium))
        SectionLabel("Train")
        panel.units.forEach { option ->
            OptionRow(
                title = option.definition.name + if (option.owned > 0) " ×${option.owned} in garrison" else "",
                detail = null,
                cost = option.definition.cost,
                affordability = option.affordability,
                action = "Train",
                onClick = { actions.onRecruit(option.definition.id) },
                panel = panel,
            )
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    detail: String?,
    cost: Map<String, Int>,
    affordability: Affordability,
    action: String,
    onClick: () -> Unit,
    panel: RealmPanel,
) {
    val colors = StratumTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.hair), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted) }
            Text(
                cost.entries.joinToString(" ") { (id, n) -> "${panel.resources.firstOrNull { it.id == id }?.glyph ?: id} $n" } + reason(affordability),
                style = MaterialTheme.typography.labelSmall,
                color = if (affordability == Affordability.Ok) colors.inkMuted else colors.danger,
            )
        }
        StratumAction(label = action, onClick = onClick, enabled = affordability == Affordability.Ok, emphasis = ActionEmphasis.SECONDARY)
    }
}

private fun reason(affordability: Affordability): String = when (affordability) {
    Affordability.Ok -> ""
    is Affordability.Missing -> " · short"
    is Affordability.Requires -> " · needs ${affordability.structureId.substringAfter(':').replace('_', ' ')}"
    Affordability.AtLimit -> " · at its limit"
    Affordability.Unknown -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Followers(panel: RealmPanel, actions: RealmActions) {
    val colors = StratumTheme.colors
    SectionLabel("Followers")
    Text(
        if (panel.followers == 0) "Nobody follows you. Train soldiers at an outpost, then muster them." else "${panel.followers} follow you.",
        style = MaterialTheme.typography.bodySmall, color = colors.inkMuted,
    )
    if (panel.here != null && panel.here.garrison.values.sum() > 0) {
        Spacer(Modifier.height(Space.small))
        StratumAction(label = "Muster the garrison", onClick = actions.onMuster, emphasis = ActionEmphasis.PRIMARY)
    }
    if (panel.followers > 0) {
        Spacer(Modifier.height(Space.small))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            FollowerOrder.entries.forEach { order ->
                StratumChip(label = order.label, selected = order == panel.order, onClick = { actions.onOrder(order) })
            }
        }
    }
}
