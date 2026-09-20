package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.SpriteFacing

/**
 * The camera every piece of generated art is drawn for, said once.
 *
 * It was said four times before, in four slightly different ways, and the
 * results showed it: a character drawn facing the viewer, a weapon drawn flat
 * on, and a world drawn at a 2:1 overhead angle do not belong in the same
 * frame. Worse, "a three-quarter overhead angle" is not actually an
 * instruction — models read it as a licence to pick any flattering angle, and
 * the one they pick most often is a straight-on hero shot, which is the one
 * angle this game never shows.
 *
 * So the camera is stated in degrees and in consequences. Degrees because they
 * are unambiguous; consequences because a model that cannot reason about a
 * 30 degree pitch can still be told that the tops of the shoulders are visible
 * and the soles of the feet are not.
 */
object IsometricCamera {

    /**
     * The angle the world is actually drawn at.
     *
     * The projection is 2:1 — a tile is twice as wide as it is tall — which is
     * an elevation of about 30 degrees. That is the Diablo camera, and it is
     * not a stylistic preference here: the renderer's projection is built on
     * it, so art drawn at any other elevation sits wrong on the ground no
     * matter how good it is.
     */
    const val ELEVATION_DEGREES = 30

    /** Turned this far off straight-on, which is what makes it three-quarter. */
    const val ROTATION_DEGREES = 45

    /**
     * The direction the drawn art faces.
     *
     * One facing is drawn and the far side is mirrored, so it has to be the
     * facing that is not mirrored — otherwise every character ships back to
     * front. South-east is that facing.
     */
    val drawnFacing: SpriteFacing = SpriteFacing.SOUTH_EAST

    /**
     * The clause every prompt shares.
     *
     * Phrased as a fixed fact about the scene rather than as a request, because
     * a request is something a model weighs against the other things it was
     * asked for and this is not negotiable.
     */
    val clause: String = buildString {
        appendLine(
            "Camera: a fixed isometric game camera, like Diablo. It is $ELEVATION_DEGREES degrees",
        )
        appendLine(
            "above the horizon and turned $ROTATION_DEGREES degrees to the side, so the subject is",
        )
        appendLine("seen from above and from a corner at the same time.")
        // The consequences, for a model that will not do the trigonometry.
        appendLine("- Looking down on the subject: the tops of the shoulders and the top of the")
        appendLine("  head are visible.")
        appendLine("- Turned three-quarters away from straight-on: one side of the body is")
        appendLine("  nearer the viewer than the other. Not a flat front view, not a flat side")
        appendLine("  view.")
        appendLine("- Facing down and to the right, towards the bottom-right corner of the image.")
        appendLine("- The ground is a flat plane receding upward at a shallow angle, twice as")
        appendLine("  wide as it is tall. Do not draw the ground itself.")
        append("- No dramatic perspective, no wide angle lens, no worm's eye or bird's eye view.")
    }

    /**
     * The same camera, said as something that must not change between frames.
     *
     * Used when editing a pose. The failure it exists to stop is specific and
     * was measured: every model tested turned the figure to a profile view when
     * asked for a pose described in terms of legs and arms, because that is the
     * angle those read best from.
     */
    val holdClause: String = buildString {
        appendLine("THE CAMERA DOES NOT MOVE. The subject is seen from exactly the same")
        appendLine("direction as in the attached image: the isometric game camera, looking down")
        appendLine("from $ELEVATION_DEGREES degrees and turned $ROTATION_DEGREES degrees to the side.")
        appendLine("Do not turn the subject to face the viewer. Do not draw a flat front view or")
        append("a flat side view. Do not change the eye level. Only the pose changes.")
    }
}
