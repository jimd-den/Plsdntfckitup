package com.stratum.feature.forge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumDivider
import com.stratum.core.designsystem.component.StratumPanel
import com.stratum.core.designsystem.component.StratumSection
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Cut
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.sprite.ActorRole
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.FacingLayout
import com.stratum.core.domain.sprite.FrameRef
import com.stratum.core.domain.sprite.SliceSpec
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteAtlas
import com.stratum.core.domain.sprite.SpriteSheet
import com.stratum.core.domain.sprite.SpriteValidation
import com.stratum.core.domain.sprite.SpriteValidationReport
import com.stratum.core.domain.sprite.ValidationSeverity

/**
 * Map the frames of a sprite sheet by hand.
 *
 * This screen exists because generating a sprite and getting a *usable* sprite
 * are different problems, and only the first one is the model's. An image model
 * will draw a superb character and lay it out on a grid nobody asked for, with
 * three blank cells, two duplicates and no death pose. None of that is fixable
 * by prompting harder. All of it is fixable in about ninety seconds by a person
 * who can see the cells and say which ones are the walk.
 *
 * So the work here is deliberately small and concrete: look at the grid, switch
 * the bad cells off, tap the good ones into the state they belong to, watch it
 * move, save. Nothing is destroyed on the way — the source image and the
 * mapping are kept side by side, and baking is a thing you can come back and do
 * again.
 */
@Composable
fun SpriteMapperScreen(
    viewModel: SpriteMapperViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /** The untouched source art for the atlas being mapped. */
    sourceFor: (SpriteAtlas) -> ImageBitmap? = { null },
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val state by viewModel.state.collectAsStateWithLifecycle()

    // Decoded once per atlas. The source is a megapixel PNG and this screen
    // recomposes on every tap, so decoding it inline would spend a full image
    // decode on switching a single cell off.
    val atlas = state.atlas
    val source = remember(atlas?.id, atlas?.sourceId) { atlas?.let(sourceFor) }

    SpriteMapperContent(
        state = state,
        source = source,
        modifier = modifier,
        onBack = onBack,
        onAction = { action ->
            when (action) {
                is SpriteMapperAction.Open -> viewModel.open(action.sheet)
                SpriteMapperAction.Close -> viewModel.close()
                SpriteMapperAction.Undo -> viewModel.undo()
                is SpriteMapperAction.SetGrid -> {
                    val slice = state.slice
                    viewModel.setGrid(
                        columns = action.columns ?: slice?.columns ?: 1,
                        rows = action.rows ?: slice?.rows ?: 1,
                        offsetX = action.offsetX ?: slice?.offsetX ?: 0,
                        offsetY = action.offsetY ?: slice?.offsetY ?: 0,
                        gutterX = action.gutterX ?: slice?.gutterX ?: 0,
                        gutterY = action.gutterY ?: slice?.gutterY ?: 0,
                    )
                }
                is SpriteMapperAction.SetCellSize -> viewModel.setCellSize(action.pixels)
                is SpriteMapperAction.SetSquareCells -> viewModel.setSquareCells(action.on)
                is SpriteMapperAction.SetGridFromBox -> viewModel.setGridFromBox(action.rect)
                SpriteMapperAction.TrimToContent -> viewModel.trimToContent()
                is SpriteMapperAction.ToggleFrame -> viewModel.toggleFrame(action.frameId)
                is SpriteMapperAction.FlipFrame -> viewModel.flipFrame(action.frameId)
                is SpriteMapperAction.CyclePivot -> viewModel.cyclePivot(action.frameId)
                is SpriteMapperAction.DuplicateFrame -> viewModel.duplicateFrame(action.frameId)
                is SpriteMapperAction.OpenFrame -> viewModel.focusFrame(action.frameId)
                SpriteMapperAction.CloseFrame -> viewModel.closeFrame()
                is SpriteMapperAction.StepFrame -> viewModel.stepFrame(action.forward)
                is SpriteMapperAction.SetStep -> viewModel.setStep(action.pixels)
                is SpriteMapperAction.NudgeFrame -> viewModel.nudgeFrame(action.dx, action.dy)
                is SpriteMapperAction.ResizeFrame -> viewModel.resizeFrame(
                    left = action.left,
                    top = action.top,
                    right = action.right,
                    bottom = action.bottom,
                )
                is SpriteMapperAction.SetFrameRect -> viewModel.setFrameRect(action.rect)
                SpriteMapperAction.SnapFrameToContent -> viewModel.snapFrameToContent()
                SpriteMapperAction.AddFrame -> viewModel.addFrame()
                is SpriteMapperAction.RemoveFrame -> viewModel.removeFrame(action.frameId)
                is SpriteMapperAction.SelectState -> viewModel.selectState(action.state)
                is SpriteMapperAction.AddToClip -> viewModel.addToActiveClip(action.frameId)
                is SpriteMapperAction.RemoveFromClip -> viewModel.removeFromActiveClip(action.index)
                is SpriteMapperAction.MoveInClip -> viewModel.moveInActiveClip(action.from, action.to)
                SpriteMapperAction.ClearClip -> viewModel.clearActiveClip()
                is SpriteMapperAction.SetFps -> viewModel.setActiveFps(action.fps)
                SpriteMapperAction.ToggleLoops -> viewModel.toggleActiveLoops()
                SpriteMapperAction.FillFallbacks -> viewModel.fillFallbacks()
                is SpriteMapperAction.SetName -> viewModel.setName(action.name)
                is SpriteMapperAction.SetRole -> viewModel.setRole(action.role)
                is SpriteMapperAction.SetFacing -> viewModel.setFacing(action.facing)
                SpriteMapperAction.SaveDraft -> viewModel.saveDraft()
                SpriteMapperAction.Bake -> viewModel.bake()
                SpriteMapperAction.DismissMessage -> viewModel.dismissMessage()
            }
        },
    )
}

