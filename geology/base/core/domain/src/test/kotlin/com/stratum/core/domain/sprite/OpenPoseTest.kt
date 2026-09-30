package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenPoseLayoutTest {

    @Test
    fun `a flat array is read in the order its layout declares`() {
        // BODY_18 puts the right shoulder third; BODY_17 puts the left shoulder
        // sixth and has no neck at all. Reading one as the other does not fail,
        // it silently produces a person whose elbows are where their hips were.
        val values = List(18 * 3) { it.toFloat() }
        val body = assertNotNull(OpenPoseImport.fromFlatArray(values))

        assertEquals(6f, body[OpenPoseJoint.RIGHT_SHOULDER]?.x)
        assertEquals(3f, body[OpenPoseJoint.NECK]?.x)
    }

    @Test
    fun `seventeen keypoints are the COCO layout, which has no neck of its own`() {
        val values = List(17 * 3) { 1f }
        val body = assertNotNull(OpenPoseImport.fromFlatArray(values))

        assertNull(body[OpenPoseJoint.NECK], "COCO-17 does not carry a neck")
        // But one can be had: it is between the shoulders, which it does carry.
        assertNotNull(body.neck())
    }

    @Test
    fun `a truncated file is refused rather than read as far as it goes`() {
        // A half-read skeleton is a person with no legs, which is worse than
        // an error because nothing downstream would notice.
        assertNull(OpenPoseImport.fromFlatArray(List(40) { 1f }))
        assertNull(OpenPoseImport.fromFlatArray(emptyList()))
    }

    @Test
    fun `pixel coordinates are normalised against the canvas they came from`() {
        val values = MutableList(18 * 3) { 0f }
        values[0] = 384f
        values[1] = 256f
        values[2] = 0.9f
        val body = assertNotNull(
            OpenPoseImport.fromFlatArray(values, imageWidth = 768f, imageHeight = 512f),
        )
        assertEquals(0.5f, body[OpenPoseJoint.NOSE]!!.x, 0.001f)
        assertEquals(0.5f, body[OpenPoseJoint.NOSE]!!.y, 0.001f)
    }

    @Test
    fun `a keypoint OpenPose could not find is absent, not at the top left corner`() {
        val values = MutableList(18 * 3) { 1f }
        values[3 * 3 + 2] = 0f
        val body = assertNotNull(OpenPoseImport.fromFlatArray(values))
        assertNull(body[OpenPoseJoint.RIGHT_ELBOW])
    }
}

class OpenPoseJsonTest {

    private fun keypoints(fill: Float = 0.5f) =
        (0 until 18).joinToString(",") { "$fill,$fill,1" }

    @Test
    fun `OpenPose's own output shape is read`() {
        val body = assertNotNull(
            OpenPoseJson.parse("""{"version":1.3,"people":[{"pose_keypoints_2d":[${keypoints()}]}]}"""),
        )
        assertEquals(18, body.presentCount)
    }

    @Test
    fun `the most complete person wins, not the first`() {
        // A pose reference with several people in it is a photograph of a
        // crowd; the character being drawn is one person, and a bystander half
        // out of frame should not beat the subject.
        val sparse = (0 until 18).joinToString(",") { if (it < 3) "0.2,0.2,1" else "0,0,0" }
        val json = """{"people":[{"pose_keypoints_2d":[$sparse]},
            {"pose_keypoints_2d":[${keypoints()}]}]}"""
        assertEquals(18, assertNotNull(OpenPoseJson.parse(json)).presentCount)
        assertEquals(2, OpenPoseJson.parseAll(json).size)
    }

    @Test
    fun `a bare keypoints array from a pose editor is read`() {
        assertNotNull(OpenPoseJson.parse("""{"keypoints":[${keypoints()}]}"""))
    }

