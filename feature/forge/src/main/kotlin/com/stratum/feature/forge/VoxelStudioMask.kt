package com.stratum.feature.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.engine.model.MicroModelRenderer
import com.stratum.engine.model.mask.BeardForm
import com.stratum.engine.model.mask.CrestForm
import com.stratum.engine.model.mask.EarForm
import com.stratum.engine.model.mask.EyeForm
import com.stratum.engine.model.mask.FaceShape
import com.stratum.engine.model.mask.MaskGenome
import com.stratum.engine.model.mask.MaskPalettes
import com.stratum.engine.model.mask.MaskTradition
import com.stratum.engine.model.mask.MouthForm
import com.stratum.engine.model.mask.NoseForm
import kotlin.math.roundToInt

/**
 * "Start from a mask": the preset masks as chips, a tradition to roll in,
 * 🎲 Randomize and "Another like this". Masks are the studio's headline
 * starting point -- the models every player begins with.
 */
@Composable
internal fun MaskStartSection(state: VoxelStudioUiState, viewModel: VoxelStudioViewModel) {
    val colors = StratumTheme.colors
    Text("An Igbo mask — pick one, roll one, then tune its dials", color = colors.ink, style = MaterialTheme.typography.labelLarge)
    ChipRow {
        MaskGenome.presets.forEach { g ->
            StratumChip(g.name, selected = state.mask?.let { it.copy(name = g.name) == g } == true, onClick = { viewModel.startMask(g) })
        }
    }
    ChipRow {
        StratumChip("Any tradition", selected = state.maskTradition == null, onClick = { viewModel.setMaskTradition(null) })
        MaskTradition.entries.forEach { t ->
            StratumChip(t.label, selected = state.maskTradition == t, onClick = { viewModel.setMaskTradition(t) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumAction(label = "🎲 Randomize", onClick = viewModel::randomMask, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1f))
        StratumAction(label = "Another like this", onClick = viewModel::anotherMask, modifier = Modifier.weight(1f))
    }
}

/**
 * The mask's dials, shown while the model is a generated mask. Every chip and
 * slider regenerates the mask live; chips are undoable steps, slider drags
 * are not (undo takes back a whole choice, not every pixel of a drag).
 */
@Composable
internal fun MaskDialsPanel(state: VoxelStudioUiState, viewModel: VoxelStudioViewModel) {
    val mask = state.mask ?: return
    val colors = StratumTheme.colors
    StratumPanel(Modifier.fillMaxWidth()) {
        SectionLabel("Mask dials · ${mask.tradition.label}")
        Dial("Face") { FaceShape.entries.forEach { f -> StratumChip(f.label, mask.face == f, { viewModel.editMask { it.copy(face = f) } }) } }
        Dial("Eyes") { EyeForm.entries.forEach { e -> StratumChip(e.label, mask.eyes == e, { viewModel.editMask { it.copy(eyes = e) } }) } }
        Dial("Nose") { NoseForm.entries.forEach { n -> StratumChip(n.label, mask.nose == n, { viewModel.editMask { it.copy(nose = n) } }) } }
        Dial("Mouth") { MouthForm.entries.forEach { m -> StratumChip(m.label, mask.mouth == m, { viewModel.editMask { it.copy(mouth = m) } }) } }
        Dial("Crest") { CrestForm.entries.forEach { c -> StratumChip(c.label, mask.crest == c, { viewModel.editMask { it.copy(crest = c) } }) } }
        Dial("Ears") {
            EarForm.entries.forEach { e -> StratumChip(e.label, mask.ears == e, { viewModel.editMask { it.copy(ears = e) } }) }
            StratumChip("Tusks", mask.tusks, { viewModel.editMask { it.copy(tusks = !it.tusks) } })
        }
        Dial("Beard") { BeardForm.entries.forEach { b -> StratumChip(b.label, mask.beard == b, { viewModel.editMask { it.copy(beard = b) } }) } }
        Dial("Marks") {
            StratumChip("Ichi ${mask.ichi}", mask.ichi > 0, { viewModel.editMask { it.copy(ichi = (it.ichi + 1) % 6) } })
            StratumChip("Cheeks ${mask.cheekMarks}", mask.cheekMarks > 0, { viewModel.editMask { it.copy(cheekMarks = (it.cheekMarks + 1) % 4) } })
            StratumChip("⇆ Symmetric", mask.symmetric, { viewModel.editMask { it.copy(symmetric = !it.symmetric) } })
        }
        Text("Colours", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
        ChipRow {
            MaskPalettes.all.forEachIndexed { i, p ->
                PaletteChip(p.colours().take(5), selected = mask.palette == i, label = p.name) { viewModel.editMask { it.copy(palette = i) } }
            }
        }
        DialSlider("Size ${mask.height} voxels (${mask.height / 4} blocks)", mask.height.toFloat(), MaskGenome.MIN_HEIGHT.toFloat()..MaskGenome.MAX_HEIGHT.toFloat(), viewModel) { g, v -> g.copy(height = v.roundToInt()) }
        DialSlider("Width", mask.width, 0f..1f, viewModel) { g, v -> g.copy(width = v) }
        DialSlider("Brow", mask.brow, 0f..1f, viewModel) { g, v -> g.copy(brow = v) }
        DialSlider("Crest height", mask.crestHeight, 0f..1f, viewModel) { g, v -> g.copy(crestHeight = v) }
        DialSlider("Combs, tiers, feathers: ${mask.crestCount}", mask.crestCount.toFloat(), 1f..9f, viewModel) { g, v -> g.copy(crestCount = v.roundToInt()) }
        DialSlider("Ornament", mask.ornament, 0f..1f, viewModel) { g, v -> g.copy(ornament = v) }
        DialSlider("Relief", mask.relief, 0f..1f, viewModel) { g, v -> g.copy(relief = v) }
        DialSlider("Features high or low", mask.features, 0f..1f, viewModel) { g, v -> g.copy(features = v) }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) { content() }
}

@Composable
private fun Dial(label: String, chips: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
        ChipRow(chips)
    }
}

/** A slider whose drag regenerates without an undo step per value. */
@Composable
private fun DialSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, viewModel: VoxelStudioViewModel, set: (MaskGenome, Float) -> MaskGenome) {
    Text(label, color = StratumTheme.colors.inkMuted, style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = { v -> viewModel.editMask(record = false) { set(it, v) } }, valueRange = range, modifier = Modifier.height(32.dp))
}

/** A scheme shown as its colours side by side, so a player picks by eye rather than by name. */
@Composable
private fun PaletteChip(colours: List<String>, selected: Boolean, label: String, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (selected) colors.accent else colors.inkMuted.copy(alpha = 0.3f))
                .clickable(onClick = onClick)
                .padding(3.dp),
        ) {
            colours.forEach { c -> Box(Modifier.size(width = 10.dp, height = 24.dp).background(Color(MicroModelRenderer.defaultColour(c)))) }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (selected) colors.accent else colors.inkMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
