package com.stratum.core.domain.sprite

/**
 * Every pose the pipeline knows, as joint angles.
 *
 * Not motion capture in the literal sense — nobody wore a suit — but the same
 * job: a skeleton per frame, authored once, that everything else reads from.
 * Three things need it and all three were previously guessing.
 *
 * The image model needs it, because prose is a poor way to specify a body.
 * "Left leg forward with the heel touching the ground, right arm swung forward"
 * is unambiguous to a person and merely suggestive to an image model, which is
 * why generated attack frames kept coming back as a cross-body guard instead of
 * an impact. A drawing of the pose is not suggestive.
 *
 * The weapon rig needs it, because a weapon is held in a hand and points along
 * a forearm — and a skeleton knows exactly where both are. Anchors used to be
 * authored blind and then corrected per character; measured against real art,
 * the blind version put the hand a full hand's width outside the body.
 *
 * And the renderer needs it for the same reason, so the number tuned against
 * the guide is the number the game draws with.
 *
 * Angles are degrees anticlockwise from straight down. An arm at the side is 0,
 * out to the near side is 90, overhead is 180. Elbows and knees are relative to
 * the limb above them, so bending an elbow does not move the shoulder.
 */
object MocapPoses {

    /** The frames of one animation, in play order. */
    fun framesFor(state: AnimationState): List<PoseAngles> = when (state) {
        AnimationState.IDLE -> idle
        AnimationState.WALK -> walk
        AnimationState.ATTACK -> attack
        AnimationState.SPECIAL -> special
        AnimationState.HURT -> hurt
        AnimationState.ROLL -> roll
        AnimationState.DIE -> die
    }

    /**
     * The pose for one frame, tolerant of a clip that came back a different
     * length than the script asked for.
     *
     * A model that returned five attack frames instead of four should still get
     * a rig, and stretching the authored frames across whatever arrived is a
     * better answer than refusing or than leaving the last frame unposed.
     */
    fun poseFor(state: AnimationState, index: Int, frameCount: Int): PoseAngles {
        val frames = framesFor(state)
        if (frames.isEmpty()) return PoseAngles()
        if (frameCount <= 1) return frames.first()
        val t = (index.toFloat() / (frameCount - 1)).coerceIn(0f, 1f)
        val exact = t * (frames.size - 1)
        val at = exact.toInt().coerceIn(0, frames.size - 1)
        val next = (at + 1).coerceAtMost(frames.size - 1)
        // Blended rather than truncated to the authored frame. Truncating sent
        // every frame to the earlier pose: six idle frames from two authored
        // ones came out as five identical stills and one odd last frame, which
        // is precisely how an idle ends up reading as a twitch.
        return frames[at].blendedTo(frames[next], exact - at)
    }

    /**
     * A breath, and back to where it started.
     *
     * Authored as a full cycle rather than as two ends of one. An idle is the
     * pose a character holds for most of the time anybody looks at it, so it
     * is the one place where a two-frame approximation is most visible: played
     * as a loop, rest and in-breath alternating is a shiver, not breathing.
     * Rising takes longer than falling, the way a real breath does, which is
     * why the peak sits at frame three of six rather than in the middle.
     *
     * The movement is deliberately tiny -- eight thousandths of the body's
     * height at the top. It is the smallest thing on this list and the one
     * that decides whether a character looks alive while standing still.
     */
    private val idle = listOf(
        PoseAngles(),
        PoseAngles(driftY = -0.003f, lean = 0.4f, shoulderNear = 8.4f, shoulderFar = -8.4f),
        PoseAngles(driftY = -0.007f, lean = 0.9f, shoulderNear = 9f, shoulderFar = -9f),
        PoseAngles(
            driftY = -0.008f, lean = 1f, shoulderNear = 9.2f, shoulderFar = -9.2f,
            headTilt = -0.5f,
        ),
        PoseAngles(driftY = -0.005f, lean = 0.6f, shoulderNear = 8.6f, shoulderFar = -8.6f),
        PoseAngles(driftY = -0.001f, lean = 0.2f, shoulderNear = 8.1f, shoulderFar = -8.1f),
    )

