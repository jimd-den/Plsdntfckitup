package com.stratum.feature.forge

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.stratum.core.domain.micro.MicroModel
import com.stratum.engine.model.ImageVoxelizer
import com.stratum.engine.model.MicroModelRenderer

/**
 * The model studio: a layer grid to draw on, a turning preview of the whole
 * model, and four ways to start -- an Igbo mask with live dials, a blank box,
 * a building rolled from the world's own generator, or a picture (an image model's output, a photo, a
 * sketch) turned into voxels. What is kept here can be placed from the play
 * screen's build tray, at a quarter block, in full detail.
 */
@Composable
fun VoxelStudioScreen(
    viewModel: VoxelStudioViewModel,
    onBack: () -> Unit,
    /** Opens the device's picker; the app decodes the picture and hands it to [VoxelStudioViewModel.fromImage]. */
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = StratumTheme.colors
    LaunchedEffect(Unit) { viewModel.refresh() }
    val model = state.model

    Column(
        modifier = modifier.fillMaxSize().background(colors.surface).safeContent()
            .verticalScroll(rememberScrollState()).padding(Space.large),
    ) {
        com.stratum.core.designsystem.component.StratumTopBar(title = "Model studio", onBack = onBack)
        Text(
            "Build your own microvoxel assets, a quarter block at a time. Keep them to place in the world from the build tray.",
            style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.medium))

        StratumPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
                Preview(model, state.turn, state.revision, 200)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    OutlinedTextField(value = model.name, onValueChange = viewModel::setName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(state.sizeLabel, color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
                    Text("${state.filled} voxels · ${model.palette.size} colours", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                        StratumChip("⟳ View", selected = false, onClick = viewModel::rotateView)
                        StratumChip("↶", selected = false, onClick = viewModel::undo)
                        StratumChip("↷", selected = false, onClick = viewModel::redo)
                    }
                }
            }
            state.message?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { viewModel.dismissMessage() }) }
        }

        if (state.mask != null) {
            Spacer(Modifier.height(Space.medium))
            MaskDialsPanel(state, viewModel)
        }

        Spacer(Modifier.height(Space.medium))
        StratumPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layer ${state.layer + 1} of ${model.sizeZ}", color = colors.ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
                StratumChip("▼", selected = false, onClick = { viewModel.setLayer(state.layer - 1) })
                Slider(
                    value = state.layer.toFloat(), onValueChange = { viewModel.setLayer(it.toInt()) },
                    valueRange = 0f..(model.sizeZ - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f),
                )
                StratumChip("▲", selected = false, onClick = { viewModel.setLayer(state.layer + 1) })
            }
            LayerGrid(state, viewModel, Modifier.fillMaxWidth().aspectRatio(model.sizeX / model.sizeY.toFloat()))
            Spacer(Modifier.height(Space.small))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StudioTool.entries.forEach { tool ->
                    StratumChip("${tool.glyph} ${tool.label}", selected = state.tool == tool, onClick = { viewModel.setTool(tool) })
                }
                StratumChip("⇆ Mirror", selected = state.mirrorX, onClick = viewModel::toggleMirrorX)
                StratumChip("⇅ Mirror", selected = state.mirrorY, onClick = viewModel::toggleMirrorY)
            }
            if (state.tool == StudioTool.BALL) {
                Text("Ball radius ${"%.0f".format(state.ball)}", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
                Slider(value = state.ball, onValueChange = viewModel::setBall, valueRange = 1f..16f)
            }
            Spacer(Modifier.height(Space.small))
            Swatches(state, viewModel)
        }

        Spacer(Modifier.height(Space.medium))
        StratumPanel(Modifier.fillMaxWidth()) {
            SectionLabel("Shape")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip("Turn", selected = false, onClick = viewModel::turnModel)
                StratumChip("Flip ⇆", selected = false, onClick = viewModel::flipX)
                StratumChip("Flip ⇅", selected = false, onClick = viewModel::flipY)
                StratumChip("Trim", selected = false, onClick = viewModel::trim)
                StratumChip("×2", selected = false, onClick = viewModel::doubleSize)
                StratumChip("½", selected = false, onClick = viewModel::halveSize)
                StratumChip("Weather", selected = false, onClick = viewModel::weather)
                StratumChip("Hollow", selected = false, onClick = viewModel::hollow)
                StratumChip("+W", selected = false, onClick = { viewModel.grow(4, 0, 0) })
                StratumChip("+D", selected = false, onClick = { viewModel.grow(0, 4, 0) })
                StratumChip("+H", selected = false, onClick = { viewModel.grow(0, 0, 4) })
            }
        }

        Spacer(Modifier.height(Space.medium))
        StratumPanel(Modifier.fillMaxWidth()) {
            SectionLabel("Start from")
            MaskStartSection(state, viewModel)
            Spacer(Modifier.height(Space.small))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip("Blank 16³", selected = false, onClick = { viewModel.newModel(16, 16, 16) })
                StratumChip("Blank 32³", selected = false, onClick = { viewModel.newModel(32, 32, 32) })
                StratumChip("Tall 24×24×64", selected = false, onClick = { viewModel.newModel(24, 24, 64) })
                StratumChip("Wide 64×64×24", selected = false, onClick = { viewModel.newModel(64, 64, 24) })
            }
            Spacer(Modifier.height(Space.small))
            Text("A building from the world's generator", color = colors.ink, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip("Any", selected = state.tradition == null, onClick = { viewModel.setTradition(null) })
                viewModel.traditions.forEach { (id, name) ->
                    StratumChip(name, selected = state.tradition == id, onClick = { viewModel.setTradition(id) })
                }
            }
            StratumAction(label = if (state.working) "Working…" else "🎲 Roll a building", onClick = viewModel::rollBuilding, enabled = !state.working, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.small))
            Text("A picture into voxels — an image model's output works best on a plain background", color = colors.ink, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                ImageVoxelizer.Mode.entries.filter { it != ImageVoxelizer.Mode.TWO_VIEW && it != ImageVoxelizer.Mode.DEPTH }.forEach { mode ->
                    StratumChip(mode.name.lowercase().replaceFirstChar(Char::uppercase), selected = state.imageMode == mode, onClick = { viewModel.setImageMode(mode) })
                }
            }
            Text("Height ${state.imageHeight} voxels (${state.imageHeight / 4} blocks)", color = colors.inkMuted, style = MaterialTheme.typography.bodySmall)
            Slider(value = state.imageHeight.toFloat(), onValueChange = { viewModel.setImageHeight(it.toInt()) }, valueRange = 8f..MicroModel.MAX_SIDE.toFloat())
            StratumAction(label = "🖼 Pick a picture", onClick = onPickImage, enabled = !state.working, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(Space.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumAction(label = "Keep", onClick = viewModel::save, emphasis = ActionEmphasis.PRIMARY, modifier = Modifier.weight(1f))
            StratumAction(label = "Keep a copy", onClick = viewModel::saveCopy, modifier = Modifier.weight(1f))
        }

        if (state.library.isNotEmpty()) {
            Spacer(Modifier.height(Space.large))
            SectionLabel("Your models")
            Spacer(Modifier.height(Space.small))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                state.library.forEach { m ->
                    Column(Modifier.width(96.dp).clickable { viewModel.open(m) }) {
                        Preview(m, VoxelStudioViewModel.previewTurn(m), m.hashCode(), 96)
                        Text(m.name, color = if (m.id == model.id) colors.accent else colors.ink, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text("Delete", color = colors.danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { viewModel.delete(m.id) })
                    }
                }
            }
        }
    }
}

