package com.stratum.feature.play

import com.stratum.core.domain.content.AssembledContent
import com.stratum.core.domain.motion.MotionProfile

/**
 * What the loaded packs say about how characters look as mask spirits:
 * monsters whose mask or motion a pack pinned, and the packs' own motion
 * profiles. Worked out once per world, not per frame.
 */
data class MaskLooks(
    /** Monster definition id to a mask genome code or preset name. */
    val masks: Map<String, String> = emptyMap(),
    /** Monster definition id to a motion profile id. */
    val motions: Map<String, String> = emptyMap(),
    /** The packs' motion profiles by id, over the built-in ones. */
    val profiles: Map<String, MotionProfile> = emptyMap(),
) {
    companion object {
        fun of(content: AssembledContent) = MaskLooks(
            masks = content.enemies.mapNotNull { e -> e.mask?.let { e.id to it } }.toMap(),
            motions = content.enemies.mapNotNull { e -> e.motion?.let { e.id to it } }.toMap(),
            profiles = content.motionOverrides,
        )
    }
}