@Composable
fun SpriteMapperContent(
    state: SpriteMapperUiState,
    source: ImageBitmap?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onAction: (SpriteMapperAction) -> Unit = {},
) {
    val colors = StratumTheme.colors
    val atlas = state.atlas

    // One frame open means one frame on screen. Showing the large view inside
    // the scrolling editor would put the thing being worked on in a window
    // half the size of the controls for it.
    if (atlas != null && state.focusFrame != null) {
        FrameStudio(
            state = state,
            source = source,
            modifier = modifier,
            onAction = onAction,
        )
        return
    }

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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Frame mapper")
            StratumAction(
                label = if (atlas == null) "Back" else "Sheets",
                onClick = { if (atlas == null) onBack() else onAction(SpriteMapperAction.Close) },
                emphasis = ActionEmphasis.QUIET,
            )
        }

        Spacer(Modifier.height(Space.medium))

        if (atlas == null) {
            SheetPicker(state.sheets, onAction)
            return@Column
        }

        Notice(state.message, state.error, onAction)

        Spacer(Modifier.height(Space.medium))
        SourcePanel(atlas, state.slice, state.squareCells, source, onAction)

        Spacer(Modifier.height(Space.medium))
        GridPanel(state.slice, state.squareCells, onAction)

        Spacer(Modifier.height(Space.medium))
        ContactSheet(atlas, state, source, onAction)

        Spacer(Modifier.height(Space.medium))
        ClipPanel(state, source, onAction)

        Spacer(Modifier.height(Space.medium))
        ReportPanel(state.report, onAction)

        Spacer(Modifier.height(Space.medium))
        AssetPanel(atlas, state, onAction)

        Spacer(Modifier.height(Space.huge))
    }
}

/**
 * The way in: everything already in the library, ready to be re-cut.
 *
 * A sheet that turned out badly is not a dead end, it is the starting material.
 * Listing them here is what makes that true in practice rather than in theory.
 */
@Composable
private fun SheetPicker(sheets: List<SpriteSheet>, onAction: (SpriteMapperAction) -> Unit) {
    val colors = StratumTheme.colors

    StratumSection(
        title = "Pick a sheet to map",
        subtitle = "Re-cut the grid, switch the bad cells off, and say which frames are " +
            "the walk. The original image is never overwritten.",
    ) {
        if (sheets.isEmpty()) {
            Text(
                text = "Nothing generated yet. Make a sprite in the forge first, however it " +
                    "turns out — a flawed sheet is what this screen is for.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            return@StratumSection
        }

        sheets.forEach { sheet ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Cut.small)
                    .clickable { onAction(SpriteMapperAction.Open(sheet)) }
                    .padding(vertical = Space.medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sheet.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${sheet.columns}x${sheet.rows} · " +
                            "${sheet.clips.size} animation${if (sheet.clips.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }
                StratumAction(
                    label = "Map",
                    onClick = { onAction(SpriteMapperAction.Open(sheet)) },
                    emphasis = ActionEmphasis.SECONDARY,
                )
            }
            StratumDivider()
        }
    }
}

