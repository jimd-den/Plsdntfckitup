package com.stratum.feature.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.attack.AttackForge
import com.stratum.core.domain.attack.AttackNaming
import com.stratum.core.domain.attack.DeliveryKind
import com.stratum.core.domain.attack.Element
import com.stratum.core.domain.attack.EmitterShape
import com.stratum.core.domain.attack.ModulatorKind
import com.stratum.core.domain.attack.Payload
import com.stratum.core.domain.attack.PowerBudget
import com.stratum.core.domain.attack.ProceduralSkill
import com.stratum.core.domain.attack.SkillEvent

/**
 * The Forge of Will: a core glyph, catalysts and resonators slotted into an
 * attack that plays in its own look as it is made. Its name, cost and
 * cooldown are read off what is slotted, a power meter shows how near it is
 * to too much to hold, and variations one part away wait below.
 */
@Composable
fun AttackForgeScreen(
    viewModel: AttackForgeViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False in screenshots and tests: the previews hold still. */
    animate: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = StratumTheme.colors
    val attack = state.attack
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).safeContent()
            .verticalScroll(rememberScrollState()).padding(Space.large),
    ) {
        com.stratum.core.designsystem.component.StratumTopBar(title = "Forge of Will", onBack = onBack)
        Text(
            "Slot a core, catalysts and resonators — ${remember { spoken(AttackForge.attacks()) }} attacks from the named parts alone, each in a look of its own.",
            style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))

        StratumPanel(Modifier.fillMaxWidth()) {
            Stage(attack, animate, Modifier.fillMaxWidth().aspectRatio(1.9f))
            Spacer(Modifier.height(Space.small))
            Text(attack.name, style = MaterialTheme.typography.titleLarge, color = colors.ink)
            AttackNaming.describe(attack).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) }
            Spacer(Modifier.height(Space.small))
            Text(
                "${fmt(attack.energyCost)} energy · ${fmt(attack.cooldownSeconds)} s recharge · ${attack.look.summary}",
                style = MaterialTheme.typography.bodySmall, color = colors.ink,
            )
            PowerMeter(state.power, Modifier.fillMaxWidth().padding(top = Space.small))
            Spacer(Modifier.height(Space.small))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(label = "🎲 Roll", onClick = viewModel::roll, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1.2f))
                StratumAction(label = "✦ Restyle", onClick = viewModel::restyle, modifier = Modifier.weight(1.2f))
                StratumAction(label = if (state.isKept) "✓ Kept" else "Keep", onClick = viewModel::keep, enabled = state.stable && !state.isKept, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.small))
            StratumAction(
                label = if (state.isEquipped) "✓ Carried into play" else "Carry into play",
                onClick = viewModel::equip, enabled = state.stable,
                emphasis = if (state.isEquipped) ActionEmphasis.SECONDARY else ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Space.small).clickable { viewModel.dismissMessage() }) }
        }

        Spacer(Modifier.height(Space.medium))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("One part away", Modifier.weight(1f))
            StratumChip("↻ More", selected = false, onClick = viewModel::vary)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            state.variations.forEach { v -> AttackCard(v, animate, Modifier.width(168.dp)) { viewModel.pick(v) } }
        }

        Spacer(Modifier.height(Space.medium))
        StratumPanel(Modifier.fillMaxWidth()) { Slots(state, viewModel) }

        Spacer(Modifier.height(Space.medium))
        SectionLabel("Kept · ${state.kept.size}")
        if (state.kept.isEmpty()) Text("Nothing kept yet. Keep an attack to carry it into play.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            state.kept.forEach { k ->
                val carried = com.stratum.core.domain.attack.AttackCode.encode(k) in state.equipped
                AttackCard(k, animate, Modifier.width(168.dp), badge = if (carried) "Carried" else null) { viewModel.pick(k) }
            }
        }

        Spacer(Modifier.height(Space.medium))
        StratumPanel(Modifier.fillMaxWidth()) { Share(state, viewModel) }
    }
}

/** The preview on a dark stage, with the look's palette as a strip beneath. */
@Composable
private fun Stage(attack: ProceduralSkill, animate: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF14111A))) {
        AttackPreview(attack, Modifier.fillMaxSize(), animate = animate)
        Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(attack.look.primary, attack.look.secondary, attack.look.core).forEach { c ->
                Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(c)))
            }
        }
    }
}

