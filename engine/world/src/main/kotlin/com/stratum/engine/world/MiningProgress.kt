package com.stratum.engine.world

import com.stratum.core.domain.world.BlockPos
import com.stratum.core.domain.world.World

/**
 * Effort spent on the block being mined.
 *
 * Kept per target here rather than by the caller, so releasing and pressing
 * again on the same block does not silently restart the dig, and moving to a
 * different block does.
 */
internal class MiningProgress {

    var target: BlockPos? = null
        private set

    var effort: Float = 0f
        private set

    /** Effort already spent on [pos]: what was spent before if it is the same block, else none. */
    fun effortOn(pos: BlockPos): Float {
        if (pos != target) {
            target = pos
            effort = 0f
        }
        return effort
    }

    fun record(effort: Float) {
        this.effort = effort
    }

    fun reset() {
        target = null
        effort = 0f
    }

    /** How far through the current dig, 0..1, for the progress ring. */
    fun fraction(world: World): Float {
        val pos = target ?: return 0f
        val hardness = world.blockAt(pos).hardness
        return if (hardness <= 0f) 0f else (effort / hardness).coerceIn(0f, 1f)
    }
}
