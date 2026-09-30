package com.stratum.core.domain.sprite

/**
 * The keypoint orders OpenPose and its ecosystem actually emit.
 *
 * Two, because both are in circulation and they are not compatible. Tooling
 * built on OpenPose itself speaks BODY_18, which has an explicit neck. Pose
 * libraries and anything built on the COCO keypoint standard speak BODY_17,
 * which does not — its neck has to be derived from the shoulders. A file read
 * with the wrong layout does not fail, it silently produces a person whose
 * elbows are where their hips should be, so the layout is named rather than
 * guessed wherever possible.
 */
private const val FACE_AND_HANDS = 110

enum class OpenPoseLayout(val keypointCount: Int, val order: List<OpenPoseJoint?>) {

    /** OpenPose's own COCO output: neck included, right side first. */
    BODY_18(
        18,
        listOf(
            OpenPoseJoint.NOSE, OpenPoseJoint.NECK,
            OpenPoseJoint.RIGHT_SHOULDER, OpenPoseJoint.RIGHT_ELBOW, OpenPoseJoint.RIGHT_WRIST,
            OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.LEFT_WRIST,
            OpenPoseJoint.RIGHT_HIP, OpenPoseJoint.RIGHT_KNEE, OpenPoseJoint.RIGHT_ANKLE,
            OpenPoseJoint.LEFT_HIP, OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.LEFT_ANKLE,
            OpenPoseJoint.RIGHT_EYE, OpenPoseJoint.LEFT_EYE,
            OpenPoseJoint.RIGHT_EAR, OpenPoseJoint.LEFT_EAR,
        ),
    ),

    /** The COCO keypoint standard: no neck, left side first, face keypoints up front. */
    BODY_17(
        17,
        listOf(
            OpenPoseJoint.NOSE,
            OpenPoseJoint.LEFT_EYE, OpenPoseJoint.RIGHT_EYE,
            OpenPoseJoint.LEFT_EAR, OpenPoseJoint.RIGHT_EAR,
            OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.RIGHT_SHOULDER,
            OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.RIGHT_ELBOW,
            OpenPoseJoint.LEFT_WRIST, OpenPoseJoint.RIGHT_WRIST,
            OpenPoseJoint.LEFT_HIP, OpenPoseJoint.RIGHT_HIP,
            OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.RIGHT_KNEE,
            OpenPoseJoint.LEFT_ANKLE, OpenPoseJoint.RIGHT_ANKLE,
        ),
    ),

    /**
     * DWPose and anything else emitting COCO-WholeBody.
     *
     * The same seventeen body keypoints as [BODY_17] and then six for the
     * feet, which is the reason to prefer it. An ankle says where a leg ends;
     * a heel and a toe say which way the foot points and how much of it is
     * down, and that is what a walk is made of. A guide drawn without them
     * puts the feet wherever the model feels like, and the frames stop being
     * a cycle and start being a set of standing poses.
     *
     * The remaining hundred and ten are face and finger joints. They are
     * carried as empty slots rather than dropped, because the positions of
     * everything after them depend on their being counted -- reading a
     * whole-body file as if the feet were followed by the next body joint
     * puts a knee where an eyebrow is.
     */
    DW_WHOLEBODY(
        133,
        BODY_17.order +
            listOf(
                OpenPoseJoint.LEFT_BIG_TOE,
                OpenPoseJoint.LEFT_SMALL_TOE,
                OpenPoseJoint.LEFT_HEEL,
                OpenPoseJoint.RIGHT_BIG_TOE,
                OpenPoseJoint.RIGHT_SMALL_TOE,
                OpenPoseJoint.RIGHT_HEEL,
            ) +
            List(FACE_AND_HANDS) { null },
    ),

    /**
     * The body and feet of a whole-body file, with the face and hands cut.
     *
     * What a person gets when they export only the parts a character guide
     * uses, which several tools offer and which is a great deal smaller.
     */
    DW_BODY_FOOT(
        23,
        DW_WHOLEBODY.order.take(23),
    ),
    ;

    /**
     * The joints this layout actually names, with the empty slots removed.
     *
     * For everything but a whole-body layout this is the order itself. It
     * exists so callers that want "the joints" do not have to carry the gaps
     * that only exist to keep a whole-body file's later positions counted.
     */
    val joints: List<OpenPoseJoint> get() = order.filterNotNull()

