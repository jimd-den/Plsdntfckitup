package com.stratum.app.hub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.settings.VoxelDestruction
import com.stratum.engine.scene.SplatMode
import com.stratum.engine.scene.quality.QualityTier

/** What the settings screen shows besides [GameSettings]: choices kept by other stores. */
data class DisplayChoices(
    val graphics: QualityTier? = null,
    val terrain: SplatMode? = null,
    val maskCharacters: Boolean = true,
)

/** Every way the settings screen changes something. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onChange: (GameSettings) -> Unit = {},
    val onGraphics: (QualityTier?) -> Unit = {},
    val onTerrain: (SplatMode?) -> Unit = {},
    val onMaskCharacters: (Boolean) -> Unit = {},
)

/**
 * Every procedural option in one place, reached from home: towns and quests,
 * combat, how attacks reshape the ground, how bodies fall, and how the world
 * is drawn. Each choice takes effect in the next world entered.
 */
@Composable
fun SettingsScreen(settings: GameSettings, display: DisplayChoices, actions: SettingsActions, modifier: Modifier = Modifier) {
    val set = actions.onChange
    StratumScreen(title = "Settings", onBack = actions.onBack, modifier = modifier, subtitle = "Every procedural option, in one place.") {
        StratumSection("Towns & quests", subtitle = "Friendly towns, their people and the work they offer.") {
            Toggle("Safe towns", "Hostiles that follow you into a friendly town turn back at its edge. Nothing ever spawns inside one.", settings.safeTowns) { set(settings.copy(safeTowns = it)) }
            Steps("Town life", listOf("Hamlet", "Village", "Town", "Market town"), settings.townLife) { set(settings.copy(townLife = it)) }
            Steps("Quests per town", (1..8).map(Int::toString), settings.questsPerTown - 1) { set(settings.copy(questsPerTown = it + 1)) }
            Steps("Quest difficulty", listOf("Errands", "Balanced", "Heroic", "Legendary"), settings.questDifficulty) { set(settings.copy(questDifficulty = it)) }
            Toggle("Quest chains", "A finished quest can lead to the giver's next one.", settings.questChains) { set(settings.copy(questChains = it)) }
        }
        StratumSection("Combat", subtitle = "How varied monsters' attacks are.") {
            Steps("Attacks per monster", listOf("1", "2", "3"), settings.attacksPerMonster - 1) { set(settings.copy(attacksPerMonster = it + 1)) }
            Toggle("Never the same move twice", "Monsters fighting together never share an attack.", settings.distinctAttacks) { set(settings.copy(distinctAttacks = it)) }
        }
        StratumSection("Voxel physics", subtitle = "How much attacks reshape the ground.") {
            Steps("Destruction", VoxelDestruction.entries.map { it.label }, settings.voxelDestruction.ordinal) { set(settings.copy(voxelDestruction = VoxelDestruction.entries[it])) }
            Hint(settings.voxelDestruction.blurb)
            if (settings.voxelDestruction == VoxelDestruction.PHYSICS) {
                val caps = listOf(40, 80, 160, 320, 640)
                Steps("Loose blocks at once", caps.map(Int::toString), caps.indexOf(settings.maxDebris).coerceAtLeast(0)) { set(settings.copy(maxDebris = caps[it])) }
                Hint("Fewer is faster on older phones; past the limit the oldest blocks settle at once.")
            }
        }
        StratumSection("Characters", subtitle = "How bodies look and fall.") {
            Toggle("Ragdolls", "Bodies tumble and fall loose when they die or are blasted.", settings.ragdolls) { set(settings.copy(ragdolls = it)) }
            Steps("Drawn as", listOf("Mask spirits", "Sprites"), if (display.maskCharacters) 0 else 1) { actions.onMaskCharacters(it == 0) }
        }
        StratumSection("Graphics", subtitle = "How the world is drawn on this device.") {
            val tiers = listOf<QualityTier?>(null) + QualityTier.entries
            Steps("Quality", tiers.map { it?.name?.lowercase()?.replaceFirstChar(Char::uppercaseChar) ?: "Auto" }, tiers.indexOf(display.graphics)) { actions.onGraphics(tiers[it]) }
            val modes = listOf(null to "Auto", SplatMode.MESH to "Mesh", SplatMode.FAST to "Voxels", SplatMode.EXACT to "Voxels, sharp")
            Steps("Terrain", modes.map { it.second }, modes.indexOfFirst { it.first == display.terrain }) { actions.onTerrain(modes[it].first) }
        }
    }
}

@Composable
private fun Toggle(label: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(Space.small))
    Text(label, style = MaterialTheme.typography.labelLarge, color = StratumTheme.colors.ink)
    Hint(hint)
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        StratumChip(label = "On", selected = on, onClick = { onChange(true) })
        StratumChip(label = "Off", selected = !on, onClick = { onChange(false) })
    }
}

@Composable
private fun Steps(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Spacer(Modifier.height(Space.small))
    Text(label, style = MaterialTheme.typography.labelLarge, color = StratumTheme.colors.ink)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
        options.forEachIndexed { i, o -> StratumChip(label = o, selected = i == selected, onClick = { onPick(i) }) }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = StratumTheme.colors.inkMuted)
