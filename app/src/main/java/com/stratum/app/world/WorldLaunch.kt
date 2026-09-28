package com.stratum.app.world

import com.stratum.core.domain.session.WorldSummary
import com.stratum.core.domain.world.RulesPresets
import com.stratum.core.domain.world.WorldRules

/**
 * Everything needed to put the player in a world: which one, as whom, and
 * under what rules. The play route is built from this alone, so continuing a
 * saved world and starting a fresh one are the same road.
 */
data class WorldLaunch(
    val worldId: String,
    val name: String,
    /** Null plays whatever class the packs list first. */
    val heroClassId: String?,
    val presetName: String,
    val rules: WorldRules,
    val seed: Long,
    /** True when [worldId] names a saved world to resume rather than a new one to create. */
    val resume: Boolean,
) {
    companion object {
        /**
         * Resumes a saved world. The summary names its preset rather than
         * storing its rules, so a preset renamed or removed since falls back
         * to [fallbackRules] rather than refusing to open the world.
         */
        fun resume(summary: WorldSummary, fallbackRules: WorldRules): WorldLaunch = WorldLaunch(
            worldId = summary.id,
            name = summary.name,
            heroClassId = summary.heroClassId.ifBlank { null },
            presetName = summary.presetName,
            rules = RulesPresets.all.firstOrNull { it.name == summary.presetName }?.rules ?: fallbackRules,
            seed = summary.seed,
            resume = true,
        )
    }
}
