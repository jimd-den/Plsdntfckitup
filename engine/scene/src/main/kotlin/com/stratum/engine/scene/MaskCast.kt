package com.stratum.engine.scene

import com.stratum.core.domain.motion.MotionBank
import com.stratum.core.domain.motion.MotionBody
import com.stratum.core.domain.motion.MotionPose
import com.stratum.core.domain.motion.MotionProfile

/**
 * The cast of mask characters on screen: the hero and every monster, each a
 * floating mask spirit, moved by the procedural motion layers and handed to
 * the scene as [SpiritInstance]s.
 *
 * The one place the motion system ([MotionBank], in the pure domain) meets
 * the scene's drawing types, so the game on a phone and the preview images
 * on a build machine animate masks with exactly the same code.
 *
 * ```
 * cast.begin()
 * cast.track("player", heroMask, profile, x, y, z, fx, fy)?.let { if (swung) it.strike() }
 * ...
 * cast.advance(dt)
 * sceneBuilder.build(..., spirits = cast.spirits)
 * ```
 *
 * Only presentation: the gameplay position given to [track] is never
 * changed, and a body the simulation drops is played out through its death
 * before it leaves.
 *
 * Allocation-free per frame once every character has appeared: instances
 * are pooled by slot and the list handed out is reused.
 */
class MaskCast(capacity: Int = MotionBank.DEFAULT_CAPACITY) {
    val bank = MotionBank(capacity)
    private val instances = arrayOfNulls<SpiritInstance>(capacity)
    private val drawn = ArrayList<SpiritInstance>(capacity)

    /** What to draw this frame, in a stable order. Valid until the next [advance]. */
    val spirits: List<SpiritInstance> get() = drawn

    fun begin() = bank.begin()

    /**
     * A character this frame: its mask, its motion profile, where it truly
     * is and faces, and how tall its mask stands in blocks.
     */
    fun track(
        id: String, mesh: SpiritMesh, profile: MotionProfile, x: Float, y: Float, z: Float, facingX: Float, facingY: Float,
        height: Float = DEFAULT_HEIGHT, spawning: Boolean = true,
    ): MotionBody? {
        val body = bank.track(id, profile, x, y, z, facingX, facingY, height, spawning) ?: return null
        body.tag = mesh
        return body
    }

    /**
     * A character whose mask is broken open by its spirit: drawn as its
     * floating pieces round the core, each on its own spring.
     */
    fun track(
        id: String, spirit: ShatteredSpirit, profile: MotionProfile, x: Float, y: Float, z: Float, facingX: Float, facingY: Float,
        height: Float = DEFAULT_HEIGHT, spawning: Boolean = true,
    ): MotionBody? {
        val body = bank.track(id, profile, x, y, z, facingX, facingY, height, spawning) ?: return null
        body.tag = spirit
        return body
    }

    fun body(id: String): MotionBody? = bank.body(id)

    /** The pose of a character as it will be drawn, or null. */
    fun spiritOf(id: String): SpiritInstance? {
        var found: SpiritInstance? = null
        for (i in drawn.indices) if (drawn[i].id == id) found = drawn[i]
        return found
    }

    /** Advances every body and rebuilds [spirits]. */
    fun advance(dt: Float) {
        bank.advance(dt)
        drawn.clear()
        bank.forEach { slot, body, pose ->
            val tag = body.tag
            val broken = tag as? ShatteredSpirit
            val mesh = broken?.core ?: tag as? SpiritMesh ?: return@forEach
            val instance = instances[slot] ?: SpiritInstance(mesh).also { instances[slot] = it }
            if (instance.id != body.id) { instance.pose.trailCount = 0; instance.id = body.id; instance.rig = null }
            instance.mesh = mesh
            dress(instance.pose, body, pose, bank.fringeAt(slot), body.profile.fringe > 0f, bank.sizeAt(slot))
            instance.shattered = broken
            if (broken == null) instance.rig = null
            else {
                val rig = instance.rig?.takeIf { it.spirit === broken } ?: ShardRig(broken).also { instance.rig = it }
                rig.update(instance.pose, dt)
            }
            drawn += instance
        }
    }

    fun clear() { bank.clear(); drawn.clear() }

    companion object {
        /** A character's mask, in blocks: about as tall as a painted character's head and shoulders. */
        const val DEFAULT_HEIGHT = 0.95f

        /**
         * Copies what the layers decided into the scene's pose. The layers
         * tip the top of the body forward with positive pitch; the scene's
         * rotation tips the face up with it, hence the sign.
         */
        fun dress(out: SpiritPose, body: MotionBody, pose: MotionPose, fringe: com.stratum.core.domain.motion.Fringe, hasFringe: Boolean, height: Float) {
            out.x = pose.x; out.y = pose.y; out.z = pose.z
            out.yaw = pose.yaw; out.pitch = -pose.pitch; out.roll = pose.roll
            // The layers' scale carries the profile's size and every swell; the height is the mask's own.
            out.scale = pose.scale * height
            out.stretch = pose.stretch
            out.glow = pose.glow; out.eyes = pose.eyes; out.lines = pose.lines; out.crest = pose.crest
            out.opacity = pose.opacity; out.flash = pose.flash; out.shield = pose.shield; out.flare = pose.flare
            out.shatter = pose.shatter; out.crack = pose.crack
            out.shieldX = pose.x; out.shieldY = pose.y; out.shieldZ = pose.z
            out.shieldFacingX = -kotlin.math.sin(pose.yaw); out.shieldFacingY = kotlin.math.cos(pose.yaw)
            out.pushTrail()
            if (hasFringe) {
                val n = minOf(fringe.ribbons, SpiritPose.MAX_RIBBONS) * minOf(fringe.points, SpiritPose.MAX_POINTS) * 3
                fringe.positions.copyInto(out.fringe, 0, 0, n)
                out.fringeRibbons = minOf(fringe.ribbons, SpiritPose.MAX_RIBBONS); out.fringePoints = minOf(fringe.points, SpiritPose.MAX_POINTS)
            } else {
                out.fringeRibbons = 0
            }
            out.handCount = body.profile.hands
            pose.hands.copyInto(out.hands); out.handGlow = pose.handGlow
            out.charms = body.profile.charms; out.charmSpin = pose.charmSpin
        }
    }
}