    companion object {
        /** The layout a file of this many keypoints must be, when it is one we know. */
        fun forKeypointCount(count: Int): OpenPoseLayout? =
            entries.firstOrNull { it.keypointCount == count }
    }
}

/** A keypoint, named rather than indexed, because indices differ between layouts. */
enum class OpenPoseJoint {
    NOSE, NECK,
    LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR,
    LEFT_SHOULDER, RIGHT_SHOULDER,
    LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST,
    LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE,
    LEFT_ANKLE, RIGHT_ANKLE,

    /**
     * Feet, which only whole-body estimators report.
     *
     * An ankle says where the leg ends and nothing about which way the foot is
     * pointing or how much of it is down. That is the difference between a
     * guide that says "stand here" and one that says "stand here, weight on
     * this heel, that toe still trailing" -- and it is the part of a walk the
     * eye reads as contact with the ground.
     */
    LEFT_BIG_TOE, LEFT_SMALL_TOE, LEFT_HEEL,
    RIGHT_BIG_TOE, RIGHT_SMALL_TOE, RIGHT_HEEL,
}

/** One keypoint as OpenPose reports it: a position and how sure it is. */
data class OpenPoseKeypoint(val x: Float, val y: Float, val confidence: Float) {
    /**
     * OpenPose marks a keypoint it could not find as 0,0 with zero confidence,
     * and 0,0 is a perfectly valid corner of an image — so the confidence is
     * the only thing that distinguishes "at the top-left" from "not there".
     */
    val isPresent: Boolean get() = confidence > 0f
}

/**
 * A body as OpenPose describes it.
 *
 * Coordinates are fractions of the image, not pixels. Files come in both — the
 * JSON is usually pixels and a rendered PNG is obviously pixels — and are
 * normalised on the way in, because everything downstream works in fractions of
 * a frame and a pose that remembered it came from a 768-pixel canvas would only
 * be usable at 768 pixels.
 */
data class OpenPoseBody(val keypoints: Map<OpenPoseJoint, OpenPoseKeypoint>) {

    operator fun get(joint: OpenPoseJoint): OpenPoseKeypoint? =
        keypoints[joint]?.takeIf { it.isPresent }

    val presentCount: Int get() = keypoints.count { it.value.isPresent }

    /**
     * The neck, derived when the layout does not carry one.
     *
     * BODY_17 has no neck keypoint, and the skeleton needs one to hang the head
     * and the arms from. The midpoint of the shoulders is what every renderer
     * that faces this uses, and it is right: the neck is between the shoulders.
     */
    fun neck(): OpenPoseKeypoint? {
        this[OpenPoseJoint.NECK]?.let { return it }
        val left = this[OpenPoseJoint.LEFT_SHOULDER] ?: return null
        val right = this[OpenPoseJoint.RIGHT_SHOULDER] ?: return null
        return OpenPoseKeypoint(
            x = (left.x + right.x) / 2f,
            y = (left.y + right.y) / 2f,
            confidence = minOf(left.confidence, right.confidence),
        )
    }

    /** The pelvis, likewise: OpenPose has hips but no single root. */
    fun pelvis(): OpenPoseKeypoint? {
        val left = this[OpenPoseJoint.LEFT_HIP] ?: return null
        val right = this[OpenPoseJoint.RIGHT_HIP] ?: return null
        return OpenPoseKeypoint(
            x = (left.x + right.x) / 2f,
            y = (left.y + right.y) / 2f,
            confidence = minOf(left.confidence, right.confidence),
        )
    }

    /**
     * Whether there is enough of a body here to pose a character from.
     *
     * A partial detection is normal and usually fine — a missing ear costs
     * nothing. A missing wrist is not fine, because the wrist is where the
     * weapon goes, and the whole reason for reading keypoints rather than just
     * using the picture is to know where the hands are.
     */
    fun isUsable(weaponSide: BodySide): Boolean {
        val wrist = if (weaponSide == BodySide.LEFT) {
            OpenPoseJoint.LEFT_WRIST
        } else {
            OpenPoseJoint.RIGHT_WRIST
        }
        return neck() != null && pelvis() != null && this[wrist] != null
    }
}

