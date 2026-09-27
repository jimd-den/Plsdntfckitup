package com.stratum.engine.scene

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World
import kotlin.math.abs
import kotlin.math.floor

/**
 * Which block a tap landed on, through a perspective camera.
 *
 * The isometric view could answer this with arithmetic; a perspective view
 * cannot, because a point on the screen is a ray into the world. The ray is
 * walked cell by cell (Amanatides–Woo), so it stops at the first solid thing
 * it actually passes through — a wall in front of the ground is hit, the
 * ground behind it is not, which is what the player sees and therefore what
 * they meant.
 */
object ScenePicker {

    /** A cell the ray hit, and the face it came in through, for placing against. */
    data class Hit(val block: BlockPos, val faceX: Int, val faceY: Int, val faceZ: Int) {
        /** The empty cell in front of the face that was hit. */
        val adjacent: BlockPos get() = BlockPos(block.x + faceX, block.y + faceY, block.z + faceZ)
    }

    /** The ray from the eye through a screen point, as origin and unit direction. */
    fun ray(camera: SceneCamera, screenX: Float, screenY: Float, width: Float, height: Float): Pair<Vec3, Vec3>? {
        val inverse = Mat4.invert(camera.viewProjection) ?: return null
        val nx = screenX / width * 2f - 1f
        val ny = 1f - screenY / height * 2f
        val near = unproject(inverse, nx, ny, -1f)
        val far = unproject(inverse, nx, ny, 1f)
        return near to (far - near).normalized()
    }

    fun pick(
        world: World,
        camera: SceneCamera,
        screenX: Float,
        screenY: Float,
        width: Float,
        height: Float,
        maxDistance: Float = camera.far,
        /** What counts as something to hit. Props and thin walls are hittable by default. */
        hits: (BlockPos) -> Boolean = { !world.blockAt(it).isAir },
    ): Hit? {
        val (origin, dir) = ray(camera, screenX, screenY, width, height) ?: return null
        var x = floor(origin.x).toInt()
        var y = floor(origin.y).toInt()
        var z = floor(origin.z).toInt()
        val stepX = if (dir.x > 0) 1 else -1
        val stepY = if (dir.y > 0) 1 else -1
        val stepZ = if (dir.z > 0) 1 else -1
        val deltaX = if (dir.x != 0f) abs(1f / dir.x) else Float.MAX_VALUE
        val deltaY = if (dir.y != 0f) abs(1f / dir.y) else Float.MAX_VALUE
        val deltaZ = if (dir.z != 0f) abs(1f / dir.z) else Float.MAX_VALUE
        var tMaxX = if (dir.x != 0f) ((if (stepX > 0) x + 1 - origin.x else origin.x - x) * deltaX) else Float.MAX_VALUE
        var tMaxY = if (dir.y != 0f) ((if (stepY > 0) y + 1 - origin.y else origin.y - y) * deltaY) else Float.MAX_VALUE
        var tMaxZ = if (dir.z != 0f) ((if (stepZ > 0) z + 1 - origin.z else origin.z - z) * deltaZ) else Float.MAX_VALUE
        var faceX = 0; var faceY = 0; var faceZ = 0
        var t = 0f
        while (t <= maxDistance) {
            if (z < 0) return null
            val pos = BlockPos(x, y, z)
            if (hits(pos)) return Hit(pos, faceX, faceY, faceZ)
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += deltaX; faceX = -stepX; faceY = 0; faceZ = 0
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += deltaY; faceX = 0; faceY = -stepY; faceZ = 0
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += deltaZ; faceX = 0; faceY = 0; faceZ = -stepZ
            }
        }
        return null
    }

    /** Where a world point lands on screen, or null if it is behind the camera. */
    fun project(camera: SceneCamera, x: Float, y: Float, z: Float, width: Float, height: Float): Pair<Float, Float>? {
        val clip = FloatArray(4)
        Mat4.transform(camera.viewProjection, x, y, z, clip)
        if (clip[3] <= 0f) return null
        return (clip[0] / clip[3] * 0.5f + 0.5f) * width to (1f - (clip[1] / clip[3] * 0.5f + 0.5f)) * height
    }

    private fun unproject(inverse: FloatArray, x: Float, y: Float, z: Float): Vec3 {
        val out = FloatArray(4)
        Mat4.transform(inverse, x, y, z, out)
        return Vec3(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }
}
