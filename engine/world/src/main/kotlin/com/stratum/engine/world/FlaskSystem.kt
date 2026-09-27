package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyRank
import com.stratum.core.domain.combat.FlaskDefinition

/** A flask on the belt, as the HUD draws it. */
data class FlaskView(val definition: FlaskDefinition, val charges: Int) {
    val uses: Int get() = charges / definition.chargesPerUse
    val canDrink: Boolean get() = charges >= definition.chargesPerUse
    val fill: Float get() = (charges.toFloat() / definition.maxCharges).coerceIn(0f, 1f)
}

/** What reaching for a flask did. */
sealed interface FlaskResult {
    data class Drunk(val flask: FlaskDefinition, val lifeRestored: Int, val resourceRestored: Int) : FlaskResult
    data class Empty(val flask: FlaskDefinition) : FlaskResult
    data object NoSuchFlask : FlaskResult
}

/** What a flask restores this instant, or over time. */
internal data class FlaskDose(val life: Float, val resource: Float, val seconds: Float)

/**
 * The belt: charges per flask, filled by kills, spent by drinking.
 *
 * Kept by the session rather than on the player, because charges are part
 * of this run -- a world entered fresh begins with a full belt.
 */
internal class FlaskSystem(flasks: List<FlaskDefinition>) {

    val belt: List<FlaskDefinition> = flasks.take(MAX_FLASKS)
    private val charges = belt.associateTo(HashMap()) { it.id to it.maxCharges }

    val views: List<FlaskView> get() = belt.map { FlaskView(it, charges[it.id] ?: 0) }

    /** Spends a use of the flask in [slot]; returns the dose, scaled by the drinker's flask effect, or why not. */
    fun drink(slot: Int, maxHealth: Int, effect: Float): Pair<FlaskResult, FlaskDose?> {
        val flask = belt.getOrNull(slot) ?: return FlaskResult.NoSuchFlask to null
        val held = charges[flask.id] ?: 0
        if (held < flask.chargesPerUse) return FlaskResult.Empty(flask) to null
        charges[flask.id] = held - flask.chargesPerUse
        val life = (flask.life + flask.lifeShare * maxHealth) * effect
        val resource = flask.resource * effect
        return FlaskResult.Drunk(flask, life.toInt(), resource.toInt()) to FlaskDose(life, resource, flask.recoverySeconds)
    }

    /** A kill refills every flask; tougher kills refill more. */
    fun onKill(rank: EnemyRank, gain: Float) {
        belt.forEach { flask ->
            val earned = (flask.chargesPerKill * rankWeight(rank) * gain).toInt()
            charges[flask.id] = ((charges[flask.id] ?: 0) + earned).coerceAtMost(flask.maxCharges)
        }
    }

    fun refill() = belt.forEach { charges[it.id] = it.maxCharges }

    /** Engine policy, like rank's other multipliers: a boss is worth a belt's worth of minions. */
    private fun rankWeight(rank: EnemyRank): Int = when (rank) {
        EnemyRank.MINION -> 1
        EnemyRank.ELITE -> 2
        EnemyRank.CHAMPION -> 4
        EnemyRank.BOSS -> 10
    }

    companion object {
        /** Five slots, the number a thumb can reach without looking. */
        const val MAX_FLASKS = 5
    }
}
