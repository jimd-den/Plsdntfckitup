package com.stratum.engine.scene

import kotlin.math.cos
import kotlin.math.sin

/**
 * The action-RPG camera: high, angled, and nearly orthographic.
 *
 * Diablo and Hades both look down at roughly fifty degrees through a narrow
 * lens from a long way back. The narrow field of view is the important part:
 * it keeps the perspective gentle enough that the grid still reads as a grid
 * and a block at the edge of the screen is the same size as one in the middle,
 * while still giving walls a top and a foreshortened face — which is what made
 * the old isometric view read as a board game and this one read as a place.
 *
 * The yaw matches the old isometric projection, looking north-west, so screen
 * directions a player has learned do not change when the world goes 3D.
 */
data class SceneCamera(
    val target: Vec3,
    /**
     * Degrees above the horizon the camera looks down from. Shared with the
     * forge's prompts, so scenery is painted from the angle it is seen at.
     */
    val pitch: Float = com.stratum.core.domain.ai.IsometricCamera.SCENE_ELEVATION_DEGREES.toFloat(),
    /** Degrees around z; 225 places the camera to the south-east, looking north-west. */
    val yaw: Float = 225f,
    /** Distance from target, in blocks. */
    val distance: Float = 34f,
    val fovY: Float = 28f,
    val aspect: Float = 16f / 9f,
    val near: Float = 4f,
    val far: Float = 90f,
) {
    val eye: Vec3
        get() {
            val p = Math.toRadians(pitch.toDouble())
            val y = Math.toRadians(yaw.toDouble())
            // The camera sits *behind* the view direction: yaw names where it
            // looks, so its position is the opposite way round the target.
            val dx = (-cos(y) * cos(p)).toFloat()
            val dy = (-sin(y) * cos(p)).toFloat()
            val dz = sin(p).toFloat()
            return target + Vec3(dx, dy, dz) * distance
        }

    val view: FloatArray get() = Mat4.lookAt(eye, target, Vec3.UP)

    val projection: FloatArray get() = Mat4.perspective(fovY, aspect, near, far)

    val viewProjection: FloatArray get() = Mat4.multiply(projection, view)

    /** The screen's right and up directions in world space, for billboards. */
    val right: Vec3 get() = (target - eye).cross(Vec3.UP).normalized()
    val up: Vec3 get() = right.cross(target - eye).normalized()

    /** Zoom as a distance multiplier, clamped to what still reads. */
    fun zoomed(factor: Float): SceneCamera = copy(distance = (distance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    companion object {
        const val MIN_DISTANCE = 18f
        const val MAX_DISTANCE = 60f
    }
}