/** The whole image with the current grid drawn over it, which is how a grid gets checked. */
@Composable
private fun SourcePanel(
    atlas: SpriteAtlas,
    slice: SliceSpec?,
    squareCells: Boolean,
    source: ImageBitmap?,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors

    // The box being drawn, in source pixels. Held here rather than in the
    // view model because a half-finished drag is not a grid -- re-cutting the
    // sheet on every frame of a drag would be forty reslices and forty undo
    // entries for one gesture.
    var drawnBox by remember(atlas.id) { mutableStateOf<SourceRect?>(null) }

    StratumSection(
        title = "Source",
        subtitle = "${atlas.sourceWidth} x ${atlas.sourceHeight}, ${atlas.frames.size} cells",
        trailing = {
            StratumAction(
                label = "Undo",
                onClick = { onAction(SpriteMapperAction.Undo) },
                emphasis = ActionEmphasis.QUIET,
            )
        },
    ) {
        StratumWell {
            if (source == null) {
                Text(
                    text = "The source image could not be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            atlas.sourceWidth.toFloat() /
                                atlas.sourceHeight.coerceAtLeast(1).toFloat(),
                        )
                        .pointerInput(atlas.id, atlas.sourceWidth, squareCells) {
                            // Source pixels per screen pixel. Everything the
                            // drag reports is in screen space and every number
                            // the grid is made of is in source space, and
                            // mixing the two is how an editor ends up cutting
                            // a sheet at a size nobody asked for.
                            val perPixel =
                                atlas.sourceWidth.toFloat() / size.width.coerceAtLeast(1)
                            var start = Offset.Zero
                            var current = Offset.Zero

                            fun boxOf(): SourceRect {
                                val left = minOf(start.x, current.x) * perPixel
                                val top = minOf(start.y, current.y) * perPixel
                                val right = maxOf(start.x, current.x) * perPixel
                                val bottom = maxOf(start.y, current.y) * perPixel
                                return SourceRect(
                                    left = left.toInt().coerceAtLeast(0),
                                    top = top.toInt().coerceAtLeast(0),
                                    width = (right - left).toInt().coerceAtLeast(1),
                                    height = (bottom - top).toInt().coerceAtLeast(1),
                                )
                            }

                            // After a long press, not immediately. The panel
                            // scrolls, the image is most of it, and a canvas
                            // that swallows every drag is a screen that cannot
                            // be scrolled past. Holding first says "I mean the
                            // sheet, not the page".
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    start = it
                                    current = it
                                    drawnBox = null
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = change.position
                                    drawnBox = boxOf()
                                },
                                onDragEnd = {
                                    drawnBox?.let { onAction(SpriteMapperAction.SetGridFromBox(it)) }
                                    drawnBox = null
                                },
                                onDragCancel = { drawnBox = null },
                            )
                        },
                ) {
                    val scale = size.width / atlas.sourceWidth.coerceAtLeast(1)
                    drawImage(
                        image = source,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(source.width, source.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.None,
                    )
                    if (slice != null) drawGrid(slice, scale, colors.accent)
                    drawnBox?.let { box ->
                        // Squared as it is drawn, not on release: a box that
                        // snaps to a different shape the moment you lift your
                        // finger is a box you cannot aim.
                        val side = minOf(box.width, box.height)
                        val w = if (squareCells) side else box.width
                        val h = if (squareCells) side else box.height
                        drawRect(
                            color = colors.accent,
                            topLeft = Offset(box.left * scale, box.top * scale),
                            size = Size(w * scale, h * scale),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2f),
                        )
                    }
                }
            }
        }
    }
}

