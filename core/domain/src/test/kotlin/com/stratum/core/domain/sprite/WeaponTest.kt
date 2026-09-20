package com.stratum.core.domain.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeaponPosingTest {

    private fun attack(index: Int, of: Int = 4) =
        assertNotNull(WeaponPosing.anchorFor(AnimationState.ATTACK, index, of))

    @Test
    fun `a swing goes up behind the shoulder and comes down across the front`() {
        val skeleton = Skeleton()
        val windUp = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 0, 4))
        val impact = skeleton.pose(MocapPoses.poseFor(AnimationState.ATTACK, 3, 4))

        // Read off the hand rather than off a rotation, now that the anchor is
        // the hand: the wind-up raises it above the shoulder and the swing
        // brings it back down.
        assertTrue(windUp.require(Joint.HAND_NEAR).y < windUp.require(Joint.SHOULDER_NEAR).y)
        assertTrue(impact.require(Joint.HAND_NEAR).y > impact.require(Joint.SHOULDER_NEAR).y)
        assertEquals(WeaponLayer.BEHIND, attack(0).layer)
        assertEquals(WeaponLayer.IN_FRONT, attack(3).layer)
    }

    @Test
    fun `the swing turns one way the whole way through`() {
        // A blade that reverses mid-arc reads as a mistake rather than as a
        // swing, and is the thing an eased interpolation could quietly do.
        val degrees = (0 until 6).map { attack(it, of = 6).rotationDegrees }
        degrees.zipWithNext { a, b -> assertTrue(b >= a, "the swing reversed: $degrees") }
    }

    @Test
    fun `a swing reads the same whether the attack came back as four frames or six`() {
        assertEquals(attack(0).rotationDegrees, attack(0, of = 6).rotationDegrees, 0.001f)
        assertEquals(attack(3).rotationDegrees, attack(5, of = 6).rotationDegrees, 0.001f)
    }

    @Test
    fun `the weapon crosses to the front once it is past the shoulder`() {
        val layers = (0 until 8).map { attack(it, of = 8).layer }
        // Behind for a while, then in front, and never back again.
        val firstFront = layers.indexOf(WeaponLayer.IN_FRONT)
        assertTrue(firstFront > 0, "the swing started in front of the body")
        assertTrue(
            layers.drop(firstFront).all { it == WeaponLayer.IN_FRONT },
            "the weapon went back behind the body mid-strike: $layers",
        )
    }

    @Test
    fun `a corpse lets go of its weapon`() {
        // A body lying still with a raised sword is the most common way a death
        // animation looks unfinished.
        assertNotNull(WeaponPosing.anchorFor(AnimationState.DIE, 0, 4))
        assertNull(WeaponPosing.anchorFor(AnimationState.DIE, 3, 4))
    }

    @Test
    fun `at rest the weapon hangs point down at the side`() {
        val idle = assertNotNull(WeaponPosing.anchorFor(AnimationState.IDLE, 0, 2))
        // Tip down rather than up: a character standing about with a raised
        // sword reads as permanently mid-attack. 180 is straight down.
        assertTrue(
            kotlin.math.abs(idle.rotationDegrees) > 150f,
            "resting rotation was ${idle.rotationDegrees}",
        )
        // In front of the leg, because the hand it hangs from is the near one
        // and is below the shoulder. Derived, not decided.
        assertEquals(WeaponLayer.IN_FRONT, idle.layer)
    }

    @Test
    fun `a walk carries the weapon without swinging it`() {
        val steps = (0 until 4).map { assertNotNull(WeaponPosing.anchorFor(AnimationState.WALK, it, 4)) }

        // It moves with the arm it is held in, rather than being pinned to the
        // screen while the character bobs underneath it.
        assertTrue(steps.map { it.yFraction }.distinct().size > 1)
        assertTrue(steps.map { it.rotationDegrees }.distinct().size > 1)
        // But it stays pointed broadly downward: a weapon that swung through a
        // walk would read as attacking every other step.
        // Straight down is 180, and rotations are signed, so "downward" is a
        // magnitude near 180 rather than a value above some threshold.
        assertTrue(
            steps.all { kotlin.math.abs(it.rotationDegrees) > 120f },
            "a walk swung the weapon up: ${steps.map { it.rotationDegrees }}",
        )
    }

    @Test
    fun `a rig covers every frame of every clip it is built from`() {
        val sheet = SpriteSheet(
            id = "t", name = "T", columns = 4, rows = 2, frameWidth = 96, frameHeight = 96,
            clips = listOf(
                AnimationClip(AnimationState.IDLE, firstFrame = 0, frameCount = 2),
                AnimationClip(AnimationState.ATTACK, firstFrame = 4, frameCount = 4, loops = false),
            ),
        )
        val rig = WeaponPosing.rigFor(sheet)

        assertNotNull(rig.anchorFor(0))
        assertNotNull(rig.anchorFor(1))
        // The attack's frames sit where the clip says they do, not where a
        // second count of the grid would put them.
        assertNotNull(rig.anchorFor(4))
        assertNotNull(rig.anchorFor(7))
        assertNull(rig.anchorFor(2), "a frame with no clip was given an anchor")
    }

    @Test
    fun `a sheet with a death only rigs the frames still holding something`() {
        val sheet = SpriteSheet(
            id = "t", name = "T", columns = 4, rows = 1, frameWidth = 96, frameHeight = 96,
            clips = listOf(AnimationClip(AnimationState.DIE, firstFrame = 0, frameCount = 4)),
        )
        val rig = WeaponPosing.rigFor(sheet)

        assertNotNull(rig.anchorFor(0))
        assertNull(rig.anchorFor(3))
        assertTrue(rig.anchors.size < 4)
    }
}

