package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkeletonTest {

    private val skeleton = Skeleton()

    @Test
    fun `an arm at zero hangs straight down and at ninety reaches towards the viewer`() {
        // The whole authoring convention in one test, because every pose in the
        // library is written against it and a silent flip would mirror the lot.
        //
        // The angles are swings in the plane a limb actually swings in --
        // forward and back -- and the camera decides what that looks like.
        // Ninety degrees is an arm held out in front of the character, and the
        // character faces the bottom-right of the frame, so the hand goes down
        // and to the right. It used to go flat across the picture, which is
        // what a front-on figure does and what this guide must never be.
        val down = skeleton.pose(PoseAngles(shoulderNear = 0f, elbowNear = 0f))
        val shoulder = down.require(Joint.SHOULDER_NEAR)
        val hand = down.require(Joint.HAND_NEAR)
        assertEquals(shoulder.x, hand.x, 0.001f, "an arm at rest did not hang plumb")
        assertTrue(hand.y > shoulder.y, "an arm at zero did not hang downward")

        val out = skeleton.pose(PoseAngles(shoulderNear = 90f, elbowNear = 0f))
        val reached = out.require(Joint.HAND_NEAR)
        val from = out.require(Joint.SHOULDER_NEAR)
        assertTrue(reached.x > from.x, "reaching forward did not come round to the right")
        assertTrue(reached.y > from.y, "reaching forward did not come towards the viewer")
    }

    @Test
    fun `an arm overhead puts the hand above the shoulder`() {
        val pose = skeleton.pose(PoseAngles(shoulderNear = 180f, elbowNear = 0f))
        assertTrue(pose.require(Joint.HAND_NEAR).y < pose.require(Joint.SHOULDER_NEAR).y)
    }

    @Test
    fun `no pose can stretch a limb`() {
        // The reason poses are authored as angles rather than as coordinates: a
        // guide that teaches the model the wrong proportions is worse than none.
        val extreme = PoseAngles(
            shoulderNear = 196f, elbowNear = -52f, hipFar = -92f, kneeFar = 124f,
            lean = 148f, driftY = 0.3f,
        )
        val pose = skeleton.pose(extreme)

        // A ceiling, not a range. The guide is a picture of a body, and a
        // picture foreshortens: a bone across the frame is seen at its full
        // length, the same bone pointing at the camera is seen as almost
        // nothing, and everything between is legitimate. So there is no useful
        // floor -- a forearm aimed down the lens really is a dot -- but there
        // is a ceiling, and a bone that exceeds it is a bone a pose stretched,
        // which is the failure this guards.
        fun assertBone(bone: Float, seen: Float, name: String) {
            val longest = bone / IsoProjection.heightScale
            assertTrue(seen > 0f, "$name vanished entirely")
            assertTrue(
                seen <= longest + 0.0005f,
                "$name is $seen on screen, longer than a $bone bone can ever look",
            )
        }
        assertBone(
            skeleton.upperArm,
            pose.require(Joint.SHOULDER_NEAR).distanceTo(pose.require(Joint.ELBOW_NEAR)),
            "the upper arm",
        )
        assertBone(
            skeleton.foreArm,
            pose.require(Joint.ELBOW_NEAR).distanceTo(pose.require(Joint.HAND_NEAR)),
            "the forearm",
        )
        assertBone(
            skeleton.shin,
            pose.require(Joint.KNEE_FAR).distanceTo(pose.require(Joint.FOOT_FAR)),
            "the shin",
        )
    }

    @Test
    fun `an elbow bends relative to the arm above it`() {
        // Bending the elbow must not move the shoulder or the elbow itself.
        val straight = skeleton.pose(PoseAngles(shoulderNear = 90f, elbowNear = 0f))
        val bent = skeleton.pose(PoseAngles(shoulderNear = 90f, elbowNear = 90f))

        assertEquals(straight.require(Joint.ELBOW_NEAR), bent.require(Joint.ELBOW_NEAR))
        // Bending +90 from an arm held out to the side folds the forearm
        // upward, so the hand rises above the elbow it hangs from.
        assertTrue(bent.require(Joint.HAND_NEAR).y < bent.require(Joint.ELBOW_NEAR).y)
        assertTrue(bent.require(Joint.HAND_NEAR) != straight.require(Joint.HAND_NEAR))
    }

    @Test
    fun `the two shoulders sit at different depths, so the guide is never square on`() {
        // A flat front view is the one angle the art must not come back as,
        // and a stick figure whose shoulders sit on one row is exactly that.
        //
        // It used to be faked: the far shoulder was nudged sideways by a
        // constant. Now the body has a real across-the-body axis and the
        // camera does the work, which means the two shoulders differ in
        // *height* as well -- the near one is closer to the camera, so it
        // hangs lower in the frame. That difference is the three-quarter view,
        // and it is the thing a constant could not produce.
        val pose = skeleton.pose(PoseAngles())
        val near = pose.require(Joint.SHOULDER_NEAR)
        val far = pose.require(Joint.SHOULDER_FAR)

        assertTrue(near.y > far.y, "the shoulders sat on one row, which is a front view")
        assertTrue(
            near.x - far.x < 2 * skeleton.shoulderHalfWidth,
            "the shoulders are as far apart as they would be square on",
        )
        // Facing the bottom-right puts the nearer side of the body to the
        // left, which is simply where that camera puts it.
        assertTrue(near.x < far.x, "the near shoulder was not on the side nearest the camera")
    }

    @Test
    fun `a standing figure fits in its frame with its feet near the bottom`() {
        val pose = skeleton.pose(PoseAngles())
        assertTrue(pose.groundY in 0.9f..1f, "feet at ${pose.groundY}")
        assertTrue(pose.require(Joint.HEAD).y > 0.05f, "the head runs off the top")
        pose.joints.values.forEach {
            assertTrue(it.x in 0f..1f && it.y in 0f..1f, "a joint left the frame: $it")
        }
    }
}