/** Which of the subject's sides. Not which side of the screen. */
enum class BodySide { LEFT, RIGHT }

/**
 * Reads OpenPose keypoints into the skeleton the rest of the engine speaks.
 *
 * The conversion that matters is the one nobody writes down: OpenPose names
 * sides from the *subject's* point of view, and this engine names them from the
 * *camera's* — near and far — because everything is drawn at one three-quarter
 * angle and a pose should not need rewriting when a character mirrors. Which of
 * the subject's sides is nearer depends entirely on which way they are facing,
 * so it is a parameter rather than an assumption. Getting it wrong puts the
 * sword in the hand the viewer cannot see.
 */
object OpenPoseImport {

    /**
     * Flattens a `pose_keypoints_2d` array into named keypoints.
     *
     * The array is triples — x, y, confidence — in the layout's order. A length
     * that is not three times a known keypoint count is not a pose file, and is
     * rejected rather than read as far as it goes: a truncated skeleton reads as
     * a person with no legs, which is worse than an error.
     */
    fun fromFlatArray(
        values: List<Float>,
        layout: OpenPoseLayout? = null,
        imageWidth: Float = 1f,
        imageHeight: Float = 1f,
    ): OpenPoseBody? {
        if (values.isEmpty() || values.size % 3 != 0) return null
        val count = values.size / 3
        val resolved = layout?.takeIf { it.keypointCount == count }
            ?: OpenPoseLayout.forKeypointCount(count)
            ?: return null
        if (imageWidth <= 0f || imageHeight <= 0f) return null

        val keypoints = HashMap<OpenPoseJoint, OpenPoseKeypoint>()
        resolved.order.forEachIndexed { index, joint ->
            // Null slots are keypoints the layout carries and this model does
            // not: a whole-body file is mostly face and finger joints, which a
            // stick guide for a game character has no use for and which would
            // only crowd the drawing.
            if (joint == null) return@forEachIndexed
            val at = index * 3
            keypoints[joint] = OpenPoseKeypoint(
                x = values[at] / imageWidth,
                y = values[at + 1] / imageHeight,
                confidence = values[at + 2],
            )
        }
        return OpenPoseBody(keypoints)
    }

    /**
     * Turns a detected body into a [Pose] the renderer and the weapon rig can
     * use.
     *
     * Missing keypoints fall back to the resting skeleton rather than being
     * dropped, because a [Pose] with holes in it would have to be checked for
     * holes at every point of use — and a guide missing an ankle is still a
     * perfectly good guide for everything above the ankle.
     */
    fun toPose(
        body: OpenPoseBody,
        weaponSide: BodySide = BodySide.RIGHT,
        nearSide: BodySide = weaponSide,
        fallback: Skeleton = Skeleton(),
    ): Pose? {
        if (!body.isUsable(weaponSide)) return null
        val resting = fallback.pose(PoseAngles())
        val neck = body.neck() ?: return null
        val pelvis = body.pelvis() ?: return null

        fun side(near: Boolean, left: OpenPoseJoint, right: OpenPoseJoint): OpenPoseJoint {
            val wanted = if (near) nearSide else opposite(nearSide)
            return if (wanted == BodySide.LEFT) left else right
        }

        fun place(joint: Joint, key: OpenPoseJoint): Pair<Joint, JointPoint> =
            joint to (body[key]?.let { JointPoint(it.x, it.y) } ?: resting.require(joint))

        val chest = JointPoint(
            x = (neck.x + pelvis.x) / 2f,
            y = neck.y + (pelvis.y - neck.y) * CHEST_ALONG_SPINE,
        )

        return Pose(
            mapOf(
                Joint.NECK to JointPoint(neck.x, neck.y),
                Joint.PELVIS to JointPoint(pelvis.x, pelvis.y),
                Joint.CHEST to chest,
                // The nose is a face keypoint, not the centre of the skull, so
                // the head is placed a little beyond it along the neck-to-nose
                // line rather than on it.
                Joint.HEAD to headFrom(neck, body[OpenPoseJoint.NOSE], resting),
                place(Joint.SHOULDER_NEAR, side(true, OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.RIGHT_SHOULDER)),
                place(Joint.ELBOW_NEAR, side(true, OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.RIGHT_ELBOW)),
                place(Joint.HAND_NEAR, side(true, OpenPoseJoint.LEFT_WRIST, OpenPoseJoint.RIGHT_WRIST)),
                place(Joint.SHOULDER_FAR, side(false, OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.RIGHT_SHOULDER)),
                place(Joint.ELBOW_FAR, side(false, OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.RIGHT_ELBOW)),
                place(Joint.HAND_FAR, side(false, OpenPoseJoint.LEFT_WRIST, OpenPoseJoint.RIGHT_WRIST)),
                place(Joint.HIP_NEAR, side(true, OpenPoseJoint.LEFT_HIP, OpenPoseJoint.RIGHT_HIP)),
                place(Joint.KNEE_NEAR, side(true, OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.RIGHT_KNEE)),
                place(Joint.FOOT_NEAR, side(true, OpenPoseJoint.LEFT_ANKLE, OpenPoseJoint.RIGHT_ANKLE)),
                place(Joint.HIP_FAR, side(false, OpenPoseJoint.LEFT_HIP, OpenPoseJoint.RIGHT_HIP)),
                place(Joint.KNEE_FAR, side(false, OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.RIGHT_KNEE)),
                place(Joint.FOOT_FAR, side(false, OpenPoseJoint.LEFT_ANKLE, OpenPoseJoint.RIGHT_ANKLE)),
            ),
        )
    }