    @Test
    fun `keypoints given as pairs get a confidence invented for them`() {
        // A tool that omitted confidence did so because everything it kept was
        // something it was sure of.
        val pairs = (0 until 18).joinToString(",") { "[0.4,0.6]" }
        val body = assertNotNull(OpenPoseJson.parse("""{"keypoints":[$pairs]}"""))
        assertEquals(1f, body[OpenPoseJoint.NOSE]?.confidence)
    }

    @Test
    fun `a named canvas is used to normalise`() {
        val pixels = (0 until 18).joinToString(",") { "384,512,1" }
        val body = assertNotNull(
            OpenPoseJson.parse("""{"canvas_width":768,"canvas_height":1024,
                "people":[{"pose_keypoints_2d":[$pixels]}]}"""),
        )
        assertEquals(0.5f, body[OpenPoseJoint.NOSE]!!.x, 0.001f)
        assertTrue(OpenPoseJson.looksNormalised(body))
    }

    @Test
    fun `a pixel file with no canvas is spotted rather than trusted`() {
        val pixels = (0 until 18).joinToString(",") { "384,512,1" }
        val body = assertNotNull(OpenPoseJson.parse("""{"people":[{"pose_keypoints_2d":[$pixels]}]}"""))
        // Read as fractions this is a skeleton five hundred frames tall, and
        // the failure would otherwise be entirely silent.
        assertTrue(!OpenPoseJson.looksNormalised(body))
    }

    @Test
    fun `nonsense is nothing rather than an exception`() {
        assertNull(OpenPoseJson.parse("not json at all"))
        assertNull(OpenPoseJson.parse("{}"))
        assertTrue(OpenPoseJson.parseAll("[]").isEmpty())
    }
}

class OpenPoseConversionTest {

    private val skeleton = Skeleton()

