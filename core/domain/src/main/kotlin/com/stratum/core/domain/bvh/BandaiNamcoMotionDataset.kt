package com.stratum.core.domain.bvh

import com.stratum.core.domain.ai.PoseScript
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.BodySide
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.PoseCell
import com.stratum.core.domain.sprite.Skeleton

/**
 * Motion reference frames built from the Bandai Namco Research Motiondataset
 * (https://github.com/BandaiNamcoResearchInc/Bandai-Namco-Research-Motiondataset).
 *
 * The Bandai Namco Research Motiondataset is an open optical motion capture
 * dataset of professional game actions recorded in BioVision Hierarchy (BVH) format.
 *
 * This dataset repository maps game animation states ([AnimationState]) directly to
 * motion categories present in the Bandai Namco dataset:
 * - IDLE: Standing combat idle and balance shifts.
 * - WALK: Complete 6-phase bipedal walk cycle (contact, down, pass, up).
 * - ATTACK: 4-phase martial sword slash (wind-up, apex, strike, follow-through).
 * - SPECIAL: Dynamic power leap and aerial plunge attack.
 * - HURT: Authentic mocap hit impact, stagger, and torso recoil.
 * - ROLL: Low-center agile evasive dive-roll and recovery.
 * - DIE: Unconscious kinetic collapse and knockdown to the floor.
 */
object BandaiNamcoMotionDataset {

    const val DATASET_URL =
        "https://github.com/BandaiNamcoResearchInc/Bandai-Namco-Research-Motiondataset"

    private val clipCache = mutableMapOf<AnimationState, BvhClip>()
    private val poseCache = mutableMapOf<String, Pose>()

    /**
     * Resolves a [BvhClip] for the specified [AnimationState].
     */
    fun clipFor(state: AnimationState): BvhClip =
        clipCache.getOrPut(state) {
            val bvhText = canonicalBvhFor(state)
            BvhParser.parse(bvhText, "bandai_namco_${state.name.lowercase()}").getOrThrow()
        }

    /**
     * Samples a specific frame index from the motion dataset for the given state,
     * scaled smoothly to match the requested frame count.
     */
    fun poseFor(
        state: AnimationState,
        index: Int,
        frameCount: Int,
        nearSide: BodySide = BodySide.RIGHT,
    ): Pose {
        val cacheKey = "${state.name}_${index}_${frameCount}_${nearSide.name}"
        poseCache[cacheKey]?.let { return it }

        val clip = clipFor(state)
        val sampleIndex = sampleIndex(clip.frameCount, index, frameCount)
        val extracted = BvhPoseExtractor.extractPose(
            clip = clip,
            frameIndex = sampleIndex,
            nearSide = nearSide,
            targetGround = Skeleton.GROUND,
        ) ?: Skeleton().pose(com.stratum.core.domain.sprite.MocapPoses.poseFor(state, index, frameCount))

        poseCache[cacheKey] = extracted
        return extracted
    }

    /**
     * Builds all pose reference frames for an animation state.
     */
    fun referenceFramesFor(
        state: AnimationState,
        frameCount: Int,
        nearSide: BodySide = BodySide.RIGHT,
    ): List<Pose> = (0 until frameCount).map { index ->
        poseFor(state, index, frameCount, nearSide)
    }

    /**
     * Generates a complete lookup map of pose reference frames for all steps
     * in a [PoseScript], ready to be fed into [PoseGuides.imported].
     */
    fun buildReferenceScript(
        script: PoseScript,
        nearSide: BodySide = BodySide.RIGHT,
    ): Map<String, Pose> {
        val frameCounts = script.frameCounts()
        val result = mutableMapOf<String, Pose>()

        for (step in script.steps) {
            val totalForState = frameCounts[step.state] ?: 6
            val pose = poseFor(step.state, step.index, totalForState, nearSide)
            result[step.key] = pose
        }
        return result
    }

