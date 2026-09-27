package com.stratum.engine.world

import com.stratum.core.domain.map.MapMarker
import com.stratum.core.domain.map.MarkerKind
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind

/**
 * A generated world's marker in the shape a hand-made level's markers take,
 * so [MapEncounters] can people a dungeon the way it peoples a Tiled map.
 *
 * A boss is a fight like any other to the encounter planner; what makes it a
 * boss is the monster its marker names. Loot, entrances and points of
 * interest are not fights, and come through as points of interest.
 */
fun WorldMarker.toPlacedMarker(): PlacedMarker {
    val kind = when (kind) {
        WorldMarkerKind.ENEMY_SPAWN, WorldMarkerKind.BOSS -> MarkerKind.ENEMY_SPAWN
        else -> MarkerKind.POINT_OF_INTEREST
    }
    // Centred on the block, as a map marker placed on a tile is.
    return PlacedMarker(MapMarker(kind, x + 0.5f, y + 0.5f, name = this.kind.name.lowercase(), refId = refId), x + 0.5f, y + 0.5f)
}
