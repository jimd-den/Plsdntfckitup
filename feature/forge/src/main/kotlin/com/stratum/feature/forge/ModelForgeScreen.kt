package com.stratum.feature.forge

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.JobProgress
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.ai.ModelSubjectKind

/**
 * Describe a thing, get a 3D model of it, and put it in the world: as a prop
 * that stands wherever its block does, or rebuilt from the world's own blocks.
 *
 * The preview is the model baked to a sprite from the game's own camera, so
 * what the player judges here is what the world will show.
 */
@Composable
fun ModelForgeScreen(
    viewModel: ModelForgeViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the device's picker; the app hands the bytes back through [ModelForgeViewModel.setReference]. */
    onPickReference: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = StratumTheme.colors
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).safeContent()
            .verticalScroll(rememberScrollState()).padding(Space.large),
    ) {
        com.stratum.core.designsystem.component.StratumTopBar(title = "3D models", onBack = onBack)
        Spacer(Modifier.height(Space.small))
        Text(
            "Props, weapons, creatures and statues as real 3D models, from ${state.provider.ifBlank { "your 3D provider" }}.",
            style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted,
        )
        if (!state.providerConfigured) {
            Spacer(Modifier.height(Space.medium))
            StratumAction(label = "Set up a 3D provider", onClick = onOpenSettings, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(Space.large))
        StratumPanel(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.subject, onValueChange = viewModel::setSubject, modifier = Modifier.fillMaxWidth(),
                label = { Text("What to make") }, placeholder = { Text("a moss-covered stone shrine") },
            )
            Spacer(Modifier.height(Space.small))
            OutlinedTextField(
                value = state.style, onValueChange = viewModel::setStyle, modifier = Modifier.fillMaxWidth(),
                label = { Text("Style (optional)") }, singleLine = true,
            )
            Spacer(Modifier.height(Space.small))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                ModelSubjectKind.entries.forEach { kind ->
                    StratumChip(kind.name.lowercase().replaceFirstChar(Char::uppercase), selected = state.kind == kind, onClick = { viewModel.setKind(kind) })
                }
            }
            Spacer(Modifier.height(Space.small))
            Text("Height: ${"%.1f".format(state.heightBlocks)} blocks", color = colors.ink, style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.heightBlocks, onValueChange = viewModel::setHeight,
                valueRange = ModelForgeViewModel.MIN_HEIGHT..ModelForgeViewModel.MAX_HEIGHT,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip("Textured", selected = state.textured, onClick = { viewModel.setTextured(!state.textured) })
                StratumChip(
                    if (state.reference != null) "Reference picked" else "Pick a reference",
                    selected = state.reference != null,
                    onClick = { if (state.reference != null) viewModel.setReference(null) else onPickReference() },
                )
            }
            Spacer(Modifier.height(Space.medium))
            StratumAction(
                label = if (state.generating) "Building…" else "Generate model",
                onClick = viewModel::generate, enabled = state.canGenerate,
                emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.fillMaxWidth(),
            )
            val job = state.job
            if (state.generating && job != null) {
                Spacer(Modifier.height(Space.small))
                val fraction = state.progress?.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.small))
                JobProgress(job, showHeader = false, onCancel = viewModel::cancel)
            } else if (state.generating) {
                Spacer(Modifier.height(Space.small))
                val fraction = state.progress?.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(state.progressLabel, color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall) }
            state.message?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall) }
        }

        state.selected?.let { asset ->
            Spacer(Modifier.height(Space.large))
            StratumPanel(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                    Preview(viewModel.previewFor(asset.id), 128)
                    Column(Modifier.weight(1f)) {
                        Text(asset.name, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        Text("${asset.triangleCount} triangles · ${asset.format.extension} · ${asset.provider}", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
                        asset.propBlockId?.let { Text("Stands in for $it", color = colors.accent, style = MaterialTheme.typography.bodySmall) }
                        asset.blueprintId?.let { Text("Buildable as blocks", color = colors.accent, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(Space.medium))
                Text("Use as prop", color = colors.ink, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    state.propBlocks.forEach { block ->
                        StratumChip(
                            block.displayName, selected = asset.propBlockId == block.id,
                            onClick = { viewModel.useAsProp(if (asset.propBlockId == block.id) null else block.id) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.small))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                    StratumAction(label = if (state.working) "Voxelising…" else "Voxelise into blocks", onClick = viewModel::voxelize, enabled = !state.working)
                    StratumAction(label = "Delete", onClick = { viewModel.delete(asset.id) }, emphasis = ActionEmphasis.QUIET)
                }
            }
        }

        if (state.assets.isNotEmpty()) {
            Spacer(Modifier.height(Space.large))
            SectionLabel("Saved models")
            Spacer(Modifier.height(Space.small))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                state.assets.forEach { asset ->
                    Column(Modifier.width(96.dp).clickable { viewModel.select(asset.id) }) {
                        Preview(viewModel.previewFor(asset.id), 96)
                        Text(asset.name, color = if (asset.id == state.selectedId) colors.accent else colors.ink, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(png: ByteArray?, size: Int) {
    val image: ImageBitmap? = remember(png) { png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(size.dp))
    } else {
        Spacer(Modifier.size(size.dp).background(StratumTheme.colors.surfaceRaised))
    }
}