    /**
     * Contact, passing, reaching — twice, once for each leg.
     *
     * The oldest frames in animation, at the length the script now asks for.
     * Arms swing opposite the legs, and the body drops at the contacts and
     * rises through the passes, which is the part that makes it read as
     * walking rather than as gliding.
     *
     * The order is the cycle, which matters more here than in any other state:
     * the last frame hands back to the first, so a reach that sits at the end
     * of the list instead of between a pass and a contact makes the character
     * hitch once per stride.
     */
    private val walk = listOf(
        // Contact: front heel down with the leg nearly straight, back leg
        // trailing with only a little bend so the toe stays down.
        PoseAngles(
            hipNear = 25f, kneeNear = -6f, hipFar = -22f, kneeFar = 10f,
            shoulderNear = -24f, elbowNear = 14f, shoulderFar = 24f, elbowFar = -14f,
            driftY = 0.014f,
        ),
        // Passing: the swing leg comes through *under* the body with the shin
        // folded back, which is a negative knee. Drawn out, bending it forward
        // instead threw the foot out ahead of the figure and read as a kick.
        PoseAngles(
            hipNear = 4f, kneeNear = 0f, hipFar = 20f, kneeFar = -55f,
            shoulderNear = -6f, elbowNear = 8f, shoulderFar = 6f, elbowFar = -8f,
            driftY = -0.004f,
        ),
        // Reaching: the swing leg thrown forward at full extension and the
        // body at its highest, the moment before the heel lands.
        PoseAngles(
            hipNear = -14f, kneeNear = 6f, hipFar = 32f, kneeFar = -14f,
            shoulderNear = 16f, elbowNear = -10f, shoulderFar = -16f, elbowFar = 10f,
            driftY = -0.012f,
        ),
        // The same three again with the legs and arms swapped.
        PoseAngles(
            hipNear = -22f, kneeNear = 10f, hipFar = 25f, kneeFar = -6f,
            shoulderNear = 24f, elbowNear = -14f, shoulderFar = -24f, elbowFar = 14f,
            driftY = 0.014f,
        ),
        PoseAngles(
            hipNear = 20f, kneeNear = -55f, hipFar = 4f, kneeFar = 0f,
            shoulderNear = 6f, elbowNear = -8f, shoulderFar = -6f, elbowFar = 8f,
            driftY = -0.004f,
        ),
        PoseAngles(
            hipNear = 32f, kneeNear = -14f, hipFar = -14f, kneeFar = 6f,
            shoulderNear = -16f, elbowNear = 10f, shoulderFar = 16f, elbowFar = -10f,
            driftY = -0.012f,
        ),
    )

    private val attack = listOf(
        // Drawn out, the first version of this reached *forward* at head
        // height: the forearm bent back towards the near side, which put the
        // hand in front of the face rather than behind the shoulder. A wind-up
        // needs the hand up and towards the FAR side, past vertical.
        PoseAngles(
            shoulderNear = 200f, elbowNear = 16f, shoulderFar = 186f, elbowFar = 20f,
            hipNear = 12f, kneeNear = -8f, hipFar = -16f, kneeFar = 14f,
            lean = -10f, headTilt = -4f,
        ),
        PoseAngles(
            shoulderNear = 168f, elbowNear = 4f, shoulderFar = 150f, elbowFar = 8f,
            hipNear = 16f, kneeNear = -6f, hipFar = -14f, kneeFar = 16f,
            lean = -2f,
        ),
        PoseAngles(
            shoulderNear = 80f, elbowNear = 8f, shoulderFar = 58f, elbowFar = 14f,
            hipNear = 24f, kneeNear = -6f, hipFar = -14f, kneeFar = 24f,
            lean = 14f, headTilt = 6f, driftX = 0.012f,
        ),
        PoseAngles(
            shoulderNear = 42f, elbowNear = 20f, shoulderFar = -18f, elbowFar = 22f,
            hipNear = 16f, kneeNear = -4f, hipFar = -10f, kneeFar = 16f,
            lean = 6f,
        ),
        // Settling: weapon low at the side, weight coming back over both feet,
        // still carrying a little of the swing forward.
        PoseAngles(
            shoulderNear = 26f, elbowNear = 14f, shoulderFar = -12f, elbowFar = 16f,
            hipNear = 8f, kneeNear = -2f, hipFar = -6f, kneeFar = 8f,
            lean = 3f,
        ),
        // Still coming down, never back up. The first version of this frame
        // raised the weapon into a guard, which is what a fighter really does
        // -- and which reverses the blade's arc in the last frame of the
        // swing. On a held weapon that reads as the blade snapping backwards.
        // Returning to guard belongs to the move out of this clip, not to the
        // end of it, so the arc runs one way the whole way through.
        PoseAngles(
            shoulderNear = 12f, elbowNear = 8f, shoulderFar = -26f, elbowFar = 12f,
            hipNear = 4f, kneeNear = -2f, hipFar = -4f, kneeFar = 4f,
            lean = 1f,
        ),
    )

