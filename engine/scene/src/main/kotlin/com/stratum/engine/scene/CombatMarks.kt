package com.stratum.engine.scene

/** What a [CombatMark] is: something flying, ground that pulses, or a wind-up marked where it will land. */
enum class CombatMarkKind { PROJECTILE, ZONE, TELEGRAPH }

/** The outline a telegraph covers on the ground. */
enum class MarkShape { CIRCLE, CONE, LANE }

/**
 * The combat core's world-space state as the scene needs it: where a thing
 * is, how big, which colour, and how far along.
 *
 * Plain numbers rather than the engine's own types, because the scene depends
 * on the domain only and does not know what a projectile is -- the same
 * reason actors arrive as [SceneActor]. The play screen translates.
 */
data class CombatMark(
    val kind: CombatMarkKind,
    val x: Float,
    val y: Float,
    /** The ground under a zone or telegraph; the height of a projectile in flight. */
    val z: Float,
    /** A circle's radius, a cone's or lane's length, a projectile's body. */
    val radius: Float,
    val color: Long,
    /** A telegraph's wind-up, 0 as it begins and 1 as it lands. */
    val progress: Float = 0f,
    val shape: MarkShape = MarkShape.CIRCLE,
    /** Which way a cone or lane points, or a projectile flies. */
    val dirX: Float = 0f,
    val dirY: Float = 1f,
    val angleDegrees: Float = 0f,
    val halfWidth: Float = 0f,
    /** Aimed at the player: drawn in the style's hostile colour whatever the skill's own. */
    val hostile: Boolean = false,
    /** A forged attack's look: drawn as its sketch rather than a plain glow or disc. */
    val look: com.stratum.core.domain.attack.AttackLook? = null,
)

/** A block flying loose after an attack tore it up, drawn as a small cube of its colour. */
data class DebrisMark(val x: Float, val y: Float, val z: Float, val size: Float, val color: Long)

/**
 * A fallen body lying loose: [points] holds x, y, z for eleven joints --
 * head, chest, pelvis, left elbow and hand, right elbow and hand, left knee
 * and foot, right knee and foot -- drawn as a figure of small voxels.
 */
class RagdollMark(val points: FloatArray, val color: Long, val scale: Float, val alpha: Float) {
    companion object {
        /** Joint pairs drawn as limbs. */
        val LIMBS = listOf(0 to 1, 1 to 2, 1 to 3, 3 to 4, 1 to 5, 5 to 6, 2 to 7, 7 to 8, 2 to 9, 9 to 10)
    }
}
