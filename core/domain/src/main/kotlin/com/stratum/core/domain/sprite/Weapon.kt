package com.stratum.core.domain.sprite

/**
 * What a weapon is, for the purposes of drawing and swinging it.
 *
 * Kind rather than free text because three things downstream need to branch on
 * it and none of them can read a description: how it is drawn, where the hand
 * goes on it, and how it moves through a swing. An axe and a bow are not the
 * same object rotated.
 */
enum class WeaponKind(
    val label: String,
    /** How the model is told to draw it. */
    val description: String,
    /**
     * Where the hand sits on the drawn weapon, as a fraction of its box.
     *
     * Every weapon is drawn pointing straight up with its handle at the bottom,
     * so the grip is always low and centred — but not identically. A sword is
     * held just above the pommel; a staff is held a third of the way up; an axe
     * is held low on a long haft.
     *
     * Measured on a generated blade: tip to pommel spanned the drawing, the
     * grip sat about 82% of the way down, and the first guess of 88% put the
     * hand on the pommel itself.
     */
    val gripY: Float,
    /**
     * How long it reads on screen, relative to the character's height.
     *
     * Measured against real art rather than guessed, after the first set came
     * out greatsword-sized on everything: a one-handed blade that reaches
     * two-thirds of a person's height is a two-handed blade. These are the
     * fractions a weapon of each kind actually occupies standing next to a
     * figure.
     */
    val reach: Float,
) {
    SWORD("Sword", "a one-handed sword with a straight or curved blade, a crossguard and a pommel", 0.82f, 0.46f),
    AXE("Axe", "a one-handed axe: a heavy head on a shaft, the head at the top", 0.8f, 0.44f),
    MACE("Mace", "a one-handed mace or club, heaviest at the top", 0.82f, 0.4f),
    DAGGER("Dagger", "a short dagger with a narrow blade", 0.8f, 0.24f),
    SPEAR("Spear", "a long spear: a narrow point on a long straight shaft", 0.68f, 0.82f),
    STAFF("Staff", "a long staff or quarterstaff, the same thickness along its length", 0.6f, 0.8f),
    BOW("Bow", "a bow, the stave curving away from the string, held at its middle", 0.5f, 0.5f),
    SHIELD("Shield", "a shield seen from the front, its face towards the viewer", 0.5f, 0.3f),
    ;

    /** The grip is centred on every kind; only its height along the weapon differs. */
    val gripX: Float get() = 0.5f
}

/**
 * A weapon's art, drawn on its own and owned by nobody.
 *
 * This exists because a weapon drawn into a character is that character's
 * forever. It cannot be dropped, it cannot be swapped for a better one, and it
 * has to be redrawn for every actor that carries one — which in a game about
 * picking up loot is the wrong way round entirely.
 *
 * Holds no pixels, like everything else here: the bytes live under [id].
 */
data class WeaponSprite(
    val id: String,
    val name: String,
    val kind: WeaponKind,
    val width: Int,
    val height: Int,
    /**
     * Where the hand grips it, as a fraction of the drawn box.
     *
     * Taken from the kind by default and overridable, because a generated
     * weapon does not always land where it was asked to. This is the single
     * number that decides whether a sword looks held or looks impaled through
     * the palm, so it is worth being able to nudge.
     */
    val gripX: Float = kind.gripX,
    val gripY: Float = kind.gripY,
    val origin: SpriteOrigin = SpriteOrigin.AI_GENERATED,
)

/** Whether the weapon passes in front of the body this frame or behind it. */
enum class WeaponLayer { BEHIND, IN_FRONT }

/**
 * Where a weapon sits for one frame of one animation.
 *
 * Positions are fractions of the character's frame box rather than pixels, so
 * one rig serves a 64 pixel sheet and a 192 pixel one alike — and survives the
 * sheet being re-packed at another size, which the pose pipeline does routinely.
 *
 * [rotationDegrees] is measured from the weapon as drawn, which is pointing
 * straight up. Positive turns clockwise. So a sword resting at the side is
 * about 150 degrees, and one at the top of a wind-up is about -120.
 */
data class WeaponAnchor(
    val xFraction: Float,
    val yFraction: Float,
    val rotationDegrees: Float,
    val scale: Float = 1f,
    /**
     * Which side of the body it passes.
     *
     * The thing that makes an attached weapon read as held rather than as a
     * sticker. A wind-up goes behind the shoulder and a strike comes across the
     * front, and getting that backwards is instantly legible as wrong even to
     * someone who could not say why.
     */
    val layer: WeaponLayer = WeaponLayer.IN_FRONT,
)

/**
 * A weapon's positions across a whole sheet, keyed by frame.
 *
 * Keyed by frame index rather than by state, because that is what the renderer
 * has in its hand: it has already resolved the clip and the elapsed time into a
 * frame number, and asking it to work backwards to a state would be asking it
 * to redo the playback logic.
 */
data class WeaponRig(val anchors: Map<Int, WeaponAnchor> = emptyMap()) {
    fun anchorFor(frame: Int): WeaponAnchor? = anchors[frame]

    val isEmpty: Boolean get() = anchors.isEmpty()
}

/**
 * One character's correction to the authored arcs.
 *
 * The arcs are universal — a wind-up goes back and up behind the shoulder for
 * every character who has ever swung anything — but the *proportions* are not.
 * A stocky figure holds a weapon lower and nearer the centre line than a lanky
 * one, and a sheet packed at a different fill fraction shifts everything again.
 * Measured on a generated warrior, the first authored anchors put the hand a
 * hand's width outside the character entirely.
 *
 * So the shape of the swing stays authored and the character supplies three
 * numbers. That is a far better split than either extreme: authoring twenty-four
 * anchors per character is work nobody will do twice, and deriving them from the
 * art means finding a hand in a drawing.
 */
