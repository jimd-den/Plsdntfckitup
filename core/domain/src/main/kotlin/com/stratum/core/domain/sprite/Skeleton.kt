package com.stratum.core.domain.sprite

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in a frame, as a fraction of it: 0,0 top-left, 1,1 bottom-right. */
data class JointPoint(val x: Float, val y: Float) {
    operator fun plus(other: JointPoint) = JointPoint(x + other.x, y + other.y)

    fun distanceTo(other: JointPoint): Float = hypot(x - other.x, y - other.y)
}

/** The joints a stick figure needs to describe a body doing something. */
enum class Joint {
    HEAD,
    NECK,
    CHEST,
    PELVIS,
    SHOULDER_NEAR, ELBOW_NEAR, HAND_NEAR,
    SHOULDER_FAR, ELBOW_FAR, HAND_FAR,
    HIP_NEAR, KNEE_NEAR, FOOT_NEAR,
    HIP_FAR, KNEE_FAR, FOOT_FAR,

    /**
     * Heel and toe, which are what a foot is as far as a guide is concerned.
     *
     * FOOT_NEAR is an ankle: it says where the leg stops and nothing about
     * which way the foot points or how much of it is on the floor. Those are
     * the two things that make a walk read as walking rather than as a figure
     * being slid along, and they are exactly what a whole-body estimator like
     * DWPose reports and a body-only one does not.
     */
    HEEL_NEAR, TOE_NEAR,
    HEEL_FAR, TOE_FAR,
    ;

    companion object {
        /**
         * Near and far rather than left and right.
         *
         * Everything here is drawn at one three-quarter camera, so one side of
         * the body is always closer to the viewer than the other. Naming them
         * that way means a pose never has to be rewritten when the character
         * mirrors: the near arm is still the near arm, and the weapon is still
         * in the hand the viewer can see.
         */
        val weaponHand = HAND_NEAR
        val weaponElbow = ELBOW_NEAR
    }
}

/** Two joints and the line between them, for drawing the figure. */
data class Bone(val from: Joint, val to: Joint, val weight: BoneWeight = BoneWeight.LIMB)

/** How thickly a bone is drawn. A torso reads as a torso only if it is thicker than an arm. */
enum class BoneWeight { TORSO, LIMB }

/**
 * A body, resolved to positions.
 *
 * The output of posing rather than the thing that is authored: angles are what
 * a person can reason about ("the elbow is bent ninety degrees"), and positions
 * are what a renderer and a weapon anchor need.
 */
data class Pose(val joints: Map<Joint, JointPoint>) {

    operator fun get(joint: Joint): JointPoint? = joints[joint]

    fun require(joint: Joint): JointPoint =
        joints[joint] ?: error("Pose is missing $joint")

    /**
     * The lowest drawn point, which is what stands on the ground.
     *
     * Measured across the feet rather than the ankles. Once a foot has a heel
     * and a toe, the ankle is no longer the bottom of the figure, and clamping
     * to it would stand the character on its ankles and push everything below
     * them through the floor.
     */
    val groundY: Float
        get() = LOWEST.mapNotNull { joints[it]?.y }.maxOrNull()
            ?: maxOf(require(Joint.FOOT_NEAR).y, require(Joint.FOOT_FAR).y)

    /**
     * Where the weapon hand is, and which way the forearm points.
     *
     * The whole reason a skeleton is worth having on the rendering side. A
     * weapon held in a hand points along the forearm, and both of those are
     * facts the skeleton already knows exactly — where previously they were
     * authored by guesswork and then corrected per character by hand.
     */
    fun weaponGrip(): WeaponGrip {
        val hand = require(Joint.weaponHand)
        val elbow = require(Joint.weaponElbow)
        val forearm = degreesFromDown(hand.x - elbow.x, hand.y - elbow.y)
        return WeaponGrip(
            at = hand,
            forearmDegrees = forearm,
            weaponDegrees = weaponDegreesFor(forearm),
        )
    }

