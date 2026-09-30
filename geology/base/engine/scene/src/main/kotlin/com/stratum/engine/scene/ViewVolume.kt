package com.stratum.engine.scene

import kotlin.math.sqrt

/**
 * What a camera can see, as six planes, for throwing away work before it is
 * done.
 *
 * The scene meshes a square of terrain around the player so walking never
 * waits on the mesher, but the lens sees a small slice of it: on a portrait
 * phone about two per cent of the columns in that square, on a landscape one
 * about ten. Everything built per frame for the rest -- props turned to the
 * camera, their shadows, litter, blooms -- was work no pixel ever showed.
 *
 * Tests are conservative: a box that might be visible is kept. Callers widen
 * what they test by whatever reaches into view from outside it, such as a
 * shadow cast from a tree whose trunk is off screen.
 */
class ViewVolume(viewProjection: FloatArray) {

    /** a, b, c, d per plane, normalised so d is a distance in blocks; inside is positive. */
    private val planes = FloatArray(PLANES * 4)

    init {
        // Gribb and Hartmann: each plane is the last row of the matrix plus
        // or minus one of the others. Column-major, so row i is m[i], m[4+i], m[8+i], m[12+i].
        val m = viewProjection
        for (p in 0 until PLANES) {
            val row = p / 2
            val sign = if (p % 2 == 0) 1f else -1f
            val a = m[3] + sign * m[row]
            val b = m[7] + sign * m[4 + row]
            val c = m[11] + sign * m[8 + row]
            val d = m[15] + sign * m[12 + row]
            val length = sqrt(a * a + b * b + c * c).takeIf { it > 0f } ?: 1f
            planes[p * 4] = a / length
            planes[p * 4 + 1] = b / length
            planes[p * 4 + 2] = c / length
            planes[p * 4 + 3] = d / length
        }
    }

    /** Whether any part of the axis-aligned box could be on screen. */
    fun intersects(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        for (p in 0 until PLANES) {
            val a = planes[p * 4]; val b = planes[p * 4 + 1]; val c = planes[p * 4 + 2]; val d = planes[p * 4 + 3]
            // The corner furthest along the plane's normal: if even that is behind it, the whole box is.
            val x = if (a >= 0f) maxX else minX
            val y = if (b >= 0f) maxY else minY
            val z = if (c >= 0f) maxZ else minZ
            if (a * x + b * y + c * z + d < 0f) return false
        }
        return true
    }

    /**
     * Whether something standing at ([x], [y], [z]) and [height] tall could be
     * seen, or could reach into view by up to [reach] blocks sideways.
     */
    fun mayShow(x: Float, y: Float, z: Float, height: Float, reach: Float): Boolean =
        intersects(x - reach, y - reach, z - reach, x + reach, y + reach, z + height + reach)

    companion object {
        private const val PLANES = 6

        /** The volume [camera] draws. */
        fun of(camera: SceneCamera): ViewVolume = ViewVolume(camera.viewProjection)
    }
}
