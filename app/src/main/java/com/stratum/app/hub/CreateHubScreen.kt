package com.stratum.app.hub

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stratum.core.designsystem.component.HubCard
import com.stratum.core.designsystem.component.HubLink
import com.stratum.core.designsystem.component.StatusTone
import com.stratum.core.designsystem.component.StratumScreen
import com.stratum.core.designsystem.theme.StratumTheme

/** Where each part of the studio stands, for the status on its card. */
data class StudioStatus(
    val classCount: Int = 0,
    val customClassCount: Int = 0,
    /** Sprite sheets imported or mapped on this device. */
    val sheetCount: Int = 0,
    /** Microvoxel models the player has kept in the model studio. */
    val microModelCount: Int = 0,
    /** Whether the hero wears a mask from the mask maker. */
    val wearsMakerMask: Boolean = false,
    val wearsCarvedMask: Boolean = false,
    /** Forged attacks carried into play. */
    val carriedAttacks: Int = 0,
)

/** Every way into the studio's tools. */
data class CreateHubActions(
    val onBack: () -> Unit = {},
    val onClasses: () -> Unit = {},
    val onVoxels: () -> Unit = {},
    val onMasks: () -> Unit = {},
    val onCarver: () -> Unit = {},
    val onAttacks: () -> Unit = {},
    val onMapper: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/**
 * The studio: every tool is procedural, works offline, and makes something
 * the game uses straight away. Each card goes to its tool and says where it
 * stands right now.
 */
@Composable
fun CreateHubScreen(
    status: StudioStatus,
    actions: CreateHubActions,
    modifier: Modifier = Modifier,
) {
    StratumScreen(title = "Create", onBack = actions.onBack, modifier = modifier, subtitle = "Procedural tools: everything you make is played at once.") {
        val heroes = if (status.customClassCount > 0) "${status.customClassCount} made · ${status.classCount} classes" else "${status.classCount} classes"
        HubCard(
            glyph = "🛡", title = "Heroes", promise = "Build a class from skills, traits and stats",
            onClick = actions.onClasses, status = heroes, statusTone = StatusTone.NEUTRAL,
            links = listOf(HubLink("Class", actions.onClasses), HubLink("Sprite mapper", actions.onMapper)),
            modifier = Modifier.fillMaxWidth(),
        )
        HubCard(
            glyph = "⚔️", title = "Forge of Will", promise = "Forge attacks from a core, catalysts and resonators — billions of them, each in a look of its own",
            onClick = actions.onAttacks,
            status = if (status.carriedAttacks > 0) "${status.carriedAttacks} carried into play" else "Ready",
            statusTone = if (status.carriedAttacks > 0) StatusTone.NEUTRAL else StatusTone.READY,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accent,
        )
        HubCard(
            glyph = "🗿", title = "Mask carver", promise = "Carve a sculpted African mask — ${com.stratum.engine.model.mask.sculpt.MaskCulture.traditions.size} peoples' traditions, every part and proportion yours",
            onClick = actions.onCarver,
            status = if (status.wearsCarvedMask) "Worn by your hero" else "Ready",
            statusTone = if (status.wearsCarvedMask) StatusTone.NEUTRAL else StatusTone.READY,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accentAlt,
        )
        HubCard(
            glyph = "🎭", title = "Mask maker", promise = "Make your own Igbo mask — carve it, paint it, give it feelings, wear it",
            onClick = actions.onMasks,
            status = if (status.wearsMakerMask) "Worn by your hero" else "Ready",
            statusTone = if (status.wearsMakerMask) StatusTone.NEUTRAL else StatusTone.READY,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accentAlt,
        )
        HubCard(
            glyph = "🧱", title = "Model studio", promise = "Microvoxel assets by hand, from a picture, or rolled from the building generator",
            onClick = actions.onVoxels,
            status = if (status.microModelCount > 0) "${status.microModelCount} kept" else "Ready",
            statusTone = if (status.microModelCount > 0) StatusTone.NEUTRAL else StatusTone.READY,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.accent,
        )
        HubCard(
            glyph = "⚙", title = "Settings", promise = "Every procedural option in one place: worlds, quests, towns, combat, physics, graphics",
            onClick = actions.onSettings, status = "All options", statusTone = StatusTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            tint = StratumTheme.colors.inkMuted,
        )
    }
}