    /**
     * Gather, rise, release, then three frames of coming back down.
     *
     * Symmetrical throughout, so it never reads as a swing.
     */
    private val special = listOf(
        PoseAngles(
            shoulderNear = 26f, elbowNear = 108f, shoulderFar = -26f, elbowFar = -108f,
            hipNear = -12f, kneeNear = 26f, hipFar = 12f, kneeFar = 26f,
            lean = 10f, headTilt = 8f, driftY = 0.03f,
        ),
        PoseAngles(
            shoulderNear = 108f, elbowNear = 20f, shoulderFar = -108f, elbowFar = -20f,
            hipNear = -4f, kneeNear = 10f, hipFar = 4f, kneeFar = 10f,
            driftY = 0.006f,
        ),
        PoseAngles(
            shoulderNear = 150f, elbowNear = 10f, shoulderFar = -150f, elbowFar = -10f,
            lean = -8f, headTilt = -12f, driftY = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 58f, elbowNear = 16f, shoulderFar = -58f, elbowFar = -16f,
            lean = 2f,
        ),
        PoseAngles(
            shoulderNear = 30f, elbowNear = 10f, shoulderFar = -30f, elbowFar = -10f,
            lean = 1f,
        ),
        PoseAngles(
            shoulderNear = 12f, elbowNear = 2f, shoulderFar = -12f, elbowFar = -2f,
            hipNear = 2f, hipFar = -2f,
        ),
    )

    /** Snapped back, doubled over, and six frames of getting upright again. */
    private val hurt = listOf(
        PoseAngles(
            shoulderNear = 122f, elbowNear = -34f, shoulderFar = -122f, elbowFar = 34f,
            hipNear = -14f, kneeNear = 12f, hipFar = 16f, kneeFar = 8f,
            lean = -18f, headTilt = -16f, driftX = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 44f, elbowNear = 62f, shoulderFar = -30f, elbowFar = 40f,
            hipNear = -8f, kneeNear = 24f, hipFar = -28f, kneeFar = 34f,
            lean = 20f, headTilt = 14f, driftX = -0.045f,
        ),
        // The worst of the stagger: bent low over the front knee, arms pulled
        // in to the body rather than flung out.
        PoseAngles(
            shoulderNear = 30f, elbowNear = 86f, shoulderFar = -20f, elbowFar = 64f,
            hipNear = -4f, kneeNear = 34f, hipFar = -34f, kneeFar = 44f,
            lean = 32f, headTilt = 22f, driftX = -0.06f, driftY = 0.04f,
        ),
        // Catching it: back foot planted, torso starting to come back up.
        PoseAngles(
            shoulderNear = 26f, elbowNear = 70f, shoulderFar = -22f, elbowFar = 44f,
            hipNear = 4f, kneeNear = 22f, hipFar = -26f, kneeFar = 30f,
            lean = 22f, headTilt = 14f, driftX = -0.05f, driftY = 0.022f,
        ),
        PoseAngles(
            shoulderNear = 18f, elbowNear = 40f, shoulderFar = -18f, elbowFar = 24f,
            hipNear = 4f, kneeNear = 12f, hipFar = -14f, kneeFar = 16f,
            lean = 12f, headTilt = 6f, driftX = -0.03f, driftY = 0.008f,
        ),
        // Recovered, but not back to the idle: still tensed, which is what
        // stops a flinch from ending in a shrug.
        PoseAngles(
            shoulderNear = 12f, elbowNear = 16f, shoulderFar = -12f, elbowFar = 8f,
            hipNear = 2f, kneeNear = 4f, hipFar = -4f, kneeFar = 4f,
            lean = 4f, driftX = -0.012f,
        ),
    )

