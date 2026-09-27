package com.stratum.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.world.RulesPresets
import com.stratum.core.domain.world.SurvivalMode
import com.stratum.core.domain.world.WorldRules

/** One stop on a dial: what it is called, and the value it sets. */
internal data class Step<T>(val label: String, val value: T)

/**
 * A rule as the player sees it: a name, a handful of steps, and how to read
 * and write it on [WorldRules]. Steps rather than sliders, because a thumb
 * picks "Many" far more surely than 1.8.
 */
internal data class Dial<T>(
    val name: String,
    val steps: List<Step<T>>,
    val read: (WorldRules) -> T,
    val write: (WorldRules, T) -> WorldRules,
) {
    /** The step nearest the current value, so a pack's 1.3 still lights one up. */
    fun selected(rules: WorldRules): Step<T> {
        val current = read(rules)
        return steps.firstOrNull { it.value == current }
            ?: (current as? Float)?.let { f -> steps.minBy { kotlin.math.abs((it.value as Float) - f) } }
            ?: steps.first()
    }
}

internal object WorldDials {
    val survival = Dial(
        "Survival",
        SurvivalMode.entries.map { Step(it.label, it) },
        { it.survival },
        { r, v -> r.copy(survival = v) },
    )
    val towns = Dial(
        "Towns",
        listOf(Step("None", 0f), Step("Few", 0.5f), Step("Normal", 1f), Step("Many", 1.8f)),
        { it.townDensity },
        { r, v -> r.copy(townDensity = v) },
    )
    val monsters = Dial(
        "Monsters",
        listOf(Step("Calm", 0.6f), Step("Normal", 1f), Step("Swarming", 1.6f)),
        { it.monsterDensity },
        { r, v -> r.copy(monsterDensity = v) },
    )
    val day = Dial(
        "Day length",
        listOf(Step("10 min", 10f), Step("20 min", 20f), Step("40 min", 40f)),
        { it.dayLengthMinutes },
        { r, v -> r.copy(dayLengthMinutes = v) },
    )
    val death = Dial(
        "On death",
        listOf(Step("Keep all", 0f), Step("Lose some", 0.25f), Step("Lose much", 0.6f)),
        { it.deathPenalty },
        { r, v -> r.copy(deathPenalty = v) },
    )
    val raids = Dial("Raids", listOf(Step("Off", false), Step("On", true)), { it.raids }, { r, v -> r.copy(raids = v) })
    val start = Dial("Start", listOf(Step("In a town", true), Step("In the wilds", false)), { it.startInTown }, { r, v -> r.copy(startInTown = v) })

    val all: List<Dial<*>> = listOf(survival, towns, monsters, raids, day, start, death)
}

/** The preset these rules are exactly, or null when the player has turned the dials. */
internal fun presetOf(rules: WorldRules) = RulesPresets.all.firstOrNull { it.rules == rules }

/** A one-line reading of the rules, for the collapsed card. */
internal fun summaryOf(rules: WorldRules): String =
    listOf(WorldDials.survival, WorldDials.towns, WorldDials.monsters).joinToString(" · ") { "${it.name} ${it.selected(rules).label.lowercase()}" } +
        if (rules.raids) " · raids" else ""

/**
 * How the next world plays: a preset in one tap, or every dial for the
 * player who wants it. The pack's own suggestion is where it starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorldSetupCard(
    rules: WorldRules,
    suggested: WorldRules,
    onRulesChange: (WorldRules) -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    val colors = StratumTheme.colors
    var expanded by remember { mutableStateOf(startExpanded) }
    val preset = presetOf(rules)
    StratumPanel(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Your world")
            Spacer(Modifier.weight(1f))
            StratumAction(label = if (expanded) "Done" else "Customise", onClick = { expanded = !expanded }, emphasis = ActionEmphasis.QUIET)
        }
        Text(
            (preset?.description ?: "Custom rules.") + if (rules == suggested && preset == null) " Suggested by your packs." else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.small))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            RulesPresets.all.forEach { option ->
                StratumChip(label = option.name, selected = option == preset, onClick = { onRulesChange(option.rules) })
            }
            if (suggested != WorldRules() && presetOf(suggested) == null) {
                StratumChip(label = "Pack's own", selected = rules == suggested, onClick = { onRulesChange(suggested) })
            }
        }
        if (expanded) {
            Spacer(Modifier.height(Space.medium))
            WorldDials.all.forEach { dial -> DialRow(dial, rules, onRulesChange) }
        } else {
            Spacer(Modifier.height(Space.small))
            Text(summaryOf(rules), style = MaterialTheme.typography.labelSmall, color = colors.accent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> DialRow(dial: Dial<T>, rules: WorldRules, onRulesChange: (WorldRules) -> Unit) {
    val colors = StratumTheme.colors
    val selected = dial.selected(rules)
    Column(Modifier.fillMaxWidth()) {
        Text(dial.name, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(Space.tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.small)) {
            dial.steps.forEach { step ->
                StratumChip(label = step.label, selected = step == selected, onClick = { onRulesChange(dial.write(rules, step.value)) })
            }
        }
        Spacer(Modifier.height(Space.small))
    }
}
