package com.stratum.feature.forge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.component.ActionEmphasis
import com.stratum.core.designsystem.component.SectionLabel
import com.stratum.core.designsystem.component.StratumAction
import com.stratum.core.designsystem.component.StratumChip
import com.stratum.core.designsystem.component.StratumWell
import com.stratum.core.designsystem.theme.Space
import com.stratum.core.designsystem.theme.StratumTheme
import com.stratum.core.designsystem.theme.safeContent
import com.stratum.core.domain.sprite.FramePivot
import com.stratum.core.domain.sprite.FrameRef
import com.stratum.core.domain.sprite.SourceRect
import com.stratum.core.domain.sprite.SpriteAtlas
import com.stratum.core.domain.sprite.SpriteValidation
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * One frame, as large as the screen will allow.
 *
 * The contact sheet answers "which cells are art". It cannot answer "is this
 * one cut right", because at four across on a phone a cell is sixty pixels and
 * a clipped hand is two of them. This is where that question gets answered: one
 * frame filling the view, zoomed to whatever size it happens to be, with the
 * rest of the image visible but dimmed around it so the frame can be judged
 * against what it was cut out of.
 *
 * The loop it is built for is next, fix, next. A person working through a
 * sixteen-cell sheet should never have to go back to a list, find the cell they
 * were on, and tap into it again — so the view refits itself to each frame as
 * it arrives, and the two buttons that matter are the largest things on screen.
 */