    /**
     * Fits an imported pose into the frame the way a generated one sits in it.
     *
     * A library pose is framed for a photograph — a figure might occupy the
     * middle third of a wide image with room above its head. Handed to the
     * generator unchanged it produces a character the same size, which then
     * packs into a cell as a small figure with a lot of empty space. Scaling it
     * to stand on the same ground line as the built-in poses is what lets the
     * two sources be used interchangeably, which is the whole point of having
     * both.
     */
    fun normalised(pose: Pose, targetGround: Float = Skeleton.GROUND): Pose {
        val points = pose.joints.values
        if (points.isEmpty()) return pose
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        val height = bottom - top
        if (height <= 0f) return pose

        val scale = (targetGround - TARGET_TOP) / height
        val centreX = (points.minOf { it.x } + points.maxOf { it.x }) / 2f
        return Pose(
            pose.joints.mapValues { (_, point) ->
                JointPoint(
                    x = 0.5f + (point.x - centreX) * scale,
                    y = TARGET_TOP + (point.y - top) * scale,
                )
            },
        )
    }

    private fun headFrom(
        neck: OpenPoseKeypoint,
        nose: OpenPoseKeypoint?,
        resting: Pose,
    ): JointPoint {
        if (nose == null) return resting.require(Joint.HEAD)
        return JointPoint(
            x = neck.x + (nose.x - neck.x) * HEAD_BEYOND_NOSE,
            y = neck.y + (nose.y - neck.y) * HEAD_BEYOND_NOSE,
        )
    }

    private fun opposite(side: BodySide) =
        if (side == BodySide.LEFT) BodySide.RIGHT else BodySide.LEFT

    /** How far down the neck-to-pelvis line the chest sits. */
    private const val CHEST_ALONG_SPINE = 0.45f

    /** The nose is on the front of the face; the skull's centre is past it. */
    private const val HEAD_BEYOND_NOSE = 1.25f

    /** Where the top of a normalised figure sits, leaving a little headroom. */
    private const val TARGET_TOP = 0.06f
}

/**
 * How OpenPose draws a skeleton, exactly.
 *
 * The colours are not decoration. Every ControlNet OpenPose preprocessor and
 * every model trained alongside one has seen this specific palette on black,
 * and a skeleton drawn in different colours is a picture of a skeleton rather
 * than a control signal. Reproducing it is what makes a pose authored here
 * usable in that ecosystem — and what lets a pose downloaded from it be
 * recognised on the way back in.
 *
 * Eighteen colours because the palette is indexed by BODY_18 keypoint order,
 * which is the order OpenPose itself emits.
 */
object OpenPoseStyle {