    private companion object {
        /**
         * The one place the two angle conventions meet.
         *
         * Limbs are authored anticlockwise from straight down, because that is
         * what reads naturally when writing a pose: an arm out to the near side
         * is +90. [WeaponAnchor] measures clockwise from straight up, because
         * that is what the renderer's rotate() does to a weapon drawn pointing
         * up. Those are opposite handedness from different zeroes, so the
         * conversion is a reflection rather than an offset -- which is exactly
         * the kind of thing that should exist once, with its reasoning attached,
         * instead of being rediscovered at three call sites.
         */
        fun weaponDegreesFor(forearmDegrees: Float): Float = normalized(180f - forearmDegrees)

        /**
         * Brought into (-180, 180].
         *
         * A rotation of 324 and one of -36 draw identically, so this changes
         * nothing on screen — and everything about being able to reason. Signed
         * around zero, a wind-up is negative and a follow-through positive, so
         * an arc that turns one way reads as a rising sequence instead of one
         * that appears to leap backwards every time it crosses vertical.
         */
        fun normalized(degrees: Float): Float {
            var value = degrees % 360f
            if (value > 180f) value -= 360f
            if (value <= -180f) value += 360f
            return value
        }
    }
}

/**
 * A hand, the direction the arm reaches it from, and what that means for a
 * weapon held in it.
 *
 * A weapon in a hand continues the line of the forearm. That is the fact this
 * whole idea turns on: both numbers are things the skeleton knows exactly,
 * where before they were authored by guesswork and then corrected per character
 * by hand.
 */
data class WeaponGrip(
    val at: JointPoint,
    val forearmDegrees: Float,
    /** Ready for [WeaponAnchor]: clockwise from a weapon drawn pointing up. */
    val weaponDegrees: Float,
)

/**
 * The proportions of a figure, as fractions of its full height.
 *
 * Fixed rather than per-character on purpose. The figure this describes is not
 * the character — it is the stick guide the character is drawn *onto*, and a
 * guide whose proportions drifted per character would stop being a common
 * reference. Characters differ in what they wear, not in having one head.
 */