    @Test
    fun `a pose survives the round trip out to OpenPose and back`() {
        val original = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 0, 4))
        val exported = OpenPoseExport.fromPose(original, nearSide = BodySide.RIGHT)
        val returned = assertNotNull(
            OpenPoseImport.toPose(exported, weaponSide = BodySide.RIGHT, nearSide = BodySide.RIGHT),
        )

        // Every joint OpenPose actually carries comes back where it started.
        // The pelvis and chest are not among them: see the test below.
        listOf(
            Joint.HAND_NEAR, Joint.ELBOW_NEAR, Joint.SHOULDER_NEAR,
            Joint.HAND_FAR, Joint.ELBOW_FAR, Joint.SHOULDER_FAR,
            Joint.FOOT_NEAR, Joint.KNEE_NEAR, Joint.HIP_NEAR,
            Joint.FOOT_FAR, Joint.KNEE_FAR, Joint.HIP_FAR,
            Joint.NECK,
        ).forEach { joint ->
            val before = original.require(joint)
            val after = returned.require(joint)
            assertEquals(before.x, after.x, 0.002f, "$joint x")
            assertEquals(before.y, after.y, 0.002f, "$joint y")
        }
    }

    @Test
    fun `the pelvis and chest survive the round trip once the camera is real`() {
        // OpenPose has no pelvis and no chest; both are rebuilt on import from
        // the joints it does carry, by taking the midpoint of the hips and of
        // the shoulders.
        //
        // That midpoint used to come back shifted, and this test pinned the
        // shift rather than papering over it: the far side of the body was
        // nudged sideways by a constant to fake a three-quarter angle, and a
        // nudge applied to one side only does not have a midpoint where the
        // spine is. Projecting properly removed it. A projection is linear, so
        // the picture of the midpoint of two hips is the midpoint of the
        // pictures -- the root reconstructs exactly, for free, and the drift
        // was never a fact about OpenPose but about the fake.
        val original = skeleton.pose(PoseAngles())
        val returned = assertNotNull(OpenPoseImport.toPose(OpenPoseExport.fromPose(original)))

        val drift = returned.require(Joint.PELVIS).x - original.require(Joint.PELVIS).x
        assertEquals(0f, drift, 0.002f, "the pelvis no longer reconstructs where it was")
        // The hips themselves are exact, which is what the legs hang from.
        assertEquals(
            original.require(Joint.HIP_NEAR).x,
            returned.require(Joint.HIP_NEAR).x,
            0.002f,
        )
    }

    @Test
    fun `the weapon hand survives the round trip, which is the point of it`() {
        val original = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 2, 4))
        val returned = assertNotNull(
            OpenPoseImport.toPose(OpenPoseExport.fromPose(original)),
        )
        assertEquals(
            original.weaponGrip().weaponDegrees,
            returned.weaponGrip().weaponDegrees,
            1f,
        )
    }

    @Test
    fun `near and far follow the side the character is turned to`() {
        // OpenPose names sides from the subject; this engine names them from
        // the camera. Getting it backwards puts the sword in the hand the
        // viewer cannot see.
        val values = MutableList(18 * 3) { 0.5f }
        fun set(joint: OpenPoseJoint, x: Float) {
            val at = OpenPoseLayout.BODY_18.order.indexOf(joint) * 3
            values[at] = x
            values[at + 1] = 0.5f
            values[at + 2] = 1f
        }
        OpenPoseLayout.BODY_18.joints.forEach { set(it, 0.5f) }
        set(OpenPoseJoint.LEFT_WRIST, 0.2f)
        set(OpenPoseJoint.RIGHT_WRIST, 0.8f)

        val body = assertNotNull(OpenPoseImport.fromFlatArray(values))
        val nearIsRight = assertNotNull(
            OpenPoseImport.toPose(body, weaponSide = BodySide.RIGHT, nearSide = BodySide.RIGHT),
        )
        val nearIsLeft = assertNotNull(
            OpenPoseImport.toPose(body, weaponSide = BodySide.LEFT, nearSide = BodySide.LEFT),
        )

        assertEquals(0.8f, nearIsRight.require(Joint.HAND_NEAR).x, 0.001f)
        assertEquals(0.2f, nearIsLeft.require(Joint.HAND_NEAR).x, 0.001f)
    }

    @Test
    fun `a body with no wrist on the weapon side is refused`() {
        // The whole reason for reading keypoints rather than just using the
        // picture is to know where the hands are.
        val values = MutableList(18 * 3) { 1f }
        val at = OpenPoseLayout.BODY_18.order.indexOf(OpenPoseJoint.RIGHT_WRIST) * 3
        values[at + 2] = 0f
        val body = assertNotNull(OpenPoseImport.fromFlatArray(values))

        assertNull(OpenPoseImport.toPose(body, weaponSide = BodySide.RIGHT))
        assertNotNull(OpenPoseImport.toPose(body, weaponSide = BodySide.LEFT))
    }

    @Test
    fun `an imported pose is scaled to stand where a generated one stands`() {
        // A library pose is framed for a photograph. Handed over unchanged it
        // produces a character that packs into its cell surrounded by nothing.
        val small = Pose(
            skeleton.pose(PoseAngles()).joints.mapValues { (_, p) ->
                JointPoint(0.4f + p.x * 0.2f, 0.3f + p.y * 0.2f)
            },
        )
        val fitted = OpenPoseImport.normalised(small)

        assertEquals(Skeleton.GROUND, fitted.joints.values.maxOf { it.y }, 0.01f)
        assertTrue(fitted.joints.values.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}

class OpenPoseImageReaderTest {

    private val size = 200

    /** Draws a filled disc, the way OpenPose draws a joint. */
    private fun disc(pixels: IntArray, cx: Int, cy: Int, r: Int, color: Int) {
        for (y in (cy - r)..(cy + r)) {
            for (x in (cx - r)..(cx + r)) {
                if (x !in 0 until size || y !in 0 until size) continue
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r * r) pixels[y * size + x] = color
            }
        }
    }

    private fun line(pixels: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            for (t in -2..2) {
                val px = x + t
                if (px in 0 until size && y in 0 until size) pixels[y * size + px] = color
            }
        }
    }

    @Test
    fun `a whole-body file keeps its body joints in the right places`() {
        // The trap this guards. A whole-body file is seventeen body points,
        // six feet, and then a hundred and ten face and finger points. Read as
        // if the feet were followed by the next body joint, a knee lands where
        // an eyebrow is -- and nothing fails, it just produces a person built
        // wrong.
        val layout = OpenPoseLayout.DW_WHOLEBODY
        assertEquals(133, layout.keypointCount)
        assertEquals(23, layout.joints.size, "only the body and the feet are modelled")

        val values = MutableList(133 * 3) { 0f }
        fun place(index: Int, x: Float, y: Float) {
            values[index * 3] = x
            values[index * 3 + 1] = y
            values[index * 3 + 2] = 1f
        }
        // Body in COCO order, then the six feet.
        place(15, 40f, 180f)   // left ankle
        place(16, 60f, 180f)   // right ankle
        place(19, 36f, 196f)   // left heel
        place(22, 64f, 196f)   // right heel
        // A face point far away, which must not be mistaken for anything.
        place(60, 999f, 999f)

        val body = assertNotNull(
            OpenPoseImport.fromFlatArray(values, layout, imageWidth = 200f, imageHeight = 200f),
        )
        assertEquals(0.20f, assertNotNull(body[OpenPoseJoint.LEFT_ANKLE]).x, 0.001f)
        assertEquals(0.30f, assertNotNull(body[OpenPoseJoint.RIGHT_ANKLE]).x, 0.001f)
        assertEquals(0.98f, assertNotNull(body[OpenPoseJoint.LEFT_HEEL]).y, 0.001f)
        assertEquals(0.32f, assertNotNull(body[OpenPoseJoint.RIGHT_HEEL]).x, 0.001f)
        // Nothing picked up the face point.
        assertTrue(
            body.keypoints.values.none { it.x > 1.5f },
            "a face keypoint was read as a body joint",
        )
    }

    @Test
    fun `a body-and-feet export is the front of a whole-body file`() {
        // Tools that offer to drop the face and hands emit the first
        // twenty-three, so the two layouts have to agree about what those are
        // or a trimmed file reads as a different skeleton.
        assertEquals(
            OpenPoseLayout.DW_WHOLEBODY.order.take(23),
            OpenPoseLayout.DW_BODY_FOOT.order,
        )
        assertEquals(23, OpenPoseLayout.DW_BODY_FOOT.keypointCount)
    }

    @Test
    fun `a layout is recognised by how many keypoints it carries`() {
        assertEquals(OpenPoseLayout.BODY_18, OpenPoseLayout.forKeypointCount(18))
        assertEquals(OpenPoseLayout.BODY_17, OpenPoseLayout.forKeypointCount(17))
        assertEquals(OpenPoseLayout.DW_BODY_FOOT, OpenPoseLayout.forKeypointCount(23))
        assertEquals(OpenPoseLayout.DW_WHOLEBODY, OpenPoseLayout.forKeypointCount(133))
    }

    @Test
    fun `a rendered skeleton is read back into keypoints`() {
        val pixels = IntArray(size * size) { 0xFF000000.toInt() }
        // Every joint at a known spot, in its own palette colour.
        val placed = OpenPoseLayout.BODY_18.joints.mapIndexed { index, joint ->
            val cx = 30 + (index % 6) * 28
            val cy = 30 + (index / 6) * 55
            disc(pixels, cx, cy, 7, OpenPoseStyle.palette[index])
            joint to (cx to cy)
        }

        val body = assertNotNull(OpenPoseImageReader.read(pixels, size, size))
        placed.forEach { (joint, at) ->
            val found = assertNotNull(body[joint], "$joint was not found")
            assertEquals(at.first / size.toFloat(), found.x, 0.02f, "$joint x")
            assertEquals(at.second / size.toFloat(), found.y, 0.02f, "$joint y")
        }
    }

    @Test
    fun `a limb drawn in a joint's own colour does not drag the joint along it`() {
        // The failure this guards: limbs share the palette, so a renderer that
        // draws them opaque puts a long streak of a joint's colour across the
        // image. Taking the centroid of everything matching would put the
        // "joint" halfway down the arm.
        val pixels = IntArray(size * size) { 0xFF000000.toInt() }
        val color = OpenPoseStyle.palette[4]
        line(pixels, 20, 20, 170, 170, color)
        disc(pixels, 40, 150, 8, color)

        val found = assertNotNull(
            OpenPoseImageReader.read(pixels, size, size)
                ?.get(OpenPoseLayout.BODY_18.joints[4])
                ?: run {
                    // Not enough other joints for a whole body; read the disc
                    // directly by giving the rest of the palette somewhere too.
                    OpenPoseLayout.BODY_18.joints.forEachIndexed { index, _ ->
                        if (index != 4) disc(pixels, 10 + index * 3, 190, 4, OpenPoseStyle.palette[index])
                    }
                    OpenPoseImageReader.read(pixels, size, size)?.get(OpenPoseLayout.BODY_18.joints[4])
                },
        )
        assertEquals(40 / size.toFloat(), found.x, 0.03f)
        assertEquals(150 / size.toFloat(), found.y, 0.03f)
    }

    @Test
    fun `a picture that is not a skeleton is not read as one`() {
        // A photograph with a red jumper in it should not come back as a person
        // standing inside the sleeve.
        val pixels = IntArray(size * size) { 0xFF000000.toInt() }
        disc(pixels, 100, 100, 30, OpenPoseStyle.palette[0])
        assertNull(OpenPoseImageReader.read(pixels, size, size))
    }

    @Test
    fun `an empty or malformed image is nothing rather than a crash`() {
        assertNull(OpenPoseImageReader.read(IntArray(0), 0, 0))
        assertNull(OpenPoseImageReader.read(IntArray(10), 100, 100))
    }
}