    /** ARGB, in BODY_18 keypoint order. */
    val palette: List<Int> = listOf(
        0xFFFF0000, 0xFFFF5500, 0xFFFFAA00, 0xFFFFFF00, 0xFFAAFF00, 0xFF55FF00,
        0xFF00FF00, 0xFF00FF55, 0xFF00FFAA, 0xFF00FFFF, 0xFF00AAFF, 0xFF0055FF,
        0xFF0000FF, 0xFF5500FF, 0xFFAA00FF, 0xFFFF00FF, 0xFFFF00AA, 0xFFFF0055,
    ).map { it.toInt() }

    /**
     * The seventeen limbs, in the order the reference renderer draws them.
     *
     * Order matters for once: limbs are drawn as translucent shapes over each
     * other, so redrawing this list in a different sequence produces a
     * different image from the same pose.
     */
    val limbs: List<Pair<OpenPoseJoint, OpenPoseJoint>> = listOf(
        OpenPoseJoint.NECK to OpenPoseJoint.RIGHT_SHOULDER,
        OpenPoseJoint.NECK to OpenPoseJoint.LEFT_SHOULDER,
        OpenPoseJoint.RIGHT_SHOULDER to OpenPoseJoint.RIGHT_ELBOW,
        OpenPoseJoint.RIGHT_ELBOW to OpenPoseJoint.RIGHT_WRIST,
        OpenPoseJoint.LEFT_SHOULDER to OpenPoseJoint.LEFT_ELBOW,
        OpenPoseJoint.LEFT_ELBOW to OpenPoseJoint.LEFT_WRIST,
        OpenPoseJoint.NECK to OpenPoseJoint.RIGHT_HIP,
        OpenPoseJoint.RIGHT_HIP to OpenPoseJoint.RIGHT_KNEE,
        OpenPoseJoint.RIGHT_KNEE to OpenPoseJoint.RIGHT_ANKLE,
        OpenPoseJoint.NECK to OpenPoseJoint.LEFT_HIP,
        OpenPoseJoint.LEFT_HIP to OpenPoseJoint.LEFT_KNEE,
        OpenPoseJoint.LEFT_KNEE to OpenPoseJoint.LEFT_ANKLE,
        OpenPoseJoint.NECK to OpenPoseJoint.NOSE,
        OpenPoseJoint.NOSE to OpenPoseJoint.RIGHT_EYE,
        OpenPoseJoint.RIGHT_EYE to OpenPoseJoint.RIGHT_EAR,
        OpenPoseJoint.NOSE to OpenPoseJoint.LEFT_EYE,
        OpenPoseJoint.LEFT_EYE to OpenPoseJoint.LEFT_EAR,
    )

    /** The colour a keypoint and the limb leaving it are drawn in. */
    fun colorFor(joint: OpenPoseJoint): Int {
        val at = OpenPoseLayout.BODY_18.order.indexOf(joint)
        return if (at < 0) palette.first() else palette[at % palette.size]
    }

    /** The colour of limb [index], which is how the reference renderer picks it. */
    fun limbColor(index: Int): Int = palette[index % palette.size]
}

/**
 * Turns this engine's skeleton back into OpenPose keypoints.
 *
 * The export direction, and the reason it exists is that this is not a
 * one-way street. A pose authored here should be usable in the same tools a
 * downloaded one came from — dropped into a ControlNet preprocessor, opened in
 * a pose editor, compared against a library. A format you can only read is a
 * format you are stuck inside.
 */
object OpenPoseExport {

