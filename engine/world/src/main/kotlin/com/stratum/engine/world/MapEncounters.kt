package com.stratum.engine.world

import com.stratum.core.domain.actor.EnemyDefinition
import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import kotlin.random.Random

/** A generator that built a hand-authored level, and can say where its author marked things. */
interface MarkedLevel {
    /** The level's markers, in world blocks. */
    val markers: List<PlacedMarker>
}

/** A map marker placed in the world. */
data class PlacedMarker(val marker: MapMarker, val x: Float, val y: Float)

/**
 * Who waits at each of a level's enemy markers.
 *
 * A marker naming a monster gets that monster, matched loosely because
 * authors name things their own way: `slime`, `Slime` and `forest:slime` all
 * find `forest:slime`. A marker naming nothing, or something no loaded pack
 * has, gets one of the monsters that live in the region, so every marker the
 * author placed is a fight.
 */
internal object MapEncounters {

    data class Encounter(val definition: EnemyDefinition, val at: PlacedMarker)

    fun plan(markers: List<PlacedMarker>, enemies: List<EnemyDefinition>, local: List<EnemyDefinition>, random: Random): List<Encounter> =
        markers.filter { it.marker.kind == MarkerKind.ENEMY_SPAWN }.mapNotNull { placed ->
            val definition = named(placed.marker.refId, enemies) ?: local.randomOrNull(random) ?: return@mapNotNull null
            Encounter(definition, placed)
        }

    private fun named(ref: String?, enemies: List<EnemyDefinition>): EnemyDefinition? {
        val wanted = ref?.let(::simplified)?.takeIf(String::isNotEmpty) ?: return null
        return enemies.firstOrNull { simplified(it.id) == wanted || simplified(it.id.substringAfter(':')) == wanted || simplified(it.name) == wanted }
    }

    private fun simplified(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)
}