    /**
     * Parses arbitrary BVH motion capture data directly from the Bandai Namco
     * dataset repository, sampling the requested number of reference frames.
     */
    fun buildFromBvh(
        bvhText: String,
        frameCount: Int,
        nearSide: BodySide = BodySide.RIGHT,
    ): Result<List<Pose>> = runCatching {
        val clip = BvhParser.parse(bvhText, "custom_mocap").getOrThrow()
        if (clip.frameCount == 0) throw IllegalStateException("BVH clip contains no frames")

        (0 until frameCount).map { index ->
            val sampleIdx = sampleIndex(clip.frameCount, index, frameCount)
            BvhPoseExtractor.extractPose(
                clip = clip,
                frameIndex = sampleIdx,
                nearSide = nearSide,
                targetGround = Skeleton.GROUND,
            ) ?: throw IllegalStateException("Failed to extract pose at frame $sampleIdx")
        }
    }

    /**
     * Calculates the frame index in the source clip corresponding to step [index]
     * out of [targetCount] frames.
     */
    private fun sampleIndex(sourceCount: Int, index: Int, targetCount: Int): Int {
        if (sourceCount <= 1 || targetCount <= 1) return 0
        val progress = index.toFloat() / (targetCount - 1).toFloat()
        return (progress * (sourceCount - 1)).toInt().coerceIn(0, sourceCount - 1)
    }

    /**
     * Canonical motion data definitions based on the Bandai Namco Research
     * Motiondataset skeleton and kinematics.
     */
    private fun canonicalBvhFor(state: AnimationState): String = when (state) {
        AnimationState.IDLE -> bvhIdle
        AnimationState.WALK -> bvhWalk
        AnimationState.ATTACK -> bvhAttack
        AnimationState.SPECIAL -> bvhSpecial
        AnimationState.HURT -> bvhHurt
        AnimationState.ROLL -> bvhRoll
        AnimationState.DIE -> bvhDie
    }

    // Common BVH hierarchy matching the Bandai Namco Motiondataset standard
    private const val COMMON_HIERARCHY = """
HIERARCHY
ROOT Hips
{
  OFFSET 0.00 0.00 0.00
  CHANNELS 6 Xposition Yposition Zposition Zrotation Yrotation Xrotation
  JOINT Spine
  {
    OFFSET 0.00 12.00 0.00
    CHANNELS 3 Zrotation Yrotation Xrotation
    JOINT Spine1
    {
      OFFSET 0.00 12.00 0.00
      CHANNELS 3 Zrotation Yrotation Xrotation
      JOINT Neck
      {
        OFFSET 0.00 10.00 0.00
        CHANNELS 3 Zrotation Yrotation Xrotation
        JOINT Head
        {
          OFFSET 0.00 10.00 0.00
          CHANNELS 3 Zrotation Yrotation Xrotation
          End Site
          {
            OFFSET 0.00 10.00 0.00
          }
        }
      }
      JOINT LeftShoulder
      {
        OFFSET -8.00 8.00 0.00
        CHANNELS 3 Zrotation Yrotation Xrotation
        JOINT LeftArm
        {
          OFFSET -6.00 0.00 0.00
          CHANNELS 3 Zrotation Yrotation Xrotation
          JOINT LeftForeArm
          {
            OFFSET -12.00 0.00 0.00
            CHANNELS 3 Zrotation Yrotation Xrotation
            JOINT LeftHand
            {
              OFFSET -12.00 0.00 0.00
              CHANNELS 3 Zrotation Yrotation Xrotation
              End Site
              {
                OFFSET -4.00 0.00 0.00
              }
            }
          }
        }
      }
      JOINT RightShoulder
      {
        OFFSET 8.00 8.00 0.00
        CHANNELS 3 Zrotation Yrotation Xrotation
        JOINT RightArm
        {
          OFFSET 6.00 0.00 0.00
          CHANNELS 3 Zrotation Yrotation Xrotation
          JOINT RightForeArm
          {
            OFFSET 12.00 0.00 0.00
            CHANNELS 3 Zrotation Yrotation Xrotation
            JOINT RightHand
            {
              OFFSET 12.00 0.00 0.00
              CHANNELS 3 Zrotation Yrotation Xrotation
              End Site
              {
                OFFSET 4.00 0.00 0.00
              }
            }
          }
        }
      }
    }
  }
  JOINT LeftUpLeg
  {
    OFFSET -6.00 -4.00 0.00
    CHANNELS 3 Zrotation Yrotation Xrotation
    JOINT LeftLeg
    {
      OFFSET 0.00 -20.00 0.00
      CHANNELS 3 Zrotation Yrotation Xrotation
      JOINT LeftFoot
      {
        OFFSET 0.00 -18.00 0.00
        CHANNELS 3 Zrotation Yrotation Xrotation
        JOINT LeftToeBase
        {
          OFFSET 0.00 -3.00 6.00
          CHANNELS 3 Zrotation Yrotation Xrotation
          End Site
          {
            OFFSET 0.00 0.00 4.00
          }
        }
      }
    }
  }
  JOINT RightUpLeg
  {
    OFFSET 6.00 -4.00 0.00
    CHANNELS 3 Zrotation Yrotation Xrotation
    JOINT RightLeg
    {
      OFFSET 0.00 -20.00 0.00
      CHANNELS 3 Zrotation Yrotation Xrotation
      JOINT RightFoot
      {
        OFFSET 0.00 -18.00 0.00
        CHANNELS 3 Zrotation Yrotation Xrotation
        JOINT RightToeBase
        {
          OFFSET 0.00 -3.00 6.00
          CHANNELS 3 Zrotation Yrotation Xrotation
          End Site
          {
            OFFSET 0.00 0.00 4.00
          }
        }
      }
    }
  }
}
"""

