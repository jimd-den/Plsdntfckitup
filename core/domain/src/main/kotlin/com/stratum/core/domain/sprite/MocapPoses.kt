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
        PoseAngles(shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR),
        PoseAngles(
            driftY = -0.003f, lean = 0.4f, shoulderNear = 8.4f, shoulderFar = -8.4f,
            shoulderNearOut = ARMS_CLEAR + 0.4f, shoulderFarOut = ARMS_CLEAR + 0.4f,
        ),
        PoseAngles(
            driftY = -0.007f, lean = 0.9f, shoulderNear = 9f, shoulderFar = -9f,
            shoulderNearOut = ARMS_CLEAR + 0.9f, shoulderFarOut = ARMS_CLEAR + 0.9f,
        ),
        PoseAngles(
            driftY = -0.008f, lean = 1f, shoulderNear = 9.2f, shoulderFar = -9.2f,
            headTilt = -0.5f,
            // The chest fills on the in-breath, which pushes the arms out a
            // shade. Tiny, and the reason an idle reads as breathing rather
            // than as bobbing.
            shoulderNearOut = ARMS_CLEAR + 1.2f, shoulderFarOut = ARMS_CLEAR + 1.2f,
        ),
        PoseAngles(
            driftY = -0.005f, lean = 0.6f, shoulderNear = 8.6f, shoulderFar = -8.6f,
            shoulderNearOut = ARMS_CLEAR + 0.7f, shoulderFarOut = ARMS_CLEAR + 0.7f,
        ),
        PoseAngles(
            driftY = -0.001f, lean = 0.2f, shoulderNear = 8.1f, shoulderFar = -8.1f,
            shoulderNearOut = ARMS_CLEAR + 0.2f, shoulderFarOut = ARMS_CLEAR + 0.2f,
        ),
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
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.014f,
        ),
        // Passing: the swing leg comes through *under* the body with the shin
        // folded back, which is a negative knee. Drawn out, bending it forward
        // instead threw the foot out ahead of the figure and read as a kick.
        PoseAngles(
            hipNear = 4f, kneeNear = 0f, hipFar = 20f, kneeFar = -55f,
            shoulderNear = -6f, elbowNear = 8f, shoulderFar = 6f, elbowFar = -8f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.004f,
        ),
        // Reaching: the swing leg thrown forward at full extension and the
        // body at its highest, the moment before the heel lands.
        PoseAngles(
            hipNear = -14f, kneeNear = 6f, hipFar = 32f, kneeFar = -14f,
            shoulderNear = 16f, elbowNear = -10f, shoulderFar = -16f, elbowFar = 10f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.012f,
        ),
        // The same three again with the legs and arms swapped.
        PoseAngles(
            hipNear = -22f, kneeNear = 10f, hipFar = 25f, kneeFar = -6f,
            shoulderNear = 24f, elbowNear = -14f, shoulderFar = -24f, elbowFar = 14f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.014f,
        ),
        PoseAngles(
            hipNear = 20f, kneeNear = -55f, hipFar = 4f, kneeFar = 0f,
            shoulderNear = 6f, elbowNear = -8f, shoulderFar = -6f, elbowFar = 8f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.004f,
        ),
        PoseAngles(
            hipNear = 32f, kneeNear = -14f, hipFar = -14f, kneeFar = 6f,
            shoulderNear = -16f, elbowNear = 10f, shoulderFar = 16f, elbowFar = -10f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.012f,
        ),
    )

    private val attack = listOf(
        // Drawn out, the first version of this reached *forward* at head
        // height: the forearm bent back towards the near side, which put the
        // hand in front of the face rather than behind the shoulder. A wind-up
        // needs the hand up and towards the FAR side, past vertical.
        PoseAngles(
            shoulderNearOut = 38f, shoulderFarOut = 24f,
            shoulderNear = 200f, elbowNear = 16f, shoulderFar = 186f, elbowFar = 20f,
            hipNear = 12f, kneeNear = -8f, hipFar = -16f, kneeFar = 14f,
            lean = -10f, headTilt = -4f,
        ),
        PoseAngles(
            shoulderNearOut = 30f, shoulderFarOut = 16f,
            shoulderNear = 168f, elbowNear = 4f, shoulderFar = 150f, elbowFar = 8f,
            hipNear = 16f, kneeNear = -6f, hipFar = -14f, kneeFar = 16f,
            lean = -2f,
        ),
        PoseAngles(
            shoulderNearOut = 20f, shoulderFarOut = 6f,
            shoulderNear = 80f, elbowNear = 8f, shoulderFar = 58f, elbowFar = 14f,
            hipNear = 24f, kneeNear = -6f, hipFar = -14f, kneeFar = 24f,
            lean = 14f, headTilt = 6f, driftX = 0.012f,
        ),
        PoseAngles(
            shoulderNearOut = 12f, shoulderFarOut = 0f,
            shoulderNear = 42f, elbowNear = 20f, shoulderFar = -18f, elbowFar = 22f,
            hipNear = 16f, kneeNear = -4f, hipFar = -10f, kneeFar = 16f,
            lean = 6f,
        ),
        // Settling: weapon low at the side, weight coming back over both feet,
        // still carrying a little of the swing forward.
        PoseAngles(
            shoulderNearOut = 8f, shoulderFarOut = 0f,
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
            shoulderNearOut = 5f, shoulderFarOut = 0f,
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
            // Drawn in, so the arms cross the body rather than hang beside it.
            shoulderNear = 26f, elbowNear = 108f, shoulderFar = -26f, elbowFar = -108f,
            shoulderNearOut = -18f, shoulderFarOut = -18f,
            hipNear = -12f, kneeNear = 26f, hipFar = 12f, kneeFar = 26f,
            hipNearOut = 8f, hipFarOut = 8f,
            lean = 10f, headTilt = 8f, driftY = 0.03f,
        ),
        PoseAngles(
            shoulderNear = 70f, elbowNear = 20f, shoulderFar = -70f, elbowFar = -20f,
            shoulderNearOut = 32f, shoulderFarOut = 32f,
            hipNear = -4f, kneeNear = 10f, hipFar = 4f, kneeFar = 10f,
            hipNearOut = 6f, hipFarOut = 6f,
            driftY = 0.006f,
        ),
        PoseAngles(
            // Wide, now that wide is a thing a pose can say. This used to be a
            // hundred and fifty degrees of forward swing, which is an arm
            // wrapped over the top of its own shoulder -- the nearest a
            // fore-and-aft skeleton could get to "thrown open".
            shoulderNear = 40f, elbowNear = 10f, shoulderFar = -40f, elbowFar = -10f,
            shoulderNearOut = 84f, shoulderFarOut = 84f,
            lean = -8f, headTilt = -12f, driftY = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 34f, elbowNear = 16f, shoulderFar = -34f, elbowFar = -16f,
            shoulderNearOut = 54f, shoulderFarOut = 54f,
            lean = 2f,
        ),
        PoseAngles(
            shoulderNear = 22f, elbowNear = 10f, shoulderFar = -22f, elbowFar = -10f,
            shoulderNearOut = 28f, shoulderFarOut = 28f,
            lean = 1f,
        ),
        PoseAngles(
            shoulderNear = 12f, elbowNear = 2f, shoulderFar = -12f, elbowFar = -2f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            hipNear = 2f, hipFar = -2f,
        ),
    )

    /** Snapped back, doubled over, and six frames of getting upright again. */
    private val hurt = listOf(
        PoseAngles(
            // Flung out, which the old numbers could only say by throwing the
            // arms forward over the head.
            shoulderNear = 46f, elbowNear = -34f, shoulderFar = -46f, elbowFar = 34f,
            shoulderNearOut = 74f, shoulderFarOut = 74f,
            hipNear = -14f, kneeNear = 12f, hipFar = 16f, kneeFar = 8f,
            lean = -18f, headTilt = -16f, driftX = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 44f, elbowNear = 62f, shoulderFar = -30f, elbowFar = 40f,
            // Coming back in across the body as it doubles over.
            shoulderNearOut = 22f, shoulderFarOut = 14f,
            hipNear = -8f, kneeNear = 24f, hipFar = -28f, kneeFar = 34f,
            lean = 20f, headTilt = 14f, driftX = -0.045f,
        ),
        // The worst of the stagger: bent low over the front knee, arms pulled
        // in to the body rather than flung out.
        PoseAngles(
            shoulderNear = 30f, elbowNear = 86f, shoulderFar = -20f, elbowFar = 64f,
            shoulderNearOut = -12f, shoulderFarOut = -12f,
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
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            hipNear = 2f, kneeNear = 4f, hipFar = -4f, kneeFar = 4f,
            lean = 4f, driftX = -0.012f,
        ),
    )

    /** Tuck, over, out onto a knee, up, and back onto both feet. */
    private val roll = listOf(
        // A roll is one shape turning over, so it is written as one shape
        // turning over: the tuck barely changes and the body pitches through
        // a full revolution beneath it. Written as six separate postures it
        // came out as a knot -- the limbs were angled from straight down while
        // the spine was bent past horizontal, so they hung as though the
        // character were still standing up inside its own somersault.
        PoseAngles(
            shoulderNear = 30f, elbowNear = 96f, shoulderFar = -26f, elbowFar = -96f,
            shoulderNearOut = 16f, shoulderFarOut = 16f,
            hipNear = -38f, kneeNear = 88f, hipFar = -42f, kneeFar = 92f,
            hipNearOut = 10f, hipFarOut = 10f,
            bodyPitch = 20f, headTilt = 14f, driftY = 0.09f,
        ),
        PoseAngles(
            shoulderNear = 34f, elbowNear = 108f, shoulderFar = -30f, elbowFar = -108f,
            shoulderNearOut = 14f, shoulderFarOut = 14f,
            hipNear = -52f, kneeNear = 104f, hipFar = -56f, kneeFar = 108f,
            hipNearOut = 8f, hipFarOut = 8f,
            bodyPitch = 95f, driftY = 0.13f,
        ),
        PoseAngles(
            shoulderNear = 36f, elbowNear = 112f, shoulderFar = -32f, elbowFar = -112f,
            shoulderNearOut = 12f, shoulderFarOut = 12f,
            hipNear = -56f, kneeNear = 108f, hipFar = -60f, kneeFar = 112f,
            hipNearOut = 8f, hipFarOut = 8f,
            bodyPitch = 180f, driftY = 0.155f,
        ),
        PoseAngles(
            shoulderNear = 34f, elbowNear = 100f, shoulderFar = -30f, elbowFar = -100f,
            shoulderNearOut = 12f, shoulderFarOut = 12f,
            hipNear = -48f, kneeNear = 96f, hipFar = -52f, kneeFar = 100f,
            hipNearOut = 8f, hipFarOut = 8f,
            bodyPitch = 265f, driftY = 0.12f,
        ),
        // Out of the roll and onto a knee, the body nearly upright again.
        PoseAngles(
            shoulderNear = 26f, elbowNear = 44f, shoulderFar = -20f, elbowFar = 40f,
            shoulderNearOut = 14f, shoulderFarOut = 12f,
            hipNear = 34f, kneeNear = -62f, hipFar = -28f, kneeFar = 80f,
            hipNearOut = 8f, hipFarOut = 6f,
            bodyPitch = 330f, lean = 18f, driftY = 0.06f,
        ),
        // Standing, weight still carrying forward.
        PoseAngles(
            shoulderNear = 12f, elbowNear = 10f, shoulderFar = -12f, elbowFar = 6f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            hipNear = 12f, kneeNear = -8f, hipFar = -10f, kneeFar = 14f,
            lean = 5f, driftY = 0.01f,
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
            // Splayed, which is the word the instruction uses and the thing a
            // fore-and-aft skeleton could not do: limbs fallen away from the
            // body rather than folded in front of it.
            shoulderNearOut = 46f, shoulderFarOut = 38f,
            hipNear = -68f, kneeNear = 40f, hipFar = -96f, kneeFar = 28f,
            hipNearOut = 26f, hipFarOut = 20f,
            lean = 78f, headTilt = 14f, driftY = 0.28f,
        ),
        // Settling: one arm falling further out, the body a little flatter.
        PoseAngles(
            shoulderNear = -70f, elbowNear = 24f, shoulderFar = -108f, elbowFar = -16f,
            shoulderNearOut = 54f, shoulderFarOut = 44f,
            hipNear = -76f, kneeNear = 26f, hipFar = -100f, kneeFar = 18f,
            hipNearOut = 30f, hipFarOut = 24f,
            lean = 82f, headTilt = 10f, driftY = 0.31f,
        ),
        // At rest. Still not a single horizontal line, for the same reason the
        // frame before it is not: a body has to stay readable as a body.
        PoseAngles(
            shoulderNear = -78f, elbowNear = 18f, shoulderFar = -112f, elbowFar = -12f,
            shoulderNearOut = 58f, shoulderFarOut = 48f,
            hipNear = -82f, kneeNear = 16f, hipFar = -104f, kneeFar = 10f,
            hipNearOut = 32f, hipFarOut = 26f,
            lean = 85f, headTilt = 8f, driftY = 0.33f,
        ),
    )

    /**
     * How far an arm hangs clear of the body at rest.
     *
     * Small, and not optional. A limb with no lift at all occupies the same
     * plane as the torso, so an arm hanging by its side passes through the
     * ribs -- which a stick figure gets away with and a guide handed to an
     * image model does not: the model draws what it is shown, and what it was
     * shown was an arm inside a chest.
     */
    private const val ARMS_CLEAR = 7f
}
