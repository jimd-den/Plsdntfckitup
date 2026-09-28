package com.stratum.app.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stratum.app.WorldDials
import com.stratum.app.DialRow
import com.stratum.app.summaryOf
import com.stratum.app.world.NewWorldDraft
import com.stratum.app.world.NewWorldStep
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.ChoiceCard
import com.stratum.core.designsystem.component.LinkPill
import com.stratum.core.designsystem.component.LocalJobsTray
import com.stratum.core.designsystem.component.SectionHeader
import com.stratum.core.designsystem.component.StepIndicator
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.world.RulesPresets

/** A class as the hero step lists it: who, what they are, and how far the kept hero got. */
data class HeroChoice(
    val id: String,
    val name: String,
    /** "Titled Bladesman · 260 health · 14 attack". */
    val line: String,
    /** The kept hero's level, or null for a class never played. */
    val level: Int? = null,
)

/** What the new-world flow can be asked to do. */
data class NewWorldActions(
    val onBack: () -> Unit = {},
    val onChange: (NewWorldDraft) -> Unit = {},
    /** Detours to the class forge; the draft waits here for the new class. */
    val onQuickMake: () -> Unit = {},
    val onGo: () -> Unit = {},
)

/**
 * A new world in three steps: who you are, what world, go. Each step is one
 * screenful with one decision on it and a Next that is always in the same
 * place, so a player who takes every default is in a world in three taps.
 */
@Composable
fun NewWorldScreen(
    draft: NewWorldDraft,
    heroes: List<HeroChoice>,
    actions: NewWorldActions,
    modifier: Modifier = Modifier,
    /** How many worlds exist, for the default name "World N". */
    existingWorlds: Int = 0,
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
) {
    val heroId = draft.heroClassId ?: heroes.firstOrNull()?.id
    val back = { if (draft.step == NewWorldStep.HERO) actions.onBack() else actions.onChange(draft.back()) }
    StratumScreen(
        title = "New world",
        onBack = back,
        modifier = modifier,
        jobsTray = jobsTray,
        bottomBar = {
            if (draft.step != NewWorldStep.HERO) {
                StratumAction(label = "Back", onClick = back, emphasis = ActionEmphasis.QUIET)
            }
            if (draft.step == NewWorldStep.GO) {
                StratumAction(label = "Go", onClick = actions.onGo, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1f).height(GO_HEIGHT))
            } else {
                val next = NewWorldStep.entries[draft.step.ordinal + 1]
                StratumAction(
                    label = "Next: ${next.label}",
                    onClick = { actions.onChange(draft.copy(heroClassId = heroId).next()) },
                    emphasis = ActionEmphasis.PRIMARY,
                    enabled = heroId != null,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        StepIndicator(
            steps = NewWorldStep.entries.map { it.label },
            current = draft.step.ordinal,
            onStep = { actions.onChange(draft.copy(step = NewWorldStep.entries[it])) },
        )
        when (draft.step) {
            NewWorldStep.HERO -> HeroStep(heroes, heroId, onPick = { actions.onChange(draft.copy(heroClassId = it)) }, onQuickMake = actions.onQuickMake)
            NewWorldStep.WORLD -> WorldStep(draft, existingWorlds, actions.onChange)
            NewWorldStep.GO -> GoStep(draft, heroes.firstOrNull { it.id == heroId }, existingWorlds)
        }
    }
}

@Composable
private fun HeroStep(heroes: List<HeroChoice>, selected: String?, onPick: (String) -> Unit, onQuickMake: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        SectionHeader("Who are you?", actionLabel = "Quick-make a hero", onAction = onQuickMake)
        heroes.forEach { hero ->
            ChoiceCard(
                title = hero.name,
                body = hero.line,
                selected = hero.id == selected,
                onClick = { onPick(hero.id) },
                glyph = hero.name.first().uppercase(),
                tag = hero.level?.let { "Lv $it" } ?: "New",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorldStep(draft: NewWorldDraft, existingWorlds: Int, onChange: (NewWorldDraft) -> Unit) {
    var fineTune by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        SectionHeader("What kind of world?")
        RulesPresets.all.forEach { preset ->
            ChoiceCard(
                title = preset.name,
                body = preset.description,
                selected = draft.presetId == preset.id && draft.rules == preset.rules,
                onClick = { onChange(draft.choosePreset(preset)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summaryOf(draft.rules),
                style = MaterialTheme.typography.labelSmall,
                color = StratumTheme.colors.accent,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.small))
            LinkPill(if (fineTune) "Done" else "Fine-tune", onClick = { fineTune = !fineTune })
        }
        if (fineTune) {
            StratumPanel(Modifier.fillMaxWidth()) {
                WorldDials.all.forEach { dial -> DialRow(dial, draft.rules) { onChange(draft.copy(rules = it)) } }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        SectionHeader("Name it")
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("World name") },
            placeholder = { Text(draft.resolvedName(existingWorlds)) },
        )
        OutlinedTextField(
            value = draft.seedText,
            onValueChange = { onChange(draft.copy(seedText = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Seed (optional)") },
            placeholder = { Text("Blank for a surprise") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

@Composable
private fun GoStep(draft: NewWorldDraft, hero: HeroChoice?, existingWorlds: Int) {
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth()) {
        Text(draft.resolvedName(existingWorlds), style = MaterialTheme.typography.displaySmall, color = colors.ink)
        Spacer(Modifier.height(Space.medium))
        SummaryRow("Hero", hero?.let { h -> h.name + (h.level?.let { " · Lv $it" } ?: " · new") } ?: "—")
        StratumDivider()
        SummaryRow("World", draft.preset?.takeIf { it.rules == draft.rules }?.name ?: NewWorldDraft.CUSTOM)
        StratumDivider()
        SummaryRow("Seed", draft.seedText.trim().ifEmpty { "A surprise" })
        Spacer(Modifier.height(Space.medium))
        Text(summaryOf(draft.rules), style = MaterialTheme.typography.labelSmall, color = colors.accent)
    }
    Text(
        "Your hero keeps their level, gear and points in every world they enter.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.inkMuted,
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = StratumTheme.colors.inkMuted, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = StratumTheme.colors.ink)
    }
}

private val GO_HEIGHT = 56.dp
