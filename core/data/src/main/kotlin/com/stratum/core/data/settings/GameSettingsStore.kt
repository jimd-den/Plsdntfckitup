package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.stratum.core.domain.settings.GameSettings
import com.stratum.core.domain.settings.VoxelDestruction

/**
 * Keeps the player's [GameSettings] between runs, one preference per field.
 * A field never saved reads as its default; a number out of range is pulled back in.
 */
class GameSettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): GameSettings {
        val d = GameSettings()
        return GameSettings(
            safeTowns = prefs.getBoolean("safe_towns", d.safeTowns),
            townLife = prefs.getInt("town_life", d.townLife),
            questsPerTown = prefs.getInt("quests_per_town", d.questsPerTown),
            questDifficulty = prefs.getInt("quest_difficulty", d.questDifficulty),
            questChains = prefs.getBoolean("quest_chains", d.questChains),
            attacksPerMonster = prefs.getInt("attacks_per_monster", d.attacksPerMonster),
            distinctAttacks = prefs.getBoolean("distinct_attacks", d.distinctAttacks),
            voxelDestruction = prefs.getString("voxel_destruction", null)?.let { n -> VoxelDestruction.entries.firstOrNull { it.name == n } } ?: d.voxelDestruction,
            maxDebris = prefs.getInt("max_debris", d.maxDebris),
            ragdolls = prefs.getBoolean("ragdolls", d.ragdolls),
        ).coerced()
    }

    fun save(settings: GameSettings) {
        val s = settings.coerced()
        prefs.edit {
            putBoolean("safe_towns", s.safeTowns)
            putInt("town_life", s.townLife)
            putInt("quests_per_town", s.questsPerTown)
            putInt("quest_difficulty", s.questDifficulty)
            putBoolean("quest_chains", s.questChains)
            putInt("attacks_per_monster", s.attacksPerMonster)
            putBoolean("distinct_attacks", s.distinctAttacks)
            putString("voxel_destruction", s.voxelDestruction.name)
            putInt("max_debris", s.maxDebris)
            putBoolean("ragdolls", s.ragdolls)
        }
    }

    private companion object {
        const val FILE = "stratum_game_settings"
    }
}
