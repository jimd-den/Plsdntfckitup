package com.stratum.core.domain.combat

/**
 * A flask: a limited, refillable answer to a bad moment.
 *
 * Charges come back by killing, not by waiting, which is the whole point --
 * a flask rewards pressing forward and punishes hiding. Packs define what a
 * flask restores and what it grants; the engine owns the refilling.
 */
data class FlaskDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val maxCharges: Int = 30,
    val chargesPerUse: Int = 10,
    /** Charges a minion's death gives; stronger ranks give more. */
    val chargesPerKill: Int = 2,
    /** Life restored: flat plus a share of maximum. */
    val life: Int = 0,
    val lifeShare: Float = 0f,
    val resource: Int = 0,
    /** Seconds the restoration is spread over; 0 is instant. */
    val recoverySeconds: Float = 0f,
    /** A status the drinker gains: the buff half of a utility flask. */
    val statusId: String? = null,
    /** Ends damage over time and slows on the drinker when drunk, when set. */
    val cleanses: Boolean = false,
    val color: Long = 0xFFD2544B,
    val glyph: String = "⚱",
) {
    init {
        require(maxCharges >= 1 && chargesPerUse in 1..maxCharges) { "flask '$id' must hold at least one use" }
        require(chargesPerKill >= 0 && recoverySeconds >= 0f) { "flask '$id' has a negative number" }
    }
}
