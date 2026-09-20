package com.stratum.core.domain.sprite

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether one frame flows into the next.
 *
 * Every other test here checks a pose on its own -- that an arm is where it
 * was asked to be, that a swing does not reverse. All of them passed while the
 * animations were being complained about for looking like random stills, and
 * they passed because a pose being right says nothing about the distance to
 * the next one. That distance is the animation.
 *
 * So this measures the thing a person actually sees: how far the furthest
 * joint travels between consecutive frames, on screen, after projection. Two
 * failures show up in that one number.
 *
 * A step far larger than its neighbours is a teleport. It was an 88 degree
 * shoulder swing in a single attack frame, and a flinch that folded both arms
 * in at once, and it reads as the limb vanishing and reappearing rather than
 * as speed.
 *
 * A step far smaller is a stall. It was a walk whose reach frame sat a third
 * of a step from the contact after it, because a reach and the contact that
 * follows it are the same beat drawn twice, so the character hitched once per
 * stride.
 *
 * The evenness this asks for is deliberately loose. An attack should
 * accelerate into its impact and a flinch should snap; what is ruled out is a
 * frame doing several times what its neighbours do.
 */
class PoseFlowTest {

    private val skeleton = Skeleton()

    /** The joints at the ends of limbs, where a discontinuity shows first. */
    private val tracked = listOf(
        Joint.HEAD, Joint.CHEST, Joint.PELVIS,
        Joint.HAND_NEAR, Joint.HAND_FAR,
        Joint.FOOT_NEAR, Joint.FOOT_FAR,
        Joint.KNEE_NEAR, Joint.KNEE_FAR,
    )

    /** Clips that play back to their own first frame, where the join is a step too. */
    private val cyclic = setOf(AnimationState.IDLE, AnimationState.WALK)

    @Test
    fun `no frame jumps or stalls against its neighbours`() {
        AnimationState.generatedRowOrder.forEach { state ->
            val steps = stepsOf(state)
            val median = steps.sorted()[steps.size / 2]
            val worst = steps.max()
            assertTrue(
                worst <= median * MAX_RATIO,
                "$state has a frame that jumps: steps $steps, worst $worst against a median of $median",
            )
            // The last step of a clip that does not loop is the settle, and a
            // body coming to rest is meant to decelerate: a death that stopped
            // as fast as it fell would read as the character being switched
            // off. Every step before it still has to keep pace, and a looping
            // clip has no rest to settle into, so none of its steps are
            // excused.
            val paced = if (state in AnimationState.oneShot) steps.dropLast(1) else steps
            val least = paced.min()
            assertTrue(
                least >= median / MAX_RATIO,
                "$state has a frame that stalls: steps $steps, least $least against a median of $median",
            )
        }
    }

    @Test
    fun `a looping clip hands its last frame back to its first`() {
        cyclic.forEach { state ->
            val poses = posesOf(state)
            val steps = stepsOf(state)
            val median = steps.sorted()[steps.size / 2]
            val loop = travel(poses.last(), poses.first())
            assertTrue(
                loop <= median * MAX_RATIO,
                "$state jumps on the loop: $loop against a median step of $median",
            )
        }
    }

    /**
     * An idle has to move enough to be seen.
     *
     * The original was authored at eight thousandths of body height, which on
     * a 192 pixel cell is a pixel and a half, and read as a still image. The
     * catch is that pushing the numbers does not necessarily fix it: an arm
     * swinging forward moves along the camera's own axis and the isometric
     * projection all but erases it, so four times the amplitude bought almost
     * nothing. This measures what is left after projection, which is the only
     * number that decides whether the character looks alive standing still.
     */
    @Test
    fun `the idle breathes visibly`() {
        val steps = stepsOf(AnimationState.IDLE)
        assertTrue(
            steps.min() >= MIN_IDLE_TRAVEL,
            "the idle is a still image: steps $steps, all of which need to reach $MIN_IDLE_TRAVEL",
        )
    }

    /**
     * Catches a frame that leaves an angle to its default while its
     * neighbours state it.
     *
     * PoseAngles rests a standing figure's arms eight degrees forward and its
     * hips two, so an omitted angle is not zero -- it is a pose of its own.
     * The breath was authored around that: its rest frame took the defaults
     * and every other frame wrote four degrees, so the arms swung backwards
     * out of rest and forwards again, and the near hand moved three times as
     * far in that step as in any other.
     */
    @Test
    fun `an angle that moves in one frame is stated in all of them`() {
        AnimationState.entries.forEach { state ->
            val frames = MocapPoses.framesFor(state)
            if (frames.size < 2) return@forEach
            val rest = PoseAngles()
            anglesWithNonZeroRest.forEach { (name, read) ->
                val values = frames.map(read)
                val restValue = read(rest)
                val moves = values.distinct().size > 1
                val restsOnDefault = values.count { it == restValue }
                assertTrue(
                    !moves || restsOnDefault <= 1,
                    "$state moves $name but leaves $restsOnDefault frames on its default of " +
                        "$restValue -- state it in every frame or none: $values",
                )
            }
        }
    }

    private fun posesOf(state: AnimationState): List<Pose> =
        (0 until FRAMES).map { skeleton.pose(MocapPoses.poseFor(state, it, FRAMES)) }

    private fun stepsOf(state: AnimationState): List<Float> {
        val poses = posesOf(state)
        return (0 until FRAMES - 1).map { travel(poses[it], poses[it + 1]) }
    }

    /** How far the furthest tracked joint moves, on screen. */
    private fun travel(from: Pose, to: Pose): Float = tracked.maxOf { joint ->
        val a = from.joints[joint] ?: return@maxOf 0f
        val b = to.joints[joint] ?: return@maxOf 0f
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
    }

    private companion object {
        /** What the pose script asks for, and what the guide sheet is laid out as. */
        const val FRAMES = 6

        /** Loose on purpose: a blow accelerates, but not by several times. */
        const val MAX_RATIO = 2.5f

        /** Four pixels on a 192 pixel cell — a breath, not a bounce. */
        const val MIN_IDLE_TRAVEL = 0.01f

        /** The angles whose unstated value is a pose rather than nothing. */
        val anglesWithNonZeroRest: List<Pair<String, (PoseAngles) -> Float>> = listOf(
            "shoulderNear" to { it: PoseAngles -> it.shoulderNear },
            "shoulderFar" to { it: PoseAngles -> it.shoulderFar },
            "hipNear" to { it: PoseAngles -> it.hipNear },
            "hipFar" to { it: PoseAngles -> it.hipFar },
        )
    }
}