class PoseGuidesTest {

    @Test
    fun `words only supplies no guide but still rigs the weapon`() {
        // Losing the guide loses pose fidelity; it should not lose the sword.
        val guides = PoseGuides(mode = PoseGuideMode.NONE)
        assertNull(guides.poseFor(AnimationState.ATTACK, 0, 4))
        assertNotNull(guides.riggingPoseFor(AnimationState.ATTACK, 0, 4))
    }

    @Test
    fun `an imported pose replaces the built-in one for its own frame only`() {
        val custom = Skeleton().pose(PoseAngles(shoulderNear = 123f))
        val guides = PoseGuides().withImported(PoseCell.keyOf(AnimationState.ATTACK, 1), custom)

        assertEquals(custom, guides.poseFor(AnimationState.ATTACK, 1, 4))
        // Filling a library set frame by frame is normal; a gap must fall back
        // rather than disable the guide.
        assertNotNull(guides.poseFor(AnimationState.ATTACK, 2, 4))
        assertTrue(guides.poseFor(AnimationState.ATTACK, 2, 4) != custom)
    }

    @Test
    fun `the weapon is rigged against whatever the art was posed from`() {
        val custom = Skeleton().pose(PoseAngles(shoulderNear = 90f, elbowNear = 0f))
        val guides = PoseGuides().withImported(PoseCell.keyOf(AnimationState.ATTACK, 0), custom)

        val builtIn = assertNotNull(WeaponPosing.anchorFor(AnimationState.ATTACK, 0, 4))
        val imported = assertNotNull(
            WeaponPosing.anchorFor(AnimationState.ATTACK, 0, 4, guides),
        )
        assertTrue(
            builtIn.xFraction != imported.xFraction || builtIn.yFraction != imported.yFraction,
            "the rig ignored the imported pose and stayed on the built-in one",
        )
        assertEquals(custom.require(Joint.HAND_NEAR).x, imported.xFraction, 0.0001f)
    }

