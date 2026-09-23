package com.stratum.tools.artpreview

import com.stratum.core.domain.art.TerrainStyle
import com.stratum.core.domain.art.Tint
import com.stratum.engine.render.FrameSink
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage

/**
 * Draws a frame into an image.
 *
 * The point of this class is not that it is fast, it is that it is *identical*:
 * it implements the same sink the phone does, from the same renderer, so a PNG
 * produced on a build machine is evidence about what the game looks like rather
 * than an artist's impression of it. Every constant that shapes the image comes
 * from the art direction, and none of them live here.
 */
class ImageFrameSink(
    val image: BufferedImage,
) : FrameSink {

    private val graphics = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private val path = Path2D.Float()
    private val ellipse = Ellipse2D.Float()

    override fun backdrop(top: Long, bottom: Long) {
        graphics.paint = GradientPaint(
            0f, 0f, top.toAwt(),
            0f, image.height.toFloat(), bottom.toAwt(),
        )
        graphics.fillRect(0, 0, image.width, image.height)
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
        fillQuad(
            centerX - halfWidth, centerY,
            centerX, centerY + halfHeight,
            centerX, centerY + halfHeight + lift,
            centerX - halfWidth, centerY + lift,
            style.left,
        )
        fillQuad(
            centerX + halfWidth, centerY,
            centerX, centerY + halfHeight,
            centerX, centerY + halfHeight + lift,
            centerX + halfWidth, centerY + lift,
            style.right,
        )
        topFace(centerX, centerY, halfWidth, halfHeight)
        graphics.color = style.top.toAwt()
        graphics.fill(path)

        if (Tint.alpha(style.occlusion) > 0) {
            graphics.color = style.occlusion.toAwt()
            graphics.fill(path)
        }
        if (Tint.alpha(style.seam) > 0) {
            graphics.color = style.seam.toAwt()
            graphics.stroke = BasicStroke(1f)
            graphics.draw(path)
        }
        if (Tint.alpha(style.edge) > 0 && style.edgeWidth > 0f) {
            graphics.color = style.edge.toAwt()
            graphics.stroke = BasicStroke(style.edgeWidth)
            graphics.draw(path)
        }
        if (Tint.alpha(highlight) > 0) {
            graphics.color = highlight.toAwt()
            graphics.stroke = BasicStroke(3f)
            graphics.draw(path)
        }
    }

    override fun polygon(points: FloatArray, count: Int, fill: Long, outline: Long, outlineWidth: Float) {
        val used = minOf(count, points.size)
        if (used < 6) return
        path.reset()
        path.moveTo(points[0], points[1])
        var index = 2
        while (index + 1 < used) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        path.closePath()

        if (Tint.alpha(fill) > 0) {
            graphics.color = fill.toAwt()
            graphics.fill(path)
        }
        if (outlineWidth > 0f && Tint.alpha(outline) > 0) {
            graphics.color = outline.toAwt()
            graphics.stroke = BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.draw(path)
        }
    }

    override fun ellipse(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float, fill: Long) {
        if (Tint.alpha(fill) == 0) return
        ellipse.setFrame(
            (centerX - radiusX).toDouble(),
            (centerY - radiusY).toDouble(),
            (radiusX * 2f).toDouble(),
            (radiusY * 2f).toDouble(),
        )
        graphics.color = fill.toAwt()
        graphics.fill(ellipse)
    }

    override fun ring(centerX: Float, centerY: Float, radius: Float, color: Long, width: Float) {
        if (Tint.alpha(color) == 0 || width <= 0f) return
        ellipse.setFrame(
            (centerX - radius).toDouble(),
            (centerY - radius).toDouble(),
            (radius * 2f).toDouble(),
            (radius * 2f).toDouble(),
        )
        graphics.color = color.toAwt()
        graphics.stroke = BasicStroke(width)
        graphics.draw(ellipse)
    }

    override fun glow(centerX: Float, centerY: Float, radius: Float, color: Long) {
        if (Tint.alpha(color) == 0 || radius <= 0.5f) return
        graphics.paint = RadialGradientPaint(
            Point2D.Float(centerX, centerY),
            radius,
            floatArrayOf(0f, 1f),
            arrayOf(color.toAwt(), Color(color.toAwt().red, color.toAwt().green, color.toAwt().blue, 0)),
        )
        graphics.fill(
            Ellipse2D.Float(centerX - radius, centerY - radius, radius * 2f, radius * 2f),
        )
    }

    override fun wash(color: Long) {
        if (Tint.alpha(color) == 0) return
        graphics.color = color.toAwt()
        graphics.fillRect(0, 0, image.width, image.height)
    }

    /**
     * Corner darkening, as four edge gradients rather than a radial one.
     *
     * A radial gradient over the whole frame is a large paint operation for a
     * subtle effect, and on a wide viewport it dims the middle of the top and
     * bottom edges more than it should. Four linear passes cost less and land
     * the darkness where the eye expects it.
     */
    override fun vignette(strength: Float, color: Long) {
        if (strength <= 0f) return
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        val depth = minOf(width, height) * VIGNETTE_DEPTH
        val solid = Tint.withAlpha(color, strength.coerceIn(0f, 1f)).toAwt()
        val clear = Color(solid.red, solid.green, solid.blue, 0)

        graphics.composite = AlphaComposite.SrcOver
        graphics.paint = GradientPaint(0f, 0f, solid, 0f, depth, clear)
        graphics.fillRect(0, 0, image.width, depth.toInt())
        graphics.paint = GradientPaint(0f, height, solid, 0f, height - depth, clear)
        graphics.fillRect(0, (height - depth).toInt(), image.width, depth.toInt() + 1)
        graphics.paint = GradientPaint(0f, 0f, solid, depth, 0f, clear)
        graphics.fillRect(0, 0, depth.toInt(), image.height)
        graphics.paint = GradientPaint(width, 0f, solid, width - depth, 0f, clear)
        graphics.fillRect((width - depth).toInt(), 0, depth.toInt() + 1, image.height)
    }

    fun dispose() = graphics.dispose()

    private fun topFace(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        path.reset()
        path.moveTo(centerX, centerY - halfHeight)
        path.lineTo(centerX + halfWidth, centerY)
        path.lineTo(centerX, centerY + halfHeight)
        path.lineTo(centerX - halfWidth, centerY)
        path.closePath()
    }

    private fun fillQuad(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        x4: Float, y4: Float,
        color: Long,
    ) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.lineTo(x4, y4)
        path.closePath()
        graphics.color = color.toAwt()
        graphics.fill(path)
    }

    private companion object {
        const val VIGNETTE_DEPTH = 0.42f
    }
}

/** Packed `0xAARRGGBB` to the colour AWT wants. */
internal fun Long.toAwt(): Color = Color(
    Tint.red(this),
    Tint.green(this),
    Tint.blue(this),
    Tint.alpha(this),
)