/** The grid lines, drawn where the cells actually are rather than evenly across. */
private fun DrawScope.drawGrid(slice: SliceSpec, scale: Float, tint: Color) {
    val stroke = 1f.coerceAtLeast(scale * 0.5f)
    for (column in 0..slice.columns) {
        val x = (slice.offsetX + column * (slice.cellWidth + slice.gutterX)) * scale
        drawLine(tint.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), stroke)
    }
    for (row in 0..slice.rows) {
        val y = (slice.offsetY + row * (slice.cellHeight + slice.gutterY)) * scale
        drawLine(tint.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), stroke)
    }
}

/**
 * The six numbers that turn a wrong grid into a right one.
 *
 * Margin and gap are here, not only columns and rows, because the sheets that
 * need this screen most are the ones that are *almost* on a grid — evenly cut
 * but inset by a border the model drew, which no choice of column count will
 * ever fix.
 */
@Composable
private fun GridPanel(
    slice: SliceSpec?,
    squareCells: Boolean,
    onAction: (SpriteMapperAction) -> Unit,
) {
    if (slice == null) return

    StratumSection(
        title = "Grid",
        subtitle = "Cells are ${slice.cellWidth} x ${slice.cellHeight}",
        trailing = {
            StratumAction(
                label = "Trim",
                onClick = { onAction(SpriteMapperAction.TrimToContent) },
                emphasis = ActionEmphasis.SECONDARY,
            )
        },
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            Text(
                text = "Shape",
                style = MaterialTheme.typography.labelSmall,
                color = StratumTheme.colors.inkMuted,
            )
            StratumChip(
                label = "Square",
                selected = squareCells,
                onClick = { onAction(SpriteMapperAction.SetSquareCells(true)) },
            )
            StratumChip(
                label = "Free",
                selected = !squareCells,
                onClick = { onAction(SpriteMapperAction.SetSquareCells(false)) },
            )
        }

        Spacer(Modifier.height(Space.small))
        // Offered before the column count, because a person who knows their
        // cell size knows it exactly, and a person counting columns on a
        // twenty-one row sheet is guessing.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            Text(
                text = "Cells",
                style = MaterialTheme.typography.labelSmall,
                color = StratumTheme.colors.inkMuted,
            )
            CELL_SIZES.forEach { pixels ->
                StratumChip(
                    label = "${pixels}px",
                    selected = slice.cellWidth == pixels && slice.cellHeight == pixels,
                    onClick = { onAction(SpriteMapperAction.SetCellSize(pixels)) },
                )
            }
        }

        Spacer(Modifier.height(Space.small))
        // The chips cover the sizes art is usually sold in; this covers the
        // sizes it is actually drawn at. A sheet of 96 pixel cells has no chip
        // and is no less real, and without this the only way to reach it is to
        // guess a column count that happens to divide out.
        if (squareCells) {
            Stepper("Square size", slice.cellWidth, step = 2) {
                onAction(SpriteMapperAction.SetCellSize(it))
            }
        }
        Stepper("Columns", slice.columns) { onAction(SpriteMapperAction.SetGrid(columns = it)) }
        Stepper("Rows", slice.rows) { onAction(SpriteMapperAction.SetGrid(rows = it)) }
        Stepper("Margin across", slice.offsetX, step = 2) {
            onAction(SpriteMapperAction.SetGrid(offsetX = it))
        }
        Stepper("Margin down", slice.offsetY, step = 2) {
            onAction(SpriteMapperAction.SetGrid(offsetY = it))
        }
        Stepper("Gap across", slice.gutterX, step = 2) {
            onAction(SpriteMapperAction.SetGrid(gutterX = it))
        }
        Stepper("Gap down", slice.gutterY, step = 2) {
            onAction(SpriteMapperAction.SetGrid(gutterY = it))
        }

        Spacer(Modifier.height(Space.small))
        Text(
            text = "Press and hold on the sheet above, then drag a box around one frame to " +
                "set the grid by eye: where the box starts is where the grid starts, and how " +
                "big it is is how big a cell is. Trim shrinks every cell to what is drawn in it and switches the " +
                "blank ones off. It is also what makes the feet line up when the frames are " +
                "packed. For art no grid describes, open a frame and size it by hand.",
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
        )
    }
}