    @Test
    fun `importing a pose switches the mode, so it is actually used`() {
        val guides = PoseGuides(mode = PoseGuideMode.BUILT_IN)
            .withImported(PoseCell.keyOf(AnimationState.IDLE, 0), Skeleton().pose(PoseAngles()))
        assertEquals(PoseGuideMode.IMPORTED, guides.mode)
    }
}

/**
 * A real pose from a real library, copied out of the page that serves it.
 *
 * Seventeen COCO keypoints as `{x, y}` objects, already normalised. Worth
 * having verbatim rather than paraphrased: every reader here was written
 * against the three shapes that were *documented*, and this fourth one — the
 * one an actual library actually ships — would have been rejected as not a pose
 * at all.
 */
class RealLibraryPoseTest {

    /** "Arms crossed": note the wrists cross, so left sits right of right. */
    private val armsCrossed = """
        {"keypoints":[
          {"x":0.5,"y":0.1},{"x":0.488,"y":0.088},{"x":0.512,"y":0.088},
          {"x":0.472,"y":0.103},{"x":0.528,"y":0.103},
          {"x":0.43,"y":0.18},{"x":0.57,"y":0.18},
          {"x":0.38,"y":0.32},{"x":0.62,"y":0.32},
          {"x":0.58,"y":0.36},{"x":0.42,"y":0.36},
          {"x":0.46,"y":0.52},{"x":0.54,"y":0.52},
          {"x":0.45,"y":0.72},{"x":0.55,"y":0.72},
          {"x":0.44,"y":0.93},{"x":0.56,"y":0.93}],"size":640}
    """.trimIndent()