class WeaponSpriteTest {

    @Test
    fun `the grip sits low on a sword and high on a staff`() {
        // Every weapon is drawn pointing up with its handle at the bottom, so
        // the grip is always low -- but a staff is held a third of the way up
        // and a sword just above the pommel.
        assertTrue(WeaponKind.SWORD.gripY > WeaponKind.STAFF.gripY)
        assertEquals(0.5f, WeaponKind.SWORD.gripX)
    }

    @Test
    fun `reach says how long a weapon reads against the character, not its canvas`() {
        assertTrue(WeaponKind.SPEAR.reach > WeaponKind.DAGGER.reach)
        // Nothing is longer than the character is tall except the polearms.
        assertTrue(WeaponKind.SWORD.reach < 1f)
    }

    @Test
    fun `a weapon takes its grip from its kind and can still be corrected`() {
        val fromKind = WeaponSprite(
            id = "w", name = "Blade", kind = WeaponKind.AXE, width = 100, height = 300,
        )
        assertEquals(WeaponKind.AXE.gripY, fromKind.gripY)

        // A generated weapon does not always land where it was asked to, and
        // this one number is the difference between held and impaled.
        assertEquals(0.6f, fromKind.copy(gripY = 0.6f).gripY)
    }

    @Test
    fun `an empty rig is simply nothing held`() {
        assertTrue(WeaponRig().isEmpty)
        assertNull(WeaponRig().anchorFor(0))
    }
}

/**
 * The correction a character supplies to the authored arcs.
 *
 * Needed because the arcs cannot be authored accurately: measured against a
 * generated warrior, the first set of anchors put the hand a full hand's width
 * outside the character. The shape of a swing is universal; a figure's
 * proportions are not.
 */
class WeaponFitTest {

    private val anchor = WeaponAnchor(
        xFraction = 0.55f,
        yFraction = 0.24f,
        rotationDegrees = -130f,
        scale = 1f,
    )

    @Test
    fun `a fit moves the hand and resizes the weapon`() {
        val fit = WeaponFit(offsetX = -0.08f, offsetY = -0.04f, scale = 1.2f)
        val fitted = fit.applyTo(anchor)

        assertEquals(0.47f, fitted.xFraction, 0.0001f)
        assertEquals(0.20f, fitted.yFraction, 0.0001f)
        assertEquals(1.2f, fitted.scale, 0.0001f)
    }

    @Test
    fun `a fit never touches the shape of the swing`() {
        // Only where the hand is and how big the weapon is. Bending the arc
        // itself per character would lose the one thing that is universal.
        val fitted = WeaponFit(offsetX = 0.2f, offsetY = -0.2f, scale = 2f).applyTo(anchor)
        assertEquals(anchor.rotationDegrees, fitted.rotationDegrees)
        assertEquals(anchor.layer, fitted.layer)
    }

    @Test
    fun `no fit changes nothing`() {
        assertEquals(anchor, WeaponFit.none.applyTo(anchor))
        assertTrue(WeaponFit.none.isIdentity)
    }

    @Test
    fun `a rig carries the character's fit into every frame`() {
        val sheet = SpriteSheet(
            id = "t", name = "T", columns = 4, rows = 1, frameWidth = 96, frameHeight = 96,
            clips = listOf(AnimationClip(AnimationState.ATTACK, 0, 4, loops = false)),
        )
        val plain = WeaponPosing.rigFor(sheet)
        val fitted = WeaponPosing.rigFor(sheet, WeaponFit(offsetX = -0.08f))

        for (frame in 0 until 4) {
            val before = assertNotNull(plain.anchorFor(frame))
            val after = assertNotNull(fitted.anchorFor(frame))
            assertEquals(before.xFraction - 0.08f, after.xFraction, 0.0001f)
            assertEquals(before.rotationDegrees, after.rotationDegrees)
        }
    }

    @Test
    fun `a weapon dropped mid-death stays dropped however it is fitted`() {
        val sheet = SpriteSheet(
            id = "t", name = "T", columns = 4, rows = 1, frameWidth = 96, frameHeight = 96,
            clips = listOf(AnimationClip(AnimationState.DIE, 0, 4)),
        )
        assertNull(WeaponPosing.rigFor(sheet, WeaponFit(offsetY = 0.2f)).anchorFor(3))
    }
}