data class Skeleton(
    val pelvisY: Float = 0.54f,
    val pelvisToChest: Float = 0.16f,
    val chestToNeck: Float = 0.12f,
    val neckToHead: Float = 0.09f,
    val shoulderHalfWidth: Float = 0.1f,
    val hipHalfWidth: Float = 0.055f,
    val upperArm: Float = 0.15f,
    val foreArm: Float = 0.14f,
    val thigh: Float = 0.22f,
    val shin: Float = 0.21f,
    /** Ankle to heel, and ankle to toe. A foot is longer forward than back. */
    val heel: Float = 0.025f,
    val toes: Float = 0.055f,
) {

    /**
     * How far apart the two sides of the body land on screen.
     *
     * Derived now rather than chosen. It used to be a constant added to the
     * far side's screen position -- the cheapest possible nod to a
     * three-quarter camera, which is what it called itself. The body is built
     * across a real axis now and the camera separates the sides by itself, so
     * this is only what that separation comes to. Kept because the OpenPose
     * round-trip measures against it: OpenPose carries no pelvis and no chest,
     * so a body that goes out and comes back drifts by exactly this much.
     */
    val threeQuarterSkew: Float
        get() = 2f * shoulderHalfWidth * IsoProjection.sideSeparation / IsoProjection.heightScale


    /**
     * Resolves angles into positions.
     *
     * Ordinary forward kinematics, and the reason poses are authored as angles:
     * bone lengths are fixed here, so no authored pose can stretch a forearm or
     * shrink a thigh. A pose expressed as raw coordinates can do both, and the
     * result is a guide that teaches the model the wrong proportions.
     */
    fun pose(angles: PoseAngles): Pose {
        // Built in the body's own three axes and projected at the end, rather
        // than placed straight into the picture. The angles mean what they
        // always meant -- a swing forward or back, in the plane the leg
        // actually swings in -- and the camera is applied once, afterwards,
        // to all of it.
        fun swing(
            from: BodyPoint,
            length: Float,
            degrees: Float,
            out: Float = 0f,
            side: Float = 1f,
        ): BodyPoint {
            val pitch = Math.toRadians(degrees.toDouble())
            val lift = Math.toRadians(out.toDouble())
            // Two angles, not one: how far the limb has swung fore or aft, and
            // how far it has been lifted away from the body. At zero lift this
            // is the plain swing it has always been.
            val alongBody = cos(lift).toFloat()
            return BodyPoint(
                lateral = from.lateral + side * (length * sin(lift)).toFloat(),
                up = from.up - length * alongBody * cos(pitch).toFloat(),
                forward = from.forward + length * alongBody * sin(pitch).toFloat(),
            )
        }

        val pelvis = BodyPoint(0f, 0f, 0f)
        val chest = swing(pelvis, pelvisToChest, UP + angles.lean)
        val neck = swing(chest, chestToNeck, UP + angles.lean * 0.5f)
        val head = swing(neck, neckToHead, UP + angles.headTilt)

        // Shoulders and hips sit either side of the spine, across the body.
        // This is where the flat version cheated: it added the offset to the
        // picture and shifted the far side sideways to fake the depth. Across
        // the body is a real direction, and the camera turns it into both.
        val shoulderNear = chest.copy(lateral = chest.lateral + shoulderHalfWidth)
        val shoulderFar = chest.copy(lateral = chest.lateral - shoulderHalfWidth)
        val hipNear = pelvis.copy(lateral = pelvis.lateral + hipHalfWidth)
        val hipFar = pelvis.copy(lateral = pelvis.lateral - hipHalfWidth)

        // The lift carries down the limb: a forearm belongs to the arm it is
        // on, so an arm held out to the side has its whole length out there
        // rather than an upper arm that leaves and a forearm that returns.
        val elbowNear = swing(shoulderNear, upperArm, angles.shoulderNear, angles.shoulderNearOut, 1f)
        val handNear = swing(
            elbowNear, foreArm, angles.shoulderNear + angles.elbowNear, angles.shoulderNearOut, 1f,
        )
        val elbowFar = swing(shoulderFar, upperArm, angles.shoulderFar, angles.shoulderFarOut, -1f)
        val handFar = swing(
            elbowFar, foreArm, angles.shoulderFar + angles.elbowFar, angles.shoulderFarOut, -1f,
        )

        val kneeNear = swing(hipNear, thigh, angles.hipNear, angles.hipNearOut, 1f)
        val shinNear = angles.hipNear + angles.kneeNear
        val footNear = swing(kneeNear, shin, shinNear, angles.hipNearOut, 1f)
        val kneeFar = swing(hipFar, thigh, angles.hipFar, angles.hipFarOut, -1f)
        val shinFar = angles.hipFar + angles.kneeFar
        val footFar = swing(kneeFar, shin, shinFar, angles.hipFarOut, -1f)

        // The foot hangs off the ankle across the shin, not along it. A leg
        // swung forward puts its heel down first and its toe up, and one
        // trailing behind does the reverse -- which falls out of taking the
        // foot as a right angle to the shin, rather than having to be
        // described pose by pose.
        val heelNear = swing(footNear, heel, shinNear + FOOT_BACK)
        val toeNear = swing(footNear, toes, shinNear + FOOT_FORWARD)
        val heelFar = swing(footFar, heel, shinFar + FOOT_BACK)
        val toeFar = swing(footFar, toes, shinFar + FOOT_FORWARD)

        val body = mapOf(
            Joint.PELVIS to pelvis,
            Joint.CHEST to chest,
            Joint.NECK to neck,
            Joint.HEAD to head,
            Joint.SHOULDER_NEAR to shoulderNear, Joint.ELBOW_NEAR to elbowNear,
            Joint.HAND_NEAR to handNear,
            Joint.SHOULDER_FAR to shoulderFar, Joint.ELBOW_FAR to elbowFar,
            Joint.HAND_FAR to handFar,
            Joint.HIP_NEAR to hipNear, Joint.KNEE_NEAR to kneeNear,
            Joint.FOOT_NEAR to footNear,
            Joint.HIP_FAR to hipFar, Joint.KNEE_FAR to kneeFar, Joint.FOOT_FAR to footFar,
            Joint.HEEL_NEAR to heelNear, Joint.TOE_NEAR to toeNear,
            Joint.HEEL_FAR to heelFar, Joint.TOE_FAR to toeFar,
        )

        // Projected, then placed. The pelvis keeps the position it always had,
        // so drift and the guide's framing are unchanged and only the shape of
        // the figure is different.
        val originX = 0.5f + angles.driftX
        val originY = pelvisY + angles.driftY
        // Zoomed back to the height the guide frame was built around.
        //
        // Projection foreshortens, so the same proportions come out about an
        // eighth shorter on screen -- and the proportions here are written as
        // fractions of a figure's height, so without this the guide becomes a
        // small figure adrift in a large frame. Applied to both axes, because
        // scaling only the vertical would undo the foreshortening that is the
        // entire point.
        val zoom = 1f / IsoProjection.heightScale
        return Pose(
            body.mapValues { (_, point) ->
                val screen = IsoProjection.project(point)
                JointPoint(originX + screen.x * zoom, originY + screen.y * zoom)
            },
        ).clampedToGround()
    }

    /**
     * Lifts a figure whose lowest point has gone through the floor.
     *
     * A clamp rather than a snap, and the difference matters. Snapping every
     * pose so its lowest point touches the ground would cancel the very thing a
     * walk is made of — the body rises over the planted foot and falls at each
     * contact — and leave a figure gliding along at a constant height. Clamping
     * only rescues the poses that overreach, which drawn out is most of the
     * deep ones: a lunge and a stride both put a foot below where a standing
     * figure's feet were, and a guide with a foot off the bottom of the frame
     * teaches the model to crop the character.
     */
    private fun Pose.clampedToGround(): Pose {
        val lowest = joints.values.maxOf { it.y }
        if (lowest <= GROUND) return this
        val lift = lowest - GROUND
        return Pose(joints.mapValues { (_, point) -> JointPoint(point.x, point.y - lift) })
    }

    /** How long a bone should be, for a test or a renderer that wants to check. */
    fun lengthOf(bone: Bone): Float = when (bone.from to bone.to) {
        Joint.SHOULDER_NEAR to Joint.ELBOW_NEAR, Joint.SHOULDER_FAR to Joint.ELBOW_FAR -> upperArm
        Joint.ELBOW_NEAR to Joint.HAND_NEAR, Joint.ELBOW_FAR to Joint.HAND_FAR -> foreArm
        Joint.HIP_NEAR to Joint.KNEE_NEAR, Joint.HIP_FAR to Joint.KNEE_FAR -> thigh
        Joint.KNEE_NEAR to Joint.FOOT_NEAR, Joint.KNEE_FAR to Joint.FOOT_FAR -> shin
        Joint.PELVIS to Joint.CHEST -> pelvisToChest
        Joint.CHEST to Joint.NECK -> chestToNeck
        Joint.NECK to Joint.HEAD -> neckToHead
        else -> 0f
    }

    companion object {
        /** Straight up, in the angle convention below. */
        const val UP = 180f

        /** Where the floor is. Nothing drawn may go below it. */
        const val GROUND = 0.96f

        /** The lines to draw, far side first so the near side covers it. */
        val bones: List<Bone> = listOf(
            Bone(Joint.SHOULDER_FAR, Joint.ELBOW_FAR),
            Bone(Joint.ELBOW_FAR, Joint.HAND_FAR),
            Bone(Joint.HIP_FAR, Joint.KNEE_FAR),
            Bone(Joint.KNEE_FAR, Joint.FOOT_FAR),
            Bone(Joint.PELVIS, Joint.CHEST, BoneWeight.TORSO),
            Bone(Joint.CHEST, Joint.NECK, BoneWeight.TORSO),
            Bone(Joint.NECK, Joint.HEAD, BoneWeight.TORSO),
            Bone(Joint.SHOULDER_FAR, Joint.SHOULDER_NEAR, BoneWeight.TORSO),
            Bone(Joint.HIP_FAR, Joint.HIP_NEAR, BoneWeight.TORSO),
            Bone(Joint.SHOULDER_NEAR, Joint.ELBOW_NEAR),
            Bone(Joint.ELBOW_NEAR, Joint.HAND_NEAR),
            Bone(Joint.HIP_NEAR, Joint.KNEE_NEAR),
            Bone(Joint.KNEE_NEAR, Joint.FOOT_NEAR),
        )
    }
}