    /** "Arms above head": keypoints leave the frame, which real poses do. */
    private val armsAboveHead = """
        {"keypoints":[
          {"x":0.5,"y":0.12},{"x":0.488,"y":0.108},{"x":0.512,"y":0.108},
          {"x":0.472,"y":0.123},{"x":0.528,"y":0.123},
          {"x":0.43,"y":0.18},{"x":0.57,"y":0.18},
          {"x":0.38,"y":0.08},{"x":0.62,"y":0.08},
          {"x":0.4,"y":-0.02},{"x":0.6,"y":-0.02},
          {"x":0.46,"y":0.52},{"x":0.54,"y":0.52},
          {"x":0.45,"y":0.72},{"x":0.55,"y":0.72},
          {"x":0.44,"y":0.93},{"x":0.56,"y":0.93}],"size":220}
    """.trimIndent()

    @Test
    fun `keypoints given as objects are read`() {
        val body = assertNotNull(OpenPoseJson.parse(armsCrossed))
        assertEquals(17, body.presentCount)
        // Seventeen keypoints is COCO, which carries no neck of its own.
        assertNull(body[OpenPoseJoint.NECK])
        assertNotNull(body.neck())
    }

    @Test
    fun `the COCO order is the one the library actually uses`() {
        val body = assertNotNull(OpenPoseJson.parse(armsCrossed))
        assertEquals(0.5f, body[OpenPoseJoint.NOSE]!!.x, 0.001f)
        assertEquals(0.43f, body[OpenPoseJoint.LEFT_SHOULDER]!!.x, 0.001f)
        assertEquals(0.57f, body[OpenPoseJoint.RIGHT_SHOULDER]!!.x, 0.001f)
        // Crossed arms: the left wrist really is to the right of the right one.
        assertTrue(body[OpenPoseJoint.LEFT_WRIST]!!.x > body[OpenPoseJoint.RIGHT_WRIST]!!.x)
    }