@Composable
private fun AttackCard(attack: ProceduralSkill, animate: Boolean, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceRaised).clickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF14111A))) {
            AttackPreview(attack, Modifier.fillMaxSize(), animate = animate)
        }
        Text(attack.name, style = MaterialTheme.typography.labelMedium, color = colors.ink, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        Text(badge ?: "${fmt(attack.energyCost)} energy", style = MaterialTheme.typography.labelSmall, color = if (badge != null) colors.accent else colors.inkMuted)
    }
}

/** How close to too much: green, then gold, then red past the limit. */
@Composable
private fun PowerMeter(power: Float, modifier: Modifier) {
    val colors = StratumTheme.colors
    val share = (power / PowerBudget.LIMIT).coerceIn(0f, 1.15f)
    val tint = when {
        share > 1f -> Color(0xFFD9534F)
        share > 0.75f -> Color(0xFFE0B040)
        else -> Color(0xFF6BBF7A)
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(share.coerceAtMost(1f)).fillMaxHeight().background(tint))
        }
        Text(
            if (share > 1f) "Unstable: too much power to hold (${fmt(power)} of ${fmt(PowerBudget.LIMIT)})" else "Power ${fmt(power)} of ${fmt(PowerBudget.LIMIT)}",
            style = MaterialTheme.typography.labelSmall, color = if (share > 1f) tint else colors.inkMuted,
        )
    }
}

@Composable
private fun Slots(state: AttackForgeUiState, vm: AttackForgeViewModel) {
    val s = state.slots
    Text("Core glyph — how it travels", style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
    Chips { DeliveryKind.entries.forEach { k -> StratumChip(k.label, selected = s.core == k, onClick = { vm.core(k) }) } }

    Spacer(Modifier.height(Space.small))
    Text("Catalysts — its shape, essence and charge", style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
    Label("Shape")
    Chips { EmitterShape.entries.forEach { e -> StratumChip(e.label, selected = s.shape == e, onClick = { vm.shape(e) }) } }
    if (s.shape != EmitterShape.SINGLE) {
        Label("How many: ${s.count}")
        Chips { (1..8).forEach { n -> StratumChip("$n", selected = s.count == n, onClick = { vm.count(n) }) } }
    }
    Label("Essence")
    Chips {
        StratumChip("Its charge's own", selected = s.essence == null, onClick = { vm.essence(null) })
        Element.entries.forEach { e -> StratumChip(e.label, selected = s.essence == e, onClick = { vm.essence(e) }) }
    }
    Label("Charge")
    Chips { Payload.entries.forEach { p -> StratumChip(p.label, selected = s.charge == p, onClick = { vm.charge(p) }) } }
    if (s.charge != null) {
        Label("Second charge")
        Chips {
            StratumChip("None", selected = s.secondCharge == null, onClick = { vm.secondCharge(null) })
            Payload.entries.filter { it != s.charge }.forEach { p -> StratumChip(p.label, selected = s.secondCharge == p, onClick = { vm.secondCharge(p) }) }
        }
    }

    Spacer(Modifier.height(Space.small))
    Text("Resonators — up to three that bend it, two that chain it", style = MaterialTheme.typography.titleSmall, color = StratumTheme.colors.ink)
    Label("Modulators · ${s.modulators.size} of 3")
    Chips { ModulatorKind.entries.forEach { m -> StratumChip(m.label, selected = m in s.modulators, onClick = { vm.toggleModulator(m) }) } }
    Label("Chains · ${s.chains.size} of 2")
    Chips { SkillEvent.entries.forEach { e -> StratumChip(s.chains[e]?.let { "${e.label}: ${it.name}" } ?: e.label, selected = e in s.chains, onClick = { vm.toggleChain(e) }) } }
}

@Composable
private fun Share(state: AttackForgeUiState, vm: AttackForgeViewModel) {
    val colors = StratumTheme.colors
    var pasted by remember { mutableStateOf("") }
    Text("Share", style = MaterialTheme.typography.titleSmall, color = colors.ink)
    Text("An attack's code is the whole attack: paste it on any device to forge the same one.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    SelectionContainer { Text(state.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.ink, modifier = Modifier.padding(vertical = Space.small)) }
    OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Paste a code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Space.small))
    StratumAction(label = "Open on the anvil", onClick = { if (vm.openCode(pasted)) pasted = "" }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth())
    if (state.isKept) {
        Spacer(Modifier.height(Space.small))
        StratumAction(label = "Forget this attack", onClick = { vm.forget(state.attack) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Label(text: String) = Text(text, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private fun fmt(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
