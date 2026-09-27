package com.stratum.core.domain.world

/** What a generated spot is for. */
enum class WorldMarkerKind {
    /** A monster waits here. */
    ENEMY_SPAWN,

    /** The one worth the trip. */
    BOSS,

    /** A chest, a cache, an offering. */
    LOOT,

    /** Where a structure is entered from the surface. */
    ENTRANCE,

    /** Something worth showing on a map. */
    POINT_OF_INTEREST,
}

/**
 * A spot the generator marked for the game layer: where a dungeon's monsters
 * stand, its boss room, its loot.
 *
 * In world blocks, standing height included: [z] is the air cell a body
 * occupies, one above the floor. [refId] is whatever the structure named --
 * a monster, a loot table -- or null to let the region decide.
 */
data class WorldMarker(
    val kind: WorldMarkerKind,
    val x: Int,
    val y: Int,
    val z: Int,
    /** The structure or pass that placed it. */
    val sourceId: String,
    val refId: String? = null,
)

/**
 * A generated world that can say where it marked things.
 *
 * The streamed counterpart of a hand-made level's markers: a generated world
 * has no end, so markers are asked for by chunk rather than listed. Answers
 * are functions of the seed and the chunk, like the terrain, so asking
 * before, after or instead of generating the chunk gives the same list.
 */
interface MarkedWorld {
    fun markersIn(pos: ChunkPos): List<WorldMarker>

    /** Markers within [radius] blocks of a column, from every chunk that could hold one. */
    fun markersNear(x: Int, y: Int, radius: Int): List<WorldMarker> {
        val min = ChunkPos.containing(x - radius, y - radius)
        val max = ChunkPos.containing(x + radius, y + radius)
        val reach = radius.toLong() * radius
        return (min.y..max.y).flatMap { cy ->
            (min.x..max.x).flatMap { cx ->
                markersIn(ChunkPos(cx, cy)).filter { m ->
                    val dx = (m.x - x).toLong()
                    val dy = (m.y - y).toLong()
                    dx * dx + dy * dy <= reach
                }
            }
        }
    }
}