    @Test
    fun `a real pose converts to something the weapon can be rigged against`() {
        val body = assertNotNull(OpenPoseJson.parse(armsCrossed))
        val pose = assertNotNull(OpenPoseImport.toPose(body, weaponSide = BodySide.RIGHT))

        assertEquals(0.42f, pose.require(Joint.HAND_NEAR).x, 0.001f)
        // And a grip falls straight out of it, which is the whole point of
        // reading keypoints rather than just using the picture.
        assertTrue(pose.weaponGrip().weaponDegrees.isFinite())
    }

    @Test
    fun `a pose that reaches outside the frame is kept, then brought back in`() {
        // Arms above the head put wrists at y = -0.02. A reader that discarded
        // out-of-frame points would quietly drop the most extreme poses, which
        // are the ones worth importing.
        val body = assertNotNull(OpenPoseJson.parse(armsAboveHead))
        assertEquals(-0.02f, body[OpenPoseJoint.LEFT_WRIST]!!.y, 0.001f)

        val fitted = OpenPoseImport.normalised(
            assertNotNull(OpenPoseImport.toPose(body, weaponSide = BodySide.RIGHT)),
        )
        assertTrue(fitted.joints.values.all { it.y in 0f..1f }, "a joint stayed off the frame")
    }

    @Test
    fun `the library's own canvas size does not rescale already normalised points`() {
        // "size":640 is the SVG it draws into, not a coordinate space: the
        // points are fractions already. Treating it as a canvas would divide
        // them again and produce a skeleton the size of a full stop.
        val body = assertNotNull(OpenPoseJson.parse(armsCrossed))
        assertTrue(OpenPoseJson.looksNormalised(body))
    }
}

/**
 * Mirroring, which the measurements demanded.
 *
 * Handed a guide with the weapon arm extended one way, the model reproduced the
 * shape of the pose and drew it on the other side. Mirroring the guide and
 * asking again produced the same handedness, so it is the model's preference
 * rather than a coin toss, and the rig has to be able to follow it.
 */
class WeaponMirrorTest {

    private val anchor = WeaponAnchor(
        xFraction = 0.86f,
        yFraction = 0.42f,
        rotationDegrees = 96f,
        scale = 1f,
    )

    @Test
    fun `mirroring reflects the hand about the centre line`() {
        val flipped = WeaponFit(mirrored = true).applyTo(anchor)
        assertEquals(0.14f, flipped.xFraction, 0.0001f)
        assertEquals(anchor.yFraction, flipped.yFraction)
    }

    @Test
    fun `the blade turns with the hand`() {
        // Putting the sword in the correct fist pointing the wrong way reads
        // worse than the original error did.
        assertEquals(-96f, WeaponFit(mirrored = true).applyTo(anchor).rotationDegrees, 0.0001f)
    }

    @Test
    fun `an offset still applies after the flip, not before it`() {
        val fitted = WeaponFit(offsetX = 0.05f, mirrored = true).applyTo(anchor)
        assertEquals(0.19f, fitted.xFraction, 0.0001f)
    }

    @Test
    fun `mirroring is not the identity, so it survives being saved`() {
        assertTrue(!WeaponFit(mirrored = true).isIdentity)
        assertTrue(WeaponFit().isIdentity)
    }

    @Test
    fun `a whole sheet mirrors together`() {
        val sheet = SpriteSheet(
            id = "t", name = "T", columns = 4, rows = 1, frameWidth = 96, frameHeight = 96,
            clips = listOf(AnimationClip(AnimationState.ATTACK, 0, 4, loops = false)),
        )
        val plain = WeaponPosing.rigFor(sheet)
        val flipped = WeaponPosing.rigFor(sheet, WeaponFit(mirrored = true))
        for (frame in 0 until 4) {
            val before = assertNotNull(plain.anchorFor(frame))
            val after = assertNotNull(flipped.anchorFor(frame))
            assertEquals(1f - before.xFraction, after.xFraction, 0.0001f)
        }
    }
}