data class WeaponFit(
    /** Added to every anchor, as a fraction of the frame. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Multiplies the weapon's reach, for a character drawn larger or smaller in its cell. */
    val scale: Float = 1f,
    /**
     * Flips the rig about the centre line, because the drawing came back
     * mirrored from the guide.
     *
     * Measured, and it is not a subtle effect. Handed a stick figure with the
     * weapon arm extended to one side, an image model reproduces the *shape* of
     * the pose faithfully — the lunge, the lean, the extension — and then draws
     * it on whichever side it prefers. Mirroring the guide and asking again
     * produced the same handedness, so this is the model imposing its own, not
     * a coin toss.
     *
     * A whole flip rather than a large [offsetX], because it is one: the hand
     * measured at 0.86 of the frame where the rig expected 0.2, which no
     * sensible offset range would ever cover, and halfway between them is the
     * character's navel.
     */
    val mirrored: Boolean = false,
) {
    fun applyTo(anchor: WeaponAnchor): WeaponAnchor {
        val x = if (mirrored) 1f - anchor.xFraction else anchor.xFraction
        return anchor.copy(
            xFraction = x + offsetX,
            yFraction = anchor.yFraction + offsetY,
            scale = anchor.scale * scale,
            // The blade has to turn with the hand. Reflecting a rotation about
            // the vertical negates it; leaving it alone would put the sword in
            // the correct fist pointing the wrong way, which reads worse than
            // the original error did.
            rotationDegrees = if (mirrored) -anchor.rotationDegrees else anchor.rotationDegrees,
        )
    }

    val isIdentity: Boolean
        get() = offsetX == 0f && offsetY == 0f && scale == 1f && !mirrored

    companion object {
        val none = WeaponFit()

        /** Wider than this and the weapon is not being corrected, it is being lost. */
        const val MAX_OFFSET = 0.35f
        const val MIN_SCALE = 0.3f
        const val MAX_SCALE = 3f
    }
}

/**
 * Where a weapon goes, read off the skeleton rather than guessed.
 *
 * This used to be a hand-authored arc: a wind-up at these degrees, a strike at
 * those, interpolated between. It was wrong, and measurably so — replaying the
 * renderer's arithmetic over real art put the hand a full hand's width outside
 * the character, and the correction had to be dialled in per character by eye.
 *
 * The skeleton removes the guess entirely. A weapon is held in a hand and
 * points along the forearm; [MocapPoses] already says exactly where the hand is
 * and which way the forearm runs, for every frame of every animation, because
 * the same data is what the image model was given to draw the pose from. The
 * arc is no longer authored twice and reconciled by hand — there is one
 * skeleton, and the drawing and the weapon both follow it.
 */
object WeaponPosing {

    /**
     * The anchor for one pose, or null when the hand should be empty.
     *
     * Null rather than a hidden anchor, so a death that has dropped its weapon
     * simply has none for those frames rather than one drawn at zero scale.
     */
    fun anchorFor(
        state: AnimationState,
        index: Int,
        frameCount: Int,
        // The same source the guide was drawn from, so a character posed
        // against an imported skeleton is rigged against that one too. Rigging
        // against the built-in set while the art followed an imported pose puts
        // the sword where the hand is not, which is the bug the skeleton exists
        // to remove.
        guides: PoseGuides = PoseGuides(),
    ): WeaponAnchor? {
        val progress = if (frameCount <= 1) 0f else index.toFloat() / (frameCount - 1)
        // A corpse still gripping a raised sword is the single most common way
        // a death animation looks unfinished.
        if (state == AnimationState.DIE && progress >= DROP_AT) return null

        val pose = guides.riggingPoseFor(state, index, frameCount)
        val grip = pose.weaponGrip()
        return WeaponAnchor(
            xFraction = grip.at.x,
            yFraction = grip.at.y,
            rotationDegrees = grip.weaponDegrees,
            layer = layerFor(pose),
        )
    }

    /** Every anchor for a laid-out sheet, ready to hang on it. */
    fun rigFor(
        sheet: SpriteSheet,
        fit: WeaponFit = WeaponFit.none,
        guides: PoseGuides = PoseGuides(),
    ): WeaponRig = WeaponRig(
        buildMap {
            for (clip in sheet.clips) {
                for (step in 0 until clip.frameCount) {
                    val anchor = anchorFor(clip.state, step, clip.frameCount, guides) ?: continue
                    put(clip.firstFrame + step, fit.applyTo(anchor))
                }
            }
        },
    )

    /**
     * Which side of the body the weapon passes, from where the hand is.
     *
     * A hand raised above its own shoulder is drawn back over the body — the
     * top of a wind-up, an overhead guard — and the weapon belongs behind the
     * figure there. Once the hand drops below the shoulder the swing is coming
     * across the front, and so is the blade. Getting this backwards is
     * instantly legible as wrong even to someone who could not say why, and
     * reading it from the pose means it can never disagree with the drawing.
     */
    private fun layerFor(pose: Pose): WeaponLayer {
        val hand = pose.require(Joint.weaponHand)
        val shoulder = pose.require(Joint.SHOULDER_NEAR)
        return if (hand.y < shoulder.y) WeaponLayer.BEHIND else WeaponLayer.IN_FRONT
    }

    /** How far into a death the weapon leaves the hand. */
    private const val DROP_AT = 0.5f
}
