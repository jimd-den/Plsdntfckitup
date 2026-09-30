package com.stratum.engine.model.mask

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small anti-aliased vector painter: filled polygons, thick round-capped
 * strokes, and fills whose colour varies across the shape. Enough to draw
 * clean cartoon art -- flat fields, bold outlines, a soft sheen -- with no
 * platform graphics library, so the phone and the preview tools paint the
 * same pixels.
 *
 * Coordinates are logical units, y up; [left], [bottom], [unitsWide] and
 * [unitsHigh] map them onto [width] by [height] pixels. Coverage is exact
 * across each scanline and sampled [ROWS] times down each pixel, so edges
 * are smooth at any size. Colours are straight (not premultiplied) ARGB;
 * pixels are composited source-over.
 */
class VectorCanvas(
    val width: Int,
    val height: Int,
    private val left: Float,
    private val bottom: Float,
    private val unitsWide: Float,
    private val unitsHigh: Float,
) {
    private val r = FloatArray(width * height)
    private val g = FloatArray(width * height)
    private val b = FloatArray(width * height)
    private val a = FloatArray(width * height)
    private val cover = FloatArray(width * height)
    private val sx = width / unitsWide
    private val sy = height / unitsHigh

    /** Pixels per logical unit, across. */
    val pixelsPerUnit: Float get() = sx

    /** A colour at a point, for fills that are not flat; ARGB. */
    fun interface Paint { fun at(x: Float, y: Float): Int }

    fun fill(points: FloatArray, color: Int, opacity: Float = 1f) = fill(points, Paint { _, _ -> color }, opacity)

    /** Fills the polygon [points] (x, y pairs, closed implicitly) with [paint], non-zero winding. */
    fun fill(points: FloatArray, paint: Paint, opacity: Float = 1f) {
        val n = points.size / 2
        if (n < 3 || opacity <= 0f) return
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) {
            px[i] = (points[i * 2] - left) * sx
            py[i] = (bottom + unitsHigh - points[i * 2 + 1]) * sy
            minX = min(minX, px[i]); maxX = max(maxX, px[i]); minY = min(minY, py[i]); maxY = max(maxY, py[i])
        }
        val x0 = max(0, floor(minX).toInt()); val x1 = min(width - 1, ceil(maxX).toInt())
        val y0 = max(0, floor(minY).toInt()); val y1 = min(height - 1, ceil(maxY).toInt())
        if (x0 > x1 || y0 > y1) return
        val crossX = FloatArray(n); val crossW = IntArray(n)
        for (y in y0..y1) {
            for (x in x0..x1) cover[y * width + x] = 0f
            for (k in 0 until ROWS) {
                val sy0 = y + (k + 0.5f) / ROWS
                var c = 0
                for (i in 0 until n) {
                    val j = if (i + 1 == n) 0 else i + 1
                    val ya = py[i]; val yb = py[j]
                    if ((ya <= sy0 && yb > sy0) || (yb <= sy0 && ya > sy0)) {
                        val t = (sy0 - ya) / (yb - ya)
                        crossX[c] = px[i] + t * (px[j] - px[i]); crossW[c] = if (yb > ya) 1 else -1; c++
                    }
                }
                if (c < 2) continue
                // Insertion sort: a handful of crossings per row.
                for (i in 1 until c) {
                    val vx = crossX[i]; val vw = crossW[i]; var j = i - 1
                    while (j >= 0 && crossX[j] > vx) { crossX[j + 1] = crossX[j]; crossW[j + 1] = crossW[j]; j-- }
                    crossX[j + 1] = vx; crossW[j + 1] = vw
                }
                var wind = 0
                for (i in 0 until c - 1) {
                    wind += crossW[i]
                    if (wind != 0) span(y, crossX[i], crossX[i + 1], x0, x1)
                }
            }
            for (x in x0..x1) {
                val o = y * width + x
                val cv = cover[o]
                if (cv <= 0f) continue
                val lx = left + (x + 0.5f) / sx; val ly = bottom + unitsHigh - (y + 0.5f) / sy
                blend(o, paint.at(lx, ly), min(1f, cv) * opacity)
            }
        }
    }

    private fun span(y: Int, fromX: Float, toX: Float, x0: Int, x1: Int) {
        val a0 = max(fromX, x0.toFloat()); val a1 = min(toX, x1 + 1f)
        if (a1 <= a0) return
        val w = 1f / ROWS
        var x = floor(a0).toInt()
        val row = y * width
        while (x < a1 && x <= x1) {
            val lo = max(a0, x.toFloat()); val hi = min(a1, x + 1f)
            if (hi > lo) cover[row + x] += (hi - lo) * w
            x++
        }
    }

    private fun blend(o: Int, argb: Int, coverage: Float) {
        val sa = ((argb ushr 24) and 255) / 255f * coverage
        if (sa <= 0f) return
        val sr = ((argb shr 16) and 255) / 255f; val sg = ((argb shr 8) and 255) / 255f; val sb = (argb and 255) / 255f
        val da = a[o]
        val oa = sa + da * (1f - sa)
        if (oa <= 0f) return
        r[o] = (sr * sa + r[o] * da * (1f - sa)) / oa
        g[o] = (sg * sa + g[o] * da * (1f - sa)) / oa
        b[o] = (sb * sa + b[o] * da * (1f - sa)) / oa
        a[o] = oa
    }

    /** A thick line through [points] (open unless [closed]), with round joins and caps. */
    fun stroke(points: FloatArray, thickness: Float, color: Int, closed: Boolean = false, opacity: Float = 1f) {
        val n = points.size / 2
        if (n < 2) return
        val h = thickness / 2f
        // The whole stroke as one outline would self-intersect at every join; draw it into a mask first so overlaps do not double.
        val shapes = ArrayList<FloatArray>()
        val segments = if (closed) n else n - 1
        for (i in 0 until segments) {
            val j = (i + 1) % n
            val ax = points[i * 2]; val ay = points[i * 2 + 1]; val bx = points[j * 2]; val by = points[j * 2 + 1]
            var dx = bx - ax; var dy = by - ay
            val l = sqrt(dx * dx + dy * dy)
            if (l < 1e-6f) continue
            dx = dx / l * h; dy = dy / l * h
            shapes += floatArrayOf(ax - dy, ay + dx, bx - dy, by + dx, bx + dy, by - dx, ax + dy, ay - dx)
        }
        for (i in 0 until n) shapes += Shapes.ellipse(points[i * 2], points[i * 2 + 1], h, h, 12)
        union(shapes, Paint { _, _ -> color }, opacity)
    }

    /** Fills several polygons as one shape: where they overlap, painted once. */
    fun union(shapes: List<FloatArray>, paint: Paint, opacity: Float = 1f) {
        if (shapes.isEmpty()) return
        // Paint coverage into a scratch layer, take the most any shape gave each pixel, then composite once.
        val scratch = VectorCanvas(width, height, left, bottom, unitsWide, unitsHigh)
        for (s in shapes) scratch.fill(s, 0xFFFFFFFF.toInt())
        for (o in 0 until width * height) {
            val cv = scratch.a[o]
            if (cv <= 0f) continue
            val x = o % width; val y = o / width
            blend(o, paint.at(left + (x + 0.5f) / sx, bottom + unitsHigh - (y + 0.5f) / sy), cv * opacity)
        }
    }

    /** The picture as ARGB pixels, top row first. */
    fun pixels(): IntArray = IntArray(width * height) { o ->
        val al = a[o]
        if (al <= 0f) 0
        else ((al * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
            ((r[o] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g[o] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b[o] * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    companion object {
        /** Samples down each pixel. */
        const val ROWS = 4
    }
}

/** Polygon builders for [VectorCanvas]: every curve flattened into points. */
object Shapes {
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 40): FloatArray {
        val out = FloatArray(segments * 2)
        for (i in 0 until segments) {
            val t = i * 2.0 * Math.PI / segments
            out[i * 2] = cx + rx * cos(t).toFloat(); out[i * 2 + 1] = cy + ry * sin(t).toFloat()
        }
        return out
    }

    /** A rectangle with corners rounded by [radius]. */
    fun roundRect(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, segments: Int = 6): FloatArray {
        val rr = min(radius, min(abs(x1 - x0), abs(y1 - y0)) / 2f)
        val out = ArrayList<Float>()
        fun corner(cx: Float, cy: Float, start: Double) {
            for (i in 0..segments) {
                val t = start + i * Math.PI / 2 / segments
                out += cx + rr * cos(t).toFloat(); out += cy + rr * sin(t).toFloat()
            }
        }
        corner(x1 - rr, y0 + rr, -Math.PI / 2)
        corner(x1 - rr, y1 - rr, 0.0)
        corner(x0 + rr, y1 - rr, Math.PI / 2)
        corner(x0 + rr, y0 + rr, Math.PI)
        return out.toFloatArray()
    }

    /** A quadratic curve from a to b bending toward c, as [segments] + 1 points. */
    fun quad(ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float, segments: Int = 12): FloatArray {
        val out = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val t = i / segments.toFloat(); val u = 1 - t
            out[i * 2] = u * u * ax + 2 * u * t * cx + t * t * bx
            out[i * 2 + 1] = u * u * ay + 2 * u * t * cy + t * t * by
        }
        return out
    }

    /**
     * A tapering horn or feather: a thick curve from a (width [w0]) to b
     * (width [w1]) bending toward c.
     */
    fun taper(ax: Float, ay: Float, cx: Float, cy: Float, bx: Float, by: Float, w0: Float, w1: Float, segments: Int = 16): FloatArray {
        val spine = quad(ax, ay, cx, cy, bx, by, segments)
        val leftSide = ArrayList<Float>(); val rightSide = ArrayList<Float>()
        for (i in 0..segments) {
            val j = min(segments, i + 1); val k = max(0, i - 1)
            var dx = spine[j * 2] - spine[k * 2]; var dy = spine[j * 2 + 1] - spine[k * 2 + 1]
            val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f); dx /= l; dy /= l
            val w = (w0 + (w1 - w0) * i / segments) / 2f
            leftSide += spine[i * 2] - dy * w; leftSide += spine[i * 2 + 1] + dx * w
            rightSide += spine[i * 2] + dy * w; rightSide += spine[i * 2 + 1] - dx * w
        }
        val out = ArrayList<Float>(leftSide)
        for (i in segments downTo 0) { out += rightSide[i * 2]; out += rightSide[i * 2 + 1] }
        return out.toFloatArray()
    }

    /** [points] moved by ([dx], [dy]). */
    fun offset(points: FloatArray, dx: Float, dy: Float): FloatArray = FloatArray(points.size) { i -> points[i] + if (i % 2 == 0) dx else dy }
}
