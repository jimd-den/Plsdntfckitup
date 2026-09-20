package com.stratum.core.data.sprite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.stratum.core.domain.sprite.BoneWeight
import com.stratum.core.domain.sprite.Joint
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.BodySide
import com.stratum.core.domain.sprite.OpenPoseExport
import com.stratum.core.domain.sprite.OpenPoseStyle
import com.stratum.core.domain.sprite.PoseGuideStyle
import com.stratum.core.domain.sprite.Skeleton
import java.io.ByteArrayOutputStream

/**
 * Draws a pose as a stick figure, to be handed to the image model.
 *
 * The argument for doing this at all: prose is a poor way to specify a body.
 * "Right leg forward with the heel touching the ground, left arm swung forward"
 * is unambiguous to a person and merely suggestive to an image model — measured,
 * an attack described that way came back as a cross-body guard. A drawing of
 * the pose is not suggestive.
 *
 * Deliberately crude. A guide that is shaded, coloured or detailed invites the
 * model to reproduce *it* rather than to pose the character by it; flat black
 * lines on white are unmistakably a diagram. Small, too, because it rides
 * alongside a megabyte of character reference and a line drawing needs no
 * resolution to be read.
 */
object PoseGuideRenderer {

    fun render(
        pose: Pose,
        size: Int = GUIDE_SIZE,
        style: PoseGuideStyle = PoseGuideStyle.DIAGRAM,
    ): ByteArray? = when (style) {
        PoseGuideStyle.DIAGRAM -> diagram(pose, size)
        PoseGuideStyle.OPENPOSE -> openPose(pose, size)
        PoseGuideStyle.DWPOSE -> openPose(pose, size, feet = true)
    }

    /**
     * The feet, drawn on top of an OpenPose skeleton.
     *
     * This is the whole of what DWPose adds here. Each foot is the ankle, the
     * heel and the toe joined up, so the guide says which way it points and
     * how much of it is on the floor -- and it is drawn in the leg's own
     * colour, so it reads as part of that leg rather than as a separate limb
     * a model might try to account for.
     */
    private fun drawFeet(canvas: Canvas, pose: Pose, size: Int) {
        val paint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            strokeWidth = size * OPENPOSE_LIMB * FOOT_WEIGHT
        }
        val feet = listOf(
            Triple(Joint.FOOT_NEAR, Joint.HEEL_NEAR, Joint.TOE_NEAR) to NEAR_FOOT,
            Triple(Joint.FOOT_FAR, Joint.HEEL_FAR, Joint.TOE_FAR) to FAR_FOOT,
        )
        feet.forEach { (joints, color) ->
            val (ankle, heel, toe) = joints
            val a = pose.joints[ankle] ?: return@forEach
            val h = pose.joints[heel] ?: return@forEach
            val t = pose.joints[toe] ?: return@forEach
            paint.color = color
            canvas.drawLine(h.x * size, h.y * size, a.x * size, a.y * size, paint)
            canvas.drawLine(a.x * size, a.y * size, t.x * size, t.y * size, paint)
            canvas.drawLine(h.x * size, h.y * size, t.x * size, t.y * size, paint)
        }
    }

    /**
     * The canonical OpenPose rendering: coloured limbs on black.
     *
     * Worth reproducing exactly rather than approximately. Every ControlNet
     * preprocessor and every model trained alongside one has seen this specific
     * palette in this specific layout; a skeleton in different colours is a
     * picture of a skeleton rather than a control signal. It also means a pose
     * authored here drops into that ecosystem unchanged, which is the half of
     * interoperability people forget to build.
     */
    private fun openPose(pose: Pose, size: Int, feet: Boolean = false): ByteArray? {
        if (size <= 0) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val body = OpenPoseExport.fromPose(pose, nearSide = BodySide.RIGHT)

        val limbPaint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
            strokeWidth = size * OPENPOSE_LIMB
        }
        OpenPoseStyle.limbs.forEachIndexed { index, (from, to) ->
            val a = body[from] ?: return@forEachIndexed
            val b = body[to] ?: return@forEachIndexed
            limbPaint.color = OpenPoseStyle.limbColor(index)
            canvas.drawLine(a.x * size, a.y * size, b.x * size, b.y * size, limbPaint)
        }

        // Joints on top and fully opaque, which is also what makes them
        // findable again when a rendered skeleton is read back in.
        val jointPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        body.keypoints.forEach { (joint, point) ->
            if (!point.isPresent) return@forEach
            jointPaint.color = OpenPoseStyle.colorFor(joint)
            canvas.drawCircle(point.x * size, point.y * size, size * OPENPOSE_JOINT, jointPaint)
        }

        // After the body, so a foot is never hidden under an ankle marker.
        if (feet) drawFeet(canvas, pose, size)

        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    private fun diagram(pose: Pose, size: Int): ByteArray? {
        if (size <= 0) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val limb = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            strokeWidth = size * LIMB_WIDTH
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val torso = Paint(limb).apply { strokeWidth = size * TORSO_WIDTH }

        // Far side first, near side last, so the nearer limbs cover the further
        // ones exactly as they will in the drawing. Without it a stick figure
        // at a three-quarter angle is ambiguous about which arm is which.
        for (bone in Skeleton.bones) {
            val from = pose[bone.from] ?: continue
            val to = pose[bone.to] ?: continue
            canvas.drawLine(
                from.x * size, from.y * size, to.x * size, to.y * size,
                if (bone.weight == BoneWeight.TORSO) torso else limb,
            )
        }

        val head = pose[Joint.HEAD]
        if (head != null) {
            canvas.drawCircle(
                head.x * size, head.y * size, size * HEAD_RADIUS,
                Paint(limb).apply { style = Paint.Style.FILL },
            )
        }

        // The weapon hand marked, so the model knows which fist to close and
        // the person checking a guide can see the pipeline agrees with itself.
        val hand = pose[Joint.weaponHand]
        if (hand != null) {
            canvas.drawCircle(
                hand.x * size, hand.y * size, size * HAND_RADIUS,
                Paint().apply {
                    isAntiAlias = true
                    color = Color.BLACK
                    style = Paint.Style.FILL
                },
            )
        }

        val out = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    /** Big enough to read, small enough to ride alongside the character reference. */
    const val GUIDE_SIZE = 512

    private const val LIMB_WIDTH = 0.018f
    private const val OPENPOSE_LIMB = 0.016f
    private const val OPENPOSE_JOINT = 0.012f
    private const val TORSO_WIDTH = 0.032f
    private const val HEAD_RADIUS = 0.052f
    private const val HAND_RADIUS = 0.022f
    private const val PNG_QUALITY = 100

    /**
     * Feet are drawn a little thinner than limbs.
     *
     * They are short, and at limb weight a foot becomes a blob the same size
     * as the ankle it hangs off -- which says less about direction than no
     * foot at all would.
     */
    private const val FOOT_WEIGHT = 0.7f

    /** The legs' own colours in the OpenPose palette, so a foot joins its leg. */
    private const val NEAR_FOOT = 0xFF00FF00.toInt()
    private const val FAR_FOOT = 0xFF0000FF.toInt()
}
