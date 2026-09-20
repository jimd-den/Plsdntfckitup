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
     * A breath and a shift of weight, and back to where it started.
     *
     * Authored as a full cycle rather than as two ends of one. An idle is the
     * pose a character holds for most of the time anybody looks at it, so it
     * is the one place where a two-frame approximation is most visible: played
     * as a loop, rest and in-breath alternating is a shiver, not breathing.
     * Rising takes longer than falling, the way a real breath does, which is
     * why the peak sits at frame three of six rather than in the middle.
     *
     * The hard part is not the amount of movement, it is the *direction*. The
     * first two versions of this put nearly all their amplitude into the
     * shoulders swinging forward and back, and a swing forward is along the
     * camera's own axis: the projection foreshortens it to almost nothing, so
     * the pose sheet read as a still image no matter how far the numbers were
     * pushed. Measured on screen, four times the amplitude bought four
     * thousandths of body height.
     *
     * So the motion goes where the camera can see it -- straight up, and
     * sideways. The chest lifts, the arms come *out* from the body rather than
     * forward, and the weight shifts onto one hip and back. That last is what
     * carries most of it: a lateral shift projects at full size, and it is
     * also what a person standing still actually does.
     */
    private val idle = listOf(
        // Every frame states every angle it moves, including the rest frame.
        //
        // This is not style. PoseAngles defaults a standing figure's arms to
        // eight degrees forward and its hips to two, because that is what a
        // person at rest looks like -- and the first version of this breath
        // left the rest frame on those defaults while writing the other five
        // out in full. Frame one asked for four degrees. So the arms went
        // *backwards* out of the rest pose and forwards again into frame two,
        // and the near hand moved three times as far in that one step as in
        // any other. A breath, measured, that began with a twitch.
        PoseAngles(
            shoulderNear = 8f, shoulderFar = -8f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            elbowNear = 0f, elbowFar = 0f,
            hipNear = 2f, kneeNear = 0f, hipFar = -2f,
            lean = 0f, headTilt = 0f, driftY = 0f, driftX = 0f,
        ),
        PoseAngles(
            shoulderNear = 9.5f, shoulderFar = -9.5f,
            shoulderNearOut = ARMS_CLEAR + 2.8f, shoulderFarOut = ARMS_CLEAR + 2.8f,
            elbowNear = -1.5f, elbowFar = 1.5f,
            hipNear = 1.2f, kneeNear = 0.8f, hipFar = -2.2f,
            lean = 1.4f, headTilt = -0.6f, driftY = -0.013f, driftX = -0.009f,
        ),
        PoseAngles(
            shoulderNear = 11f, shoulderFar = -11f,
            shoulderNearOut = ARMS_CLEAR + 5.4f, shoulderFarOut = ARMS_CLEAR + 5.4f,
            elbowNear = -3f, elbowFar = 3f,
            hipNear = 0.6f, kneeNear = 1.6f, hipFar = -2.4f,
            lean = 2.6f, headTilt = -1.2f, driftY = -0.025f, driftX = -0.017f,
        ),
        PoseAngles(
            // The top of the breath, and the weight fully onto the near hip.
            shoulderNear = 12f, shoulderFar = -12f,
            shoulderNearOut = ARMS_CLEAR + 7f, shoulderFarOut = ARMS_CLEAR + 7f,
            elbowNear = -3.6f, elbowFar = 3.6f,
            hipNear = 0.2f, kneeNear = 2.2f, hipFar = -2.5f,
            lean = 3.2f, headTilt = -1.6f, driftY = -0.033f, driftX = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 10.5f, shoulderFar = -10.5f,
            shoulderNearOut = ARMS_CLEAR + 4.6f, shoulderFarOut = ARMS_CLEAR + 4.6f,
            elbowNear = -2.5f, elbowFar = 2.5f,
            hipNear = 0.8f, kneeNear = 1.4f, hipFar = -2.3f,
            lean = 2.2f, headTilt = -1f, driftY = -0.022f, driftX = -0.015f,
        ),
        PoseAngles(
            // Out again, and a hair above the first frame so the loop closes
            // without landing on it twice.
            shoulderNear = 9f, shoulderFar = -9f,
            shoulderNearOut = ARMS_CLEAR + 2.1f, shoulderFarOut = ARMS_CLEAR + 2.1f,
            elbowNear = -1f, elbowFar = 1f,
            hipNear = 1.4f, kneeNear = 0.6f, hipFar = -2.1f,
            lean = 1f, headTilt = -0.4f, driftY = -0.01f, driftX = -0.007f,
        ),
    )

    /**
     * Contact, down, passing -- twice, once for each leg.
     *
     * The beats matter more here than in any other state, because a walk is
     * the one clip a person watches for minutes at a time. The first version
     * of this used contact / passing / reaching, and reaching is the moment
     * *just before* the next heel lands: measured, the reach frame sat a third
     * of a step from its neighbour where every other frame sat a whole one, so
     * the cycle stalled once per stride. The reach and the contact after it
     * are the same beat drawn twice.
     *
     * Replacing the reach with the *down* -- weight arriving over the lead
     * leg, the knee absorbing it, the body at its lowest -- gives three beats
     * that are genuinely a third of a stride apart, and gives the walk its
     * bounce: lowest at the down, highest at the passing.
     *
     * The order is the cycle, so the last frame hands back to the first. A
     * passing frame at the end and a contact at the start is a full beat
     * apart, which is what stops the hitch.
     */
    private val walk = listOf(
        // Contact: front heel down with the leg nearly straight, back leg
        // trailing with the shin angled forward under it so the toe stays
        // down. Body at mid height, on its way from the passing to the down.
        PoseAngles(
            hipNear = 27f, kneeNear = -3f, hipFar = -25f, kneeFar = 12f,
            shoulderNear = -26f, elbowNear = 15f, shoulderFar = 26f, elbowFar = -15f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.005f,
        ),
        // Down: the whole weight over the lead leg, its knee bent to take the
        // landing, the trailing leg pushing off with the heel already up. The
        // lowest point of the cycle, and the frame that carries the impact.
        PoseAngles(
            hipNear = 13f, kneeNear = -15f, hipFar = -28f, kneeFar = 2f,
            shoulderNear = -13f, elbowNear = 9f, shoulderFar = 13f, elbowFar = -9f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.021f,
        ),
        // Passing: the swing leg comes through *under* the body with the shin
        // folded back, which is a negative knee. Drawn out, bending it forward
        // instead threw the foot out ahead of the figure and read as a kick.
        // The support leg is straight and the body is at its highest.
        PoseAngles(
            hipNear = -6f, kneeNear = 1f, hipFar = 15f, kneeFar = -36f,
            shoulderNear = 3f, elbowNear = -2f, shoulderFar = -3f, elbowFar = 2f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.017f,
        ),
        // The same three again with the legs and arms swapped.
        PoseAngles(
            hipNear = -25f, kneeNear = 12f, hipFar = 27f, kneeFar = -3f,
            shoulderNear = 26f, elbowNear = -15f, shoulderFar = -26f, elbowFar = 15f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.005f,
        ),
        PoseAngles(
            hipNear = -28f, kneeNear = 2f, hipFar = 13f, kneeFar = -15f,
            shoulderNear = 13f, elbowNear = -9f, shoulderFar = -13f, elbowFar = 9f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = 0.021f,
        ),
        PoseAngles(
            hipNear = 15f, kneeNear = -36f, hipFar = -6f, kneeFar = 1f,
            shoulderNear = -3f, elbowNear = 2f, shoulderFar = 3f, elbowFar = -2f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            driftY = -0.017f,
        ),
    )

    /**
     * One swing, accelerating into the impact and decelerating out of it.
     *
     * Measured frame to frame, the first version of this spent four frames
     * barely moving and did the entire strike in one: the near shoulder went
     * from 168 degrees to 80 in a single step, three times the travel of any
     * other, while the last two frames moved almost nothing at all. At a fixed
     * frame duration that is not a fast swing, it is a teleport followed by a
     * pause.
     *
     * So the strike is spread across three frames and the follow-through is
     * given real distance to cover. The swing still accelerates -- the steps
     * run roughly 28, 48, 62, 38, 22 degrees, which is the shape of a real
     * blow -- but no single frame does more than about half again what its
     * neighbours do.
     */
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
        // Breaking out of the wind-up. Still slow: the weight has not gone
        // forward yet, so this is the arm starting to fall, not the blow.
        PoseAngles(
            shoulderNearOut = 32f, shoulderFarOut = 18f,
            shoulderNear = 172f, elbowNear = 6f, shoulderFar = 156f, elbowFar = 10f,
            hipNear = 15f, kneeNear = -7f, hipFar = -15f, kneeFar = 15f,
            lean = -4f, headTilt = -2f,
        ),
        // Accelerating. The arms are nearly straight through here -- an
        // extended elbow is what carries the weapon's tip fastest, and a bent
        // one at this point reads as a shove.
        PoseAngles(
            shoulderNearOut = 22f, shoulderFarOut = 10f,
            shoulderNear = 124f, elbowNear = 4f, shoulderFar = 110f, elbowFar = 8f,
            hipNear = 20f, kneeNear = -6f, hipFar = -14f, kneeFar = 20f,
            lean = 6f, headTilt = 2f, driftX = 0.005f,
        ),
        // Impact: the fastest frame, the body furthest forward over the lead
        // leg, the weapon roughly level.
        PoseAngles(
            shoulderNearOut = 12f, shoulderFarOut = 4f,
            shoulderNear = 62f, elbowNear = 10f, shoulderFar = 44f, elbowFar = 14f,
            hipNear = 25f, kneeNear = -6f, hipFar = -14f, kneeFar = 25f,
            lean = 14f, headTilt = 6f, driftX = 0.012f,
        ),
        // Follow-through: the swing carries past, the weight starts coming
        // back over both feet, the elbows fold as the arms slow.
        PoseAngles(
            shoulderNearOut = 6f, shoulderFarOut = 0f,
            shoulderNear = 24f, elbowNear = 18f, shoulderFar = -6f, elbowFar = 20f,
            hipNear = 14f, kneeNear = -4f, hipFar = -9f, kneeFar = 13f,
            lean = 7f, headTilt = 3f, driftX = 0.004f,
        ),
        // Still coming down, never back up. The first version of this frame
        // raised the weapon into a guard, which is what a fighter really does
        // -- and which reverses the blade's arc in the last frame of the
        // swing. On a held weapon that reads as the blade snapping backwards.
        // Returning to guard belongs to the move out of this clip, not to the
        // end of it, so the arc runs one way the whole way through.
        PoseAngles(
            shoulderNearOut = 4f, shoulderFarOut = 0f,
            shoulderNear = 2f, elbowNear = 12f, shoulderFar = -26f, elbowFar = 14f,
            hipNear = 5f, kneeNear = -2f, hipFar = -4f, kneeFar = 4f,
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
            lean = 1f, headTilt = -2f, driftY = 0.006f,
        ),
        PoseAngles(
            // Wide, now that wide is a thing a pose can say. This used to be a
            // hundred and fifty degrees of forward swing, which is an arm
            // wrapped over the top of its own shoulder -- the nearest a
            // fore-and-aft skeleton could get to "thrown open".
            //
            // The legs are written out from here on rather than left to the
            // defaults. Left off, they snapped from the crouch to a neutral
            // stand in one frame and then held it for three, so the release
            // recoiled from the waist up while the lower body stood still.
            shoulderNear = 40f, elbowNear = 10f, shoulderFar = -40f, elbowFar = -10f,
            shoulderNearOut = 84f, shoulderFarOut = 84f,
            hipNear = -2f, kneeNear = 2f, hipFar = 2f, kneeFar = 2f,
            hipNearOut = 3f, hipFarOut = 3f,
            lean = -8f, headTilt = -12f, driftY = -0.022f,
        ),
        PoseAngles(
            shoulderNear = 34f, elbowNear = 16f, shoulderFar = -34f, elbowFar = -16f,
            shoulderNearOut = 54f, shoulderFarOut = 54f,
            hipNear = 0f, kneeNear = 4f, hipFar = 0f, kneeFar = 4f,
            hipNearOut = 2f, hipFarOut = 2f,
            lean = 2f, headTilt = -6f, driftY = -0.008f,
        ),
        PoseAngles(
            shoulderNear = 22f, elbowNear = 10f, shoulderFar = -22f, elbowFar = -10f,
            shoulderNearOut = 28f, shoulderFarOut = 28f,
            hipNear = 1f, kneeNear = 3f, hipFar = -1f, kneeFar = 3f,
            hipNearOut = 1f, hipFarOut = 1f,
            lean = 1f, headTilt = -2f, driftY = 0.002f,
        ),
        PoseAngles(
            shoulderNear = 12f, elbowNear = 2f, shoulderFar = -12f, elbowFar = -2f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            hipNear = 2f, kneeNear = 0f, hipFar = -2f, kneeFar = 0f,
            hipNearOut = 0f, hipFarOut = 0f,
            lean = 0f, headTilt = 0f, driftY = 0f,
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
        // The recoil. Measured, the first version folded the arms all the way
        // in here -- shoulders from 74 degrees out to 22 and the near elbow
        // from straight to past a right angle in one frame -- which moved the
        // hands four times as far as any later frame did. A flinch is fast,
        // but a frame that outruns its neighbours by that much reads as the
        // arms vanishing and reappearing. The fold starts here and finishes at
        // the bottom of the stagger.
        PoseAngles(
            shoulderNear = 44f, elbowNear = 24f, shoulderFar = -34f, elbowFar = 18f,
            shoulderNearOut = 50f, shoulderFarOut = 46f,
            hipNear = -8f, kneeNear = 22f, hipFar = -24f, kneeFar = 30f,
            lean = 14f, headTilt = 10f, driftX = -0.04f,
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
            shoulderNear = 24f, elbowNear = 46f, shoulderFar = -21f, elbowFar = 34f,
            shoulderNearOut = 2f, shoulderFarOut = 2f,
            hipNear = 4f, kneeNear = 17f, hipFar = -23f, kneeFar = 23f,
            lean = 18f, headTilt = 11f, driftX = -0.042f, driftY = 0.018f,
        ),
        // Rising. The recovery used to spend its last three frames moving
        // almost nothing, so half the clip was a held pose; it covers real
        // ground now, which is also what keeps the stagger from looking like
        // the character got stuck part-way down.
        PoseAngles(
            shoulderNear = 16f, elbowNear = 22f, shoulderFar = -15f, elbowFar = 16f,
            shoulderNearOut = ARMS_CLEAR - 2f, shoulderFarOut = ARMS_CLEAR - 2f,
            hipNear = 3f, kneeNear = 8f, hipFar = -12f, kneeFar = 11f,
            lean = 9f, headTilt = 5f, driftX = -0.024f, driftY = 0.005f,
        ),
        // Recovered, but not back to the idle: still tensed, which is what
        // stops a flinch from ending in a shrug.
        PoseAngles(
            shoulderNear = 10f, elbowNear = 6f, shoulderFar = -10f, elbowFar = 4f,
            shoulderNearOut = ARMS_CLEAR, shoulderFarOut = ARMS_CLEAR,
            hipNear = 2f, kneeNear = 2f, hipFar = -3f, kneeFar = 3f,
            lean = 3f, driftX = -0.008f,
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
            shoulderNear = -44f, elbowNear = 34f, shoulderFar = -88f, elbowFar = -26f,
            // Splayed, which is the word the instruction uses and the thing a
            // fore-and-aft skeleton could not do: limbs fallen away from the
            // body rather than folded in front of it.
            shoulderNearOut = 38f, shoulderFarOut = 32f,
            hipNear = -60f, kneeNear = 50f, hipFar = -86f, kneeFar = 34f,
            hipNearOut = 20f, hipFarOut = 16f,
            lean = 70f, headTilt = 16f, driftY = 0.24f,
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
