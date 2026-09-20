package com.stratum.core.domain.sprite

import kotlin.math.cos
import kotlin.math.sin

/**
 * A point on the body, before anything decides how it is seen.
 *
 * Three axes, in fractions of the figure's height: [lateral] across the body
 * towards the near side, [up] towards the head, and [forward] the way the
 * character is facing.
 */
data class BodyPoint(val lateral: Float, val up: Float, val forward: Float)

/**
 * Turns a body into a picture of a body seen from the game's camera.
 *
 * The guide used to be built flat. Joints were placed straight into screen
 * coordinates and the far side of the body was nudged sideways by a constant
 * that called itself "the cheapest possible nod to the three-quarter camera" --
 * which is what it was. The result was a front-on stick figure handed to a
 * model alongside a prompt insisting, in capitals, on a camera thirty degrees
 * up and forty-five round. The two disagreed in every frame, and the model had
 * to decide which to believe.
 *
 * Projecting properly costs a sine and a cosine per joint and removes the
 * disagreement: the guide is the pose seen from the camera the art is drawn
 * for, so following the guide and following the camera are the same act.
 *
 * The consequences are the ones a projection should have. Height is
 * foreshortened, so a figure is shorter on screen than it is tall. A limb
 * swung towards the viewer travels down and across rather than only across.
 * The two sides of the body separate, because one really is nearer -- which is
 * the thing the constant was imitating.
 */
object IsoProjection {

    /** Matches `IsometricCamera`: the elevation and rotation the prompts name. */
    const val ELEVATION_DEGREES = 30f
    const val ROTATION_DEGREES = 45f

    private val elevation = Math.toRadians(ELEVATION_DEGREES.toDouble())
    private val rotation = Math.toRadians(ROTATION_DEGREES.toDouble())

    private val cosRotation = cos(rotation).toFloat()
    private val sinRotation = sin(rotation).toFloat()
    private val cosElevation = cos(elevation).toFloat()
    private val sinElevation = sin(elevation).toFloat()

    /**
     * Where a body point lands, relative to the point the figure stands on.
     *
     * Screen y grows downward, so height subtracts. Depth adds: something
     * further from the camera sits higher in the frame, which is what makes
     * the far shoulder ride above the near one instead of beside it.
     */
    fun project(point: BodyPoint): JointPoint {
        // The two ground axes, turned by the camera's rotation. Facing and
        // across-the-body are perpendicular on the ground, so under this
        // camera one runs down-and-right and the other down-and-left -- which
        // is what gives a figure its three-quarter shape rather than a flat
        // one.
        //
        // The signs are the whole of it, and the first version had them wrong:
        // reaching forward carried the hand *away* from the viewer and up the
        // frame, which is a character facing backwards. Forward is towards the
        // bottom-right of the picture, because that is the direction the
        // prompts tell every model the character faces.
        val across = point.forward * sinRotation - point.lateral * cosRotation
        val depth = -point.lateral * sinRotation - point.forward * cosRotation
        return JointPoint(
            x = across,
            // Screen y grows downward, so height subtracts. Depth subtracts
            // too: something nearer the camera sits lower in the frame, which
            // is what puts the near shoulder below the far one rather than
            // beside it.
            y = -point.up * cosElevation - depth * sinElevation,
        )
    }

    /**
     * How much shorter a standing figure is on screen than it is tall.
     *
     * Exposed because the skeleton's proportions are written as fractions of a
     * figure's height, and after projection they no longer add up to one. A
     * guide drawn without accounting for that is a correct pose at the wrong
     * size, which reads to a model as a short character.
     */
    val heightScale: Float get() = cosElevation

    /**
     * How much of a sideways offset turns into screen depth rather than width.
     *
     * The measure of how far the camera is turned: at zero the two sides of a
     * body land on the same screen row and the figure reads as a flat front
     * view, which is the one angle the art must never come back as.
     */
    val sideSeparation: Float get() = sinRotation * sinElevation
}
