package com.stratum.core.domain.bvh

/**
 * The channel types defined by the BioVision Hierarchy (BVH) motion capture format.
 *
 * Each channel specifies one degree of freedom for a joint in the hierarchy.
 * In BVH files, channels appear in a specific order per joint (e.g. Zrotation,
 * Yrotation, Xrotation), which dictates the order in which Euler angle
 * rotations must be evaluated during forward kinematics.
 */
enum class BvhChannelType {
    XPOSITION,
    YPOSITION,
    ZPOSITION,
    XROTATION,
    YROTATION,
    ZROTATION;

    val isPosition: Boolean
        get() = this == XPOSITION || this == YPOSITION || this == ZPOSITION

    val isRotation: Boolean
        get() = this == XROTATION || this == YROTATION || this == ZROTATION

    companion object {
        fun fromString(token: String): BvhChannelType? = when (token.uppercase()) {
            "XPOSITION" -> XPOSITION
            "YPOSITION" -> YPOSITION
            "ZPOSITION" -> ZPOSITION
            "XROTATION" -> XROTATION
            "YROTATION" -> YROTATION
            "ZROTATION" -> ZROTATION
            else -> null
        }
    }
}

/**
 * A joint node in the BVH skeletal tree.
 *
 * Skeletons in the Bandai Namco Research Motiondataset are structured as a tree
 * of joints starting from a root joint (typically "Hips" or "Root"). Each joint
 * has an initial rest-pose offset relative to its parent, a list of animation
 * channels, zero or more child joints, and optionally an end site offset.
 */
data class BvhJoint(
    val name: String,
    val offset: FloatArray,
    val channels: List<BvhChannelType> = emptyList(),
    val children: List<BvhJoint> = emptyList(),
    val endSiteOffset: FloatArray? = null,
) {
    /** Total number of channels across this joint and all of its descendants. */
    val totalChannels: Int
        get() = channels.size + children.sumOf { it.totalChannels }

    /** Flattened list of all joints in depth-first traversal order. */
    val allJoints: List<BvhJoint>
        get() = listOf(this) + children.flatMap { it.allJoints }

    /** Finds a joint by name, case-insensitively. */
    fun findJoint(jointName: String): BvhJoint? {
        if (name.equals(jointName, ignoreCase = true)) return this
        for (child in children) {
            val found = child.findJoint(jointName)
            if (found != null) return found
        }
        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BvhJoint
        if (name != other.name) return false
        if (!offset.contentEquals(other.offset)) return false
        if (channels != other.channels) return false
        if (children != other.children) return false
        if (endSiteOffset != null) {
            if (other.endSiteOffset == null) return false
            if (!endSiteOffset.contentEquals(other.endSiteOffset)) return false
        } else if (other.endSiteOffset != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + offset.contentHashCode()
        result = 31 * result + channels.hashCode()
        result = 31 * result + children.hashCode()
        result = 31 * result + (endSiteOffset?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * One frame of motion data, containing raw channel values in depth-first order.
 */
data class BvhFrame(
    val values: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BvhFrame
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()
}

/**
 * A complete motion capture clip parsed from a BVH file.
 *
 * Holds the hierarchical skeleton definition, the frame rate, and all recorded frames.
 */
data class BvhClip(
    val name: String,
    val root: BvhJoint,
    val frames: List<BvhFrame>,
    val frameTime: Float,
) {
    val frameCount: Int get() = frames.size

    val durationSeconds: Float get() = frameCount * frameTime

    /** Safely retrieves a frame by index, clamped to valid boundaries. */
    fun frameAt(index: Int): BvhFrame? {
        if (frames.isEmpty()) return null
        return frames[index.coerceIn(0, frames.size - 1)]
    }
}
