package com.stratum.engine.model

import com.stratum.core.domain.ai.IsometricCamera
import com.stratum.engine.scene.PropModel
import com.stratum.engine.scene.Texture
import com.stratum.engine.scene.forge.Pixels
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The eight ways a baked model can face, clockwise from east, as compass points on the world grid. */
enum class BakeDirection(val degrees: Int) {
    EAST(0), SOUTH_EAST(45), SOUTH(90), SOUTH_WEST(135), WEST(180), NORTH_WEST(225), NORTH(270), NORTH_EAST(315),
}

/** A model drawn from the game's camera once per direction, all at one scale. */
class BakedSprites(val frames: Map<BakeDirection, Texture>) {
    /** The frame to show when nothing says otherwise: facing the camera. */
    val front: Texture get() = frames.getValue(BakeDirection.SOUTH_EAST)

    /** All eight in [BakeDirection] order, side by side, as one sheet. */
    fun strip(): Texture {
        val w = front.width; val h = front.height
        val out = IntArray(w * BakeDirection.entries.size * h)
        BakeDirection.entries.forEachIndexed { i, direction ->
            val frame = frames.getValue(direction)
            for (y in 0 until h) frame.argb.copyInto(out, y * w * BakeDirection.entries.size + i * w, y * w, y * w + w)
        }
        return Texture(w * BakeDirection.entries.size, h, out)
    }
}

/**
 * Draws a model into sprites on the CPU: a small software rasteriser with a
 * depth buffer, one light and an outline.
 *
 * This is what lets a 3D model feed every path that takes sprites — the flat
 * renderer, the billboard props and actors of the 3D view, the forge's own
 * preview — without any of them learning what a mesh is. The camera is the
 * game's: orthographic, [elevationDegrees] above the horizon, looking from the
 * south-east, so a baked prop stands in the world at the angle it will be seen
 * from. All eight frames share one scale and one ground line, so turning the
 * model does not make it grow or hop.
 */
object SpriteBaker {

    fun bake(
        model: PropModel,
        size: Int = DEFAULT_SIZE,
        elevationDegrees: Float = IsometricCamera.SCENE_ELEVATION_DEGREES.toFloat(),
        directions: List<BakeDirection> = BakeDirection.entries,
        outline: Boolean = true,
    ): BakedSprites {
        require(size in 8..MAX_SIZE) { "a sprite between 8 and $MAX_SIZE pixels" }
        require(model.triangleCount > 0) { "nothing to draw" }
        val view = View(elevationDegrees)
        // One fit across every direction, so the frames agree on scale and ground.
        var halfWidth = 1e-3f; var top = 1e-3f; var bottom = 0f
        for (direction in directions) {
            val turned = turn(model.positions, direction.degrees)
            for (i in 0 until turned.size / 3) {
                val sx = view.screenX(turned, i); val sy = view.screenY(turned, i)
                halfWidth = max(halfWidth, kotlin.math.abs(sx)); top = max(top, sy); bottom = min(bottom, sy)
            }
        }
        val render = size * SUPERSAMPLE
        val pad = render * PADDING
        val scale = min((render - 2 * pad) / (2 * halfWidth), (render - 2 * pad) / (top - bottom))
        val frames = directions.associateWith { direction ->
            val big = rasterize(model, turn(model.positions, direction.degrees), turn(model.normals, direction.degrees), view, render, scale, pad, top)
            val small = Pixels.downscale(big, size)
            if (outline) outlined(small) else small
        }
        return BakedSprites(frames)
    }

    /** The camera as three unit vectors: screen right, screen up, and towards the eye. */
    private class View(elevationDegrees: Float) {
        private val p = Math.toRadians(elevationDegrees.toDouble())
        private val a = (cos(p) / sqrt(2.0)).toFloat()
        private val s = sin(p).toFloat()
        private val r = 1f / sqrt(2f)

        // Eye to the south-east, as SceneCamera's default yaw puts it.
        val rightX = r; val rightY = -r; val rightZ = 0f
        val upX = -s * r; val upY = -s * r; val upZ = cos(p).toFloat()
        val eyeX = a; val eyeY = a; val eyeZ = s

        fun screenX(v: FloatArray, i: Int) = v[i * 3] * rightX + v[i * 3 + 1] * rightY + v[i * 3 + 2] * rightZ
        fun screenY(v: FloatArray, i: Int) = v[i * 3] * upX + v[i * 3 + 1] * upY + v[i * 3 + 2] * upZ
        fun depth(v: FloatArray, i: Int) = v[i * 3] * eyeX + v[i * 3 + 1] * eyeY + v[i * 3 + 2] * eyeZ
    }

