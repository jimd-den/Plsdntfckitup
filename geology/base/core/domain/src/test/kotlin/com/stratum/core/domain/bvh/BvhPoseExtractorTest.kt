package com.stratum.core.domain.bvh

import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.Joint
import com.stratum.core.domain.sprite.Skeleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BvhPoseExtractorTest {

    @Test
    fun `extracts all required domain joints from canonical motion`() {
        val clip = BandaiNamcoMotionDataset.clipFor(AnimationState.WALK)
        val pose = assertNotNull(BvhPoseExtractor.extractPose(clip, frameIndex = 0))

        val requiredJoints = listOf(
            Joint.PELVIS, Joint.CHEST, Joint.NECK, Joint.HEAD,
            Joint.SHOULDER_NEAR, Joint.ELBOW_NEAR, Joint.HAND_NEAR,
            Joint.SHOULDER_FAR, Joint.ELBOW_FAR, Joint.HAND_FAR,
            Joint.HIP_NEAR, Joint.KNEE_NEAR, Joint.FOOT_NEAR,
            Joint.HIP_FAR, Joint.KNEE_FAR, Joint.FOOT_FAR,
            Joint.HEEL_NEAR, Joint.TOE_NEAR, Joint.HEEL_FAR, Joint.TOE_FAR,
        )

        for (joint in requiredJoints) {
            val point = pose.joints[joint]
            assertNotNull(point, "Joint $joint should be extracted")
            assertTrue(point.x in 0f..1f, "Joint $joint X coordinate ${point.x} should be within canvas")
            assertTrue(point.y in 0f..1f, "Joint $joint Y coordinate ${point.y} should be within canvas")
        }
    }

    @Test
    fun `poses stand precisely on the canvas ground line`() {
        val clip = BandaiNamcoMotionDataset.clipFor(AnimationState.IDLE)
        val pose = assertNotNull(BvhPoseExtractor.extractPose(clip, frameIndex = 0))

        val groundY = pose.joints.values.maxOf { it.y }
        assertEquals(Skeleton.GROUND, groundY, 0.015f, "Figure must stand on the ground line")
    }

    @Test
    fun `weapon hand provides a valid weapon grip`() {
        val clip = BandaiNamcoMotionDataset.clipFor(AnimationState.ATTACK)
        val pose = assertNotNull(BvhPoseExtractor.extractPose(clip, frameIndex = 2))

        val grip = pose.weaponGrip()
        assertNotNull(grip.at)
        assertTrue(grip.weaponDegrees in -180f..180f, "Weapon angle must be normalized")
    }
}