    /** Tuck, over, out onto a knee, up, and back onto both feet. */
    private val roll = listOf(
        PoseAngles(
            shoulderNear = 32f, elbowNear = 116f, shoulderFar = -32f, elbowFar = -116f,
            hipNear = -46f, kneeNear = 104f, hipFar = -50f, kneeFar = 108f,
            lean = 34f, headTilt = 20f, driftY = 0.1f,
        ),
        PoseAngles(
            shoulderNear = 40f, elbowNear = 120f, shoulderFar = -40f, elbowFar = -120f,
            hipNear = -70f, kneeNear = 120f, hipFar = -74f, kneeFar = 124f,
            lean = 148f, driftY = 0.13f,
        ),
        PoseAngles(
            shoulderNear = 70f, elbowNear = 40f, shoulderFar = -20f, elbowFar = 60f,
            hipNear = 42f, kneeNear = -74f, hipFar = -34f, kneeFar = 96f,
            lean = 26f, driftY = 0.075f,
        ),
        PoseAngles(
            shoulderNear = 20f, elbowNear = 24f, shoulderFar = -24f, elbowFar = 20f,
            hipNear = 20f, kneeNear = -18f, hipFar = -16f, kneeFar = 30f,
            lean = 10f, driftY = 0.02f,
        ),
        // Upright with the momentum still carrying forward, one foot ahead.
        PoseAngles(
            shoulderNear = 14f, elbowNear = 14f, shoulderFar = -18f, elbowFar = 12f,
            hipNear = 14f, kneeNear = -8f, hipFar = -12f, kneeFar = 16f,
            lean = 6f, driftY = 0.006f,
        ),
        PoseAngles(
            shoulderNear = 8f, elbowNear = 4f, shoulderFar = -8f, elbowFar = 2f,
            hipNear = 4f, kneeNear = -2f, hipFar = -4f, kneeFar = 4f,
            lean = 2f,
        ),
    )

    /** Buckling, down on a knee, folding, and three frames of going still. */
    private val die = listOf(
        PoseAngles(
            shoulderNear = 26f, elbowNear = 22f, shoulderFar = -30f, elbowFar = -18f,
            hipNear = -6f, kneeNear = 32f, hipFar = 8f, kneeFar = 30f,
            lean = 18f, headTilt = 14f, driftY = 0.03f,
        ),
        PoseAngles(
            shoulderNear = 18f, elbowNear = 34f, shoulderFar = -16f, elbowFar = -30f,
            hipNear = 10f, kneeNear = 20f, hipFar = -62f, kneeFar = 120f,
            lean = 28f, headTilt = 20f, driftY = 0.12f,
        ),
        PoseAngles(
            shoulderNear = 8f, elbowNear = 30f, shoulderFar = -10f, elbowFar = -26f,
            hipNear = -50f, kneeNear = 90f, hipFar = -60f, kneeFar = 100f,
            lean = 66f, headTilt = 22f, driftY = 0.2f,
        ),
        // Not quite flat: a corpse collapsed onto a single horizontal line is
        // unreadable as a body, and the guide has to be legible before it can
        // be followed.
        PoseAngles(
            shoulderNear = -58f, elbowNear = 34f, shoulderFar = -100f, elbowFar = -26f,
            hipNear = -68f, kneeNear = 40f, hipFar = -96f, kneeFar = 28f,
            lean = 78f, headTilt = 14f, driftY = 0.28f,
        ),
        // Settling: one arm falling further out, the body a little flatter.
        PoseAngles(
            shoulderNear = -70f, elbowNear = 24f, shoulderFar = -108f, elbowFar = -16f,
            hipNear = -76f, kneeNear = 26f, hipFar = -100f, kneeFar = 18f,
            lean = 82f, headTilt = 10f, driftY = 0.31f,
        ),
        // At rest. Still not a single horizontal line, for the same reason the
        // frame before it is not: a body has to stay readable as a body.
        PoseAngles(
            shoulderNear = -78f, elbowNear = 18f, shoulderFar = -112f, elbowFar = -12f,
            hipNear = -82f, kneeNear = 16f, hipFar = -104f, kneeFar = 10f,
            lean = 85f, headTilt = 8f, driftY = 0.33f,
        ),
    )
}
