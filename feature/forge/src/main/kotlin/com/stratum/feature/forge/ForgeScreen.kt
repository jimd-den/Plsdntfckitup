package com.stratum.feature.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.domain.content.ContentPack

/**
 * The pack forge: describe a world, get one you can play.
 *
 * The preview after generation is the point of the screen. A generated pack
 * changes what the whole game is made of, so the player sees exactly what
 * arrived before it is allowed to load.
 */
@Composable
fun ForgeScreen(
    viewModel: ForgeViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    // Re-read the provider on every visit. The view model outlives this screen,
    // so without this a key saved in settings a moment ago is still reported as
    // missing until the app is restarted.
    LaunchedEffect(Unit) { viewModel.refreshProviderStatus() }

    val state by viewModel.state.collectAsStateWithLifecycle()
    ForgeScreenContent(
        state = state,
        modifier = modifier,
        onThemeChange = viewModel::updateTheme,
        onForge = viewModel::forgePack,
        onForgeLore = viewModel::forgeLore,
        onAccept = viewModel::acceptPack,
        onDiscard = viewModel::discard,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun ForgeScreenContent(
    state: ForgeUiState,
    modifier: Modifier = Modifier,
    onThemeChange: (String) -> Unit = {},
    onForge: () -> Unit = {},
    onForgeLore: () -> Unit = {},
    onAccept: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val colors = StratumTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeContent()
            .verticalScroll(rememberScrollState())
            .padding(Space.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("Pack forge")
            StratumAction(label = "Back", onClick = onBack, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.medium))

        Text(
            text = "Describe a world. A model writes the blocks, regions, classes and lore, " +
                "and the result loads as an ordinary content pack.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )

        Spacer(Modifier.height(Space.large))

        StratumPanel(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.theme,
                onValueChange = onThemeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Theme") },
                placeholder = { Text("A drowned city of glass beneath a frozen sea") },
                enabled = !state.isBusy,
                minLines = 2,
            )

            Spacer(Modifier.height(Space.medium))

            if (!state.providerConfigured) {
                Text(
                    text = "No model provider is configured yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                Spacer(Modifier.height(Space.small))
                StratumAction(
                    label = "Open settings",
                    onClick = onOpenSettings,
                    emphasis = ActionEmphasis.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                StratumAction(
                    label = if (state.isBusy) "Forging" else "Forge pack",
                    onClick = onForge,
                    emphasis = ActionEmphasis.PRIMARY,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.isBusy) {
                Spacer(Modifier.height(Space.medium))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.surfaceSunken,
                )
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(Space.medium))
            StratumPanel(modifier = Modifier.fillMaxWidth(), raised = false) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
            }
        }

        val pack = state.result
        if (pack != null) {
            Spacer(Modifier.height(Space.large))
            PackPreview(
                pack = pack,
                loreCount = state.loreCount,
                busy = state.isBusy,
                accepted = state.status == ForgeStatus.ACCEPTED,
                onForgeLore = onForgeLore,
                onAccept = onAccept,
                onDiscard = onDiscard,
            )
        }

        Spacer(Modifier.height(Space.huge))
    }
}

@Composable
private fun PackPreview(
    pack: ContentPack,
    loreCount: Int,
    busy: Boolean,
    accepted: Boolean,
    onForgeLore: () -> Unit,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colors = StratumTheme.colors

    StratumSection(
        title = pack.name,
        subtitle = pack.description,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            Count("Blocks", pack.blocks.size, Modifier.weight(1f))
            Count("Regions", pack.biomes.size, Modifier.weight(1f))
            Count("Classes", pack.heroClasses.size, Modifier.weight(1f))
            Count("Lore", loreCount, Modifier.weight(1f))
        }

        Spacer(Modifier.height(Space.medium))
        StratumDivider()
        Spacer(Modifier.height(Space.medium))

        // The palette is shown as swatches because it restyles the interface as
        // well as the world, which is not obvious from a list of hex values.
        SectionLabel("Palette")
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.tight)) {
            listOf(
                pack.palette.surface,
                pack.palette.surfaceRaised,
                pack.palette.accent,
                pack.palette.accentAlt,
                pack.palette.danger,
                pack.palette.ink,
            ).forEach { value ->
                Box(
                    Modifier
                        .height(24.dp)
                        .weight(1f)
                        .background(Color(value)),
                )
            }
        }

        Spacer(Modifier.height(Space.medium))
        SectionLabel("Blocks")
        Spacer(Modifier.height(Space.small))
        pack.blocks.take(PREVIEW_BLOCKS).forEach { block ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.hair),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(Color(block.topColor)),
                    )
                    Spacer(Modifier.width(Space.small))
                    Text(
                        text = block.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink,
                    )
                }
                Text(
                    text = "${block.material.name.lowercase()} · ${block.hardness}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
        }
        if (pack.blocks.size > PREVIEW_BLOCKS) {
            Text(
                text = "and ${pack.blocks.size - PREVIEW_BLOCKS} more",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.height(Space.large))

        if (accepted) {
            Text(
                text = "Loaded. Start a new world to see it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.accentAlt,
            )
        } else {
            StratumAction(
                label = "Load this pack",
                onClick = onAccept,
                emphasis = ActionEmphasis.PRIMARY,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.small))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(
                    label = "Write more lore",
                    onClick = onForgeLore,
                    emphasis = ActionEmphasis.SECONDARY,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                StratumAction(
                    label = "Discard",
                    onClick = onDiscard,
                    emphasis = ActionEmphasis.DESTRUCTIVE,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Count(label: String, value: Int, modifier: Modifier = Modifier) {
    StratumWell(modifier = modifier) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = StratumTheme.colors.accent,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
        )
    }
}

private const val PREVIEW_BLOCKS = 8