/**
 * One pose, as the angle of every joint.
 *
 * Degrees clockwise from straight down, which sounds odd until you author with
 * it: an arm hanging at the side is 0, an arm straight out to the near side is
 * 90, an arm raised overhead is 180. Elbows and knees are relative to the limb
 * above them, so bending an elbow does not require recomputing where the
 * shoulder put it.
 */
data class PoseAngles(
    val shoulderNear: Float = 8f,
    val elbowNear: Float = 0f,
    val shoulderFar: Float = -8f,
    val elbowFar: Float = 0f,
    val hipNear: Float = 2f,
    val kneeNear: Float = 0f,
    val hipFar: Float = -2f,
    val kneeFar: Float = 0f,
    /**
     * How far each limb is lifted away from the body's midline.
     *
     * The second axis, and until now there wasn't one: every joint had a
     * single angle, a swing forward or back in the plane the limb hangs in. A
     * body that can only swing fore and aft cannot put an arm out to the side
     * at all -- a T-pose, the single most common reference pose there is, was
     * not a thing this skeleton could express. Poses that wanted width had to
     * fake it by swinging an arm so far forward it came round the top, which
     * is why several of them read as a figure clutching at itself.
     *
     * Zero is a limb hanging in its own plane, so every pose written before
     * this means exactly what it meant. Positive is outward, on whichever side
     * the limb is: the near arm goes towards the viewer's side of the body and
     * the far arm away, so one number reads the same on both.
     */
    val shoulderNearOut: Float = 0f,
    val shoulderFarOut: Float = 0f,
    val hipNearOut: Float = 0f,
    val hipFarOut: Float = 0f,
    val lean: Float = 0f,
    val headTilt: Float = 0f,
    /** Moves the whole body, for a crouch, a lunge or a fall. */
    val driftX: Float = 0f,
    val driftY: Float = 0f,
) {
    /**
     * Part way from this pose to [other].
     *
     * Every field is an angle or an offset, so a straight blend is the right
     * one. It exists because the authored poses and the frames asked for are
     * two different counts that will not always agree: a model asked for six
     * frames may send five, and without a blend the rig snaps between authored
     * poses, holding several frames identical and then jumping -- which reads
     * as the character freezing and twitching rather than moving.
     */
    fun blendedTo(other: PoseAngles, amount: Float): PoseAngles {
        val t = amount.coerceIn(0f, 1f)
        if (t <= 0f) return this
        if (t >= 1f) return other
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return PoseAngles(
            shoulderNearOut = mix(shoulderNearOut, other.shoulderNearOut),
            shoulderFarOut = mix(shoulderFarOut, other.shoulderFarOut),
            hipNearOut = mix(hipNearOut, other.hipNearOut),
            hipFarOut = mix(hipFarOut, other.hipFarOut),
            shoulderNear = mix(shoulderNear, other.shoulderNear),
            elbowNear = mix(elbowNear, other.elbowNear),
            shoulderFar = mix(shoulderFar, other.shoulderFar),
            elbowFar = mix(elbowFar, other.elbowFar),
            hipNear = mix(hipNear, other.hipNear),
            kneeNear = mix(kneeNear, other.kneeNear),
            hipFar = mix(hipFar, other.hipFar),
            kneeFar = mix(kneeFar, other.kneeFar),
            lean = mix(lean, other.lean),
            headTilt = mix(headTilt, other.headTilt),
            driftX = mix(driftX, other.driftX),
            driftY = mix(driftY, other.driftY),
        )
    }
}

