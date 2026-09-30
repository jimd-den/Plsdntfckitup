package com.stratum.feature.play

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.actor.SkillDefinition
import com.stratum.core.domain.sandbox.BreakdownLayer
import com.stratum.core.domain.sandbox.Contribution
import com.stratum.core.domain.sandbox.ExplainedStat
import com.stratum.core.domain.sandbox.StatBreakdown
import com.stratum.core.domain.sandbox.StatQuery

// Readouts the satchel, the hero panel and the sandbox share: a tooltip's
// lines, what a swap would change, and a number broken down by source.

/** An item's tooltip lines, each coloured for what it says. */
@Composable
internal fun TooltipLines(lines: List<TooltipLine>, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(modifier) {
        lines.forEach { line ->
            val color = when (line.tone) {
                LineTone.BASE -> colors.inkMuted
                LineTone.IMPLICIT -> colors.ink
                LineTone.AFFIX -> colors.accentAlt
                LineTone.UNIQUE -> colors.accent
                LineTone.INSERT -> colors.accentAlt
                LineTone.FLAG -> colors.danger
                LineTone.SET_HEADER -> colors.accent
                LineTone.SET_ACTIVE -> colors.ink
                LineTone.SET_WAITING -> colors.inkMuted.copy(alpha = 0.6f)
                LineTone.FLAVOUR -> colors.inkMuted
                LineTone.REQUIREMENT -> colors.danger
            }
            Text(
                line.text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontStyle = if (line.tone == LineTone.FLAVOUR) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

/** What wearing something would change: gains in the accent, losses in the danger colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeltaLine(deltas: List<StatDelta>, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    if (deltas.isEmpty()) {
        Text("No change to what you fight with", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = modifier)
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        deltas.forEach { delta ->
            Text(delta.text, style = MaterialTheme.typography.labelSmall, color = if (delta.isGain) colors.accentAlt else colors.danger)
        }
    }
}

/**
 * Pick a number to explain: every stat as a chip, then the damage type or
 * skill the stat needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatPicker(
    query: StatQuery,
    damageTypes: List<NamedChoice>,
    skills: List<SkillDefinition>,
    onPick: (StatQuery) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(Space.tight)) {
        ExplainedStat.entries.forEach { stat ->
            StratumChip(
                label = stat.label,
                selected = query.stat == stat,
                onClick = {
                    onPick(
                        StatQuery(
                            stat,
                            damageTypeId = if (stat.needsDamageType) query.damageTypeId ?: damageTypes.firstOrNull()?.id else null,
                            skillId = if (stat.needsSkill) query.skillId ?: skills.firstOrNull()?.id else null,
                        ),
                    )
                },
            )
        }
    }
    if (query.stat.needsDamageType) {
        Spacer(Modifier.height(Space.tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            damageTypes.forEach { type ->
                StratumChip(label = type.name, selected = query.damageTypeId == type.id, onClick = { onPick(query.copy(damageTypeId = type.id)) }, swatch = type.color?.let(::Color))
            }
        }
    }
    if (query.stat.needsSkill) {
        Spacer(Modifier.height(Space.tight))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            skills.forEach { skill ->
                StratumChip(label = skill.name, selected = query.skillId == skill.id, onClick = { onPick(query.copy(skillId = skill.id)) }, swatch = Color(skill.color))
            }
        }
    }
}

/**
 * A number, broken down: the formula each layer applied, with every
 * contribution under the part of the formula it went into and who made it.
 */
@Composable
internal fun BreakdownView(breakdown: StatBreakdown?, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (breakdown == null) {
            Text("Choose a number to see where it comes from.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            return
        }
        Row(Modifier.fillMaxWidth()) {
            Text(breakdown.title, style = MaterialTheme.typography.titleSmall, color = colors.ink, modifier = Modifier.weight(1f))
            Text(breakdown.display, style = MaterialTheme.typography.titleMedium, color = colors.accent, textAlign = TextAlign.End)
        }
        Text("(base + flat) × (1 + Σ increased) × Π (1 + more)", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        breakdown.layers.forEach { layer ->
            Spacer(Modifier.height(Space.small))
            LayerCard(layer)
        }
        if (breakdown.notes.isNotEmpty()) {
            Spacer(Modifier.height(Space.small))
            breakdown.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted) }
        }
    }
}

@Composable
private fun LayerCard(layer: BreakdownLayer) {
    val colors = StratumTheme.colors
    StratumWell(Modifier.fillMaxWidth()) {
        Text(layer.title, style = MaterialTheme.typography.labelMedium, color = colors.accent)
        Text(layer.formula + if (layer.result != layer.raw) " → ${formatted(layer.result)}" else "", style = MaterialTheme.typography.labelSmall, color = colors.ink)
        Part("Base", layer.base.map { "${it.kind.label}: ${it.label}" to formatted(it.value) })
        Part("Flat", layer.flat.map(::row))
        Part("Increased", layer.increased.map(::row))
        Part("More", layer.more.map(::row))
    }
}

private fun row(contribution: Contribution): Pair<String, String> =
    "${contribution.kind.label}: ${contribution.label}" to contribution.modifier.describe()

@Composable
private fun Part(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    val colors = StratumTheme.colors
    Spacer(Modifier.height(Space.tight))
    Text(title, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
    rows.forEach { (who, what) ->
        Row(Modifier.fillMaxWidth()) {
            Text(who, style = MaterialTheme.typography.labelSmall, color = colors.ink, modifier = Modifier.weight(1f))
            Text(what, style = MaterialTheme.typography.labelSmall, color = colors.accentAlt, textAlign = TextAlign.End)
        }
    }
}

private fun formatted(value: Float): String =
    if (value == value.toInt().toFloat() && kotlin.math.abs(value) < 1e9f) value.toInt().toString() else "%.3f".format(value).trimEnd('0').trimEnd('.')
