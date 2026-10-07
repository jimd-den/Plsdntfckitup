package com.stratum.engine.world

import com.stratum.core.domain.settlement.SettlementPlan
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.floor

/**
 * Where hostiles may not go and may not strike: the bounds of the friendly
 * towns in sight, when towns are safe.
 *
 * A hostile walking up to one stops at its edge; nothing hostile casts from
 * inside one or at anything standing in one, and no attack's blow, burst or
 * voxel payload lands there. The towns are refreshed each tick from what
 * the politics can see.
 */
internal class SafeGround(private val enabled: Boolean) {

    @Volatile private var towns: List<SettlementPlan> = emptyList()

    /** The friendly towns in sight now. */
    fun update(friendly: List<SettlementPlan>) {
        towns = if (enabled) friendly else emptyList()
    }

    val isEmpty: Boolean get() = towns.isEmpty()

    fun contains(x: Float, y: Float): Boolean {
        val t = towns
        if (t.isEmpty()) return false
        val bx = floor(x).toInt(); val by = floor(y).toInt()
        return t.any { it.contains(bx, by) }
    }

    fun contains(at: WorldPoint): Boolean = contains(at.x, at.y)
}