/** Rotates a unit vector [degrees] clockwise from straight down and steps along it. */
private fun JointPoint.along(length: Float, degrees: Float): JointPoint {
    val radians = Math.toRadians(degrees.toDouble())
    // Down is (0, 1) because y grows downward; rotating clockwise on screen
    // sends it towards +x first, which is why "out to the near side" is +90.
    return JointPoint(
        x = x + (length * sin(radians)).toFloat(),
        y = y + (length * cos(radians)).toFloat(),
    )
}

/** The inverse: which angle a direction corresponds to. */
internal fun degreesFromDown(dx: Float, dy: Float): Float {
    if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return 0f
    val degrees = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble())).toFloat()
    return degrees
}

/** Unused directly, kept so the convention above stays honest under test. */
internal fun directionFromDown(degrees: Float): Pair<Float, Float> {
    val radians = Math.toRadians(degrees.toDouble())
    return sin(radians).toFloat() to cos(radians).toFloat()
}

/**
 * Where a foot points relative to the shin above it.
 *
 * A right angle either side, so the foot is flat when the shin is vertical and
 * tips automatically as the leg swings: heel down and toe up on a leg reaching
 * forward, the reverse on one trailing behind. Getting that for free is the
 * point -- describing it per pose would be ninety-six more numbers to keep
 * consistent across seven animations, and they would drift.
 */
private const val FOOT_FORWARD = -90f
private const val FOOT_BACK = 90f

/** Everything that can be the bottom of a figure. */
private val LOWEST = listOf(
    Joint.FOOT_NEAR, Joint.FOOT_FAR,
    Joint.HEEL_NEAR, Joint.HEEL_FAR,
    Joint.TOE_NEAR, Joint.TOE_FAR,
)
