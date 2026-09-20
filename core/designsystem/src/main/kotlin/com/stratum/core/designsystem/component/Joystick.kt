package com.stratum.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stratum.core.designsystem.theme.Stroke
import com.stratum.core.designsystem.theme.StratumTheme
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An analog thumbstick.
 *
 * Reports a vector in the unit circle: x east, y south, matching world axes so
 * the caller never has to translate. A direction pad cannot express "slightly
 * north-east", which is most of the input in an isometric game, and it forces
 * the thumb to hunt for four separate targets.
 */
@Composable
fun StratumJoystick(
    onDirection: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    val colors = StratumTheme.colors
    val callback by rememberUpdatedState(onDirection)

    var knob by remember { mutableStateOf(Offset.Zero) }
    var radiusPx by remember { mutableStateOf(1f) }

    Box(
        modifier = modifier
            .size(size)
            .onSizeChanged { radiusPx = (it.width / 2f).coerceAtLeast(1f) }
            .clip(CircleShape)
            .background(colors.surfaceSunken.copy(alpha = 0.72f))
            .border(Stroke.hairline, colors.hairline, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        knob = start - Offset(size.toPx() / 2f, size.toPx() / 2f)
                        emit(knob, radiusPx, callback)
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        // Clamp to the ring so the knob never leaves the base
                        // and the reported vector never exceeds one.
                        val moved = knob + delta
                        val length = sqrt(moved.x * moved.x + moved.y * moved.y)
                        knob = if (length > radiusPx) moved * (radiusPx / length) else moved
                        emit(knob, radiusPx, callback)
                    },
                    onDragEnd = {
                        knob = Offset.Zero
                        callback(0f, 0f)
                    },
                    onDragCancel = {
                        knob = Offset.Zero
                        callback(0f, 0f)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(size * KNOB_FRACTION)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.9f))
                .border(Stroke.edge, colors.ink.copy(alpha = 0.35f), CircleShape),
        )
    }
}

private fun emit(knob: Offset, radiusPx: Float, callback: (Float, Float) -> Unit) {
    callback(knob.x / radiusPx, knob.y / radiusPx)
}

private const val KNOB_FRACTION = 0.42f
