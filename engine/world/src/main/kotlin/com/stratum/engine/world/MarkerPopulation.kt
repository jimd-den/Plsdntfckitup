package com.stratum.engine.world

import com.stratum.core.domain.world.ChunkPos
import com.stratum.core.domain.world.MarkedWorld
import com.stratum.core.domain.world.WorldMarker
import com.stratum.core.domain.world.WorldMarkerKind
import com.stratum.core.domain.world.WorldPoint
import kotlin.math.abs

/**
 * Which of a generated world's markers are waiting to be peopled, and which
 * have been.
 *
 * Markers are learnt as their chunks stream in and forgotten as they stream
 * out, so only the resident world is ever held. A marker wakes when the
 * player comes near it -- near in height too, so a dungeon ten blocks down
 * stays empty while the player walks over it and fills as they descend -- and
 * is then consumed for the rest of the session: walking away and back, which
 * unloads and reloads its chunk, does not refill a cleared room.
 *
 * Waking near the player rather than at load keeps the population inside
 * the director's reach; a monster placed at the edge of the loaded world
 * would be forgotten by the director on its next tick.
 */
internal class MarkerPopulation(private val marked: MarkedWorld) {

    private val waiting = HashMap<ChunkPos, List<WorldMarker>>()
    private val resident = HashSet<ChunkPos>()
    /** Consumed markers by [key], so the set can be saved and read back without the chunks that hold them. */
    private val consumed = HashSet<String>()
    private var seenResidency = -1

    /** Markers waiting in resident chunks, for tests and a map. */
    val pending: List<WorldMarker> get() = waiting.values.flatten()

    /** Whether [marker] has already been peopled this session. */
    fun isConsumed(marker: WorldMarker): Boolean = marker.key in consumed

    /** Every marker peopled so far, as the keys a save keeps. */
    val consumedKeys: Set<String> get() = consumed.toSet()

    /** Marks saved markers as already peopled. Before the first [follow], so none of them is ever listed as waiting. */
    fun restore(keys: Collection<String>) {
        consumed += keys
        waiting.replaceAll { _, markers -> markers.filterNot { it.key in consumed } }
        waiting.values.removeAll { it.isEmpty() }
    }

    /**
     * Brings the waiting list in line with the resident chunks. [residency]
     * is the world's change counter; when it has not moved, nothing is done.
     */
    fun follow(residency: Int, chunks: () -> Collection<ChunkPos>) {
        if (residency == seenResidency) return
        seenResidency = residency
        val now = chunks().toHashSet()
        resident.filterNot(now::contains).forEach { pos ->
            resident -= pos
            waiting -= pos
        }
        now.filterNot(resident::contains).forEach { pos ->
            resident += pos
            val fresh = marked.markersIn(pos).filterNot { it.key in consumed }
            if (fresh.isNotEmpty()) waiting[pos] = fresh
        }
    }

    /**
     * Consumes and returns the markers close enough to [player] to wake,
     * nearest first. At most [monsterRoom] of them are monsters; the rest
     * keep waiting, so a cave full of spawn markers fills as the fight thins
     * rather than all at once. Loot and landmarks are never held back.
     */
    fun wake(player: WorldPoint, monsterRoom: Int): List<WorldMarker> {
        if (waiting.isEmpty()) return emptyList()
        val near = waiting.values.asSequence().flatten()
            .filter { abs(it.z - player.z) <= WAKE_DEPTH && it.centre.horizontalDistanceTo(player) <= WAKE_RADIUS }
            .sortedBy { it.centre.horizontalDistanceTo(player) }
            .toList()
        if (near.isEmpty()) return emptyList()
        var room = monsterRoom
        val woken = near.filter { marker ->
            if (!marker.isMonster) return@filter true
            (room > 0).also { if (it) room-- }
        }
        woken.mapTo(consumed) { it.key }
        val gone = woken.toHashSet()
        waiting.replaceAll { _, markers -> markers.filterNot(gone::contains) }
        waiting.values.removeAll { it.isEmpty() }
        return woken
    }

    companion object {
        /** How close, across the ground, the player comes before a marker wakes. */
        const val WAKE_RADIUS = 20f

        /** How far above or below the player a marker may be and still wake. */
        const val WAKE_DEPTH = 6f
    }
}

/**
 * A marker's identity across sessions: what it is, where, and what placed
 * it. Markers are a function of the seed and the chunk, so the same world
 * names the same marker the same way every time it is generated.
 */
internal val WorldMarker.key: String get() = "${kind.name}@$x,$y,$z#$sourceId${refId?.let { "/$it" }.orEmpty()}"

/** The middle of the marked block, at standing height. */
internal val WorldMarker.centre: WorldPoint get() = WorldPoint(x + 0.5f, y + 0.5f, z.toFloat())

internal val WorldMarker.isMonster: Boolean get() = kind == WorldMarkerKind.ENEMY_SPAWN || kind == WorldMarkerKind.BOSS