@Composable
fun FrameStudio(
    state: SpriteMapperUiState,
    source: ImageBitmap?,
    modifier: Modifier = Modifier,
    onAction: (SpriteMapperAction) -> Unit = {},
) {
    val colors = StratumTheme.colors
    val atlas = state.atlas ?: return
    val frame = state.focusFrame ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .safeContent()
            .padding(Space.large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                SectionLabel("Frame ${state.focusIndex + 1} of ${state.frameCount}")
                Text(
                    text = "${frame.source.width} x ${frame.source.height} " +
                        "at ${frame.source.left}, ${frame.source.top}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                StratumAction(
                    label = "Undo",
                    onClick = { onAction(SpriteMapperAction.Undo) },
                    emphasis = ActionEmphasis.QUIET,
                )
                StratumAction(
                    label = "Done",
                    onClick = { onAction(SpriteMapperAction.CloseFrame) },
                    emphasis = ActionEmphasis.QUIET,
                )
            }
        }

        val notice = state.error ?: state.message
        if (notice != null) {
            Spacer(Modifier.height(Space.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.error != null) colors.danger else colors.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                StratumAction(
                    label = "OK",
                    onClick = { onAction(SpriteMapperAction.DismissMessage) },
                    emphasis = ActionEmphasis.QUIET,
                )
            }
        }

        Spacer(Modifier.height(Space.medium))

        FrameStage(
            atlas = atlas,
            frame = frame,
            source = source,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onMove = { dx, dy -> onAction(SpriteMapperAction.NudgeFrame(dx, dy)) },
        )

        Spacer(Modifier.height(Space.medium))
        FrameControls(state, frame, onAction)

        Spacer(Modifier.height(Space.medium))

        // The two that carry the whole workflow, so they are the two that are
        // impossible to miss and impossible to mis-tap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            StratumAction(
                label = "Previous",
                onClick = { onAction(SpriteMapperAction.StepFrame(forward = false)) },
                emphasis = ActionEmphasis.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            StratumAction(
                label = "Next",
                onClick = { onAction(SpriteMapperAction.StepFrame(forward = true)) },
                emphasis = ActionEmphasis.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The frame at whatever magnification it takes to fill the view.
 *
 * "Resize accordingly" in the literal sense: the zoom is derived from the
 * frame's own size every time, so a 32-pixel cell and a 400-pixel one are both
 * worked on at the same apparent size. A fixed zoom would make the small ones
 * unusable and the large ones impossible to see whole.
 */
@Composable
private fun FrameStage(
    atlas: SpriteAtlas,
    frame: FrameRef,
    source: ImageBitmap?,
    modifier: Modifier = Modifier,
    onMove: (Int, Int) -> Unit = { _, _ -> },
) {
    val colors = StratumTheme.colors

    StratumWell(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (source == null) {
                Text(
                    text = "The source image could not be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                return@BoxWithConstraints
            }

            val density = LocalDensity.current
            val viewWidth = with(density) { maxWidth.toPx() }
            val viewHeight = with(density) { maxHeight.toPx() }
            val rect = frame.source
            val scale = (
                min(
                    viewWidth / rect.width.coerceAtLeast(1),
                    viewHeight / rect.height.coerceAtLeast(1),
                ) * FILL_FRACTION
                )
                // A two-pixel frame would otherwise ask the rasteriser to draw
                // the whole sheet several hundred thousand pixels wide. Past
                // this there is nothing more to see anyway: the pixels are
                // already the size of a fingertip.
                .coerceIn(MIN_SCALE, MAX_SCALE)

            // Dragging works in screen pixels and the frame lives in image
            // pixels, and at eight times magnification a whole drag can be less
            // than one image pixel. The remainder is carried rather than
            // dropped, so a slow drag still moves instead of doing nothing.
            var carriedX by remember(frame.id) { mutableFloatStateOf(0f) }
            var carriedY by remember(frame.id) { mutableFloatStateOf(0f) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(frame.id, scale) {
                        detectDragGestures(
                            onDragEnd = { carriedX = 0f; carriedY = 0f },
                        ) { change, delta ->
                            change.consume()
                            carriedX += delta.x / scale
                            carriedY += delta.y / scale
                            val dx = carriedX.toInt()
                            val dy = carriedY.toInt()
                            if (dx != 0 || dy != 0) {
                                carriedX -= dx
                                carriedY -= dy
                                onMove(dx, dy)
                            }
                        }
                    },
            ) {
                val originX = size.width / 2f - (rect.left + rect.width / 2f) * scale
                val originY = size.height / 2f - (rect.top + rect.height / 2f) * scale

                // The whole sheet, faint. Cutting the frame out of its
                // surroundings entirely is what makes a grid look right when it
                // is a few pixels off -- the neighbours are the evidence.
                drawImage(
                    image = source,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(source.width, source.height),
                    dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                    dstSize = IntSize(
                        (atlas.sourceWidth * scale).roundToInt(),
                        (atlas.sourceHeight * scale).roundToInt(),
                    ),
                    filterQuality = FilterQuality.None,
                    alpha = SURROUNDS_ALPHA,
                )

                val clipped = rect.clampedTo(atlas.sourceWidth, atlas.sourceHeight)
                val frameLeft = originX + rect.left * scale
                val frameTop = originY + rect.top * scale
                val frameWidth = rect.width * scale
                val frameHeight = rect.height * scale

                if (clipped != null) {
                    drawImage(
                        image = source,
                        srcOffset = IntOffset(clipped.left, clipped.top),
                        srcSize = IntSize(clipped.width, clipped.height),
                        dstOffset = IntOffset(
                            (originX + clipped.left * scale).roundToInt(),
                            (originY + clipped.top * scale).roundToInt(),
                        ),
                        dstSize = IntSize(
                            (clipped.width * scale).roundToInt(),
                            (clipped.height * scale).roundToInt(),
                        ),
                        filterQuality = FilterQuality.None,
                        alpha = if (frame.enabled) 1f else DISABLED_ALPHA,
                    )
                }

                drawRect(
                    color = if (frame.enabled) colors.accent else colors.danger,
                    topLeft = Offset(frameLeft, frameTop),
                    size = Size(frameWidth, frameHeight),
                    style = Stroke(width = BORDER_WIDTH),
                )

                // The anchor, drawn where the sprite will be hung from. Without
                // it the pivot is an abstraction in a menu; with it, a person
                // can see that the feet are on the line.
                drawAnchor(
                    pivot = frame.pivot,
                    left = frameLeft,
                    top = frameTop,
                    width = frameWidth,
                    height = frameHeight,
                    tint = colors.accentAlt,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnchor(
    pivot: FramePivot,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    tint: Color,
) {
    val anchorX = left + width * pivot.xFraction
    val anchorY = top + height * pivot.yFraction
    drawLine(
        color = tint,
        start = Offset(left, anchorY),
        end = Offset(left + width, anchorY),
        strokeWidth = ANCHOR_WIDTH,
    )
    drawLine(
        color = tint.copy(alpha = 0.5f),
        start = Offset(anchorX, top),
        end = Offset(anchorX, top + height),
        strokeWidth = ANCHOR_WIDTH,
    )
}

/**
 * Buttons rather than drag handles.
 *
 * A drag handle on a touch screen is covered by the finger using it, which is
 * fine for a rough crop and useless for deciding whether an edge is on the
 * outline or one pixel inside it. A button moves an edge by a known amount and
 * leaves the pixels visible while it does.
 */
@Composable
private fun FrameControls(
    state: SpriteMapperUiState,
    frame: FrameRef,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors
    val step = state.step

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Step",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                STEPS.forEach { pixels ->
                    StratumChip(
                        label = "${pixels}px",
                        selected = pixels == step,
                        onClick = { onAction(SpriteMapperAction.SetStep(pixels)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.small))

        // Each row is one edge, so "the left edge is too far in" has one
        // obvious place to go. Outward first, because widening a frame that
        // clips the art is the overwhelmingly common repair.
        EdgeRow("Left", { onAction(SpriteMapperAction.ResizeFrame(left = step)) }) {
            onAction(SpriteMapperAction.ResizeFrame(left = -step))
        }
        EdgeRow("Top", { onAction(SpriteMapperAction.ResizeFrame(top = step)) }) {
            onAction(SpriteMapperAction.ResizeFrame(top = -step))
        }
        EdgeRow("Right", { onAction(SpriteMapperAction.ResizeFrame(right = step)) }) {
            onAction(SpriteMapperAction.ResizeFrame(right = -step))
        }
        EdgeRow("Bottom", { onAction(SpriteMapperAction.ResizeFrame(bottom = step)) }) {
            onAction(SpriteMapperAction.ResizeFrame(bottom = -step))
        }

        // Typed, not only nudged. Stepping to an exact size by tapping a plus
        // is fine for a pixel or two and absurd for a frame that needs to be
        // 96 wide when it is 137: it is forty taps, and a person who knows the
        // number they want should be able to say it.
        Spacer(Modifier.height(Space.small))
        DimensionFields(frame.source, onAction)

        Spacer(Modifier.height(Space.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Move",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            StratumAction("←", { onAction(SpriteMapperAction.NudgeFrame(-step, 0)) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("↑", { onAction(SpriteMapperAction.NudgeFrame(0, -step)) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("↓", { onAction(SpriteMapperAction.NudgeFrame(0, step)) }, emphasis = ActionEmphasis.QUIET)
            StratumAction("→", { onAction(SpriteMapperAction.NudgeFrame(step, 0)) }, emphasis = ActionEmphasis.QUIET)
        }

        Spacer(Modifier.height(Space.small))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.small),
        ) {
            StratumAction(
                label = "Snap",
                onClick = { onAction(SpriteMapperAction.SnapFrameToContent) },
                emphasis = ActionEmphasis.SECONDARY,
            )
            StratumAction(
                label = if (frame.enabled) "Switch off" else "Switch on",
                onClick = { onAction(SpriteMapperAction.ToggleFrame(frame.id)) },
                emphasis = ActionEmphasis.QUIET,
            )
            StratumAction(
                label = if (frame.flippedX) "Unflip" else "Flip",
                onClick = { onAction(SpriteMapperAction.FlipFrame(frame.id)) },
                emphasis = ActionEmphasis.QUIET,
            )
            StratumAction(
                label = "Anchor: ${frame.pivot.label}",
                onClick = { onAction(SpriteMapperAction.CyclePivot(frame.id)) },
                emphasis = ActionEmphasis.QUIET,
            )
            StratumAction(
                label = "Copy",
                onClick = { onAction(SpriteMapperAction.DuplicateFrame(frame.id)) },
                emphasis = ActionEmphasis.QUIET,
            )
            StratumAction(
                label = "New frame",
                onClick = { onAction(SpriteMapperAction.AddFrame) },
                emphasis = ActionEmphasis.QUIET,
            )
            StratumAction(
                label = "Delete",
                onClick = { onAction(SpriteMapperAction.RemoveFrame(frame.id)) },
                emphasis = ActionEmphasis.DESTRUCTIVE,
            )
        }

        Spacer(Modifier.height(Space.small))
        StratumAction(
            label = "Add to ${SpriteValidation.name(state.activeState)}",
            onClick = { onAction(SpriteMapperAction.AddToClip(frame.id)) },
            emphasis = ActionEmphasis.SECONDARY,
            enabled = frame.enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EdgeRow(label: String, onOut: () -> Unit, onIn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.hair),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = StratumTheme.colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        StratumAction("out", onOut, emphasis = ActionEmphasis.QUIET)
        StratumAction("in", onIn, emphasis = ActionEmphasis.QUIET)
    }
}

/** Steps a person actually wants: a pixel, a nudge, and a jump. */
private val STEPS = listOf(1, 4, 16)

/** How much of the view the frame fills, leaving its surroundings visible. */
private const val FILL_FRACTION = 0.78f

/** Magnification bounds: enough to see a pixel, not enough to melt the rasteriser. */
private const val MIN_SCALE = 0.02f
private const val MAX_SCALE = 48f
private const val SURROUNDS_ALPHA = 0.28f
private const val DISABLED_ALPHA = 0.35f
private const val BORDER_WIDTH = 3f
private const val ANCHOR_WIDTH = 2f

/**
 * The frame's rectangle as four numbers a person can type into.
 *
 * Held as text rather than as the rect's own integers while being edited. A
 * field bound straight to the value fights whoever is typing: clearing it to
 * retype reads as zero, so the frame collapses under the cursor and the digit
 * that follows applies to a rectangle that no longer exists. Text is only
 * turned back into a rectangle when it parses to something usable.
 */
@Composable
private fun DimensionFields(
    rect: SourceRect,
    onAction: (SpriteMapperAction) -> Unit,
) {
    val colors = StratumTheme.colors
    // Re-seeded whenever the rect changes underneath -- a nudge, a snap, an
    // undo -- so the fields follow the frame rather than showing a stale
    // number the person did not type.
    var left by remember(rect) { mutableStateOf(rect.left.toString()) }
    var top by remember(rect) { mutableStateOf(rect.top.toString()) }
    var width by remember(rect) { mutableStateOf(rect.width.toString()) }
    var height by remember(rect) { mutableStateOf(rect.height.toString()) }

    fun apply() {
        val next = SourceRect(
            left = left.toIntOrNull() ?: rect.left,
            top = top.toIntOrNull() ?: rect.top,
            width = width.toIntOrNull() ?: rect.width,
            height = height.toIntOrNull() ?: rect.height,
        )
        if (next != rect) onAction(SpriteMapperAction.SetFrameRect(next))
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Size and position",
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            NumberField("W", width, Modifier.weight(1f), { width = it }, ::apply)
            NumberField("H", height, Modifier.weight(1f), { height = it }, ::apply)
        }
        Spacer(Modifier.height(Space.small))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            NumberField("X", left, Modifier.weight(1f), { left = it }, ::apply)
            NumberField("Y", top, Modifier.weight(1f), { top = it }, ::apply)
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        // Digits only, filtered here rather than rejected after the fact: a
        // field that accepts a letter and then refuses to do anything is a
        // field that looks broken.
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(5)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        // Applied on Done as well as on losing focus, because on a phone the
        // keyboard is usually dismissed rather than tabbed away from.
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
    )
    LaunchedEffect(value) { onCommit() }
}