class WeaponGripTest {

    private val skeleton = Skeleton()

    @Test
    fun `a weapon in a hand continues the line of the forearm as it is drawn`() {
        // Arm held out in front of the character. A quarter turn was the right
        // answer while the guide was flat, and is the wrong one now: the
        // weapon is a picture laid over a picture, so it has to follow the
        // forearm's direction *on screen*, not the direction the arm points in
        // the world. An arm reaching towards the viewer is foreshortened, and
        // a weapon rotated as if it were not would leave the hand.
        val pose = skeleton.pose(PoseAngles(shoulderNear = 90f, elbowNear = 0f))
        val hand = pose.require(Joint.HAND_NEAR)
        val elbow = pose.require(Joint.ELBOW_NEAR)
        val degrees = pose.weaponGrip().weaponDegrees

        // Past a quarter turn, because the forearm is running down the frame
        // as well as across it.
        assertTrue(degrees > 90f, "the weapon did not follow the arm towards the viewer")
        assertTrue(degrees < 180f, "the weapon turned past the arm entirely")
        // And it really is the drawn direction: the hand is below and right of
        // the elbow, which is what more than a quarter turn means.
        assertTrue(hand.x > elbow.x && hand.y > elbow.y)
    }

    @Test
    fun `a T-pose is expressible, which it was not`() {
        // The reference every character starts from is a T-pose, and until the
        // skeleton had a second axis it could not make one: every joint had a
        // single angle, a swing forward or back, so an arm could reach in
        // front or behind and never out to the side. Poses that wanted width
        // faked it by swinging an arm past vertical, which is a figure
        // clutching at itself rather than one standing with its arms out.
        val tPose = skeleton.pose(
            PoseAngles(
                shoulderNear = 0f, shoulderNearOut = 90f,
                shoulderFar = 0f, shoulderFarOut = 90f,
            ),
        )
        val chest = tPose.require(Joint.CHEST)
        val nearHand = tPose.require(Joint.HAND_NEAR)
        val farHand = tPose.require(Joint.HAND_FAR)

        // Both arms out, on opposite sides of the body.
        assertTrue(nearHand.x < chest.x, "the near arm did not reach out to its own side")
        assertTrue(farHand.x > chest.x, "the far arm did not reach out to its own side")

        // And not level, which is the point of drawing it at this camera: an
        // arm held straight out to the near side is coming towards the viewer,
        // so it drops down the frame, while the far arm recedes and rides up.
        // A T-pose with both hands on one row is a T-pose seen square on,
        // which is the angle none of this art is drawn at.
        assertTrue(nearHand.y > farHand.y, "the arms came out level, which is a front view")

        // Neither arm is merely hanging, though -- they are out, not down.
        val shoulder = tPose.require(Joint.SHOULDER_NEAR)
        val armLength = (skeleton.upperArm + skeleton.foreArm)
        assertTrue(
            nearHand.y - shoulder.y < armLength * 0.5f,
            "the arm hung rather than reached",
        )

        // And the span is most of the arm: an arm out to the side is not
        // foreshortened much by this camera, which is exactly why a reference
        // pose uses one.
        val span = farHand.x - nearHand.x
        assertTrue(span > 2f * (skeleton.upperArm + skeleton.foreArm) * 0.6f, "span was $span")
    }

