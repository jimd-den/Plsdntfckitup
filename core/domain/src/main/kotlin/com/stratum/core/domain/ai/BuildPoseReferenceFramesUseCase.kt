package com.stratum.core.domain.ai

import com.stratum.core.domain.bvh.BandaiNamcoMotionDataset
import com.stratum.core.domain.sprite.AnimationState
import com.stratum.core.domain.sprite.BodySide
import com.stratum.core.domain.sprite.Pose
import com.stratum.core.domain.sprite.PoseCell

/**
 * Use case to build 2D skeletal pose reference frames from optical motion capture datasets,
 * specifically the Bandai Namco Research Motiondataset.
 *
 * Adheres to Pure Clean Architecture:
 * - Independent of UI and external platform frameworks.
 * - Coordinates BVH parsing, forward kinematics, isometric camera projection,
 *   and canvas normalization to produce accurate [Pose] domain entities.
 */
class BuildPoseReferenceFramesUseCase(
    private val dataset: BandaiNamcoMotionDataset = BandaiNamcoMotionDataset,
) {

    /**
     * Builds pose reference frames for all steps in an animation [PoseScript].
     */
    fun execute(
        script: PoseScript,
        nearSide: BodySide = BodySide.RIGHT,
    ): Map<String, Pose> = dataset.buildReferenceScript(script, nearSide)

    /**
     * Builds reference frames for a specific animation state.
     */
    fun executeForState(
        state: AnimationState,
        frameCount: Int,
        nearSide: BodySide = BodySide.RIGHT,
    ): List<Pose> = dataset.referenceFramesFor(state, frameCount, nearSide)

    /**
     * Builds reference frames from arbitrary raw BVH text (such as custom or downloaded
     * motions from the Bandai Namco Research Motiondataset repository).
     */
    fun executeFromBvh(
        bvhText: String,
        state: AnimationState,
        frameCount: Int,
        nearSide: BodySide = BodySide.RIGHT,
    ): Result<Map<String, Pose>> = runCatching {
        val poses = dataset.buildFromBvh(bvhText, frameCount, nearSide).getOrThrow()
        poses.mapIndexed { index, pose ->
            PoseCell.keyOf(state, index) to pose
        }.toMap()
    }
}
