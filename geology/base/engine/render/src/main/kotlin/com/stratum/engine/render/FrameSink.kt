package com.stratum.engine.render

import com.stratum.core.domain.art.TerrainStyle

/**
 * Where a frame is drawn to.
 *
 * The renderer describes a frame in primitives that any backend can draw, and
 * the backend does nothing else. That split is what makes the look testable:
 * the same frame that goes to a Compose canvas on a phone goes to an image
 * rasteriser on a build machine, so "does the world still look right" is a
 * question with an answer rather than a screenshot somebody remembers.
 *
 * It is a sink rather than a list of objects because it runs sixty times a
 * second on a phone. A frame is tens of thousands of primitives, and allocating
 * a description of each one was, measurably, more work than drawing it.
 *
 * Colours are packed `0xAARRGGBB` longs and coordinates are screen pixels with
 * the origin already applied: a backend needs no knowledge of the world, the
 * camera or the projection.
 */
interface FrameSink {

    /** The vertical wash behind everything. Called first, once. */
    fun backdrop(top: Long, bottom: Long)

    /**
     * One cube, as its three visible faces plus whatever the style puts on top.
     *
     * Kept as a single call rather than three polygons because it is the hot
     * one — a backend can hold three reusable paths and rewind them, which is
     * the difference between a steady frame rate and a stuttering one.
     */
    fun cube(
        centerX: Float,
        centerY: Float,
        halfWidth: Float,
        halfHeight: Float,
        lift: Float,
        style: TerrainStyle,
        /** Non-zero when this is the cell the player is about to act on. */
        highlight: Long,
    )

    /**
     * A filled polygon, as x,y pairs in [points], of which only the first
     * [count] entries are part of this shape.
     *
     * The count exists so callers can hand over a buffer they reuse rather than
     * a right-sized copy. A frame draws hundreds of props of a few polygons
     * each, and a `FloatArray` per polygon per frame is garbage measured in
     * megabytes a second for no benefit whatsoever.
     */
    fun polygon(points: FloatArray, count: Int, fill: Long, outline: Long, outlineWidth: Float)

    fun ellipse(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float, fill: Long)

    fun ring(centerX: Float, centerY: Float, radius: Float, color: Long, width: Float)

    /** A soft radial bloom. Backends without gradients may draw concentric fades. */
    fun glow(centerX: Float, centerY: Float, radius: Float, color: Long)

    /** A flat wash over the whole viewport: overlays, and the haze that is not per-cell. */
    fun wash(color: Long)

    /** Corner darkening. Separate from [wash] because backends do it very differently. */
    fun vignette(strength: Float, color: Long)
}

/** What is being drawn and how big the window onto it is. */
data class FrameView(
    val width: Float,
    val height: Float,
    /** Screen offset already worked out from the camera, so sinks stay world-free. */
    val originX: Float,
    val originY: Float,
    /** The level the camera sits at; everything lower shades away from it. */
    val eyeLevel: Int,
)
