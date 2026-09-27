package com.stratum.engine.world

import com.stratum.core.domain.combat.CombatStats
import com.stratum.core.domain.tabletop.ActiveBoon

/**
 * The tabletop side of a run: which boons and banes are running, and which
 * checks are still cooling down. Kept apart from the player's own state
 * because it is borrowed power with a clock on it, not who they are.
 */
internal class TableState {

    var boons: List<ActiveBoon> = emptyList()
        private set

    private val cooldowns = HashMap<String, Float>()

    fun cooldownOf(checkId: String): Float = cooldowns[checkId] ?: 0f

    fun startCooldown(checkId: String, seconds: Float) {
        if (seconds > 0f) cooldowns[checkId] = seconds
    }

    /** A second boon from the same check replaces the first rather than stacking. */
    fun grant(boon: ActiveBoon) {
        boons = boons.filterNot { it.fromCheckId == boon.fromCheckId } + boon
    }

    fun advance(deltaSeconds: Float) {
        boons = boons.mapNotNull { it.aged(deltaSeconds) }
        val iterator = cooldowns.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val remaining = entry.value - deltaSeconds
            if (remaining <= 0f) iterator.remove() else entry.setValue(remaining)
        }
    }

    fun applyTo(stats: CombatStats): CombatStats = boons.fold(stats) { acc, active -> active.boon.applyTo(acc) }

    fun clear() {
        boons = emptyList()
        cooldowns.clear()
    }
}
