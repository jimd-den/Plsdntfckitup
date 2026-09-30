package com.stratum.tools.artpreview

import com.stratum.engine.scene.SceneCamera
import com.stratum.engine.scene.ScenePicker
import com.stratum.engine.scene.Vec3
import com.stratum.engine.world.IsometricProjection
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Up on the stick is up on the screen, in both views.
 *
 * The 2D projection and the 3D camera once disagreed about handedness, and the
 * stick was fed in as world axes: pushed right, the hero walked down-right in
 * 2D and down-left in 3D. This pins all three together — stick, 2D, 3D — by
 * where a step actually lands on screen.
 */
class ScreenDirectionTest {

    private val camera = SceneCamera(Vec3(20f, 20f, 5f))
    private val flat = IsometricProjection()

    /** Screen angle, in degrees, of a one-block step from the target in 3D. */
    private fun angle3d(dx: Float, dy: Float): Double {
        val (x0, y0) = ScenePicker.project(camera, 20f, 20f, 5f, 1280f, 720f)!!
        val (x1, y1) = ScenePicker.project(camera, 20f + dx, 20f + dy, 5f, 1280f, 720f)!!
        return Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
    }

    private fun angle2d(dx: Float, dy: Float): Double {
        val a = flat.project(20f, 20f, 5f)
        val b = flat.project(20f + dx, 20f + dy, 5f)
        return Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
    }

    private fun near(a: Double, b: Double, tolerance: Double) =
        abs(((a - b + 540.0) % 360.0) - 180.0) <= tolerance

    @Test
    fun `each push of the stick walks that way on screen, in 2D and in 3D`() {
        // Screen convention: x right, y down; atan2 angles in screen space.
        val pushes = mapOf(
            "right" to (1f to 0f), "left" to (-1f to 0f),
            "down" to (0f to 1f), "up" to (0f to -1f),
        )
        pushes.forEach { (name, stick) ->
            val expected = Math.toDegrees(atan2(stick.second.toDouble(), stick.first.toDouble()))
            val world = IsometricProjection.screenToWorldDirection(stick.first, stick.second)
            // The 3D camera is foreshortened vertically, so diagonals bend a
            // little; the four axes must land on their own side of the screen.
            assertTrue(near(angle3d(world.x, world.y), expected, 1.0), "3D: pushing $name walked at ${angle3d(world.x, world.y)} degrees")
            assertTrue(near(angle2d(world.x, world.y), expected, 1.0), "2D: pushing $name walked at ${angle2d(world.x, world.y)} degrees")
        }
    }

    @Test
    fun `the two views agree on where every world direction goes`() {
        listOf(1f to 0f, 0f to 1f, 1f to 1f, -1f to 1f).forEach { (dx, dy) ->
            val a = angle2d(dx, dy); val b = angle3d(dx, dy)
            // Same quadrant and side: the 3D camera is steeper, so angles differ
            // in size but never in sign.
            assertTrue(Math.signum(Math.cos(Math.toRadians(a))) == Math.signum(Math.cos(Math.toRadians(b))) || abs(Math.cos(Math.toRadians(a))) < 1e-3,
                "world ($dx,$dy): 2D goes $a, 3D goes $b — the views are mirrored")
            assertTrue(Math.signum(Math.sin(Math.toRadians(a))) == Math.signum(Math.sin(Math.toRadians(b))) || abs(Math.sin(Math.toRadians(a))) < 1e-3,
                "world ($dx,$dy): 2D goes $a, 3D goes $b — the views are flipped")
        }
    }
}