    @Test
    fun `lifting a limb sideways cannot change its length`() {
        // The new axis has the same guarantee as the old one: it turns a bone,
        // it does not stretch one.
        val out = skeleton.pose(PoseAngles(shoulderNear = 20f, shoulderNearOut = 55f))
        val upper = out.require(Joint.SHOULDER_NEAR).distanceTo(out.require(Joint.ELBOW_NEAR))
        assertTrue(
            upper <= skeleton.upperArm / IsoProjection.heightScale + 0.0005f,
            "lifting the arm out stretched it to $upper",
        )
    }

    @Test
    fun `an arm hanging down points the weapon down`() {
        val pose = skeleton.pose(PoseAngles(shoulderNear = 0f, elbowNear = 0f))
        assertEquals(180f, pose.weaponGrip().weaponDegrees, 0.5f)
    }

    @Test
    fun `an arm raised overhead points the weapon up`() {
        val pose = skeleton.pose(PoseAngles(shoulderNear = 180f, elbowNear = 0f))
        assertEquals(0f, pose.weaponGrip().weaponDegrees, 0.5f)
    }

    @Test
    fun `the grip is the hand joint itself, not a number near it`() {
        val pose = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 0, 6))
        assertEquals(pose.require(Joint.HAND_NEAR), pose.weaponGrip().at)
    }
}

class MocapPosesTest {

    @Test
    fun `every animation has a pose for every frame the script asks for`() {
        AnimationState.entries.forEach { state ->
            assertTrue(MocapPoses.framesFor(state).isNotEmpty(), "$state has no poses")
        }
    }

    @Test
    fun `a clip that came back longer than authored is still posed throughout`() {
        // Models do not always return the frame count they were asked for, and
        // an unposed frame is a frame with no weapon in it.
        val poses = (0 until 7).map { MocapPoses.poseFor(AnimationState.ATTACK, it, 7) }
        assertEquals(7, poses.size)
        assertTrue(poses.first() != poses.last(), "a stretched clip collapsed to one pose")
    }

    @Test
    fun `a walk lifts the body between contacts`() {
        val frames = MocapPoses.framesFor(AnimationState.WALK)
        // Contact, pass, reach -- twice. The contacts are the low points and
        // the passes and reaches the high ones; without that difference a walk
        // reads as gliding.
        assertTrue(frames[0].driftY > frames[1].driftY, "the first contact did not drop")
        assertTrue(frames[3].driftY > frames[4].driftY, "the second contact did not drop")
        // And the reach is the highest point of each half, which is what puts
        // the bounce at the top of the stride rather than halfway up it.
        assertTrue(frames[2].driftY < frames[1].driftY, "the first reach did not rise")
        assertTrue(frames[5].driftY < frames[4].driftY, "the second reach did not rise")
    }