    /**
     * [facingX] nudges the face keypoints towards the direction the character
     * looks, because a skeleton with its nose dead centre reads as facing the
     * viewer — which is the one angle this game never draws.
     */
    fun fromPose(
        pose: Pose,
        nearSide: BodySide = BodySide.RIGHT,
        facingX: Float = 0.35f,
    ): OpenPoseBody {
        val head = pose.require(Joint.HEAD)
        val neck = pose.require(Joint.NECK)
        val headRadius = head.distanceTo(neck) * HEAD_SPAN

        fun sure(point: JointPoint) = OpenPoseKeypoint(point.x, point.y, 1f)
        fun near(left: OpenPoseJoint, right: OpenPoseJoint) =
            if (nearSide == BodySide.LEFT) left else right
        fun far(left: OpenPoseJoint, right: OpenPoseJoint) =
            if (nearSide == BodySide.LEFT) right else left

        val nose = JointPoint(head.x + headRadius * facingX, head.y + headRadius * NOSE_DROP)
        val keypoints = mutableMapOf(
            OpenPoseJoint.NOSE to sure(nose),
            OpenPoseJoint.NECK to sure(neck),
            near(OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.RIGHT_SHOULDER) to
                sure(pose.require(Joint.SHOULDER_NEAR)),
            near(OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.RIGHT_ELBOW) to
                sure(pose.require(Joint.ELBOW_NEAR)),
            near(OpenPoseJoint.LEFT_WRIST, OpenPoseJoint.RIGHT_WRIST) to
                sure(pose.require(Joint.HAND_NEAR)),
            far(OpenPoseJoint.LEFT_SHOULDER, OpenPoseJoint.RIGHT_SHOULDER) to
                sure(pose.require(Joint.SHOULDER_FAR)),
            far(OpenPoseJoint.LEFT_ELBOW, OpenPoseJoint.RIGHT_ELBOW) to
                sure(pose.require(Joint.ELBOW_FAR)),
            far(OpenPoseJoint.LEFT_WRIST, OpenPoseJoint.RIGHT_WRIST) to
                sure(pose.require(Joint.HAND_FAR)),
            near(OpenPoseJoint.LEFT_HIP, OpenPoseJoint.RIGHT_HIP) to
                sure(pose.require(Joint.HIP_NEAR)),
            near(OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.RIGHT_KNEE) to
                sure(pose.require(Joint.KNEE_NEAR)),
            near(OpenPoseJoint.LEFT_ANKLE, OpenPoseJoint.RIGHT_ANKLE) to
                sure(pose.require(Joint.FOOT_NEAR)),
            far(OpenPoseJoint.LEFT_HIP, OpenPoseJoint.RIGHT_HIP) to
                sure(pose.require(Joint.HIP_FAR)),
            far(OpenPoseJoint.LEFT_KNEE, OpenPoseJoint.RIGHT_KNEE) to
                sure(pose.require(Joint.KNEE_FAR)),
            far(OpenPoseJoint.LEFT_ANKLE, OpenPoseJoint.RIGHT_ANKLE) to
                sure(pose.require(Joint.FOOT_FAR)),
        )

        // Eyes and ears are invented from the head, because this skeleton does
        // not track a face and a preprocessor that gets none at all decides the
        // head is missing rather than that it is plain.
        val eye = headRadius * EYE_SPREAD
        keypoints[near(OpenPoseJoint.LEFT_EYE, OpenPoseJoint.RIGHT_EYE)] =
            sure(JointPoint(nose.x + eye, nose.y - eye))
        keypoints[far(OpenPoseJoint.LEFT_EYE, OpenPoseJoint.RIGHT_EYE)] =
            sure(JointPoint(nose.x - eye * 0.3f, nose.y - eye))
        keypoints[near(OpenPoseJoint.LEFT_EAR, OpenPoseJoint.RIGHT_EAR)] =
            sure(JointPoint(head.x + headRadius * EAR_SPREAD, head.y))

        return OpenPoseBody(keypoints)
    }

    /** `pose_keypoints_2d` for a given layout, ready to write to a file. */
    fun toFlatArray(
        body: OpenPoseBody,
        layout: OpenPoseLayout = OpenPoseLayout.BODY_18,
        imageWidth: Float = 1f,
        imageHeight: Float = 1f,
    ): List<Float> = layout.order.flatMap { joint ->
        val point = joint?.let { body[it] }
        if (point == null) {
            // OpenPose's own marker for "not found", and the reason
            // OpenPoseKeypoint carries a confidence at all.
            listOf(0f, 0f, 0f)
        } else {
            listOf(point.x * imageWidth, point.y * imageHeight, point.confidence)
        }
    }

    private const val HEAD_SPAN = 1.1f
    private const val NOSE_DROP = 0.1f
    private const val EYE_SPREAD = 0.28f
    private const val EAR_SPREAD = 0.5f
}
