package com.stratum.engine.world

import com.stratum.core.domain.combat.CombatRules
import com.stratum.core.domain.status.DotTick
import com.stratum.core.domain.status.StatusApplication
import com.stratum.core.domain.status.StatusBook
import com.stratum.core.domain.status.StatusSet

/**
 * Every body's statuses, keyed by actor id: the player's under
 * [WorldSession.PLAYER_ACTOR_ID], each monster's under its instance id.
 *
 * Kept beside the bodies rather than on them because the player's state
 * belongs to more systems than combat, and a burn is combat's business
 * alone. The sets themselves are immutable; this only holds the current one.
 */
internal class StatusSystem(private val book: StatusBook, private val rules: CombatRules) {

    private val sets = HashMap<String, StatusSet>()

    fun of(actorId: String): StatusSet = sets[actorId] ?: StatusSet.EMPTY

    /** Everything carried, for the snapshot. */
    fun all(): Map<String, StatusSet> = sets.filterValues { !it.isEmpty }

    /** Applies [application] to [actorId]; returns false when the status is unknown. */
    fun apply(actorId: String, application: StatusApplication): Boolean {
        val definition = book[application.statusId] ?: return false
        sets[actorId] = of(actorId).applying(definition, application, rules.maxStatusStacks)
        return true
    }

    fun remove(actorId: String, statusId: String) {
        sets[actorId]?.let { sets[actorId] = it.removing(statusId) }
    }

    /** Clears every harmful status, for a cleansing flask. */
    fun cleanse(actorId: String) {
        val set = sets[actorId] ?: return
        sets[actorId] = StatusSet(set.instances.filterNot { book[it.statusId]?.isDebuff == true })
    }

    /**
     * Advances every clock by [deltaSeconds] and returns the damage over time
     * dealt across it, before the defenders' resistances.
     */
    fun advance(deltaSeconds: Float): List<Pair<String, List<DotTick>>> {
        val ticks = sets.mapNotNull { (id, set) -> set.dotTicks(deltaSeconds, book).takeIf { it.isNotEmpty() }?.let { id to it } }
        val iterator = sets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val advanced = entry.value.advanced(deltaSeconds)
            if (advanced.isEmpty) iterator.remove() else entry.setValue(advanced)
        }
        return ticks
    }

    fun forget(actorId: String) {
        sets.remove(actorId)
    }

    /** Keeps only the ids still in play, so the dead and despawned do not tick forever. */
    fun retain(actorIds: Set<String>) {
        sets.keys.retainAll(actorIds)
    }

    fun clear() = sets.clear()
}