    /** Rotates x, y triples about the vertical by [degrees], facing the model's front (+y) the given way. */
    private fun turn(v: FloatArray, degrees: Int): FloatArray {
        // The model's front faces south (90°); turning by (degrees - 90) faces it towards [degrees].
        val radians = Math.toRadians((degrees - 90).toDouble())
        val c = cos(radians).toFloat(); val sn = sin(radians).toFloat()
        val out = FloatArray(v.size)
        for (i in 0 until v.size / 3) {
            val x = v[i * 3]; val y = v[i * 3 + 1]
            out[i * 3] = x * c - y * sn; out[i * 3 + 1] = x * sn + y * c; out[i * 3 + 2] = v[i * 3 + 2]
        }
        return out
    }

    private fun rasterize(
        model: PropModel, positions: FloatArray, normals: FloatArray, view: View,
        size: Int, scale: Float, pad: Float, top: Float,
    ): Texture {
        val color = IntArray(size * size)
        val depth = FloatArray(size * size) { -Float.MAX_VALUE }
        val sx = FloatArray(3); val sy = FloatArray(3); val sz = FloatArray(3)
        for (t in 0 until model.triangleCount) {
            for (c in 0 until 3) {
                val v = t * 3 + c
                sx[c] = size / 2f + view.screenX(positions, v) * scale
                sy[c] = pad + (top - view.screenY(positions, v)) * scale
                sz[c] = view.depth(positions, v)
            }
            val shaded = Colors.shade(model.colors[t] or OPAQUE, light(normals, t, view))
            fill(sx, sy, sz, shaded, color, depth, size)
        }
        return Texture(size, size, color)
    }

    /**
     * Hemisphere ambient plus one sun from the upper left, as the house style
     * lights the world. Two-sided: a face turned away from the camera by a
     * file's inconsistent winding is lit as the side the camera sees.
     */
    private fun light(normals: FloatArray, t: Int, view: View): Float {
        var nx = normals[t * 3]; var ny = normals[t * 3 + 1]; var nz = normals[t * 3 + 2]
        if (nx * view.eyeX + ny * view.eyeY + nz * view.eyeZ < 0f) { nx = -nx; ny = -ny; nz = -nz }
        val sun = max(0f, nx * SUN_X + ny * SUN_Y + nz * SUN_Z)
        val sky = 0.5f + 0.5f * nz
        return AMBIENT_GROUND + (AMBIENT_SKY - AMBIENT_GROUND) * sky + SUN * sun
    }

    /** Fills one screen triangle with a depth test, by edge functions over its bounding box. */
    private fun fill(x: FloatArray, y: FloatArray, z: FloatArray, argb: Int, color: IntArray, depth: FloatArray, size: Int) {
        val area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
        if (kotlin.math.abs(area) < 1e-8f) return
        val minX = max(0, kotlin.math.floor(minOf(x[0], x[1], x[2])).toInt())
        val maxX = min(size - 1, kotlin.math.ceil(maxOf(x[0], x[1], x[2])).toInt())
        val minY = max(0, kotlin.math.floor(minOf(y[0], y[1], y[2])).toInt())
        val maxY = min(size - 1, kotlin.math.ceil(maxOf(y[0], y[1], y[2])).toInt())
        for (py in minY..maxY) {
            val cy = py + 0.5f
            for (px in minX..maxX) {
                val cx = px + 0.5f
                val w0 = ((x[1] - cx) * (y[2] - cy) - (x[2] - cx) * (y[1] - cy)) / area
                val w1 = ((x[2] - cx) * (y[0] - cy) - (x[0] - cx) * (y[2] - cy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val d = w0 * z[0] + w1 * z[1] + w2 * z[2]
                val i = py * size + px
                if (d <= depth[i]) continue
                depth[i] = d
                color[i] = argb
            }
        }
    }

    /**
     * A one-pixel dark edge where the model meets the empty background.
     *
     * The sprites the forge paints carry an outline, and a baked model without
     * one reads as a different game's art standing among them.
     */
    private fun outlined(src: Texture): Texture {
        val w = src.width; val h = src.height
        val out = src.argb.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (Colors.a(src.argb[y * w + x]) >= EDGE_ALPHA) continue
            var edge = 0
            for ((dx, dy) in NEIGHBOURS) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) {
                    val n = src.argb[ny * w + nx]
                    if (Colors.a(n) >= EDGE_ALPHA) { edge = Colors.shade(n, OUTLINE_SHADE); break }
                }
            }
            if (edge != 0) out[y * w + x] = (edge and 0x00FFFFFF) or (OUTLINE_ALPHA shl 24)
        }
        return Texture(w, h, out)
    }

    const val DEFAULT_SIZE = 256
    private const val MAX_SIZE = 1024
    private const val SUPERSAMPLE = 2
    private const val PADDING = 0.04f
    private const val OPAQUE = -0x1000000

    private const val AMBIENT_SKY = 0.62f
    private const val AMBIENT_GROUND = 0.36f
    private const val SUN = 0.55f
    private const val SUN_X = -0.55f
    private const val SUN_Y = -0.2f
    private const val SUN_Z = 0.81f

    private const val EDGE_ALPHA = 128
    private const val OUTLINE_SHADE = 0.3f
    private const val OUTLINE_ALPHA = 230
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
