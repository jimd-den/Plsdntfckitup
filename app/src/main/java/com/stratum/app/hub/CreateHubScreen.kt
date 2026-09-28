package com.stratum.app.hub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stratum.core.designsystem.component.HubCard
import com.stratum.core.designsystem.component.HubLink
import com.stratum.core.designsystem.component.LocalJobsTray
import com.stratum.core.designsystem.component.StatusTone
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.StratumTheme

/** Where each part of the studio stands, for the status on its card. */
data class StudioStatus(
    val classCount: Int = 0,
    val customClassCount: Int = 0,
    val sheetCount: Int = 0,
    /** Characters drawn in the pose forge but not yet packed into a sheet. */
    val unpackedCharacters: Int = 0,
    val keptCreations: Int = 0,
    val modelCount: Int = 0,
    /** Whether a text and image model is set up, which the writers and painters need. */
    val modelReady: Boolean = false,
    /** Whether a 3D provider is set up. */
    val meshReady: Boolean = false,
    /** The style the world is painted in, when one was painted. */
    val paintedStyle: String? = null,
    /** Pose runs and other long generations in flight. */
    val running: Int = 0,
)

/** Every way into the studio's tools. */
data class CreateHubActions(
    val onBack: () -> Unit = {},
    val onClasses: () -> Unit = {},
    val onPoses: () -> Unit = {},
    val onWeapons: () -> Unit = {},
    val onWorldCrew: () -> Unit = {},
    val onTextures: () -> Unit = {},
    val onLore: () -> Unit = {},
    val onSprites: () -> Unit = {},
    val onModels: () -> Unit = {},
    val onCrew: () -> Unit = {},
    val onMapper: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/**
 * The studio: five places to make things, each a big card with one line of
 * promise and where it stands right now. The card goes to the main tool;
 * the links under it go straight to the rest.
 */
@Composable
fun CreateHubScreen(
    status: StudioStatus,
    actions: CreateHubActions,
    modifier: Modifier = Modifier,
    jobsTray: @Composable () -> Unit = LocalJobsTray.current,
) {
    val needsKey = "Needs a model key" to StatusTone.NEEDS
    fun ai(ready: Pair<String, StatusTone>): Pair<String, StatusTone> = if (status.modelReady) ready else needsKey

    StratumScreen(title = "Create", onBack = actions.onBack, modifier = modifier, jobsTray = jobsTray, subtitle = "Everything you make works straight away and gets better as AI finishes it.") {
        if (!status.modelReady) {
            Text(
                "Most tools start from the built-in pack and work without AI. Connect a model in settings to write and paint for real.",
                style = MaterialTheme.typography.bodySmall,
                color = StratumTheme.colors.inkMuted,
            )
        }
        val heroes = when {
            status.running > 0 -> "${status.running} running" to StatusTone.BUSY
            status.unpackedCharacters > 0 -> "${status.unpackedCharacters} to pack" to StatusTone.BUSY
            status.customClassCount > 0 -> "${status.customClassCount} made · ${status.classCount} classes" to StatusTone.NEUTRAL
            else -> "${status.classCount} classes" to StatusTone.NEUTRAL
        }
        HubCard(
            glyph = "⚔", title = "Heroes", promise = "A class, a look, a weapon",
            onClick = actions.onClasses, status = heroes.first, statusTone = heroes.second,
            links = listOf(HubLink("Class", actions.onClasses), HubLink("Look", actions.onPoses), HubLink("Weapons", actions.onWeapons)),
            modifier = Modifier.fillMaxWidth(),
        )
        val worlds = ai(status.paintedStyle?.let { "Wearing: $it" to StatusTone.READY } ?: ("Ready" to StatusTone.READY))
        HubCard(
            glyph = "🌍", title = "Worlds", promise = "AI writes a whole world and paints it",
            onClick = actions.onWorldCrew, status = worlds.first, statusTone = worlds.second,
            links = listOf(HubLink("World crew", actions.onWorldCrew), HubLink("Texture style", actions.onTextures)),
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accentAlt,
        )
        val lore = if (status.keptCreations > 0) "${status.keptCreations} kept" to StatusTone.NEUTRAL else ai("Ready" to StatusTone.READY)
        HubCard(
            glyph = "⚒", title = "Lore & gear", promise = "Uniques, sets, affixes and lore",
            onClick = actions.onLore, status = lore.first, statusTone = lore.second,
            modifier = Modifier.fillMaxWidth(),
        )
        val art = when {
            status.sheetCount > 0 || status.modelCount > 0 -> "${status.sheetCount} sprites · ${status.modelCount} models" to StatusTone.NEUTRAL
            else -> ai("Ready" to StatusTone.READY)
        }
        HubCard(
            glyph = "🎨", title = "Art", promise = "Textures, sprites and 3D props",
            onClick = actions.onTextures, status = art.first, statusTone = art.second,
            links = listOf(HubLink("Textures", actions.onTextures), HubLink("Sprites", actions.onSprites), HubLink("3D models", actions.onModels)),
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accentAlt,
        )
        val advanced = ai("Every step on the record" to StatusTone.NEUTRAL)
        HubCard(
            glyph = "🤖", title = "Advanced", promise = "The agent crew and the sprite mapper",
            onClick = actions.onCrew, status = advanced.first, statusTone = advanced.second,
            links = listOf(HubLink("Agent crew", actions.onCrew), HubLink("Sprite mapper", actions.onMapper), HubLink("Model keys", actions.onSettings)),
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.inkMuted,
        )
    }
}