/** A number with a minus and a plus, sized for a thumb rather than a mouse. */
@Composable
private fun Stepper(label: String, value: Int, step: Int = 1, onChange: (Int) -> Unit) {
    val colors = StratumTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.tight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        StratumAction(
            label = "-",
            onClick = { onChange((value - step).coerceAtLeast(0)) },
            emphasis = ActionEmphasis.QUIET,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            modifier = Modifier
                .width(48.dp)
                .padding(horizontal = Space.small),
        )
        StratumAction(
            label = "+",
            onClick = { onChange(value + step) },
            emphasis = ActionEmphasis.QUIET,
        )
    }
}

/**
 * Every cell, as a thing to tap.
 *
 * One tap puts a frame into the state whose tab is open — that single gesture
 * is the whole editor, and everything else on this screen is in service of it.
 * The row of small controls under each cell is for the cell itself: off, flip,
 * anchor, copy.
 */
@Composable
private fun ContactSheet(
    atlas: SpriteAtlas,
    state: SpriteMapperUiState,
    source: ImageBitmap?,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors

    StratumSection(
        title = "Frames",
        subtitle = "Tap a frame to add it to ${SpriteValidation.name(state.activeState)}. " +
            "${state.usableFrames} of ${atlas.frames.size} switched on.",
    ) {
        atlas.frames.chunked(FRAMES_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.tight),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                row.forEach { frame ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FrameThumb(
                            frame = frame,
                            atlas = atlas,
                            source = source,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable(enabled = frame.enabled) {
                                    onAction(SpriteMapperAction.AddToClip(frame.id))
                                },
                        )
                        Text(
                            text = frame.label.ifBlank { frame.id },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (frame.enabled) colors.inkMuted else colors.hairline,
                            maxLines = 1,
                        )
                        // Two, not five. Everything else a single frame needs
                        // is in the large view, where there is room to see what
                        // it did.
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.hair)) {
                            TinyButton(if (frame.enabled) "off" else "on") {
                                onAction(SpriteMapperAction.ToggleFrame(frame.id))
                            }
                            TinyButton("edit") {
                                onAction(SpriteMapperAction.OpenFrame(frame.id))
                            }
                        }
                    }
                }
                // Keeps a short last row aligned with the ones above it.
                repeat(FRAMES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One cell of the source, drawn at whatever size it is given. */
@Composable
private fun FrameThumb(
    frame: FrameRef,
    atlas: SpriteAtlas,
    source: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = StratumTheme.colors
    Box(
        modifier = modifier
            .clip(Cut.tiny)
            .background(colors.surfaceSunken)
            .border(
                Stroke.hairline,
                if (frame.enabled) colors.hairline else colors.danger.copy(alpha = 0.5f),
                Cut.tiny,
            ),
    ) {
        if (source == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val rect = frame.source.clampedTo(atlas.sourceWidth, atlas.sourceHeight)
                ?: return@Canvas
            val draw: DrawScope.() -> Unit = {
                drawImage(
                    image = source,
                    srcOffset = IntOffset(rect.left, rect.top),
                    srcSize = IntSize(rect.width, rect.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None,
                    alpha = if (frame.enabled) 1f else 0.3f,
                )
            }
            // Shown flipped because that is how it will be baked; a thumbnail
            // that disagrees with the output is worse than no thumbnail.
            if (frame.flippedX) {
                withTransform({ scale(-1f, 1f, Offset(size.width / 2f, size.height / 2f)) }) { draw() }
            } else {
                draw()
            }
        }
    }
}

@Composable
private fun TinyButton(label: String, onClick: () -> Unit) {
    val colors = StratumTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.inkMuted,
        modifier = Modifier
            .clip(Cut.tiny)
            .border(Stroke.hairline, colors.hairline, Cut.tiny)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.small, vertical = Space.tight),
    )
}

