package com.stratum.engine.world

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.session.PlayerState

/** Where the player's life and resource come back from this tick, per second. */
internal data class Regeneration(
    val lifePerSecond: Float,
    val resourcePerSecond: Float,
    val maxHealth: Int,
    val maxResource: Int,
)

/**
 * The player's recovery over time: regeneration, leech and flasks that heal
 * slowly.
 *
 * Health and resource are whole numbers on the player, but recovery is
 * continuous, so fractions are carried here between ticks; otherwise a
 * regeneration of half a point per frame would round to nothing, forever.
 *
 * Leech is a pool drained at the world's leech rate, Path of Exile style: it
 * rewards sustained hitting rather than one big crit, and [CombatRules.maxLeechRate]
 * is the dial a sandbox turns up. The instant-leech keystone skips the pool.
 */
internal class VitalsSystem(private val rules: CombatRules) {

    private var leechPool = 0f
    private var lifeCarry = 0f
    private var resourceCarry = 0f
    private val doses = mutableListOf<FlaskDose>()
    private var wasLow = false

    /** Life waiting to be leeched, for a HUD that wants to show it. */
    val pendingLeech: Float get() = leechPool

    fun leech(amount: Int) {
        if (amount > 0) leechPool += amount
    }

    /** A flask whose restoration is spread over time. */
    fun recoverOverTime(dose: FlaskDose) {
        if (dose.seconds > 0f) doses += dose
    }

    fun advance(player: PlayerState, deltaSeconds: Float, regeneration: Regeneration): PlayerState {
        if (!player.isAlive) return player
        var life = regeneration.lifePerSecond * deltaSeconds
        var resource = regeneration.resourcePerSecond * deltaSeconds

        val leechCap = rules.maxLeechRate * regeneration.maxHealth * deltaSeconds
        val leeched = minOf(leechPool, leechCap)
        leechPool -= leeched
        life += leeched

        val iterator = doses.listIterator()
        while (iterator.hasNext()) {
            val dose = iterator.next()
            val slice = minOf(deltaSeconds, dose.seconds)
            life += dose.life / dose.seconds * slice
            resource += dose.resource / dose.seconds * slice
            val left = dose.copy(life = dose.life - dose.life / dose.seconds * slice, resource = dose.resource - dose.resource / dose.seconds * slice, seconds = dose.seconds - slice)
            if (left.seconds <= 1e-4f) iterator.remove() else iterator.set(left)
        }

        lifeCarry += life
        resourceCarry += resource
        val wholeLife = lifeCarry.toInt()
        val wholeResource = resourceCarry.toInt()
        lifeCarry -= wholeLife
        resourceCarry -= wholeResource
        if (wholeLife == 0 && wholeResource == 0) return player
        return player.copy(
            health = (player.health + wholeLife).coerceIn(0, maxOf(player.health, regeneration.maxHealth)),
            resource = (player.resource + wholeResource).coerceIn(0, maxOf(player.resource, regeneration.maxResource)),
        )
    }

    /** True once each time life falls to or below [threshold]; it must climb back above to fire again. */
    fun crossedLow(health: Int, maxHealth: Int, threshold: Float): Boolean {
        val low = maxHealth > 0 && health.toFloat() / maxHealth <= threshold
        val crossed = low && !wasLow
        wasLow = low
        return crossed
    }

    fun clear() {
        leechPool = 0f
        lifeCarry = 0f
        resourceCarry = 0f
        doses.clear()
        wasLow = false
    }
}
