package com.stratum.core.domain.bvh

import com.stratum.core.domain.ai.BuildPoseReferenceFramesUseCase
import com.stratum.core.domain.ai.PoseScript
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.Joint
import com.stratum.core.domain.sprite.PoseGuideMode
import com.stratum.core.domain.sprite.PoseGuides
import com.stratum.core.domain.sprite.Skeleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BandaiNamcoMotionDatasetTest {

    @Test
    fun `every animation state produces motion capture reference frames`() {
        for (state in AnimationState.entries) {
            val poses = BandaiNamcoMotionDataset.referenceFramesFor(state, frameCount = 6)
            assertEquals(6, poses.size, "Should generate 6 frames for $state")

            for ((idx, pose) in poses.withIndex()) {
                assertNotNull(pose.joints[Joint.HAND_NEAR], "Hand near present in $state frame $idx")
                assertNotNull(pose.joints[Joint.HEAD], "Head present in $state frame $idx")
                assertNotNull(pose.joints[Joint.PELVIS], "Pelvis present in $state frame $idx")

                val ground = pose.joints.values.maxOf { it.y }
                assertEquals(Skeleton.GROUND, ground, 0.02f, "Ground level matches for $state frame $idx")
            }
        }
    }

    @Test
    fun `use case populates reference frames across an entire pose script`() {
        val script = PoseScript.full()
        val useCase = BuildPoseReferenceFramesUseCase()
        val referenceMap = useCase.execute(script)

        assertEquals(script.steps.size, referenceMap.size)
        for (step in script.steps) {
            val pose = referenceMap[step.key]
            assertNotNull(pose, "Pose must exist for step ${step.key}")
            assertTrue(pose.joints.isNotEmpty())
        }
    }

    @Test
    fun `PoseGuides with BANDAI_NAMCO mode supplies dataset poses and rigs weapon`() {
        val guides = PoseGuides(mode = PoseGuideMode.BANDAI_NAMCO)
        val attackPose = guides.poseFor(AnimationState.ATTACK, 2, 4)
        assertNotNull(attackPose)

        val rigPose = guides.riggingPoseFor(AnimationState.ATTACK, 2, 4)
        assertNotNull(rigPose)
        assertEquals(attackPose, rigPose)

        val grip = rigPose.weaponGrip()
        assertNotNull(grip.at)
    }

    @Test
    fun `parses custom BVH from Bandai Namco dataset`() {
        val customBvh = """
HIERARCHY
ROOT Hips
{
  OFFSET 0.00 0.00 0.00
  CHANNELS 6 Xposition Yposition Zposition Zrotation Yrotation Xrotation
  JOINT Spine
  {
    OFFSET 0.00 15.00 0.00
    CHANNELS 3 Zrotation Yrotation Xrotation
    End Site
    {
      OFFSET 0.00 15.00 0.00
    }
  }
}
MOTION
Frames: 3
Frame Time: 0.0333333
0.0 50.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0
0.0 51.0 0.0 0.0 0.0 0.0 0.0 0.0 5.0
0.0 52.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0
""".trimIndent()

        val result = BandaiNamcoMotionDataset.buildFromBvh(customBvh, frameCount = 3)
        assertTrue(result.isSuccess)
        val frames = result.getOrThrow()
        assertEquals(3, frames.size)
    }
}