/** The state tabs, the frames mapped to the open one, and what it looks like moving. */
@Composable
private fun ClipPanel(
    state: SpriteMapperUiState,
    source: ImageBitmap?,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors
    val atlas = state.atlas ?: return
    val clip = state.activeClip
    val frames = state.activeFrames

    StratumSection(
        title = "Animations",
        subtitle = "${atlas.mappedStates.size} of ${AnimationState.entries.size} states mapped",
        trailing = {
            StratumAction(
                label = "Fill gaps",
                onClick = { onAction(SpriteMapperAction.FillFallbacks) },
                emphasis = ActionEmphasis.QUIET,
            )
        },
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            AnimationState.entries.forEach { animation ->
                StratumChip(
                    label = SpriteValidation.name(animation) +
                        if (animation in atlas.mappedStates) "" else " ·",
                    selected = animation == state.activeState,
                    onClick = { onAction(SpriteMapperAction.SelectState(animation)) },
                )
            }
        }

        Spacer(Modifier.height(Space.medium))

        if (frames.isEmpty()) {
            Text(
                text = "No frames yet. Tap the cells above, in the order they should play.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.small),
            ) {
                frames.forEachIndexed { index, frame ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FrameThumb(
                            frame = frame,
                            atlas = atlas,
                            source = source,
                            modifier = Modifier.size(56.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.hair)) {
                            TinyButton("<") {
                                onAction(SpriteMapperAction.MoveInClip(index, index - 1))
                            }
                            TinyButton("x") {
                                onAction(SpriteMapperAction.RemoveFromClip(index))
                            }
                            TinyButton(">") {
                                onAction(SpriteMapperAction.MoveInClip(index, index + 1))
                            }
                        }
                        // The frame that looks wrong in a preview is the one to
                        // open, and this is where a person is looking when they
                        // notice it.
                        TinyButton("edit") { onAction(SpriteMapperAction.OpenFrame(frame.id)) }
                    }
                }
            }

            Spacer(Modifier.height(Space.medium))
            ClipPreview(frames, atlas, source, clip?.frameDurationMs ?: 120, clip?.loops ?: true)

            Spacer(Modifier.height(Space.medium))
            Text(
                text = "Speed: ${"%.0f".format(clip?.fps ?: 8f)} fps",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            Slider(
                value = clip?.fps ?: 8f,
                onValueChange = { onAction(SpriteMapperAction.SetFps(it)) },
                valueRange = SpriteMapperViewModel.MIN_FPS..SpriteMapperViewModel.MAX_FPS,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumChip(
                    label = if (clip?.loops == true) "Loops" else "Plays once",
                    selected = clip?.loops == true,
                    onClick = { onAction(SpriteMapperAction.ToggleLoops) },
                )
                StratumAction(
                    label = "Clear",
                    onClick = { onAction(SpriteMapperAction.ClearClip) },
                    emphasis = ActionEmphasis.QUIET,
                )
            }
        }
    }
}

/**
 * The clip, playing.
 *
 * The one control that settles arguments. A strip of thumbnails cannot tell
 * anybody whether a walk reads as walking; four frames a second on the actual
 * art settles it in two seconds, which is why this is not tucked behind a
 * button.
 */
@Composable
private fun ClipPreview(
    frames: List<FrameRef>,
    atlas: SpriteAtlas,
    source: ImageBitmap?,
    frameDurationMs: Int,
    loops: Boolean,
) {
    var elapsed by remember(frames, frameDurationMs) { mutableLongStateOf(0L) }

    LaunchedEffect(frames, frameDurationMs, loops) {
        val started = System.currentTimeMillis()
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            elapsed = System.currentTimeMillis() - started
        }
    }

    val index = if (frames.isEmpty()) {
        0
    } else {
        val step = (elapsed / frameDurationMs.coerceAtLeast(1)).toInt()
        // A one-shot preview holds its last pose and then starts over after a
        // beat, so a death can be watched twice without leaving the screen.
        if (loops) step % frames.size else (step % (frames.size + HOLD_FRAMES)).coerceAtMost(frames.size - 1)
    }

    StratumWell {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            val frame = frames.getOrNull(index)
            if (frame != null && source != null) {
                FrameThumb(
                    frame = frame,
                    atlas = atlas,
                    source = source,
                    modifier = Modifier.size(PREVIEW_HEIGHT),
                )
            }
        }
    }
}

