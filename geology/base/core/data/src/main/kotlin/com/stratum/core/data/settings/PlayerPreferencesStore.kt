package com.stratum.core.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.stratum.core.domain.session.PlayerLoadout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the player's active art choices (hero class, sprite sheet, weapon).
 *
 * Survives process death and app restarts so that characters crafted or selected
 * by the player remain active across sessions.
 */
class PlayerPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _loadout = MutableStateFlow(load())
    val loadout: StateFlow<PlayerLoadout> = _loadout.asStateFlow()

    fun load(): PlayerLoadout = PlayerLoadout(
        heroClassId = prefs.getString(KEY_HERO_CLASS, null),
        heroSheetId = prefs.getString(KEY_HERO_SHEET, null),
        equippedWeaponId = prefs.getString(KEY_EQUIPPED_WEAPON, null),
    )

    fun saveHeroClass(id: String?) {
        prefs.edit { putString(KEY_HERO_CLASS, id) }
        _loadout.value = _loadout.value.copy(heroClassId = id)
    }

    fun saveHeroSheet(id: String?) {
        prefs.edit { putString(KEY_HERO_SHEET, id) }
        _loadout.value = _loadout.value.copy(heroSheetId = id)
    }

    fun saveEquippedWeapon(id: String?) {
        prefs.edit { putString(KEY_EQUIPPED_WEAPON, id) }
        _loadout.value = _loadout.value.copy(equippedWeaponId = id)
    }

    fun save(loadout: PlayerLoadout) {
        prefs.edit {
            putString(KEY_HERO_CLASS, loadout.heroClassId)
            putString(KEY_HERO_SHEET, loadout.heroSheetId)
            putString(KEY_EQUIPPED_WEAPON, loadout.equippedWeaponId)
        }
        _loadout.value = loadout
    }

    private companion object {
        const val FILE = "stratum_player_preferences"
        const val KEY_HERO_CLASS = "hero_class_id"
        const val KEY_HERO_SHEET = "hero_sheet_id"
        const val KEY_EQUIPPED_WEAPON = "equipped_weapon_id"
    }
}
