package com.stratum.core.domain.ai

import com.stratum.core.domain.sprite.SpriteFacing

/**
 * Which way the character is turned, as a thing to be drawn.
 *
 * A generated sheet has always held one angle, and the renderer bought the
 * second by mirroring it -- which is honest at this camera, because a figure
 * seen from the front-left really is a figure seen from the front-right
 * flipped. It cannot buy the third and fourth. A character walking away has
 * its back to the viewer, and no amount of flipping the front turns a face
 * into the back of a head, so a character walking north walked backwards.
 *
 * So the away angle is drawn rather than derived, and the mirror still earns
 * its keep: two drawn angles cover four, exactly as one covered two.
 */
enum class PoseView(
    val label: String,
    /**
     * Appended to the pose key. [FRONT] appends nothing on purpose: it is what
     * every character drawn before this existed was filed under, and changing
     * it would strand every set already on disk.
     */
    val keySuffix: String,
    /** The facing this angle is drawn as; the other of its pair is its mirror. */
    val drawnAs: SpriteFacing,
) {
    FRONT("Towards", "", SpriteFacing.SOUTH_EAST),
    AWAY("Away", "_away", SpriteFacing.NORTH_EAST);

    /** The two world facings this angle serves, itself and its mirror. */
    val serves: List<SpriteFacing>
        get() = when (this) {
            FRONT -> listOf(SpriteFacing.SOUTH_EAST, SpriteFacing.SOUTH_WEST)
            AWAY -> listOf(SpriteFacing.NORTH_EAST, SpriteFacing.NORTH_WEST)
        }

    /**
     * What to tell the model about which way the body is turned.
     *
     * Written as a fact about the body rather than about the camera, because
     * the camera clause next to it is emphatic that the camera does not move,
     * and the two must not appear to argue. The character turns; the viewer
     * stays exactly where they were.
     */
    val turnClause: String
        get() = when (this) {
            FRONT -> ""
            AWAY -> buildString {
                appendLine("THE SUBJECT IS TURNED AWAY FROM THE VIEWER.")
                appendLine("The camera has not moved. The character has: it is facing away, up")
                appendLine("and to the right, towards the top-right corner of the image.")
                appendLine("- The back of the head and the back of the shoulders face the viewer.")
                appendLine("- The face is not visible. No eyes, no mouth, no front of the mask.")
                appendLine("- Whatever is worn on the back is what is seen: a cloak, a quiver, the")
                appendLine("  straps and fastenings of the armour.")
                append("- Same character, same costume, same colours as the reference.")
            }
        }
}
