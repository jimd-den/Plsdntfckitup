package com.stratum.core.domain.bvh

import com.stratum.core.domain.sprite.BodyPoint
import com.stratum.core.domain.sprite.BodySide
import com.stratum.core.domain.sprite.IsoProjection
import com.stratum.core.domain.sprite.Joint
import com.stratum.core.domain.sprite.JointPoint
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.Skeleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Solves forward kinematics for BVH motion capture data from the Bandai Namco
 * Research Motiondataset, projecting 3D skeleton frames into 2D character reference poses.
 *
 * Skeletons in the Bandai Namco dataset contain hierarchical Euler rotations and
 * translation offsets. This extractor:
 * 1. Computes world-space 3D transformations for each bone hierarchy.
 * 2. Maps Bandai Namco joint naming conventions to Stratum domain [Joint]s.
 * 3. Projects 3D world joints to 2D canvas coordinates using the game's [IsoProjection].
 * 4. Normalises the figure so it stands on the canonical ground line with proper framing.
 */
object BvhPoseExtractor {

    /** 3D vector representation for forward kinematics. */
    data class Vector3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
        operator fun times(scalar: Float) = Vector3(x * scalar, y * scalar, z * scalar)
    }

    /** 4x4 matrix for affine skeletal transformations. */
    class Matrix4 private constructor(val m: FloatArray) {
        constructor() : this(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        operator fun times(other: Matrix4): Matrix4 {
            val res = FloatArray(16)
            for (r in 0..3) {
                for (c in 0..3) {
                    var sum = 0f
                    for (k in 0..3) {
                        sum += this.m[r * 4 + k] * other.m[k * 4 + c]
                    }
                    res[r * 4 + c] = sum
                }
            }
            return Matrix4(res)
        }

        fun transformPoint(x: Float, y: Float, z: Float): Vector3 {
            val rx = m[0] * x + m[1] * y + m[2] * z + m[3]
            val ry = m[4] * x + m[5] * y + m[6] * z + m[7]
            val rz = m[8] * x + m[9] * y + m[10] * z + m[11]
            return Vector3(rx, ry, rz)
        }

        fun translation(): Vector3 = Vector3(m[3], m[7], m[11])

        companion object {
            fun translation(tx: Float, ty: Float, tz: Float): Matrix4 {
                val res = Matrix4()
                res.m[3] = tx
                res.m[7] = ty
                res.m[11] = tz
                return res
            }

            fun rotateX(degrees: Float): Matrix4 {
                val rad = Math.toRadians(degrees.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val res = Matrix4()
                res.m[5] = c
                res.m[6] = -s
                res.m[9] = s
                res.m[10] = c
                return res
            }

            fun rotateY(degrees: Float): Matrix4 {
                val rad = Math.toRadians(degrees.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val res = Matrix4()
                res.m[0] = c
                res.m[2] = s
                res.m[8] = -s
                res.m[10] = c
                return res
            }

            fun rotateZ(degrees: Float): Matrix4 {
                val rad = Math.toRadians(degrees.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                val res = Matrix4()
                res.m[0] = c
                res.m[1] = -s
                res.m[4] = s
                res.m[5] = c
                return res
            }
        }
    }

    /**
     * Solves forward kinematics and extracts 3D world positions of all joints
     * in a given frame of a BVH clip.
     */
    fun computeWorldJoints(clip: BvhClip, frameIndex: Int): Map<String, Vector3> {
        val frame = clip.frameAt(frameIndex) ?: return emptyMap()
        val positions = mutableMapOf<String, Vector3>()
        var channelCursor = 0

        fun evaluateJoint(joint: BvhJoint, parentTransform: Matrix4) {
            var tx = joint.offset[0]
            var ty = joint.offset[1]
            var tz = joint.offset[2]

            var rotationMatrix = Matrix4()

            for (ch in joint.channels) {
                if (channelCursor >= frame.values.size) break
                val value = frame.values[channelCursor++]
                when (ch) {
                    BvhChannelType.XPOSITION -> tx += value
                    BvhChannelType.YPOSITION -> ty += value
                    BvhChannelType.ZPOSITION -> tz += value
                    BvhChannelType.XROTATION -> rotationMatrix = rotationMatrix * Matrix4.rotateX(value)
                    BvhChannelType.YROTATION -> rotationMatrix = rotationMatrix * Matrix4.rotateY(value)
                    BvhChannelType.ZROTATION -> rotationMatrix = rotationMatrix * Matrix4.rotateZ(value)
                }
            }

            val localTransform = Matrix4.translation(tx, ty, tz) * rotationMatrix
            val globalTransform = parentTransform * localTransform

            positions[joint.name.lowercase()] = globalTransform.translation()

            if (joint.endSiteOffset != null) {
                val endPos = globalTransform.transformPoint(
                    joint.endSiteOffset[0],
                    joint.endSiteOffset[1],
                    joint.endSiteOffset[2],
                )
                positions["${joint.name.lowercase()}_end"] = endPos
            }

            for (child in joint.children) {
                evaluateJoint(child, globalTransform)
            }
        }

        evaluateJoint(clip.root, Matrix4())
        return positions
    }

    /**
     * Extracts a full Stratum [Pose] from a BVH clip frame.
     *
     * @param clip The parsed BVH motion capture clip.
     * @param frameIndex The index of the frame to extract.
     * @param nearSide Which side of the character is nearer to the camera.
     * @param targetGround The Y level on canvas to align the lowest foot joint with.
     */
    fun extractPose(
        clip: BvhClip,
        frameIndex: Int,
        nearSide: BodySide = BodySide.RIGHT,
        targetGround: Float = Skeleton.GROUND,
    ): Pose? {
        val worldJoints = computeWorldJoints(clip, frameIndex)
        if (worldJoints.isEmpty()) return null

        fun findJoint(vararg names: String): Vector3? {
            for (name in names) {
                worldJoints[name.lowercase()]?.let { return it }
            }
            return null
        }

        val pelvis = findJoint("hips", "root", "pelvis") ?: return null
        val chest = findJoint("spine1", "spine2", "spine", "chest")
            ?: pelvis.let { Vector3(it.x, it.y + 15f, it.z) }
        val neck = findJoint("neck", "neck1")
            ?: chest.let { Vector3(it.x, it.y + 10f, it.z) }
        val head = findJoint("head_end", "head", "headtop_end")
            ?: neck.let { Vector3(it.x, it.y + 10f, it.z) }

        val isRightNear = nearSide == BodySide.RIGHT

        // Arms
        val rShoulder = findJoint("rightshoulder", "rightcollar", "r_shoulder")
            ?: chest.let { Vector3(it.x + 8f, it.y, it.z) }
        val rArm = findJoint("rightarm", "rightuparm", "r_arm") ?: rShoulder
        val rElbow = findJoint("rightforearm", "rightlowarm", "r_forearm")
            ?: rArm.let { Vector3(it.x + 8f, it.y - 10f, it.z) }
        val rHand = findJoint("righthand", "rightwrist", "r_hand")
            ?: rElbow.let { Vector3(it.x, it.y - 10f, it.z) }

        val lShoulder = findJoint("leftshoulder", "leftcollar", "l_shoulder")
            ?: chest.let { Vector3(it.x - 8f, it.y, it.z) }
        val lArm = findJoint("leftarm", "leftuparm", "l_arm") ?: lShoulder
        val lElbow = findJoint("leftforearm", "leftlowarm", "l_forearm")
            ?: lArm.let { Vector3(it.x - 8f, it.y - 10f, it.z) }
        val lHand = findJoint("lefthand", "leftwrist", "l_hand")
            ?: lElbow.let { Vector3(it.x, it.y - 10f, it.z) }

        // Legs
        val rHip = findJoint("rightupleg", "rightthigh", "rightup_leg", "r_upleg")
            ?: pelvis.let { Vector3(it.x + 5f, it.y, it.z) }
        val rKnee = findJoint("rightleg", "rightlowleg", "rightknee", "r_leg")
            ?: rHip.let { Vector3(it.x, it.y - 20f, it.z) }
        val rFoot = findJoint("rightfoot", "rightankle", "r_foot")
            ?: rKnee.let { Vector3(it.x, it.y - 20f, it.z) }
        val rToe = findJoint("righttoebase", "righttoe", "rightfoot_end", "r_toe")
            ?: rFoot.let { Vector3(it.x, it.y, it.z + 5f) }
        val rHeel = rFoot - (rToe - rFoot) * 0.35f

        val lHip = findJoint("leftupleg", "leftthigh", "leftup_leg", "l_upleg")
            ?: pelvis.let { Vector3(it.x - 5f, it.y, it.z) }
        val lKnee = findJoint("leftleg", "leftlowleg", "leftknee", "l_leg")
            ?: lHip.let { Vector3(it.x, it.y - 20f, it.z) }
        val lFoot = findJoint("leftfoot", "leftankle", "l_foot")
            ?: lKnee.let { Vector3(it.x, it.y - 20f, it.z) }
        val lToe = findJoint("lefttoebase", "lefttoe", "leftfoot_end", "l_toe")
            ?: lFoot.let { Vector3(it.x, it.y, it.z + 5f) }
        val lHeel = lFoot - (lToe - lFoot) * 0.35f

        // Near/Far assignment
        val shoulderNear = if (isRightNear) rShoulder else lShoulder
        val elbowNear = if (isRightNear) rElbow else lElbow
        val handNear = if (isRightNear) rHand else lHand

        val shoulderFar = if (isRightNear) lShoulder else rShoulder
        val elbowFar = if (isRightNear) lElbow else rElbow
        val handFar = if (isRightNear) lHand else rHand

        val hipNear = if (isRightNear) rHip else lHip
        val kneeNear = if (isRightNear) rKnee else lKnee
        val footNear = if (isRightNear) rFoot else lFoot
        val toeNear = if (isRightNear) rToe else lToe
        val heelNear = if (isRightNear) rHeel else lHeel

        val hipFar = if (isRightNear) lHip else rHip
        val kneeFar = if (isRightNear) lKnee else rKnee
        val footFar = if (isRightNear) lFoot else rFoot
        val toeFar = if (isRightNear) lToe else rToe
        val heelFar = if (isRightNear) lHeel else rHeel

        // Project relative to pelvis
        fun toBodyPoint(p: Vector3): BodyPoint = BodyPoint(
            lateral = p.x - pelvis.x,
            up = p.y - pelvis.y,
            forward = p.z - pelvis.z,
        )

        val body3D = mapOf(
            Joint.PELVIS to toBodyPoint(pelvis),
            Joint.CHEST to toBodyPoint(chest),
            Joint.NECK to toBodyPoint(neck),
            Joint.HEAD to toBodyPoint(head),
            Joint.SHOULDER_NEAR to toBodyPoint(shoulderNear),
            Joint.ELBOW_NEAR to toBodyPoint(elbowNear),
            Joint.HAND_NEAR to toBodyPoint(handNear),
            Joint.SHOULDER_FAR to toBodyPoint(shoulderFar),
            Joint.ELBOW_FAR to toBodyPoint(elbowFar),
            Joint.HAND_FAR to toBodyPoint(handFar),
            Joint.HIP_NEAR to toBodyPoint(hipNear),
            Joint.KNEE_NEAR to toBodyPoint(kneeNear),
            Joint.FOOT_NEAR to toBodyPoint(footNear),
            Joint.TOE_NEAR to toBodyPoint(toeNear),
            Joint.HEEL_NEAR to toBodyPoint(heelNear),
            Joint.HIP_FAR to toBodyPoint(hipFar),
            Joint.KNEE_FAR to toBodyPoint(kneeFar),
            Joint.FOOT_FAR to toBodyPoint(footFar),
            Joint.TOE_FAR to toBodyPoint(toeFar),
            Joint.HEEL_FAR to toBodyPoint(heelFar),
        )

        val projected = body3D.mapValues { (_, pt) -> IsoProjection.project(pt) }

        return normaliseToCanvas(projected, targetGround)
    }

    /**
     * Normalises projected joint points to fit neatly on a 1.0 x 1.0 canvas,
     * maintaining aspect ratio, centered horizontally, with feet on the ground line.
     */
    private fun normaliseToCanvas(
        projected: Map<Joint, JointPoint>,
        targetGround: Float,
    ): Pose {
        val points = projected.values
        if (points.isEmpty()) return Pose(projected)

        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val height = maxY - minY

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val centreX = (minX + maxX) / 2f

        val targetTop = 0.06f // leaves clean headroom for head/helmet
        val scale = if (height > 0.0001f) (targetGround - targetTop) / height else 1f

        val normalised = projected.mapValues { (_, pt) ->
            JointPoint(
                x = 0.5f + (pt.x - centreX) * scale,
                y = targetTop + (pt.y - minY) * scale,
            )
        }

        val lowest = normalised.values.maxOf { it.y }
        val lift = if (lowest > targetGround) lowest - targetGround else 0f
        return Pose(normalised.mapValues { (_, pt) -> JointPoint(pt.x, pt.y - lift) })
    }
}
