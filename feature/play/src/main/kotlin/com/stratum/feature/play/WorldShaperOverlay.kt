package com.stratum.feature.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.engine.microvoxel.gen.StageParam

/** One stage as the World panel shows it: what it is, whether it runs, and each knob's value now. */
data class ShaperStage(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val params: List<StageParam>,
    val values: Map<String, String>,
) {
    fun valueOf(p: StageParam): String = values[p.key] ?: p.default
}

/**
 * The World panel: the terrain generator's stages, live. Absent (not
 * [available]) in worlds whose terrain cannot be reshaped while played.
 */
data class WorldShaperPanel(
    val open: Boolean = false,
    val available: Boolean = false,
    val stages: List<ShaperStage> = emptyList(),
    /** A new world is being built on a worker; the controls wait for it. */
    val busy: Boolean = false,
    /** How many times the land has been reshaped in this world. */
    val revision: Int = 0,
)

/** What the World panel can ask of the game. */
data class WorldShaperActions(
    val onToggle: () -> Unit = {},
    val onSet: (stageId: String, key: String, value: String) -> Unit = { _, _, _ -> },
    val onToggleStage: (stageId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onResetStage: (stageId: String) -> Unit = {},
    val onLandShape: (Map<String, String>) -> Unit = {},
    /** Reshapes the world from a description: land, towns, buildings, life. */
    val onDescribe: (String) -> Unit = {},
)

/** A whole look in one tap: the land stage's options for it. */
internal data class LandShape(val label: String, val options: Map<String, String>)

internal val LAND_SHAPES = listOf(
    LandShape("Plains", mapOf("height" to "0.12", "mountains" to "0.05", "scale" to "1.2", "terrace" to "0")),
    LandShape("Savanna", mapOf("height" to "0.26", "mountains" to "0.30", "scale" to "0.75", "terrace" to "0")),
    LandShape("Hills", mapOf("height" to "0.45", "mountains" to "0.60", "scale" to "0.9", "terrace" to "0")),
    LandShape("Highlands", mapOf("height" to "0.75", "mountains" to "1.40", "scale" to "0.6", "terrace" to "0")),
    LandShape("Terraces", mapOf("height" to "0.40", "mountains" to "0.50", "scale" to "0.8", "terrace" to "6")),
)

/**
 * Where the player reshapes the world they stand in.
 *
 * Every stage of the terrain generator is listed with its own knobs, and a
 * change applies the moment a slider is let go: the land rebuilds around
 * the hero while the game runs. Nothing here knows any stage -- the knobs
 * come from each stage's own description, so a plugin's stage gets its
 * sliders here for free. Places the player has built or dug keep their
 * changes; the rest of the world follows the new settings.
 */
@Composable
fun WorldShaperOverlay(
    panel: WorldShaperPanel,
    onSet: (stageId: String, key: String, value: String) -> Unit,
    onToggleStage: (stageId: String, enabled: Boolean) -> Unit,
    onResetStage: (stageId: String) -> Unit,
    onLandShape: (Map<String, String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onDescribe: (String) -> Unit = {},
) {
    val colors = StratumTheme.colors
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        StratumPanel(
            modifier = Modifier
                .fillMaxWidth()
                .safeContent()
                .padding(Space.medium)
                .widthIn(max = PANEL_MAX_WIDTH)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(text = "Shape the world", modifier = Modifier.weight(1f))
                if (panel.busy) Text("Building…", style = MaterialTheme.typography.labelMedium, color = colors.accent)
            }
            Text(
                "Changes rebuild the land around you as you play. What you have built or dug stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )

            // Words first: the whole scene at once, every stage it names.
            var described by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
            Spacer(Modifier.height(Space.medium))
            androidx.compose.material3.OutlinedTextField(
                value = described, onValueChange = { described = it }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
                label = { Text("Describe a scene") },
                placeholder = { Text("Kano's walled courtyards on a dry savanna, peaceful") },
            )
            StratumAction(
                label = "Reshape from words", onClick = { if (!panel.busy && described.isNotBlank()) onDescribe(described) },
                enabled = !panel.busy && described.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            )

            // The one-tap shapes tune the generic hills; a geological landscape shapes itself (see its Landscape choice).
            val land = panel.stages.firstOrNull { it.id == LAND_STAGE }
            val classic = land == null || (land.values["geology"] ?: land.params.firstOrNull { it.key == "geology" }?.default ?: CLASSIC) == CLASSIC
            if (classic) {
            Spacer(Modifier.height(Space.medium))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                items(LAND_SHAPES) { shape ->
                    StratumChip(
                        label = shape.label,
                        selected = land != null && shape.options.all { (k, v) ->
                            val p = land.params.firstOrNull { it.key == k }
                            (land.values[k] ?: p?.default)?.toFloatOrNull() == v.toFloatOrNull()
                        },
                        onClick = { if (!panel.busy) onLandShape(shape.options) },
                    )
                }
            }
            }

            for (stage in panel.stages) {
                Spacer(Modifier.height(Space.medium))
                StageCard(stage, panel.busy, onSet, onToggleStage, onResetStage)
            }

            Spacer(Modifier.height(Space.medium))
            StratumAction(label = "Done", onClick = onClose, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StageCard(
    stage: ShaperStage,
    busy: Boolean,
    onSet: (String, String, String) -> Unit,
    onToggleStage: (String, Boolean) -> Unit,
    onResetStage: (String) -> Unit,
) {
    val colors = StratumTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stage.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
        if (stage.enabled && stage.params.isNotEmpty()) {
            StratumAction(label = "Reset", onClick = { onResetStage(stage.id) }, enabled = !busy, emphasis = ActionEmphasis.QUIET)
        }
        // The land is what everything else stands on; it cannot be switched off.
        if (stage.id != LAND_STAGE) Switch(checked = stage.enabled, onCheckedChange = { onToggleStage(stage.id, it) }, enabled = !busy)
    }
    Text(stage.summary, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    if (!stage.enabled) return
    for (p in stage.params) {
        when (p) {
            is StageParam.Number -> NumberKnob(stage, p, busy, onSet)
            is StageParam.Choice -> {
                Text(p.label, style = MaterialTheme.typography.labelMedium, color = colors.ink, modifier = Modifier.padding(top = Space.small))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    items(p.options) { option ->
                        StratumChip(
                            label = option.substringAfter(':').replace('_', ' '),
                            selected = stage.valueOf(p) == option,
                            onClick = { if (!busy) onSet(stage.id, p.key, option) },
                        )
                    }
                }
            }
            is StageParam.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.labelMedium, color = colors.ink, modifier = Modifier.weight(1f))
                Switch(checked = stage.valueOf(p).toBoolean(), onCheckedChange = { onSet(stage.id, p.key, it.toString()) }, enabled = !busy)
            }
        }
    }
}

/** A slider that shows its value while dragged and applies it once, on release: one rebuild per decision, not per frame. */
@Composable
private fun NumberKnob(stage: ShaperStage, p: StageParam.Number, busy: Boolean, onSet: (String, String, String) -> Unit) {
    val colors = StratumTheme.colors
    val current = stage.valueOf(p).toFloatOrNull() ?: p.default.toFloat()
    var dragged by remember(stage.id, p.key, current) { mutableFloatStateOf(current) }
    Row(modifier = Modifier.padding(top = Space.small), verticalAlignment = Alignment.CenterVertically) {
        Text(p.label, style = MaterialTheme.typography.labelMedium, color = colors.ink, modifier = Modifier.weight(1f))
        Text(p.format(dragged), style = MaterialTheme.typography.labelMedium, color = colors.accent)
    }
    val steps = if (p.step > 0f) (((p.max - p.min) / p.step).toInt() - 1).coerceIn(0, 100) else 0
    Slider(
        value = dragged,
        onValueChange = { dragged = it },
        onValueChangeFinished = { if (p.format(dragged) != p.format(current)) onSet(stage.id, p.key, p.format(dragged)) },
        valueRange = p.min..p.max,
        steps = if (steps in 1..20) steps else 0,
        enabled = !busy,
        colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
    )
}

internal const val LAND_STAGE = "micro:terrain"
private const val CLASSIC = "classic"