/** What is wrong, in the order a person should deal with it. */
@Composable
private fun ReportPanel(report: SpriteValidationReport?, onAction: (SpriteMapperAction) -> Unit) {
    if (report == null) return
    val colors = StratumTheme.colors

    StratumSection(
        title = when (report.severity) {
            ValidationSeverity.READY -> "Ready"
            ValidationSeverity.WARNING -> "Playable, with gaps"
            ValidationSeverity.BLOCKED -> "Not playable yet"
        },
        subtitle = report.usableStates
            .sortedBy { it.ordinal }
            .joinToString { SpriteValidation.name(it) }
            .ifBlank { "Nothing mapped yet" },
        trailing = if (report.fillableGaps) {
            {
                StratumAction(
                    label = "Fill",
                    onClick = { onAction(SpriteMapperAction.FillFallbacks) },
                    emphasis = ActionEmphasis.SECONDARY,
                )
            }
        } else {
            null
        },
    ) {
        if (report.messages.isEmpty()) {
            Text(
                text = "Every state this is for has real frames behind it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            return@StratumSection
        }
        report.messages.forEach { message ->
            Row(modifier = Modifier.padding(vertical = Space.tight)) {
                Box(
                    Modifier
                        .padding(top = Space.tight)
                        .size(Space.small)
                        .background(
                            when (message.severity) {
                                ValidationSeverity.BLOCKED -> colors.danger
                                ValidationSeverity.WARNING -> colors.accentAlt
                                ValidationSeverity.READY -> colors.accent
                            },
                        ),
                )
                Spacer(Modifier.width(Space.small))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink,
                )
            }
        }
    }
}

/** What the asset is for, and the two ways out of the screen. */
@Composable
private fun AssetPanel(
    atlas: SpriteAtlas,
    state: SpriteMapperUiState,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors
    val plan = state.plannedSheet

    StratumSection(title = "Save") {
        OutlinedTextField(
            value = atlas.name,
            onValueChange = { onAction(SpriteMapperAction.SetName(it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.medium))
        Text(
            text = "Used as",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.tight))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            ActorRole.entries.forEach { role ->
                StratumChip(
                    label = role.label,
                    selected = role == atlas.role,
                    onClick = { onAction(SpriteMapperAction.SetRole(role)) },
                )
            }
        }

        Spacer(Modifier.height(Space.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            FacingLayout.entries.forEach { facing ->
                StratumChip(
                    label = facing.label,
                    selected = facing == atlas.facing,
                    onClick = { onAction(SpriteMapperAction.SetFacing(facing)) },
                )
            }
        }

        if (plan != null) {
            Spacer(Modifier.height(Space.medium))
            Text(
                text = "Packs to ${plan.sheetWidth} x ${plan.sheetHeight}, " +
                    "${plan.sheet.columns} x ${plan.sheet.rows} cells" +
                    if (plan.scale < 1f) ", scaled to fit a texture" else "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.height(Space.medium))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            StratumAction(
                label = "Save draft",
                onClick = { onAction(SpriteMapperAction.SaveDraft) },
                emphasis = ActionEmphasis.SECONDARY,
            )
            StratumAction(
                label = if (state.busy) "Packing" else "Save sheet",
                onClick = { onAction(SpriteMapperAction.Bake) },
                emphasis = ActionEmphasis.PRIMARY,
                enabled = state.canBake,
            )
        }
    }
}

@Composable
private fun Notice(
    message: String?,
    error: String?,
    onAction: (SpriteMapperAction) -> Unit,
) {
    if (message == null && error == null) return
    val colors = StratumTheme.colors

    StratumPanel(raised = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = error ?: message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) colors.danger else colors.ink,
                modifier = Modifier.weight(1f),
            )
            StratumAction(
                label = "OK",
                onClick = { onAction(SpriteMapperAction.DismissMessage) },
                emphasis = ActionEmphasis.QUIET,
            )
        }
    }
}

/**
 * The frame sizes sprite art is actually published at. 64 is the LPC standard
 * and by far the most common thing anyone imports.
 */
private val CELL_SIZES = listOf(16, 32, 48, 64, 96, 128)

/** Four across fits a phone; five makes the cells too small to judge. */
private const val FRAMES_PER_ROW = 4

/** How long a one-shot preview holds its last pose before starting again. */
private const val HOLD_FRAMES = 6

private val PREVIEW_HEIGHT = 140.dp
