package com.stratum.feature.play

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stratum.core.domain.art.TerrainStyle
import com.stratum.core.domain.art.Tint
import com.stratum.engine.render.FrameSink

/**
 * Draws a frame onto a Compose canvas.
 *
 * Everything this class knows is how to put a shape down. It has no opinion
 * about colour, weight, lighting or atmosphere, and it cannot see the world at
 * all — which is the point, because the identical frame is drawn by an image
 * rasteriser on a build machine and the two must not be able to disagree.
 *
 * Paths are held and rewound rather than built per call. A frame is tens of
 * thousands of primitives and a `Path` per primitive per frame was, measurably,
 * more work for the collector than the drawing was for the GPU.
 */
internal class ComposeFrameSink(private val scope: DrawScope) : FrameSink {

    private val top = Path()
    private val left = Path()
    private val right = Path()
    private val shape = Path()

    override fun backdrop(top: Long, bottom: Long) {
        scope.drawRect(
            brush = Brush.verticalGradient(listOf(Color(top), Color(bottom))),
            size = scope.size,
        )
    }

    override fun cube(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        lift: Float,
        style: TerrainStyle,
        highlight: Long,
    ) {
        top.rewind()
        top.moveTo(centerX, centerY - halfHeight)
        top.lineTo(centerX + halfWidth, centerY)
        top.lineTo(centerX, centerY + halfHeight)
        top.lineTo(centerX - halfWidth, centerY)
        top.close()

        left.rewind()
        left.moveTo(centerX - halfWidth, centerY)
        left.lineTo(centerX, centerY + halfHeight)
        left.lineTo(centerX, centerY + halfHeight + lift)
        left.lineTo(centerX - halfWidth, centerY + lift)
        left.close()

        right.rewind()
        right.moveTo(centerX + halfWidth, centerY)
        right.lineTo(centerX, centerY + halfHeight)
        right.lineTo(centerX, centerY + halfHeight + lift)
        right.lineTo(centerX + halfWidth, centerY + lift)
        right.close()

        scope.drawPath(left, Color(style.left))
        scope.drawPath(right, Color(style.right))
        scope.drawPath(top, Color(style.top))

        if (Tint.alpha(style.occlusion) > 0) scope.drawPath(top, Color(style.occlusion))
        if (Tint.alpha(style.seam) > 0) {
            scope.drawPath(top, Color(style.seam), style = Stroke(width = SEAM_WIDTH))
        }
        if (Tint.alpha(style.edge) > 0 && style.edgeWidth > 0f) {
            scope.drawPath(top, Color(style.edge), style = Stroke(width = style.edgeWidth))
        }
        if (Tint.alpha(highlight) > 0) {
            scope.drawPath(top, Color(highlight), style = Stroke(width = HIGHLIGHT_WIDTH))
        }
    }

    override fun polygon(points: FloatArray, count: Int, fill: Long, outline: Long, outlineWidth: Float) {
        val used = minOf(count, points.size)
        if (used < 6) return
        shape.rewind()
        shape.moveTo(points[0], points[1])
        var index = 2
        while (index + 1 < used) {
            shape.lineTo(points[index], points[index + 1])
            index += 2
        }
        shape.close()

        if (Tint.alpha(fill) > 0) scope.drawPath(shape, Color(fill))
        if (outlineWidth > 0f && Tint.alpha(outline) > 0) {
            scope.drawPath(shape, Color(outline), style = Stroke(width = outlineWidth))
        }
    }

    override fun ellipse(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float, fill: Long) {
        if (Tint.alpha(fill) == 0) return
        scope.drawOval(
            color = Color(fill),
            topLeft = Offset(centerX - radiusX, centerY - radiusY),
            size = Size(radiusX * 2f, radiusY * 2f),
        )
    }

    override fun ring(centerX: Float, centerY: Float, radius: Float, color: Long, width: Float) {
        if (Tint.alpha(color) == 0 || width <= 0f) return
        scope.drawCircle(Color(color), radius, Offset(centerX, centerY), style = Stroke(width = width))
    }

    override fun glow(centerX: Float, centerY: Float, radius: Float, color: Long) {
        if (Tint.alpha(color) == 0 || radius <= 0.5f) return
        scope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(color), Color(color).copy(alpha = 0f)),
                center = Offset(centerX, centerY),
                radius = radius,
            ),
            radius = radius,
            center = Offset(centerX, centerY),
        )
    }

    override fun wash(color: Long) {
        if (Tint.alpha(color) == 0) return
        scope.drawRect(Color(color), size = scope.size)
    }

    /**
     * Four edge gradients rather than one radial.
     *
     * A radial gradient the size of the viewport is an expensive shader for a
     * subtle effect, and on a phone held sideways it dims the middle of the
     * long edges far more than it should.
     */
    override fun vignette(strength: Float, color: Long) {
        if (strength <= 0f) return
        val width = scope.size.width
        val height = scope.size.height
        val depth = minOf(width, height) * VIGNETTE_DEPTH
        val solid = Color(Tint.withAlpha(color, strength.coerceIn(0f, 1f)))
        val clear = solid.copy(alpha = 0f)

        scope.drawRect(
            brush = Brush.verticalGradient(listOf(solid, clear), startY = 0f, endY = depth),
            size = Size(width, depth),
        )
        scope.drawRect(
            brush = Brush.verticalGradient(listOf(clear, solid), startY = height - depth, endY = height),
            topLeft = Offset(0f, height - depth),
            size = Size(width, depth),
        )
        scope.drawRect(
            brush = Brush.horizontalGradient(listOf(solid, clear), startX = 0f, endX = depth),
            size = Size(depth, height),
        )
        scope.drawRect(
            brush = Brush.horizontalGradient(listOf(clear, solid), startX = width - depth, endX = width),
            topLeft = Offset(width - depth, 0f),
            size = Size(depth, height),
        )
    }

    private companion object {
        const val SEAM_WIDTH = 1f
        const val HIGHLIGHT_WIDTH = 3f
        const val VIGNETTE_DEPTH = 0.42f
    }
}
