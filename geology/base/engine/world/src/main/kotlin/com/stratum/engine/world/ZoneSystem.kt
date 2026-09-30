package com.stratum.engine.world

import com.stratum.core.domain.world.WorldPoint

/**
 * A patch of ground doing something: burning, a trap waiting, a totem
 * pulsing. The lite version of traps and totems -- no body to kill, just a
 * place and a clock -- which covers most of what those skills are for.
 */
data class Zone(
    val id: Long,
    val skillId: String,
    val casterId: String,
    val side: CombatSide,
    val position: WorldPoint,
    val radius: Float,
    val remaining: Float,
    val pulseSeconds: Float,
    /** Counts down to the next pulse. */
    val nextPulse: Float = 0f,
    val isTrap: Boolean = false,
    val depth: Int = 0,
    val color: Long = 0xFFFFFFFF,
)

/** A zone pulsing over the bodies in it. */
internal data class ZonePulse(val zone: Zone, val targetIds: List<String>)

internal class ZoneSystem {

    private val zones = mutableListOf<Zone>()
    private var nextId = 0L

    val active: List<Zone> get() = zones.toList()

    fun place(zone: Zone) {
        if (zones.size >= MAX_ZONES) zones.removeAt(0)
        zones += zone.copy(id = nextId++)
    }

    /**
     * Ages every zone; returns the pulses due. A trap pulses once, when
     * something first stands in it, and is gone; anything else pulses on its
     * clock until it runs out.
     */
    fun advance(deltaSeconds: Float, targets: (CombatSide) -> List<Candidate>): List<ZonePulse> {
        if (zones.isEmpty()) return emptyList()
        val pulses = mutableListOf<ZonePulse>()
        val kept = zones.mapNotNull { zone ->
            val inside = targets(zone.side).filter { it.position.horizontalDistanceTo(zone.position) <= zone.radius }.map { it.id }
            val aged = zone.copy(remaining = zone.remaining - deltaSeconds, nextPulse = zone.nextPulse - deltaSeconds)
            when {
                zone.isTrap && inside.isNotEmpty() -> {
                    pulses += ZonePulse(zone, inside)
                    null
                }
                zone.isTrap -> aged.takeIf { it.remaining > 0f }
                aged.nextPulse <= 0f -> {
                    if (inside.isNotEmpty()) pulses += ZonePulse(zone, inside)
                    aged.copy(nextPulse = aged.nextPulse + zone.pulseSeconds).takeIf { it.remaining > 0f }
                }
                else -> aged.takeIf { it.remaining > 0f }
            }
        }
        zones.clear()
        zones += kept
        return pulses
    }

    fun clear() = zones.clear()

    private companion object {
        const val MAX_ZONES = 64
    }
}