    @Test
    fun `an attack carries the weapon hand from high and back to low and across`() {
        val skeleton = Skeleton()
        val windUp = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 0, 6))
        val impact = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 2, 6))

        val up = windUp.require(Joint.HAND_NEAR)
        val down = impact.require(Joint.HAND_NEAR)
        assertTrue(up.y < down.y, "the swing never came down")
        assertTrue(up.y < windUp.require(Joint.SHOULDER_NEAR).y, "the wind-up was not raised")
    }
}

class SkeletonDrivenWeaponTest {

    @Test
    fun `the anchor is the hand, so it can never drift off the body`() {
        // The failure this replaces: authored anchors put the hand a full
        // hand's width outside the character, and had to be corrected by eye.
        val skeleton = Skeleton()
        for (state in AnimationState.entries) {
            val frames = MocapPoses.framesFor(state).size
            for (index in 0 until frames) {
                val anchor = WeaponPosing.anchorFor(state, index, frames) ?: continue
                val pose = skeleton.pose(MocapPoses.poseFor(state, index, frames))
                val hand = pose.require(Joint.HAND_NEAR)
                assertEquals(hand.x, anchor.xFraction, 0.0001f, "$state $index")
                assertEquals(hand.y, anchor.yFraction, 0.0001f, "$state $index")
            }
        }
    }

    @Test
    fun `a raised hand puts the weapon behind the body and a low one in front`() {
        val windUp = assertNotNull(WeaponPosing.anchorFor(AnimationState.ATTACK, 0, 4))
        val impact = assertNotNull(WeaponPosing.anchorFor(AnimationState.ATTACK, 2, 4))
        assertEquals(WeaponLayer.BEHIND, windUp.layer)
        assertEquals(WeaponLayer.IN_FRONT, impact.layer)
    }

    @Test
    fun `a corpse still lets go of its weapon`() {
        assertNotNull(WeaponPosing.anchorFor(AnimationState.DIE, 0, 4))
        assertNull(WeaponPosing.anchorFor(AnimationState.DIE, 3, 4))
    }

    @Test
    fun `every anchor lands somewhere on the frame`() {
        for (state in AnimationState.entries) {
            val frames = MocapPoses.framesFor(state).size
            for (index in 0 until frames) {
                val anchor = WeaponPosing.anchorFor(state, index, frames) ?: continue
                assertTrue(
                    anchor.xFraction in 0f..1f && anchor.yFraction in 0f..1f,
                    "$state $index put the weapon off the frame at $anchor",
                )
            }
        }
    }
}

/**
 * The prose and the skeleton describe the same animation, and both are sent.
 *
 * If their frame counts ever disagreed, the guide handed to the model would be
 * sampled at the wrong index — frame three of an attack drawn against the pose
 * for frame two — and nothing would fail loudly. The art would just be subtly
 * wrong, and the weapon would be rigged to a pose the character is not in.
 */
class PoseScriptAgreementTest {

    @Test
    fun `every animation has as many skeletons as it has written poses`() {
        AnimationState.entries.forEach { state ->
            assertEquals(
                com.stratum.core.domain.ai.PoseScript.posesFor(state).size,
                MocapPoses.framesFor(state).size,
                "$state: the written poses and the skeletons disagree",
            )
        }
    }

    @Test
    fun `a step's index picks the skeleton meant for it`() {
        val script = com.stratum.core.domain.ai.PoseScript.full()
        script.steps.forEach { step ->
            val count = com.stratum.core.domain.ai.PoseScript.posesFor(step.state).size
            assertEquals(
                MocapPoses.framesFor(step.state)[step.index],
                MocapPoses.poseFor(step.state, step.index, count),
                "${step.key} was posed from the wrong frame",
            )
        }
    }
}
