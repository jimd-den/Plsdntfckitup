package com.stratum.app.world

import com.stratum.core.domain.session.WorldIdentity
import com.stratum.core.domain.world.WorldRules

/**
 * What to put the player in: a saved world to resume, or a new one to start.
 * The play route is built from this alone.
 */
sealed interface WorldLaunch {
    val worldId: String

    /**
     * A saved world, by id. Its seed, rules, hero class and hero all come
     * from the save, which the play route loads off the main thread.
     */
    data class Resume(override val worldId: String, val heroClassId: String?) : WorldLaunch

    /** A new world: its slot in the library, who plays it and how it plays. */
    data class New(
        val identity: WorldIdentity,
        /** Null plays whatever class the packs list first. */
        val heroClassId: String?,
        val rules: WorldRules,
        val seed: Long,
    ) : WorldLaunch {
        override val worldId: String get() = identity.id
    }
}