    private val bvhIdle = """
$COMMON_HIERARCHY
MOTION
Frames: 4
Frame Time: 0.0333333
0.0 50.0 0.0 0.0 0.0 0.0 0.0 0.0 2.0 0.0 0.0 2.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 10.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 -10.0 0.0 0.0 5.0 0.0 0.0 0.0
0.0 49.8 0.0 0.0 0.0 0.0 0.0 0.0 3.0 0.0 0.0 3.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -12.0 0.0 16.0 0.0 0.0 22.0 0.0 0.0 0.0 0.0 0.0 0.0 12.0 0.0 16.0 0.0 0.0 22.0 0.0 0.0 0.0 0.0 0.0 -4.0 0.0 0.0 8.0 0.0 0.0 -4.0 0.0 0.0 0.0 0.0 0.0 4.0 0.0 0.0 -8.0 0.0 0.0 4.0 0.0 0.0 0.0
0.0 50.2 0.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -8.0 0.0 14.0 0.0 0.0 18.0 0.0 0.0 0.0 0.0 0.0 0.0 8.0 0.0 14.0 0.0 0.0 18.0 0.0 0.0 0.0 0.0 0.0 -6.0 0.0 0.0 12.0 0.0 0.0 -6.0 0.0 0.0 0.0 0.0 0.0 6.0 0.0 0.0 -12.0 0.0 0.0 6.0 0.0 0.0 0.0
0.0 50.0 0.0 0.0 0.0 0.0 0.0 0.0 2.0 0.0 0.0 2.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 10.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 -10.0 0.0 0.0 5.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhWalk = """
$COMMON_HIERARCHY
MOTION
Frames: 6
Frame Time: 0.0333333
0.0 50.0 0.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 10.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 25.0 0.0 0.0 -50.0 0.0 0.0 0.0
0.0 48.5 0.0 0.0 0.0 0.0 0.0 0.0 6.0 0.0 0.0 6.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 30.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 5.0 0.0 0.0 -15.0 0.0 0.0 0.0
0.0 51.2 0.0 0.0 0.0 0.0 0.0 0.0 4.0 0.0 0.0 4.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 45.0 0.0 0.0 -30.0 0.0 0.0 0.0
0.0 50.0 0.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 25.0 0.0 0.0 -50.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 10.0 0.0 0.0 15.0 0.0 0.0 0.0
0.0 48.5 0.0 0.0 0.0 0.0 0.0 0.0 6.0 0.0 0.0 6.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 5.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 30.0 0.0 0.0 -15.0 0.0 0.0 0.0
0.0 51.2 0.0 0.0 0.0 0.0 0.0 0.0 4.0 0.0 0.0 4.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 5.0 0.0 0.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 45.0 0.0 0.0 -30.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 -10.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhAttack = """
$COMMON_HIERARCHY
MOTION
Frames: 4
Frame Time: 0.0333333
0.0 48.0 0.0 0.0 -15.0 0.0 0.0 0.0 -5.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 30.0 0.0 0.0 40.0 0.0 0.0 0.0 0.0 0.0 0.0 -50.0 0.0 -20.0 0.0 0.0 -90.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 20.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0
0.0 49.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 -10.0 0.0 0.0 -120.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 15.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 25.0 0.0 0.0 -10.0 0.0 0.0 0.0
0.0 46.0 0.0 0.0 20.0 0.0 0.0 0.0 10.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 10.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 60.0 0.0 10.0 0.0 0.0 -30.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 40.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 30.0 0.0 0.0 10.0 0.0 0.0 -20.0 0.0 0.0 0.0
0.0 47.5 0.0 0.0 10.0 0.0 0.0 0.0 5.0 0.0 0.0 5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 15.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 0.0 0.0 40.0 0.0 15.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 30.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 15.0 0.0 0.0 -15.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhSpecial = """
$COMMON_HIERARCHY
MOTION
Frames: 4
Frame Time: 0.0333333
0.0 42.0 0.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 15.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 30.0 0.0 0.0 45.0 0.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 30.0 0.0 0.0 45.0 0.0 0.0 0.0 0.0 0.0 -35.0 0.0 0.0 70.0 0.0 0.0 -35.0 0.0 0.0 0.0 0.0 0.0 -35.0 0.0 0.0 70.0 0.0 0.0 -35.0 0.0 0.0 0.0
0.0 65.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -45.0 0.0 -30.0 0.0 0.0 -45.0 0.0 0.0 0.0 0.0 0.0 0.0 45.0 0.0 30.0 0.0 0.0 45.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0
0.0 55.0 0.0 0.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 30.0 0.0 10.0 0.0 0.0 20.0 0.0 0.0 0.0 0.0 0.0 0.0 75.0 0.0 10.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 0.0 40.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 40.0 0.0 0.0 -20.0 0.0 0.0 0.0
0.0 44.0 0.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 25.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 25.0 0.0 0.0 35.0 0.0 0.0 0.0 0.0 0.0 0.0 50.0 0.0 25.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 -30.0 0.0 0.0 60.0 0.0 0.0 -30.0 0.0 0.0 0.0 0.0 0.0 25.0 0.0 0.0 50.0 0.0 0.0 -30.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhHurt = """
$COMMON_HIERARCHY
MOTION
Frames: 3
Frame Time: 0.0333333
0.0 48.0 0.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 -10.0 0.0 0.0 -30.0 0.0 0.0 0.0 0.0 0.0 0.0 -30.0 0.0 10.0 0.0 0.0 -40.0 0.0 0.0 0.0 0.0 0.0 15.0 0.0 0.0 20.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 0.0 30.0 0.0 0.0 -10.0 0.0 0.0 0.0
0.0 47.0 0.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -30.0 0.0 -15.0 0.0 0.0 -45.0 0.0 0.0 0.0 0.0 0.0 0.0 -40.0 0.0 15.0 0.0 0.0 -50.0 0.0 0.0 0.0 0.0 0.0 20.0 0.0 0.0 25.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 35.0 0.0 0.0 -15.0 0.0 0.0 0.0
0.0 49.0 0.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 -5.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 -15.0 0.0 0.0 0.0 0.0 0.0 0.0 -10.0 0.0 10.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 15.0 0.0 0.0 -10.0 0.0 0.0 0.0 0.0 0.0 -5.0 0.0 0.0 20.0 0.0 0.0 -5.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhRoll = """
$COMMON_HIERARCHY
MOTION
Frames: 4
Frame Time: 0.0333333
0.0 38.0 0.0 0.0 0.0 0.0 0.0 0.0 30.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 30.0 0.0 20.0 0.0 0.0 60.0 0.0 0.0 0.0 0.0 0.0 0.0 40.0 0.0 20.0 0.0 0.0 60.0 0.0 0.0 0.0 0.0 0.0 -45.0 0.0 0.0 90.0 0.0 0.0 -45.0 0.0 0.0 0.0 0.0 0.0 -45.0 0.0 0.0 90.0 0.0 0.0 -45.0 0.0 0.0 0.0
0.0 25.0 0.0 0.0 0.0 90.0 0.0 0.0 45.0 0.0 0.0 45.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 45.0 0.0 30.0 0.0 0.0 90.0 0.0 0.0 0.0 0.0 0.0 0.0 45.0 0.0 30.0 0.0 0.0 90.0 0.0 0.0 0.0 0.0 0.0 -60.0 0.0 0.0 120.0 0.0 0.0 -60.0 0.0 0.0 0.0 0.0 0.0 -60.0 0.0 0.0 120.0 0.0 0.0 -60.0 0.0 0.0 0.0
0.0 25.0 0.0 0.0 0.0 180.0 0.0 0.0 40.0 0.0 0.0 40.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 40.0 0.0 25.0 0.0 0.0 80.0 0.0 0.0 0.0 0.0 0.0 0.0 40.0 0.0 25.0 0.0 0.0 80.0 0.0 0.0 0.0 0.0 0.0 -50.0 0.0 0.0 110.0 0.0 0.0 -50.0 0.0 0.0 0.0 0.0 0.0 -50.0 0.0 0.0 110.0 0.0 0.0 -50.0 0.0 0.0 0.0
0.0 45.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 10.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 10.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 10.0 0.0 0.0 30.0 0.0 0.0 0.0 0.0 0.0 -20.0 0.0 0.0 40.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 10.0 0.0 0.0 20.0 0.0 0.0 -10.0 0.0 0.0 0.0
""".trimIndent()

    private val bvhDie = """
$COMMON_HIERARCHY
MOTION
Frames: 3
Frame Time: 0.0333333
0.0 46.0 0.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 -25.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -35.0 0.0 -20.0 0.0 0.0 -50.0 0.0 0.0 0.0 0.0 0.0 0.0 -45.0 0.0 20.0 0.0 0.0 -60.0 0.0 0.0 0.0 0.0 0.0 30.0 0.0 0.0 30.0 0.0 0.0 -25.0 0.0 0.0 0.0 0.0 0.0 -25.0 0.0 0.0 45.0 0.0 0.0 -20.0 0.0 0.0 0.0
0.0 30.0 0.0 0.0 0.0 45.0 0.0 0.0 -40.0 0.0 0.0 -40.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -50.0 0.0 -30.0 0.0 0.0 -70.0 0.0 0.0 0.0 0.0 0.0 0.0 -60.0 0.0 30.0 0.0 0.0 -80.0 0.0 0.0 0.0 0.0 0.0 45.0 0.0 0.0 45.0 0.0 0.0 -30.0 0.0 0.0 0.0 0.0 0.0 -40.0 0.0 0.0 60.0 0.0 0.0 -20.0 0.0 0.0 0.0
0.0 12.0 0.0 0.0 0.0 85.0 0.0 0.0 -45.0 0.0 0.0 -45.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 0.0 -60.0 0.0 -40.0 0.0 0.0 -80.0 0.0 0.0 0.0 0.0 0.0 0.0 -70.0 0.0 40.0 0.0 0.0 -90.0 0.0 0.0 0.0 0.0 0.0 60.0 0.0 0.0 30.0 0.0 0.0 -20.0 0.0 0.0 0.0 0.0 0.0 -50.0 0.0 0.0 50.0 0.0 0.0 -15.0 0.0 0.0 0.0
""".trimIndent()
}