/** One layer of the model, drawn as a grid; the layer below shows faintly through, as tracing paper would. */
@Composable
private fun LayerGrid(state: VoxelStudioUiState, viewModel: VoxelStudioViewModel, modifier: Modifier) {
    val colors = StratumTheme.colors
    val model = state.model
    val current by rememberUpdatedState(state)
    Canvas(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceRaised)
            .pointerInput(model.sizeX, model.sizeY) {
                detectTapGestures { p ->
                    val m = current.model
                    viewModel.tapCell((p.x / size.width * m.sizeX).toInt(), m.sizeY - 1 - (p.y / size.height * m.sizeY).toInt())
                }
            }
            .pointerInput(model.sizeX, model.sizeY) {
                detectDragGestures(
                    onDragEnd = viewModel::endStroke,
                    onDragCancel = viewModel::endStroke,
                ) { change, _ ->
                    val m = current.model
                    val p = change.position
                    viewModel.dragCell((p.x / size.width * m.sizeX).toInt(), m.sizeY - 1 - (p.y / size.height * m.sizeY).toInt())
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") state.revision
        val cw = size.width / model.sizeX; val ch = size.height / model.sizeY
        val z = state.layer
        for (y in 0 until model.sizeY) for (x in 0 until model.sizeX) {
            val here = model.at(x, y, z)
            val below = if (z > 0) model.at(x, y, z - 1) else null
            val entry = here ?: below ?: continue
            val argb = MicroModelRenderer.defaultColour(entry)
            val c = Color(argb).copy(alpha = if (here != null) 1f else 0.25f)
            drawRect(c, Offset(x * cw, (model.sizeY - 1 - y) * ch), Size(cw + 0.5f, ch + 0.5f))
        }
        if (cw >= 6f) {
            val line = colors.inkMuted.copy(alpha = 0.18f)
            for (x in 0..model.sizeX) drawLine(line, Offset(x * cw, 0f), Offset(x * cw, size.height))
            for (y in 0..model.sizeY) drawLine(line, Offset(0f, y * ch), Offset(size.width, y * ch))
        }
        // Block edges every four cells, so a player sees how big it will stand.
        val block = colors.accent.copy(alpha = 0.35f)
        for (x in 0..model.sizeX step 4) drawLine(block, Offset(x * cw, 0f), Offset(x * cw, size.height))
        for (y in 0..model.sizeY step 4) drawLine(block, Offset(0f, size.height - y * ch), Offset(size.width, size.height - y * ch))
        if (state.mirrorX) drawLine(colors.danger, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
        if (state.mirrorY) drawLine(colors.danger, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
        state.anchor?.let { (ax, ay, _) ->
            drawRect(colors.accent, Offset(ax * cw, (model.sizeY - 1 - ay) * ch), Size(cw, ch), style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun Swatches(state: VoxelStudioUiState, viewModel: VoxelStudioViewModel) {
    val colors = StratumTheme.colors
    val entries = (VoxelStudioViewModel.SWATCHES + state.model.palette).distinct()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            val selected = entry == state.colour
            Box(
                Modifier.size(if (selected) 34.dp else 28.dp).clip(CircleShape)
                    .background(Color(MicroModelRenderer.defaultColour(entry)))
                    .border(if (selected) 3.dp else 1.dp, if (selected) colors.accent else colors.inkMuted.copy(alpha = 0.4f), CircleShape)
                    .clickable { viewModel.setColour(entry) },
            )
        }
    }
}

@Composable
private fun Preview(model: MicroModel, turn: Int, revision: Int, sizeDp: Int) {
    val px = 192
    val image: ImageBitmap = remember(model, turn, revision) {
        val pixels = MicroModelRenderer.render(model, px, turn = turn)
        Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = image, contentDescription = model.name, filterQuality = FilterQuality.None,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(StratumTheme.colors.surfaceRaised),
    )
}
